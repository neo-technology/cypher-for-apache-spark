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
package org.opencypher.spark.impl.physical.operators

import org.apache.spark.sql.DataFrame
import org.opencypher.okapi.api.graph.QualifiedGraphName
import org.opencypher.okapi.api.types._
import org.opencypher.okapi.impl.exception.IllegalArgumentException
import org.opencypher.okapi.relational.api.physical.PhysicalOperator
import org.opencypher.okapi.relational.impl.table.{IRecordHeader, RecordSlot}
import org.opencypher.okapi.trees.AbstractTreeNode
import org.opencypher.spark.api.CAPSSession
import org.opencypher.spark.impl.CAPSConverters._
import org.opencypher.spark.impl.DataFrameOps._
import org.opencypher.spark.impl.physical.{CAPSPhysicalResult, CAPSRuntimeContext}
import org.opencypher.spark.impl.{CAPSGraph, CAPSRecords}

private[spark] abstract class CAPSPhysicalOperator
  extends AbstractTreeNode[CAPSPhysicalOperator]
  with PhysicalOperator[CAPSRecords, CAPSGraph, CAPSRuntimeContext] {

  override def header: IRecordHeader

  override def execute(implicit context: CAPSRuntimeContext): CAPSPhysicalResult

  protected def resolve(qualifiedGraphName: QualifiedGraphName)(implicit context: CAPSRuntimeContext): CAPSGraph = {
    context.resolve(qualifiedGraphName).map(_.asCaps).getOrElse(throw IllegalArgumentException(s"a graph at $qualifiedGraphName"))
  }

  protected def resolveTags(qgn: QualifiedGraphName)(implicit context: CAPSRuntimeContext): Set[Int] = context.patternGraphTags.getOrElse(qgn, resolve(qgn).tags)

  override def args: Iterator[Any] = super.args.flatMap {
    case IRecordHeader | Some(IRecordHeader) => None
    case other                               => Some(other)
  }
}

object CAPSPhysicalOperator {
  def joinRecords(
      header: IRecordHeader,
      joinSlots: Seq[(RecordSlot, RecordSlot)],
      joinType: String = "inner",
      deduplicate: Boolean = false)(lhs: CAPSRecords, rhs: CAPSRecords): CAPSRecords = {

    val lhsData = lhs.toDF()
    val rhsData = rhs.toDF()

    val joinCols = joinSlots.map { case (left, right) => header.of(left) -> header.of(right) }

    // TODO: the join produced corrupt data when the previous operator was a cross. We work around that by using a
    // subsequent select. This can be removed, once https://issues.apache.org/jira/browse/SPARK-23855 is solved or we
    // upgrade to Spark 2.3.0
    val potentiallyCorruptedResult = joinDFs(lhsData, rhsData, header, joinCols)(joinType, deduplicate)(lhs.caps)
    val select = potentiallyCorruptedResult.data.select("*")
    CAPSRecords.verifyAndCreate(header, select)(lhs.caps)
  }

  def joinDFs(lhsData: DataFrame, rhsData: DataFrame, header: IRecordHeader, joinCols: Seq[(String, String)])(
      joinType: String,
      deduplicate: Boolean)(implicit caps: CAPSSession): CAPSRecords = {

    val joinedData = lhsData.safeJoin(rhsData, joinCols, joinType)

    val returnData = if (deduplicate) {
      val colsToDrop = joinCols.map(col => col._2)
      joinedData.safeDropColumns(colsToDrop: _*)
    } else joinedData

    CAPSRecords.verifyAndCreate(header, returnData)
  }

  def assertIsNode(slot: RecordSlot): Unit = {
    slot.content.cypherType match {
      case CTNode(_, _) =>
      case x =>
        throw IllegalArgumentException(s"Expected $slot to contain a node, but was $x")
    }
  }

}
