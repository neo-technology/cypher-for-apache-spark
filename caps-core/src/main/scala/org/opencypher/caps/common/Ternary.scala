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
package org.opencypher.caps.common

import scala.language.implicitConversions

object Ternary {
  implicit def apply(v: Boolean): Ternary = if (v) True else False
  implicit def apply(v: Option[Boolean]): Ternary = v.map(Ternary(_)).getOrElse(Maybe)
}

sealed trait Ternary {
  def isTrue: Boolean
  def isFalse: Boolean
  def isDefinite: Boolean
  def isUnknown: Boolean

  def maybeTrue: Boolean
  def maybeFalse: Boolean

  def and(other: Ternary): Ternary
  def or(other: Ternary): Ternary

  def negated: Ternary

  final def orNull: java.lang.Boolean = if (isDefinite) isTrue else null
  def toOption: Option[Boolean]
}

sealed private[caps] trait DefiniteTernary extends Ternary {
  def isDefinite: Boolean = true
  def isUnknown: Boolean = false
}

case object True extends DefiniteTernary {
  override def isTrue = true
  override def isFalse = false

  override def maybeTrue = true
  override def maybeFalse = false

  override def and(other: Ternary): Ternary = other
  override def or(other: Ternary): Ternary = True
  override def negated: False.type = False

  override val toOption: Some[Boolean] = Some(true)

  override def toString = "definitely true"
}

case object False extends DefiniteTernary {
  override def isTrue = false
  override def isFalse = true

  override def maybeTrue = false
  override def maybeFalse = true

  override def and(other: Ternary): Ternary = False
  override def or(other: Ternary): Ternary = other
  override def negated: True.type = True

  override val toOption: Some[Boolean] = Some(false)

  override def toString = "definitely false"
}

case object Maybe extends Ternary {
  override def isTrue = false
  override def isFalse = false
  override def isDefinite = false
  override def isUnknown = true

  override def maybeTrue = true
  override def maybeFalse = true

  override def and(other: Ternary): Ternary = other match {
    case False => False
    case _     => Maybe
  }

  override def or(other: Ternary): Ternary = other match {
    case True => True
    case _    => Maybe
  }

  override def negated: Maybe.type = Maybe

  override def toOption: None.type = None

  override def toString = "maybe"
}
