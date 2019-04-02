/*
 * Copyright (c) 2016-2019 "Neo4j Sweden, AB" [https://neo4j.com]
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

import org.opencypher.okapi.api.types.CypherType._
import org.opencypher.okapi.api.types._
import org.opencypher.okapi.impl.exception.NotImplementedException
import org.opencypher.okapi.ir.api._
import org.opencypher.okapi.ir.api.expr._
import org.opencypher.okapi.ir.impl.OperatorTyping._
import org.opencypher.okapi.ir.impl.parse.functions.FunctionExtensions
import org.opencypher.okapi.ir.impl.parse.{functions => f}
import org.opencypher.okapi.ir.impl.typer.{InvalidArgument, InvalidContainerAccess, MissingParameter, NoSuitableSignatureForExpr, SignatureConverter, UnTypedExpr}
import org.opencypher.v9_0.expressions.{OperatorExpression, RegexMatch, TypeSignatures, functions}
import org.opencypher.v9_0.{expressions => ast}

import scala.language.implicitConversions

object AddType {

  private implicit class RichCTList(val left: CTList) extends AnyVal {
    def listConcatJoin(right: CypherType): CypherType = (left, right) match {
      case (CTList(lInner), CTList(rInner)) => CTList(lInner join rInner)
      case (CTList(lInner), _) => CTList(lInner join right)
    }
  }

  def apply(lhs: CypherType, rhs: CypherType): Option[CypherType] = {
    val lookup = lhs.material -> rhs.material match {
      case (CTVoid, _) => CTNull
      case (_, CTVoid) => CTNull
      case (left: CTList, _) => left listConcatJoin rhs
      case (_, right: CTList) => right listConcatJoin lhs
      case (CTString, _) if rhs.subTypeOf(CTNumber) => CTString
      case (_, CTString) if lhs.subTypeOf(CTNumber) => CTString
      case (CTString, CTString) => CTString
      case (CTDuration, CTDuration) => CTDuration
      case (CTLocalDateTime, CTDuration) => CTLocalDateTime
      case (CTDuration, CTLocalDateTime) => CTLocalDateTime
      case (CTDate, CTDuration) => CTDate
      case (CTDuration, CTDate) => CTDate
      case (CTInteger, CTInteger) => CTInteger
      case (CTFloat, CTInteger) => CTFloat
      case (CTInteger, CTFloat) => CTFloat
      case (CTFloat, CTFloat) => CTFloat
      case (CTNumber, y) if y.subTypeOf(CTNumber) => CTNumber
      case (x, CTNumber) if x.subTypeOf(CTNumber) => CTNumber
      case _ => null
    }
    Option(lookup).map(_.asNullableAs(lhs.join(rhs)))
  }

}

final class ExpressionConverter(context: IRBuilderContext) {

  private def schema = context.workingGraph.schema

  private def parameterType(p: ast.Parameter): CypherType = {
    context.parameters.get(p.name) match {
      case None => throw MissingParameter(p.name)
      case Some(param) => param.cypherType
    }
  }

  def convert(e: ast.Expression): Expr = e match {
    case ast.Variable(name) => Var(name)(context.knownTypes.getOrElse(e, throw UnTypedExpr(e)))
    case p@ast.Parameter(name, _) => Param(name)(parameterType(p))

    // Literals
    case astExpr: ast.IntegerLiteral => IntegerLit(astExpr.value)
    case ast.StringLiteral(value) => StringLit(value)
    case _: ast.True => TrueLit
    case _: ast.False => FalseLit
    case ast.ListLiteral(exprs) =>
      val elements = exprs.map(convert).toList
      val elementType = elements.foldLeft(CTVoid: CypherType) { case (agg, nextExpr) => agg.join(nextExpr.cypherType) }
      ListLit(elements)(CTList(elementType))

    case ast.Property(m, ast.PropertyKeyName(name)) =>
      val entity = convert(m)
      val key = PropertyKey(name)
      entity.cypherType.material match {
        case CTVoid => NullLit
        // This means that the node can have any possible label combination, as the user did not specify any constraints
        case n: CTNode if n.labels.isEmpty =>
          val propertyType = schema.allCombinations
            .map(l => schema.nodePropertyKeyType(l, name).getOrElse(CTNull))
            .foldLeft(CTVoid: CypherType)(_ join _)
          // User specified label constraints - we can use those for type inference
          EntityProperty(entity, key)(propertyType)
        case CTNode(labels, _) =>
          val propertyType = schema.nodePropertyKeyType(labels, name).getOrElse(CTNull)
          EntityProperty(entity, key)(propertyType)
        case CTRelationship(types, _) =>
          val propertyType = schema.relationshipPropertyKeyType(types, name).getOrElse(CTNull)
          EntityProperty(entity, key)(propertyType)
        case _: CTMap =>
          MapProperty(entity, key)
        case CTDate =>
          DateProperty(entity, key)
        case CTLocalDateTime =>
          LocalDateTimeProperty(entity, key)
        case CTDuration =>
          DurationProperty(entity, key)
        case _ => throw InvalidContainerAccess(e)
      }

    // Predicates
    case ast.Ands(expressions) => Ands(expressions.map(convert))
    case ast.Ors(expressions) => Ors(expressions.map(convert))
    case ast.HasLabels(node, labels) =>
      val exprs = labels.map { l: ast.LabelName =>
        HasLabel(convert(node), Label(l.name))
      }
      if (exprs.size == 1) exprs.head else Ands(exprs.toSet)
    case ast.Not(expr) => Not(convert(expr))
    case ast.Equals(f: ast.FunctionInvocation, s: ast.StringLiteral) if f.function == functions.Type =>
      HasType(convert(f.args.head), RelType(s.value))
    case ast.Equals(lhs, rhs) => Equals(convert(lhs), convert(rhs))
    case ast.LessThan(lhs, rhs) => LessThan(convert(lhs), convert(rhs))
    case ast.LessThanOrEqual(lhs, rhs) => LessThanOrEqual(convert(lhs), convert(rhs))
    case ast.GreaterThan(lhs, rhs) => GreaterThan(convert(lhs), convert(rhs))
    case ast.GreaterThanOrEqual(lhs, rhs) => GreaterThanOrEqual(convert(lhs), convert(rhs))
    // if the list only contains a single element, convert to simple equality to avoid list construction
    case ast.In(lhs, ast.ListLiteral(elems)) if elems.size == 1 => Equals(convert(lhs), convert(elems.head))
    case ast.In(lhs, rhs) => In(convert(lhs), convert(rhs))
    case ast.IsNull(expr) => IsNull(convert(expr))
    case ast.IsNotNull(expr) => IsNotNull(convert(expr))
    case ast.StartsWith(lhs, rhs) => StartsWith(convert(lhs), convert(rhs))
    case ast.EndsWith(lhs, rhs) => EndsWith(convert(lhs), convert(rhs))
    case ast.Contains(lhs, rhs) => Contains(convert(lhs), convert(rhs))

    // Arithmetics
    case ast.Add(lhs, rhs) =>
      val convertedLhs = convert(lhs)
      val convertedRhs = convert(rhs)
      val addType = AddType(convertedLhs.cypherType, convertedRhs.cypherType).getOrElse(
        throw NoSuitableSignatureForExpr(e, Seq(convertedLhs.cypherType, convertedRhs.cypherType))
      )

      Add(convertedLhs, convertedRhs)(addType)
    case s@ast.Subtract(lhs, rhs) =>

      val convertedLhs = convert(lhs)
      val convertedRhs = convert(rhs)
      val exprType = s.returnTypeFor(convertedLhs.cypherType, convertedRhs.cypherType)

      Subtract(convertedLhs, convertedRhs)(exprType)
    case m@ast.Multiply(lhs, rhs) =>
      val convertedLhs = convert(lhs)
      val convertedRhs = convert(rhs)
      val exprType = m.returnTypeFor(convertedLhs.cypherType, convertedRhs.cypherType)

      Multiply(convertedLhs, convertedRhs)(exprType)
    case d@ast.Divide(lhs, rhs) =>
      val convertedLhs = convert(lhs)
      val convertedRhs = convert(rhs)
      val exprType = d.returnTypeFor(convertedLhs.cypherType, convertedRhs.cypherType)

      Divide(convertedLhs, convertedRhs)(exprType)

    case funcInv: ast.FunctionInvocation =>
      val convertedArgs = funcInv.args.map(convert).toList

      def returnType: CypherType = funcInv.returnTypeFor(convertedArgs.map(_.cypherType): _*)

      val distinct = funcInv.distinct

      def arg0 = convertedArgs(0)

      def arg1 = convertedArgs(1)

      def arg2 = convertedArgs(2)

      funcInv.function match {
        case functions.Id => Id(arg0)
        case functions.Labels => Labels(arg0)
        case functions.Type => Type(arg0)
        case functions.Avg => Avg(arg0)
        case functions.Max => Max(arg0)(returnType)
        case functions.Min => Min(arg0)(returnType)
        case functions.Sum => Sum(arg0)(returnType)
        case functions.Count => Count(arg0, distinct)
        case functions.Collect => Collect(arg0, distinct)
        case functions.Exists => Exists(arg0)
        case functions.Size => Size(arg0)
        case functions.Keys => Keys(arg0)(returnType)
        case functions.StartNode => StartNodeFunction(arg0)(returnType)
        case functions.EndNode => EndNodeFunction(arg0)(returnType)
        case functions.ToFloat => ToFloat(arg0)
        case functions.ToInteger => ToInteger(arg0)
        case functions.ToString => ToString(arg0)
        case functions.ToBoolean => ToBoolean(arg0)
        case functions.Coalesce =>
          // Special optimisation for coalesce using short-circuit logic
          convertedArgs.map(_.cypherType).indexWhere(!_.isNullable) match {
            case 0 =>
              // first argument is non-nullable; just use it directly without coalesce
              convertedArgs.head
            case -1 =>
              // nothing was non-nullable; keep all args
              val outType = convertedArgs.map(_.cypherType).reduceLeft(_ join _)
              Coalesce(convertedArgs)(outType)
            case other =>
              // keep only the args up until the first non-nullable (inclusive)
              val relevantArgs = convertedArgs.slice(0, other + 1)
              val outType = relevantArgs.map(_.cypherType).reduceLeft(_ join _)
              Coalesce(relevantArgs)(outType.material)
          }
        case functions.Range => Range(arg0, arg1, convertedArgs.lift(2))
        case functions.Substring => Substring(arg0, arg1, convertedArgs.lift(2))
        case functions.Left => Substring(arg0, IntegerLit(0), convertedArgs.lift(1))
        case functions.Right => Substring(arg0, Subtract(Multiply(IntegerLit(-1), arg1)(CTInteger), IntegerLit(1))(CTInteger), None)
        case functions.Replace => Replace(arg0, arg1, arg2)
        case functions.Trim => Trim(arg0)
        case functions.LTrim => LTrim(arg0)
        case functions.RTrim => RTrim(arg0)
        case functions.ToUpper => ToUpper(arg0)
        case functions.ToLower => ToLower(arg0)
        case functions.Properties =>
          val outType = arg0.cypherType.material match {
            case CTVoid => CTNull
            case CTNode(labels, _) =>
              CTMap(schema.nodePropertyKeysForCombinations(schema.combinationsFor(labels)))
            case CTRelationship(types, _) =>
              CTMap(schema.relationshipPropertyKeysForTypes(types))
            case m: CTMap => m
            case _ => throw InvalidArgument(funcInv, funcInv.args(0))
          }
          Properties(arg0)(outType)

        // Logarithmic functions
        case functions.Sqrt => Sqrt(arg0)
        case functions.Log => Log(arg0)
        case functions.Log10 => Log10(arg0)
        case functions.Exp => Exp(arg0)
        case functions.E => E
        case functions.Pi => Pi

        // Numeric functions
        case functions.Abs => Abs(arg0)(returnType)
        case functions.Ceil => Ceil(arg0)
        case functions.Floor => Floor(arg0)
        case functions.Rand => Rand
        case functions.Round => Round(arg0)
        case functions.Sign => Sign(arg0)

        // Trigonometric functions
        case functions.Acos => Acos(arg0)
        case functions.Asin => Asin(arg0)
        case functions.Atan => Atan(arg0)
        case functions.Atan2 => Atan2(arg0, arg1)
        case functions.Cos => Cos(arg0)
        case functions.Cot => Cot(arg0)
        case functions.Degrees => Degrees(arg0)
        case functions.Haversin => Haversin(arg0)
        case functions.Radians => Radians(arg0)
        case functions.Sin => Sin(arg0)
        case functions.Tan => Tan(arg0)

        // Match by name
        case functions.UnresolvedFunction => funcInv.name match {
          // Time functions
          case f.Timestamp.name => Timestamp
          case f.LocalDateTime.name => LocalDateTime(convertedArgs.headOption)
          case f.Date.name => Date(convertedArgs.headOption)
          case f.Duration.name => Duration(arg0)
          case name => throw NotImplementedException(s"Support for converting function '$name' is not yet implemented")
        }

        case a: functions.Function =>
          throw NotImplementedException(s"Support for converting function '${a.name}' is not yet implemented")
      }

    case _: ast.CountStar => CountStar

    // Exists (rewritten Pattern Expressions)
    case org.opencypher.okapi.ir.impl.parse.rewriter.ExistsPattern(subquery, trueVar) =>
      val innerModel = IRBuilder(subquery)(context) match {
        case sq: SingleQuery => sq
        case _ => throw new IllegalArgumentException("ExistsPattern only accepts SingleQuery")
      }
      ExistsPatternExpr(
        Var(trueVar.name)(CTBoolean),
        innerModel
      )

    // Case When .. Then .. [Else ..] End
    case ast.CaseExpression(None, alternatives, default) =>
      val convertedAlternatives = alternatives.toList.map { case (left, right) => convert(left) -> convert(right) }
      val maybeConvertedDefault: Option[Expr] = default.map(expr => convert(expr))
      val possibleTypes = convertedAlternatives.map { case (_, thenExpr) => thenExpr.cypherType }
      val defaultCaseType = maybeConvertedDefault.map(_.cypherType).getOrElse(CTNull)
      val returnType = possibleTypes.foldLeft(defaultCaseType)(_ join _)
      CaseExpr(convertedAlternatives, maybeConvertedDefault)(returnType)

    case ast.MapExpression(items) =>
      val convertedMap = items.map { case (key, value) => key.name -> convert(value) }.toMap
      val mapType = CTMap(convertedMap.map { case (key, value) => key -> value.cypherType })
      MapExpression(convertedMap)(mapType)

    // Expression
    case ast.ListSlice(list, Some(from), Some(to)) => ListSliceFromTo(convert(list), convert(from), convert(to))
    case ast.ListSlice(list, None, Some(to)) => ListSliceTo(convert(list), convert(to))
    case ast.ListSlice(list, Some(from), None) => ListSliceFrom(convert(list), convert(from))

    case ast.ContainerIndex(container, index) =>
      val convertedContainer = convert(container)
      val elementType = convertedContainer.cypherType.material match {
        case CTList(eltTyp) => eltTyp
        case CTMap(innerTypes) =>
          index match {
            case ast.Parameter(name, _) =>
              val key = context.parameters(name).cast[String]
              innerTypes.getOrElse(key, CTVoid)
            case ast.StringLiteral(key) => innerTypes.getOrElse(key, CTVoid)
            case _ => innerTypes.values.foldLeft(CTVoid: CypherType)(_ join _).nullable
          }
        case _ => throw InvalidContainerAccess(e)
      }
      ContainerIndex(convertedContainer, convert(index))(elementType)

    case ast.Null() => NullLit

    case RegexMatch(lhs, rhs) => expr.RegexMatch(convert(lhs), convert(rhs))

    case _ =>
      throw NotImplementedException(s"Not yet able to convert expression: $e")
  }

}

object OperatorTyping {

  def returnTypeFor(signatures: Seq[ast.TypeSignature], args: Seq[CypherType]): Option[CypherType] = {
    val expandedSignatures = SignatureConverter.from(signatures)
      .expandWithNulls
      .expandWithSubstitutions(CTFloat, CTInteger)
      .signatures

    val possibleReturnTypes = expandedSignatures.filter { sig =>
      sig.input.zip(args).forall {
        case (sigType, argType) =>
          argType.couldBeSameTypeAs(sigType)
      }
    }.map(_.output)

    possibleReturnTypes.reduceLeftOption(_ join _)
  }

  implicit class RichOperatorExpression(val o: ast.Expression with OperatorExpression) {
    def returnTypeFor(args: CypherType*): CypherType = {
      OperatorTyping.returnTypeFor(o.signatures, args).getOrElse(throw NoSuitableSignatureForExpr(o, args))
    }
  }

  implicit class RichTypeSignatures(val f: ast.FunctionInvocation) {
    def returnTypeFor(args: CypherType*): CypherType = {

      val signatures = FunctionExtensions.getOrElse(f.function.name, f.function) match {
        case t: TypeSignatures => t.signatures
        case _ => throw NoSuitableSignatureForExpr(f, args)
      }

      OperatorTyping.returnTypeFor(signatures, args).getOrElse(throw NoSuitableSignatureForExpr(f, args))
    }
  }
}
