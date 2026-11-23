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

import app.gyrolet.mpvrx.ui.liquidglass.playerGlassSurfaceEnabled
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.PlayerSheetDragHandle
import app.gyrolet.mpvrx.presentation.components.sheetWindow
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.liquidglass.LocalKyantPlayerBackdrop
import app.gyrolet.mpvrx.ui.liquidglass.playerGlassSurface
import app.gyrolet.mpvrx.ui.liquidglass.LocalPlayerGlassSurfaceActive
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.panelCardsColors
import app.gyrolet.mpvrx.ui.tv.isTelevision
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
  alignment: Alignment = AbsoluteAlignment.CenterRight,
  scrollContent: Boolean = true,
  portraitHeightFraction: Float = 0.62f,
  maxPanelWidth: Dp = 420.dp,
  content: @Composable () -> Unit,
) {
  var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
  var panelWidth by remember { mutableIntStateOf(0) }
  val liquidGlassEnabled = playerGlassSurfaceEnabled()
  val television = isTelevision()
  val window = sheetWindow()
  val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

  val panelAlignment = alignment
  val layoutDirection = LocalLayoutDirection.current

  BoxWithConstraints(
    modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
    contentAlignment = panelAlignment,
  ) {
    val isPortrait = constraints.maxHeight > constraints.maxWidth
    val freeSpace = (constraints.maxWidth - panelWidth).coerceAtLeast(0)
    val initialLeft =
      panelAlignment.align(
        size = IntSize(panelWidth, 0),
        space = IntSize(constraints.maxWidth, 0),
        layoutDirection = layoutDirection,
      ).x
    val minOffset = -initialLeft.toFloat()
    val maxOffset = (freeSpace - initialLeft).toFloat()
    val canDrag = !television && freeSpace > 0
    val panelHeight = if (isPortrait) maxHeight * portraitHeightFraction.coerceIn(0.4f, 1f)
      else window.maxHeight(maxHeight, keyboardVisible)
    val dragModifier = if (canDrag) {
      Modifier.pointerInput(minOffset, maxOffset) {
        detectHorizontalDragGestures { change, delta ->
          change.consume()
          offsetX = (offsetX.coerceIn(minOffset, maxOffset) + delta).coerceIn(minOffset, maxOffset)
        }
      }
    } else Modifier

    val colors = panelCardsColors()
    val backdrop = LocalKyantPlayerBackdrop.current
    val panelShape = shape ?: MaterialTheme.shapes.extraLargeIncreased
    val panelColor = containerColor ?: colors.containerColor
    // Translucent over video in normal mode; keep any more-transparent caller override.
    val expressivePanelColor = panelColor.copy(alpha = panelColor.alpha.coerceAtMost(0.78f))
    Surface(
      modifier =
        Modifier
          .absoluteOffset {
            IntOffset(
              offsetX.coerceIn(minOffset, maxOffset).roundToInt(),
              0,
            )
          }
          .onSizeChanged {
            panelWidth = it.width
          }
          .widthIn(max = maxPanelWidth)
          .height(panelHeight)
          // A hit-test boundary, not a gesture consumer: child lists and sliders own their events.
          .pointerInput(Unit) {}
          .playerGlassSurface(backdrop, panelShape, panelColor, enabled = liquidGlassEnabled),
      shape = panelShape,
      color = if (liquidGlassEnabled) Color.Transparent else expressivePanelColor,
      contentColor = colors.contentColor,
      tonalElevation = if (liquidGlassEnabled) 0.dp else tonalElevation,
      shadowElevation = if (liquidGlassEnabled) 0.dp else shadowElevation,
      border =
        if (liquidGlassEnabled) {
          null
        } else {
          border ?: BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
          )
        },
    ) {
      Column(Modifier.fillMaxWidth()) {
        if (canDrag) {
          Box(Modifier.fillMaxWidth().then(dragModifier)) {
            PlayerSheetDragHandle()
          }
        }
        if (header != null) {
          Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).then(dragModifier),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(Modifier.weight(1f)) { header() }
          }
        }

        // Scrollable content
        if (scrollContent) {
          Column(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
          ) {
            CompositionLocalProvider(LocalPlayerGlassSurfaceActive provides liquidGlassEnabled) {
              content()
            }
          }
        } else {
          // Embedded settings own their LazyColumn. Giving it a finite viewport avoids nested
          // vertical scrolling and keeps search, sliders and remembered list positions reliable.
          Box(Modifier.fillMaxWidth().weight(1f)) {
            CompositionLocalProvider(LocalPlayerGlassSurfaceActive provides liquidGlassEnabled) {
              content()
            }
          }
        }
      }
    }
  }
}

@Composable
fun DraggablePanelHeader(
  title: String,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  leadingContent: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    leadingContent?.invoke()
    Text(
      text = title,
      style = MaterialTheme.typography.titleMediumEmphasized,
      modifier = Modifier.weight(1f).semantics { heading() },
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    IconButton(
      onClick = onDismissRequest,
      modifier = Modifier.size(48.dp).tvFocusHighlight(MaterialTheme.shapes.medium),
    ) {
      Icon(Icons.RoundedFilled.Close, contentDescription = stringResource(R.string.ui_close), modifier = Modifier.size(22.dp))
    }
  }
}
