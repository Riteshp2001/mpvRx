package app.gyrolet.mpvrx.ui.utils

import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.findViewTreeLifecycleOwner
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor
import com.wonddak.VibrationPreset
import com.wonddak.VibratorManager
import com.wonddak.vibrate
import org.koin.compose.koinInject
import org.koin.core.context.GlobalContext
import kotlin.math.abs

@Composable
internal fun ProvideAppHaptics(content: @Composable () -> Unit) {
  val preferences = koinInject<GesturePreferences>()
  val enabled by preferences.hapticFeedbackEnabled.collectAsState()
  val feedback = rememberKmpHapticFeedback()
  LaunchedEffect(enabled) {
    if (!enabled) AppVibration.cancel()
  }
  LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { AppVibration.cancel() }
  DisposableEffect(Unit) {
    onDispose { AppVibration.cancel() }
  }
  CompositionLocalProvider(LocalHapticFeedback provides feedback, content = content)
}

@Composable
private fun rememberKmpHapticFeedback(): HapticFeedback {
  val view = LocalView.current
  val preferences = koinInject<GesturePreferences>()
  return remember(view, preferences) {
    object : HapticFeedback {
      override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        AppVibration.perform(view, preferences, hapticFeedbackType)
      }
    }
  }
}

private object AppVibration {
  private const val SYSTEM_HAPTIC_FEEDBACK_INTENSITY = "haptic_feedback_intensity"
  private var lastTickAt = Long.MIN_VALUE

  fun perform(
    view: View,
    preferences: GesturePreferences,
    type: HapticFeedbackType,
  ): Boolean =
    runCatching {
      if (DeviceFormFactor.isTelevision(view.context) ||
        !preferences.hapticFeedbackEnabled.get() ||
        !view.isHapticFeedbackEnabled
      ) {
        cancel()
        return@runCatching false
      }
      if (!view.isAttachedToWindow || !view.isShown) return@runCatching false
      val resumed =
        view
          .findViewTreeLifecycleOwner()
          ?.lifecycle
          ?.currentState
          ?.isAtLeast(Lifecycle.State.RESUMED)
          ?: view.hasWindowFocus()
      if (!resumed) return@runCatching false
      val resolver = view.context.contentResolver

      // Custom vibrations bypass USAGE_TOUCH, so the user's system haptics toggle is read directly.
      @Suppress("DEPRECATION")
      val systemEnabled = Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
      val systemIntensity = Settings.System.getInt(resolver, SYSTEM_HAPTIC_FEEDBACK_INTENSITY, -1)
      if (!systemEnabled || systemIntensity == 0) {
        cancel()
        return@runCatching false
      }
      if (!VibratorManager.isSupported()) return@runCatching false

      val isTick =
        type == HapticFeedbackType.SegmentTick ||
          type == HapticFeedbackType.SegmentFrequentTick ||
          type == HapticFeedbackType.TextHandleMove
      val now = SystemClock.uptimeMillis()
      if (isTick && lastTickAt != Long.MIN_VALUE && now - lastTickAt < 70L) return@runCatching false

      val preset =
        when (type) {
          HapticFeedbackType.Confirm, HapticFeedbackType.Reject -> VibrationPreset.DoubleClick
          HapticFeedbackType.ToggleOff,
          HapticFeedbackType.SegmentTick,
          HapticFeedbackType.SegmentFrequentTick,
          HapticFeedbackType.TextHandleMove,
          HapticFeedbackType.GestureEnd,
          -> VibrationPreset.Tick
          else -> VibrationPreset.Click
        }
      val strength =
        when (type) {
          HapticFeedbackType.LongPress, HapticFeedbackType.Reject -> 0.6f
          HapticFeedbackType.SegmentFrequentTick, HapticFeedbackType.TextHandleMove -> 0.25f
          HapticFeedbackType.SegmentTick, HapticFeedbackType.ToggleOff -> 0.35f
          else -> 0.45f
        }
      VibratorManager.vibrate(preset, strength)
      if (isTick) lastTickAt = now
      true
    }.getOrDefault(false)

  fun cancel() {
    runCatching { VibratorManager.stopVibrate() }
    lastTickAt = Long.MIN_VALUE
  }
}

@Stable
internal class AppHaptics(
  private val feedback: HapticFeedback,
) {
  fun selection(selected: Boolean) {
    feedback.performHapticFeedback(if (selected) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
  }

  fun pickup() {
    feedback.performHapticFeedback(HapticFeedbackType.LongPress)
  }

  fun confirm() {
    feedback.performHapticFeedback(HapticFeedbackType.Confirm)
  }

  fun tick() {
    feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
  }
}

@Composable
internal fun rememberAppHaptics(): AppHaptics {
  val feedback = rememberKmpHapticFeedback()
  return remember(feedback) { AppHaptics(feedback) }
}

internal fun View.performAppHapticFeedback(feedback: Int): Boolean {
  val preferences = runCatching { GlobalContext.get().get<GesturePreferences>() }.getOrNull() ?: return false
  val type =
    when (feedback) {
      HapticFeedbackConstants.LONG_PRESS -> HapticFeedbackType.LongPress
      HapticFeedbackConstants.CONFIRM -> HapticFeedbackType.Confirm
      HapticFeedbackConstants.REJECT -> HapticFeedbackType.Reject
      HapticFeedbackConstants.TOGGLE_ON -> HapticFeedbackType.ToggleOn
      HapticFeedbackConstants.TOGGLE_OFF -> HapticFeedbackType.ToggleOff
      else -> HapticFeedbackType.SegmentTick
    }
  return AppVibration.perform(this, preferences, type)
}

internal class AdjustmentHaptics(
  private val haptics: AppHaptics,
  private val markers: List<Float>,
  private val hysteresis: Float,
) {
  private var latchedMarker: Float? = null

  fun move(
    previous: Float,
    value: Float,
  ) {
    if (!previous.isFinite() || !value.isFinite() || previous == value) return
    if (latchedMarker?.let { abs(value - it) > hysteresis } == true) latchedMarker = null
    val crossed =
      markers
        .filter { marker ->
          (previous < marker && value >= marker) || (previous > marker && value <= marker)
        }.minByOrNull { abs(value - it) }
    if (crossed != null && crossed != latchedMarker) {
      latchedMarker = crossed
      haptics.tick()
    }
  }
}

@Composable
internal fun rememberAdjustmentHaptics(
  min: Float,
  max: Float,
  steps: Int = 0,
  landmarks: List<Float> = emptyList(),
): AdjustmentHaptics {
  val haptics = rememberAppHaptics()
  return remember(haptics, min, max, steps, landmarks) {
    val markers =
      buildList {
        add(min)
        add(max)
        if (min < 0f && max > 0f) add(0f)
        addAll(landmarks.filter { it in min..max })
        if (steps in 1..20) {
          repeat(steps) { index -> add(min + (max - min) * (index + 1) / (steps + 1)) }
        }
      }.distinct()
    AdjustmentHaptics(haptics, markers, (max - min) * 0.02f)
  }
}
