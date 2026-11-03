/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.cards

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.AppMotion

/** Watched videos keep their thumbnail but lose its colour, so unwatched ones stand out. */
internal val WatchedThumbnailFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

private const val REVEAL_MILLIS = 420
private val RevealBlur = 12.dp

/** Runs 0 to 1 when a thumbnail missing at first draw arrives; one already in memory starts at 1. */
@Composable
internal fun rememberThumbnailReveal(shown: Boolean, vararg keys: Any?): State<Float> {
  val reveal = remember(*keys) { Animatable(if (shown) 1f else 0f) }
  val reduceMotion = AppMotion.shouldReduceMotion()
  LaunchedEffect(reveal, shown) {
    if (!shown || reveal.value >= 1f) return@LaunchedEffect
    if (reduceMotion) {
      reveal.snapTo(1f)
    } else {
      reveal.animateTo(1f, tween(REVEAL_MILLIS, easing = LinearOutSlowInEasing))
    }
  }
  return reveal.asState()
}

/** Fades the image in, then clears a soft blur. Read in the layer, so it never recomposes. */
internal fun Modifier.thumbnailReveal(progress: State<Float>): Modifier =
  graphicsLayer {
    val value = progress.value
    alpha = (value * 2f).coerceAtMost(1f)
    compositingStrategy = CompositingStrategy.ModulateAlpha
    val radius = RevealBlur.toPx() * (1f - value)
    // RenderEffect needs API 31; older devices only fade, and settled thumbnails get no effect.
    renderEffect =
      if (radius > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BlurEffect(radius, radius) else null
  }
