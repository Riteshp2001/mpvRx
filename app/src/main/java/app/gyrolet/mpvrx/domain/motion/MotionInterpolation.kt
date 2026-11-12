/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.motion

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.annotation.StringRes
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.utils.device.VulkanCapabilities
import java.util.Locale
import kotlin.math.roundToInt

/** How the motion interpolation target rate is chosen. */
enum class MotionTargetMode(
  @StringRes val summaryRes: Int,
) {
  /** Interpolate up to the display's highest refresh rate. */
  AUTO(R.string.motion_target_auto_summary),

  /** Interpolate up to one of [MotionInterpolation.FIXED_TARGET_FPS]. */
  FIXED(R.string.motion_target_fixed_summary),
}

data class MotionConfig(
  val enabled: Boolean,
  val mode: MotionTargetMode,
  val targetFps: Int,
)

/**
 * Whether motion interpolation can run, checked in this order. The hard requirements
 * ([blocksEnabling]) keep it switched off; the rest are checked against the running player.
 */
enum class MotionAvailability(
  @StringRes val messageRes: Int?,
  val blocksEnabling: Boolean,
) {
  READY(null, false),

  /** Only FongMi's mpv carries the GPU motion core. */
  UNSUPPORTED_BUILD(R.string.motion_status_unsupported_build, true),

  /** The motion core runs on mpvRx's Vulkan renderer, which needs Vulkan 1.3 (Android 13+). */
  UNSUPPORTED_DEVICE(R.string.motion_status_unsupported_device, true),

  /** GPU Next and Vulkan must both be selected in the Decoder settings. */
  NEEDS_RENDERER(R.string.motion_status_needs_renderer, true),

  /** The bundled mpvlib predates the motion core. */
  UNSUPPORTED_LIBMPV(R.string.motion_status_unsupported_libmpv, false),

  /** mpv.conf sets motion-interpolation itself. */
  CONFIG_OWNED(R.string.motion_status_config_owned, false),

  /** GPU Next + Vulkan is selected, but this player started on another renderer or fell back to OpenGL. */
  RENDERER_RESTART(R.string.motion_status_renderer_restart, false),

  /** mpv.conf sets video-sync to a mode that is not display-synced. */
  NEEDS_DISPLAY_SYNC(R.string.motion_status_needs_display_sync, false),

  /** mpv reports that this GPU lacks the compute features the motion core needs. */
  UNSUPPORTED_GPU(R.string.motion_status_unsupported_gpu, false),
}

/** `motion-interpolation-info/state`, as reported by FongMi's vo_gpu_next. */
enum class MotionState(
  val key: String,
  @StringRes val labelRes: Int,
) {
  DISABLED("disabled", R.string.motion_state_disabled),
  READY("ready", R.string.motion_state_ready),
  ACTIVE("active", R.string.motion_state_active),
  SOURCE("source", R.string.motion_state_source),
  IDLE("idle", R.string.motion_state_idle),
  PAUSED("paused", R.string.motion_state_paused),
  UNSUPPORTED("unsupported", R.string.motion_state_unsupported),
  ERROR("error", R.string.motion_state_error),
  ;

  companion object {
    /** States in which the core generates nothing, worth naming instead of showing rates. */
    val NOT_GENERATING = setOf(SOURCE, IDLE, PAUSED, UNSUPPORTED, ERROR)

    fun fromKey(key: String?): MotionState? = entries.firstOrNull { it.key == key }
  }
}

/**
 * GPU motion interpolation from FongMi's mpv (`vo=gpu-next`, `--motion-interpolation=<fps>`).
 *
 * The motion core estimates motion between two real frames and synthesizes the frames in
 * between, up to the target rate. It replaces mpv's `interpolation` frame mixing, works with HW+
 * decoding and needs display-synced playback. It checks every source frame itself: SDR, HDR10 and
 * Dolby Vision video is interpolated whatever the HDR output setting, while formats it cannot
 * capture, such as HLG, play through unchanged.
 */
object MotionInterpolation {
  const val OPTION = "motion-interpolation"
  const val INFO_PROPERTY = "motion-interpolation-info"
  const val MIN_TARGET_FPS = 15
  const val MAX_TARGET_FPS = 240
  const val DEFAULT_TARGET_FPS = 60

  /** The rates a fixed target can use; each has its own Material symbol in the player sheet. */
  val FIXED_TARGET_FPS = listOf(60, 30, 24)

