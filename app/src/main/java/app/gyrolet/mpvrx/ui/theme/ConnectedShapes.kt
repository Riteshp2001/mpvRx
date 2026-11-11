/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Layouts supported by [connectedShape]. */
enum class ConnectedLayout {
  Vertical,
  Horizontal,
  Grid,
}

/**
 * Geometry shared by intentionally connected controls and information tiles.
 *
 * The radii use logical start/end corners. [RoundedCornerShape] resolves those corners against
 * the current [LayoutDirection], so the same group remains correct in RTL without mirroring data
 * or maintaining a second set of shapes.
 */
@Immutable
data class ConnectedShapeTokens(
  val outerCorner: Dp = 28.dp,
  val innerCorner: Dp = 6.dp,
  val spacing: Dp = 4.dp,
)

/** Application defaults for Material 3 Expressive connected groups. */
val AppConnectedShapeTokens = ConnectedShapeTokens()

/** Tighter geometry for compact action groups where a 28dp corner would collapse into a pill. */
val AppConnectedControlShapeTokens =
  ConnectedShapeTokens(
    outerCorner = 20.dp,
    innerCorner = 4.dp,
    spacing = 4.dp,
  )

@Immutable
data class ConnectedCornerRadii(
  val topStart: Dp,
  val topEnd: Dp,
  val bottomEnd: Dp,
  val bottomStart: Dp,
) {
  fun asShape(): RoundedCornerShape =
    RoundedCornerShape(
      topStart = topStart,
      topEnd = topEnd,
      bottomEnd = bottomEnd,
      bottomStart = bottomStart,
    )
}

/**
 * Calculates the logical corner radii for one item in a connected group.
 *
 * Grid indices are row-major in logical layout order. A corner is only tightened where it touches
 * a neighbouring item, so every exposed corner stays rounded, including in incomplete final rows.
 * Filtered, sorted, or resized groups therefore only need to pass their current index, count, and
 * column count; no shape state is retained between layouts.
 */
fun connectedCornerRadii(
  index: Int,
  itemCount: Int,
  layout: ConnectedLayout,
  columns: Int = 1,
  tokens: ConnectedShapeTokens = AppConnectedShapeTokens,
): ConnectedCornerRadii {
  require(itemCount > 0) { "Connected groups must contain at least one item" }
  require(index in 0 until itemCount) { "index ($index) must be in 0 until itemCount ($itemCount)" }
  require(columns > 0) { "columns must be greater than zero" }

  if (itemCount == 1) {
    return ConnectedCornerRadii(
      topStart = tokens.outerCorner,
      topEnd = tokens.outerCorner,
      bottomEnd = tokens.outerCorner,
      bottomStart = tokens.outerCorner,
    )
  }

  val outer = tokens.outerCorner
  val inner = tokens.innerCorner
  return when (layout) {
    ConnectedLayout.Vertical ->
      ConnectedCornerRadii(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomEnd = if (index == itemCount - 1) outer else inner,
        bottomStart = if (index == itemCount - 1) outer else inner,
      )

    ConnectedLayout.Horizontal ->
      ConnectedCornerRadii(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == itemCount - 1) outer else inner,
        bottomEnd = if (index == itemCount - 1) outer else inner,
        bottomStart = if (index == 0) outer else inner,
      )

    ConnectedLayout.Grid -> {
      val effectiveColumns = columns.coerceAtMost(itemCount)
      val column = index % effectiveColumns
      val hasTop = index >= effectiveColumns
      val hasBottom = index + effectiveColumns < itemCount
      val hasStart = column > 0
      val hasEnd = column < effectiveColumns - 1 && index + 1 < itemCount

      ConnectedCornerRadii(
        topStart = if (hasTop || hasStart) inner else outer,
        topEnd = if (hasTop || hasEnd) inner else outer,
        bottomEnd = if (hasBottom || hasEnd) inner else outer,
        bottomStart = if (hasBottom || hasStart) inner else outer,
      )
    }
  }
}

fun connectedShape(
  index: Int,
  itemCount: Int,
  layout: ConnectedLayout,
  columns: Int = 1,
  tokens: ConnectedShapeTokens = AppConnectedShapeTokens,
): Shape = connectedCornerRadii(index, itemCount, layout, columns, tokens).asShape()

/**
 * Animates shared connected geometry without moving layout or hit bounds. Logical corners keep
 * pressed and selected morphs correct in RTL; reduced motion applies their final shapes immediately.
 */
@Composable
fun rememberConnectedShape(
  index: Int,
  itemCount: Int,
  layout: ConnectedLayout,
  columns: Int = 1,
  tokens: ConnectedShapeTokens = AppConnectedShapeTokens,
  pressed: Boolean = false,
  selected: Boolean = false,
  reduceMotion: Boolean = AppMotion.shouldReduceMotion(),
): Shape {
  val layoutDirection = LocalLayoutDirection.current
  val target = remember(index, itemCount, layout, columns, tokens, pressed, selected) {
    connectedCornerRadii(
      index, itemCount, layout, columns,
      tokens.copy(
        outerCorner = if (pressed) tokens.outerCorner * 0.64f else tokens.outerCorner,
        innerCorner = if (pressed || selected) tokens.innerCorner + 8.dp else tokens.innerCorner,
      ),
    )
  }
  val animationSpec = if (reduceMotion) AppMotion.ReducedDp else AppMotion.Spatial.SnappyDp
  val topStart by animateDpAsState(target.topStart, animationSpec, label = "connectedTopStart")
  val topEnd by animateDpAsState(target.topEnd, animationSpec, label = "connectedTopEnd")
  val bottomEnd by animateDpAsState(target.bottomEnd, animationSpec, label = "connectedBottomEnd")
  val bottomStart by animateDpAsState(target.bottomStart, animationSpec, label = "connectedBottomStart")
  return remember(topStart, topEnd, bottomEnd, bottomStart, layoutDirection) {
    RoundedCornerShape(
      topStart = topStart,
      topEnd = topEnd,
      bottomEnd = bottomEnd,
      bottomStart = bottomStart,
    )
  }
}

@Composable
fun animatedConnectedContainerColorState(
  selected: Boolean,
  pressed: Boolean = false,
  enabled: Boolean = true,
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
  selectedContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
  reduceMotion: Boolean = AppMotion.shouldReduceMotion(),
): State<Color> {
  val base = if (selected) selectedContainerColor else containerColor
  return animateColorAsState(
    targetValue = if (pressed && enabled) lerp(base, MaterialTheme.colorScheme.primary, 0.10f) else base,
    animationSpec = if (reduceMotion) snap() else AppMotion.Effect.Color,
    label = "connectedContainerColor",
  )
}

@Composable
fun animatedConnectedContainerColor(
  selected: Boolean,
  pressed: Boolean = false,
  enabled: Boolean = true,
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
  selectedContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
  reduceMotion: Boolean = AppMotion.shouldReduceMotion(),
): Color = animatedConnectedContainerColorState(
  selected, pressed, enabled, containerColor, selectedContainerColor, reduceMotion,
).value

/** Visible for deterministic shape verification without requiring composition. */
internal fun ConnectedCornerRadii.physical(layoutDirection: LayoutDirection): ConnectedCornerRadii =
  if (layoutDirection == LayoutDirection.Ltr) {
    this
  } else {
    ConnectedCornerRadii(
      topStart = topEnd,
      topEnd = topStart,
      bottomEnd = bottomStart,
      bottomStart = bottomEnd,
    )
  }
