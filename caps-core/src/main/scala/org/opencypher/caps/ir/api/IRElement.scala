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
package org.opencypher.caps.ir.api

import java.net.URI

import org.opencypher.caps.api.schema.Schema
import org.opencypher.caps.api.types._
import org.opencypher.caps.ir.api.pattern._

trait IRElement {
  def name: String
  def escapedName: String = name.replaceAll("`", "``")
}

final case class IRField(name: String)(val cypherType: CypherType = CTWildcard) extends IRElement {
  override def toString = s"$name :: $cypherType"

  def toTypedTuple: (String, CypherType) = name -> cypherType
}

trait IRGraph extends IRElement {
  def toNamedGraph: IRNamedGraph = IRNamedGraph(name, schema)

  def schema: Schema
}

final case class IRNamedGraph(name: String, schema: Schema) extends IRGraph {
  override def toNamedGraph: IRNamedGraph = this
}

final case class IRExternalGraph(name: String, schema: Schema, uri: URI) extends IRGraph

final case class IRPatternGraph[E](name: String, schema: Schema, pattern: Pattern[E]) extends IRGraph
