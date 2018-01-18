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
package org.opencypher.caps.logical.impl

import java.net.URI

import org.opencypher.caps.api.schema.Schema
import org.opencypher.caps.api.types.CTNode
import org.opencypher.caps.ir.api.block.SortItem
import org.opencypher.caps.ir.api.expr._
import org.opencypher.caps.ir.api.{Label, SolvedQueryModel}
import org.opencypher.caps.trees.AbstractTreeNode

sealed abstract class LogicalOperator extends AbstractTreeNode[LogicalOperator] {
  def solved: SolvedQueryModel[Expr]

  val fields: Set[Var]

  def sourceGraph: LogicalGraph

  override def args = super.args.filter {
    case SolvedQueryModel(_, _, _) => false
    case other                     => true
  }
}

trait LogicalGraph {
  def schema: Schema

  def name: String

  override def toString = s"${getClass.getSimpleName}($name)($args)"

  protected def args: String = ""
}

final case class LogicalExternalGraph(name: String, uri: URI, schema: Schema) extends LogicalGraph {
  override protected def args: String = uri.toString
}

final case class LogicalPatternGraph(name: String, schema: Schema, pattern: GraphOfPattern) extends LogicalGraph {
  override protected def args: String = pattern.toString
}

final case class GraphOfPattern(toCreate: Set[ConstructedEntity], toRetain: Set[Var])

sealed trait ConstructedEntity {
  def v: Var
}

case class ConstructedNode(v: Var, labels: Set[Label]) extends ConstructedEntity

case class ConstructedRelationship(v: Var, source: Var, target: Var, typ: String) extends ConstructedEntity

sealed abstract class StackingLogicalOperator extends LogicalOperator {
  def in: LogicalOperator

  override def sourceGraph: LogicalGraph = in.sourceGraph
}

sealed abstract class BinaryLogicalOperator extends LogicalOperator {
  def lhs: LogicalOperator

  def rhs: LogicalOperator

  /**
    * Always pick the source graph from the right-hand side, because it works for in-pattern expansions
    * and changing of source graphs. This relies on the planner always planning _later_ operators on the rhs.
    */
  override def sourceGraph: LogicalGraph = rhs.sourceGraph
}

sealed abstract class LogicalLeafOperator extends LogicalOperator

final case class NodeScan(node: Var, in: LogicalOperator, solved: SolvedQueryModel[Expr])
    extends StackingLogicalOperator {
  require(node.cypherType.isInstanceOf[CTNode], "A variable for a node scan needs to have type CTNode")

  def labels: Set[String] = node.cypherType.asInstanceOf[CTNode].labels

  override val fields: Set[Var] = in.fields + node
}

final case class Distinct(fields: Set[Var], in: LogicalOperator, solved: SolvedQueryModel[Expr])
    extends StackingLogicalOperator

final case class Filter(expr: Expr, in: LogicalOperator, solved: SolvedQueryModel[Expr])
    extends StackingLogicalOperator {

  // TODO: Add more precise type information based on predicates (?)
  override val fields: Set[Var] = in.fields
}

sealed trait ExpandOperator {
  def source: Var

  def rel: Var

  def target: Var
}

final case class ExpandSource(
    source: Var,
    rel: Var,
    target: Var,
    lhs: LogicalOperator,
    rhs: LogicalOperator,
    solved: SolvedQueryModel[Expr])
    extends BinaryLogicalOperator
    with ExpandOperator {

  override val fields: Set[Var] = lhs.fields ++ rhs.fields + rel
}

final case class ExpandTarget(
    source: Var,
    rel: Var,
    target: Var,
    lhs: LogicalOperator,
    rhs: LogicalOperator,
    solved: SolvedQueryModel[Expr])
    extends BinaryLogicalOperator
    with ExpandOperator {

  override val fields: Set[Var] = lhs.fields ++ rhs.fields
}

final case class BoundedVarLengthExpand(
    source: Var,
    rel: Var,
    target: Var,
    lower: Int,
    upper: Int,
    lhs: LogicalOperator,
    rhs: LogicalOperator,
    solved: SolvedQueryModel[Expr])
    extends BinaryLogicalOperator
    with ExpandOperator {

  override val fields: Set[Var] = lhs.fields ++ rhs.fields
}

