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
package org.opencypher.okapi.neo4j.io

import java.util.function.BiConsumer

import org.neo4j.driver.v1.Session
import org.opencypher.okapi.api.value.CypherValue
import org.opencypher.okapi.api.value.CypherValue.CypherValue

import scala.collection.JavaConverters._

/**
  * Inefficient convenience methods.
  */
object Neo4jHelpers {

  /**
    * This module defines constants that are used for interactions with the Neo4j database
    */
  object Neo4jDefaults {
    val metaPrefix: String = "___"

    val metaPropertyKey: String = s"${metaPrefix}capsID"

    val idPropertyKey: String = s"${metaPrefix}capsID"

    val startIdPropertyKey: String = s"${metaPrefix}capsSTART_ID"

    val endIdPropertyKey: String = s"${metaPrefix}capsEND_ID"

    val entityVarName = "e"
  }

  implicit class RichLabelString(val label: String) extends AnyVal {
    def cypherLabelPredicate: String = s":`$label`"
  }

  implicit class RichLabelSet(val labels: Set[String]) extends AnyVal {
    def cypherLabelPredicate:String = if (labels.isEmpty) "" else labels.map(_.cypherLabelPredicate).mkString("")
  }

  implicit class RichConfig(val config: Neo4jConfig) extends AnyVal {

    def withSession[T](f: Session => T): T = {
      val driver = config.driver()
      val session = driver.session()
      try {
        f(session)
      } finally {
        session.closeAsync.whenCompleteAsync(new BiConsumer[Void, Throwable]() {
          override def accept(result: Void, error: Throwable):Unit = {
            driver.closeAsync()
          }
        })
      }
    }

    /**
      * Creates a new driver and session just for one Cypher query and returns the result as a list of maps
      * that represent rows. Convenient and inefficient.
      *
      * @param query Cypher query to execute
      * @return list of result rows with each row represented as a map
      */
    def cypher(query: String): List[Map[String, CypherValue]] = {
      withSession { session =>
        session.run(query).list().asScala.map(_.asMap().asScala.mapValues(CypherValue(_)).toMap).toList
      }
    }
  }

}
