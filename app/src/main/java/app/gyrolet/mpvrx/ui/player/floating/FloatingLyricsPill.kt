/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Floating-lyrics design and motion based on Mochy Music (GPL-3.0):
 * https://github.com/realidkroo/Mochy-Music/tree/bde01cb4321895c406ca93679462447236c2b2c3/app/src/main/kotlin/com/mochyy/mochymusic/floating
 * Adapted to mpvRx's playback, accessibility and window lifecycle.
 */

package app.gyrolet.mpvrx.ui.player.floating

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.lyrics.SyncedLine
import app.gyrolet.mpvrx.preferences.LyricsTranslationDisplayMode
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.LyricWordSpan
import app.gyrolet.mpvrx.ui.player.controls.components.drawLyricFill
import app.gyrolet.mpvrx.ui.player.controls.components.hasRtlDirection
import app.gyrolet.mpvrx.ui.player.controls.components.lyricCharacterPosition
import app.gyrolet.mpvrx.ui.player.controls.components.lyricSweepX
import app.gyrolet.mpvrx.ui.player.controls.components.lyricWordSpans
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.LocalAppFontFamily
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class FloatingPillShape { COVER, PILL, OPEN }

@Immutable
internal data class FloatingPillMetrics(
  val bubbleWidth: Dp = 188.dp,
  val rowPitch: Dp = 21.dp,
  val baseHeight: Dp = 58.dp,
  val maxSlots: Int = 3,
) {
  fun bubbleHeight(slots: Float): Dp = baseHeight + rowPitch * (slots - 2f)
  fun bodyHeight(slots: Float): Dp = maxOf(Cover, BubbleTop + bubbleHeight(slots))
  fun barY(slots: Float): Dp = Margin + bodyHeight(slots) + 16.dp
  fun width(shape: FloatingPillShape): Dp =
    Margin * 2 + Cover + if (shape == FloatingPillShape.COVER) 0.dp else Gap + bubbleWidth +
      if (shape == FloatingPillShape.OPEN) ButtonExtra else 0.dp

  fun height(shape: FloatingPillShape, slots: Int): Dp =
    Margin * 2 + if (shape == FloatingPillShape.COVER) Cover else bodyHeight(slots.toFloat()) +
      if (shape == FloatingPillShape.OPEN) BarArea else 0.dp

  companion object {
    val Margin = 20.dp
    val Cover = 62.dp
    val Gap = 10.dp
    val BubbleTop = 2.dp
    val ButtonExtra = 48.dp
    val BarArea = 40.dp
    val SeekReach = 22.dp
  }
}

@Stable
internal class FloatingPillState {
  var model by mutableStateOf(FloatingLyricsModel())
  var metrics by mutableStateOf(FloatingPillMetrics())
  var expanded by mutableStateOf(false)
  var dockEnd by mutableStateOf(true)
  var tucked by mutableStateOf(false)
  var lifted by mutableStateOf(false)
  var touchTick by mutableIntStateOf(0)
}

internal interface FloatingPillHost {
  fun reserveFrame(shape: FloatingPillShape, slots: Int)
  fun fitFrame(shape: FloatingPillShape, slots: Int)
  fun toggleExpanded()
  fun collapse()
  fun openPlayer()
  fun togglePlay(generation: Long)
  fun previous(generation: Long)
  fun next(generation: Long)
  fun seekTo(position: Long, generation: Long)
  fun touch()
  fun dock(right: Boolean)
  fun dismiss(permanently: Boolean)
}

