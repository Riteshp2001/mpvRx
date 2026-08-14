/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.presentation.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

data class SettingsSearchTarget(
  val screen: Screen,
  val ordinal: Int,
  val totalOnScreen: Int,
)

object SettingsSearchNavigation {
  private val _target = MutableStateFlow<SettingsSearchTarget?>(null)
  val target = _target.asStateFlow()

  fun open(preference: SearchablePreference) {
    val (ordinal, total) = SearchablePreferences.positionOnScreen(preference)
    _target.value = SettingsSearchTarget(preference.screen, ordinal, total)
  }

  fun clear(target: SettingsSearchTarget) {
    _target.compareAndSet(target, null)
  }
}

@Composable
fun rememberSettingsSearchList(
  screen: Screen,
  highlightColor: Color,
): Pair<LazyListState, Modifier> {
  val listState = rememberLazyListState()
  return listState to rememberSettingsSearchHighlight(screen, listState, highlightColor)
}

/**
 * Scrolls a settings list to the result's relative position and softly tints the destination row.
 * The relative position keeps the search index independent from each screen's Compose item layout.
 */
@Composable
fun rememberSettingsSearchHighlight(
  screen: Screen,
  listState: LazyListState,
  highlightColor: Color,
): Modifier {
  val requestedTarget by SettingsSearchNavigation.target.collectAsState()
  var highlightedIndex by remember { mutableIntStateOf(-1) }
  var highlightVisible by remember { mutableStateOf(false) }

  LaunchedEffect(requestedTarget, screen, listState) {
    val target = requestedTarget?.takeIf { it.screen == screen } ?: return@LaunchedEffect
    val totalItems =
      snapshotFlow { listState.layoutInfo.totalItemsCount }
        .filter { it > 0 }
        .first()
    val fraction = target.positionFraction()
    highlightedIndex = (fraction * (totalItems - 1)).roundToInt().coerceIn(0, totalItems - 1)
    listState.animateScrollToItem(highlightedIndex)
    highlightVisible = true
    delay(1800)
    highlightVisible = false
    SettingsSearchNavigation.clear(target)
  }

  return Modifier.drawWithContent {
    drawContent()
    if (highlightVisible) {
      listState.layoutInfo.visibleItemsInfo
        .firstOrNull { it.index == highlightedIndex }
        ?.let { item ->
          drawRect(
            color = highlightColor.copy(alpha = 0.16f),
            topLeft = Offset(0f, item.offset.toFloat()),
            size = Size(size.width, item.size.toFloat()),
          )
        }
    }
  }
}

/** Scrolls non-lazy settings screens to a search result and briefly tints its destination area. */
@Composable
fun rememberSettingsSearchHighlight(
  screen: Screen,
  scrollState: ScrollState,
  highlightColor: Color,
): Modifier {
  val requestedTarget by SettingsSearchNavigation.target.collectAsState()
  var highlightVisible by remember { mutableStateOf(false) }

  LaunchedEffect(requestedTarget, screen, scrollState) {
    val target = requestedTarget?.takeIf { it.screen == screen } ?: return@LaunchedEffect
    snapshotFlow { scrollState.maxValue }
      .filter { it > 0 }
      .first()
    scrollState.animateScrollTo((target.positionFraction() * scrollState.maxValue).roundToInt())
    highlightVisible = true
    delay(1800)
    highlightVisible = false
    SettingsSearchNavigation.clear(target)
  }

  return Modifier.drawWithContent {
    drawContent()
    if (highlightVisible) {
      drawRect(
        color = highlightColor.copy(alpha = 0.12f),
        size = Size(size.width, minOf(size.height, 112.dp.toPx())),
      )
    }
  }
}

/** Highlights a settings screen that has no scrollable preference list. */
@Composable
fun rememberSettingsSearchHighlight(
  screen: Screen,
  highlightColor: Color,
): Modifier {
  val requestedTarget by SettingsSearchNavigation.target.collectAsState()
  var highlightVisible by remember { mutableStateOf(false) }

  LaunchedEffect(requestedTarget, screen) {
    val target = requestedTarget?.takeIf { it.screen == screen } ?: return@LaunchedEffect
    highlightVisible = true
    delay(1800)
    highlightVisible = false
    SettingsSearchNavigation.clear(target)
  }

  return Modifier.drawWithContent {
    drawContent()
    if (highlightVisible) drawRect(highlightColor.copy(alpha = 0.10f))
  }
}

private fun SettingsSearchTarget.positionFraction(): Float =
  if (totalOnScreen <= 1) 0f else ordinal.toFloat() / (totalOnScreen - 1).toFloat()
