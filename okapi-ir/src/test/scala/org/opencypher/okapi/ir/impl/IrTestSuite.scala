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
package org.opencypher.okapi.ir.impl

import org.opencypher.okapi.api.graph.{GraphName, QualifiedGraphName}
import org.opencypher.okapi.api.schema.Schema
import org.opencypher.okapi.api.value.CypherValue._
import org.opencypher.okapi.ir.api._
import org.opencypher.okapi.ir.api.block._
import org.opencypher.okapi.ir.api.expr.Expr
import org.opencypher.okapi.ir.api.pattern.Pattern
import org.opencypher.okapi.ir.impl.parse.CypherParser
import org.opencypher.okapi.testing.BaseTestSuite
import org.opencypher.v9_0.ast.semantics.SemanticState

import scala.language.implicitConversions
import scala.reflect.ClassTag

abstract class IrTestSuite extends BaseTestSuite {

  def unsupportedViewInstantiation(qgn: QualifiedGraphName, params: List[CypherString]) =
    throw new Exception("View instantiation is unsupported")

  def testGraph()(implicit schema: Schema = testGraphSchema) =
    IRCatalogGraph(testQualifiedGraphName, schema)

  def leafBlock: SourceBlock = SourceBlock(testGraph)

  val graphBlock: SourceBlock = SourceBlock(testGraph)

  def project(
    fields: Fields,
    after: List[Block] = List(leafBlock),
    given: Set[Expr] = Set.empty) =
    ProjectBlock(after, fields, given, testGraph)

  protected def matchBlock(pattern: Pattern): Block =
    MatchBlock(List(leafBlock), pattern, Set.empty, false, testGraph)

  def irFor(root: Block): CypherQuery = {
    val result = TableResultBlock(
      after = List(root),
      binds = OrderedFields(),
      graph = testGraph
    )
    val model = QueryModel(result, CypherMap.empty)
    CypherQuery(QueryInfo("test"), model)
  }

  case class DummyBlock(override val after: List[Block] = List.empty) extends BasicBlock[DummyBinds[Expr]](BlockType("dummy")) {
    override def binds: DummyBinds[Expr] = DummyBinds[Expr]()

    override def where: Set[Expr] = Set.empty[Expr]

    override val graph: IRCatalogGraph = testGraph
  }

  case class DummyBinds[E](fields: Set[IRField] = Set.empty) extends Binds

  implicit class RichString(queryText: String) {
    def parseIR[T <: CypherStatement : ClassTag](graphsWithSchema: (GraphName, Schema)*)
      (implicit schema: Schema = Schema.empty): T =
      ir(graphsWithSchema: _ *) match {
        case cq: T => cq
        case other => throw new IllegalArgumentException(s"Cannot convert $other")
      }

    def asCypherQuery(graphsWithSchema: (GraphName, Schema)*)(implicit schema: Schema = Schema.empty): CypherQuery =
      parseIR[CypherQuery](graphsWithSchema: _*)

    def ir(graphsWithSchema: (GraphName, Schema)*)(implicit schema: Schema = Schema.empty): CypherStatement = {
      val stmt = CypherParser(queryText)(CypherParser.defaultContext)
      val parameters = Map.empty[String, CypherValue]
      IRBuilder(stmt)(
        IRBuilderContext.initial(
          queryText,
          parameters,
          SemanticState.clean,
          testGraph()(schema),
          qgnGenerator,
          Map.empty.withDefaultValue(testGraphSource(graphsWithSchema :+ (testGraphName -> schema): _*)),
          unsupportedViewInstantiation
        ))
    }

    def irWithParams(params: (String, CypherValue)*)(implicit schema: Schema = Schema.empty): CypherStatement = {
      val stmt = CypherParser(queryText)(CypherParser.defaultContext)
      IRBuilder(stmt)(
        IRBuilderContext.initial(queryText,
          params.toMap,
          SemanticState.clean,
          testGraph()(schema),
          qgnGenerator,
          Map.empty.withDefaultValue(testGraphSource(testGraphName -> schema)),
          unsupportedViewInstantiation
        )
      )
    }
  }

  object IRBuilderHelper {
    val emptyIRBuilderContext: IRBuilderContext =
      IRBuilderContext.initial(
        "",
        Map.empty[String, CypherValue],
        SemanticState.clean,
        testGraph()(Schema.empty),
        qgnGenerator,
        Map.empty,
        unsupportedViewInstantiation
      )
  }
}
