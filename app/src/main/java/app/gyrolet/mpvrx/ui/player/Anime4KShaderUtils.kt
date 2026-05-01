package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.util.Log
import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager
import `is`.xyz.mpv.MPVLib

internal data class Anime4KSelection(
  val mode: Anime4KManager.Mode,
  val quality: Anime4KManager.Quality,
  val reason: String? = null,
)

internal fun selectThermalSafeAnime4K(
  mode: Anime4KManager.Mode,
  quality: Anime4KManager.Quality,
): Anime4KSelection {
  val width = MPVLib.getPropertyInt("video-params/w") ?: 0
  val height = MPVLib.getPropertyInt("video-params/h") ?: 0
  val fps =
    MPVLib.getPropertyDouble("container-fps")
      ?: MPVLib.getPropertyDouble("estimated-vf-fps")
      ?: 0.0
  val pixels = width.toLong() * height.toLong()

  if (pixels >= 3840L * 2160L) {
    return Anime4KSelection(
      mode = Anime4KManager.Mode.OFF,
      quality = Anime4KManager.Quality.FAST,
      reason = "Disabled Anime4K for 4K+ playback to prevent thermal throttling",
    )
  }

  var selectedMode = mode
  var selectedQuality = quality
  val reasons = mutableListOf<String>()

  val highLoadVideo = pixels >= 2560L * 1440L || fps >= 50.0
  if (highLoadVideo) {
    if (selectedMode in listOf(Anime4KManager.Mode.A_PLUS, Anime4KManager.Mode.B_PLUS, Anime4KManager.Mode.C_PLUS)) {
      selectedMode = Anime4KManager.Mode.C
      reasons += "Preset downgraded to C for high-load video"
    }
    if (selectedQuality != Anime4KManager.Quality.FAST) {
      selectedQuality = Anime4KManager.Quality.FAST
      reasons += "Quality forced to Fast for high-load video"
    }
  }

  return Anime4KSelection(
    mode = selectedMode,
    quality = selectedQuality,
    reason = reasons.takeIf { it.isNotEmpty() }?.joinToString("; "),
  )
}

internal fun selectRuntimeStableAnime4K(
  mode: Anime4KManager.Mode,
  quality: Anime4KManager.Quality,
  context: Context? = null,
): Anime4KSelection {
  val staticSelection = selectThermalSafeAnime4K(mode, quality)
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
        "Anime4KShaderUtils",
        "Thermal headroom low (%.2f) — preemptively downgrading Anime4K to C/Fast".format(headroom),
      )
      return Anime4KSelection(
        mode = Anime4KManager.Mode.C,
        quality = Anime4KManager.Quality.FAST,
        reason = "Thermal headroom low (headroom=%.2f); preemptive downgrade to C/Fast".format(headroom),
      )
    }
  }

  val droppedFrames = MPVLib.getPropertyInt("drop-frame-count") ?: 0
  val delayedFrames = MPVLib.getPropertyInt("vo-delayed-frame-count") ?: 0
  val mistimedFrames = MPVLib.getPropertyInt("mistimed-frame-count") ?: 0
  val voRenderMs = MPVLib.getPropertyDouble("vo-delayed-frame-average-ms") ?: 0.0

  // Runtime pressure guard:
  // If renderer starts falling behind for sustained periods, aggressively lower Anime4K load.
  val highRuntimeLoad =
    droppedFrames >= 45 ||
      delayedFrames >= 60 ||
      mistimedFrames >= 100 ||
      voRenderMs >= 18.0

  if (!highRuntimeLoad) {
    return staticSelection
  }

  return Anime4KSelection(
    mode = Anime4KManager.Mode.C,
    quality = Anime4KManager.Quality.FAST,
    reason = "Runtime pressure detected (drop=$droppedFrames delayed=$delayedFrames mistimed=$mistimedFrames avgDelayMs=$voRenderMs); downgraded to C/Fast",
  )
}

internal fun clearAnime4KShaders() {
  MPVLib.setOptionString("glsl-shaders", "")
  MPVLib.setPropertyString("glsl-shaders", "")
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

  val shaderChain = shaderPaths.joinToString(":")
  MPVLib.setOptionString("glsl-shaders", shaderChain)
  MPVLib.setPropertyString("glsl-shaders", "")
  shaderPaths.forEach { shaderPath ->
    MPVLib.command("change-list", "glsl-shaders", "append", shaderPath)
  }
  return true
}

internal fun applyAnime4KStabilityOptions(useVulkan: Boolean) {
  // OpenGL-only tuning should not be pushed onto the Vulkan backend.
  if (!useVulkan) {
    MPVLib.setOptionString("opengl-pbo", "yes")
    MPVLib.setOptionString("opengl-early-flush", "no")
  }
  MPVLib.setOptionString("vd-lavc-dr", "yes")
}
