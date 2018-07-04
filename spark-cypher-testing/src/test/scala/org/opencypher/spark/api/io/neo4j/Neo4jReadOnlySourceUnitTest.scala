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
package org.opencypher.spark.api.io.neo4j

import java.net.URI

import org.opencypher.okapi.api.graph.GraphName
import org.opencypher.okapi.api.schema.Schema
import org.opencypher.okapi.api.types.{CTBoolean, CTFloat, CTInteger, CTString}
import org.opencypher.okapi.neo4j.io.Neo4jConfig
import org.opencypher.okapi.testing.BaseTestSuite
import org.opencypher.spark.api.CAPSSession
import org.opencypher.spark.api.io.GraphEntity.sourceIdKey
import org.opencypher.spark.api.io.Relationship.{sourceEndNodeKey, sourceStartNodeKey}

class Neo4jReadOnlySourceUnitTest extends BaseTestSuite {

  private val schema = Schema.empty
    .withNodePropertyKeys("A")("foo" -> CTInteger, "bar" -> CTString.nullable)
    .withNodePropertyKeys("B")()
    .withRelationshipPropertyKeys("TYPE")("foo" -> CTFloat.nullable, "f" -> CTBoolean)
    .withRelationshipPropertyKeys("TYPE2")()

  private val entireGraph = "allOfIt"
  private val pgds = Neo4jReadOnlySource(Neo4jConfig(URI.create("test://foo")), GraphName(entireGraph))(mock[CAPSSession])

  it("constructs flat node queries from schema") {
    pgds.flatNodeQuery(GraphName(entireGraph), Set("A"), schema) should equal(
      s"MATCH (n:A) RETURN id(n) AS $sourceIdKey, n.bar, n.foo"
    )
  }

  it("constructs flat node queries from schema without properties") {
    pgds.flatNodeQuery(GraphName(entireGraph), Set("B"), schema) should equal(
      s"MATCH (n:B) RETURN id(n) AS $sourceIdKey"
    )
  }

  it("constructs flat relationship queries from schema") {
    pgds.flatRelQuery(GraphName(entireGraph), "TYPE", schema) should equal(
      s"MATCH (s)-[r:TYPE]->(e) RETURN id(r) AS $sourceIdKey, id(s) AS $sourceStartNodeKey, id(e) AS $sourceEndNodeKey, r.f, r.foo"
    )
  }

  it("constructs flat relationship queries from schema with no properties") {
    pgds.flatRelQuery(GraphName(entireGraph), "TYPE2", schema) should equal(
      s"MATCH (s)-[r:TYPE2]->(e) RETURN id(r) AS $sourceIdKey, id(s) AS $sourceStartNodeKey, id(e) AS $sourceEndNodeKey"
    )
  }
}
