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
package org.opencypher.spark.impl.acceptance

import org.opencypher.okapi.api.value.CypherValue._
import org.opencypher.okapi.relational.api.configuration.CoraConfiguration.PrintPhysicalPlan
import org.opencypher.okapi.testing.Bag
import org.opencypher.okapi.testing.Bag._
import org.opencypher.spark.impl.CAPSConverters._
import org.opencypher.spark.testing.CAPSTestSuite
import org.scalatest.DoNotDiscover

@DoNotDiscover
class MatchBehaviour extends CAPSTestSuite with DefaultGraphInit {

  describe("match single node") {

    it("matches a label") {
      // Given
      val given = initGraph(
        """
          |CREATE (p:Person {firstName: "Alice", lastName: "Foo"})
        """.stripMargin)

      // When
      val result = given.cypher(
        """
          |MATCH (a:Person)
          |RETURN a.firstName
        """.stripMargin)

      // Then
      result.getRecords.toMaps should equal(Bag(CypherMap("a.firstName" -> "Alice")
      ))
    }

    it("matches an unknown label") {
      // Given
      val given = initGraph("CREATE (p:Person {firstName: 'Alice', lastName: 'Foo'})")

      // When
      val result = given.cypher(
        """
          |MATCH (a:Animal)
          |RETURN a
        """.stripMargin)

      // Then
      result.getRecords.toMaps shouldBe empty
    }
  }

  describe("multiple match clauses") {
    it("can handle multiple match clauses") {
      // Given
      val given = initGraph(
        """CREATE (p1:Person {name: "Alice"})
          |CREATE (p2:Person {name: "Bob"})
          |CREATE (p3:Person {name: "Eve"})
          |CREATE (p1)-[:KNOWS]->(p2)
          |CREATE (p2)-[:KNOWS]->(p3)
        """.stripMargin)

      // When
      val result = given.cypher(
        """MATCH (p1:Person)
          |MATCH (p1:Person)-[e1]->(p2:Person)
          |MATCH (p2)-[e2]->(p3:Person)
          |RETURN p1.name, p2.name, p3.name
        """.stripMargin)

      // Then
      result.getRecords.toMaps should equal(
        Bag(
          CypherMap(
            "p1.name" -> "Alice",
            "p2.name" -> "Bob",
            "p3.name" ->
              "Eve"
          )
        ))
    }

    it("cyphermorphism and multiple match clauses") {
      // Given
      val given = initGraph(
        """
          |CREATE (p1:Person {name: "Alice"})
          |CREATE (p2:Person {name: "Bob"})
          |CREATE (p1)-[:KNOWS]->(p2)
          |CREATE (p2)-[:KNOWS]->(p1)
        """.stripMargin)

      // When
      val result = given.cypher(
        """
          |MATCH (p1:Person)-[e1:KNOWS]->(p2:Person)-[e2:KNOWS]->(p3:Person)
          |MATCH (p3)-[e3:KNOWS]->(p4:Person)
          |RETURN p1.name, p2.name, p3.name, p4.name
        """.stripMargin)

      // Then
      result.getRecords.toMaps should equal(
        Bag(
          CypherMap(
            "p1.name" -> "Bob",
            "p2.name" -> "Alice",
            "p3.name" -> "Bob",
            "p4.name" -> "Alice"
          ),
          CypherMap(
            "p1.name" -> "Alice",
            "p2.name" -> "Bob",
            "p3.name" -> "Alice",
            "p4.name" -> "Bob"
          )
        ))
    }
  }

  describe("disconnected match clauses") {

    it("disconnected components") {
      // Given
      val given = initGraph(
        """
          |CREATE (p1:Narcissist {name: "Alice"})
          |CREATE (p2:Narcissist {name: "Bob"})
          |CREATE (p1)-[:LOVES]->(p1)
          |CREATE (p2)-[:LOVES]->(p2)
        """.stripMargin)

      // When
      val result = given.cypher(
        """
          |MATCH (a:Narcissist), (b:Narcissist)
          |RETURN a.name AS one, b.name AS two
        """.stripMargin)

      // Then
      result.getRecords.toMaps should equal(
        Bag(
          CypherMap("one" -> "Alice", "two" -> "Alice"),
          CypherMap("one" -> "Alice", "two" -> "Bob"),
          CypherMap("one" -> "Bob", "two" -> "Bob"),
          CypherMap("one" -> "Bob", "two" -> "Alice")
        ))
    }

    it("joined components") {
      PrintPhysicalPlan.set
      // Given
      val given = initGraph(
        """
          |CREATE (p1:Narcissist {name: "Alice"})
          |CREATE (p2:Narcissist {name: "Bob"})
          |CREATE (p1)-[:LOVES]->(p1)
          |CREATE (p2)-[:LOVES]->(p2)
        """.stripMargin)

      // When
      val result = given.cypher(
        """
          |MATCH (a:Narcissist), (b:Narcissist) WHERE a.name = b.name
          |RETURN a.name AS one, b.name AS two
        """.stripMargin)

      // Then
      result.getRecords.toMaps should equal(
        Bag(
          CypherMap("one" -> "Alice", "two" -> "Alice"),
          CypherMap("one" -> "Bob", "two" -> "Bob")
        ))

      // TODO: Move to plan based testing
      result.plans.asCaps.logical should include("ValueJoin")
    }

    it("can evaluate cross Product between multiple match clauses") {
      val graph = initGraph("CREATE (:A {val: 0}), (:B {val: 1})-[:REL]->(:C {val: 2})")
      val query =
        """
          |MATCH (a:A)
          |MATCH (b:B)-->(c:C)
          |RETURN a.val, c.val
        """.stripMargin

      graph.cypher(query).getRecords.collect.toBag should equal(Bag(
        CypherMap("a.val" -> 0, "c.val" -> 2)
      ))
    }
  }

