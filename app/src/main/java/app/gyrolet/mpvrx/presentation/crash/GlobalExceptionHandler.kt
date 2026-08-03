/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.presentation.crash

import android.content.Context
import android.content.Intent
import kotlin.system.exitProcess

class GlobalExceptionHandler(
  private val context: Context,
  private val activity: Class<*>,
) : Thread.UncaughtExceptionHandler {
  override fun uncaughtException(
    t: Thread,
    e: Throwable,
  ) {
    val intent = Intent(context, activity)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    intent.putExtra("exception", e.stackTraceToString())
    context.startActivity(intent)
    exitProcess(0)
  }
}
