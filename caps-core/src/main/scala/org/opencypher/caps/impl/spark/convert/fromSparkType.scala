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
package org.opencypher.caps.impl.spark.convert

import org.apache.spark.sql.types._
import org.opencypher.caps.api.types._
import org.opencypher.caps.impl.spark.exception.Raise

object fromSparkType extends Serializable {

  def apply(dt: DataType, nullable: Boolean): Option[CypherType] = {
    val result = dt match {
      case StringType => Some(CTString)
      case LongType => Some(CTInteger)
      case BooleanType => Some(CTBoolean)
      case BinaryType => Some(CTAny)
      case DoubleType => Some(CTFloat)
      case ArrayType(elemType, containsNull) =>
        val maybeElementType = fromSparkType(elemType, containsNull)
        maybeElementType.map(CTList(_))
      case _ => None
    }

    if (nullable) result.map(_.nullable) else result.map(_.material)
  }

}
