/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.theme.animatedConnectedContainerColorState
import app.gyrolet.mpvrx.ui.theme.rememberLowEndDevice
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.tv.isTelevision

/**
 * A connected Material 3 Expressive group for legacy Compose Preference rows.
 *
 * Draws each measured row separately with a small transparent gap, and only rounds
 * the exposed group corners. This is shared by the Appearance, Player, Audio,
 * Subtitle, Advanced, AI, About and other preference pages.
 */
internal val LocalInsidePreferenceCard = staticCompositionLocalOf { false }
private const val ConnectedPreferenceGap = "mpvrx-settings-connected-gap"
internal const val SelectedPreferenceTile = "mpvrx-settings-selected-tile"

internal data class PreferenceTileVisualState(val selected: Boolean, val pressed: Boolean)

private data class ConnectedPreferenceFrame(
  val top: Int,
  val bottom: Int,
  val selected: Boolean,
  val pressed: Boolean,
)

@Composable
fun PreferenceCard(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val spacingPx = with(LocalDensity.current) { AppConnectedShapeTokens.spacing.roundToPx() }
  var tileFrames by remember { mutableStateOf(emptyList<ConnectedPreferenceFrame>()) }
  val reduceMotion = AppMotion.shouldReduceMotion()
  val playerPanel = LocalPlayerSettingsPanel.current
  val lowEnd = rememberLowEndDevice()
  val frames = tileFrames
  val tileColors = frames.map { frame ->
    animatedConnectedContainerColorState(
      selected = frame.selected,
      pressed = frame.pressed,
      containerColor = colors.surfaceContainerLow,
      reduceMotion = reduceMotion || lowEnd,
    )
  }
  val tileShapes = frames.mapIndexed { index, frame ->
    rememberConnectedShape(
      index, frames.size, ConnectedLayout.Vertical,
      pressed = frame.pressed, selected = frame.selected, reduceMotion = reduceMotion || lowEnd,
    )
  }
  val groupShape = rememberConnectedShape(
    0, 1, ConnectedLayout.Vertical,
    pressed = frames.firstOrNull()?.pressed == true || frames.lastOrNull()?.pressed == true,
    reduceMotion = reduceMotion || lowEnd,
  )

  // The outer mask keeps highlight/ripple surfaces within the expressive group boundary.
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = if (playerPanel) 0.dp else 8.dp)
      .then(if (lowEnd || reduceMotion) Modifier else Modifier.animateContentSize(AppMotion.IntSizeSpring)),
  ) {
    val columnScope = this
    Layout(
      modifier = Modifier
        .fillMaxWidth()
        .clip(groupShape)
        .drawWithCache {
          val connectedOutlines = frames.mapIndexedNotNull { index, frame ->
            val top = frame.top.toFloat()
            val bottom = frame.bottom.toFloat()
            if (bottom <= top) return@mapIndexedNotNull null
            val outline = tileShapes[index].createOutline(Size(size.width, bottom - top), layoutDirection, this)
            Triple(outline, index, top)
          }
          onDrawBehind {
            connectedOutlines.forEach { (outline, index, top) ->
              translate(top = top) { drawOutline(outline, color = tileColors[index].value) }
            }
          }
        },
      content = {
        CompositionLocalProvider(LocalInsidePreferenceCard provides true) {
          content(columnScope)
        }
      },
    ) { measurables, constraints ->
      val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
      val placeables = measurables.map { it.measure(childConstraints) }
      val positions = IntArray(placeables.size)
      val frames = ArrayList<ConnectedPreferenceFrame>(placeables.size)
      var y = 0
      var precedingTile = false
      placeables.forEachIndexed { index, placeable ->
        if (placeable.height == 0) return@forEachIndexed
        val isDivider = measurables[index].layoutId == ConnectedPreferenceGap
        // Adjacent rows without an explicit PreferenceDivider still get a small connected gap.
        if (!isDivider && precedingTile) y += spacingPx
        positions[index] = y
        if (!isDivider && placeable.height > 0) {
          val visualState = measurables[index].layoutId as? PreferenceTileVisualState
          frames.add(
            ConnectedPreferenceFrame(
              y,
              y + placeable.height,
              selected = visualState?.selected ?: (measurables[index].layoutId == SelectedPreferenceTile),
              pressed = visualState?.pressed == true,
            ),
          )
          precedingTile = true
        } else if (isDivider) {
          precedingTile = false
        }
        y += placeable.height
      }
      if (tileFrames != frames) tileFrames = frames
      layout(
        constraints.maxWidth,
        y.coerceIn(constraints.minHeight, constraints.maxHeight),
      ) {
        placeables.forEachIndexed { index, placeable ->
          placeable.placeRelative(0, positions[index])
        }
      }
    }
  }
}

