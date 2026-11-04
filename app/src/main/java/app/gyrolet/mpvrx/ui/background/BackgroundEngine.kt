package app.gyrolet.mpvrx.ui.background

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.gyrolet.mpvrx.ui.theme.AppMotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/* ------------------------------------------------------------------------------------------
 * Shared plumbing for every backdrop.
 *
 * The backdrops here are *analytic*: each one is a pure function of time. A particle's position
 * is computed from its index and the clock rather than integrated frame by frame, so there is no
 * per-particle state to allocate, nothing to reset when the page is recreated, and a background
 * can be paused simply by not advancing the clock.
 * ------------------------------------------------------------------------------------------ */

/** Colours a backdrop may use, taken from the live theme so every backdrop follows it. */
internal class BackdropPalette(
  val primary: Color,
  val secondary: Color,
  val tertiary: Color,
  /** Readable tone against the page: near-white on dark themes, near-black on light ones. */
  val ink: Color,
  val isDark: Boolean,
) {
  fun accent(i: Int): Color =
    when (((i % 3) + 3) % 3) {
      0 -> primary
      1 -> secondary
      else -> tertiary
    }
}

@Composable
internal fun rememberBackdropPalette(): BackdropPalette {
  val cs = MaterialTheme.colorScheme
  val dark = cs.background.luminance() < 0.5f
  return remember(cs.primary, cs.secondary, cs.tertiary, cs.onBackground, dark) {
    BackdropPalette(cs.primary, cs.secondary, cs.tertiary, cs.onBackground, dark)
  }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/**
 * Seconds since this backdrop appeared. Read only from drawing, and advance with the display's
 * frame clock while limiting background redraws to approximately 30 Hz to save GPU and battery.
 * Hidden or paused surfaces do not request frames. Reset the frame anchor on resume while keeping
 * the artwork's current position.
 */
@Composable
internal fun rememberBackdropClock(running: Boolean = true): State<Float> {
  val lifecycle = LocalLifecycleOwner.current
  val clock = remember { mutableFloatStateOf(0f) }
  LaunchedEffect(lifecycle, running) {
    if (!running) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      var last = -1L
      var elapsed = 0f
      while (true) {
        androidx.compose.runtime.withFrameNanos { now ->
          if (last >= 0L) {
            // Coalesce display refreshes instead of invalidating a full-screen canvas at 120 Hz.
            // Keep the real elapsed time so motion stays the same on 60/90/120 Hz panels.
            elapsed += ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
            if (elapsed >= 1f / 30f) {
              clock.floatValue += elapsed
              elapsed = 0f
            }
          }
          last = now
        }
      }
    }
  }
  return clock
}

/**
 * Device tilt as a value in -1..1 on both axes (x: right-down positive, y: top-toward-you
 * positive), eased so a shaky hand does not make the artwork shiver. Stays at zero when
 * [enabled] is false or the device has no gravity/accelerometer.
 */
@Composable
internal fun rememberTilt(enabled: Boolean): State<Offset> {
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current
  val tilt = remember { mutableStateOf(Offset.Zero) }
  val view = LocalView.current
  DisposableEffect(enabled, lifecycle, context, view) {
    if (!enabled) {
      tilt.value = Offset.Zero
      return@DisposableEffect onDispose { }
    }
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    val sensor =
      manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    var ex = 0f
    var ey = 0f
    val listener =
      object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
          // 9.81 m/s^2 along an axis means the device is fully on its side; ~5 is a hearty tilt
          // Sensors use the device's natural orientation, whereas the canvas uses display axes.
          val rotation = view.display?.rotation
            ?: android.view.Surface.ROTATION_0
          val (gx, gy) = when (rotation) {
            android.view.Surface.ROTATION_90 -> -event.values[1] to event.values[0]
            android.view.Surface.ROTATION_180 -> -event.values[0] to -event.values[1]
            android.view.Surface.ROTATION_270 -> event.values[1] to -event.values[0]
            else -> event.values[0] to event.values[1]
          }
          val tx = (-gx / TILT_FULL_SCALE).coerceIn(-1f, 1f)
          val ty = ((gy - REST_PITCH) / TILT_FULL_SCALE).coerceIn(-1f, 1f)
          ex += (tx - ex) * EASE
          ey += (ty - ey) * EASE
          // Keep tiny sensor noise out of the draw loop.
          val cur = tilt.value
          if (abs(ex - cur.x) > 0.005f || abs(ey - cur.y) > 0.005f) tilt.value = Offset(ex, ey)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
      }

    val observer =
      androidx.lifecycle.LifecycleEventObserver { _, event ->
        when (event) {
          Lifecycle.Event.ON_RESUME ->
            if (sensor != null) manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
          Lifecycle.Event.ON_PAUSE -> manager?.unregisterListener(listener)
          else -> Unit
        }
      }
    lifecycle.lifecycle.addObserver(observer)
    // The picker is usually opened while already resumed, without another ON_RESUME event.
    if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && sensor != null) {
      manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    onDispose {
      lifecycle.lifecycle.removeObserver(observer)
      manager?.unregisterListener(listener)
    }
  }
  return tilt
}

