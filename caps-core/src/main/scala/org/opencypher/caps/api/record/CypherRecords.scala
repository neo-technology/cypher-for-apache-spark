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
package org.opencypher.caps.api.record

/**
  * Represents a table of records containing Cypher values.
  * Each column (or slot) in this table represents an evaluated Cypher expression.
  *
  * Slots that have been bound to a variable name are called <i>fields</i>.
  * @see [[RecordHeader]]
  */
trait CypherRecords extends CypherPrintable {

  type Data
  type Records <: CypherRecords

  /**
    * The header for this table, describing the slots stored.
    *
    * @return the header for this table.
    */
  def header: RecordHeader

  /**
    * The data structure that actually holds all tabular data.
    *
    * @return the underlying data of this table.
    */
  def data: Data

  /**
    * The explicitly named fields in this table.
    *
    * @return the set of fields in this table.
    * @see [[RecordHeader.fields]]
    */
  def fields: Set[String]

  /**
    * The explicitly named fields of this table, in the order they were defined.
    *
    * @return the sequence of fields of this table, in the order they were defined.
    * @see [[RecordHeader.fieldsInOrder]]
    */
  def fieldsInOrder: Seq[String]

  def contract[E <: EmbeddedEntity](entity: VerifiedEmbeddedEntity[E]): Records

  /**
    * Compacts this table by dropping all slots that are not fields, keeping only
    * the non-field slots as determined by the implicit argument.
    *
    * @param details the details to keep in addition to the fields.
    * @return a new table with only the fields and argument details of this table.
    */
  def compact(implicit details: RetainedDetails): Records
}
