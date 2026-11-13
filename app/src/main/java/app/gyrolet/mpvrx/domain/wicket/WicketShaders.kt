/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.wicket

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.hdr.HDR_REFERENCE_WHITE_NITS
import app.gyrolet.mpvrx.domain.hdr.MpvShaderRuntime
import java.io.File
import java.io.FileOutputStream

/**
 * Wicket shaders by Ágúst Ari (GPL-3.0), bundled in assets/shaders/wicket.
 * https://github.com/sup3rflyer/Wicket-shaders
 */
object WicketShaders {
  const val CELFLARE = "CelFlare.glsl"
  const val TEXTURE_CLARITY = "TextureClarity.glsl"
  const val DEBANDIT = "Debandit.glsl"
  const val FILM_GRAIN = "filmgrain-smooth.glsl"
  const val MATCH_GRAIN = "filmgrain-match.glsl"

  val ALL = listOf(MATCH_GRAIN, TEXTURE_CLARITY, DEBANDIT, CELFLARE, FILM_GRAIN)

  internal const val ASSET_DIR = "shaders/wicket"
  internal const val MPV_PREFIX = "~~/shaders/wicket/"

  /** Tags the SDR frame as PQ BT.2020, the colour space CelFlare writes, with a 1000 nit peak. */
  internal const val RETAG_LABEL = "mpvrx_celflare"
  internal const val RETAG_FILTER = "@$RETAG_LABEL:format=gamma=pq:primaries=bt.2020:max-luma=1000"
}

enum class WicketGrain(
  @StringRes val titleRes: Int,
  val shaderFile: String?,
  val preset: Int? = null,
) {
  OFF(R.string.wicket_grain_off, null),
  LIGHT(R.string.wicket_grain_light, WicketShaders.FILM_GRAIN, 1),
  MEDIUM(R.string.wicket_grain_medium, WicketShaders.FILM_GRAIN, 2),
  HEAVY(R.string.wicket_grain_heavy, WicketShaders.FILM_GRAIN, 3),
  MATCH(R.string.wicket_grain_match, WicketShaders.MATCH_GRAIN),
}

data class WicketSelection(
  val textureClarity: Boolean = false,
  val debandit: Boolean = false,
  val celFlare: Boolean = false,
  val grain: WicketGrain = WicketGrain.OFF,
) {
  val isActive: Boolean
    get() = textureClarity || debandit || celFlare || grain != WicketGrain.OFF
}

/** The player state that decides which selected shaders can run. */
data class WicketRuntime(
  /** gpu-next on Vulkan. Wicket needs libplacebo compute shaders. */
  val supported: Boolean,
  /** HW+ skips LUMA hooks and video filters. */
  val hwPlus: Boolean,
  /** Decoder output before any filter retags it; null until the first frame is decoded. */
  val sourceGamma: String?,
  val sourcePrimaries: String?,
  val sourceMatrix: String?,
  /** hdr-toys runs at OUTPUT and ends in BT.1886, so later OUTPUT hooks see SDR. */
  val hdrToysActive: Boolean,
  val targetGamma: String?,
  /** mpv.conf owns `vf`, so the CelFlare retag can't be added. */
  val videoFiltersOwned: Boolean,
)

data class WicketPlan(
  /** Shader files in load order. */
  val shaders: List<String>,
  val options: List<Pair<String, String>>,
) {
  val celFlare: Boolean
    get() = WicketShaders.CELFLARE in shaders

  companion object {
    val EMPTY = WicketPlan(emptyList(), emptyList())
  }
}

private val SDR_TRANSFERS = setOf("bt.1886", "srgb", "gamma1.8", "gamma2.0", "gamma2.2", "gamma2.4", "gamma2.6", "gamma2.8")
private val SDR_PRIMARIES = setOf("bt.709", "bt.601-525", "bt.601-625", "bt.470m", "ebu3213")

/** CelFlare expands SDR only; HDR, wide gamut and not yet decoded sources stay untouched. */
internal fun WicketRuntime.isSdrSource(): Boolean =
  sourceGamma in SDR_TRANSFERS && sourcePrimaries in SDR_PRIMARIES && sourceMatrix != "dolbyvision"

/**
 * Picks the shaders and options for [selection].
 *
 * Load order follows the Wicket README: Match Grain observes LUMA before TextureClarity sharpens
 * it, Debandit cleans MAIN before CelFlare expands it, and film grain lands last at OUTPUT.
 */
