/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.anime4k

import android.content.Context
import android.util.Log
import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.ThermalMonitor

internal data class Anime4KSelection(
  val mode: Anime4KManager.Mode,
  val quality: Anime4KManager.Quality,
  val reason: String? = null,
)

internal fun selectThermalSafeAnime4K(
  mode: Anime4KManager.Mode,
  quality: Anime4KManager.Quality,
  enableIn4k: Boolean = false,
): Anime4KSelection {
  val width = PlaybackSession.getPropertyInt("video-params/w") ?: 0
  val height = PlaybackSession.getPropertyInt("video-params/h") ?: 0
  val pixels = width.toLong() * height.toLong()

  if (!enableIn4k && pixels >= 3840L * 2160L) {
    return Anime4KSelection(
      mode = Anime4KManager.Mode.OFF,
      quality = Anime4KManager.DEFAULT_QUALITY,
      reason = "Disabled Anime4K for 4K+ playback to prevent thermal throttling",
    )
  }

  return Anime4KSelection(
    mode = mode,
    quality = quality,
  )
}

internal fun selectRuntimeStableAnime4K(
  mode: Anime4KManager.Mode,
  quality: Anime4KManager.Quality,
  context: Context? = null,
  enableIn4k: Boolean = false,
): Anime4KSelection {
  val staticSelection = selectThermalSafeAnime4K(mode, quality, enableIn4k)
  if (staticSelection.mode == Anime4KManager.Mode.OFF) {
    return staticSelection
  }

  // ── Proactive thermal guard (API 30+) ────────────────────────────────────
  // Check the device's thermal headroom *before* inspecting frame-drop counters.
  // Frame drops are a lagging indicator — by the time 45 frames are dropped the
  // SoC may already be throttling.  Catching low headroom early avoids the
  // thermal runaway that causes battery drain and stutter.
  if (context != null) {
    val headroom = ThermalMonitor.getHeadroom(context)
    if (ThermalMonitor.shouldThrottleAnime4K(headroom)) {
      Log.i(
        "Anime4KPlayback",
        "Thermal headroom low (%.2f) — preemptively downgrading Anime4K to C/Fast".format(headroom),
      )
      return Anime4KSelection(
        mode = Anime4KManager.Mode.C,
        quality = Anime4KManager.Quality.FAST,
        reason = "Thermal headroom low (headroom=%.2f); preemptive downgrade to C/Fast".format(headroom),
      )
    }
  }

  val droppedFrames = PlaybackSession.getPropertyInt("drop-frame-count") ?: 0
  val delayedFrames = PlaybackSession.getPropertyInt("vo-delayed-frame-count") ?: 0
  val mistimedFrames = PlaybackSession.getPropertyInt("mistimed-frame-count") ?: 0
  val voRenderMs = PlaybackSession.getPropertyDouble("vo-delayed-frame-average-ms") ?: 0.0

  // Runtime pressure guard:
  // If renderer starts falling behind for sustained periods, aggressively lower Anime4K load.
  // Lowered thresholds for 4K HDR content which requires faster reaction to prevent stutter.
  val highRuntimeLoad =
    droppedFrames >= 15 ||
      delayedFrames >= 25 ||
      mistimedFrames >= 40 ||
      voRenderMs >= 12.0

  if (!highRuntimeLoad) {
    return staticSelection
  }

  return Anime4KSelection(
    mode = Anime4KManager.Mode.C,
    quality = Anime4KManager.Quality.FAST,
    reason = "Runtime pressure detected (drop=$droppedFrames delayed=$delayedFrames mistimed=$mistimedFrames avgDelayMs=$voRenderMs); downgraded to C/Fast",
  )
}

private data class VideoGeometrySnapshot(
  val doubles: Map<String, Double>,
  val strings: Map<String, String>,
)

internal fun clearAnime4KShaders() {
  withPreservedVideoGeometry {
    setShaderList(currentShaderList().filterNot(::isBuiltInAnime4KShaderPath))
  }
}

internal fun applyAnime4KShaderChain(
  anime4kManager: Anime4KManager,
  mode: Anime4KManager.Mode,
  quality: Anime4KManager.Quality,
): Boolean {
  if (!anime4kManager.initialize()) {
    return false
  }

  val shaderPaths = anime4kManager.getShaderPaths(mode, quality)
  if (shaderPaths.isEmpty()) {
    return false
  }

  withPreservedVideoGeometry {
    val retainedShaders = currentShaderList().filterNot(::isBuiltInAnime4KShaderPath)
    setShaderList(shaderPaths + retainedShaders)
  }
  return true
}

internal fun applyAnime4KStabilityOptions(useVulkan: Boolean) {
  // OpenGL-only tuning should not be pushed onto the Vulkan backend.
  if (!useVulkan) {
    PlaybackSession.setOptionString("opengl-pbo", "yes")
    PlaybackSession.setOptionString("opengl-early-flush", "no")
  }
  PlaybackSession.setOptionString("vd-lavc-dr", "yes")
}

private inline fun withPreservedVideoGeometry(block: () -> Unit) {
  val snapshot = captureVideoGeometry()
  block()
  restoreVideoGeometry(snapshot)
}

private fun captureVideoGeometry(): VideoGeometrySnapshot =
  VideoGeometrySnapshot(
    doubles =
      VIDEO_GEOMETRY_DOUBLE_PROPS
        .mapNotNull { prop ->
          PlaybackSession.getPropertyDouble(prop)?.let { prop to it }
        }.toMap(),
    strings =
      VIDEO_GEOMETRY_STRING_PROPS
        .mapNotNull { prop ->
          PlaybackSession.getPropertyString(prop)?.takeIf { it.isNotBlank() }?.let { prop to it }
        }.toMap(),
  )

private fun restoreVideoGeometry(snapshot: VideoGeometrySnapshot) {
  snapshot.doubles.forEach { (prop, value) ->
    runCatching { PlaybackSession.setPropertyDouble(prop, value) }
  }
  snapshot.strings.forEach { (prop, value) ->
    runCatching { PlaybackSession.setPropertyString(prop, value) }
  }
}

private val VIDEO_GEOMETRY_DOUBLE_PROPS =
  listOf(
    "video-zoom",
    "video-pan-x",
    "video-pan-y",
    "video-align-x",
    "video-align-y",
    "video-aspect-override",
    "panscan",
    "brightness",
    "contrast",
    "saturation",
    "gamma",
    "hue",
    "sharpen",
  )

private val VIDEO_GEOMETRY_STRING_PROPS =
  listOf(
    "video-unscaled",
  )

private fun currentShaderList(): List<String> =
  app.gyrolet.mpvrx.ui.player.MpvPathList.decode(PlaybackSession.getPropertyString("glsl-shaders"))

private fun setShaderList(shaderPaths: List<String>) {
  PlaybackSession.updateShaderList { current ->
    val requestedAnimeShaders = shaderPaths.filter(::isBuiltInAnime4KShaderPath)
    val retained = current.filterNot(::isBuiltInAnime4KShaderPath)
    requestedAnimeShaders + retained
  }
}

private fun isBuiltInAnime4KShaderPath(path: String): Boolean {
  val normalized = path.replace('\\', '/')
  val fileName = normalized.substringAfterLast('/')
  return fileName in Anime4KManager.BUILT_IN_SHADER_FILES
}
