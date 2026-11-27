package app.gyrolet.mpvrx.ui.browser.cards

import androidx.compose.animation.core.snap
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppMotion

/** Shared click handling that preserves each card's layout, clipping and semantics modifiers. */
@Composable
internal fun Modifier.mediaCardClickable(
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null,
): Modifier {
  val interactionSource = remember { MutableInteractionSource() }
  return this.combinedClickable(
    interactionSource = interactionSource,
    indication = LocalIndication.current,
    onClick = onClick,
    onLongClick = onLongClick,
  )
}

/**
 * Stable, visible empty space between neighboring media surfaces. Kept in the layout (not
 * conditional on selection) so toggling selection never jumps rows or reloads thumbnails.
 */
internal val MediaSelectionGridGap = 4.dp

// Keep the list and grid breathing room identical; gaps never depend on selection.
internal val MediaSelectionListGap = MediaSelectionGridGap

/**
 * Neighbors are from the visible, filtered/sorted media order. Logical start and end work for
 * RTL as RoundedCornerShape resolves them using the current layout direction.
 */
data class MediaSelectionNeighbors(
  val top: Boolean = false,
  val bottom: Boolean = false,
  val start: Boolean = false,
  val end: Boolean = false,
)

/** Grid-aware adjacency, also usable for vertical lists by passing columns = 1. */
fun mediaSelectionNeighbors(
  index: Int,
  itemCount: Int,
  columns: Int,
  isSelectedAt: (Int) -> Boolean,
): MediaSelectionNeighbors {
  if (index !in 0 until itemCount) return MediaSelectionNeighbors()
  val width = columns.coerceAtLeast(1)
  val column = index % width
  return MediaSelectionNeighbors(
    top = index >= width && isSelectedAt(index - width),
    bottom = index + width < itemCount && isSelectedAt(index + width),
    start = column > 0 && isSelectedAt(index - 1),
    end = column < width - 1 && index + 1 < itemCount && isSelectedAt(index + 1),
  )
}

/**
 * Independent selected cards keep their original modest all-round shape.
 * Adjacent selected cards share tight inner corners; exposed corners stay rounded.
 * Geometry is animated, never layout size, thumbnail requests or the item key.
 */
@Composable
internal fun rememberMediaSelectionShape(
  selected: Boolean,
  grid: Boolean,
  neighbors: MediaSelectionNeighbors = MediaSelectionNeighbors(),
): Shape {
  val outer = if (grid) 18.dp else 18.dp
  val inner = 5.dp
  val regular = 16.dp
  val spec = AppMotion.spatial(AppMotion.Spatial.SnappyDp, snap())
  val scope = rememberCoroutineScope()
  val topStart by animateDpWhenChanged(
    targetValue =
      if (!selected) {
        regular
      } else if (neighbors.top || neighbors.start) {
        inner
      } else {
        outer
      },
    animationSpec = spec,
    scope = scope,
  )
  val topEnd by animateDpWhenChanged(
    targetValue =
      if (!selected) {
        regular
      } else if (neighbors.top || neighbors.end) {
        inner
      } else {
        outer
      },
    animationSpec = spec,
    scope = scope,
  )
  val bottomStart by animateDpWhenChanged(
    targetValue =
      if (!selected) {
        regular
      } else if (neighbors.bottom || neighbors.start) {
        inner
      } else {
        outer
      },
    animationSpec = spec,
    scope = scope,
  )
  val bottomEnd by animateDpWhenChanged(
    targetValue =
      if (!selected) {
        regular
      } else if (neighbors.bottom || neighbors.end) {
        inner
      } else {
        outer
      },
    animationSpec = spec,
    scope = scope,
  )
  return remember(topStart, topEnd, bottomStart, bottomEnd) {
    RoundedCornerShape(
      topStart = topStart,
      topEnd = topEnd,
      bottomStart = bottomStart,
      bottomEnd = bottomEnd,
    )
  }
}

internal val mediaSelectionColor: Color
  @Composable get() = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)

@Composable
internal fun animatedSelectionColor(
  selected: Boolean,
  unselectedColor: Color = mediaSelectionColor.copy(alpha = 0f),
): Color {
  val selectedColor = mediaSelectionColor
  val color by animateColorWhenChanged(
    targetValue = if (selected) selectedColor else unselectedColor,
    animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
  )
  return color
}

@Composable
internal fun SelectionIndicator(
  selected: Boolean,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  val opacityState =
    animateFloatWhenChanged(
      targetValue = if (selected) 1f else 0f,
      animationSpec = AppMotion.spatial(AppMotion.Effect.Alpha, snap()),
      scope = scope,
    )
  val scaleState =
    animateFloatWhenChanged(
      targetValue = if (selected) 1f else 0.88f,
      animationSpec = AppMotion.spatial(AppMotion.Spatial.ExpressiveFast, snap()),
      scope = scope,
    )
  val fadedOut by remember { derivedStateOf { opacityState.value <= 0f } }
  // Unselected cards keep only the badge's footprint; its circle and icon are composed while visible.
  if (!selected && fadedOut) {
    Box(modifier = modifier.size(24.dp))
    return
  }
  Box(
    modifier =
      modifier
        .size(24.dp)
        .clearAndSetSemantics { }
        .graphicsLayer {
          alpha = opacityState.value
          val scale = scaleState.value
          scaleX = scale
          scaleY = scale
        }.background(MaterialTheme.colorScheme.tertiary, CircleShape)
        .padding(4.dp),
  ) {
    Icon(Icons.RoundedFilled.Check, null, tint = MaterialTheme.colorScheme.onTertiary)
  }
}