fun resolveWicketPlan(
  selection: WicketSelection,
  runtime: WicketRuntime,
): WicketPlan {
  if (!runtime.supported || !selection.isActive) return WicketPlan.EMPTY

  val lumaHooks = !runtime.hwPlus
  val celFlare = selection.celFlare && lumaHooks && !runtime.videoFiltersOwned && runtime.isSdrSource()
  val grainFile = selection.grain.shaderFile?.takeIf { lumaHooks }
  // Grain runs after hdr-toys; without it the OUTPUT stage carries whatever mpv targets.
  val outputIsPq = !runtime.hdrToysActive && runtime.targetGamma == "pq"

  val shaders =
    buildList {
      if (grainFile == WicketShaders.MATCH_GRAIN) add(WicketShaders.MATCH_GRAIN)
      if (selection.textureClarity && lumaHooks) add(WicketShaders.TEXTURE_CLARITY)
      if (selection.debandit) add(WicketShaders.DEBANDIT)
      if (celFlare) add(WicketShaders.CELFLARE)
      if (grainFile == WicketShaders.FILM_GRAIN) add(WicketShaders.FILM_GRAIN)
    }

  val options =
    buildList {
      if (celFlare) add("cf_ref_white" to HDR_REFERENCE_WHITE_NITS)
      if (grainFile != null) {
        selection.grain.preset?.let { add("grain_preset" to it.toString()) }
        add("grain_hdr" to if (outputIsPq) "1" else "0")
        add("grain_ref_white" to HDR_REFERENCE_WHITE_NITS)
        if (grainFile == WicketShaders.MATCH_GRAIN) {
          val nativePq = runtime.sourceGamma == "pq" && !celFlare
          add("grain_source_trc" to if (nativePq) "1" else "0")
        }
      }
    }

  return WicketPlan(shaders, options)
}

/**
 * Copies the bundled Wicket shaders to [Context.filesDir]/shaders/wicket and keeps their part of
 * mpv's `glsl-shaders` list in step with a [WicketPlan].
 */
class WicketShaderManager(
  private val context: Context,
  private val shaderRuntime: MpvShaderRuntime,
) {
  private var initialized = false

  @Synchronized
  fun initialize(): Boolean {
    if (initialized && filesExist()) return true
    return runCatching {
      val destination = File(context.filesDir, WicketShaders.ASSET_DIR).apply { mkdirs() }
      WicketShaders.ALL.forEach { name ->
        context.assets.open("${WicketShaders.ASSET_DIR}/$name").use { input ->
          FileOutputStream(File(destination, name)).use { output -> input.copyTo(output) }
        }
      }
      filesExist().also { initialized = it }
    }.onFailure { error ->
      initialized = false
      Log.w(TAG, "Failed to copy Wicket shaders", error)
    }.getOrDefault(false)
  }

  /**
   * Replaces the Wicket shaders with [plan], appended after whatever is loaded now.
   * Returns false when a shader in the plan could not be loaded.
   */
  @Synchronized
  fun apply(plan: WicketPlan): Boolean {
    if (plan.shaders.isEmpty()) {
      clear()
      return true
    }
    if (!initialize()) {
      clear()
      return false
    }

    var shaders = plan.shaders
    if (plan.celFlare) {
      // CelFlare writes PQ. Without the retag mpv would show that as SDR, so drop the shader.
      if (!addRetag()) shaders = shaders - WicketShaders.CELFLARE
    } else {
      removeRetag()
    }

    removeShaders()
    shaders.forEach { name ->
      shaderRuntime.command("change-list", "glsl-shaders", "append", "${WicketShaders.MPV_PREFIX}$name")
    }
    return shaders.size == plan.shaders.size
  }

  @Synchronized
  fun clear() {
    removeShaders()
    removeRetag()
  }

  private fun filesExist(): Boolean =
    WicketShaders.ALL.all { name ->
      File(context.filesDir, "${WicketShaders.ASSET_DIR}/$name").let { it.exists() && it.length() > 0L }
    }

  private fun removeShaders() {
    val active = shaderRuntime.getPropertyString("glsl-shaders").orEmpty()
    if (!active.contains(WicketShaders.MPV_PREFIX)) return
    WicketShaders.ALL
      .map { name -> "${WicketShaders.MPV_PREFIX}$name" }
      .filter { path -> active.contains(path) }
      .forEach { path -> runCatching { shaderRuntime.command("change-list", "glsl-shaders", "remove", path) } }
  }

  private fun addRetag(): Boolean {
    if (shaderRuntime.hasVideoFilter(WicketShaders.RETAG_LABEL)) return true
    runCatching { shaderRuntime.command("vf", "add", WicketShaders.RETAG_FILTER) }
      .onFailure { error -> Log.w(TAG, "Failed to add the CelFlare retag filter", error) }
    return shaderRuntime.hasVideoFilter(WicketShaders.RETAG_LABEL)
  }

  private fun removeRetag() {
    if (!shaderRuntime.hasVideoFilter(WicketShaders.RETAG_LABEL)) return
    runCatching { shaderRuntime.command("vf", "remove", "@${WicketShaders.RETAG_LABEL}") }
  }

  private companion object {
    const val TAG = "WicketShaderManager"
  }
}
