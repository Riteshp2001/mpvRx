/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.tv.tryRequestFocus
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor
import kotlinx.coroutines.delay

fun Modifier.tvFocusHighlight(
  shape: Shape = RoundedCornerShape(8.dp),
  enabled: Boolean = true,
  focusedScale: Float = 1f,
  clipToShape: Boolean = true,
): Modifier =
  composed {
    val isTelevision = DeviceFormFactor.isTelevision(LocalContext.current)
    val shapeClip = if (clipToShape) Modifier.clip(shape) else Modifier
    if (!isTelevision || !enabled) return@composed shapeClip

    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
      targetValue = if (focused) focusedScale.coerceAtLeast(1f) else 1f,
      animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
      label = "tv_focus_scale",
    )
    this
      .onFocusChanged { state -> focused = state.isFocused || state.hasFocus }
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
      }.then(shapeClip)
      .then(
        if (focused) {
          Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
        } else {
          Modifier
        },
      )
  }

@Composable
fun rememberTvInitialFocusRequester(
  enabled: Boolean = true,
  requestKey: Any? = Unit,
): FocusRequester {
  val isTelevision = DeviceFormFactor.isTelevision(LocalContext.current)
  val requester = remember(requestKey) { FocusRequester() }
  LaunchedEffect(isTelevision, enabled, requestKey) {
    if (isTelevision && enabled) {
      withFrameNanos { }
      // Lazy sheet/dialog content can attach after the first frame of its entrance.
      // Stop as soon as focus succeeds, so this never resets a user's subsequent moves.
      repeat(15) {
        if (requester.tryRequestFocus()) return@LaunchedEffect
        delay(100)
      }
    }
  }
  return requester
}

fun Modifier.tvInitialFocus(requester: FocusRequester): Modifier =
  composed {
    if (DeviceFormFactor.isTelevision(LocalContext.current)) this.focusRequester(requester) else this
  }

fun Modifier.tvFocusGroup(): Modifier =
  composed {
    if (DeviceFormFactor.isTelevision(LocalContext.current)) this.focusGroup() else this
  }

fun Modifier.tvContextMenu(onContextMenu: (() -> Unit)?): Modifier =
  composed {
    val isTelevision = DeviceFormFactor.isTelevision(LocalContext.current)
    val currentOnContextMenu by rememberUpdatedState(onContextMenu)
    if (!isTelevision || currentOnContextMenu == null) return@composed this

    this.onPreviewKeyEvent { event ->
      val isContextMenuKey = event.key == Key.Menu || event.key == Key.Info
      if (!isContextMenuKey) return@onPreviewKeyEvent false
      when (event.type) {
        KeyEventType.KeyUp -> {
          currentOnContextMenu?.invoke()
          true
        }
        KeyEventType.KeyDown -> true
        else -> false
      }
    }
  }
