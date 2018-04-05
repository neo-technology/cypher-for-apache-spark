/*
 * Copyright (c) 2016-2018 "Neo4j, Inc." [https://neo4j.com]
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
package org.opencypher.spark.impl.convert

import org.apache.spark.sql.Row
import org.opencypher.okapi.api.types.{CTNode, CTRelationship}
import org.opencypher.okapi.api.value.CypherValue._
import org.opencypher.okapi.api.value._
import org.opencypher.okapi.impl.exception.UnsupportedOperationException
import org.opencypher.okapi.ir.api.expr.Var
import org.opencypher.okapi.relational.impl.table.{ColumnName, RecordHeader}

final case class rowToCypherMap(header: RecordHeader) extends (Row => CypherMap) {
  override def apply(row: Row): CypherMap = {
    val values = header.internalHeader.fields.map { field =>
      field.name -> constructValue(row, field)
    }.toSeq

    CypherMap(values: _*)
  }

  // TODO: Validate all column types. At the moment null values are cast to the expected type...
  private def constructValue(row: Row, field: Var): CypherValue = {
    field.cypherType match {
      case _: CTNode =>
        collectNode(row, field)

      case _: CTRelationship =>
        collectRel(row, field)

      case _ =>
        val raw = row.getAs[Any](ColumnName.of(header.slotFor(field)))
        CypherValue(raw)
    }
  }

  private def collectNode(row: Row, field: Var): CypherValue = {
    val idValue = row.getAs[Any](ColumnName.of(header.slotFor(field)))
    idValue match {
      case null       => CypherNull
      case id: String   =>
        val labels = header
        .labelSlots(field)
        .mapValues { s =>
          row.getAs[Boolean](ColumnName.of(s))
        }
        .collect {
          case (h, b) if b =>
            h.label.name
        }
        .toSet

        val properties = header
          .propertySlots(field)
          .mapValues { s =>
            CypherValue(row.getAs[Any](ColumnName.of(s)))
          }
          .collect {
            case (p, v) if !v.isNull =>
              p.key.name -> v
          }

        CAPSNode(id, labels, properties)
      case invalidID => throw UnsupportedOperationException(s"CAPSNode ID has to be a Long instead of ${invalidID.getClass}")
    }
  }

  private def collectRel(row: Row, field: Var): CypherValue = {
    val idValue = row.getAs[Any](ColumnName.of(header.slotFor(field)))
    idValue match {
      case null       => CypherNull
      case id: String   =>
        val source = row.getAs[String](ColumnName.of(header.sourceNodeSlot(field)))
        val target = row.getAs[String](ColumnName.of(header.targetNodeSlot(field)))
        val typ = row.getAs[String](ColumnName.of(header.typeSlot(field)))
        val properties = header
          .propertySlots(field)
          .mapValues { s =>
            CypherValue.apply(row.getAs[Any](ColumnName.of(s)))
          }
          .collect {
            case (p, v) if !v.isNull =>
              p.key.name -> v
          }

        CAPSRelationship(id, source, target, typ, properties)
      case invalidID => throw UnsupportedOperationException(s"CAPSRelationship ID has to be a Long instead of ${invalidID.getClass}")
    }
  }
}
