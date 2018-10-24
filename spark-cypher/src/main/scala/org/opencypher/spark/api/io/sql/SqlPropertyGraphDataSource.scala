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
package org.opencypher.spark.api.io.sql

import org.apache.spark.sql.DataFrame
import org.opencypher.okapi.api.graph.{GraphName, PropertyGraph}
import org.opencypher.okapi.api.io.conversion.{NodeMapping, RelationshipMapping}
import org.opencypher.okapi.api.schema.Schema
import org.opencypher.okapi.impl.exception.{IllegalArgumentException, UnsupportedOperationException}
import org.opencypher.spark.api.CAPSSession
import org.opencypher.spark.api.io.{CAPSNodeTable, CAPSRelationshipTable, HiveFormat, JdbcFormat}
import org.opencypher.spark.impl.CAPSFunctions.partitioned_id_assignment
import org.opencypher.spark.impl.io.CAPSPropertyGraphDataSource
import org.opencypher.sql.ddl._

case class DDLFormatException(message: String) extends RuntimeException

/**
  * Id of a SQL view assigned to a specific label definition. This is necessary because the same SQL view can be
  * assigned to multiple label definitions.
  */
// TODO: move to IR
case class AssignedViewIdentifier(labelDefinitions: Set[String], viewName: String)

case class SqlPropertyGraphDataSource(
  ddl: DdlDefinitions,
  sqlDataSources: Map[String, SqlDataSourceConfig]
)(implicit val caps: CAPSSession) extends CAPSPropertyGraphDataSource {

  override def hasGraph(graphName: GraphName): Boolean = ddl.graphByName.contains(graphName.value)


  private val idColumn = "id"
  private val startColumn = "start"
  private val endColumn = "end"

  override def graph(graphName: GraphName): PropertyGraph = {
    val graphSchema = schema(graphName).getOrElse(notFound(s"schema for graph $graphName", ddl.graphSchemas.keySet))

    val (dataSourceName, databaseName) = ddl.setSchema match {
      case Some(SetSchemaDefinition(dsName, Some(dbName))) => dsName -> dbName
      case Some(SetSchemaDefinition(dsName, None)) => dsName -> sqlDataSources
        .getOrElse(dsName, notFound(dsName, sqlDataSources.keys))
        .defaultSchema.getOrElse(notFound(s"database schema for $dsName"))
      case _ => throw DDLFormatException("Missing in DDL: `SET SCHEMA <dataSourceName>.<databaseSchemaName>`")
    }

    val sqlDataSourceConfig = sqlDataSources.getOrElse(dataSourceName, throw SqlDataSourceConfigException(s"No configuration for $dataSourceName"))

    val graphDefinition = ddl.graphByName(graphName.value)

    // Node tables

    val nodeDataFramesWithoutIds = for {
      nodeMapping <- graphDefinition.nodeMappings
      nodeToViewDefinition <- nodeMapping.nodeToViewDefinitions
    } yield AssignedViewIdentifier(nodeMapping.labelNames, nodeToViewDefinition.viewName) -> readSqlTable(nodeToViewDefinition.viewName, sqlDataSourceConfig)

    // id assignment preparation
    val nodeDfPartitionCounts = nodeDataFramesWithoutIds.map(_._2.rdd.getNumPartitions)
    val nodeDfPartitionStartDeltas = nodeDfPartitionCounts.scan(0)(_ + _).dropRight(1) // drop last delta, as we don't need it

    // actual id assignment
    // TODO: Ensure added id does not collide with a property called `id`
    val nodeDataFramesWithIds = nodeDataFramesWithoutIds.zip(nodeDfPartitionStartDeltas).map {
      case ((instantiatedViewIdentifier, df), partitionStartDelta) =>
        val dfWithIdColumn = df.withColumn(idColumn, partitioned_id_assignment(partitionStartDelta))
        instantiatedViewIdentifier -> dfWithIdColumn
    }.toMap


    // TODO: maps a AssignedViewId to its NodeToViewDefinition --> make available through DDL IR
    val nodeToViewDefinitions = (for {
      nodeMapping <- graphDefinition.nodeMappings
      nodeToViewDefinition <- nodeMapping.nodeToViewDefinitions
    } yield AssignedViewIdentifier(nodeMapping.labelNames, nodeToViewDefinition.viewName) -> nodeToViewDefinition).toMap

    val nodeTables = nodeDataFramesWithIds.map {
      case (viewId, df) =>
        val nodeMapping = computeNodeMapping(viewId.labelDefinitions, graphSchema, nodeToViewDefinitions(viewId))
        CAPSNodeTable.fromMapping(nodeMapping, df)
    }.toSeq

    // Relationship tables

    val relDataFramesWithoutIds = for {
      relMapping <- graphDefinition.relationshipMappings
      relToViewDefinition <- relMapping.relationshipToViewDefinitions
    } yield AssignedViewIdentifier(Set(relMapping.relType), relToViewDefinition.viewDefinition.name) -> readSqlTable(relToViewDefinition.viewDefinition.name, sqlDataSourceConfig)

    // id assignment preparation
    val relDfPartitionCounts = nodeDataFramesWithoutIds.map(_._2.rdd.getNumPartitions)
    val relDfPartitionStartDeltas = relDfPartitionCounts.scan(0)(_ + _).dropRight(1) // drop last delta, as we don't need it

    // actual id assignment
    // TODO: Ensure added id does not collide with a property called `id`
    val relDataFramesWithIds = relDataFramesWithoutIds.zip(relDfPartitionStartDeltas).map {
      case ((instantiatedViewIdentifier, df), partitionStartDelta) =>
        val dfWithIdColumn = df.withColumn(idColumn, partitioned_id_assignment(partitionStartDelta))
        instantiatedViewIdentifier -> dfWithIdColumn
    }.toMap

    val relToViewDefinitions = (for {
      relMaping <- graphDefinition.relationshipMappings
      relToViewDefinition <- relMaping.relationshipToViewDefinitions
    } yield AssignedViewIdentifier(Set(relMaping.relType), relToViewDefinition.viewDefinition.name) -> relToViewDefinition).toMap

    val relDataFramesWithNodeIds = relToViewDefinitions.map {
      case (relId, relToViewDefinition) =>

        val relDf = relDataFramesWithIds(relId)
        val startNodeToViewDefinition = relToViewDefinition.startNodeToViewDefinition
        val endNodeToViewDefinition = relToViewDefinition.endNodeToViewDefinition

        val relsWithStartNodeId = addNodeDfToRelDf(relDf, nodeDataFramesWithIds, startNodeToViewDefinition, relToViewDefinition, startColumn)
        val relsWithEndNodeId = addNodeDfToRelDf(relsWithStartNodeId, nodeDataFramesWithIds, endNodeToViewDefinition, relToViewDefinition, endColumn)

        relId -> relsWithEndNodeId
    }

    val relationshipTables = relDataFramesWithNodeIds.map {
      case (viewId, df) =>
        val relMapping = computeRelMapping(viewId.labelDefinitions.head, graphSchema, relToViewDefinitions(viewId))
        CAPSRelationshipTable.fromMapping(relMapping, df)
    }.toSeq

    caps.graphs.create(nodeTables.head, nodeTables.tail ++ relationshipTables: _*)
  }

  private def addNodeDfToRelDf(
    relDf: DataFrame,
    nodeDataFramesWithIds: Map[AssignedViewIdentifier, DataFrame],
    nodeMapping: LabelToViewDefinition,
    relToViewDefinition: RelationshipToViewDefinition,
    newNodeIdColumn: String): DataFrame = {

    val nodeViewName = nodeMapping.viewDefinition.name
    val nodeViewAlias = nodeMapping.viewDefinition.alias
    val nodeViewId = AssignedViewIdentifier(nodeMapping.labelSet, nodeViewName)

    val nodeDf = nodeDataFramesWithIds(nodeViewId)

    val relViewAlias = relToViewDefinition.viewDefinition.alias

    val namespacedNodeDf = nodeDf.columns.foldLeft(nodeDf) {
      case (currentDf, colName) => currentDf.withColumnRenamed(colName, s"${nodeViewAlias}_$colName")
    }

    val namespacedRelDf = relDf.columns.foldLeft(relDf) {
      case (currentDf, colName) => currentDf.withColumnRenamed(colName, s"${relViewAlias}_$colName")
    }

    val joinColumnNames = nodeMapping.joinOn.joinPredicates.map {
      case (leftCol, rightCol) => leftCol.mkString("_") -> rightCol.mkString("_")
    }

    val joinPredicate = joinColumnNames
      .map { case (leftColName, rightColName) => namespacedNodeDf.col(leftColName) -> namespacedRelDf.col(rightColName) }
      .map { case (leftCol, rightCol) => leftCol === rightCol }
      .reduce(_ && _)

    val nodeIdColumnName = s"${nodeViewAlias}_$idColumn"
    val relsWithUpdatedStartNodeId = namespacedNodeDf
      .select(nodeIdColumnName, joinColumnNames.unzip._1: _*)
      .withColumnRenamed(nodeIdColumnName, newNodeIdColumn)
      .join(namespacedRelDf, joinPredicate)

    relsWithUpdatedStartNodeId.columns.foldLeft(relsWithUpdatedStartNodeId) {
      case (currentDf, columnName) if columnName.startsWith(nodeViewAlias) =>
        currentDf.drop(columnName)
      case (currentDf, columnName) if columnName.startsWith(relViewAlias) =>
        currentDf.withColumnRenamed(columnName, columnName.substring(relViewAlias.length + 1))
      case (currentDf, _) => currentDf

    }

  }

  private def computeNodeMapping(
    labelCombination: Set[String],
    graphSchema: Schema,
    elementToViewDefinition: ElementToViewDefinition
  ): NodeMapping = {
    val propertyToColumnMapping = elementToViewDefinition.maybePropertyMapping match {
      case Some(propertyToColumnMappingDefinition) => propertyToColumnMappingDefinition
      // TODO: support unicode characters in properties and ensure there are no collisions with column name `id`
      case None => graphSchema.nodePropertyKeys(labelCombination).map { case (key, _) => key -> key }
    }
    val initialNodeMapping = NodeMapping.on(idColumn).withImpliedLabels(labelCombination.toSeq: _*)
    val nodeMapping = propertyToColumnMapping.foldLeft(initialNodeMapping) {
      case (currentNodeMapping, (propertyKey, columnName)) => currentNodeMapping.withPropertyKey(propertyKey -> columnName)
    }
    nodeMapping
  }

  private def computeRelMapping(
    relType: String,
    graphSchema: Schema,
    elementToViewDefinition: ElementToViewDefinition
  ): RelationshipMapping = {
    val propertyToColumnMapping = elementToViewDefinition.maybePropertyMapping match {
      case Some(propertyToColumnMappingDefinition) => propertyToColumnMappingDefinition
      // TODO: support unicode characters in properties and ensure there are no collisions with column name `id`
      case None => graphSchema.relationshipPropertyKeys(relType).map { case (key, _) => key -> key }
    }
    val initialRelMapping = RelationshipMapping.on(idColumn)
      .withSourceStartNodeKey(startColumn)
      .withSourceEndNodeKey(endColumn)
      .withRelType(relType)

    val relMapping = propertyToColumnMapping.foldLeft(initialRelMapping) {
      case (currentRelMapping, (propertyKey, columnName)) => currentRelMapping.withPropertyKey(propertyKey -> columnName)
    }
    relMapping
  }

  private def readSqlTable(viewName: String, sqlDataSourceConfig: SqlDataSourceConfig): DataFrame = {
    val spark = caps.sparkSession

    val inputTable = sqlDataSourceConfig.storageFormat match {
      case JdbcFormat =>
        spark.read
          .format("jdbc")
          .option("url", sqlDataSourceConfig.jdbcUri.getOrElse(throw SqlDataSourceConfigException("Missing JDBC URI")))
          .option("driver", sqlDataSourceConfig.jdbcDriver.getOrElse(throw SqlDataSourceConfigException("Missing JDBC Driver")))
          .option("fetchSize", sqlDataSourceConfig.jdbcFetchSize)
          .option("dbtable", viewName)
          .load()

      case HiveFormat => spark.table(viewName)

      case otherFormat => notFound(otherFormat, Seq(JdbcFormat, HiveFormat))
    }

    inputTable
  }

  override def schema(name: GraphName): Option[Schema] = ddl.graphSchemas.get(name.value)

  override def store(name: GraphName, graph: PropertyGraph): Unit = unsupported("storing a graph")

  override def delete(name: GraphName): Unit = unsupported("deleting a graph")

  override def graphNames: Set[GraphName] = ddl.graphByName.keySet.map(GraphName)

  private val className = getClass.getSimpleName

  private def unsupported(operation: String): Nothing =
    throw UnsupportedOperationException(s"$className does not allow $operation")

  private def notFound(needle: Any, haystack: Traversable[Any] = Traversable.empty): Nothing =
    throw IllegalArgumentException(
      expected = if (haystack.nonEmpty) s"one of ${stringList(haystack)}" else "",
      actual = needle
    )

  private def stringList(elems: Traversable[Any]): String =
    elems.mkString("[", ",", "]")
}