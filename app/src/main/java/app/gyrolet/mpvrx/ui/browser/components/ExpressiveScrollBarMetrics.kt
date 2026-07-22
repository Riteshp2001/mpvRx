package app.gyrolet.mpvrx.ui.browser.components

internal class AxisObservationTracker {
  private var trackedTotalItemsCount = -1
  private var trackedSpacingPx = Int.MIN_VALUE
  private var representativeStridePx = 1f
  private var representativeItemSizePx = 1f
  private var representativeStrideSampleCount = 0
  private var representativeItemSizeSampleCount = 0
  private val observedStridesPx = mutableMapOf<Int, Float>()
  private val observedItemSizesPx = mutableMapOf<Int, Float>()
  private var observedStrideSums = FloatArray(1)
  private var observedStrideCounts = IntArray(1)

  fun resetIfNeeded(totalItemsCount: Int, spacingPx: Int) {
    if (trackedTotalItemsCount == totalItemsCount && trackedSpacingPx == spacingPx) {
      return
    }

    trackedTotalItemsCount = totalItemsCount
    trackedSpacingPx = spacingPx
    representativeStridePx = 1f
    representativeItemSizePx = 1f
    representativeStrideSampleCount = 0
    representativeItemSizeSampleCount = 0
    observedStridesPx.clear()
    observedItemSizesPx.clear()
    observedStrideSums = FloatArray(totalItemsCount.coerceAtLeast(0) + 1)
    observedStrideCounts = IntArray(totalItemsCount.coerceAtLeast(0) + 1)
  }

  fun observeRepresentativeSample(
    strideSamplePx: Float?,
    itemSizeSamplePx: Float?,
  ) {
    val normalizedStrideSamplePx = strideSamplePx?.coerceAtLeast(1f)
    val normalizedItemSizeSamplePx = itemSizeSamplePx?.coerceAtLeast(1f)

    if (normalizedStrideSamplePx == null && normalizedItemSizeSamplePx == null) {
      return
    }

    normalizedStrideSamplePx?.let { sample ->
      val currentCount = representativeStrideSampleCount
      representativeStrideSampleCount = currentCount + 1
      representativeStridePx =
        if (currentCount == 0) {
          sample
        } else {
          ((representativeStridePx * currentCount) + sample) / representativeStrideSampleCount
        }
    }

    normalizedItemSizeSamplePx?.let { sample ->
      val currentCount = representativeItemSizeSampleCount
      representativeItemSizeSampleCount = currentCount + 1
      representativeItemSizePx =
        if (currentCount == 0) {
          sample
        } else {
          ((representativeItemSizePx * currentCount) + sample) / representativeItemSizeSampleCount
        }
    }
  }

  fun observeItemSize(index: Int, sizePx: Float) {
    observedItemSizesPx[index] = sizePx.coerceAtLeast(1f)
  }

  fun observeStride(index: Int, stridePx: Float) {
    if (index !in 0 until trackedTotalItemsCount) return

    val normalizedStridePx = stridePx.coerceAtLeast(1f)
    val previousStridePx = observedStridesPx.put(index, normalizedStridePx)
    updateStrideSum(index, normalizedStridePx - (previousStridePx ?: 0f))
    if (previousStridePx == null) updateStrideCount(index, 1)
  }

  fun representativeStridePx(fallbackStridePx: Float): Float =
    if (representativeStrideSampleCount > 0) {
      representativeStridePx
    } else {
      fallbackStridePx.coerceAtLeast(1f)
    }

  fun representativeItemSizePx(fallbackItemSizePx: Float): Float =
    if (representativeItemSizeSampleCount > 0) {
      representativeItemSizePx
    } else {
      fallbackItemSizePx.coerceAtLeast(1f)
    }

  fun distanceBeforeIndex(index: Int, representativeStridePx: Float): Float {
    if (index <= 0) return 0f

    val observedStridePx = strideSumBefore(index)
    val observedCount = strideCountBefore(index)
    val estimatedStridePx = (index - observedCount) * representativeStridePx
    return (observedStridePx + estimatedStridePx).coerceAtLeast(0f)
  }

  fun itemSizePx(index: Int, representativeItemSizePx: Float): Float =
    observedItemSizesPx[index] ?: representativeItemSizePx.coerceAtLeast(1f)

  private fun updateStrideSum(index: Int, delta: Float) {
    var treeIndex = index + 1
    while (treeIndex < observedStrideSums.size) {
      observedStrideSums[treeIndex] += delta
      treeIndex += treeIndex and -treeIndex
    }
  }

  private fun updateStrideCount(index: Int, delta: Int) {
    var treeIndex = index + 1
    while (treeIndex < observedStrideCounts.size) {
      observedStrideCounts[treeIndex] += delta
      treeIndex += treeIndex and -treeIndex
    }
  }

  private fun strideSumBefore(index: Int): Float {
    var treeIndex = index.coerceIn(0, observedStrideSums.lastIndex)
    var sum = 0f
    while (treeIndex > 0) {
      sum += observedStrideSums[treeIndex]
      treeIndex -= treeIndex and -treeIndex
    }
    return sum
  }

  private fun strideCountBefore(index: Int): Int {
    var treeIndex = index.coerceIn(0, observedStrideCounts.lastIndex)
    var count = 0
    while (treeIndex > 0) {
      count += observedStrideCounts[treeIndex]
      treeIndex -= treeIndex and -treeIndex
    }
    return count
  }
}

internal fun resolveDragTargetIndex(
  progress: Float,
  maxScrollIndex: Int,
  totalItemsCount: Int,
  itemsPerLine: Int = 1,
): Int {
  if (totalItemsCount <= 1) return 0

  val clampedProgress = progress.coerceIn(0f, 1f)
  val lastIndex = totalItemsCount - 1
  val rawTarget =
    if (clampedProgress >= 1f) {
      lastIndex
    } else {
      (clampedProgress * maxScrollIndex.coerceAtLeast(1))
        .toInt()
        .coerceIn(0, lastIndex)
    }
  val safeItemsPerLine = itemsPerLine.coerceAtLeast(1)

  return rawTarget - (rawTarget % safeItemsPerLine)
}

fun fastScrollGlyph(value: String?): String? {
  val leadingChar =
    value
      .orEmpty()
      .trim()
      .firstOrNull { it.isLetterOrDigit() }
      ?: return null

  return if (leadingChar.isDigit()) {
    "#"
  } else {
    leadingChar.uppercaseChar().toString()
  }
}

internal fun medianOrNull(values: Iterable<Float>): Float? {
  val sorted =
    values
      .filter { it.isFinite() && it > 0f }
      .sorted()

  if (sorted.isEmpty()) return null

  val middleIndex = sorted.size / 2
  return if (sorted.size % 2 == 0) {
    (sorted[middleIndex - 1] + sorted[middleIndex]) / 2f
  } else {
    sorted[middleIndex]
  }
}
