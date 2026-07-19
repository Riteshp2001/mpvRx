package app.gyrolet.mpvrx.ui.browser.components

import androidx.compose.ui.unit.Dp

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

private data class ScrollMetrics(
  val progress: Float,
  val totalItemsCount: Int,
  val maxScrollIndex: Int,
  val scrollableHeight: Float,
  val itemsPerLine: Int,
)

private data class VisibleGridLineMetrics(
  val index: Int,
  val offsetPx: Int,
  val sizePx: Int,
)

private fun estimateListFallbackStridePx(
  visibleItems: List<LazyListItemInfo>,
  spacingPx: Int,
): Float {
  val strideSamples =
    visibleItems
      .zipWithNext()
      .mapNotNull { (current, next) ->
        (next.offset - current.offset)
          .takeIf { next.index == current.index + 1 && it > 0 }
          ?.toFloat()
      }

  return medianOrNull(strideSamples)
    ?: medianOrNull(visibleItems.map { it.size.toFloat() + spacingPx })
    ?: 1f
}

private fun observeListLayoutMetrics(
  layoutInfo: LazyListLayoutInfo,
  tracker: AxisObservationTracker,
) {
  tracker.resetIfNeeded(
    totalItemsCount = layoutInfo.totalItemsCount,
    spacingPx = layoutInfo.mainAxisItemSpacing,
  )

  val visibleItems = layoutInfo.visibleItemsInfo
  if (visibleItems.isEmpty()) return

  tracker.observeRepresentativeSample(
    strideSamplePx =
      estimateListFallbackStridePx(
        visibleItems = visibleItems,
        spacingPx = layoutInfo.mainAxisItemSpacing,
      ),
    itemSizeSamplePx = medianOrNull(visibleItems.map { it.size.toFloat() }),
  )

  visibleItems.forEach { item ->
    tracker.observeItemSize(index = item.index, sizePx = item.size.toFloat())
  }

  visibleItems
    .zipWithNext()
    .forEach { (current, next) ->
      if (next.index == current.index + 1) {
        tracker.observeStride(
          index = current.index,
          stridePx = (next.offset - current.offset).toFloat(),
        )
      }
    }

  val lastVisibleItem = visibleItems.last()
  if (lastVisibleItem.index < layoutInfo.totalItemsCount - 1) {
    tracker.observeStride(
      index = lastVisibleItem.index,
      stridePx = (lastVisibleItem.size + layoutInfo.mainAxisItemSpacing).toFloat(),
    )
  }
}

private fun buildVisibleGridLines(layoutInfo: LazyGridLayoutInfo): List<VisibleGridLineMetrics> {
  val isVertical = layoutInfo.orientation == Orientation.Vertical
  val groupedLines = linkedMapOf<Int, MutableList<LazyGridItemInfo>>()

  layoutInfo.visibleItemsInfo.forEach { item ->
    val lineIndex = if (isVertical) item.row else item.column
    if (lineIndex >= 0) {
      groupedLines.getOrPut(lineIndex) { mutableListOf() }.add(item)
    }
  }

  return groupedLines.entries
    .map { (lineIndex, itemsInLine) ->
      VisibleGridLineMetrics(
        index = lineIndex,
        offsetPx = itemsInLine.minOf { if (isVertical) it.offset.y else it.offset.x },
        sizePx = itemsInLine.maxOf { if (isVertical) it.size.height else it.size.width },
      )
    }
    .sortedBy { it.index }
}

private fun estimateGridFallbackStridePx(
  visibleLines: List<VisibleGridLineMetrics>,
  spacingPx: Int,
): Float {
  val strideSamples =
    visibleLines
      .zipWithNext()
      .mapNotNull { (current, next) ->
        (next.offsetPx - current.offsetPx)
          .takeIf { next.index == current.index + 1 && it > 0 }
          ?.toFloat()
      }

  return medianOrNull(strideSamples)
    ?: medianOrNull(visibleLines.map { it.sizePx.toFloat() + spacingPx })
    ?: 1f
}