private data class PillColors(val bubble: Color, val ink: Color) {
  companion object {
    fun from(accent: Int?): PillColors {
      var fill = accent ?: 0xFFE8EEF1.toInt()
      val dark = 0xFF0D0D0E.toInt()
      val useDark = ColorUtils.calculateContrast(dark, fill) >= ColorUtils.calculateContrast(android.graphics.Color.WHITE, fill)
      val ink = if (useDark) dark else android.graphics.Color.WHITE
      repeat(12) {
        if (ColorUtils.calculateContrast(ink, fill) < 4.5) {
          fill = ColorUtils.blendARGB(fill, if (useDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK, 0.1f)
        }
      }
      return PillColors(Color(fill), Color(ink))
    }
  }
}

@Composable
internal fun FloatingLyricsPill(
  state: FloatingPillState,
  displayMode: LyricsTranslationDisplayMode,
  host: FloatingPillHost,
) {
  val reduced = AppMotion.shouldReduceMotion()
  val model by remember(state) {
    derivedStateOf { state.model.copy(positionMs = 0L, positionUpdatedAt = 0L, speed = 1f, seekTick = 0L, lineIndex = -1) }
  }
  val metrics = state.metrics
  val script = remember(model.generation, model.lines, model.title, model.artist, model.durationMs, model.errorMessage, displayMode) {
    pillScript(model, displayMode)
  }
  val slots = script.slots.coerceAtMost(metrics.maxSlots)
  val shape = when {
    state.tucked -> FloatingPillShape.COVER
    state.expanded -> FloatingPillShape.OPEN
    else -> FloatingPillShape.PILL
  }
  val expansion = remember { Animatable(0f) }
  val tuck = remember { Animatable(1f) }
  val appearance = remember { Animatable(0f) }
  val rows = remember { Animatable(slots.toFloat()) }
  LaunchedEffect(reduced) {
    if (reduced) appearance.snapTo(1f)
    else appearance.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 380f))
  }
  LaunchedEffect(shape, slots, metrics, reduced) {
    host.reserveFrame(shape, slots)
    if (!reduced && appearance.value < 0.5f) delay(90)
    coroutineScope {
      launch {
        expansion.animateTo(
          if (shape == FloatingPillShape.OPEN) 1f else 0f,
          if (reduced) snap() else if (shape == FloatingPillShape.OPEN) {
            tween(640, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
          } else tween(460, easing = CubicBezierEasing(0.33f, 0.9f, 0.3f, 1f)),
        )
      }
      launch {
        tuck.animateTo(
          if (shape == FloatingPillShape.COVER) 1f else 0f,
          if (reduced) snap() else if (shape == FloatingPillShape.COVER) {
            spring(dampingRatio = 1f, stiffness = 900f)
          } else spring(dampingRatio = 0.58f, stiffness = 420f),
        )
      }
      launch { rows.animateTo(slots.toFloat(), if (reduced) snap() else spring(dampingRatio = 0.85f, stiffness = 320f)) }
    }
    host.fitFrame(shape, slots)
  }
  val scrub = remember(model.generation) { mutableFloatStateOf(-1f) }
  LaunchedEffect(model.canSeek) { if (!model.canSeek) scrub.floatValue = -1f }
  LaunchedEffect(state.expanded, state.touchTick, scrub.floatValue >= 0f) {
    if (state.expanded && scrub.floatValue < 0f) {
      delay(4_500)
      host.collapse()
    }
  }
  val target = remember(model.artworkAccent) { PillColors.from(model.artworkAccent) }
  val bubble = animateColorAsState(target.bubble, if (reduced) snap() else tween(700), label = "floatingBubbleColor")
  val ink = animateColorAsState(target.ink, if (reduced) snap() else tween(700), label = "floatingInkColor")
  var pressed by remember { mutableStateOf(false) }
  val press = animateFloatAsState(
    if (pressed) 0.93f else 1f,
    if (reduced) snap() else spring(dampingRatio = 0.6f, stiffness = 600f),
    label = "floatingCoverPress",
  )
  val lift = animateFloatAsState(
    if (state.lifted) 1f else 0f,
    if (reduced) snap() else spring(dampingRatio = 0.55f, stiffness = 500f),
    label = "floatingCoverLift",
  )
  val haptic = LocalHapticFeedback.current
  val coverScale = { press.value * (1f + 0.08f * lift.value) * (0.4f + 0.6f * appearance.value) }
  val coverAlpha = { (appearance.value * 2f).coerceIn(0f, 1f) }
  val end = state.dockEnd
  val expandLabel = stringResource(if (state.expanded) R.string.generic_collapse else R.string.generic_expand)
  val openLabel = stringResource(R.string.floating_lyrics_open_player)
  val leftLabel = stringResource(R.string.floating_lyrics_dock_left)
  val rightLabel = stringResource(R.string.floating_lyrics_dock_right)
  val closeLabel = stringResource(R.string.floating_lyrics_dismiss)
  val offLabel = stringResource(R.string.floating_lyrics_turn_off)
  val previousLabel = stringResource(R.string.pref_gesture_media_previous)
  val nextLabel = stringResource(R.string.pref_gesture_media_next)
  Box(Modifier.fillMaxSize()) {
    PillPlayButton(state, host, bubble.value, ink.value, end, expansion, reduced)
    Box(
      Modifier.pillPosition(end, { FloatingPillMetrics.Margin.toPx() }, { FloatingPillMetrics.Margin.toPx() })
        .size(FloatingPillMetrics.Cover)
        .graphicsLayer {
          scaleX = coverScale()
          scaleY = scaleX
          alpha = coverAlpha()
          compositingStrategy = CompositingStrategy.ModulateAlpha
        }
        .pillShadow(31.dp, 12.dp, { 4.dp + 5.dp * lift.value }, { 0.28f + 0.1f * lift.value })
        .semantics {
          role = Role.Button
          contentDescription = model.title
          onClick(expandLabel) { host.toggleExpanded(); true }
          onLongClick(openLabel) { host.openPlayer(); true }
          customActions = buildList {
            add(CustomAccessibilityAction(leftLabel) { host.dock(false); true })
            add(CustomAccessibilityAction(rightLabel) { host.dock(true); true })
            add(CustomAccessibilityAction(closeLabel) { host.dismiss(false); true })
            add(CustomAccessibilityAction(offLabel) { host.dismiss(true); true })
            if (model.canPrevious) add(CustomAccessibilityAction(previousLabel) { host.previous(model.generation); true })
            if (model.canNext) add(CustomAccessibilityAction(nextLabel) { host.next(model.generation); true })
          }
        }
        .pointerInput(host) {
          detectTapGestures(
            onPress = {
              pressed = true
              try { tryAwaitRelease() } finally { pressed = false }
            },
            onTap = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              host.toggleExpanded()
            },
            onLongPress = {
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              host.openPlayer()
            },
          )
        },
    ) {
      Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFF55585C)), contentAlignment = Alignment.Center) {
        val artwork = model.artwork
        if (artwork != null && !artwork.isRecycled) {
          Image(
            bitmap = remember(artwork) { artwork.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else Icon(Icons.RoundedFilled.Audiotrack, null, modifier = Modifier.size(28.dp), tint = Color.White)
      }
    }
    PillProgress(state, metrics, expansion, rows, coverScale, coverAlpha, scrub)
    PillTimes(state, metrics, expansion, rows, scrub)
    Box(
      Modifier.pillPosition(
        end,
        { (FloatingPillMetrics.Margin + FloatingPillMetrics.Cover + FloatingPillMetrics.Gap).toPx() +
          FloatingPillMetrics.ButtonExtra.toPx() * expansion.value },
        { (FloatingPillMetrics.Margin + FloatingPillMetrics.BubbleTop).toPx() },
      )
        .layout { measurable, _ ->
          val width = metrics.bubbleWidth.roundToPx()
          val height = metrics.bubbleHeight(rows.value).roundToPx().coerceAtLeast(1)
          val content = measurable.measure(Constraints.fixed(width, height))
          layout(width, height) { content.place(0, 0) }
        }
        .graphicsLayer {
          scaleX = (1f - 0.72f * tuck.value).coerceAtLeast(0.01f)
          scaleY = scaleX
          alpha = (1f - tuck.value * 1.6f).coerceIn(0f, 1f)
          translationX = (if (end) 1f else -1f) * 18.dp.toPx() * tuck.value
          transformOrigin = TransformOrigin(if (end) 1f else 0f, (metrics.baseHeight.toPx() * 0.64f / size.height).coerceIn(0f, 1f))
          compositingStrategy = CompositingStrategy.ModulateAlpha
        }
        .pillShadow(18.dp, 14.dp, { 5.dp }, { 0.2f })
        .drawWithCache {
          val path = speechBubblePath(size, 18.dp.toPx(), 8.dp.toPx(), 13.dp.toPx(), metrics.baseHeight.toPx() * 0.64f, end)
          onDrawBehind { drawPath(path, bubble.value) }
        },
    ) {
      PillLyricRows(state, script, metrics, bubble.value, ink.value, host, reduced)
    }
    PillSeekTouch(state, metrics, host, rows, scrub)
  }
}

