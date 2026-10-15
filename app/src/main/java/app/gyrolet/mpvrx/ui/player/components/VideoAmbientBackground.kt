/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import app.gyrolet.mpvrx.ui.player.MPVView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.palette.graphics.Palette
import app.gyrolet.mpvrx.ui.player.HdrScreenMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import androidx.compose.ui.geometry.Rect as ComposeRect

private const val SAMPLE_WIDTH = 96
private const val SAMPLE_HEIGHT = 54
private const val DISPLAY_WIDTH = 32
private const val DISPLAY_HEIGHT = 18
private const val DECIMATION = 3

private const val CAPTURE_INTERVAL_MS = 350L
private const val IDLE_INTERVAL_MS = 1000L
private const val SMOOTH_INTERVAL_MS = 60L
private const val SMOOTH_IDLE_INTERVAL_MS = 200L
private const val SMOOTH_TIME_CONSTANT_MS = 450f
private const val CONVERGENCE_EPSILON = 0.0015f
private const val FRAME_CHANGE_THRESHOLD = 2
private const val CHANGE_SAMPLE_STEP = 7
private const val MAX_BACKOFF_MULTIPLIER = 4L
private const val FAILURES_BEFORE_FALLBACK = 2
private const val FAILURES_BEFORE_CLEAR = 3

private const val CAPTURE_OK = 0
private const val CAPTURE_RETRY = 1
private const val CAPTURE_UNSUPPORTED = 2

internal const val AMBIENT_DEFAULT_BLUR_RADIUS = 2
private const val BLUR_PASSES = 3
private const val LINEAR_LUT_SIZE = 4096

private const val MIN_COLOR_POPULATION = 3
private const val MIN_PREFERRED_LUMA = 0.20f
private const val MAX_PREFERRED_LUMA = 0.86f

private const val BASE_ALPHA = 0.52f
private const val FRAME_ALPHA = 0.46f
private const val ACCENT_ALPHA = 0.24f
private const val SCRIM_ALPHA = 0.24f

private val SRGB_TO_LINEAR =
  FloatArray(256) { byteValue ->
    val channel = byteValue / 255f
    if (channel <= 0.04045f) {
      channel / 12.92f
    } else {
      ((channel + 0.055f) / 1.055f).pow(2.4f)
    }
  }

private val LINEAR_TO_SRGB =
  IntArray(LINEAR_LUT_SIZE + 1) { index ->
    val channel = index / LINEAR_LUT_SIZE.toFloat()
    val encoded =
      if (channel <= 0.0031308f) {
        channel * 12.92f
      } else {
        1.055f * channel.pow(1f / 2.4f) - 0.055f
      }
    (encoded * 255f + 0.5f).toInt().coerceIn(0, 255)
  }

data class VideoAmbientFrame(
  val frame: ImageBitmap? = null,
  val base: Color? = null,
  val accent: Color? = null,
  val supported: Boolean = true,
)

@Composable
fun rememberVideoAmbientFrame(
  surfaceView: MPVView,
  active: Boolean,
  playbackGeneration: Long,
  hdrScreenMode: HdrScreenMode,
  orientation: Int,
  isSurfaceReadyProvider: () -> Boolean,
  isPlayingProvider: () -> Boolean,
  retainFrameWhenInactive: Boolean = false,
  blurRadius: Int = AMBIENT_DEFAULT_BLUR_RADIUS,
  fallbackFrameProvider: suspend (Int) -> Bitmap?,
): VideoAmbientFrame {
  var state by remember(surfaceView, playbackGeneration, hdrScreenMode, orientation) {
    mutableStateOf(VideoAmbientFrame())
  }
  val currentIsSurfaceReadyProvider by rememberUpdatedState(isSurfaceReadyProvider)
  val currentIsPlayingProvider by rememberUpdatedState(isPlayingProvider)
  val currentFallbackFrameProvider by rememberUpdatedState(fallbackFrameProvider)
  val currentBlurRadius by rememberUpdatedState(blurRadius)
  val lifecycleOwner = LocalLifecycleOwner.current

  LaunchedEffect(
    active,
    surfaceView,
    lifecycleOwner,
    playbackGeneration,
    hdrScreenMode,
    orientation,
    retainFrameWhenInactive,
  ) {
    if (!retainFrameWhenInactive) state = VideoAmbientFrame()
    if (!active) {
      return@LaunchedEffect
    }

    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
      if (!retainFrameWhenInactive) state = VideoAmbientFrame()
      val pipeline = VideoAmbientPipeline { currentBlurRadius }
      var unsupported = false
      try {
        coroutineScope {
          launch {
            pipeline.runCapture(
              surfaceView = surfaceView,
              isSurfaceReady = currentIsSurfaceReadyProvider,
              isPlaying = currentIsPlayingProvider,
              fallbackFrame = { currentFallbackFrameProvider(SAMPLE_WIDTH) },
              onCaptureLost = { state = VideoAmbientFrame() },
              onUnsupported = {
                unsupported = true
                state = VideoAmbientFrame(supported = false)
              },
            )
          }
          launch { pipeline.runSmoothing { state = it } }
        }
      } finally {
        pipeline.close()
        if (!unsupported && !retainFrameWhenInactive) state = VideoAmbientFrame()
      }
    }
  }

  return state
}