private fun observeGridLayoutMetrics(
  layoutInfo: LazyGridLayoutInfo,
  tracker: AxisObservationTracker,
): List<VisibleGridLineMetrics> {
  tracker.resetIfNeeded(
    totalItemsCount = layoutInfo.totalItemsCount,
    spacingPx = layoutInfo.mainAxisItemSpacing,
  )

  val visibleLines = buildVisibleGridLines(layoutInfo)
  if (visibleLines.isEmpty()) return visibleLines

  tracker.observeRepresentativeSample(
    strideSamplePx =
      estimateGridFallbackStridePx(
        visibleLines = visibleLines,
        spacingPx = layoutInfo.mainAxisItemSpacing,
      ),
    itemSizeSamplePx = medianOrNull(visibleLines.map { it.sizePx.toFloat() }),
  )

  visibleLines.forEach { line ->
    tracker.observeItemSize(index = line.index, sizePx = line.sizePx.toFloat())
  }

  visibleLines
    .zipWithNext()
    .forEach { (current, next) ->
      if (next.index == current.index + 1) {
        tracker.observeStride(
          index = current.index,
          stridePx = (next.offsetPx - current.offsetPx).toFloat(),
        )
      }
    }

  val totalLines = ((layoutInfo.totalItemsCount + layoutInfo.maxSpan - 1) / layoutInfo.maxSpan).coerceAtLeast(1)
  val lastVisibleLine = visibleLines.last()
  if (lastVisibleLine.index < totalLines - 1) {
    tracker.observeStride(
      index = lastVisibleLine.index,
      stridePx = (lastVisibleLine.sizePx + layoutInfo.mainAxisItemSpacing).toFloat(),
    )
  }

  return visibleLines
}

