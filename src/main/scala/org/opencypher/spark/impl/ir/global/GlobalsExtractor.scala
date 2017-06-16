package org.opencypher.spark.impl.ir.global

import org.neo4j.cypher.internal.frontend.v3_3.ast
import org.opencypher.spark.api.ir.global._
import org.opencypher.spark.api.types.CypherType
import org.opencypher.spark.impl.typer.fromFrontendType

object GlobalsExtractor {

  def apply(expr: ast.ASTNode, tokens: GlobalsRegistry = GlobalsRegistry.empty): GlobalsRegistry = {
    expr.fold(tokens) {
      case ast.LabelName(name) => _.mapTokens(_.withLabel(Label(name)))
      case ast.RelTypeName(name) => _.mapTokens(_.withRelType(RelType(name)))
      case ast.PropertyKeyName(name) => _.mapTokens(_.withPropertyKey(PropertyKey(name)))
      case ast.Parameter(name, _) => _.mapConstants(_.withConstant(Constant(name)))
    }
  }

  def paramWithTypes(expr: ast.ASTNode): Map[ast.Expression, CypherType] = {
    expr.fold(Map.empty[ast.Expression, CypherType]) {
      case p: ast.Parameter => _.updated(p, fromFrontendType(p.parameterType))
    }
  }
}
