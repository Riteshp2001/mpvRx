/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.fab

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import app.gyrolet.mpvrx.ui.tv.isTelevision
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Common helper functions for FAB visibility based on scroll state
 */
object FabScrollHelper {
  /**
   * Fullscreen scrim overlay that absorbs all pointer gestures and auto-dismisses the expanded FAB.
   * Intercepts gestures during PointerEventPass.Initial so background content (LazyColumn, HorizontalPager)
   * cannot scroll or swipe while the FAB menu is open.
   */
  @Composable
  fun FabScrim(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
  ) {
    AnimatedVisibility(
      visible = visible,
      enter = fadeIn(),
      exit = fadeOut(),
    ) {
      Box(
        modifier =
          modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .pointerInput(Unit) {
              awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                down.consume()
                onDismiss()
                do {
                  val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                  event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
              }
            },
      )
    }
  }

  /**
   * Sets up scroll tracking for both list and grid views to control FAB visibility.
   * On TV the FAB never hides: lists scroll with every D-pad step, and a FAB scaled out of view
   * would still be a focus stop the user cannot see.
   */
  @Composable
  fun trackScrollForFabVisibility(
    listState: LazyListState,
    gridState: LazyGridState?,
    isFabVisible: MutableState<Boolean>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
  ) {
    val latestExpanded = rememberUpdatedState(expanded)
    val latestOnExpandedChange = rememberUpdatedState(onExpandedChange)
    val isTelevision = isTelevision()

    // Read rapidly changing positions inside snapshotFlow. Using them as LaunchedEffect keys
    // recomposed this helper and restarted a coroutine for every scroll pixel.
    LaunchedEffect(listState, isTelevision) {
      isFabVisible.value = true
      if (isTelevision) return@LaunchedEffect
      delay(STATE_CHANGE_GRACE_PERIOD_MS)

      var previousIndex = listState.firstVisibleItemIndex
      var previousOffset = listState.firstVisibleItemScrollOffset
      snapshotFlow {
        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
      }.distinctUntilChanged()
        .collect { (currentIndex, currentOffset) ->
          updateFabVisibility(
            isFabVisible,
            currentIndex,
            currentOffset,
            previousIndex,
            previousOffset,
          )
          previousIndex = currentIndex
          previousOffset = currentOffset
        }
    }

    if (gridState != null) {
      LaunchedEffect(gridState, isTelevision) {
        isFabVisible.value = true
        if (isTelevision) return@LaunchedEffect
        delay(STATE_CHANGE_GRACE_PERIOD_MS)

        var previousIndex = gridState.firstVisibleItemIndex
        var previousOffset = gridState.firstVisibleItemScrollOffset
        snapshotFlow {
          gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        }.distinctUntilChanged()
          .collect { (currentIndex, currentOffset) ->
            updateFabVisibility(
              isFabVisible,
              currentIndex,
              currentOffset,
              previousIndex,
              previousOffset,
            )
            previousIndex = currentIndex
            previousOffset = currentOffset
          }
      }
    }

    // Auto-collapse menu when list scrolling
    LaunchedEffect(listState) {
      snapshotFlow { listState.isScrollInProgress }
        .distinctUntilChanged()
        .filter { it }
        .collect {
          if (latestExpanded.value) latestOnExpandedChange.value(false)
        }
    }

    // Auto-collapse menu when grid scrolling
    gridState?.let { grid ->
      LaunchedEffect(grid) {
        snapshotFlow { grid.isScrollInProgress }
          .distinctUntilChanged()
          .filter { it }
          .collect {
            if (latestExpanded.value) latestOnExpandedChange.value(false)
          }
      }
    }
  }

  /**
   * Helper function to update FAB visibility based on scroll position
   */
  private fun updateFabVisibility(
    isFabVisible: MutableState<Boolean>,
    currentIndex: Int,
    currentScrollOffset: Int,
    previousIndex: Int,
    previousScrollOffset: Int,
  ) {
    // Always show at top
    if (currentIndex == 0 && currentScrollOffset == 0) {
      isFabVisible.value = true
    } else {
      // Calculate if scrolling down or up
      val isScrollingDown =
        if (currentIndex != previousIndex) {
          currentIndex > previousIndex
        } else {
          currentScrollOffset > previousScrollOffset
        }

      // Hide when scrolling down, show when scrolling up
      isFabVisible.value = !isScrollingDown
    }
  }

  private const val STATE_CHANGE_GRACE_PERIOD_MS = 300L
}
