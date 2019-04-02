/*
 * Copyright (c) 2016-2019 "Neo4j Sweden, AB" [https://neo4j.com]
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
package org.opencypher.okapi.api

import org.opencypher.okapi.api.graph.QualifiedGraphName

import scala.language.postfixOps

package object types {

  trait TemporalValueCypherType extends CypherType

  trait TemporalInstantCypherType extends CypherType

  case object CTAnyMaterial extends CypherType {
    override lazy val nullable: CypherType = CTAny

    override def name: String = "ANY"
  }

  object CTMap extends CTMap(Map.empty) {
    override def name: String = "ANYMAP"

    override def toString: String = name

    override def equals(obj: Any): Boolean = obj.isInstanceOf[CTMap.type]

    override def canEqual(that: Any): Boolean = that.isInstanceOf[CTMap.type]
  }

  case class CTMap(properties: Map[String, CypherType] = Map.empty) extends CypherType {

    override def containsNullable: Boolean = properties.values.exists(_.containsNullable)

    override def name: String = {
      s"MAP(${properties.map { case (n, t) => s"$n: ${t.name}" }.mkString(", ")})"
    }

  }

  object CTList extends CTList(CTAny)

  case class CTList(inner: CypherType) extends CypherType {

    override def containsNullable: Boolean = inner.containsNullable

    override def name: String = s"LIST(${inner.name})"

  }

  object CTNode extends CTNode(Set.empty, None) {
    def apply(labels: String*): CTNode = CTNode(labels.toSet)

    def unapply(ct: CypherType): Option[(Set[String], Option[QualifiedGraphName])] = {
      ct match {
        case n: CTNode => Some(n.labels -> n.graph)
        case u: CTUnion => u.alternatives.collectFirst { case n: CTNode => n.labels -> n.graph }
        case _ => None
      }
    }
  }

  case class CTNode(
    labels: Set[String] = Set.empty,
    override val graph: Option[QualifiedGraphName] = None
  ) extends CypherType {
    override def withGraph(qgn: QualifiedGraphName): CTNode = copy(graph = Some(qgn))
    override def withoutGraph: CypherType = CTNode(labels)
    override def name: String = s"NODE(${labels.map(l => s":$l").mkString})${graph.map(g => s" @ $g").getOrElse("")}"
    override def toString: String = s"CTNode(${labels.map(l => "\"" + l + "\"").mkString(", ")})"
  }

  object CTRelationship extends CTRelationship(Set.empty, None) {
    def apply(relTypes: String*): CTRelationship = CTRelationship(relTypes.toSet)

    def unapply(ct: CypherType): Option[(Set[String], Option[QualifiedGraphName])] = {
      ct match {
        case r: CTRelationship => Some(r.types -> r.graph)
        case u: CTUnion => u.alternatives.collectFirst { case r: CTRelationship => r.types -> r.graph }
        case _ => None
      }
    }
  }

  case class CTRelationship(
    types: Set[String] = Set.empty,
    override val graph: Option[QualifiedGraphName] = None
  ) extends CypherType {
    override def withGraph(qgn: QualifiedGraphName): CTRelationship = copy(graph = Some(qgn))
    override def withoutGraph: CypherType = CTRelationship(types)

    override def name: String = {
      if (this == CTRelationship) {
        "RELATIONSHIP"
      } else {
        s"RELATIONSHIP(${types.map(l => s":$l").mkString("|")})${graph.map(g => s" @ $g").getOrElse("")}"
      }
    }

    override def toString: String = {
      if (this == CTRelationship) "CTRelationship"
      else s"CTRelationship(${types.map(t => "\"" + t + "\"").mkString(", ")})"
    }

  }

  case object CTString extends CypherType

  case object CTInteger extends CypherType

  case object CTFloat extends CypherType

  case object CTTrue extends CypherType

  case object CTFalse extends CypherType

  case object CTNull extends CypherType {
    override def isNullable: Boolean = true
    override def material: CypherType = CTVoid
  }

  case object CTIdentity extends CypherType

  case object CTLocalDateTime extends TemporalInstantCypherType

  case object CTDate extends TemporalInstantCypherType

  case object CTDuration extends TemporalValueCypherType

  case object CTVoid extends CypherType

  case class CTUnion(alternatives: Set[CypherType]) extends CypherType {
    require(!alternatives.exists(_.isInstanceOf[CTUnion]), "Unions need to be flattened")

    override def isNullable: Boolean = alternatives.contains(CTNull)

    override def material: CypherType = CTUnion((alternatives - CTNull).toSeq: _*)

    override def name: String = {
      if (this == CTAny) "ANY?"
      else if (this == CTBoolean) "BOOLEAN"
      else if (this == CTNumber) "NUMBER"
      else if (isNullable) s"${material.name}?"
      else throw new UnsupportedOperationException(s"Type $toString does not have a name")
    }

    override def graph: Option[QualifiedGraphName] = alternatives.flatMap(_.graph).headOption

    override def toString: String = {
      if (this == CTAny) "CTAny"
      else s"CTUnion(${alternatives.mkString(", ")})"
    }

  }

  object CTUnion {
    def apply(ts: CypherType*): CypherType = {
      val flattened = ts.flatMap {
        case CTUnion(as) => as
        case p => Set(p)
      }.distinct.toList

      // Filter alternatives that are a subtype of another alternative
      val filtered = flattened.filter(t => !flattened.exists(o => o != t && t.subTypeOf(o)))

      filtered match {
        case Nil => CTVoid
        case h :: Nil => h
        case many if many.contains(CTAnyMaterial) => if (many.contains(CTNull)) CTAny else CTAnyMaterial
        case many => CTUnion(many.toSet)
      }
    }
  }

  case object CTPath extends CypherType

  val CTNumber: CypherType = CTUnion(CTFloat, CTInteger)

  val CTBoolean: CypherType = CTUnion(CTTrue, CTFalse)

  val CTEntity: CypherType = CTUnion(CTNode, CTRelationship)

  val CTAny: CypherType = CTUnion(CTAnyMaterial, CTNull)

}