private fun Modifier.pillPosition(
  end: Boolean,
  x: Density.() -> Float,
  y: Density.() -> Float,
): Modifier = layout { measurable, constraints ->
  val child = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
  layout(child.width, child.height) {
    val side = x()
    child.place((if (end) constraints.maxWidth - side - child.width else side).roundToInt(), y().roundToInt())
  }
}

private fun Modifier.pillShadow(corner: Dp, blur: Dp, drop: () -> Dp, strength: () -> Float): Modifier {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return shadow(3.dp, RoundedCornerShape(corner), clip = false)
  return drawWithCache {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(blur.toPx(), BlurMaskFilter.Blur.NORMAL) }
    val radius = corner.toPx().coerceAtMost(size.minDimension / 2f)
    val inset = blur.toPx() * 0.2f
    onDrawBehind {
      paint.color = android.graphics.Color.argb((strength().coerceIn(0f, 1f) * 255).roundToInt(), 0, 0, 0)
      val dy = drop().toPx()
      drawIntoCanvas { it.nativeCanvas.drawRoundRect(inset, dy, size.width - inset, size.height + dy, radius, radius, paint) }
    }
  }
}

private fun speechBubblePath(size: Size, radius: Float, width: Float, height: Float, y: Float, end: Boolean): Path {
  val body = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius))) }
  fun side(x: Float): Float = if (end) size.width - x else x
  val tail = Path().apply {
    moveTo(side(2f), y - height / 2)
    quadraticTo(side(-width * 0.55f), y - height * 0.1f, side(-width), y + height * 0.08f)
    quadraticTo(side(-width * 0.4f), y + height * 0.4f, side(2f), y + height / 2)
    close()
  }
  return Path().apply { op(body, tail, PathOperation.Union) }
}

