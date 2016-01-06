/* =========================================================================================
 * Copyright © 2013-2016 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.instrumentation.jdbc

import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit.{ NANOSECONDS ⇒ nanos }

import kamon.Kamon
import kamon.jdbc.JdbcExtension
import kamon.jdbc.metric.StatementsMetrics
import kamon.trace.{ SegmentCategory, TraceContext, Tracer }

import scala.util.control.NonFatal

object SqlProcessor {

  val SelectStatement = "(?i)^\\s*select.*?\\sfrom[\\s\\[]+([^\\]\\s,)(;]*).*".r
  val InsertStatement = "(?i)^\\s*insert(?:\\s+ignore)?\\s+into\\s+([^\\s(,;]*).*".r
  val UpdateStatement = "(?i)^\\s*update\\s+([^\\s,;]*).*".r
  val DeleteStatement = "(?i)^\\s*delete\\s+from\\s+([^\\s,(;]*).*".r
  val CommentPattern = "/\\*.*?\\*/" //for now only removes comments of kind / * anything * /
  val Empty = ""
  val Statements = "jdbc-statements"
  val Select = "Select"
  val Insert = "Insert"
  val Update = "Update"
  val Delete = "Delete"

  implicit class PimpedCallable(callable: Callable[_]) {
    def proceedWithErrorHandler(sql: String)(implicit statementRecorder: StatementsMetrics): Any = {
      try {
        callable.call()
      } catch {
        case NonFatal(cause) ⇒
          JdbcExtension.processSqlError(sql, cause)
          statementRecorder.errors.increment()
          throw cause
      }
    }
  }

  def processStatement(callable: Callable[_], sql: String) = {
    Tracer.currentContext.collect { ctx ⇒
      implicit val statementRecorder = Kamon.metrics.entity(StatementsMetrics, "jdbc-statements")

      sql.replaceAll(CommentPattern, Empty) match {
        case SelectStatement(_) ⇒ withSegment(ctx, Select)(recordRead(callable, sql))
        case InsertStatement(_) ⇒ withSegment(ctx, Insert)(recordWrite(callable, sql))
        case UpdateStatement(_) ⇒ withSegment(ctx, Update)(recordWrite(callable, sql))
        case DeleteStatement(_) ⇒ withSegment(ctx, Delete)(recordWrite(callable, sql))
        case anythingElse ⇒
          JdbcExtension.log.debug(s"Unable to parse sql [$sql]")
          callable.call()
      }
    }
  } getOrElse callable.call()

  def withTimeSpent[A](thunk: ⇒ A)(timeSpent: Long ⇒ Unit): A = {
    val start = System.nanoTime()
    try thunk finally timeSpent(System.nanoTime() - start)
  }

  def withSegment[A](ctx: TraceContext, statement: String)(thunk: ⇒ A): A = {
    val segmentName = JdbcExtension.generateJdbcSegmentName(statement)
    val segment = ctx.startSegment(segmentName, SegmentCategory.Database, JdbcExtension.SegmentLibraryName)
    try thunk finally segment.finish()
  }

  def recordRead(callable: Callable[_], sql: String)(implicit statementRecorder: StatementsMetrics): Any = {
    withTimeSpent(callable.proceedWithErrorHandler(sql)) { timeSpent ⇒
      statementRecorder.reads.record(timeSpent)

      val timeSpentInMillis = nanos.toMillis(timeSpent)

      if (timeSpentInMillis >= JdbcExtension.slowQueryThreshold) {
        statementRecorder.slows.increment()
        JdbcExtension.processSlowQuery(sql, timeSpentInMillis)
      }
    }
  }

  def recordWrite(callable: Callable[_], sql: String)(implicit statementRecorder: StatementsMetrics): Any = {
    withTimeSpent(callable.proceedWithErrorHandler(sql)) { timeSpent ⇒
      statementRecorder.writes.record(timeSpent)
    }
  }
}