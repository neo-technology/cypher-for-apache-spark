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
package org.opencypher.spark.impl.io.neo4j

import org.apache.spark.rdd.RDD
import org.neo4j.driver.internal.{InternalNode, InternalRelationship}
import org.opencypher.okapi.api.schema.PropertyKeys.PropertyKeys
import org.opencypher.okapi.api.schema.{PropertyKeys, Schema}
import org.opencypher.okapi.api.types.CypherType._
import org.opencypher.okapi.api.value.CypherValue
import org.opencypher.okapi.neo4j.io.Neo4jConfig
import org.opencypher.spark.api.CAPSSession
import org.opencypher.spark.impl.io.neo4j.external.Neo4j
import org.opencypher.spark.schema.CAPSSchema._

import scala.collection.JavaConverters._

object Neo4jGraphLoader {

  def loadSchema(config: Neo4jConfig, nodeQ: String, relQ: String)(implicit caps: CAPSSession): Schema = {
    val (nodes, rels) = loadRDDs(config, nodeQ, relQ)

    loadSchema(nodes, rels)
  }

  def loadSchema(nodes: RDD[InternalNode], rels: RDD[InternalRelationship]): Schema = {

    def computeNodeSchema(schema: Schema, node: InternalNode): Schema = {
      val labels = node.labels().asScala.toSet

      schema.withNodePropertyKeys(labels, convertProperties(node.asMap()))
    }

    def computeRelSchema(schema: Schema, relationship: InternalRelationship): Schema =
      schema.withRelationshipPropertyKeys(relationship.`type`(), convertProperties(relationship.asMap()))

    val nodeSchema = nodes.aggregate(Schema.empty)(computeNodeSchema, _ ++ _)
    val completeSchema = rels.aggregate(nodeSchema)(computeRelSchema, _ ++ _)

    completeSchema.asCaps
  }

  private def convertProperties(properties: java.util.Map[String, AnyRef]): PropertyKeys = {
    if (properties.isEmpty) {
      PropertyKeys.empty
    } else {
      properties.asScala.map {
        case (k, v) => k -> CypherValue(v).cypherType
      }.toMap
    }
  }

  def fromNeo4j(config: Neo4jConfig)(implicit caps: CAPSSession): Neo4jGraph =
    fromNeo4j(config, "MATCH (n) RETURN n", "MATCH ()-[r]->() RETURN r")

  def fromNeo4j(config: Neo4jConfig, nodeQuery: String, relQuery: String)(implicit caps: CAPSSession): Neo4jGraph =
    fromNeo4j(config, nodeQuery, relQuery,  None)

  def fromNeo4j(
    config: Neo4jConfig,
    nodeQuery: String,
    relQuery: String,
    maybeSchema: Option[Schema] = None)(implicit caps: CAPSSession): Neo4jGraph = {
    val (nodes, rels) = loadRDDs(config, nodeQuery, relQuery)

    val schema = maybeSchema.getOrElse(loadSchema(nodes, rels)).asCaps

    new Neo4jGraph(schema, caps)(nodes, rels)
  }

  def loadRDDs(config: Neo4jConfig, nodeQ: String, relQ: String)(implicit caps: CAPSSession): (RDD[InternalNode], RDD[InternalRelationship]) = {
    val sparkSession = caps.sparkSession
    val neo4j = Neo4j(config, sparkSession)
    val nodes = neo4j.cypher(nodeQ).loadNodeRdds.map(row => row(0).asInstanceOf[InternalNode])
    val rels = neo4j.cypher(relQ).loadRowRdd.map(row => row(0).asInstanceOf[InternalRelationship])

    nodes -> rels
  }
}
