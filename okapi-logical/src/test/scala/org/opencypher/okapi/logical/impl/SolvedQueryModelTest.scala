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
package org.opencypher.okapi.logical.impl

import java.net.URI

import org.opencypher.okapi.api.types.CypherType._
import org.opencypher.okapi.impl.exception.IllegalArgumentException
import org.opencypher.okapi.ir.api.RelType
import org.opencypher.okapi.ir.api.block._
import org.opencypher.okapi.ir.api.expr.{Equals, Expr, _}
import org.opencypher.okapi.ir.api.pattern.Pattern
import org.opencypher.okapi.ir.impl.util.VarConverters._
import org.opencypher.okapi.testing.BaseTestSuite

class SolvedQueryModelTest extends BaseTestSuite with IrConstruction {

  implicit val uri = URI.create("test")

  test("add fields") {
    val s = SolvedQueryModel.empty.withField('a).withFields('b, 'c)

    s.fields should equal(Set('a, 'b, 'c).map(toField))
  }

  test("contains a block") {
    val block = matchBlock(Pattern.empty.withEntity('a).withEntity('b).withEntity('c))
    val s = SolvedQueryModel.empty.withField('a).withFields('b, 'c)

    s.contains(block) shouldBe true
  }

  test("contains several blocks") {
    val block1 = matchBlock(Pattern.empty.withEntity('a -> CTAnyNode))
    val block2 = matchBlock(Pattern.empty.withEntity('b -> CTAnyNode))
    val binds: Fields[Expr] = Fields(Map(toField('c) -> Equals('a, 'b)(CTBoolean)))
    val block3 = project(binds)
    val block4 = project(ProjectedFieldsOf(toField('d) -> Equals('c, 'b)(CTBoolean)))

    val s = SolvedQueryModel.empty.withField('a).withFields('b, 'c)

    s.contains(block1) shouldBe true
    s.contains(block1, block2) shouldBe true
    s.contains(block1, block2, block3) shouldBe true
    s.contains(block1, block2, block3, block4) shouldBe false
  }

  test("solves") {
    val s = SolvedQueryModel.empty.withField('a).withFields('b, 'c)
    val p = Pattern.empty[Expr].withEntity('a -> CTAnyNode).withEntity('b -> CTAnyNode).withEntity('c -> CTAnyNode)

    s.solves(toField('a)) shouldBe true
    s.solves(toField('b)) shouldBe true
    s.solves(toField('x)) shouldBe false
    s.solves(p) shouldBe true
    s.solves(p.withEntity('x -> CTAnyNode)) shouldBe false
  }

  it("can solve a relationship") {
    val s = SolvedQueryModel.empty

    an [IllegalArgumentException] should be thrownBy s.solveRelationship('a)
    s.solveRelationship('r -> CTAnyRelationship) should equal(SolvedQueryModel.empty.withField('r -> CTAnyRelationship))
    s.solveRelationship('r -> CTRelationship("KNOWS")) should equal(
      SolvedQueryModel.empty
        .withField('r -> CTAnyRelationship)
        .withPredicate(HasType(Var("r")(CTRelationship("KNOWS")), RelType("KNOWS"))(CTBoolean))
    )
    s.solveRelationship('r -> CTRelationship("KNOWS", "LOVES", "HATES")) should equal(
      SolvedQueryModel.empty
        .withField('r -> CTAnyRelationship)
        .withPredicate(Ors(
          HasType(RelationshipVar("r")(), RelType("KNOWS"))(CTBoolean),
          HasType(RelationshipVar("r")(), RelType("LOVES"))(CTBoolean),
          HasType(RelationshipVar("r")(), RelType("HATES"))(CTBoolean)))
    )
  }
}
