/*
 * Copyright (c) 2016-2017 "Neo4j, Inc." [https://neo4j.com]
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
 */
package org.opencypher.caps.test.support.creation.caps

import org.apache.spark.sql.types.StructField
import org.opencypher.caps.api.schema.PropertyKeys.PropertyKeys
import org.opencypher.caps.api.schema.Schema
import org.opencypher.caps.api.spark.{CAPSGraph, CAPSSession}
import org.opencypher.caps.impl.spark.convert.toSparkType
import org.opencypher.caps.ir.impl.convert.toCypherType
import org.opencypher.caps.test.support.creation.propertygraph.{Node, PropertyGraph, Relationship}

trait CAPSGraphFactory {

  def apply(propertyGraph: PropertyGraph)(implicit caps: CAPSSession): CAPSGraph

  def name: String

  override def toString: String = name

  def computeSchema(propertyGraph: PropertyGraph): Schema = {
    def extractFromNode(n: Node) =
      n.labels -> n.properties.map {
        case (name, prop) => name -> toCypherType(prop)
      }

    def extractFromRel(r: Relationship) =
      r.relType -> r.properties.map {
        case (name, prop) => name -> toCypherType(prop)
      }

    val labelsAndProps = propertyGraph.nodes.map(extractFromNode)
    val typesAndProps = propertyGraph.relationships.map(extractFromRel)

    val schemaWithLabels = labelsAndProps.foldLeft(Schema.empty) {
      case (acc, (labels, props)) => acc.withNodePropertyKeys(labels, props)
    }

    typesAndProps.foldLeft(schemaWithLabels) {
      case (acc, (t, props)) => acc.withRelationshipPropertyKeys(t)(props.toSeq: _*)
    }
  }

  protected def getPropertyStructFields(propKeys: PropertyKeys): Seq[StructField] = {
    propKeys.foldLeft(Seq.empty[StructField]) {
      case (fields, key) => fields :+ StructField(key._1, toSparkType(key._2), key._2.isNullable)
    }
  }
}
