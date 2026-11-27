package app.gyrolet.mpvrx.ui.browser

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal data class NavigationJellyFrame(
  val position: Float,
  val pillScaleX: Float = 1f,
  val pillScaleY: Float = 1f,
  val contentScale: Float = 1f,
  val panelOffset: Float = 0f,
  val trackScale: Float = 1f,
  val trackScaleX: Float = 1f,
  val trackOffsetY: Float = 0f,
  val originX: Float = 0f,
  val glowY: Float = 0f,
  val glowOpacity: Float = 0f,
)

@Stable
internal class NavigationJellyMotion(
  initialIndex: Int,
  private val tabCount: Int,
) {
  private val position = NavigationJellySpring(initialIndex.coerceIn(0, tabCount - 1).toDouble(), 1000.0, 1.0)
  private val velocity = NavigationJellySpring(0.0, 300.0, 0.5)
  private val press = NavigationJellySpring(0.0, 1000.0, 1.0)
  private val scaleX = NavigationJellySpring(1.0, 250.0, 0.6)
  private val scaleY = NavigationJellySpring(1.0, 250.0, 0.7)
  private val panel = NavigationJellySpring(0.0, 300.0, 1.0)
  private val distortionDamping = 18.0 / (2 * sqrt(240.0 * 0.9))
  private val trackY = NavigationJellySpring(0.0, 240.0 / 0.9, distortionDamping)
  private val trackX = NavigationJellySpring(1.0, 240.0 / 0.9, distortionDamping)
  private val trackPress = NavigationJellySpring(1.0, 240.0 / 0.9, distortionDamping)
  private val glow = NavigationJellySpring(0.0, 240.0 / 0.9, distortionDamping)
  private var target = position.value
  private var pressTarget = 0.0
  private var shapeTarget = 1.0
  private var releasePending = false
  private var downX = 0.0
  private var downY = 0.0
  private var dragStartTarget = target
  private var dragStartPanel = 0.0
  private var dragStartY = 0.0
  private var movedDistance = 0.0
  private var originX = 0.0
  private var width = 0.0
  private var height = 64.0
  private val maxIndex get() = tabCount - 1
  private val tabWidth get() = ((width - 8) / tabCount).coerceAtLeast(0.0)

  var dragging = false
    private set
  var running by mutableStateOf(false)
    private set
  var frame by mutableStateOf(NavigationJellyFrame(position.value.toFloat()))
    private set

  fun resize(
    width: Float,
    height: Float,
  ) {
    this.width = width.toDouble()
    this.height = height.toDouble()
    if (!dragging) originX = this.width / 2
    publish()
  }

  fun snapTo(index: Int) {
    target = index.coerceIn(0, maxIndex).toDouble()
    position.snapTo(target)
    listOf(velocity, press, panel, trackY, glow).forEach { it.snapTo(0.0) }
    listOf(scaleX, scaleY, trackX, trackPress).forEach { it.snapTo(1.0) }
    dragging = false
    releasePending = false
    pressTarget = 0.0
    shapeTarget = 1.0
    running = false
    publish()
  }

  fun select(index: Int) {
    dragging = false
    target = index.coerceIn(0, maxIndex).toDouble()
    releasePending = true
    pressTarget = 0.0
    shapeTarget = 1.0
    running = true
  }

  fun begin(
    positionX: Float,
    positionY: Float,
  ) {
    downX = positionX.toDouble()
    downY = positionY.toDouble().coerceIn(0.0, height)
    originX = downX.coerceIn(0.0, width)
    dragStartY = trackY.value
    movedDistance = 0.0
    if (tabWidth > 0) target = indexAt(downX).toDouble()
    dragStartTarget = target
    dragStartPanel = panel.value
    dragging = true
    releasePending = false
    pressTarget = 1.0
    shapeTarget = 1.3
    panel.velocity = 0.0
    running = true
  }

  fun drag(
    distanceX: Float,
    distanceY: Float,
  ) {
    if (!dragging || tabWidth <= 0) return
    val deltaX = distanceX.toDouble()
    val deltaY = distanceY.toDouble()
    target = (dragStartTarget + deltaX / tabWidth).coerceIn(0.0, maxIndex.toDouble())
    panel.snapTo(dragStartPanel + deltaX)
    trackY.snapTo(dragStartY + navigationRubberBand(deltaY, height) * 0.25)
    trackX.snapTo(1 - (abs(deltaY) / 700).coerceAtMost(1.0) * 0.08)
    originX = (downX + deltaX).coerceIn(0.0, width)
    movedDistance = max(movedDistance, max(abs(deltaX), abs(deltaY)))
    publish()
  }

  fun finish(): Int {
    val index =
      if (movedDistance < 4 && tabWidth > 0) {
        indexAt(downX)
      } else {
        floor(target + 0.5).toInt().coerceIn(0, maxIndex)
      }
    dragging = false
    panel.velocity = 0.0
    target = index.toDouble()
    releasePending = true
    running = true
    return index
  }

  fun advance(seconds: Double) {
    val delta = seconds.coerceIn(0.0, 0.064)
    position.advance(target, delta)
    velocity.advance(if (dragging && maxIndex > 0) position.velocity / maxIndex else 0.0, delta)
    if (!dragging) panel.advance(0.0, delta)
    if (releasePending && abs(position.value - target) < max(1, maxIndex) * 0.025) {
      releasePending = false
      pressTarget = 0.0
      shapeTarget = 1.0
    }
    press.advance(pressTarget, delta)
    scaleX.advance(shapeTarget, delta)
    scaleY.advance(shapeTarget, delta)
    trackPress.advance(if (dragging) 1.025 else 1.0, delta)
    glow.advance(if (dragging) 1.0 else 0.0, delta)
    if (!dragging) {
      trackY.advance(0.0, delta)
      trackX.advance(1.0, delta)
      if (trackX.isAtRest(1.0)) originX = width / 2
    }
    publish()
    running = dragging ||
      releasePending ||
      !position.isAtRest(target) ||
      !velocity.isAtRest(0.0) ||
      !press.isAtRest(0.0) ||
      !scaleX.isAtRest(1.0) ||
      !scaleY.isAtRest(1.0) ||
      !panel.isAtRest(0.0) ||
      !trackY.isAtRest(0.0) ||
      !trackX.isAtRest(1.0) ||
      !trackPress.isAtRest(1.0) ||
      !glow.isAtRest(0.0)
  }

  private fun indexAt(positionX: Double): Int = floor((positionX - 4) / tabWidth).toInt().coerceIn(0, maxIndex)

  private fun publish() {
    val speed = velocity.value / 10
    frame =
      NavigationJellyFrame(
        position = position.value.toFloat(),
        pillScaleX = (scaleX.value / (1 - (speed * 0.75).coerceIn(-0.2, 0.2))).toFloat(),
        pillScaleY = (scaleY.value * (1 - (speed * 0.25).coerceIn(-0.2, 0.2))).toFloat(),
        contentScale = (1 + 0.2 * press.value).toFloat(),
        panelOffset = navigationPanelOffset(panel.value, width),
        trackScale = trackPress.value.toFloat(),
        trackScaleX = trackX.value.toFloat(),
        trackOffsetY = trackY.value.toFloat(),
        originX = originX.toFloat(),
        glowY = downY.toFloat(),
        glowOpacity = glow.value.toFloat().coerceIn(0f, 1f),
      )
  }
}

