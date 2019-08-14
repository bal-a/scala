/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala
package reflect
package internal

import scala.annotation.unchecked.uncheckedStable
import settings.MutableSettings

/** Provides delegates to the reporter doing the actual work.
 *  All forwarding methods should be marked final,
 *  but some subclasses out of our reach still override them.
 *
 *  Eventually, this interface should be reduced to one method: `reporter`,
 *  and clients should indirect themselves (reduce duplication of forwarders).
 */
trait Reporting { self : Positions =>
  def reporter: Reporter
  def currentRun: RunReporting

  trait RunReporting {
    val reporting: PerRunReporting = PerRunReporting
  }

  type PerRunReporting <: PerRunReportingBase
  protected def PerRunReporting: PerRunReporting
  abstract class PerRunReportingBase {
    def deprecationWarning(pos: Position, msg: String, since: String): Unit

    /** Have we already supplemented the error message of a compiler crash? */
    private[this] var supplementedError = false
    def supplementErrorMessage(errorMessage: String): String =
      if (supplementedError) errorMessage
      else {
        supplementedError = true
        supplementTyperState(errorMessage)
      }

  }

  // overridden in Global
  def supplementTyperState(errorMessage: String): String = errorMessage

  def supplementErrorMessage(errorMessage: String) = currentRun.reporting.supplementErrorMessage(errorMessage)

  @deprecatedOverriding("This forwards to the corresponding method in reporter -- override reporter instead", "2.11.2")
  def inform(msg: String): Unit      = inform(NoPosition, msg)
  @deprecatedOverriding("This forwards to the corresponding method in reporter -- override reporter instead", "2.11.2")
  def warning(msg: String): Unit     = warning(NoPosition, msg)
  // globalError(msg: String) used to abort -- not sure that was a good idea, so I made it more regular
  // (couldn't find any uses that relied on old behavior)
  @deprecatedOverriding("This forwards to the corresponding method in reporter -- override reporter instead", "2.11.2")
  def globalError(msg: String): Unit = globalError(NoPosition, msg)

  def abort(msg: String): Nothing = {
    val augmented = supplementErrorMessage(msg)
    // Needs to call error to make sure the compile fails.
    globalError(augmented)
    throw new FatalError(augmented)
  }

  @deprecatedOverriding("This forwards to the corresponding method in reporter -- override reporter instead", "2.11.2")
  def inform(pos: Position, msg: String)      = reporter.echo(pos, msg)
  @deprecatedOverriding("This forwards to the corresponding method in reporter -- override reporter instead", "2.11.2")
  def warning(pos: Position, msg: String)     = reporter.warning(pos, msg)
  @deprecatedOverriding("This forwards to the corresponding method in reporter -- override reporter instead", "2.11.2")
  def globalError(pos: Position, msg: String) = reporter.error(pos, msg)
}

import util.Position

/** Report information, warnings and errors.
 *
 *  This describes the (future) external interface for issuing information, warnings and errors.
 *  Currently, scala.tools.nsc.Reporter is used by sbt/ide.
 */
abstract class Reporter {
  private[this] var _errorCount = 0
  private[this] var _warningCount = 0

  // sbt source compatibility
  final type Severity = Reporter.Severity
  @uncheckedStable final def INFO: Severity    = Reporter.INFO
  @uncheckedStable final def WARNING: Severity = Reporter.WARNING
  @uncheckedStable final def ERROR: Severity   = Reporter.ERROR

  // TODO: rename to `doReport`, remove the `force` parameter.
  // Note: `force` is ignored. It used to mean: if `!force`, the reporter may skip INFO messages.
  // If `force`, INFO messages were always printed. Now, INFO messages are always printed.
  protected def info0(pos: Position, msg: String, severity: Severity, force: Boolean): Unit

  // 0: count and display
  // 1: count only, don't display
  // 2: don't count, don't display
  def filter(pos: Position, msg: String, severity: Severity): Int = 0

  final def echo(msg: String): Unit = echo(util.NoPosition, msg)
  final def echo(pos: Position, msg: String): Unit =
    if (filter(pos, msg, INFO) == 0)
      info0(pos, msg, INFO, force = true)

  def warning(pos: Position, msg: String): Unit = {
    val f = filter(pos, msg, WARNING)
    if (f <= 1) increment(WARNING)
    if (f == 0) info0(pos, msg, WARNING, force = false)
  }

  def error(pos: Position, msg: String): Unit = {
    val f = filter(pos, msg, ERROR)
    if (f <= 1) increment(ERROR)
    if (f == 0) info0(pos, msg, ERROR, force = false)
  }

  def increment(severity: Severity): Unit =
    if (severity == ERROR) _errorCount += 1
    else if (severity == WARNING) _warningCount += 1

  def errorCount: Int = _errorCount
  def warningCount: Int = _warningCount

  def hasErrors: Boolean = errorCount > 0
  def hasWarnings: Boolean = warningCount > 0

  def reset(): Unit = {
    _errorCount = 0
    _warningCount = 0
  }

  def flush(): Unit = ()

  /** Finish reporting: print summaries, release resources. */
  def finish(): Unit = ()

  /** After reporting, offer advice on getting more details.
    * Does not access `this`, but not static because it's overridden in ReplReporterImpl.
    */
  def rerunWithDetails(setting: MutableSettings#Setting, name: String): String =
    setting.value match {
      case b: Boolean if !b => s"; re-run with ${name} for details"
      case _ => s"; re-run enabling ${name} for details, or try -help"
    }
}

object Reporter {
  sealed class Severity(val id: Int, override val toString: String)
  object INFO    extends Severity(0, "INFO")
  object WARNING extends Severity(1, "WARNING")
  object ERROR   extends Severity(2, "ERROR")
}
