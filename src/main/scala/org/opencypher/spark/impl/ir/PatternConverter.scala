package org.opencypher.spark.impl.ir

import cats._
import cats.data.State
import cats.data.State._
import cats.instances.list._
import cats.syntax.flatMap._
import org.neo4j.cypher.internal.frontend.v3_3.SemanticDirection._
import org.neo4j.cypher.internal.frontend.v3_3.ast
import org.neo4j.cypher.internal.frontend.v3_3.ast.LabelName
import org.opencypher.spark.api.expr.Expr
import org.opencypher.spark.api.ir._
import org.opencypher.spark.api.ir.global.{GlobalsRegistry, Label, RelType}
import org.opencypher.spark.api.ir.pattern._
import org.opencypher.spark.api.types.{CTNode, CTRelationship}

import scala.annotation.tailrec

final class PatternConverter(val tokens: GlobalsRegistry) extends AnyVal {

  type Result[A] = State[Pattern[Expr], A]

  def convert(p: ast.Pattern, pattern: Pattern[Expr] = Pattern.empty): Pattern[Expr] =
    convertPattern(p).runS(pattern).value

  private def convertPattern(p: ast.Pattern): Result[Unit] =
    Foldable[List].sequence_[Result, Unit](p.patternParts.toList.map(convertPart))

  @tailrec
  private def convertPart(p: ast.PatternPart): Result[Unit] = p match {
    case _: ast.AnonymousPatternPart => stomp(convertElement(p.element))
    case ast.NamedPatternPart(_, part) => convertPart(part)
  }

  private def convertElement(p: ast.PatternElement): Result[Field] = p match {
    case ast.NodePattern(Some(v), labels: Seq[LabelName], None) =>
      for {
        entity <- pure(Field(v.name)(CTNode))
        _ <- modify[Pattern[Expr]](_.withEntity(entity, EveryNode(AllGiven(labels.map(l => Label(l.name)).toSet))))
      } yield entity

    case ast.RelationshipChain(left, ast.RelationshipPattern(Some(eVar), types, None, None, dir, _), right) =>
      for {
        source <- convertElement(left)
        target <- convertElement(right)
        rel <- pure(Field(eVar.name)(CTRelationship(types.map(_.name).toSet)))
        _ <- modify[Pattern[Expr]] { given =>
          val relTypes =
            if (types.isEmpty) AnyGiven[RelType]()
            else AnyGiven[RelType](types.map(t => RelType(t.name)).toSet)
          val registered = given.withEntity(rel, EveryRelationship(relTypes))

          Endpoints.apply(source, target) match {
            case ends: IdenticalEndpoints =>
              registered.withConnection(rel, CyclicRelationship(ends))

            case ends: DifferentEndpoints =>
              dir match {
                case OUTGOING =>
                  registered.withConnection(rel, DirectedRelationship(ends))

                case INCOMING =>
                  registered.withConnection(rel, DirectedRelationship(ends.flip))

                case BOTH =>
                  registered.withConnection(rel, UndirectedRelationship(ends))
              }
          }
        }
      } yield target

    case x => throw new NotImplementedError(s"Pattern conversion not yet done for $x")
  }

  private def stomp[T](result: Result[T]): Result[Unit] = result >> pure(())
}
