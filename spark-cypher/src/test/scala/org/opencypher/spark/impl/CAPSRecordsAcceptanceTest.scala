/*
 * Copyright (c) 2016-2018 "Neo4j, Inc." [https://neo4j.com]
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
import org.opencypher.okapi.impl.exception.IllegalArgumentException
import org.opencypher.okapi.ir.test.support.Bag
import org.opencypher.okapi.ir.test.support.Bag._
import org.opencypher.spark.impl.CAPSConverters._
import org.opencypher.spark.impl.io.neo4j.Neo4jGraphLoader
import org.opencypher.spark.test.CAPSTestSuite
import org.opencypher.spark.test.fixture.{Neo4jServerFixture, OpenCypherDataFixture}

import scala.language.reflectiveCalls

class CAPSRecordsAcceptanceTest extends CAPSTestSuite with Neo4jServerFixture with OpenCypherDataFixture {

  private lazy val graph: CAPSGraph =
    Neo4jGraphLoader.fromNeo4j(neo4jConfig)

  test("convert to CypherMaps") {
    // When
    val result = graph.cypher("MATCH (a:Person) WHERE a.birthyear < 1930 RETURN a, a.name")

    // Then
    val strings = result.getRecords.collect.map(_.toCypherString).toSet

    // We do string comparisons here because CypherNode.equals() does not check labels/properties
    strings should equal(
      Set(
        "{a: (:Actor:Person {birthyear: 1910, name: 'Rachel Kempson'}), a.name: 'Rachel Kempson'}",
        "{a: (:Actor:Person {birthyear: 1908, name: 'Michael Redgrave'}), a.name: 'Michael Redgrave'}",
        "{a: (:Actor:Person {birthyear: 1873, name: 'Roy Redgrave'}), a.name: 'Roy Redgrave'}"
      ))
  }

  test("label scan and project") {
    // When
    val result = graph.cypher("MATCH (a:Person) RETURN a.name")

    // Then
    result.getRecords shouldHaveSize 15 andContain "Rachel Kempson"
  }

  test("expand and project") {
    // When
    val result = graph.cypher("MATCH (a:Actor)-[r]->(m:Film) RETURN a.birthyear, m.title")

    // Then
    result.getRecords shouldHaveSize 8 andContain 1952 -> "Batman Begins"
  }

  test("filter rels on property") {
    // Given
    val query = "MATCH (a:Actor)-[r:ACTED_IN]->() WHERE r.charactername = 'Guenevere' RETURN a, r"

    // When
    val result = graph.cypher(query)

    // Then
    result.getRecords.asCaps.toDF().collect().toBag should equal(
      Bag(
        Row(2L, true, true, 1937L, "Vanessa Redgrave", 21L, 2L, "ACTED_IN", 20L, "Guenevere")
      ))
  }

  test("filter nodes on property") {
    // When
    val result = graph.cypher("MATCH (p:Person) WHERE p.birthyear = 1970 RETURN p.name")

    // Then
    result.getRecords shouldHaveSize 3 andContain "Christopher Nolan"
  }

  test("expand and project, three properties") {
    // Given
    val query = "MATCH (a:Actor)-[:ACTED_IN]->(f:Film) RETURN a.name, f.title, a.birthyear"

    // When
    val result = graph.cypher(query)

    // Then
    val tuple = (
      "Natasha Richardson",
      "The Parent Trap",
      1963
    )
    result.getRecords shouldHaveSize 8 andContain tuple
  }

  // Removed the data node that enabled this test
  // TODO: Rewrite this test elsewhere to capture invariant
  ignore("handle properties with same key and different type between labels") {
    // When
    val movieResult = graph.cypher("MATCH (m:Movie) RETURN m.title")

    // Then
    movieResult.getRecords shouldHaveSize 1 andContain 444

    // When
    val filmResult = graph.cypher("MATCH (f:Film) RETURN f.title")

    // Then
    filmResult.getRecords shouldHaveSize 5 andContain "Camelot"
  }

  test("multiple hops of expand with different reltypes") {
    // Given
    val query = "MATCH (c:City)<-[:BORN_IN]-(a:Actor)-[r:ACTED_IN]->(f:Film) RETURN a.name, c.name, f.title"

    // When
    val records = graph.cypher(query).getRecords

    records.asCaps.toDF().collect().toBag should equal(
      Bag(
        Row("Natasha Richardson", "London", "The Parent Trap"),
        Row("Dennis Quaid", "Houston", "The Parent Trap"),
        Row("Lindsay Lohan", "New York", "The Parent Trap"),
        Row("Vanessa Redgrave", "London", "Camelot")
      ))
  }

  // TODO: Figure out what invariant this was meant to measure
  ignore("multiple hops of expand with possible reltype conflict") {
    // Given
    val query = "MATCH (u1:User)-[r1:POSTED]->(t:Tweet)-[r2]->(u2:User) RETURN u1.name, u2.name, t.text"

    // When
    val result = graph.cypher(query)

    // Then
    val tuple = (
      "Brendan Madden",
      "Tom Sawyer Software",
      "#tsperspectives 7.6 is 15% faster with #neo4j Bolt support. https://t.co/1xPxB9slrB @TSawyerSoftware #graphviz")
    result.getRecords shouldHaveSize 79 andContain tuple
  }

  // TODO: Replace with Bag testing
  implicit class OtherRichRecords(records: CypherRecords) {
    val capsRecords = records.asCaps

    def shouldHaveSize(size: Int) = {
      val tuples = capsRecords.data.collect().toSeq.map { r =>
        val cells = capsRecords.header.slots.map { s =>
          r.get(s.index)
        }

        asProduct(cells)
      }

      tuples.size shouldBe size

      new {
        def andContain(contents: Product): Unit = {
          tuples should contain(contents)
        }

        def andContain(contents: Any): Unit = andContain(Tuple1(contents))
      }
    }

    def asProduct(elts: IndexedSeq[Any]): Product = elts.length match {
      case 0 => throw IllegalArgumentException("non-empty list of elements")
      case 1 => Tuple1(elts(0))
      case 2 => Tuple2(elts(0), elts(1))
      case 3 => Tuple3(elts(0), elts(1), elts(2))
      case 4 => Tuple4(elts(0), elts(1), elts(2), elts(3))
      case 5 => Tuple5(elts(0), elts(1), elts(2), elts(3), elts(4))
      case 6 => Tuple6(elts(0), elts(1), elts(2), elts(3), elts(4), elts(5))
      case 7 => Tuple7(elts(0), elts(1), elts(2), elts(3), elts(4), elts(5), elts(6))
      case 8 => Tuple8(elts(0), elts(1), elts(2), elts(3), elts(4), elts(5), elts(6), elts(7))
      case 9 => Tuple9(elts(0), elts(1), elts(2), elts(3), elts(4), elts(5), elts(6), elts(7), elts(8))
      case _ => throw new UnsupportedOperationException("Implement support for larger products")
    }
  }
}
