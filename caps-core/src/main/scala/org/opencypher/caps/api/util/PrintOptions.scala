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
package org.opencypher.caps.api.util

import java.io.PrintStream

object PrintOptions {
  private val DEFAULT_COLUMN_WIDTH = 20
  private val DEFAULT_MARGIN       = 2

  implicit lazy val out: PrintOptions =
    PrintOptions(stream = Console.out, columnWidth = DEFAULT_COLUMN_WIDTH, margin = DEFAULT_MARGIN)

  lazy val err: PrintOptions =
    PrintOptions(stream = Console.err, columnWidth = DEFAULT_COLUMN_WIDTH, margin = DEFAULT_MARGIN)

  def current(implicit options: PrintOptions): PrintOptions =
    options
}

final case class PrintOptions(stream: PrintStream, columnWidth: Int, margin: Int) {
  def stream(newStream: PrintStream): PrintOptions   = copy(stream = newStream)
  def columnWidth(newColumnWidth: Int): PrintOptions = copy(columnWidth = newColumnWidth)
  def margin(newMargin: Int): PrintOptions           = copy(margin = newMargin)
}
