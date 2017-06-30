package org.opencypher.spark.impl.instances

import org.opencypher.spark.api.value.CypherMap
import org.opencypher.spark.impl.instances.spark.cypher._
import org.opencypher.spark.{GraphMatchingTestSupport, TestSuiteImpl}

class WithAcceptanceTest extends TestSuiteImpl with GraphMatchingTestSupport {

  test("projecting variables in scope") {

    // Given
    val given = TestGraph("""(:Node {val: 4L})-->(:Node {val: 5L})""")

    // When
    val result = given.cypher("MATCH (n:Node)-->(m:Node) WITH n, m RETURN n.val")

    // Then
    result.records.toMaps should equal(Set(
      CypherMap("n.val" -> 4)
    ))

    // And
    result.graph shouldMatch given.graph
  }
}
