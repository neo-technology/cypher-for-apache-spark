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
package org.opencypher.caps.api.value

import org.opencypher.caps.common.{Maybe, Ternary, True}

class CypherValueEqualTest extends CypherValueTestSuite {

  import CypherTestValues._
  import instances._
  import syntax._

  test("PATH equal") {
    verifyEqual(PATH_valueGroups)
  }

  test("RELATIONSHIP equal") {
    verifyEqual(RELATIONSHIP_valueGroups)
  }

  test("NODE equal") {
    verifyEqual(NODE_valueGroups)
  }

  test("MAP equal") {
    verifyEqual(MAP_valueGroups)
  }

  test("LIST equal") {
    verifyEqual(LIST_valueGroups)
  }

  test("BOOLEAN equal") {
    verifyEqual(BOOLEAN_valueGroups)
  }

  test("INTEGER equal") {
    verifyEqual(INTEGER_valueGroups)
  }

  test("FLOAT equal") {
    verifyEqual(FLOAT_valueGroups)
  }

  test("NUMBER equal") {
    verifyEqual(NUMBER_valueGroups)
  }

  test("ANY equal") {
    verifyEqual(ANY_valueGroups)
  }

  def verifyEqual[V <: CypherValue : CypherValueCompanion](valueGroups: ValueGroups[V]): Unit = {
    val values = valueGroups.flatten

    values.foreach { v => equal[V](v, v) should be(if (v.comparesNulls) Maybe else True) }
    values.foreach { v => (cypherNull[V] `equalTo` v) should be(Maybe) }
    values.foreach { v => (v `equalTo` cypherNull[V]) should be(Maybe) }

    (cypherNull[V] `equalTo` cypherNull[V]) should be(Maybe)

    values.foreach { v1 =>
      values.foreach { v2 =>
        if (v1.comparesNulls || v2.comparesNulls)
          equal[V](v1, v2) should be(Maybe)
        else {
          equal[V](v1, v2) should be(Ternary(v1 == v2))
        }
      }
    }
  }

  private def equal[V <: CypherValue : CypherValueCompanion](v1: V, v2: V): Ternary = {
    val cmp1 = CypherValueCompanion[V].equal(v1, v2)
    val cmp2 = CypherValueCompanion[V].equal(v2, v1)

    cmp1 should equal(cmp2)

    cmp1
  }
}
