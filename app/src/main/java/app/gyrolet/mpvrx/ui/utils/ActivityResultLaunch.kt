/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import app.gyrolet.mpvrx.R

fun <I, O> ManagedActivityResultLauncher<I, O>.launchSafely(
  context: Context,
  input: I,
): Boolean =
  try {
    launch(input)
    true
  } catch (_: ActivityNotFoundException) {
    Toast.makeText(context, R.string.tv_system_picker_unavailable, Toast.LENGTH_LONG).show()
    false
  }
