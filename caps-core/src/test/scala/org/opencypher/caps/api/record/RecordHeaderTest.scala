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
package org.opencypher.caps.api.record

import org.opencypher.caps.api.expr._
import org.opencypher.caps.api.types._
import org.opencypher.caps.impl.record.{Added, Found, Replaced}
import org.opencypher.caps.impl.syntax.header._
import org.opencypher.caps.impl.util.toVar
import org.opencypher.caps.ir.api.{Label, PropertyKey, RelType}
import org.opencypher.caps.test.BaseTestSuite

import scala.language.implicitConversions

class RecordHeaderTest extends BaseTestSuite {

  test("select") {
    val nSlots = Set(
      OpaqueField('n),
      ProjectedExpr(Property('n, PropertyKey("prop"))()),
      ProjectedExpr(HasLabel('n, Label("Foo"))())
    )
    val pSlots = Set(
      OpaqueField('p),
      ProjectedExpr(Property('p, PropertyKey("prop"))())
    )

    val h1 = RecordHeader.empty.update(addContents((nSlots ++ pSlots).toSeq))._1

    h1.select(Set('n)).slots.map(_.content).toSet should equal(nSlots)
    h1.select(Set('p)).slots.map(_.content).toSet should equal(pSlots)
    h1.select(Set('r)).slots.map(_.content).toSet should equal(Set.empty)
    h1.select(Set('n, 'p, 'r)).slots.map(_.content).toSet should equal(nSlots ++ pSlots)
  }

  test("Can add projected expressions") {
    val content               = ProjectedExpr(TrueLit())
    val (result, Added(slot)) = RecordHeader.empty.update(addContent(content))

    slot should equal(RecordSlot(0, content))
    result.slots should equal(Seq(slot))
    result.fields should equal(Set.empty)
  }

  test("Can add opaque fields") {
    val content               = OpaqueField('n)
    val (result, Added(slot)) = RecordHeader.empty.update(addContent(content))

    slot should equal(RecordSlot(0, content))
    result.slots should equal(Seq(slot))
    result.fields should equal(Set(toVar('n)))
  }

  test("Can re-add opaque fields") {
    val content         = OpaqueField('n)
    val (result, slots) = RecordHeader.empty.update(addContents(Seq(content, content)))
    val slot            = RecordSlot(0, content)

    slots should equal(Vector(Added(slot), Found(slot)))
    result.slots should equal(Seq(slot))
    result.fields should equal(Set(toVar('n)))
  }

  test("Can add projected fields") {
    val content               = ProjectedField('n, TrueLit())
    val (result, Added(slot)) = RecordHeader.empty.update(addContent(content))

    slot should equal(RecordSlot(0, content))
    result.slots should equal(Seq(slot))
    result.fields should equal(Set(toVar('n)))
  }

  test("Adding projected expressions re-uses previously added projected expressions") {
    val content                     = ProjectedExpr(TrueLit())
    val (oldHeader, Added(oldSlot)) = RecordHeader.empty.update(addContent(content))
    val (newHeader, Found(newSlot)) = oldHeader.update(addContent(content))

    oldSlot should equal(RecordSlot(0, content))
    newSlot should equal(oldSlot)
    newHeader.slots should equal(Seq(newSlot))
    newHeader.fields should equal(Set.empty)
  }

  test("Adding projected expressions re-uses previously added projected fields") {
    val oldContent                  = ProjectedField('n, TrueLit())
    val (oldHeader, Added(oldSlot)) = RecordHeader.empty.update(addContent(oldContent))
    val newContent                  = ProjectedExpr(TrueLit())
    val (newHeader, Found(newSlot)) = oldHeader.update(addContent(newContent))

    oldSlot should equal(RecordSlot(0, oldContent))
    newSlot should equal(oldSlot)
    newHeader.slots should equal(Seq(newSlot))
    newHeader.fields should equal(Set(toVar('n)))
  }

  test("Adding projected field will alias previously added projected expression") {
    val oldContent                               = ProjectedExpr(TrueLit())
    val (oldHeader, Added(oldSlot))              = RecordHeader.empty.update(addContent(oldContent))
    val newContent                               = ProjectedField('n, TrueLit())
    val (newHeader, Replaced(prevSlot, newSlot)) = oldHeader.update(addContent(newContent))

    oldSlot should equal(RecordSlot(0, oldContent))
    prevSlot should equal(oldSlot)
    newSlot should equal(RecordSlot(0, newContent))
    newHeader.slots should equal(Seq(newSlot))
    newHeader.fields should equal(Set(toVar('n)))
  }

  test("Adding projected field will alias previously added projected expression 2") {
    val oldContent                               = ProjectedExpr(Property('n, PropertyKey("prop"))())
    val (oldHeader, Added(oldSlot))              = RecordHeader.empty.update(addContent(oldContent))
    val newContent                               = ProjectedField(Var("n.text")(CTString), Property('n, PropertyKey("prop"))())
    val (newHeader, Replaced(prevSlot, newSlot)) = oldHeader.update(addContent(newContent))

    oldSlot should equal(RecordSlot(0, oldContent))
    prevSlot should equal(oldSlot)
    newSlot should equal(RecordSlot(0, newContent))
    newHeader.slots should equal(Seq(newSlot))
    newHeader.fields should equal(Set(Var("n.text")(CTString)))
  }

  test("Adding opaque field will replace previously existing") {
    val oldContent                               = OpaqueField(Var("n")(CTNode))
    val (oldHeader, Added(oldSlot))              = RecordHeader.empty.update(addContent(oldContent))
    val newContent                               = OpaqueField(Var("n")(CTNode("User")))
    val (newHeader, Replaced(prevSlot, newSlot)) = oldHeader.update(addContent(newContent))

    oldSlot should equal(RecordSlot(0, oldContent))
    prevSlot should equal(oldSlot)
    newSlot should equal(RecordSlot(0, newContent))
    newHeader.slots should equal(Seq(newSlot))
    newHeader.fields should equal(Set(Var("n")(CTNode("User"))))
  }

  test("concatenating headers") {
    var lhs = RecordHeader.empty
    var rhs = RecordHeader.empty

    lhs ++ rhs should equal(lhs)

    lhs = lhs.update(addContent(ProjectedExpr(Var("n")(CTNode))))._1
    lhs ++ rhs should equal(lhs)

    rhs = rhs.update(addContent(OpaqueField(Var("m")(CTRelationship))))._1
    (lhs ++ rhs).slots.map(_.content) should equal(
      Seq(
        ProjectedExpr(Var("n")(CTNode)),
        OpaqueField(Var("m")(CTRelationship))
      ))
  }

  test("concatenating headers with similar properties") {
    val (lhs, _) = RecordHeader.empty.update(
      addContent(ProjectedExpr(Property(Var("n")(), PropertyKey("name"))(CTInteger))))
    val (rhs, _) = RecordHeader.empty.update(
      addContent(ProjectedExpr(Property(Var("n")(), PropertyKey("name"))(CTString))))

    val concatenated = lhs ++ rhs

    concatenated.slots should equal(
      IndexedSeq(
        RecordSlot(0, ProjectedExpr(Property(Var("n")(), PropertyKey("name"))(CTInteger))),
        RecordSlot(1, ProjectedExpr(Property(Var("n")(), PropertyKey("name"))(CTString)))
      ))
  }

  test("can get labels") {
    val field1 = OpaqueField('n)
    val label1 = ProjectedExpr(HasLabel('n, Label("A"))(CTBoolean))
    val label2 = ProjectedExpr(HasLabel('n, Label("B"))(CTBoolean))
    val prop   = ProjectedExpr(Property('n, PropertyKey("foo"))(CTString))
    val field2 = OpaqueField('m)
    val prop2  = ProjectedExpr(Property('m, PropertyKey("bar"))(CTString))

    val (h1, _)     = RecordHeader.empty.update(addContent(field1))
    val (h2, _)     = h1.update(addContent(label1))
    val (h3, _)     = h2.update(addContent(label2))
    val (h4, _)     = h3.update(addContent(prop))
    val (h5, _)     = h4.update(addContent(field2))
    val (header, _) = h3.update(addContent(prop2))

    header.labels(Var("n")(CTNode)) should equal(
      Seq(
        HasLabel('n, Label("A"))(CTBoolean),
        HasLabel('n, Label("B"))(CTBoolean)
      )
    )
  }

  test("can get all slots for a given node var") {
    val field1 = OpaqueField('n)
    val label1 = ProjectedExpr(HasLabel('n, Label("A"))(CTBoolean))
    val label2 = ProjectedExpr(HasLabel('n, Label("B"))(CTBoolean))
    val prop   = ProjectedExpr(Property('n, PropertyKey("foo"))(CTString))
    val field2 = OpaqueField('m)
    val prop2  = ProjectedExpr(Property('m, PropertyKey("bar"))(CTString))

    val (h1, _)     = RecordHeader.empty.update(addContent(field1))
    val (h2, _)     = h1.update(addContent(label1))
    val (h3, _)     = h2.update(addContent(label2))
    val (h4, _)     = h3.update(addContent(prop))
    val (h5, _)     = h4.update(addContent(field2))
    val (header, _) = h5.update(addContent(prop2))

    header.childSlots(Var("n")(CTNode)) should equal(
      Seq(
        RecordSlot(1, label1),
        RecordSlot(2, label2),
        RecordSlot(3, prop)
      )
    )
  }

  test("can get all slots for a given edge var") {
    val field1  = OpaqueField('e1)
    val source1 = ProjectedExpr(StartNode('e1)(CTNode))
    val target1 = ProjectedExpr(EndNode('e1)(CTNode))
    val type1   = ProjectedExpr(HasType('e1, RelType("KNOWS"))(CTInteger))
    val prop1   = ProjectedExpr(Property('e1, PropertyKey("foo"))(CTString))
    val field2  = OpaqueField('e2)
    val source2 = ProjectedExpr(StartNode('e2)(CTNode))
    val target2 = ProjectedExpr(EndNode('e2)(CTNode))
    val type2   = ProjectedExpr(HasType('e2, RelType("KNOWS"))(CTInteger))
    val prop2   = ProjectedExpr(Property('e2, PropertyKey("bar"))(CTString))

    val (h1, _)     = RecordHeader.empty.update(addContent(field1))
    val (h2, _)     = h1.update(addContent(source1))
    val (h3, _)     = h2.update(addContent(target1))
    val (h4, _)     = h3.update(addContent(type1))
    val (h5, _)     = h4.update(addContent(prop1))
    val (h6, _)     = h5.update(addContent(field2))
    val (h7, _)     = h6.update(addContent(source2))
    val (h8, _)     = h7.update(addContent(target2))
    val (h9, _)     = h8.update(addContent(type2))
    val (header, _) = h9.update(addContent(prop2))

    header.childSlots(Var("e1")(CTRelationship)) should equal(
      Seq(
        RecordSlot(1, source1),
        RecordSlot(2, target1),
        RecordSlot(3, type1),
        RecordSlot(4, prop1)
      )
    )
  }

  test("labelSlots") {
    val field1 = OpaqueField('n)
    val field2 = OpaqueField('m)
    val label1 = ProjectedExpr(HasLabel('n, Label("A"))(CTBoolean))
    val label2 = ProjectedField('foo, HasLabel('n, Label("B"))(CTBoolean))
    val label3 = ProjectedExpr(HasLabel('m, Label("B"))(CTBoolean))
    val prop   = ProjectedExpr(Property('n, PropertyKey("foo"))(CTString))

    val (header, _) =
      RecordHeader.empty.update(addContents(Seq(field1, label1, label2, prop, field2, label3)))

    header.labelSlots('n).mapValues(_.content) should equal(
      Map(label1.expr                                                       -> label1, label2.expr -> label2))
    header.labelSlots('m).mapValues(_.content) should equal(Map(label3.expr -> label3))
    header.labelSlots('q).mapValues(_.content) should equal(Map.empty)
  }

  test("propertySlots") {
    val node1    = OpaqueField('n)
    val node2    = OpaqueField('m)
    val rel      = OpaqueField('r)
    val label    = ProjectedExpr(HasLabel('n, Label("A"))(CTBoolean))
    val propFoo1 = ProjectedExpr(Property('n, PropertyKey("foo"))(CTString))
    val propBar1 = ProjectedField('foo, Property('n, PropertyKey("bar"))(CTString))
    val propBaz  = ProjectedExpr(Property('n, PropertyKey("baz"))(CTString))
    val propFoo2 = ProjectedExpr(Property('r, PropertyKey("foo"))(CTString))
    val propBar2 = ProjectedExpr(Property('r, PropertyKey("bar"))(CTString))

    val (header, _) = RecordHeader.empty.update(
      addContents(
        Seq(
          node1,
          node2,
          rel,
          label,
          propFoo1,
          propFoo2,
          propBar1,
          propBar2,
          propBaz
        )))

    header.propertySlots('n).mapValues(_.content) should equal(
      Map(propFoo1.expr -> propFoo1, propBar1.expr -> propBar1, propBaz.expr -> propBaz))
    header.propertySlots('m).mapValues(_.content) should equal(Map.empty)
    header.propertySlots('r).mapValues(_.content) should equal(
      Map(propFoo2.expr -> propFoo2, propBar2.expr -> propBar2))
  }

  test("nodesForType") {
    val p: Var = 'p -> CTNode("Person")
    val n: Var = 'n -> CTNode
    val q: Var = 'q -> CTNode("Foo")
    val fields = Seq(
      OpaqueField(p),
      ProjectedExpr(HasLabel(p, Label("Fireman"))()),
      OpaqueField(n),
      ProjectedExpr(HasLabel(n, Label("Person"))()),
      ProjectedExpr(HasLabel(n, Label("Fireman"))()),
      ProjectedExpr(HasLabel(n, Label("Foo"))()),
      OpaqueField(q),
      OpaqueField('r -> CTRelationship("KNOWS"))
    )
    val (header, _) = RecordHeader.empty.update(addContents(fields))

    header.nodesForType(CTNode) should equal(Seq(p, n, q))
    header.nodesForType(CTNode("Person")) should equal(Seq(p, n))
    header.nodesForType(CTNode("Fireman")) should equal(Seq(p, n))
    header.nodesForType(CTNode("Foo")) should equal(Seq(n, q))
    header.nodesForType(CTNode("Person", "Foo")) should equal(Seq(n))
    header.nodesForType(CTNode("Nop")) should equal(Seq.empty)
  }

  test("relsForType") {
    val p: Var = 'p -> CTRelationship("KNOWS")
    val r: Var = 'r -> CTRelationship
    val q: Var = 'q -> CTRelationship("LOVES", "HATES")
    val fields = Seq(
      OpaqueField(p),
      OpaqueField(r),
      OpaqueField(q),
      OpaqueField('n -> CTNode("Foo"))
    )
    val (header, _) = RecordHeader.empty.update(addContents(fields))

    header.relationshipsForType(CTRelationship) should equal(List(p, r, q))
    header.relationshipsForType(CTRelationship("KNOWS")) should equal(List(p, r))
    header.relationshipsForType(CTRelationship("LOVES")) should equal(List(r, q))
    header.relationshipsForType(CTRelationship("LOVES", "HATES")) should equal(List(r, q))
  }
}
