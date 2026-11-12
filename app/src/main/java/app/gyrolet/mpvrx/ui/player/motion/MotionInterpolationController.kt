/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.motion

import android.content.Context
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.motion.MotionAvailability
import app.gyrolet.mpvrx.domain.motion.MotionConfig
import app.gyrolet.mpvrx.domain.motion.MotionInterpolation
import app.gyrolet.mpvrx.domain.motion.MotionState
import app.gyrolet.mpvrx.domain.motion.MotionTargetMode
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class MotionUiState(
  val config: MotionConfig,
  val availability: MotionAvailability,
  /** Enabled and every requirement met, so mpv has the motion core switched on. */
  val running: Boolean,
)

/** What mpv's motion core is doing, for the sheet's status line. */
data class MotionLive(
  val state: MotionState?,
  val videoFps: Double?,
  /** The rate frames actually reach the screen: the target capped to the display refresh. */
  val outputFps: Double?,
  val displayFps: Double?,
) {
  companion object {
    val EMPTY = MotionLive(null, null, null, null)
  }
}

/**
 * Applies the motion interpolation preferences to the live mpv core. Preferences are the single
 * source of truth: the player sheet and Decoder settings only write them, and every change is
 * picked up here, so both take effect immediately.
 */
class MotionInterpolationController(
  private val context: Context,
  private val preferences: DecoderPreferences,
  private val scope: CoroutineScope,
  private val showText: (String) -> Unit,
) {
  private val _state =
    MutableStateFlow(
      MotionUiState(
        config = MotionInterpolation.config(preferences),
        availability =
          if (MotionInterpolation.isBuildSupported) {
            MotionAvailability.READY
          } else {
            MotionAvailability.UNSUPPORTED_BUILD
          },
        running = false,
      ),
    )
  val state: StateFlow<MotionUiState> = _state.asStateFlow()

  // The video-sync mode that was active before motion interpolation needed display sync.
  private var savedVideoSync: String? = null
  private var applyJob: Job? = null

  // A preference change re-runs apply() and cancels the previous run, so keep its toast pending.
  private var announcePending = false

  init {
    val watched: List<Flow<Any>> =
      listOf(
        preferences.motionInterpolationEnabled.changes(),
        preferences.motionInterpolationMode.changes(),
        preferences.motionInterpolationTargetFps.changes(),
        // Leaving GPU Next + Vulkan switches motion interpolation off.
        preferences.gpuNext.changes(),
        preferences.useVulkan.changes(),
      )
    scope.launch {
      // The first combination is the stored state, which MPVView applied before the core started.
      combine(watched) { }.drop(1).collect { apply() }
    }
    refresh()
  }

  /** Re-checks every requirement, e.g. after a file loads or the sheet opens. */
  fun refresh() {
    apply()
  }

  fun toggle() {
    setEnabled(!preferences.motionInterpolationEnabled.get())
  }

  /** Selects GPU Next with Vulkan, which the motion core renders through; applies on reopen. */
  fun enableRequiredRenderer() {
    if (!MotionInterpolation.isBuildSupported || !MotionInterpolation.isDeviceSupported(context)) return
    preferences.gpuNext.set(true)
    preferences.useVulkan.set(true)
    showText(context.getString(R.string.motion_renderer_enabled))
  }

  /** Picks a target and switches on, since choosing a rate is a request to interpolate to it. */
  fun selectTarget(
    mode: MotionTargetMode,
    fps: Int? = null,
  ) {
    // The rate first, so switching to a fixed target never applies the previous one.
    fps?.let { preferences.motionInterpolationTargetFps.set(it) }
    preferences.motionInterpolationMode.set(mode)
    if (!preferences.motionInterpolationEnabled.get()) setEnabled(true)
  }

  /** Switching on is refused, with the reason shown, while a hard requirement is unmet. */
  fun setEnabled(enabled: Boolean) {
    if (enabled) {
      val barrier = MotionInterpolation.barrier(context, preferences)
      if (barrier.blocksEnabling) {
        barrier.messageRes?.let { showText(context.getString(it)) }
        apply()
        return
      }
    }
    announcePending = true
    if (preferences.motionInterpolationEnabled.get() == enabled) {
      apply()
    } else {
      // The preference observer runs apply() and shows the pending toast.
      preferences.motionInterpolationEnabled.set(enabled)
    }
  }

  private fun apply() {
    applyJob?.cancel()
    applyJob =
      scope.launch {
        val barrier = MotionInterpolation.barrier(context, preferences)
        if (barrier.blocksEnabling && preferences.motionInterpolationEnabled.get()) {
          // Turning off GPU Next or Vulkan also turns motion interpolation off.
          preferences.motionInterpolationEnabled.set(false)
          announcePending = false
          barrier.messageRes?.let { showText(context.getString(it)) }
        }

        val config = MotionInterpolation.config(preferences)
        val coreReady = PlaybackSession.getPropertyString("mpv-version") != null
        val optionKnown =
          coreReady && PlaybackSession.getPropertyString("option-info/${MotionInterpolation.OPTION}/name") != null
        val running = optionKnown && currentTargetFps() > 0
        // mpv.conf may switch the core on without claiming the option; leave that alone.
        val configOwned =
          MpvConfigOverridePolicy.isOwnedByMpvConf(MotionInterpolation.OPTION) ||
            (running && !MotionInterpolation.appApplied && !config.enabled)
        val availability =
          when {
            barrier.blocksEnabling -> barrier
            !coreReady -> MotionAvailability.READY
            !optionKnown -> MotionAvailability.UNSUPPORTED_LIBMPV
            configOwned -> MotionAvailability.CONFIG_OWNED
            !isRendererRunning() -> MotionAvailability.RENDERER_RESTART
            !isDisplaySyncAllowed() -> MotionAvailability.NEEDS_DISPLAY_SYNC
            isGpuUnsupported() -> MotionAvailability.UNSUPPORTED_GPU
            else -> MotionAvailability.READY
          }

        val shouldRun = config.enabled && coreReady && availability == MotionAvailability.READY
        if (optionKnown && !configOwned) {
          if (shouldRun) {
            if (!running) savedVideoSync = PlaybackSession.getPropertyString("video-sync")
            applyRunning(config)
          } else if (running && MotionInterpolation.appApplied) {
            stop()
          }
        }

        _state.value = MotionUiState(config, availability, shouldRun)
        if (announcePending) {
          announcePending = false
          announce(config, availability)
        }
      }
  }

  private fun applyRunning(config: MotionConfig) {
    MotionInterpolation.displayFpsOverride(context)?.let { fps ->
      val current = PlaybackSession.getPropertyDouble("display-fps-override")
      if (current == null || abs(current - fps.toDouble()) > 0.001) setOption("display-fps-override", fps)
    }
    if (PlaybackSession.getPropertyString("video-sync")?.startsWith("display-") != true) {
      setOption("video-sync", "display-resample")
    }
    // Changing the target restarts the motion core, so only write it when it differs.
    val target = MotionInterpolation.mpvTargetFps(config, context)
    if (currentTargetFps() != target) setOption(MotionInterpolation.OPTION, target.toString())
    MotionInterpolation.appApplied = true
  }

  /** Undoes only what mpvRx applied, restoring the sync mode that was active before it. */
  private fun stop() {
    setOption(MotionInterpolation.OPTION, "0")
    // Null when startup applied it before this controller existed; then the app default applies.
    setOption("video-sync", savedVideoSync ?: "audio")
    setOption("display-fps-override", "0")
    MotionInterpolation.appApplied = false
    savedVideoSync = null
  }

  private fun setOption(
    name: String,
    value: String,
  ) {
    PlaybackSession.setOptionString(name, value)
  }

  private fun currentTargetFps(): Int = PlaybackSession.getPropertyInt(MotionInterpolation.OPTION) ?: 0

  /** The live renderer, not the preference: a failed Vulkan start falls back to OpenGL. */
  private fun isRendererRunning(): Boolean =
    PlaybackSession.videoOutput == "gpu-next" && PlaybackSession.getPropertyString("gpu-api") == "vulkan"

  private fun isDisplaySyncAllowed(): Boolean =
    !MpvConfigOverridePolicy.isOwnedByMpvConf("video-sync") ||
      PlaybackSession.getPropertyString("video-sync")?.startsWith("display-") == true

  /** Only known once a video output exists; until then it is assumed to work. */
  private fun isGpuUnsupported(): Boolean =
    PlaybackSession.getPropertyBoolean("${MotionInterpolation.INFO_PROPERTY}/supported") == false

  private fun announce(
    config: MotionConfig,
    availability: MotionAvailability,
  ) {
    val message =
      when {
        !config.enabled -> context.getString(R.string.motion_toast_off)
        availability.messageRes != null -> context.getString(availability.messageRes)
        else -> context.getString(R.string.motion_toast_on, targetLabel(config))
      }
    showText(message)
  }

  fun targetLabel(config: MotionConfig): String =
    when (config.mode) {
      MotionTargetMode.AUTO ->
        context.getString(R.string.motion_target_auto_value, MotionInterpolation.mpvTargetFps(config, context))
      MotionTargetMode.FIXED -> context.getString(R.string.motion_fps_value, config.targetFps)
    }

  /** Reads mpv's motion state and rates. Blocking native reads; call off the main thread. */
  fun live(): MotionLive {
    val info = MotionInterpolation.INFO_PROPERTY
    val target = PlaybackSession.getPropertyInt("$info/target-fps") ?: currentTargetFps()
    val display =
      PlaybackSession.getPropertyDouble("display-fps")?.takeIf { it > 0.0 }
        ?: MotionInterpolation.currentDisplayRefreshRate(context)?.toDouble()
    return MotionLive(
      state = MotionState.fromKey(PlaybackSession.getPropertyString("$info/state")),
      videoFps =
        PlaybackSession.getPropertyDouble("estimated-vf-fps")?.takeIf { it > 0.0 }
          ?: PlaybackSession.getPropertyDouble("container-fps")?.takeIf { it > 0.0 },
      outputFps = target.takeIf { it > 0 }?.let { MotionInterpolation.effectiveOutputFps(it, display) },
      displayFps = display,
    )
  }
}

/** Rounds to whole numbers except for NTSC-style rates such as 23.976. */
fun formatFps(fps: Double): String =
  if (abs(fps - fps.roundToInt()) < 0.01) fps.roundToInt().toString() else String.format(Locale.US, "%.2f", fps)
