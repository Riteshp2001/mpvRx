/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.logging

import app.gyrolet.mpvrx.presentation.crash.AppLogging
import timber.log.Timber

/**
 * mpvRx's log, with the same calls as android.util.Log. Every line goes through Timber to logcat
 * and to the in-app log (Debug logs, crash reports), which keeps it even when Android drops or
 * never returns the app's logcat. See [app.gyrolet.mpvrx.presentation.crash.AppLogBuffer].
 */
object Log {
  const val VERBOSE = android.util.Log.VERBOSE
  const val DEBUG = android.util.Log.DEBUG
  const val INFO = android.util.Log.INFO
  const val WARN = android.util.Log.WARN
  const val ERROR = android.util.Log.ERROR
  const val ASSERT = android.util.Log.ASSERT

  @JvmStatic fun v(tag: String?, msg: String?): Int = write(VERBOSE, tag, msg, null)

  @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = write(VERBOSE, tag, msg, tr)

  @JvmStatic fun d(tag: String?, msg: String?): Int = write(DEBUG, tag, msg, null)

  @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = write(DEBUG, tag, msg, tr)

  @JvmStatic fun i(tag: String?, msg: String?): Int = write(INFO, tag, msg, null)

  @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = write(INFO, tag, msg, tr)

  @JvmStatic fun w(tag: String?, msg: String?): Int = write(WARN, tag, msg, null)

  @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = write(WARN, tag, msg, tr)

  @JvmStatic fun w(tag: String?, tr: Throwable?): Int = write(WARN, tag, null, tr)

  @JvmStatic fun e(tag: String?, msg: String?): Int = write(ERROR, tag, msg, null)

  @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = write(ERROR, tag, msg, tr)

  @JvmStatic fun wtf(tag: String?, msg: String?): Int = write(ASSERT, tag, msg, null)

  @JvmStatic fun wtf(tag: String?, tr: Throwable): Int = write(ASSERT, tag, null, tr)

  @JvmStatic fun wtf(tag: String?, msg: String?, tr: Throwable?): Int = write(ASSERT, tag, msg, tr)

  @JvmStatic fun println(priority: Int, tag: String?, msg: String?): Int = write(priority, tag, msg, null)

  @JvmStatic fun isLoggable(tag: String?, level: Int): Boolean = android.util.Log.isLoggable(tag, level)

  @JvmStatic fun getStackTraceString(tr: Throwable?): String = android.util.Log.getStackTraceString(tr)

  private fun write(
    priority: Int,
    tag: String?,
    msg: String?,
    tr: Throwable?,
  ): Int {
    AppLogging.ensureInstalled()
    Timber.tag(tag ?: AppLogging.DEFAULT_TAG).log(priority, tr, msg)
    return msg?.length ?: 0
  }
}
