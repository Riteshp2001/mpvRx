/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.theme

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
 * Grid indices are row-major in logical layout order. Incomplete final rows get rounded bottom
 * corners on their first and last visible items, while a single visible item is fully rounded.
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
      val row = index / effectiveColumns
      val column = index % effectiveColumns
      val lastRow = (itemCount - 1) / effectiveColumns
      val rowStart = row * effectiveColumns
      val itemsInRow = minOf(effectiveColumns, itemCount - rowStart)
      val isFirstColumn = column == 0
      val isLastVisibleColumn = column == itemsInRow - 1

      ConnectedCornerRadii(
        topStart = if (row == 0 && isFirstColumn) outer else inner,
        topEnd = if (row == 0 && isLastVisibleColumn) outer else inner,
        bottomEnd = if (row == lastRow && isLastVisibleColumn) outer else inner,
        bottomStart = if (row == lastRow && isFirstColumn) outer else inner,
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
 * Remembers a connected shape and observes layout direction explicitly. The shape itself uses
 * logical corners, so Compose performs the correct physical mirroring for RTL layouts.
 */
@Composable
fun rememberConnectedShape(
  index: Int,
  itemCount: Int,
  layout: ConnectedLayout,
  columns: Int = 1,
  tokens: ConnectedShapeTokens = AppConnectedShapeTokens,
): Shape {
  val layoutDirection = LocalLayoutDirection.current
  return remember(index, itemCount, layout, columns, tokens, layoutDirection) {
    connectedShape(
      index = index,
      itemCount = itemCount,
      layout = layout,
      columns = columns,
      tokens = tokens,
    )
  }
}

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
