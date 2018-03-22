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
package org.opencypher.spark.impl.acceptance

import org.opencypher.okapi.api.value.CypherValue.CypherMap
import org.opencypher.spark.impl.CAPSGraph

import org.opencypher.okapi.ir.test.support.Bag

trait ExpandIntoBehaviour {
  self: AcceptanceTest =>

  def expandIntoBehaviour(initGraph: String => CAPSGraph): Unit = {
    test("test expand into for dangling edge") {
      // Given
      val given = initGraph(
        """
          |CREATE (p1:Person {name: "Alice"})
          |CREATE (p2:Person {name: "Bob"})
          |CREATE (p3:Person {name: "Eve"})
          |CREATE (p4:Person {name: "Carl"})
          |CREATE (p5:Person {name: "Richard"})
          |CREATE (p1)-[:KNOWS]->(p2)
          |CREATE (p2)-[:KNOWS]->(p3)
          |CREATE (p1)-[:KNOWS]->(p3)
          |CREATE (p3)-[:KNOWS]->(p4)
          |CREATE (p3)-[:KNOWS]->(p5)
        """.stripMargin)

      // When
      val result = given.cypher(
        """
          |MATCH (p1:Person)-[e1:KNOWS]->(p2:Person),
          |(p2)-[e2:KNOWS]->(p3:Person),
          |(p1)-[e3:KNOWS]->(p3),
          |(p3)-[e4:KNOWS]->(p4)
          |RETURN p1.name, p2.name, p3.name, p4.name
        """.stripMargin)

      // Then
      result.getRecords.toMaps should equal(Bag(
        CypherMap(
          "p1.name" -> "Alice",
          "p2.name" -> "Bob",
          "p3.name" -> "Eve",
          "p4.name" -> "Carl"
        ),
        CypherMap(
          "p1.name" -> "Alice",
          "p2.name" -> "Bob",
          "p3.name" -> "Eve",
          "p4.name" -> "Richard"
        )
      ))
    }

    test("test expand into for triangle") {
      // Given
      val given = initGraph(
        """
          |CREATE (p1:Person {name: "Alice"})
          |CREATE (p2:Person {name: "Bob"})
          |CREATE (p3:Person {name: "Eve"})
          |CREATE (p1)-[:KNOWS]->(p2)
          |CREATE (p2)-[:KNOWS]->(p3)
          |CREATE (p1)-[:KNOWS]->(p3)
        """.stripMargin)

      // When
      val result = given.cypher(
        """
          |MATCH (p1:Person)-[e1:KNOWS]->(p2:Person),
          |(p2)-[e2:KNOWS]->(p3:Person),
          |(p1)-[e3:KNOWS]->(p3)
          |RETURN p1.name, p2.name, p3.name
        """.stripMargin)

      // Then
      result.getRecords.toMaps should equal(Bag(
        CypherMap(
          "p1.name" -> "Alice",
          "p2.name" -> "Bob",
          "p3.name" -> "Eve"
        )
      ))
    }

    test("Expand into after var expand") {
      // Given
      val given = initGraph(
        """
          |CREATE (p1:Person {name: "Alice"})
          |CREATE (p2:Person {name: "Bob"})
          |CREATE (comment:Comment)
          |CREATE (post1:Post {content: "asdf"})
          |CREATE (post2:Post {content: "foobar"})
          |CREATE (p1)-[:KNOWS]->(p2)
          |CREATE (p2)<-[:HASCREATOR]-(comment)
          |CREATE (comment)-[:REPLYOF]->(post1)-[:REPLYOF]->(post2)
          |CREATE (post2)-[:HASCREATOR]->(p1)
        """.stripMargin)

      // When
      val result = given.cypher(
        """
          |MATCH (p1:Person)-[e1:KNOWS]->(p2:Person),
          |      (p2)<-[e2:HASCREATOR]-(comment:Comment),
          |      (comment)-[e3:REPLYOF*1..10]->(post:Post),
          |      (p1)<-[:HASCREATOR]-(post)
          |WHERE p1.name = "Alice"
          |RETURN p1.name, p2.name, post.content
        """.stripMargin
      )

      // Then
      result.getRecords.toMaps should equal(Bag(
        CypherMap(
          "p1.name" -> "Alice",
          "p2.name" -> "Bob",
          "post.content" -> "foobar"
        )
      ))
    }
  }
}
