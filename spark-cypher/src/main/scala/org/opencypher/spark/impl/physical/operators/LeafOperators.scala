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
package org.opencypher.spark.impl.physical.operators

import org.opencypher.okapi.api.graph.QualifiedGraphName
import org.opencypher.okapi.relational.impl.table.IRecordHeader
import org.opencypher.spark.api.CAPSSession
import org.opencypher.spark.impl.CAPSRecords
import org.opencypher.spark.impl.physical.{CAPSPhysicalResult, CAPSRuntimeContext}

private[spark] abstract class LeafPhysicalOperator extends CAPSPhysicalOperator {

  override def execute(implicit context: CAPSRuntimeContext): CAPSPhysicalResult = {
    executeLeaf()
  }

  def executeLeaf()(implicit context: CAPSRuntimeContext): CAPSPhysicalResult
}

object Start {

  def apply(qgn: QualifiedGraphName, records: CAPSRecords)(implicit caps: CAPSSession): Start = {
    Start(qgn, Some(records))
  }

}

final case class Start(qgn: QualifiedGraphName, recordsOpt: Option[CAPSRecords])
  (implicit caps: CAPSSession) extends LeafPhysicalOperator with PhysicalOperatorDebugging {

  override val header = recordsOpt.map(_.header).getOrElse(IRecordHeader.empty)

  override def executeLeaf()(implicit context: CAPSRuntimeContext): CAPSPhysicalResult = {
    val records = recordsOpt.getOrElse(CAPSRecords.unit())
    CAPSPhysicalResult(records, resolve(qgn), qgn)
  }

  override def toString = {
    val graphArg = qgn.toString
    val recordsArg = recordsOpt.map(_.toString)
    val allArgs = List(recordsArg, graphArg).mkString(", ")
    s"Start($allArgs)"
  }

}
