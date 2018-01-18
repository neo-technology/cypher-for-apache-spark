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
package org.opencypher.caps.test.support.creation.propertygraph

import java.util.concurrent.atomic.AtomicLong

import cats._
import cats.data.State
import cats.data.State._
import cats.instances.list._
import cats.syntax.all._
import org.neo4j.cypher.internal.frontend.v3_4.ast._
import org.neo4j.cypher.internal.util.v3_4.ASTNode
import org.neo4j.cypher.internal.v3_4.expressions._
import org.opencypher.caps.api.exception.{IllegalArgumentException, UnsupportedOperationException}
import org.opencypher.caps.ir.impl.parse.CypherParser

import scala.collection.TraversableOnce

object CAPSPropertyGraphFactory extends PropertyGraphFactory {

  type Result[A] = State[ParsingContext, A]

  def apply(createQuery: String, externalParams: Map[String, Any] = Map.empty): PropertyGraph = {
    val (ast, params, _) = CypherParser.process(createQuery)(CypherParser.defaultContext)
    val context = ParsingContext.fromParams(params ++ externalParams)

    ast match {
      case Query(_, SingleQuery(clauses)) => processClauses(clauses).runS(context).value.graph
    }
  }

  def processClauses(clauses: Seq[Clause]): Result[Unit] = {
    clauses match {
      case (head :: tail) =>
        head match {
          case Create(pattern) =>
            processPattern(pattern) >> processClauses(tail)

          case Unwind(expr, variable) =>
            for {
              values <- processValues(expr)
              _ <- modify[ParsingContext](_.protectScope)
              _ <- Foldable[List].sequence_[Result, Unit](values.map { v =>
                for {
                  _ <- modify[ParsingContext](_.updated(variable.name, v))
                  _ <- processClauses(tail)
                  _ <- modify[ParsingContext](_.restoreScope)
                } yield ()
              })
              _ <- modify[ParsingContext](_.popProtectedScope)
            } yield ()

          case other => throw UnsupportedOperationException(s"Processing clause: ${other.name}")
        }
      case _ => pure[ParsingContext, Unit](())
    }
  }

  def processPattern(pattern: Pattern): Result[Unit] = {
    val parts = pattern.patternParts.map {
      case EveryPath(element) => element
      case other              => throw UnsupportedOperationException(s"Processing pattern: ${other.getClass.getSimpleName}")
    }

    Foldable[List].sequence_[Result, GraphElement](parts.toList.map(pe => processPatternElement(pe)))
  }

  def processPatternElement(patternElement: ASTNode): Result[GraphElement] = {
    patternElement match {
      case NodePattern(Some(variable), labels, props) =>
        for {
          properties <- props match {
            case Some(expr: MapExpression) => extractProperties(expr)
            case Some(other)               => throw IllegalArgumentException("a NodePattern with MapExpression", other)
            case None                      => pure[ParsingContext, Map[String, Any]](Map.empty)
          }
          node <- inspect[ParsingContext, Node] { context =>
            context.variableMapping.get(variable.name) match {
              case Some(n: Node) => n
              case Some(other)   => throw IllegalArgumentException(s"a Node for variable ${variable.name}", other)
              case None          => Node(context.nextId, labels.map(_.name).toSet, properties)
            }
          }
          _ <- modify[ParsingContext] { context =>
            if (context.variableMapping.get(variable.name).isEmpty) {
              context.updated(variable.name, node)
            } else {
              context
            }
          }
        } yield node

      case RelationshipChain(first, RelationshipPattern(Some(variable), relType, None, props, direction, _), third) =>
        for {
          source <- processPatternElement(first)
          sourceId <- pure[ParsingContext, Long](source match {
            case Node(id, _, _)  => id
            case r: Relationship => r.endId
          })
          target <- processPatternElement(third)
          properties <- props match {
            case Some(expr: MapExpression) => extractProperties(expr)
            case Some(other)               => throw IllegalArgumentException("a RelationshipChain with MapExpression", other)
            case None                      => pure[ParsingContext, Map[String, Any]](Map.empty)
          }
          rel <- inspect[ParsingContext, Relationship] { context =>
            if (direction == SemanticDirection.OUTGOING)
              Relationship(context.nextId, sourceId, target.id, relType.head.name, properties)
            else if (direction == SemanticDirection.INCOMING)
              Relationship(context.nextId, target.id, sourceId, relType.head.name, properties)
            else throw IllegalArgumentException("a directed relationship", direction)
          }

          _ <- modify[ParsingContext](_.updated(variable.name, rel))
        } yield rel
    }
  }