final case class ValueJoin(
    lhs: LogicalOperator,
    rhs: LogicalOperator,
    predicates: Set[org.opencypher.caps.ir.api.expr.Equals],
    solved: SolvedQueryModel[Expr])
    extends BinaryLogicalOperator {

  override val fields: Set[Var] = lhs.fields ++ rhs.fields
}

final case class ExpandInto(source: Var, rel: Var, target: Var, in: LogicalOperator, solved: SolvedQueryModel[Expr])
    extends StackingLogicalOperator
    with ExpandOperator {

  override val fields: Set[Var] = lhs.fields ++ rhs.fields

  def lhs: LogicalOperator = in

  def rhs: LogicalOperator = in
}

final case class Project(expr: Expr, field: Option[Var], in: LogicalOperator, solved: SolvedQueryModel[Expr])
    extends StackingLogicalOperator {

  override val fields: Set[Var] = in.fields ++ field
}

final case class Unwind(expr: Expr, field: Var, in: LogicalOperator, solved: SolvedQueryModel[Expr])
    extends StackingLogicalOperator {

  override val fields: Set[Var] = in.fields + field
}

final case class ProjectGraph(graph: LogicalGraph, in: LogicalOperator, solved: SolvedQueryModel[Expr])
    extends StackingLogicalOperator {

  override val fields: Set[Var] = in.fields
}

final case class Aggregate(
    aggregations: Set[(Var, Aggregator)],
    group: Set[Var],
    in: LogicalOperator,
    solved: SolvedQueryModel[Expr])
    extends StackingLogicalOperator {

  override val fields: Set[Var] = in.fields ++ aggregations.map(_._1) ++ group
}

final case class Select(
    orderedFields: IndexedSeq[Var],
    graphs: Set[String],
    in: LogicalOperator,
    solved: SolvedQueryModel[Expr])
    extends StackingLogicalOperator {

  override val fields: Set[Var] = orderedFields.toSet
}

final case class OrderBy(sortItems: Seq[SortItem[Expr]], in: LogicalOperator, solved: SolvedQueryModel[Expr])
    extends StackingLogicalOperator {

  override val fields: Set[Var] = in.fields
}

final case class Skip(expr: Expr, in: LogicalOperator, solved: SolvedQueryModel[Expr]) extends StackingLogicalOperator {

  override val fields: Set[Var] = in.fields
}

final case class Limit(expr: Expr, in: LogicalOperator, solved: SolvedQueryModel[Expr])
    extends StackingLogicalOperator {

  override val fields: Set[Var] = in.fields
}

final case class CartesianProduct(lhs: LogicalOperator, rhs: LogicalOperator, solved: SolvedQueryModel[Expr])
    extends BinaryLogicalOperator {

  override val fields: Set[Var] = lhs.fields ++ rhs.fields
}

final case class Optional(lhs: LogicalOperator, rhs: LogicalOperator, solved: SolvedQueryModel[Expr])
    extends BinaryLogicalOperator {

  override val fields: Set[Var] = lhs.fields ++ rhs.fields
}

final case class ExistsPatternPredicate(
    expr: ExistsPatternExpr,
    lhs: LogicalOperator,
    rhs: LogicalOperator,
    solved: SolvedQueryModel[Expr])
    extends BinaryLogicalOperator {

  override val fields: Set[Var] = lhs.fields + expr.predicateField
}

final case class SetSourceGraph(
    override val sourceGraph: LogicalGraph,
    in: LogicalOperator,
    solved: SolvedQueryModel[Expr])
    extends StackingLogicalOperator {

  override val fields: Set[Var] = in.fields
}

final case class EmptyRecords(fields: Set[Var], in: LogicalOperator, solved: SolvedQueryModel[Expr])
    extends StackingLogicalOperator

final case class Start(sourceGraph: LogicalGraph, fields: Set[Var], solved: SolvedQueryModel[Expr])
    extends LogicalLeafOperator