@Composable
private fun PillPlayButton(
  state: FloatingPillState,
  host: FloatingPillHost,
  bubble: Color,
  ink: Color,
  end: Boolean,
  expansion: Animatable<Float, AnimationVector1D>,
  reduced: Boolean,
) {
  val model by remember(state) {
    derivedStateOf { state.model.copy(positionMs = 0L, positionUpdatedAt = 0L, speed = 1f, seekTick = 0L, lineIndex = -1) }
  }
  val playing = animateFloatAsState(
    if (model.paused) 0f else 1f,
    if (reduced) snap() else spring(dampingRatio = 0.55f, stiffness = 520f),
    label = "floatingPlayGlyph",
  )
  val haptic = LocalHapticFeedback.current
  var pressed by remember { mutableStateOf(false) }
  val press = animateFloatAsState(
    if (pressed) 0.82f else 1f,
    if (reduced) snap() else spring(dampingRatio = 0.6f, stiffness = 700f),
    label = "floatingPlayPress",
  )
  Box(
    Modifier.pillPosition(end, { (FloatingPillMetrics.Margin + FloatingPillMetrics.Cover / 2).toPx() }, { FloatingPillMetrics.Margin.toPx() })
      .layout { measurable, _ ->
        val width = (FloatingPillMetrics.Cover / 2).roundToPx() +
          (FloatingPillMetrics.ButtonExtra.toPx() * expansion.value).roundToInt()
        val height = FloatingPillMetrics.Cover.roundToPx()
        val child = measurable.measure(Constraints.fixed(width.coerceAtLeast(1), height))
        layout(width, height) { child.place(0, 0) }
      }
      .graphicsLayer { alpha = (expansion.value * 4f).coerceIn(0f, 1f) }
      .pillShadow(16.dp, 12.dp, { 4.dp }, { 0.18f * expansion.value })
      .background(bubble, RoundedCornerShape(16.dp)),
  )
  val interactive = state.expanded && model.canControl
  val label = stringResource(if (model.paused) R.string.ui_play else R.string.audiobook_pause)
  Box(
    Modifier.pillPosition(end, { (FloatingPillMetrics.Margin + FloatingPillMetrics.Cover).toPx() }, { FloatingPillMetrics.Margin.toPx() })
      .size(FloatingPillMetrics.ButtonExtra, FloatingPillMetrics.Cover)
      .clearAndSetSemantics {
        if (state.expanded) {
          role = Role.Button
          contentDescription = label
          if (!model.canControl) disabled()
          onClick { if (model.canControl) host.togglePlay(model.generation); model.canControl }
        }
      }
      .pointerInput(host, model.generation, interactive) {
        if (interactive) detectTapGestures(
          onPress = {
            pressed = true
            try { tryAwaitRelease() } finally { pressed = false }
          },
          onTap = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            host.togglePlay(model.generation)
          },
        )
      }
      .graphicsLayer {
        val shown = ((expansion.value - 0.35f) / 0.65f).coerceIn(0f, 1f)
        alpha = shown * if (model.canControl) 1f else 0.38f
        scaleX = (0.6f + 0.4f * shown) * press.value
        scaleY = scaleX
        translationX = (if (end) 1f else -1f) * FloatingPillMetrics.ButtonExtra.toPx() / 2 * (1f - expansion.value)
      },
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      Icons.RoundedFilled.PlayArrow, null, tint = ink,
      modifier = Modifier.size(26.dp).graphicsLayer {
        val shown = 1f - playing.value
        alpha = shown.coerceIn(0f, 1f)
        scaleX = 0.5f + 0.5f * shown
        scaleY = scaleX
      },
    )
    Icon(
      Icons.RoundedFilled.Pause, null, tint = ink,
      modifier = Modifier.size(26.dp).graphicsLayer {
        alpha = playing.value.coerceIn(0f, 1f)
        scaleX = 0.5f + 0.5f * playing.value
        scaleY = scaleX
      },
    )
  }
}

@Composable
private fun PillProgress(
  state: FloatingPillState,
  metrics: FloatingPillMetrics,
  expansion: Animatable<Float, AnimationVector1D>,
  rows: Animatable<Float, AnimationVector1D>,
  coverScale: () -> Float,
  coverAlpha: () -> Float,
  scrub: MutableFloatState,
) {
  val reduced = AppMotion.shouldReduceMotion()
  val grab = animateFloatAsState(
    if (scrub.floatValue >= 0f) 1f else 0f,
    if (reduced) snap() else spring(dampingRatio = 0.7f, stiffness = 600f),
    label = "floatingSeekGrab",
  )
  val track = remember { Path() }
  val played = remember { Path() }
  Spacer(
    Modifier.fillMaxSize().drawWithContent {
      val model = state.model
      val shown = coverAlpha()
      if (shown <= 0f) return@drawWithContent
      val t = expansion.value.coerceIn(0f, 1f)
      val margin = FloatingPillMetrics.Margin.toPx()
      val cover = FloatingPillMetrics.Cover.toPx()
      val center = Offset(if (state.dockEnd) size.width - margin - cover / 2 else margin + cover / 2, margin + cover / 2)
      val radius = (cover / 2 - 5.dp.toPx()) * coverScale()
      val left = margin + 2.dp.toPx()
      val right = size.width - margin - 2.dp.toPx()
      val y = metrics.barY(rows.value).toPx()
      val stroke = 2.5.dp.toPx() + 1.5.dp.toPx() * t
      val progress = scrub.floatValue.takeIf { it >= 0f }
        ?: if (model.durationMs > 0) (model.positionMs.toFloat() / model.durationMs).coerceIn(0f, 1f) else 0f
      val trackColor = lerp(Color.Black.copy(alpha = 0.2f), Color.White.copy(alpha = 0.42f), t)
      val head = Color.White.copy(alpha = shown)
      val shade = ((t - 0.85f) / 0.15f).coerceIn(0f, 1f)
      if (shade > 0f) drawLine(
        Color.Black.copy(alpha = 0.22f * shade), Offset(left, y), Offset(right, y),
        stroke + 3.dp.toPx() * grab.value + 3.dp.toPx(), StrokeCap.Round,
      )
      when {
        t <= 0.001f -> {
          drawCircle(trackColor.copy(alpha = trackColor.alpha * shown), radius, center, style = Stroke(stroke))
          if (progress > 0f) drawArc(
            head, -90f, progress * 360f, false,
            Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2),
            style = Stroke(stroke, cap = StrokeCap.Round),
          )
        }
        t >= 0.999f -> {
          val thickness = stroke + 3.dp.toPx() * grab.value
          val x = left + (right - left) * progress
          drawLine(trackColor, Offset(left, y), Offset(right, y), thickness, StrokeCap.Round)
          if (progress > 0f) drawLine(head, Offset(left, y), Offset(x, y), thickness, StrokeCap.Round)
          if (grab.value > 0.01f) drawCircle(head, 7.dp.toPx() * grab.value, Offset(x, y))
        }
        else -> {
          ringPath(track, 1f, center, radius, left, right, y, t)
          ringPath(played, progress, center, radius, left, right, y, t)
          val style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
          drawPath(track, trackColor, style = style)
          if (progress > 0f) drawPath(played, head, style = style)
        }
      }
    },
  )
}

