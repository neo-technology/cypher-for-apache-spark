package org.opencypher.morpheus.adapters

import org.apache.spark.cypher.adapters.MappingAdapter._
import org.apache.spark.graph.api.{NodeFrame, PropertyGraph, PropertyGraphType, RelationshipFrame}
import org.apache.spark.sql.DataFrame
import org.opencypher.morpheus.MorpheusSession
import org.opencypher.okapi.api.types.{CTNode, CTRelationship}
import org.opencypher.okapi.ir.api.expr.Var
import org.opencypher.spark.api.io.CAPSEntityTable

case class RelationalGraphAdapter(
  cypherSession: MorpheusSession,
  nodeFrames: Seq[NodeFrame],
  relationshipFrames: Seq[RelationshipFrame]) extends PropertyGraph {

  override def schema: PropertyGraphType = SchemaAdapter(graph.schema)

  private[morpheus] lazy val graph = {
    if (nodeFrames.isEmpty) {
      cypherSession.graphs.empty
    } else {
      val nodeTables = nodeFrames.map { nodeDataFrame => CAPSEntityTable(nodeDataFrame.toNodeMapping, nodeDataFrame.df) }
      val relTables = relationshipFrames.map { relDataFrame => CAPSEntityTable(relDataFrame.toRelationshipMapping, relDataFrame.df) }
      cypherSession.graphs.create(nodeTables.head, nodeTables.tail ++ relTables: _*)
    }
  }

  private lazy val _nodeFrame: Map[Set[String], NodeFrame] = nodeFrames.map(nf => nf.labelSet -> nf).toMap

  private lazy val _relationshipFrame: Map[String, RelationshipFrame] = relationshipFrames.map(rf => rf.relationshipType -> rf).toMap

  override def nodes: DataFrame = {
    // TODO: move to API as default implementation
    val nodeVar = Var("n")(CTNode)
    val nodes = graph.nodes(nodeVar.name)

    val df = nodes.table.df
    val header = nodes.header

    val idRename = header.column(nodeVar) -> "$ID"
    val labelRenames = header.labelsFor(nodeVar).map(hasLabel => header.column(hasLabel) -> s":${hasLabel.label.name}").toSeq.sortBy(_._2)
    val propertyRenames = header.propertiesFor(nodeVar).map(property => header.column(property) -> property.key.name).toSeq.sortBy(_._2)

    val selectColumns = (Seq(idRename) ++ labelRenames ++ propertyRenames).map { case (oldColumn, newColumn) => df.col(oldColumn).as(newColumn) }

    df.select(selectColumns: _*)
  }

  override def relationships: DataFrame = {
    // TODO: move to API as default implementation
    val relVar = Var("r")(CTRelationship)
    val rels = graph.relationships(relVar.name)

    val df = rels.table.df
    val header = rels.header

    val idRename = header.column(relVar) -> "$ID"
    val sourceIdRename = header.column(header.startNodeFor(relVar)) -> "$SOURCE_ID"
    val targetIdRename = header.column(header.endNodeFor(relVar)) -> "$TARGET_ID"
    val relTypeRenames = header.typesFor(relVar).map(hasType => header.column(hasType) -> s":${hasType.relType.name}").toSeq.sortBy(_._2)
    val propertyRenames = header.propertiesFor(relVar).map(property => header.column(property) -> property.key.name).toSeq.sortBy(_._2)

    val selectColumns = (Seq(idRename, sourceIdRename, targetIdRename) ++ relTypeRenames ++ propertyRenames).map { case (oldColumn, newColumn) => df.col(oldColumn).as(newColumn) }

    df.select(selectColumns: _*)
  }

  override def nodeFrame(labelSet: Set[String]): NodeFrame = _nodeFrame(labelSet)

  override def relationshipFrame(relationshipType: String): RelationshipFrame = _relationshipFrame(relationshipType)

}