@Composable
fun ExpressiveScrollBar(
  modifier: Modifier = Modifier,
  listState: LazyListState? = null,
  gridState: LazyGridState? = null,
  minHeight: Dp = 54.dp,
  thickness: Dp = 8.dp,
  indicatorExpandedWidth: Dp = 24.dp,
  indicatorExpandedWidthBoost: Dp = 4.dp,
  indicatorRightCornerRadius: Dp = 6.dp,
  paddingEnd: Dp = 4.dp,
  trackGap: Dp = 8.dp,
  dragLabelProvider: ((Int) -> String?)? = null,
  dragLabelSize: Dp = 40.dp,
  dragLabelGap: Dp = 10.dp,
  autoHideDelayMillis: Long = 700L,
  onDragStateChanged: (Boolean) -> Unit = {},
) {
  val listMetricsTracker = remember(listState) { AxisObservationTracker() }
  val gridMetricsTracker = remember(gridState) { AxisObservationTracker() }
  var isPressed by remember { mutableStateOf(false) }
  var isDragging by remember { mutableStateOf(false) }
  var dragProgress by remember { mutableFloatStateOf(-1f) }
  var pendingScrollIndex by remember { mutableIntStateOf(-1) }
  var dragScrollMetrics by remember { mutableStateOf<ScrollMetrics?>(null) }
  var retainedDragLabel by remember { mutableStateOf<String?>(null) }
  val displayedProgress = remember { Animatable(0f) }
  var hasSyncedDisplayedProgress by remember { mutableStateOf(false) }
  var keepVisibleAfterScroll by remember { mutableStateOf(false) }

  val primaryColor = MaterialTheme.colorScheme.primary
  val trackColor = MaterialTheme.colorScheme.secondaryContainer
  val expandedIndicatorWidth = (indicatorExpandedWidth + indicatorExpandedWidthBoost).coerceAtLeast(thickness)
  val indicatorRightCornerRadiusPx = with(LocalDensity.current) { indicatorRightCornerRadius.toPx() }
  val isInteracting = isPressed || isDragging
  val sourceIsScrolling by remember(listState, gridState) {
    derivedStateOf {
      listState?.isScrollInProgress == true || gridState?.isScrollInProgress == true
    }
  }
  val shouldShowScrollbar = isInteracting || sourceIsScrolling || keepVisibleAfterScroll
  val visibilityAlpha by animateFloatAsState(
    targetValue = if (shouldShowScrollbar) 1f else 0f,
    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
    label = "expressive_scroll_visibility_alpha",
  )

  val animatedWidth by animateDpAsState(
    targetValue = if (isInteracting) expandedIndicatorWidth else thickness,
    animationSpec = tween(durationMillis = 200),
    label = "expressive_scroll_width",
  )

  val iconAlpha by animateFloatAsState(
    targetValue = if (isInteracting) 1f else 0f,
    animationSpec = tween(durationMillis = 200),
    label = "expressive_scroll_icon_alpha",
  )

  LaunchedEffect(sourceIsScrolling, isInteracting, autoHideDelayMillis) {
    if (sourceIsScrolling || isInteracting) {
      keepVisibleAfterScroll = true
      return@LaunchedEffect
    }

    delay(autoHideDelayMillis)
    if (!isInteracting && listState?.isScrollInProgress != true && gridState?.isScrollInProgress != true) {
      keepVisibleAfterScroll = false
    }
  }

  BoxWithConstraints(
    modifier =
      modifier
        .fillMaxHeight()
        .width(expandedIndicatorWidth + paddingEnd),
  ) {
    val density = LocalDensity.current
    val constraintsMaxWidth = maxWidth
    val coarseJumpThresholdPx = with(density) { 16.dp.toPx() }
    val smoothJumpMinDistancePx = with(density) { 10.dp.toPx() }

    val canScrollForward by remember {
      derivedStateOf {
        listState?.canScrollForward ?: gridState?.canScrollForward ?: false
      }
    }
    val canScrollBackward by remember {
      derivedStateOf {
        listState?.canScrollBackward ?: gridState?.canScrollBackward ?: false
      }
    }

    if (!canScrollForward && !canScrollBackward) return@BoxWithConstraints

    fun getScrollStats(): ScrollMetrics {
      val totalItemsCount: Int
      val currentScrollPx: Float
      val totalScrollableContentPx: Float
      val approximateMaxScrollIndex: Int

      if (listState != null) {
        val layoutInfo = listState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        totalItemsCount = layoutInfo.totalItemsCount

        if (visibleItems.isEmpty()) {
          return ScrollMetrics(0f, totalItemsCount, 1, 1f, 1)
        }

        observeListLayoutMetrics(layoutInfo, listMetricsTracker)

        val viewportHeightPx =
          (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat().coerceAtLeast(1f)
        val itemStridePx =
          listMetricsTracker.representativeStridePx(
            fallbackStridePx =
              estimateListFallbackStridePx(
                visibleItems = visibleItems,
                spacingPx = layoutInfo.mainAxisItemSpacing,
              ),
          )
        val representativeItemSizePx =
          listMetricsTracker.representativeItemSizePx(
            fallbackItemSizePx = medianOrNull(visibleItems.map { it.size.toFloat() }) ?: 1f,
          )
        val estimatedVisibleItems = (viewportHeightPx / itemStridePx).coerceAtLeast(1f)
        val lastItemIndex = (totalItemsCount - 1).coerceAtLeast(0)

        currentScrollPx =
          (
            listMetricsTracker.distanceBeforeIndex(
              index = listState.firstVisibleItemIndex,
              representativeStridePx = itemStridePx,
            ) + listState.firstVisibleItemScrollOffset
          ).coerceAtLeast(0f)
        totalScrollableContentPx =
          (
            layoutInfo.beforeContentPadding + layoutInfo.afterContentPadding
          ).toFloat()
            .plus(
              listMetricsTracker.distanceBeforeIndex(
                index = lastItemIndex,
                representativeStridePx = itemStridePx,
              ) + listMetricsTracker.itemSizePx(
                index = lastItemIndex,
                representativeItemSizePx = representativeItemSizePx,
              ) - viewportHeightPx,
            ).coerceAtLeast(1f)
        approximateMaxScrollIndex = (totalItemsCount - estimatedVisibleItems).toInt().coerceAtLeast(1)
      } else if (gridState != null) {
        val layoutInfo = gridState.layoutInfo
        totalItemsCount = layoutInfo.totalItemsCount

        val visibleLines = observeGridLayoutMetrics(layoutInfo, gridMetricsTracker)
        val viewportHeightPx =
          (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat().coerceAtLeast(1f)
        val itemsPerRow = layoutInfo.maxSpan.coerceAtLeast(1)
        val totalRows = ((totalItemsCount + itemsPerRow - 1) / itemsPerRow).coerceAtLeast(1)
        val rowStridePx =
          gridMetricsTracker.representativeStridePx(
            fallbackStridePx =
              estimateGridFallbackStridePx(
                visibleLines = visibleLines,
                spacingPx = layoutInfo.mainAxisItemSpacing,
              ),
          )
        val representativeRowSizePx =
          gridMetricsTracker.representativeItemSizePx(
            fallbackItemSizePx = medianOrNull(visibleLines.map { it.sizePx.toFloat() }) ?: 1f,
          )
        val estimatedVisibleRows = (viewportHeightPx / rowStridePx).coerceAtLeast(1f)
        val currentRow = visibleLines.firstOrNull()?.index ?: 0
        val lastRowIndex = (totalRows - 1).coerceAtLeast(0)

        currentScrollPx =
          (
            gridMetricsTracker.distanceBeforeIndex(
              index = currentRow,
              representativeStridePx = rowStridePx,
            ) + gridState.firstVisibleItemScrollOffset
          ).coerceAtLeast(0f)
        totalScrollableContentPx =
          (
            layoutInfo.beforeContentPadding + layoutInfo.afterContentPadding
          ).toFloat()
            .plus(
              gridMetricsTracker.distanceBeforeIndex(
                index = lastRowIndex,
                representativeStridePx = rowStridePx,
              ) + gridMetricsTracker.itemSizePx(
                index = lastRowIndex,
                representativeItemSizePx = representativeRowSizePx,
              ) - viewportHeightPx,
            ).coerceAtLeast(1f)
        approximateMaxScrollIndex =
          (((totalRows - estimatedVisibleRows).toInt().coerceAtLeast(1)) * itemsPerRow)
            .coerceAtMost((totalItemsCount - 1).coerceAtLeast(1))
      } else {
        return ScrollMetrics(0f, 0, 1, 1f, 1)
      }

      if (totalItemsCount == 0) {
        return ScrollMetrics(0f, 0, 1, 1f, 1)
      }

      val forward = listState?.canScrollForward ?: gridState?.canScrollForward ?: false
      val backward = listState?.canScrollBackward ?: gridState?.canScrollBackward ?: false
      val boundedScrollPx = currentScrollPx.coerceIn(0f, totalScrollableContentPx)
      val realProgress =
        if (!forward && totalItemsCount > 0) {
          1f
        } else if (!backward) {
          0f
        } else {
          (boundedScrollPx / totalScrollableContentPx).coerceIn(0f, 0.999f)
        }

      val availableHeight = with(density) { maxHeight.toPx() }
      val handleHeightPx = with(density) { minHeight.toPx() }
      val scrollableHeight = (availableHeight - handleHeightPx).coerceAtLeast(1f)

      return ScrollMetrics(
        progress = realProgress,
        totalItemsCount = totalItemsCount,
        maxScrollIndex = approximateMaxScrollIndex,
        scrollableHeight = scrollableHeight,
        itemsPerLine = if (gridState != null) gridState.layoutInfo.maxSpan.coerceAtLeast(1) else 1,
      )
    }

    fun updateProgressFromTouch(touchY: Float, grabOffset: Float) {
      // Layout metrics are stable enough for the duration of a drag. Reusing the
      // initial snapshot avoids scanning and allocating visible-item lists for
      // every pointer event.
      val stats = dragScrollMetrics ?: getScrollStats()
      val targetHandleTop = touchY - grabOffset
      val newProgress = (targetHandleTop / stats.scrollableHeight).coerceIn(0f, 1f)

      dragProgress = newProgress
      pendingScrollIndex =
        resolveDragTargetIndex(
          progress = newProgress,
          maxScrollIndex = stats.maxScrollIndex,
          totalItemsCount = stats.totalItemsCount,
          itemsPerLine = stats.itemsPerLine,
        )
    }

    LaunchedEffect(listState, gridState) {
      var lastDispatchedIndex = -1
      snapshotFlow { pendingScrollIndex }
        .distinctUntilChanged()
        .collectLatest { index ->
          if (index < 0) {
            lastDispatchedIndex = -1
            return@collectLatest
          }

          // A drag can deliver pointer events faster than Compose can measure a
          // new lazy-list viewport. Keep only the newest request for each frame.
          withFrameNanos { }
          val visibleIndex = listState?.firstVisibleItemIndex ?: gridState?.firstVisibleItemIndex ?: -1
          if (shouldDispatchDragTarget(index, visibleIndex, lastDispatchedIndex)) {
            lastDispatchedIndex = index
            listState?.scrollToItem(index)
            gridState?.scrollToItem(index)
          }
        }
    }

    LaunchedEffect(listState, gridState, maxHeight, minHeight, isDragging) {
      if (isDragging) return@LaunchedEffect

      snapshotFlow { getScrollStats() }
        .distinctUntilChanged()
        .collectLatest { stats ->
          val targetProgress = stats.progress
          if (!hasSyncedDisplayedProgress) {
            displayedProgress.snapTo(targetProgress)
            hasSyncedDisplayedProgress = true
          } else {
            val sourceIsScrolling = listState?.isScrollInProgress == true || gridState?.isScrollInProgress == true
            val handleDeltaPx = abs(targetProgress - displayedProgress.value) * stats.scrollableHeight
            val estimatedStepPx = stats.scrollableHeight / stats.maxScrollIndex.coerceAtLeast(1).toFloat()
            val shouldSmoothJump =
              !sourceIsScrolling &&
                estimatedStepPx >= coarseJumpThresholdPx &&
                handleDeltaPx >= smoothJumpMinDistancePx

            if (shouldSmoothJump) {
              displayedProgress.animateTo(
                targetValue = targetProgress,
                animationSpec = tween(durationMillis = 70, easing = FastOutSlowInEasing),
              )
            } else {
              displayedProgress.snapTo(targetProgress)
            }
          }
        }
    }

    val dragLabelTargetIndex =
      when {
        pendingScrollIndex >= 0 -> pendingScrollIndex
        listState != null -> listState.firstVisibleItemIndex
        gridState != null -> gridState.firstVisibleItemIndex
        else -> -1
      }
    val activeDragLabel =
      if (isDragging && dragLabelProvider != null && dragLabelTargetIndex >= 0) {
        dragLabelProvider(dragLabelTargetIndex)
      } else {
        null
      }
    val showDragLabel = isDragging && !activeDragLabel.isNullOrBlank()

    LaunchedEffect(activeDragLabel) {
      if (!activeDragLabel.isNullOrBlank()) {
        retainedDragLabel = activeDragLabel
      }
    }

    val dragLabelAlpha by animateFloatAsState(
      targetValue = if (showDragLabel) 1f else 0f,
      animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
      label = "expressive_scroll_label_alpha",
    )
    val dragLabelScale by animateFloatAsState(
      targetValue = if (showDragLabel) 1f else 0.82f,
      animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
      label = "expressive_scroll_label_scale",
    )
    val dragLabelSlide by animateDpAsState(
      targetValue = if (showDragLabel) 0.dp else 8.dp,
      animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
      label = "expressive_scroll_label_slide",
    )

    val indicatorPath = remember { Path() }
    val inputEnabled = shouldShowScrollbar || visibilityAlpha > 0.01f

    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .graphicsLayer { alpha = visibilityAlpha }
          .pointerInput(inputEnabled) {
            if (!inputEnabled) return@pointerInput
            detectTapGestures(
              onPress = {
                isPressed = true
                try {
                  awaitRelease()
                } finally {
                  isPressed = false
                }
              },
            )
          }
          .pointerInput(inputEnabled) {
            if (!inputEnabled) return@pointerInput
            var grabOffset = 0f

            detectDragGestures(
              onDragStart = { offset ->
                isDragging = true
                onDragStateChanged(true)

                val stats = getScrollStats()
                dragScrollMetrics = stats
                val handleHeightPx = with(density) { minHeight.toPx() }
                val visualProgress = displayedProgress.value
                val handleY = visualProgress * stats.scrollableHeight
                val isTouchOnHandle = offset.y >= handleY && offset.y <= (handleY + handleHeightPx)

                if (isTouchOnHandle) {
                  grabOffset = offset.y - handleY
                  dragProgress = visualProgress
                  pendingScrollIndex = listState?.firstVisibleItemIndex ?: gridState?.firstVisibleItemIndex ?: 0
                } else {
                  grabOffset = handleHeightPx / 2f
                  updateProgressFromTouch(offset.y, grabOffset)
                }
              },
              onDragEnd = {
                isDragging = false
                dragProgress = -1f
                pendingScrollIndex = -1
                dragScrollMetrics = null
                onDragStateChanged(false)
              },
              onDragCancel = {
                isDragging = false
                dragProgress = -1f
                pendingScrollIndex = -1
                dragScrollMetrics = null
                onDragStateChanged(false)
              },
              onDrag = { change, _ ->
                change.consume()
                updateProgressFromTouch(change.position.y, grabOffset)
              },
            )
          },
    ) {
      val rightAnchorX = with(density) { (constraintsMaxWidth - paddingEnd).toPx() }
      val trackX = rightAnchorX - with(density) { thickness.toPx() / 2 }

      Canvas(modifier = Modifier.fillMaxSize()) {
        val stats = getScrollStats()
        val displayProgress = if (isDragging && dragProgress >= 0f) dragProgress else displayedProgress.value
        val handleY = displayProgress * stats.scrollableHeight
        val handleHeightPx = minHeight.toPx()
        val trackStrokeWidth = thickness.toPx()
        val indicatorWidthPx = animatedWidth.toPx()
        val gapPx = trackGap.toPx()
        val indicatorLeftCornerRadius = indicatorWidthPx / 2f
        val maxAllowedRightCornerRadius = minOf(indicatorWidthPx / 2f, handleHeightPx / 2f)
        val resolvedRightCornerRadius = indicatorRightCornerRadiusPx.coerceIn(0f, maxAllowedRightCornerRadius)
        val currentIndicatorX = rightAnchorX - indicatorWidthPx

        if (handleY > gapPx) {
          drawLine(
            color = trackColor,
            start = Offset(trackX, 0f),
            end = Offset(trackX, handleY - gapPx),
            strokeWidth = trackStrokeWidth,
            cap = StrokeCap.Round,
          )
        }

        if (handleY + handleHeightPx + gapPx < size.height) {
          drawLine(
            color = trackColor,
            start = Offset(trackX, handleY + handleHeightPx + gapPx),
            end = Offset(trackX, size.height),
            strokeWidth = trackStrokeWidth,
            cap = StrokeCap.Round,
          )
        }

        indicatorPath.reset()
        indicatorPath.addRoundRect(
          RoundRect(
            rect = Rect(offset = Offset(currentIndicatorX, handleY), size = Size(indicatorWidthPx, handleHeightPx)),
            topLeft = CornerRadius(indicatorLeftCornerRadius, indicatorLeftCornerRadius),
            topRight = CornerRadius(resolvedRightCornerRadius, resolvedRightCornerRadius),
            bottomRight = CornerRadius(resolvedRightCornerRadius, resolvedRightCornerRadius),
            bottomLeft = CornerRadius(indicatorLeftCornerRadius, indicatorLeftCornerRadius),
          ),
        )
        drawPath(path = indicatorPath, color = primaryColor)
      }

      if (iconAlpha > 0f) {
        Box(
          modifier =
            Modifier
              .offset {
                val stats = getScrollStats()
                val displayProgress = if (isDragging && dragProgress >= 0f) dragProgress else displayedProgress.value
                val handleY = displayProgress * stats.scrollableHeight
                val handleHeightPx = with(density) { minHeight.toPx() }
                val iconSizePx = with(density) { 24.dp.toPx() }
                val paddingEndPx = with(density) { paddingEnd.toPx() }
                val animatedWidthPx = with(density) { animatedWidth.toPx() }
                val maxWidthPx = with(density) { constraintsMaxWidth.toPx() }
                val x = maxWidthPx - paddingEndPx - (animatedWidthPx / 2f) - (iconSizePx / 2f)
                val y = handleY + (handleHeightPx / 2f) - (iconSizePx / 2f)
                IntOffset(x.toInt(), y.toInt())
              }
              .size(24.dp)
              .graphicsLayer {
                alpha = iconAlpha
                scaleX = iconAlpha
                scaleY = iconAlpha
              },
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.fillMaxSize(),
          )
        }
      }

      val displayedDragLabel = activeDragLabel ?: retainedDragLabel
      if (dragLabelAlpha > 0f && !displayedDragLabel.isNullOrBlank()) {
        Surface(
          modifier =
            Modifier
              .offset {
                val stats = getScrollStats()
                val displayProgress = if (isDragging && dragProgress >= 0f) dragProgress else displayedProgress.value
                val handleY = displayProgress * stats.scrollableHeight
                val handleHeightPx = with(density) { minHeight.toPx() }
                val dragLabelSizePx = with(density) { dragLabelSize.toPx() }
                val dragLabelGapPx = with(density) { dragLabelGap.toPx() }
                val dragLabelSlidePx = with(density) { dragLabelSlide.toPx() }
                val paddingEndPx = with(density) { paddingEnd.toPx() }
                val animatedWidthPx = with(density) { animatedWidth.toPx() }
                val maxWidthPx = with(density) { constraintsMaxWidth.toPx() }
                val indicatorX = maxWidthPx - paddingEndPx - animatedWidthPx
                val x = indicatorX - dragLabelSizePx - dragLabelGapPx - dragLabelSlidePx
                val y = handleY + (handleHeightPx / 2f) - (dragLabelSizePx / 2f)
                IntOffset(x.toInt(), y.toInt())
              }
              .size(dragLabelSize)
              .graphicsLayer {
                alpha = dragLabelAlpha
                scaleX = dragLabelScale
                scaleY = dragLabelScale
              },
          shape = CircleShape,
          color = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          tonalElevation = 6.dp,
          shadowElevation = 2.dp,
        ) {
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = displayedDragLabel,
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
    }
  }
}
