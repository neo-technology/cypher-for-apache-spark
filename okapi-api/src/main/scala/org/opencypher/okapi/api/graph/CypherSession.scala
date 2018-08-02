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
package org.opencypher.okapi.api.graph

import org.opencypher.okapi.api.io._
import org.opencypher.okapi.api.table.CypherRecords
import org.opencypher.okapi.api.value.CypherValue._

/**
  * The Cypher Session is the main API for a Cypher-based application. It manages graphs which can be queried using
  * Cypher. Graphs can be read from / written to different data sources (e.g. CSV) and also stored in / retrieved from
  * the session-local storage.
  */
trait CypherSession {

  type Result <: CypherResult

  /**
    * Executes a Cypher query in this session on the current ambient graph.
    *
    * @param query        Cypher query to execute
    * @param parameters   parameters used by the Cypher query
    * @param drivingTable seed data that can be accessed from within the query
    * @return result of the query
    */
  def cypher(query: String, parameters: CypherMap = CypherMap.empty, drivingTable: Option[CypherRecords] = None): Result

  /**
    * Interface through which the user may (de-)register property graph datasources as well as read, write and delete property graphs.
    *
    * @return session catalog
    */
  def catalog: PropertyGraphCatalog

  /**
    * Register the given [[org.opencypher.okapi.api.io.PropertyGraphDataSource]] under the specific [[org.opencypher.okapi.api.graph.Namespace]] within the session catalog.
    *
    * This enables a user to refer to that [[org.opencypher.okapi.api.io.PropertyGraphDataSource]] within a Cypher query.
    *
    * Note, that it is not allowed to overwrite an already registered [[org.opencypher.okapi.api.graph.Namespace]].
    * Use [[CypherSession#deregisterSource]] first.
    *
    * @param namespace  namespace for lookup
    * @param dataSource property graph data source
    */
  def registerSource(namespace: Namespace, dataSource: PropertyGraphDataSource): Unit =
    catalog.register(namespace, dataSource)

  /**
    * Registers a view property graph data source defined by `viewQuery`.
    *
    * Example:
    *  - given a catalog graph with qualified graph name `foo.bar`
    *  - given a view with namespace `view`
    *
    * In this case the qualified graph name of the view of that graph is `view.foo.bar`.
    *
    * A view query is a query that uses Cypher 10 to construct a graph. This is an example for a view query that returns
    * the entire underlying graph without any changes:
    * {{{
    *   MATCH (n)
    *   MATCH (s)-[r]->(t)
    *   CONSTRUCT
    *     CLONE n
    *     NEW (s)-[r]->(t)
    *   RETURN GRAPH
    * }}}
    *
    * @param namespace namespace where the view data source is registered
    * @param viewQuery query that constructs the view
    */
  def registerView(namespace: Namespace, viewQuery: String): Unit = {
    registerSource(namespace, ViewPropertyGraphDataSource(viewQuery)(this))
  }

  /**
    * De-registers a [[org.opencypher.okapi.api.io.PropertyGraphDataSource]] from the sessions catalog by its given [[org.opencypher.okapi.api.graph.Namespace]].
    *
    * @param namespace namespace for lookup
    */
  def deregisterSource(namespace: Namespace): Unit =
    catalog.deregister(namespace)

  /**
    * Executes a Cypher query in this session, using the argument graph as the ambient graph.
    *
    * The ambient graph is the graph that is used for graph matching and updating,
    * unless another graph is explicitly selected by the query.
    *
    * @param graph      ambient graph for this query
    * @param query      Cypher query to execute
    * @param parameters parameters used by the Cypher query
    * @return result of the query
    */
  private[graph] def cypherOnGraph(
    graph: PropertyGraph,
    query: String,
    parameters: CypherMap = CypherMap.empty,
    drivingTable: Option[CypherRecords]
  ): CypherResult

  private[opencypher] lazy val emptyGraphQgn = QualifiedGraphName(catalog.sessionNamespace, GraphName("emptyGraph"))
}
