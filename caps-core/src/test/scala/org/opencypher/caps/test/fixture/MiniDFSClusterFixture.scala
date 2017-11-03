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
package org.opencypher.caps.test.fixture

import java.net.URI

import org.apache.hadoop.fs.Path
import org.apache.hadoop.hdfs.MiniDFSCluster
import org.apache.spark.sql.Row
import org.opencypher.caps.test.BaseTestSuite

trait MiniDFSClusterFixture extends BaseTestFixture {

  self: SparkSessionFixture with BaseTestSuite =>

  protected var dfsCluster: MiniDFSCluster = _

  protected val dfsTestGraphPath: String

  protected def hdfsURI: URI = hdfsURI(dfsTestGraphPath)

  protected def hdfsURI(path: String): URI = URI.create(s"hdfs://${dfsCluster.getNameNode.getHostAndPort}$path")

  abstract override def beforeAll(): Unit = {
    super.beforeAll()
    dfsCluster = new MiniDFSCluster.Builder(session.sparkContext.hadoopConfiguration).build()
    dfsCluster.waitClusterUp()
    dfsCluster.getFileSystem.copyFromLocalFile(
      new Path(getClass.getResource(dfsTestGraphPath).toString),
      new Path(dfsTestGraphPath))
  }

  abstract override def afterAll: Unit = {
    dfsCluster.shutdown(true)
    super.afterAll()
  }

  /**
    * Returns the expected nodes for the test graph in /resources/csv/sn
    *
    * @return expected nodes
    */
  def dfsTestGraphNodes: Set[Row] = Set(
    Row(1L, true,  true, false, true,  "Stefan",   42L),
    Row(2L, false, true,  true, true,    "Mats",   23L),
    Row(3L, true,  true, false, true,  "Martin", 1337L),
    Row(4L, true,  true, false, true,     "Max",    8L)
  )

  /**
    * Returns the expected rels for the test graph in /resources/csv/sn
    *
    * @return expected rels
    */
  def dfsTestGraphRels: Set[Row] = Set(
    Row(1L, 10L, "KNOWS", 2L, 2016L),
    Row(2L, 20L, "KNOWS", 3L, 2017L),
    Row(3L, 30L, "KNOWS", 4L, 2015L)
  )
}
