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
package org.opencypher.okapi.relational.impl.table

import org.opencypher.okapi.api.types._
import org.opencypher.okapi.ir.api.expr._
import org.opencypher.okapi.ir.api.{Label, PropertyKey, RelType}
import org.opencypher.okapi.ir.test.support.MatchHelper._
import org.scalatest.{FunSpec, Matchers}

class RecordHeaderNewTest extends FunSpec with Matchers {

  val n: Var = Var("n")(CTNode)
  val m: Var = Var("m")(CTNode)
  val o: Var = Var("o")(CTNode)
  val r: Var = Var("r")(CTRelationship)

  val countN = CountStar(CTInteger)

  val nLabelA: HasLabel = HasLabel(n, Label("A"))(CTBoolean)
  val nLabelB: HasLabel = HasLabel(n, Label("B"))(CTBoolean)
  val nPropFoo: Property = Property(n, PropertyKey("foo"))(CTString)
  val nExprs: Set[Expr] = Set(n, nLabelA, nLabelB, nPropFoo)
  val mExprs: Set[Expr] = nExprs.map(_.withOwner(m))
  val oExprs: Set[Expr] = nExprs.map(_.withOwner(o))

  val rStart: StartNode = StartNode(r)(CTNode)
  val rEnd: EndNode = EndNode(r)(CTNode)
  val rRelType: HasType = HasType(r, RelType("R"))(CTBoolean)
  val rPropFoo: Property = Property(r, PropertyKey("foo"))(CTString)
  val rExprs: Set[Expr] = Set(r, rStart, rEnd, rRelType, rPropFoo)

  val nHeader: RecordHeaderNew = RecordHeaderNew.empty.withExprs(nExprs)
  val mHeader: RecordHeaderNew = RecordHeaderNew.empty.withExprs(mExprs)
  val rHeader: RecordHeaderNew = RecordHeaderNew.empty.withExprs(rExprs)

  it("can add an entity expression") {
    nHeader.ownedBy(n) should equal(nExprs)
  }

  it("can add an alias for an entity") {
    val withAlias = nHeader.withAlias(m, n)

    withAlias.ownedBy(n) should equalWithTracing(nExprs)
    withAlias.ownedBy(m) should equalWithTracing(mExprs)
  }

  it("can add an alias for a non-entity expression") {
    val s = Var("nPropFoo_Alias")(nPropFoo.cypherType)
    val t = Var("nPropFoo_Alias")(nPropFoo.cypherType)
    val withAlias1 = nHeader.withAlias(s, nPropFoo)
    val withAlias2 = withAlias1.withAlias(t, s)

    withAlias2.column(s) should equalWithTracing(withAlias2.column(nPropFoo))
    withAlias2.column(t) should equalWithTracing(withAlias2.column(nPropFoo))
    withAlias2.ownedBy(n) should equalWithTracing(nExprs)
    withAlias2.ownedBy(s) should equalWithTracing(Set(s))
    withAlias2.ownedBy(t) should equalWithTracing(Set(t))
  }

  it("can combine simple headers") {
    val unionHeader = nHeader ++ mHeader

    unionHeader.ownedBy(n) should equalWithTracing(nExprs)
    unionHeader.ownedBy(m) should equalWithTracing(mExprs)
  }

  it("can combine complex headers") {
    val p = Var("nPropFoo_Alias")(nPropFoo.cypherType)

    val nHeaderWithAlias = nHeader.withAlias(p, nPropFoo)
    val mHeaderWithAlias = mHeader.withAlias(o, m)

    val unionHeader = nHeaderWithAlias ++ mHeaderWithAlias

    unionHeader.column(p) should equal(unionHeader.column(nPropFoo))
    unionHeader.ownedBy(n) should equalWithTracing(nExprs)
    unionHeader.ownedBy(m) should equalWithTracing(mExprs)
    unionHeader.ownedBy(o) should equalWithTracing(oExprs)
  }

