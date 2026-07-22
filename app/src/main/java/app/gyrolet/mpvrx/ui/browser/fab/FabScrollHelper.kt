package app.gyrolet.mpvrx.ui.browser.fab

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Common helper functions for FAB visibility based on scroll state
 */
object FabScrollHelper {
    /**
     * Sets up scroll tracking for both list and grid views to control FAB visibility
     */
    @Composable
    fun trackScrollForFabVisibility(
        listState: LazyListState,
        gridState: LazyGridState?,
        isFabVisible: MutableState<Boolean>,
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit
    ) {
        val latestExpanded = rememberUpdatedState(expanded)
        val latestOnExpandedChange = rememberUpdatedState(onExpandedChange)

        // Read rapidly changing positions inside snapshotFlow. Using them as LaunchedEffect keys
        // recomposed this helper and restarted a coroutine for every scroll pixel.
        LaunchedEffect(listState) {
            isFabVisible.value = true
            delay(STATE_CHANGE_GRACE_PERIOD_MS)

            var previousIndex = listState.firstVisibleItemIndex
            var previousOffset = listState.firstVisibleItemScrollOffset
            snapshotFlow {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }
                .distinctUntilChanged()
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
            LaunchedEffect(gridState) {
                isFabVisible.value = true
                delay(STATE_CHANGE_GRACE_PERIOD_MS)

                var previousIndex = gridState.firstVisibleItemIndex
                var previousOffset = gridState.firstVisibleItemScrollOffset
                snapshotFlow {
                    gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
                }
                    .distinctUntilChanged()
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
        previousScrollOffset: Int
    ) {
        // Always show at top
        if (currentIndex == 0 && currentScrollOffset == 0) {
            isFabVisible.value = true
        } else {
            // Calculate if scrolling down or up
            val isScrollingDown = if (currentIndex != previousIndex) {
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

