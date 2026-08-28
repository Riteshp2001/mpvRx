/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.tv

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor

@Immutable
data class TvUiEnvironment(
  val isTelevision: Boolean = false,
  val hasTouchscreen: Boolean = true,
)

val LocalTvUiEnvironment = staticCompositionLocalOf { TvUiEnvironment() }

fun Context.tvUiEnvironment(): TvUiEnvironment =
  TvUiEnvironment(
    isTelevision = DeviceFormFactor.isTelevision(this),
    hasTouchscreen = DeviceFormFactor.hasTouchscreen(this),
  )

@Composable
fun rememberTvUiEnvironment(): TvUiEnvironment {
  val context = LocalContext.current
  return androidx.compose.runtime.remember(context) { context.tvUiEnvironment() }
}