private class NavigationJellySpring(
  var value: Double,
  private val stiffness: Double,
  private val dampingRatio: Double,
) {
  var velocity = 0.0

  fun isAtRest(target: Double): Boolean = abs(value - target) < 0.0001 && abs(velocity) < 0.0001

  fun snapTo(target: Double) {
    value = target
    velocity = 0.0
  }

  fun advance(
    target: Double,
    seconds: Double,
  ) {
    if (isAtRest(target)) {
      snapTo(target)
      return
    }
    val displacement = value - target
    val frequency = sqrt(stiffness)
    if (dampingRatio == 1.0) {
      val decay = exp(-frequency * seconds)
      val coefficient = velocity + frequency * displacement
      value = target + (displacement + coefficient * seconds) * decay
      velocity = (velocity - frequency * coefficient * seconds) * decay
    } else {
      val damping = dampingRatio * frequency
      val damped = frequency * sqrt(1 - dampingRatio * dampingRatio)
      val decay = exp(-damping * seconds)
      val cosine = cos(damped * seconds)
      val sine = sin(damped * seconds)
      val positionCoefficient = (velocity + damping * displacement) / damped
      val velocityCoefficient = (damping * velocity + stiffness * displacement) / damped
      value = target + decay * (displacement * cosine + positionCoefficient * sine)
      velocity = decay * (velocity * cosine - velocityCoefficient * sine)
    }
  }
}