  def extractProperties(expr: MapExpression): Result[Map[String, Any]] = {
    for {
      keys <- pure(expr.items.map(_._1.name))
      values <- expr.items.toList.traverse[Result, Any] {
        case (_, inner) => processExpr(inner)
      }
      res <- pure(keys.zip(values).toMap)
    } yield res
  }

  def processExpr(expr: Expression): Result[Any] = {
    for {
      res <- expr match {
        case Parameter(name, _)       => inspect[ParsingContext, Any](_.parameter(name))
        case Variable(name)           => inspect[ParsingContext, Any](_.variableMapping(name))
        case l: Literal               => pure[ParsingContext, Any](l.value)
        case ListLiteral(expressions) => expressions.toList.traverse[Result, Any](processExpr)
        case Property(variable: Variable, propertyKey) =>
          inspect[ParsingContext, Any]({ context =>
            context.variableMapping(variable.name) match {
              case a: GraphElement => a.properties(propertyKey.name)
              case other =>
                throw UnsupportedOperationException(s"Reading property from a ${other.getClass.getSimpleName}")
            }
          })
        case other =>
          throw UnsupportedOperationException(s"Processing expression of type ${other.getClass.getSimpleName}")
      }
    } yield res
  }

  def processValues(expr: Expression): Result[List[Any]] = {
    expr match {
      case ListLiteral(expressions) => expressions.toList.traverse[Result, Any](processExpr)

      case Variable(name) =>
        inspect[ParsingContext, List[Any]](_.variableMapping(name) match {
          case l: TraversableOnce[Any] => l.toList
          case other                   => throw IllegalArgumentException(s"a list value for variable $name", other)
        })

      case Parameter(name, _) =>
        inspect[ParsingContext, List[Any]](_.parameter(name) match {
          case l: TraversableOnce[Any] => l.toList
          case other                   => throw IllegalArgumentException(s"a list value for parameter $name", other)
        })

      case FunctionInvocation(_, FunctionName("range"), _, Seq(lb: IntegerLiteral, ub: IntegerLiteral)) =>
        pure[ParsingContext, List[Any]](List.range[Long](lb.value, ub.value + 1))

      case other => throw UnsupportedOperationException(s"Processing value of type ${other.getClass.getSimpleName}")
    }
  }
}

final case class ParsingContext(
    parameter: Map[String, Any],
    variableMapping: Map[String, Any],
    graph: PropertyGraph,
    protectedScopes: List[Map[String, Any]],
    idGenerator: AtomicLong) {

  def nextId: Long = idGenerator.getAndIncrement()

  def protectScope: ParsingContext = {
    copy(protectedScopes = variableMapping :: protectedScopes)
  }

  def restoreScope: ParsingContext = {
    copy(variableMapping = protectedScopes.head)
  }

  def popProtectedScope: ParsingContext = copy(protectedScopes = protectedScopes.tail)

  def updated(k: String, v: Any): ParsingContext = v match {
    case n: Node =>
      copy(graph = graph.updated(n), variableMapping = variableMapping.updated(k, n))

    case r: Relationship =>
      copy(graph = graph.updated(r), variableMapping = variableMapping.updated(k, r))

    case _ =>
      copy(variableMapping = variableMapping.updated(k, v))
  }
}

object ParsingContext {
  def fromParams(params: Map[String, Any]): ParsingContext =
    ParsingContext(params, Map.empty, PropertyGraph.empty, List.empty, new AtomicLong())
}
