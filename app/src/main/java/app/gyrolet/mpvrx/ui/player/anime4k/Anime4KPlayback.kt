package app.gyrolet.mpvrx.ui.player.anime4k

import android.content.Context
import android.util.Log
import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager
import app.gyrolet.mpvrx.ui.player.ThermalMonitor
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
  val pixels = width.toLong() * height.toLong()

  if (pixels >= 3840L * 2160L) {
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
    MPVLib.setOptionString("opengl-pbo", "yes")
    MPVLib.setOptionString("opengl-early-flush", "no")
  }
  MPVLib.setOptionString("vd-lavc-dr", "yes")
}

private inline fun withPreservedVideoGeometry(block: () -> Unit) {
  val snapshot = captureVideoGeometry()
  block()
  restoreVideoGeometry(snapshot)
}

private fun captureVideoGeometry(): VideoGeometrySnapshot =
  VideoGeometrySnapshot(
    doubles = VIDEO_GEOMETRY_DOUBLE_PROPS.mapNotNull { prop ->
      MPVLib.getPropertyDouble(prop)?.let { prop to it }
    }.toMap(),
    strings = VIDEO_GEOMETRY_STRING_PROPS.mapNotNull { prop ->
      MPVLib.getPropertyString(prop)?.takeIf { it.isNotBlank() }?.let { prop to it }
    }.toMap(),
  )

private fun restoreVideoGeometry(snapshot: VideoGeometrySnapshot) {
  snapshot.doubles.forEach { (prop, value) ->
    runCatching { MPVLib.setPropertyDouble(prop, value) }
  }
  snapshot.strings.forEach { (prop, value) ->
    runCatching { MPVLib.setPropertyString(prop, value) }
  }
}

private val VIDEO_GEOMETRY_DOUBLE_PROPS = listOf(
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

private val VIDEO_GEOMETRY_STRING_PROPS = listOf(
  "video-unscaled",
)

private fun currentShaderList(): List<String> =
  MPVLib.getPropertyString("glsl-shaders")
    ?.split(":")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    .orEmpty()

private fun setShaderList(shaderPaths: List<String>) {
  MPVLib.setPropertyString("glsl-shaders", shaderPaths.joinToString(":"))
}

private fun isBuiltInAnime4KShaderPath(path: String): Boolean {
  val normalized = path.replace('\\', '/')
  val fileName = normalized.substringAfterLast('/')
  return fileName in Anime4KManager.BUILT_IN_SHADER_FILES
}
