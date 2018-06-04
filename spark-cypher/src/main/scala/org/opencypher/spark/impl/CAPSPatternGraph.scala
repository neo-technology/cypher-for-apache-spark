/*
 * Copyright (c) 2016-2018 "Neo4j Sweden, AB" [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Attribution Notice under the terms of the Apache License 2.0
 *
 * This work was created by the collective efforts of the openCypher community.
 * Without limiting the terms of Section 6, any Derivative Work that is not
 * approved by the public consensus process of the openCypher Implementers Group
 * should not be described as “Cypher” (and Cypher® is a registered trademark of
 * Neo4j Inc.) or as "openCypher". Extensions by implementers or prototypes or
 * proposals for change that have been documented or implemented should only be
 * described as "implementation extensions to Cypher" or as "proposed changes to
 * Cypher that are not yet approved by the openCypher community".
 */
package org.opencypher.spark.impl

import org.apache.spark.storage.StorageLevel
import org.opencypher.okapi.api.types.{CTNode, CTRelationship}
import org.opencypher.okapi.ir.api.expr._
import org.opencypher.okapi.relational.api.schema.RelationalSchema._
import org.opencypher.okapi.relational.impl.table.RecordHeaderNew
import org.opencypher.spark.api.CAPSSession
import org.opencypher.spark.impl.table.CAPSRecordHeader._
import org.opencypher.spark.schema.CAPSSchema

/**
  * A pattern graph represents the result of CONSTRUCT clause. It contains all entities from the outer scope that the
  * clause constructs. The initial schema of that graph is the union of all graph schemata the CONSTRUCT clause refers
  * to, including their corresponding graph tags. Note, that the initial schema does not include the graph tag used for
  * the constructed entities.
  */
case class CAPSPatternGraph(
  private[spark] val baseTable: CAPSRecords,
  override val schema: CAPSSchema,
  override val tags: Set[Int]
)(implicit val session: CAPSSession)
    extends CAPSGraph {

  private val header = baseTable.header

  def show(): Unit = baseTable.data.show()

  override def cache(): CAPSPatternGraph = map(_.cache())

  override def persist(): CAPSPatternGraph = map(_.persist())

  override def persist(storageLevel: StorageLevel): CAPSPatternGraph = map(_.persist(storageLevel))

  override def unpersist(): CAPSPatternGraph = map(_.unpersist())

  override def unpersist(blocking: Boolean): CAPSPatternGraph = map(_.unpersist(blocking))

  private def map(f: CAPSRecords => CAPSRecords) =
    CAPSPatternGraph(f(baseTable), schema, tags)

  override def nodes(name: String, nodeCypherType: CTNode): CAPSRecords = {
    val targetNode = Var(name)(nodeCypherType)
    val nodeSchema = schema.forNode(nodeCypherType.labels)
    val targetNodeHeader = nodeSchema.headerForNode(targetNode)
    val extractionNodes: Set[Var] = header.nodesForType(nodeCypherType)

    extractRecordsFor(targetNode, targetNodeHeader, extractionNodes)
  }

  override def relationships(name: String, relCypherType: CTRelationship): CAPSRecords = {
    val targetRel = Var(name)(relCypherType)
    val relSchema = schema.forRelationship(relCypherType)
    val targetRelHeader = relSchema.headerForRelationship(targetRel)
    val extractionRels = header.relationshipsForType(relCypherType)

    extractRecordsFor(targetRel, targetRelHeader, extractionRels)
  }

  private def extractRecordsFor(targetVar: Var, targetHeader: RecordHeaderNew, extractionVars: Set[Var]): CAPSRecords = {
    val extractionExpressions = extractionVars.map(candidate => candidate -> header.ownedBy(candidate)).toMap

    val relColumnsLookupTables = extractionExpressions.map {
      case (relVar, expressionsForRel) =>
        relVar -> createScanToBaseTableLookup(targetHeader, targetVar, expressionsForRel)
    }

    val extractedDf = baseTable
      .toDF()
      .flatMap(RowExpansion(targetHeader, targetVar, extractionExpressions, relColumnsLookupTables))(targetHeader.rowEncoder)

    val distinctData = extractedDf.dropDuplicates(targetHeader.column(targetVar))

    CAPSRecords.verifyAndCreate(targetHeader, distinctData)
  }

  private def createScanToBaseTableLookup(targetHeader: RecordHeaderNew, scanTableVar: Var, baseTableExpressions: Set[Expr]): Map[String, String] = {
    baseTableExpressions.flatMap { baseTableExpression =>
      val scanTableExpression = baseTableExpression.withOwner(scanTableVar)

      if (targetHeader.contains(scanTableExpression)) {
        Some(targetHeader.column(scanTableExpression) -> header.column(baseTableExpression))
      } else {
        None
      }
    }.toMap
  }
}
