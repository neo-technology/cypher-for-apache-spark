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

import java.net.URI

import org.neo4j.driver.v1._
import org.opencypher.okapi.api.value.CypherValue
import org.opencypher.okapi.api.value.CypherValue.CypherValue
import scala.collection.JavaConverters._

case class Neo4jConfig(
  uri: URI,
  user: String = "neo4j",
  password: Option[String] = None,
  encrypted: Boolean = true
) {

  def driver(): Driver = password match {
    case Some(pwd) => GraphDatabase.driver(uri, AuthTokens.basic(user, pwd), boltConfig())
    case _ => GraphDatabase.driver(uri, boltConfig())
  }

  private def boltConfig(): Config = {
    val builder = Config.build

    if (encrypted)
      builder.withEncryption().toConfig
    else
      builder.withoutEncryption().toConfig
  }

  def execute[T](f: Session => T): T = {
    val session = driver().session
    val t = f(session)
    session.close()
    t
  }

  def cypher(query: String): List[Map[String, CypherValue]] = {
    execute { session =>
      session.run(query).list().asScala.map(_.asMap().asScala.mapValues(CypherValue(_)).toMap).toList
    }
  }
}