private class VideoAmbientPipeline(
  private val blurRadius: () -> Int,
) : AutoCloseable {
  private val sample = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
  private val samplePixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
  private val previousPixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
  private val pixelCopyHandler = Handler(Looper.getMainLooper())
  private val fallbackCanvas = Canvas(sample)
  private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private val sampleBounds = Rect(0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)

  private val cellCount = DISPLAY_WIDTH * DISPLAY_HEIGHT
  private val targetGrid = FloatArray(cellCount * 3)
  private val currentGrid = FloatArray(cellCount * 3)
  private val stagingGrid = FloatArray(cellCount * 3)
  private val scratchGrid = FloatArray(cellCount * 3)
  private val outputPixels = IntArray(cellCount)

  private val targetBase = FloatArray(3)
  private val stagingBase = FloatArray(3)
  private val currentBase = FloatArray(3)
  private val targetAccent = FloatArray(3)
  private val stagingAccent = FloatArray(3)
  private val currentAccent = FloatArray(3)

  private val outputBitmaps =
    arrayOf(
      Bitmap.createBitmap(DISPLAY_WIDTH, DISPLAY_HEIGHT, Bitmap.Config.ARGB_8888),
      Bitmap.createBitmap(DISPLAY_WIDTH, DISPLAY_HEIGHT, Bitmap.Config.ARGB_8888),
    )

  private var outputBitmapIndex = 0
  private var hasPreviousSample = false
  private var previousBlurRadius = -1
  private var hasTarget = false
  private var seeded = false
  private var supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
  private var consecutiveFailures = 0
  @Volatile private var closed = false

  suspend fun runCapture(
    surfaceView: MPVView,
    isSurfaceReady: () -> Boolean,
    isPlaying: () -> Boolean,
    fallbackFrame: suspend () -> Bitmap?,
    onCaptureLost: () -> Unit,
    onUnsupported: () -> Unit,
  ) {
    if (!supported) {
      onUnsupported()
      return
    }

    while (currentCoroutineContext().isActive && supported && !closed) {
      var cadence = IDLE_INTERVAL_MS
      if (isSurfaceReady() && surfaceView.width > 0 && surfaceView.height > 0) {
        when (captureSurface(surfaceView, sample, pixelCopyHandler)) {
          CAPTURE_OK -> {
            consecutiveFailures = 0
            cadence = if (isPlaying()) CAPTURE_INTERVAL_MS else IDLE_INTERVAL_MS
            processSample()
          }

          CAPTURE_UNSUPPORTED -> {
            supported = false
            onUnsupported()
          }

          else -> {
            consecutiveFailures++
            val fallbackApplied =
              if (consecutiveFailures >= FAILURES_BEFORE_FALLBACK) {
                applyFallbackFrame(fallbackFrame)
              } else {
                false
              }
            if (fallbackApplied) {
              consecutiveFailures = FAILURES_BEFORE_FALLBACK - 1
              cadence = if (isPlaying()) CAPTURE_INTERVAL_MS * 2 else IDLE_INTERVAL_MS
            } else {
              if (consecutiveFailures == FAILURES_BEFORE_CLEAR) {
                resetScene()
                onCaptureLost()
              }
              val multiplier =
                (1L shl consecutiveFailures.coerceAtMost(3))
                  .coerceAtMost(MAX_BACKOFF_MULTIPLIER)
              cadence = CAPTURE_INTERVAL_MS * multiplier
            }
          }
        }
      }
      if (supported && !closed) delay(cadence)
    }
  }

  private fun computeTarget(): Boolean {
    val radius = blurRadius().coerceIn(0, 4)
    sample.getPixels(samplePixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
    if (hasPreviousSample && previousBlurRadius == radius &&
      ambientMeanAbsoluteDifference(samplePixels, previousPixels) < FRAME_CHANGE_THRESHOLD
    ) {
      return false
    }

    samplePixels.copyInto(previousPixels)
    hasPreviousSample = true
    previousBlurRadius = radius
    decimateToLinear(samplePixels, stagingGrid)
    ambientBoxBlur(stagingGrid, scratchGrid, DISPLAY_WIDTH, DISPLAY_HEIGHT, radius, BLUR_PASSES)

    val (base, accent) = extractAmbientColors(sample)
    stagingBase.fill(0f)
    stagingAccent.fill(0f)
    base?.let { colorToLinear(it, stagingBase) }
    accent?.let { colorToLinear(it, stagingAccent) }
    return true
  }

  private suspend fun processSample() {
    val accepted = withContext(Dispatchers.Default) { computeTarget() }
    if (!accepted || closed) return
    stagingGrid.copyInto(targetGrid)
    stagingBase.copyInto(targetBase)
    stagingAccent.copyInto(targetAccent)
    hasTarget = true
  }

  private suspend fun applyFallbackFrame(provider: suspend () -> Bitmap?): Boolean {
    val frame = provider() ?: return false
    return try {
      if (frame.isRecycled || closed) return false
      fallbackCanvas.drawBitmap(frame, null, sampleBounds, fallbackPaint)
      processSample()
      true
    } finally {
      if (frame !== sample && !frame.isRecycled) frame.recycle()
    }
  }

  suspend fun runSmoothing(emit: (VideoAmbientFrame) -> Unit) {
    var previousTick = SystemClock.elapsedRealtime()
    while (currentCoroutineContext().isActive && supported && !closed) {
      if (!hasTarget) {
        delay(SMOOTH_IDLE_INTERVAL_MS)
        previousTick = SystemClock.elapsedRealtime()
        continue
      }

      if (!seeded) {
        targetGrid.copyInto(currentGrid)
        targetBase.copyInto(currentBase)
        targetAccent.copyInto(currentAccent)
        seeded = true
        emit(publish())
        delay(SMOOTH_INTERVAL_MS)
        previousTick = SystemClock.elapsedRealtime()
        continue
      }

      val now = SystemClock.elapsedRealtime()
      val elapsedMs = (now - previousTick).coerceIn(1L, 500L)
      previousTick = now
      val alpha = 1f - exp(-elapsedMs.toFloat() / SMOOTH_TIME_CONSTANT_MS)

      val changed =
        ambientStep(currentGrid, targetGrid, alpha) or
          ambientStep(currentBase, targetBase, alpha) or
          ambientStep(currentAccent, targetAccent, alpha)

      if (changed) {
        emit(publish())
        delay(SMOOTH_INTERVAL_MS)
      } else {
        delay(SMOOTH_IDLE_INTERVAL_MS)
        previousTick = SystemClock.elapsedRealtime()
      }
    }
  }

  private fun publish(): VideoAmbientFrame {
    encodeAmbientPixels(currentGrid, outputPixels)
    outputBitmapIndex = outputBitmapIndex xor 1
    val bitmap = outputBitmaps[outputBitmapIndex]
    bitmap.setPixels(outputPixels, 0, DISPLAY_WIDTH, 0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT)
    return VideoAmbientFrame(
      frame = bitmap.asImageBitmap(),
      base = colorFromLinear(currentBase),
      accent = colorFromLinear(currentAccent),
    )
  }

  private fun resetScene() {
    hasPreviousSample = false
    hasTarget = false
    seeded = false
  }

  override fun close() {
    closed = true
    supported = false
    resetScene()
  }
}

internal fun ambientStep(
  current: FloatArray,
  target: FloatArray,
  alpha: Float,
): Boolean {
  var changed = false
  for (index in current.indices) {
    val delta = target[index] - current[index]
    if (abs(delta) > CONVERGENCE_EPSILON) {
      current[index] += delta * alpha
      changed = true
    } else {
      current[index] = target[index]
    }
  }
  return changed
}

private fun decimateToLinear(
  source: IntArray,
  destination: FloatArray,
) {
  val sampleCount = (DECIMATION * DECIMATION).toFloat()
  var outputIndex = 0
  for (y in 0 until DISPLAY_HEIGHT) {
    for (x in 0 until DISPLAY_WIDTH) {
      var red = 0f
      var green = 0f
      var blue = 0f
      for (offsetY in 0 until DECIMATION) {
        var sourceIndex = (y * DECIMATION + offsetY) * SAMPLE_WIDTH + x * DECIMATION
        repeat(DECIMATION) {
          val color = source[sourceIndex++]
          red += SRGB_TO_LINEAR[(color shr 16) and 0xFF]
          green += SRGB_TO_LINEAR[(color shr 8) and 0xFF]
          blue += SRGB_TO_LINEAR[color and 0xFF]
        }
      }
      destination[outputIndex++] = red / sampleCount
      destination[outputIndex++] = green / sampleCount
      destination[outputIndex++] = blue / sampleCount
    }
  }
}

internal fun ambientBoxBlur(
  buffer: FloatArray,
  scratch: FloatArray,
  width: Int,
  height: Int,
  radius: Int,
  passes: Int,
) {
  if (radius <= 0 || width <= 0 || height <= 0) return
  repeat(passes) {
    blurAmbientAxis(buffer, scratch, width, height, radius, horizontal = true)
    blurAmbientAxis(scratch, buffer, width, height, radius, horizontal = false)
  }
}

private fun blurAmbientAxis(
  source: FloatArray,
  destination: FloatArray,
  width: Int,
  height: Int,
  radius: Int,
  horizontal: Boolean,
) {
  val lineCount = if (horizontal) height else width
  val lineLength = if (horizontal) width else height
  for (line in 0 until lineCount) {
    for (position in 0 until lineLength) {
      var red = 0f
      var green = 0f
      var blue = 0f
      var count = 0
      for (offset in -radius..radius) {
        val samplePosition = (position + offset).coerceIn(0, lineLength - 1)
        val outputPosition = if (horizontal) line * width + samplePosition else samplePosition * width + line
        val index = outputPosition * 3
        red += source[index]
        green += source[index + 1]
        blue += source[index + 2]
        count++
      }
      val outputPosition = if (horizontal) line * width + position else position * width + line
      val index = outputPosition * 3
      destination[index] = red / count
      destination[index + 1] = green / count
      destination[index + 2] = blue / count
    }
  }
}

private fun encodeAmbientPixels(
  grid: FloatArray,
  output: IntArray,
) {
  for (index in output.indices) {
    val gridIndex = index * 3
    output[index] =
      (0xFF shl 24) or
        (ambientLinearToSrgb(grid[gridIndex]) shl 16) or
        (ambientLinearToSrgb(grid[gridIndex + 1]) shl 8) or
        ambientLinearToSrgb(grid[gridIndex + 2])
  }
}

internal fun ambientLinearToSrgb(value: Float): Int =
  LINEAR_TO_SRGB[(value.coerceIn(0f, 1f) * LINEAR_LUT_SIZE + 0.5f).toInt()]

internal fun ambientSrgbToLinear(byteValue: Int): Float = SRGB_TO_LINEAR[byteValue.coerceIn(0, 255)]

private fun colorToLinear(
  color: Color,
  output: FloatArray,
) {
  output[0] = ambientSrgbToLinear((color.red * 255f + 0.5f).toInt())
  output[1] = ambientSrgbToLinear((color.green * 255f + 0.5f).toInt())
  output[2] = ambientSrgbToLinear((color.blue * 255f + 0.5f).toInt())
}

private fun colorFromLinear(channels: FloatArray): Color =
  Color(
    ambientLinearToSrgb(channels[0]),
    ambientLinearToSrgb(channels[1]),
    ambientLinearToSrgb(channels[2]),
  )

internal fun ambientMeanAbsoluteDifference(
  current: IntArray,
  previous: IntArray,
): Int {
  var sum = 0L
  var channelCount = 0
  var index = 0
  while (index < current.size) {
    val currentColor = current[index]
    val previousColor = previous[index]
    sum += abs(((currentColor shr 16) and 0xFF) - ((previousColor shr 16) and 0xFF))
    sum += abs(((currentColor shr 8) and 0xFF) - ((previousColor shr 8) and 0xFF))
    sum += abs((currentColor and 0xFF) - (previousColor and 0xFF))
    channelCount += 3
    index += CHANGE_SAMPLE_STEP
  }
  return if (channelCount == 0) 0 else (sum / channelCount).toInt()
}

private suspend fun captureSurface(
  surfaceView: MPVView,
  destination: Bitmap,
  handler: Handler,
): Int =
  suspendCancellableCoroutine { continuation ->
    val surface = surfaceView.renderSurface
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      continuation.resume(CAPTURE_UNSUPPORTED)
      return@suspendCancellableCoroutine
    }
    if (surface?.isValid != true) {
      continuation.resume(CAPTURE_RETRY)
      return@suspendCancellableCoroutine
    }

    try {
      PixelCopy.request(
        surface,
        destination,
        { result ->
          if (continuation.isActive) {
            continuation.resume(
              when (result) {
                PixelCopy.SUCCESS -> CAPTURE_OK
                else -> CAPTURE_RETRY
              },
            )
          }
        },
        handler,
      )
    } catch (error: IllegalArgumentException) {
      Log.w("VideoAmbient", "PixelCopy could not capture the video surface", error)
      if (continuation.isActive) continuation.resume(CAPTURE_RETRY)
    }
  }