private fun ringPath(path: Path, through: Float, center: Offset, radius: Float, left: Float, right: Float, y: Float, t: Float) {
  fun point(s: Float): Offset {
    val raw = (t * 1.45f - s * 0.45f).coerceIn(0f, 1f)
    val mix = raw * raw * (3f - 2f * raw)
    val angle = (-90f + 360f * s) * PI.toFloat() / 180f
    return androidx.compose.ui.geometry.lerp(
      Offset(center.x + radius * cos(angle), center.y + radius * sin(angle)),
      Offset(left + (right - left) * s, y), mix,
    )
  }
  path.reset()
  val start = point(0f)
  path.moveTo(start.x, start.y)
  val count = (through * 64).toInt()
  for (index in 1..count) {
    val next = point(index / 64f)
    path.lineTo(next.x, next.y)
  }
  if (through * 64 > count) {
    val last = point(through)
    path.lineTo(last.x, last.y)
  }
}

@Composable
private fun PillTimes(
  state: FloatingPillState,
  metrics: FloatingPillMetrics,
  expansion: Animatable<Float, AnimationVector1D>,
  rows: Animatable<Float, AnimationVector1D>,
  scrub: MutableFloatState,
) {
  if (!state.expanded && expansion.value <= 0.01f) return
  val model = state.model
  val elapsed = scrub.floatValue.takeIf { it >= 0f }?.let { (it * model.durationMs).toLong() } ?: model.positionMs
  val style = TextStyle(
    fontFamily = LocalAppFontFamily.current, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
    color = Color.White.copy(alpha = 0.86f), shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 1f), 5f),
  )
  for (right in listOf(false, true)) BasicText(
    text = DateUtils.formatElapsedTime((if (right) model.durationMs else elapsed).coerceAtLeast(0) / 1_000),
    style = style,
    modifier = Modifier.pillPosition(
      right,
      { FloatingPillMetrics.Margin.toPx() },
      { metrics.barY(rows.value).toPx() + 12.dp.toPx() },
    ).graphicsLayer {
      alpha = ((expansion.value - 0.5f) / 0.5f).coerceIn(0f, 1f)
      translationY = (1f - alpha) * 6.dp.toPx()
    },
  )
}

@Composable
private fun PillSeekTouch(
  state: FloatingPillState,
  metrics: FloatingPillMetrics,
  host: FloatingPillHost,
  rows: Animatable<Float, AnimationVector1D>,
  scrub: MutableFloatState,
) {
  if (!state.expanded || !state.model.canSeek || state.model.durationMs <= 0) return
  val model = state.model
  val haptic = LocalHapticFeedback.current
  val label = stringResource(R.string.pref_player_seeking_title)
  Box(
    Modifier.layout { measurable, constraints ->
      val height = (FloatingPillMetrics.SeekReach * 2).roundToPx()
      val content = measurable.measure(Constraints.fixed(constraints.maxWidth, height))
      layout(content.width, height) { content.place(0, (metrics.barY(rows.value) - FloatingPillMetrics.SeekReach).roundToPx()) }
    }
      .semantics {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(
          (model.positionMs.toFloat() / model.durationMs).coerceIn(0f, 1f), 0f..1f,
        )
        setProgress { host.seekTo((it.coerceIn(0f, 1f) * model.durationMs).toLong(), model.generation); true }
      }
      .pointerInput(host, model.generation, model.canSeek) {
        awaitEachGesture {
          val down = awaitFirstDown()
          val left = (FloatingPillMetrics.Margin + 2.dp).toPx()
          val span = (size.width - left * 2).coerceAtLeast(1f)
          fun at(x: Float): Float = ((x - left) / span).coerceIn(0f, 1f)
          haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          host.touch()
          scrub.floatValue = at(down.position.x)
          down.consume()
          var released = false
          try {
            while (true) {
              val event = awaitPointerEvent()
              if (event.changes.count { it.pressed } > 1) break
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (change.isConsumed) break
              scrub.floatValue = at(change.position.x)
              if (!change.pressed) { released = true; change.consume(); break }
              change.consume()
            }
          } finally {
            if (released && scrub.floatValue >= 0f) {
              host.seekTo((scrub.floatValue * state.model.durationMs).toLong(), model.generation)
            }
            scrub.floatValue = -1f
            host.touch()
          }
        }
      },
  )
}