  describe("undirected patterns") {
    it("matches an undirected relationship") {
      val given = initGraph(
        """
          |CREATE (a:A {prop: 'isA'})
          |CREATE (b:B {prop: 'fromA'})
          |CREATE (c:C {prop: 'toA'})
          |CREATE (d:D)
          |CREATE (a)-[:T]->(b)
          |CREATE (b)-[:T]->(c)
          |CREATE (c)-[:T]->(a)
        """.stripMargin
      )

      val result = given.cypher("MATCH (a:A)--(other) RETURN a.prop, other.prop")

      result.getRecords.collect.toBag should equal(Bag(
        CypherMap("a.prop" -> "isA", "other.prop" -> "fromA"),
        CypherMap("a.prop" -> "isA", "other.prop" -> "toA")
      ))
    }

    it("matches an undirected relationship with two hops") {
      val given = initGraph(
        """
          |CREATE (a:A {prop: 'a'})
          |CREATE (b:B {prop: 'b'})
          |CREATE (c:C {prop: 'c'})
          |CREATE (d:D {prop: 'd'})
          |CREATE (a)-[:T]->(b)
          |CREATE (b)-[:T]->(c)
          |CREATE (c)-[:T]->(a)
          |CREATE (c)-[:T]->(d)
        """.stripMargin
      )

      val result = given.cypher("MATCH (a:A)--()--(other) RETURN a.prop, other.prop")

      result.getRecords.collect.toBag should equal(Bag(
        CypherMap("a.prop" -> "a", "other.prop" -> "c"),
        CypherMap("a.prop" -> "a", "other.prop" -> "b"),
        CypherMap("a.prop" -> "a", "other.prop" -> "d")
      ))
    }

    it("matches an undirected pattern with pre-bound nodes") {
      val given = initGraph(
        """
          |CREATE (a:A {prop: 'a'})
          |CREATE (b:B {prop: 'b'})
          |CREATE (b)-[:T]->(a)
          |CREATE (a)-[:T]->(b)
        """.stripMargin
      )

      val result = given.cypher(
        """
          |MATCH (a:A)
          |MATCH (b:B)
          |MATCH (a)--(b)
          |RETURN a.prop, b.prop
        """.stripMargin)

      result.getRecords.collect.toBag should equal(Bag(
        CypherMap("a.prop" -> "a", "b.prop" -> "b"),
        CypherMap("a.prop" -> "a", "b.prop" -> "b")
      ))
    }

    it("matches a mixed directed/undirected pattern") {
      val given = initGraph(
        """
          |CREATE (a:A {prop: 'a'})
          |CREATE (b:B {prop: 'b'})
          |CREATE (c:C {prop: 'c'})
          |CREATE (a)-[:T]->(a)
          |CREATE (a)-[:T]->(a)
          |CREATE (b)-[:T]->(a)
          |CREATE (a)-[:T]->(c)
        """.stripMargin
      )

      val result = given.cypher("MATCH (a:A)--(a)<--(other) RETURN a.prop, other.prop")

      result.getRecords.collect.toBag should equal(Bag(
        CypherMap("a.prop" -> "a", "other.prop" -> "a"),
        CypherMap("a.prop" -> "a", "other.prop" -> "a"),
        CypherMap("a.prop" -> "a", "other.prop" -> "b"),
        CypherMap("a.prop" -> "a", "other.prop" -> "b")
      ))
    }

    it("matches an undirected cyclic relationship") {
      val given = initGraph(
        """
          |CREATE (a:A {prop: 'isA'})
          |CREATE (b:B)
          |CREATE (a)-[:T]->(a)
          |CREATE (b)-[:T]->(a)
        """.stripMargin
      )

      val result = given.cypher("MATCH (a:A)--(a) RETURN a.prop")

      result.getRecords.collect.toBag should equal(Bag(
        CypherMap("a.prop" -> "isA")
      ))
    }

    it("matches an undirected variable-length relationship") {
      val given = initGraph(
        """
          |CREATE (a:A {prop: 'a'})
          |CREATE (b:B {prop: 'b'})
          |CREATE (c:C {prop: 'c'})
          |CREATE (a)-[:T]->(b)
          |CREATE (b)<-[:T]-(c)
        """.stripMargin
      )

      val result = given.cypher("MATCH (a:A)-[*2..2]-(other) RETURN a.prop, other.prop")

      result.getRecords.collect.toBag should equal(Bag(
        CypherMap("a.prop" -> "a", "other.prop" -> "c")
      ))
    }
  }

  ignore("Broken start of demo query") {
    // Given
    val given = initGraph(
      """
        |CREATE (a:Person {name: "Philip"})
        |CREATE (b:Person {name: "Stefan"})
        |CREATE (c:City {name: "The Pan-European Sprawl"})
        |CREATE (a)-[:KNOWS]->(b)
        |CREATE (a)-[:LIVES_IN]->(c)
        |CREATE (b)-[:LIVES_IN]->(c)
      """.stripMargin)

    // Change last b to x: et voila, it works
    val result = given.cypher(
      "MATCH (a:Person)-[:LIVES_IN]->(city:City)<-[:LIVES_IN]-(b:Person), (a)-[:KNOWS*1..2]->(b) RETURN *"
    )
  }
}