private fun extractAmbientColors(bitmap: Bitmap): Pair<Color?, Color?> {
  val palette = Palette.from(bitmap).clearFilters().generate()
  val usableSwatches =
    palette.swatches
      .filter { it.population >= MIN_COLOR_POPULATION }
      .sortedByDescending { swatch ->
        val hsl = swatch.hsl
        val lumaFit = 1f - abs(hsl[2].coerceIn(0f, 1f) - 0.56f)
        (hsl[1] * 1.5f + lumaFit) * swatch.population
      }

  val base =
    usableSwatches.firstOrNull { it.hsl[2] in MIN_PREFERRED_LUMA..MAX_PREFERRED_LUMA }
      ?: palette.vibrantSwatch
      ?: palette.lightVibrantSwatch
      ?: palette.dominantSwatch
  val accent =
    palette.vibrantSwatch
      ?: palette.lightVibrantSwatch
      ?: usableSwatches.firstOrNull()
      ?: palette.mutedSwatch
      ?: palette.dominantSwatch
  return base?.let { Color(it.rgb) } to accent?.let { Color(it.rgb) }
}

@Composable
fun VideoAmbientBackground(
  frame: ImageBitmap?,
  baseColor: Color?,
  accentColor: Color?,
  videoBounds: ComposeRect,
  edgeBlend: Float,
  modifier: Modifier = Modifier,
) {
  val base = baseColor ?: Color.Transparent
  val accent = accentColor ?: Color.Transparent

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .videoAmbientMask(videoBounds, edgeBlend)
        .background(Color.Black),
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(base.copy(alpha = BASE_ALPHA)),
    )
    if (frame != null) {
      Image(
        bitmap = frame,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
        alpha = FRAME_ALPHA,
      )
    }
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(accent.copy(alpha = ACCENT_ALPHA)),
    )
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = SCRIM_ALPHA)),
    )
  }
}

