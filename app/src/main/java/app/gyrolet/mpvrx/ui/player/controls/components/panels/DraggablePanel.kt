/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

@file:Suppress("ktlint:standard:no-wildcard-imports")

package app.gyrolet.mpvrx.ui.player.controls.components.panels

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.presentation.components.PlayerSheetDragHandle
import app.gyrolet.mpvrx.ui.player.controls.panelCardsColors
import kotlin.math.roundToInt

/**
 * A draggable panel with an optional fixed header and scrollable content.
 *
 * @param modifier Modifier for the panel
 * @param header Optional composable for the fixed header that stays constant during scroll
 * @param content The scrollable content of the panel
 */
@Composable
fun DraggablePanel(
  modifier: Modifier = Modifier,
  header: (@Composable () -> Unit)? = null,
  shape: Shape? = null,
  containerColor: Color? = null,
  tonalElevation: Dp = 0.dp,
  shadowElevation: Dp = 0.dp,
  border: BorderStroke? = null,
  content: @Composable () -> Unit,
) {
  var offsetX by remember { mutableFloatStateOf(0f) }
  var panelWidth by remember { mutableIntStateOf(0) }

  val configuration = LocalConfiguration.current
  val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
  val panelAlignment = AbsoluteAlignment.CenterRight
  val layoutDirection = LocalLayoutDirection.current

  BoxWithConstraints(
    modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
    contentAlignment = panelAlignment,
  ) {
    val freeSpace = (constraints.maxWidth - panelWidth).coerceAtLeast(0)
    val initialLeft =
      panelAlignment.align(
        size = IntSize(panelWidth, 0),
        space = IntSize(constraints.maxWidth, 0),
        layoutDirection = layoutDirection,
      ).x
    val minOffset = -initialLeft.toFloat()
    val maxOffset = (freeSpace - initialLeft).toFloat()

    val panelHeight = if (isPortrait) (configuration.screenHeightDp.dp * 0.5f).coerceAtMost(maxHeight) else maxHeight

    val colors = panelCardsColors()
    Surface(
      modifier =
        Modifier
          .absoluteOffset { IntOffset(offsetX.coerceIn(minOffset, maxOffset).roundToInt(), 0) }
          .onSizeChanged { panelWidth = it.width }
          .widthIn(max = 380.dp)
          .height(panelHeight),
      shape = shape ?: MaterialTheme.shapes.extraLargeIncreased,
      color = containerColor ?: colors.containerColor,
      contentColor = colors.contentColor,
      tonalElevation = tonalElevation,
      shadowElevation = shadowElevation,
      border = border,
    ) {
      Column(Modifier.fillMaxWidth()) {
        // Drag Handle & Indicator
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .pointerInput(maxOffset, minOffset) {
                detectDragGestures { change, dragAmount ->
                  change.consume()
                  val newOffset = offsetX.coerceIn(minOffset, maxOffset) + dragAmount.x
                  offsetX = newOffset.coerceIn(minOffset, maxOffset)
                }
              },
          contentAlignment = Alignment.Center,
        ) {
          PlayerSheetDragHandle()
        }

        // Fixed header (if provided) - stays constant
        if (header != null) {
          header()
        }

        // Scrollable content
        Column(
          modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
        ) {
          content()
        }
      }
    }
  }
}