/**
 * A Material 3 Expressive preference section made from individually connected tiles.
 *
 * Unlike [PreferenceCard], this does not draw one large container behind every setting. Each
 * child owns its complete row and uses [ExpressivePreferenceTile] or the connected parameters on
 * a preference component. This keeps selected states full-width while preserving a clear visual
 * relationship between adjacent settings.
 */
@Composable
fun ExpressivePreferenceGroup(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val reduceMotion = AppMotion.shouldReduceMotion()
  val lowEnd = rememberLowEndDevice()
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .then(if (lowEnd || reduceMotion) Modifier else Modifier.animateContentSize(AppMotion.IntSizeSpring))
        .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
    content = content,
  )
}

/** A full-width tile whose outer and inner corners follow its position in a vertical group. */
@Composable
fun ExpressivePreferenceTile(
  index: Int,
  itemCount: Int,
  modifier: Modifier = Modifier,
  selected: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val containerColor by animateColorAsState(
    targetValue = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
    animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
    label = "expressivePreferenceTileColor",
  )
  val shape =
    rememberConnectedShape(
      index = index,
      itemCount = itemCount,
      layout = ConnectedLayout.Vertical,
      selected = selected,
    )
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = shape,
    color = containerColor,
  ) {
    // The tile already owns the row surface and the 16dp group inset. Nested
    // ComposePreference rows (especially sliders) must not add another inset.
    CompositionLocalProvider(LocalInsidePreferenceCard provides true) {
      content()
    }
  }
}

/**
 * A neutral per-preference Material 3 container for ComposePreference-backed rows.
 *
 * The external preference library renders interactive content without its own surface;
 * wrapping its existing interaction preserves the click/slider callbacks while matching
 * SwitchPreference and the other Expressive tiles. The parent section stays transparent.
 */
@Composable
fun ExpressivePreferenceItem(
  modifier: Modifier = Modifier,
  selected: Boolean = false,
  // Standalone keeps its content shape; grouped selection is drawn by PreferenceCard.
  standalone: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val insideGroup = LocalInsidePreferenceCard.current
  val shape = if (insideGroup && !standalone) MaterialTheme.shapes.small else MaterialTheme.shapes.large
  val television = isTelevision()
  val color by animateColorAsState(
    // PreferenceCard paints both selected and unselected tiles at their measured connected
    // corners; a child surface must not override that geometry.
    targetValue = when {
      insideGroup -> androidx.compose.ui.graphics.Color.Transparent
      selected -> colors.secondaryContainer
      else -> colors.surfaceContainerLow
    },
    animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
    label = "preferenceItemColor",
  )
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .then(if (television) Modifier.tvFocusHighlight(shape, clipToShape = false) else Modifier)
      .then(if (insideGroup && selected) Modifier.layoutId(SelectedPreferenceTile) else Modifier)
      .then(if (insideGroup) Modifier else Modifier.padding(horizontal = 16.dp, vertical = 2.dp)),
    shape = shape,
    color = color,
  ) {
    content()
  }
}

/**
 * A divider to separate preferences within a card.
 */
@Composable
fun PreferenceDivider(modifier: Modifier = Modifier) {
  if (LocalPlayerSettingsPanel.current) return
  if (LocalInsidePreferenceCard.current) {
    Spacer(modifier = modifier.fillMaxWidth().height(AppConnectedShapeTokens.spacing).layoutId(ConnectedPreferenceGap))
  } else {
    HorizontalDivider(
      modifier = modifier.padding(horizontal = 16.dp),
      color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
  }
}

/**
 * Controls which ends of the narrow section marker are exposed in a connected sequence.
 * A single section deliberately rounds both ends.
 */
enum class PreferenceSectionPosition { First, Middle, Last, Single }

/**
 * A section header for preferences, displayed outside cards.
 */
@Composable
fun PreferenceSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  topPadding: Dp = 30.dp,
  position: PreferenceSectionPosition = PreferenceSectionPosition.Single,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(start = 20.dp, end = 20.dp, top = topPadding, bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      modifier = Modifier.width(5.dp).height(25.dp),
      color = MaterialTheme.colorScheme.primary,
      shape = RoundedCornerShape(
        topStart = if (position == PreferenceSectionPosition.First || position == PreferenceSectionPosition.Single) 7.dp else 2.dp,
        topEnd = if (position == PreferenceSectionPosition.First || position == PreferenceSectionPosition.Single) 7.dp else 2.dp,
        bottomStart = if (position == PreferenceSectionPosition.Last || position == PreferenceSectionPosition.Single) 7.dp else 2.dp,
        bottomEnd = if (position == PreferenceSectionPosition.Last || position == PreferenceSectionPosition.Single) 7.dp else 2.dp,
      ),
      content = {},
    )
    Spacer(modifier = Modifier.width(10.dp))
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}