internal fun Modifier.videoAmbientMask(videoBounds: ComposeRect, edgeBlend: Float): Modifier {
  val layer =
    if (edgeBlend > 0f) {
      Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    } else {
      Modifier
    }
  return then(layer).drawWithCache {
    val canvasSize = size
    val videoPath = Path().apply { addRect(videoBounds) }
    val blendWidth = edgeBlend.coerceIn(0f, 0.1f) * minOf(videoBounds.width, videoBounds.height)
    val masks =
      buildList<Pair<ComposeRect, Brush>> {
        if (blendWidth <= 0f || videoBounds.isEmpty) return@buildList
        if (videoBounds.left > 0f) {
          add(
            ComposeRect(0f, 0f, videoBounds.left, canvasSize.height) to
              Brush.horizontalGradient(
                listOf(Color.Black, Color.Transparent),
                startX = videoBounds.left - blendWidth,
                endX = videoBounds.left,
              ),
          )
        }
        if (videoBounds.right < canvasSize.width) {
          add(
            ComposeRect(videoBounds.right, 0f, canvasSize.width, canvasSize.height) to
              Brush.horizontalGradient(
                listOf(Color.Transparent, Color.Black),
                startX = videoBounds.right,
                endX = videoBounds.right + blendWidth,
              ),
          )
        }
        if (videoBounds.top > 0f) {
          add(
            ComposeRect(0f, 0f, canvasSize.width, videoBounds.top) to
              Brush.verticalGradient(
                listOf(Color.Black, Color.Transparent),
                startY = videoBounds.top - blendWidth,
                endY = videoBounds.top,
              ),
          )
        }
        if (videoBounds.bottom < canvasSize.height) {
          add(
            ComposeRect(0f, videoBounds.bottom, canvasSize.width, canvasSize.height) to
              Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black),
                startY = videoBounds.bottom,
                endY = videoBounds.bottom + blendWidth,
              ),
          )
        }
      }
    onDrawWithContent {
      if (!videoBounds.isEmpty) {
        // The background, including its edge fade, must never paint over video or subtitle pixels.
        clipPath(videoPath, clipOp = ClipOp.Difference) {
          this@onDrawWithContent.drawContent()
          for ((bounds, brush) in masks) {
            drawRect(
              brush = brush,
              topLeft = Offset(bounds.left, bounds.top),
              size = bounds.size,
              blendMode = BlendMode.DstIn,
            )
          }
        }
      }
    }
  }
}
