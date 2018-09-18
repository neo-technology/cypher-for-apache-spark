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
package org.opencypher.spark.impl

import org.apache.spark.sql.Row
import org.opencypher.okapi.api.table.CypherRecords
import org.opencypher.okapi.api.value.CypherValue.CypherMap
import org.opencypher.okapi.neo4j.io.MetaLabelSupport._
import org.opencypher.okapi.relational.api.graph.RelationalCypherGraph
import org.opencypher.okapi.testing.Bag
import org.opencypher.okapi.testing.Bag._
import org.opencypher.spark.api.GraphSources
import org.opencypher.spark.api.value.{CAPSNode, CAPSRelationship}
import org.opencypher.spark.impl.CAPSConverters._
import org.opencypher.spark.impl.table.SparkTable.DataFrameTable
import org.opencypher.spark.testing.CAPSTestSuite
import org.opencypher.spark.testing.fixture.{CAPSNeo4jServerFixture, OpenCypherDataFixture}
import org.scalatest.Assertion

import scala.language.reflectiveCalls

class CAPSRecordsAcceptanceTest extends CAPSTestSuite with CAPSNeo4jServerFixture with OpenCypherDataFixture {

  private lazy val graph: RelationalCypherGraph[DataFrameTable] =
    GraphSources.cypher.neo4j(neo4jConfig).graph(entireGraphName).asCaps

  it("convert to CypherMaps") {
    // When
    val result = graph.cypher("MATCH (a:Person) WHERE a.birthyear < 1930 RETURN a, a.name")

    // Then
    val strings = result.records.collect.map(_.toCypherString).toSet

    // We do string comparisons here because CypherNode.equals() does not check labels/properties
    strings should equal(
      Set(
        "{`a`: (:`Actor`:`Person` {`birthyear`: 1910, `name`: 'Rachel Kempson'}), `a.name`: 'Rachel Kempson'}",
        "{`a`: (:`Actor`:`Person` {`birthyear`: 1908, `name`: 'Michael Redgrave'}), `a.name`: 'Michael Redgrave'}",
        "{`a`: (:`Actor`:`Person` {`birthyear`: 1873, `name`: 'Roy Redgrave'}), `a.name`: 'Roy Redgrave'}"
      ))
  }

  it("label scan and project") {
    // When
    val result = graph.cypher("MATCH (a:Person) RETURN a.name")

    // Then
    result.records shouldHaveSize 15 // andContain "Rachel Kempson"
  }

  it("expand and project") {
    // When
    val result = graph.cypher("MATCH (a:Actor)-[r]->(m:Film) RETURN a.birthyear, m.title")

    // Then
    result.records shouldHaveSize 8 // andContain 1952 -> "Batman Begins"
  }

  it("filter rels on property") {
    // Given
    val query = "MATCH (a:Actor)-[r:ACTED_IN]->() WHERE r.charactername = 'Guenevere' RETURN a, r"

    // When
    val result = graph.cypher(query)

    // Then
    result.records.asCaps.toCypherMaps.collect().toBag should equal(Bag(CypherMap(
        "a" -> CAPSNode(2, Set("Actor", "Person"), CypherMap("birthyear" -> 1937, "name" -> "Vanessa Redgrave")),
        "r" -> CAPSRelationship(21, 2, 20, "ACTED_IN", CypherMap("charactername" -> "Guenevere")))))
  }

  it("filter nodes on property") {
    // When
    val result = graph.cypher("MATCH (p:Person) WHERE p.birthyear = 1970 RETURN p.name")

    // Then
    result.records shouldHaveSize 3 // andContain "Christopher Nolan"
  }

  it("expand and project, three properties") {
    // Given
    val query = "MATCH (a:Actor)-[:ACTED_IN]->(f:Film) RETURN a.name, f.title, a.birthyear"

    // When
    val result = graph.cypher(query)

    result.records shouldHaveSize 8 // andContain tuple
  }

  it("multiple hops of expand with different reltypes") {
    // Given
    val query = "MATCH (c:City)<-[:BORN_IN]-(a:Actor)-[r:ACTED_IN]->(f:Film) RETURN a.name, c.name, f.title"

    // When
    val records = graph.cypher(query).records

    records.asCaps.df.collect().toBag should equal(Bag(
        Row("Natasha Richardson", "London", "The Parent Trap"),
        Row("Dennis Quaid", "Houston", "The Parent Trap"),
        Row("Lindsay Lohan", "New York", "The Parent Trap"),
        Row("Vanessa Redgrave", "London", "Camelot")
      ))
  }

  implicit class OtherRichRecords(records: CypherRecords) {
    val capsRecords: CAPSRecords = records.asCaps

    def shouldHaveSize(size: Int): Assertion = {
      val maps: Bag[CypherMap] = capsRecords.toMaps

      maps.size shouldBe size

      // TODO: contain syntax does not work for maps -> https://github.com/scalatest/scalatest/issues/1224
//      new {
//        def andContain(contents: Map[String, CypherValue]): Unit = {
//          maps should contain(contents)
//        }
//
//        def andContain(contents: Any): Unit = andContain(Tuple1(contents))
//      }
    }

  }
}
