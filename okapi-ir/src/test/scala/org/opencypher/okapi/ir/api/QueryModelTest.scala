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
package org.opencypher.okapi.ir.api

import org.opencypher.okapi.impl.exception.IllegalStateException
import org.opencypher.okapi.ir.api.block._
import org.opencypher.okapi.ir.impl.IrTestSuite

class QueryModelTest extends IrTestSuite {

  val block_a = BlockRef("a")
  val block_b = BlockRef("b")
  val block_c = BlockRef("c")
  val block_d = BlockRef("d")
  val block_e = BlockRef("e")

  test("dependencies") {
    val model = irFor(
      block_a,
      Map(
        block_a -> DummyBlock(Set(block_b, block_c)),
        block_b -> DummyBlock(),
        block_c -> DummyBlock()
      )).model

    model.dependencies(block_a) should equal(Set(block_b, block_c))
    model.dependencies(block_b) should equal(Set.empty)
    model.dependencies(block_c) should equal(Set.empty)
  }

  test("all_dependencies") {
    val model = irFor(
      block_a,
      Map(
        block_a -> DummyBlock(Set(block_b, block_c)),
        block_b -> DummyBlock(Set(block_d)),
        block_c -> DummyBlock(),
        block_d -> DummyBlock(Set(block_e)),
        block_e -> DummyBlock()
      )
    ).model

    model.allDependencies(block_a) should equal(Set(block_b, block_c, block_d, block_e))
    model.allDependencies(block_b) should equal(Set(block_d, block_e))
    model.allDependencies(block_c) should equal(Set.empty)
    model.allDependencies(block_d) should equal(Set(block_e))
    model.allDependencies(block_e) should equal(Set.empty)
  }

  test("handle loops") {
    val model = irFor(
      block_a,
      Map(
        block_a -> DummyBlock(Set(block_b, block_c)),
        block_b -> DummyBlock(Set(block_d)),
        block_c -> DummyBlock(Set(block_b)),
        block_d -> DummyBlock(Set(block_c))
      )
    ).model

    an[IllegalStateException] shouldBe thrownBy {
      model.allDependencies(block_a)
    }
    an[IllegalStateException] shouldBe thrownBy {
      model.allDependencies(block_b)
    }
    an[IllegalStateException] shouldBe thrownBy {
      model.allDependencies(block_c)
    }
    an[IllegalStateException] shouldBe thrownBy {
      model.allDependencies(block_d)
    }
  }
}
