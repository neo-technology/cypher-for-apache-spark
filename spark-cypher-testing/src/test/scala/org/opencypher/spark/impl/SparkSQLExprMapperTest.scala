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
package org.opencypher.spark.impl

import java.util.Collections

import org.apache.spark.sql.types.{IntegerType, StructField, StructType}
import org.apache.spark.sql.{Column, DataFrame, Row}
import org.opencypher.okapi.ir.api.expr.{Expr, Subtract, Var}
import org.opencypher.okapi.relational.impl.table.{IRecordHeader, OpaqueField, ProjectedField}
import org.opencypher.okapi.testing.BaseTestSuite
import org.opencypher.spark.impl.SparkSQLExprMapper._
import org.opencypher.spark.impl.physical.CAPSRuntimeContext
import org.opencypher.spark.testing.fixture.SparkSessionFixture

import scala.language.implicitConversions

class SparkSQLExprMapperTest extends BaseTestSuite with SparkSessionFixture {

  test("can map subtract") {
    val expr = Subtract(Var("a")(), Var("b")())()

    convert(expr, _header.addContent(ProjectedField(Var("foo")(), expr))) should equal(
      df.col("a") - df.col("b")
    )
  }

  private def convert(expr: Expr, header: IRecordHeader = _header): Column = {
    expr.asSparkSQLExpr(header, df, CAPSRuntimeContext.empty)
  }

  val _header: IRecordHeader = IRecordHeader.empty.addContents(Seq(OpaqueField(Var("a")()), OpaqueField(Var("b")())))
  val df: DataFrame = sparkSession.createDataFrame(
    Collections.emptyList[Row](),
    StructType(Seq(StructField("a", IntegerType), StructField("b", IntegerType))))

  implicit def extractRecordHeaderFromResult[T](tuple: (IRecordHeader, T)): IRecordHeader = tuple._1
}