private const val TILT_FULL_SCALE = 5f
private const val REST_PITCH = 6f // phone held upright reads ~6 m/s^2 on the y axis
private const val EASE = 0.12f

/**
 * Canvas that redraws from the clock and tilt without recomposing: both are read inside the draw
 * block, so a tick invalidates drawing only.
 */
@Composable
internal fun BackdropCanvas(
  type: BackgroundType,
  parallax: Boolean,
  modifier: Modifier = Modifier,
  running: Boolean = true,
) {
  val reduceMotion = AppMotion.shouldReduceMotion()
  val clock = rememberBackdropClock(running && !reduceMotion)
  val targetTilt = rememberTilt(parallax && running && !reduceMotion)
  val palette = rememberBackdropPalette()
  val drawing = remember { BackdropDrawingState() }
  Canvas(modifier = modifier.fillMaxSize()) {
    if (size.minDimension > 0f) {
      drawBackdrop(type, clock.value, targetTilt.value, palette, drawing)
    }
  }
}

/** Scratch geometry belongs to one canvas, never to a global renderer or a frame. */
internal class BackdropDrawingState {
  val path = Path()
  val starX = FloatArray(CONSTELLATION_STARS)
  val starY = FloatArray(CONSTELLATION_STARS)
  val starVisibility = FloatArray(CONSTELLATION_STARS)
}

internal const val CONSTELLATION_STARS = 34

/** Shared by the live background and its static picker illustration. */
internal fun DrawScope.drawBackdrop(
  type: BackgroundType,
  t: Float,
  tilt: Offset,
  p: BackdropPalette,
  drawing: BackdropDrawingState,
) {
  when (type) {
    BackgroundType.NONE, BackgroundType.RANDOM -> Unit
    BackgroundType.CIRCLES -> drawCircles(t, tilt, p)
    BackgroundType.RINGS -> drawRings(t, tilt, p)
    BackgroundType.SPACE -> drawSpace(t, tilt, p)
    BackgroundType.SHAPES -> drawShapes(t, tilt, p)
    BackgroundType.SNOW -> drawSnow(t, tilt, p)
    BackgroundType.GRID -> drawGrid(t, tilt, p)
    BackgroundType.PARTICLES -> drawParticles(t, tilt, p)
    BackgroundType.LAVA -> drawLava(t, tilt, p)
    BackgroundType.AURORA -> drawAurora(t, tilt, p, drawing.path)
    BackgroundType.FIREFLIES -> drawFireflies(t, tilt, p)
    BackgroundType.RAIN -> drawRain(t, tilt, p)
    BackgroundType.BOKEH -> drawBokeh(t, tilt, p)
    BackgroundType.SAKURA -> drawSakura(t, tilt, p)
    BackgroundType.CONSTELLATION -> drawConstellation(t, tilt, p, drawing)
    BackgroundType.BUBBLES -> drawBubbles(t, tilt, p)
    BackgroundType.HALLOWEEN -> drawHalloween(t, tilt, p, drawing.path)
    BackgroundType.FIREWORKS -> drawFireworks(t, tilt, p)
    BackgroundType.WINTER -> drawWinter(t, tilt, p, drawing.path)
    BackgroundType.DIWALI -> drawDiwali(t, tilt, p, drawing.path)
    BackgroundType.HOLI -> drawHoli(t, tilt, p)
  }
}

/* ------------------------------------------------------------------ helpers ---------------- */

internal const val TAU = (2.0 * PI).toFloat()

/** Screen-area-aware particle budget shared by all effects (including their compact previews). */
internal fun DrawScope.backdropCount(base: Int, minimum: Int = 4, maximum: Int = base * 2): Int {
  val referenceArea = 360.dp.toPx() * 720.dp.toPx()
  val fraction = sqrt((size.width * size.height / referenceArea).coerceAtLeast(0f))
    .coerceIn(0.55f, 1.35f)
  return (base * fraction).toInt().coerceIn(minimum, maximum.coerceAtLeast(minimum))
}

/** Fraction part, always in 0..1 (also for negatives). */
internal fun fract(x: Float): Float = x - floor(x)

/** Cheap deterministic noise in 0..1 for (index, salt). Same inputs, same output, every launch. */
internal fun hash01(index: Int, salt: Int = 0): Float {
  var h = index * 374761393 + salt * 668265263 + 0x2545F491
  h = (h xor (h ushr 13)) * 1274126177
  h = h xor (h ushr 16)
  return (h and 0xFFFFFF) / 16777215f
}

/** Smooth 0..1..0 pulse of period 1. */
internal fun pulse(x: Float): Float = 0.5f - 0.5f * kotlin.math.cos(x * TAU)

/** Sine mapped to 0..1. */
internal fun wave01(x: Float): Float = 0.5f + 0.5f * sin(x)

internal fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
  val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
  return t * t * (3f - 2f * t)
}