private data class PillRow(
  val text: String,
  val line: SyncedLine? = null,
  val small: Boolean = false,
  val startMs: Long = 0L,
  val endMs: Long = 0L,
  val dots: Boolean = false,
  val ownerMs: Long = startMs,
  val breakFrom: Long? = null,
  val breakTo: Long? = null,
)

private data class PillStop(val timeMs: Long, val top: Int)
private data class PillScript(val generation: Long, val rows: List<PillRow>, val stops: List<PillStop>, val slots: Int) {
  fun topAt(position: Long): Int {
    var low = 0
    var high = stops.lastIndex
    while (low < high) {
      val middle = (low + high + 1) ushr 1
      if (stops[middle].timeMs <= position) low = middle else high = middle - 1
    }
    return stops[low].top
  }
}

private fun pillScript(model: FloatingLyricsModel, mode: LyricsTranslationDisplayMode): PillScript {
  val rows = mutableListOf<PillRow>()
  val stops = mutableListOf<PillStop>()
  var slots = 2
  for ((index, line) in model.lines.withIndex()) {
    if (line.line.isBlank()) continue
    val start = line.time.toLong()
    val next = model.lines.getOrNull(index + 1)?.time?.toLong()
    val end = line.endTime?.toLong()?.takeIf { it > start }
      ?: line.words?.lastOrNull()?.time?.toLong()?.takeIf { it >= start }?.let { it + 600L }
      ?: next?.takeIf { it > start }
      ?: model.durationMs.takeIf { it > start }
      ?: (line.words?.lastOrNull()?.time?.toLong() ?: start) + 600L
    if (stops.isEmpty() && start >= 4_000) {
      rows += PillRow("", startMs = 0, endMs = start, dots = true)
      stops += PillStop(0L, 0)
    }
    val top = rows.size
    val translated = mode == LyricsTranslationDisplayMode.Replace && !line.translation.isNullOrBlank()
    rows += PillRow(if (translated) line.translation.orEmpty() else line.line, line.takeUnless { translated }, startMs = start, endMs = end)
    line.romanization?.takeIf { it.isNotBlank() && it != line.line }?.let {
      rows += PillRow(it, small = true, startMs = start, endMs = end)
      slots = 3
    }
    line.background?.takeIf { it.line.isNotBlank() }?.let {
      rows += PillRow(
        it.line, it, small = true, startMs = it.time.toLong(),
        endMs = it.endTime?.toLong()?.takeIf { stop -> stop > it.time } ?: end, ownerMs = start,
      )
    }
    if (mode == LyricsTranslationDisplayMode.DualLine) {
      line.translation
        ?.takeIf { it.isNotBlank() && !it.trim().equals(line.line.trim(), ignoreCase = true) }
        ?.let {
          rows += PillRow(it, small = true, startMs = start, endMs = end)
          slots = 3
        }
    }
    val time = (start - 300L).coerceAtLeast(0)
    while (stops.size > 1 && stops.last().timeMs >= time) stops.removeAt(stops.lastIndex)
    if (stops.isEmpty()) stops += PillStop(0, top)
    if (stops.last().timeMs >= time) stops[stops.lastIndex] = PillStop(stops.last().timeMs, top)
    else stops += PillStop(time, top)
    if (next != null && next - end >= 4_000) {
      val last = rows.lastIndex
      rows[last] = rows[last].copy(breakFrom = end, breakTo = next)
      stops += PillStop(end, last)
    }
  }
  val info = rows.size
  rows += PillRow(model.title)
  rows += PillRow(model.errorMessage ?: model.artist, small = true)
  if (stops.isEmpty()) stops += PillStop(0, info)
  else {
    val end = rows.take(info).maxOfOrNull { it.endMs } ?: 0L
    stops += PillStop(maxOf(end + 1_500, stops.last().timeMs + 1), info)
  }
  return PillScript(model.generation, rows, stops, slots)
}

private data class PreparedRow(val row: PillRow, val layout: TextLayoutResult, val spans: List<LyricWordSpan>)
private data class PreparedScript(val script: PillScript, val rows: List<PreparedRow>)