private fun navigationRubberBand(
  distance: Double,
  dimension: Double,
): Double {
  if (distance == 0.0 || dimension <= 0.0) return 0.0
  val damped = (1 - 1 / (abs(distance) * 0.14 / dimension + 1)) * dimension
  return if (distance < 0) -damped else damped
}

private fun navigationPanelOffset(
  rawOffset: Double,
  width: Double,
): Float {
  if (width <= 0 || rawOffset == 0.0) return 0f
  val fraction = (rawOffset / width).coerceIn(-1.0, 1.0)
  val distance = abs(fraction)
  var low = 0.0
  var high = 1.0
  var parameter = distance
  repeat(10) {
    val bezierX = parameter * parameter * (3 * (1 - parameter) * 0.58 + parameter)
    if (bezierX < distance) low = parameter else high = parameter
    parameter = (low + high) / 2
  }
  val eased = parameter * parameter * (3 * (1 - parameter) + parameter)
  return ((if (fraction < 0) -4 else 4) * eased).toFloat()
}

internal fun DrawScope.navigationJellyPath(
  frame: NavigationJellyFrame,
  count: Int,
): Path {
  val inset = 4.dp.toPx()
  val tabWidth = ((size.width - 2 * inset) / count).coerceAtLeast(0f)
  val itemHeight = (size.height - 2 * inset).coerceAtLeast(0f)
  val centerX = inset + (frame.position + 0.5f) * tabWidth
  val halfWidth = tabWidth * frame.pillScaleX / 2
  val halfHeight = itemHeight * frame.pillScaleY / 2
  val radius = min(tabWidth, itemHeight) / 2
  return Path().apply {
    addRoundRect(
      RoundRect(
        centerX - halfWidth,
        size.height / 2 - halfHeight,
        centerX + halfWidth,
        size.height / 2 + halfHeight,
        CornerRadius(radius * frame.pillScaleX, radius * frame.pillScaleY),
      ),
    )
  }
}

internal fun DrawScope.drawNavigationJellyGlow(
  frame: NavigationJellyFrame,
  color: Color,
) {
  if (frame.glowOpacity <= 0f) return
  val alpha = 0.15f * frame.glowOpacity * color.alpha
  drawRect(
    brush =
      Brush.radialGradient(
        0f to color.copy(alpha = alpha),
        0.45f to color.copy(alpha = alpha * 0.43f),
        1f to Color.Transparent,
        center = Offset(frame.originX.dp.toPx(), frame.glowY.dp.toPx()),
        radius = 300.dp.toPx(),
      ),
    topLeft = Offset(-48.dp.toPx(), -16.dp.toPx()),
    size = Size(size.width + 96.dp.toPx(), size.height + 32.dp.toPx()),
  )
}