  it("can modify alias and original expression") {
    val prop2 = Property(n, PropertyKey("bar"))(CTString)
    val aliasHeader = nHeader.withAlias(m, n)
    val withNewProp = aliasHeader.withExpr(prop2)

    withNewProp.ownedBy(n) should equalWithTracing(nExprs + prop2)
    withNewProp.ownedBy(m) should equalWithTracing(mExprs + prop2.withOwner(m))
  }

  it("can return all aliases for an expression") {
    val s = Var("nPropFoo_Alias")(nPropFoo.cypherType)
    val t = Var("nPropFoo_Alias")(nPropFoo.cypherType)
    val aliasHeader = nHeader
      .withAlias(m, n)
      .withAlias(s, nPropFoo)
      .withAlias(t, s)

    aliasHeader.aliasesFor(n) should equalWithTracing(Set(m, n))
    aliasHeader.aliasesFor(m) should equalWithTracing(Set(m, n))
    aliasHeader.aliasesFor(nLabelA) should equalWithTracing(Set.empty)
    aliasHeader.aliasesFor(nPropFoo) should equalWithTracing(Set(s, t))
    aliasHeader.aliasesFor(s) should equalWithTracing(Set(s, t))
  }

  it("adds a new child expr for all aliases of owner") {
    val prop2 = Property(n, PropertyKey("bar"))(CTString)
    val aliasHeader = nHeader
      .withAlias(m, n)
      .withExpr(prop2)

    aliasHeader.ownedBy(n) should equalWithTracing(nExprs + prop2)
    aliasHeader.ownedBy(m) should equalWithTracing(mExprs + prop2.withOwner(m))
  }

  it("finds all id columns") {
    nHeader.idColumns should equalWithTracing(Set(nHeader.column(n)))

    rHeader.idColumns should equalWithTracing(
      Set(rHeader.column(r), rHeader.column(rStart), rHeader.column(rEnd))
    )

    val rExtendedHeader = nHeader ++ rHeader
    rExtendedHeader.idColumns should equalWithTracing(
      Set(rExtendedHeader.column(n), rExtendedHeader.column(r), rExtendedHeader.column(rStart), rExtendedHeader.column(rEnd))
    )
  }

  it("finds entity properties") {
    nHeader.propertiesFor(n) should equalWithTracing(Set(nPropFoo))
    rHeader.propertiesFor(r) should equalWithTracing(Set(rPropFoo))
  }

  it("finds start and end nodes") {
    rHeader.startNodeFor(r) should equalWithTracing(rStart)
    rHeader.endNodeFor(r) should equalWithTracing(rEnd)
  }

  it("returns members for an entity") {
    nHeader.ownedBy(n) should equalWithTracing(nExprs)
    rHeader.ownedBy(r) should equalWithTracing(rExprs)
  }

  it("returns labels for a node") {
    nHeader.labelsFor(n) should equalWithTracing(Set(nLabelA, nLabelB))
    nHeader.labelsFor(m) should equalWithTracing(Set.empty)
  }

  it("returns type for a rel") {
    rHeader.typeFor(r) should equalWithTracing(Some(rRelType))
    nHeader.typeFor(r) should equalWithTracing(None)
  }

  it("returns all node vars") {
    nHeader.nodeVars should equalWithTracing(Set(n))
    rHeader.nodeVars should equalWithTracing(Set.empty)
  }

  it("returns all rel vars") {
    rHeader.relationshipVars should equalWithTracing(Set(r))
    nHeader.relationshipVars should equalWithTracing(Set.empty)
  }

  it("returns all node vars for a given node type") {
    nHeader.nodesForType(CTNode("A")) should equalWithTracing(Set(n))
    nHeader.nodesForType(CTNode("A", "B")) should equalWithTracing(Set(n))
    nHeader.nodesForType(CTNode("C")) should equalWithTracing(Set.empty)
  }

  it("returns all rel vars for a given rel type") {
    rHeader.relationshipsForType(CTRelationship("R")) should equalWithTracing(Set(r))
    rHeader.relationshipsForType(CTRelationship("R", "S")) should equalWithTracing(Set(r))
    rHeader.relationshipsForType(CTRelationship("S")) should equalWithTracing(Set.empty)
  }

}