@Composable
private fun PillLyricRows(
  state: FloatingPillState,
  script: PillScript,
  metrics: FloatingPillMetrics,
  bubble: Color,
  ink: Color,
  host: FloatingPillHost,
  reduced: Boolean,
) {
  val density = LocalDensity.current
  val direction = LocalLayoutDirection.current
  val resolver = LocalFontFamilyResolver.current
  val font = LocalAppFontFamily.current
  val prepared by produceState<PreparedScript?>(null, script, density, font, direction) {
    value = withContext(Dispatchers.Default) {
      val measurer = TextMeasurer(resolver, density, direction, cacheSize = 0)
      val rows = script.rows.map { row ->
        val layout = measurer.measure(
          if (row.dots) "..." else row.text,
          TextStyle(
            fontFamily = font,
            fontWeight = if (row.small) FontWeight.SemiBold else FontWeight.Bold,
            fontSize = if (row.small) 13.sp else 15.sp,
            lineHeight = if (row.small) 17.sp else 19.sp,
            letterSpacing = (-0.03).em,
          ),
          overflow = TextOverflow.Clip, softWrap = false, maxLines = 1,
        )
        val spans = lyricWordSpans(row.text, row.line?.words.orEmpty(), row.endMs)
        PreparedRow(row, layout, spans)
      }
      PreparedScript(script, rows)
    }
  }
  Crossfade(
    targetState = prepared,
    animationSpec = if (reduced) snap() else tween(320),
    modifier = Modifier.fillMaxSize(),
    label = "floatingLyricsSource",
  ) { ready ->
    if (ready != null) PreparedPillRows(ready, state, metrics, bubble, ink, host, reduced, direction == LayoutDirection.Rtl)
  }
}

@Composable
private fun PreparedPillRows(
  prepared: PreparedScript,
  state: FloatingPillState,
  metrics: FloatingPillMetrics,
  bubble: Color,
  ink: Color,
  host: FloatingPillHost,
  reduced: Boolean,
  appRtl: Boolean,
) {
  val script = prepared.script
  val slots = script.slots.coerceAtMost(metrics.maxSlots)
  val top by remember(script, state) { derivedStateOf { script.topAt(state.model.positionMs + state.model.offsetMs) } }
  val lead = remember(script) { Animatable(top.toFloat()) }
  val trail = remember(script) { Animatable(top.toFloat()) }
  LaunchedEffect(top, script, reduced) {
    val target = top.toFloat()
    if (abs(target - lead.value) > 2.5f) {
      val near = target - kotlin.math.sign(target - lead.value) * 1.5f
      lead.snapTo(near)
      trail.snapTo(near)
    }
    coroutineScope {
      launch { lead.animateTo(target, if (reduced) snap() else spring(dampingRatio = 0.82f, stiffness = 260f)) }
      launch { trail.animateTo(target, if (reduced) snap() else spring(dampingRatio = 0.86f, stiffness = 150f)) }
    }
  }
  Box(
    Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).clipToBounds().drawWithContent {
      drawContent()
      val inset = 14.dp.toPx()
      drawRect(Brush.horizontalGradient(listOf(bubble, bubble.copy(alpha = 0f)), 0f, inset), size = Size(inset, size.height))
      drawRect(
        Brush.horizontalGradient(listOf(bubble.copy(alpha = 0f), bubble), size.width - inset, size.width),
        Offset(size.width - inset, 0f), Size(inset, size.height),
      )
    },
  ) {
    val first = (top - 1).coerceAtLeast(0)
    val last = (top + slots).coerceAtMost(prepared.rows.lastIndex)
    for (index in first..last) key(script, index) {
      val row = prepared.rows[index]
      val weight = ((index - top + 1) / 2f).coerceIn(0f, 1f)
      PillLyricRow(
        row, state, slots, metrics, ink, appRtl,
        live = !row.row.dots && row.row.ownerMs == script.rows[top].ownerMs,
        position = { index - lead.value - (trail.value - lead.value) * weight },
        waiting = index > top && row.row.ownerMs != script.rows[top].ownerMs,
        host = host, reduced = reduced, generation = script.generation,
      )
    }
  }
}

