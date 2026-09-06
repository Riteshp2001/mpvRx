/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor

fun Modifier.tvFocusHighlight(
  shape: Shape = RoundedCornerShape(8.dp),
  enabled: Boolean = true,
): Modifier =
  composed {
    val isTelevision = DeviceFormFactor.isTelevision(LocalContext.current)
    if (!isTelevision || !enabled) return@composed this

    var focused by remember { mutableStateOf(false) }
    this
      .onFocusChanged { state -> focused = state.isFocused || state.hasFocus }
      .then(
        if (focused) {
          Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
        } else {
          Modifier
        },
      )
  }

@Composable
fun rememberTvInitialFocusRequester(enabled: Boolean = true): FocusRequester {
  val isTelevision = DeviceFormFactor.isTelevision(LocalContext.current)
  val requester = remember { FocusRequester() }
  LaunchedEffect(isTelevision, enabled) {
    if (isTelevision && enabled) {
      withFrameNanos { }
      runCatching { requester.requestFocus() }
    }
  }
  return requester
}

fun Modifier.tvInitialFocus(requester: FocusRequester): Modifier =
  composed {
    if (DeviceFormFactor.isTelevision(LocalContext.current)) this.focusRequester(requester) else this
  }
