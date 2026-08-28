/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.gyrolet.mpvrx.ui.theme.AppMotion

fun Modifier.tvFocusable(
  shape: Shape = RoundedCornerShape(8.dp),
  enabled: Boolean = true,
  makeFocusable: Boolean = false,
  onFocusChanged: (Boolean) -> Unit = {},
): Modifier =
  composed {
    if (!LocalTvUiEnvironment.current.isTelevision || !enabled) return@composed this

    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
      targetValue = if (focused) 1.045f else 1f,
      animationSpec = AppMotion.Spatial.Snappy,
      label = "TvFocusScale",
    )
    val focusModifier =
      Modifier
        .onFocusChanged { state ->
          val nextFocused = state.isFocused || state.hasFocus
          if (focused != nextFocused) {
            focused = nextFocused
            onFocusChanged(nextFocused)
          }
        }.zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        }.then(
          if (focused) {
            Modifier
              .shadow(10.dp, shape, clip = false)
              .border(3.dp, MaterialTheme.colorScheme.primary, shape)
              .clip(shape)
          } else {
            Modifier
          },
        ).then(if (makeFocusable) Modifier.focusable() else Modifier)

    this.then(focusModifier)
  }

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
fun Modifier.tvFocusRestorer(): Modifier =
  composed {
    if (LocalTvUiEnvironment.current.isTelevision) {
      this.focusRestorer().focusGroup()
    } else {
      this
    }
  }

fun Modifier.tvSafeContentPadding(): Modifier =
  composed {
    if (LocalTvUiEnvironment.current.isTelevision) {
      this.padding(horizontal = 48.dp, vertical = 24.dp)
    } else {
      this
    }
  }

fun Modifier.tvExcludeFromFocus(): Modifier =
  composed {
    if (LocalTvUiEnvironment.current.isTelevision) {
      this.focusProperties { canFocus = false }
    } else {
      this
    }
  }

@Composable
fun rememberTvInitialFocusRequester(enabled: Boolean = true): FocusRequester {
  val isTelevision = LocalTvUiEnvironment.current.isTelevision
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
    if (LocalTvUiEnvironment.current.isTelevision) this.focusRequester(requester) else this
  }

@Composable
@Suppress("ktlint:standard:function-naming")
fun TvFocusScene(
  modifier: Modifier = Modifier,
  requestFocus: Boolean = true,
  content: @Composable BoxScope.() -> Unit,
) {
  val isTelevision = LocalTvUiEnvironment.current.isTelevision
  val rootRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current
  LaunchedEffect(isTelevision, requestFocus) {
    if (isTelevision && requestFocus) {
      withFrameNanos { }
      val requested = runCatching { rootRequester.requestFocus() }.getOrDefault(false)
      if (requested) {
        focusManager.moveFocus(FocusDirection.Enter)
      } else {
        focusManager.moveFocus(FocusDirection.Next)
      }
    }
  }

  Box(
    modifier =
      if (isTelevision) {
        modifier
          .focusRequester(rootRequester)
          .tvFocusRestorer()
      } else {
        modifier
      },
    content = content,
  )
}