@Composable
private fun PillLyricRow(
  prepared: PreparedRow,
  state: FloatingPillState,
  slots: Int,
  metrics: FloatingPillMetrics,
  ink: Color,
  appRtl: Boolean,
  live: Boolean,
  position: () -> Float,
  waiting: Boolean,
  host: FloatingPillHost,
  reduced: Boolean,
  generation: Long,
) {
  val row = prepared.row
  val layout = prepared.layout
  val paused by remember(state) { derivedStateOf { state.model.paused } }
  val seekTick by remember(state) { derivedStateOf { state.model.seekTick } }
  val offsetMs by remember(state) { derivedStateOf { state.model.offsetMs } }
  val pausedPosition by remember(state) { derivedStateOf { state.model.positionMs.takeIf { state.model.paused } } }
  val canSeek by remember(state) { derivedStateOf { state.model.canSeek } }
  val density = LocalDensity.current
  val slotWidth = with(density) { (metrics.bubbleWidth - 28.dp).toPx() }
  val overflow = (layout.size.width - slotWidth).coerceAtLeast(0f)
  val drawnWidth = if (row.dots || row.breakFrom != null) maxOf(with(density) { 28.dp.roundToPx() }, layout.size.width) else layout.size.width
  val rtl = row.text.hasRtlDirection()
  val endAligned = row.line?.alignEnd == true
  val slide = remember(prepared) { mutableFloatStateOf(0f) }
  val head = remember(prepared) { mutableFloatStateOf(0f) }
  val showsDots by remember(prepared, state) {
    derivedStateOf {
      row.dots || row.breakFrom?.let { state.model.positionMs + state.model.offsetMs >= it } == true
    }
  }
  val swept = live && prepared.spans.isNotEmpty() && !showsDots
  val dots = animateFloatAsState(if (showsDots) 1f else 0f, if (reduced) snap() else tween(320), label = "floatingBreakDots")
  val scale = animateFloatAsState(
    if (waiting && !row.small) 0.84f else 1f,
    if (reduced) snap() else spring(dampingRatio = 0.8f, stiffness = 280f), label = "floatingNextLine",
  )
  val wait = animateFloatAsState(if (waiting) 1f else 0f, if (reduced) snap() else tween(300), label = "floatingLineInk")
  val textStart = {
    val available = slotWidth - drawnWidth
    if (overflow > 0) {
      if (rtl) available + slide.floatValue else -slide.floatValue
    } else if (endAligned != appRtl) available else 0f
  }
  LaunchedEffect(prepared, paused, pausedPosition, seekTick, offsetMs, swept, reduced) {
    var previousFrame = 0L
    do {
      val moving = withFrameNanos { now ->
        val model = state.model
        val elapsed = if (!model.paused && model.canControl && model.positionUpdatedAt > 0) {
          ((SystemClock.uptimeMillis() - model.positionUpdatedAt).coerceIn(0L, 250L) * model.speed).toLong()
        } else 0L
        val time = model.positionMs + elapsed + model.offsetMs
        head.floatValue = if (swept) lyricCharacterPosition(row.text.length, prepared.spans, time) else 0f
        val dt = if (previousFrame == 0L) 0.016f else ((now - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.064f)
        previousFrame = now
        val x = lyricSweepX(layout, head.floatValue, rtl)
        val read = if (rtl) layout.size.width - x else x
        val target = if (swept) (read - slotWidth * 0.62f).coerceIn(0f, overflow) else 0f
        slide.floatValue = if (reduced) target else slide.floatValue + (target - slide.floatValue) * (1f - exp(-dt / 0.28f))
        !reduced && (abs(target - slide.floatValue) > 0.5f || (swept && !model.paused && time < row.endMs))
      }
    } while (moving)
  }
  val seekLabel = stringResource(R.string.pref_player_seeking_title)
  Box(
    Modifier.graphicsLayer {
      val pos = position()
      translationX = 14.dp.toPx() + textStart()
      val pitch = metrics.rowPitch.toPx()
      val top = (metrics.baseHeight.toPx() - pitch * 2) / 2
      translationY = top + (pos + 0.5f) * pitch - layout.size.height / 2
      alpha = when {
        pos < 0f -> (1f + pos / 0.7f).coerceIn(0f, 1f)
        pos > slots - 1 -> (1f - (pos - slots + 1) / 0.7f).coerceIn(0f, 1f)
        else -> 1f
      }
      scaleX = scale.value
      scaleY = scale.value
      transformOrigin = TransformOrigin(if (rtl || (endAligned != appRtl)) 1f else 0f, 0.5f)
      compositingStrategy = CompositingStrategy.ModulateAlpha
    }
      .layout { measurable, constraints ->
        val child = measurable.measure(Constraints.fixed(drawnWidth.coerceAtLeast(1), layout.size.height.coerceAtLeast(1)))
        layout(minOf(child.width, constraints.maxWidth), minOf(child.height, constraints.maxHeight)) { child.place(0, 0) }
      }
      .semantics {
        contentDescription = row.text
        if (row.line != null && canSeek && !showsDots) onClick(seekLabel) {
          host.seekTo(row.line.time.toLong() - state.model.offsetMs, generation)
          true
        }
      }
      .drawWithContent {
        if (dots.value > 0f) {
          val time = state.model.positionMs + state.model.offsetMs
          val start = row.breakFrom ?: row.startMs
          val end = row.breakTo ?: row.endMs
          val amount = (((time - start).toFloat() / (end - start).coerceAtLeast(1)) * 3 + 1).coerceIn(1f, 3f)
          val radius = 3.dp.toPx()
          val x = (if (endAligned != appRtl) slotWidth - 28.dp.toPx() else 0f) - textStart()
          for (index in 0..2) drawCircle(
            lerp(ink.copy(alpha = 0.42f), ink, (amount - index).coerceIn(0f, 1f)),
            radius, Offset(x + radius + index * 11.dp.toPx(), size.height / 2), alpha = dots.value,
          )
        }
        if (row.dots || dots.value >= 0.999f) return@drawWithContent
        if (swept) {
          drawLyricFill(layout, head.floatValue, rtl, ink, ink.copy(alpha = 0.42f))
        } else {
          val active = ink.copy(alpha = if (row.small) 0.58f else 1f)
          drawText(layout, color = lerp(active, ink.copy(alpha = 0.42f), wait.value), alpha = 1f - dots.value)
        }
      },
  )
}