  /** Only the FongMi APK ships the motion core. */
  val isBuildSupported: Boolean
    get() = BuildConfig.MPV_SUPPORTS_MOTION_INTERPOLATION

  /** Whether mpvRx switched the motion core on in the current mpv core, as opposed to mpv.conf. */
  @Volatile var appApplied: Boolean = false

  @Volatile private var deviceSupported: Boolean? = null

  /** Vulkan support cannot change while the app runs, so it is checked once. */
  fun isDeviceSupported(context: Context): Boolean =
    deviceSupported ?: VulkanCapabilities.isDeviceSupported(context).also { deviceSupported = it }

  /** Like FongMi's own player, motion interpolation runs on gpu-next with Vulkan. */
  fun isRendererSelected(preferences: DecoderPreferences): Boolean =
    preferences.gpuNext.get() && preferences.useVulkan.get()

  /** The first unmet hard requirement, or [MotionAvailability.READY]. */
  fun barrier(
    context: Context,
    preferences: DecoderPreferences,
  ): MotionAvailability =
    when {
      !isBuildSupported -> MotionAvailability.UNSUPPORTED_BUILD
      !isDeviceSupported(context) -> MotionAvailability.UNSUPPORTED_DEVICE
      !isRendererSelected(preferences) -> MotionAvailability.NEEDS_RENDERER
      else -> MotionAvailability.READY
    }

  fun config(preferences: DecoderPreferences): MotionConfig =
    config(
      enabled = preferences.motionInterpolationEnabled.get(),
      mode = preferences.motionInterpolationMode.get(),
      targetFps = preferences.motionInterpolationTargetFps.get(),
    )

  /** Earlier versions stored any rate; one that [FIXED_TARGET_FPS] no longer offers reads as Auto. */
  fun config(
    enabled: Boolean,
    mode: MotionTargetMode,
    targetFps: Int,
  ): MotionConfig =
    if (targetFps in FIXED_TARGET_FPS) {
      MotionConfig(enabled, mode, targetFps)
    } else {
      MotionConfig(enabled, MotionTargetMode.AUTO, DEFAULT_TARGET_FPS)
    }

  private fun display(context: Context): Display? =
    context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

  /** The refresh rate the display is running at right now. */
  fun currentDisplayRefreshRate(context: Context): Float? =
    display(context)?.refreshRate?.takeIf { it.isFinite() && it > 0f }

  /** The highest refresh rate the display offers at its current resolution. */
  fun maxDisplayRefreshRate(context: Context): Float? {
    val display = display(context) ?: return null
    val current = display.mode
    return display.supportedModes
      .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
      .maxOfOrNull { it.refreshRate }
      ?.takeIf { it.isFinite() && it > 0f }
      ?: currentDisplayRefreshRate(context)
  }

  /** The value for `--motion-interpolation`. */
  fun mpvTargetFps(
    config: MotionConfig,
    context: Context,
  ): Int =
    when (config.mode) {
      MotionTargetMode.AUTO -> maxDisplayRefreshRate(context)?.roundToInt() ?: DEFAULT_TARGET_FPS
      MotionTargetMode.FIXED -> config.targetFps
    }.coerceIn(MIN_TARGET_FPS, MAX_TARGET_FPS)

  /**
   * The rate frames reach the screen: mpv synthesizes at most one frame per display refresh, so
   * a target above the refresh rate is capped to it.
   */
  fun effectiveOutputFps(
    targetFps: Int,
    displayFps: Double?,
  ): Double {
    val display = displayFps?.takeIf { it.isFinite() && it > 0.0 } ?: return targetFps.toDouble()
    return minOf(targetFps.toDouble(), display)
  }

  /**
   * The rate to request for the playback Surface while motion interpolation runs, so the display
   * can show every synthesized frame. Null when the display's rates are unknown.
   */
  fun surfaceFrameRate(
    config: MotionConfig,
    context: Context,
  ): Float? =
    when (config.mode) {
      MotionTargetMode.AUTO -> maxDisplayRefreshRate(context)
      MotionTargetMode.FIXED -> config.targetFps.toFloat()
    }

  /** Android's mpv contexts cannot report the refresh rate, which display sync needs. */
  fun displayFpsOverride(context: Context): String? =
    currentDisplayRefreshRate(context)?.let { String.format(Locale.US, "%.3f", it) }
}
