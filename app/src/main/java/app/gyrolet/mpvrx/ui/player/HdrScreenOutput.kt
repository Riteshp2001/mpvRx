/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import androidx.annotation.StringRes
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.hdr.HdrToysProfile

/**
 * Available HDR screen output modes.
 *
 * Every mode below owns the complete mpv HDR/color-output state. This is intentional: switching
 * modes must never depend on whichever target-prim/target-trc/tone-mapping values were left behind
 * by the previous mode.
 *
 * - [OFF]         — restore mpv's normal automatic SDR/HDR handling.
 * - [BT_2100_PQ]  — HDR10 hdr-toys pipeline on gpu-next; native SDR mapping on legacy gpu.
 * - [BT_2100_HLG] — HLG hdr-toys pipeline on gpu-next; native SDR mapping on legacy gpu.
 * - [BT_2020]     — BT.2020 hdr-toys gamut pipeline on gpu-next; native SDR mapping on legacy gpu.
 * - [LINEAR]      — mpv-native gpu-next HDR path, without hdr-toys shaders.
 */
enum class HdrScreenMode(
  @StringRes val titleRes: Int,
  @StringRes val shortTitleRes: Int,
  @StringRes val descriptionRes: Int,
  val hdrToysProfile: HdrToysProfile? = null,
) {
  OFF(
    titleRes = R.string.hdr_mode_off,
    shortTitleRes = R.string.hdr_mode_off,
    descriptionRes = R.string.hdr_mode_off_description,
  ),
  BT_2100_PQ(
    titleRes = R.string.hdr_mode_bt2100_pq,
    shortTitleRes = R.string.hdr_mode_pq_short,
    descriptionRes = R.string.hdr_mode_bt2100_pq_description,
    hdrToysProfile = HdrToysProfile.BT_2100_PQ,
  ),
  BT_2100_HLG(
    titleRes = R.string.hdr_mode_bt2100_hlg,
    shortTitleRes = R.string.hdr_mode_hlg_short,
    descriptionRes = R.string.hdr_mode_bt2100_hlg_description,
    hdrToysProfile = HdrToysProfile.BT_2100_HLG,
  ),
  BT_2020(
    titleRes = R.string.hdr_mode_bt2020,
    shortTitleRes = R.string.hdr_mode_bt2020,
    descriptionRes = R.string.hdr_mode_bt2020_description,
    hdrToysProfile = HdrToysProfile.BT_2020,
  ),
  LINEAR(
    titleRes = R.string.hdr_mode_linear,
    shortTitleRes = R.string.hdr_mode_linear_short,
    descriptionRes = R.string.hdr_mode_linear_description,
  ),
  ;

  companion object {
    val selectableModes = listOf(BT_2100_PQ, BT_2100_HLG, BT_2020, LINEAR)

    val defaultEnabledMode = BT_2020
  }
}

/**
 * All mpv options owned by the HDR mode controller.
 *
 * Keep this list synchronized with every mode builder below. A mode transition is deterministic
 * only when every property that can affect output color is explicitly written by every mode.
 */
private val HDR_OWNED_PROPERTIES =
  listOf(
    "target-colorspace-hint",
    "target-colorspace-hint-mode",
    "target-prim",
    "target-trc",
    "target-peak",
    "inverse-tone-mapping",
    "tone-mapping",
    "gamut-mapping-mode",
    "hdr-compute-peak",
    "hdr-reference-white",
    "tone-mapping-visualize",
    "glsl-shader-opts",
  )

private val HDR_TOYS_SHADER_OPTION_KEYS =
  HdrToysProfile.entries
    .flatMap { profile -> profile.shaderOptions }
    .map { (name, _) -> name }
    .toSet()

internal fun hdrScreenOutputSettings(
  mode: HdrScreenMode,
  pipelineReady: Boolean,
  boostSdrToHdr: Boolean = false,
  gpuNextRenderer: Boolean = true,
): List<Pair<String, String>> {
  val activeMode = if (pipelineReady) mode else HdrScreenMode.OFF
  val settings =
    when (activeMode) {
      HdrScreenMode.OFF -> offSettings()
      HdrScreenMode.LINEAR -> linearHdrSettings(boostSdrToHdr)
      else ->
        if (gpuNextRenderer) {
          hdrToysSettings(activeMode.hdrToysProfile ?: HdrToysProfile.BT_2100_PQ)
        } else {
          legacyGpuSdrSettings()
        }
    }

  // Defensive invariant for future modes: never allow a partial color-state profile to ship.
  check(settings.map { it.first }.toSet() == HDR_OWNED_PROPERTIES.toSet()) {
    "Incomplete HDR output settings for $activeMode"
  }
  return settings
}

private fun commonSettings(
  targetColorspaceHint: String,
  targetColorspaceHintMode: String,
  targetPrim: String,
  targetTrc: String,
  targetPeak: String,
  inverseToneMapping: String,
  toneMapping: String,
  gamutMappingMode: String,
  hdrComputePeak: String,
  shaderOptions: String,
): List<Pair<String, String>> =
  listOf(
    "target-colorspace-hint" to targetColorspaceHint,
    "target-colorspace-hint-mode" to targetColorspaceHintMode,
    "target-prim" to targetPrim,
    "target-trc" to targetTrc,
    "target-peak" to targetPeak,
    "inverse-tone-mapping" to inverseToneMapping,
    "tone-mapping" to toneMapping,
    "gamut-mapping-mode" to gamutMappingMode,
    "hdr-compute-peak" to hdrComputePeak,
    "hdr-reference-white" to "203",
    "tone-mapping-visualize" to "no",
    "glsl-shader-opts" to shaderOptions,
  )

private fun offSettings(): List<Pair<String, String>> =
  commonSettings(
    targetColorspaceHint = "auto",
    targetColorspaceHintMode = "target",
    targetPrim = "auto",
    targetTrc = "auto",
    targetPeak = "auto",
    inverseToneMapping = "auto",
    toneMapping = "auto",
    gamutMappingMode = "auto",
    hdrComputePeak = "auto",
    shaderOptions = "",
  )

private fun hdrToysSettings(profile: HdrToysProfile): List<Pair<String, String>> =
  commonSettings(
    // hdr-toys intentionally performs its transfer/gamut/tone work in the OUTPUT shader chain.
    // Keep its upstream target TRC/primaries intact on gpu-next and do not add another negotiated
    // output transform on top of that chain.
    targetColorspaceHint = "no",
    targetColorspaceHintMode = "target",
    targetPrim = profile.targetPrim,
    targetTrc = profile.targetTrc,
    targetPeak = "auto",
    inverseToneMapping = "no",
    toneMapping = "clip",
    gamutMappingMode = "clip",
    hdrComputePeak = "no",
    shaderOptions = profile.shaderOptionsValue,
  )

/**
 * hdr-toys targets gpu-next/libplacebo. Legacy vo=gpu gets a deterministic native SDR output
 * profile instead of running that shader chain under different hook/color-management semantics.
 */
private fun legacyGpuSdrSettings(): List<Pair<String, String>> =
  commonSettings(
    targetColorspaceHint = "no",
    targetColorspaceHintMode = "target",
    targetPrim = "bt.709",
    targetTrc = "bt.1886",
    targetPeak = "auto",
    inverseToneMapping = "no",
    toneMapping = "auto",
    gamutMappingMode = "auto",
    hdrComputePeak = "auto",
    shaderOptions = "",
  )

private fun linearHdrSettings(boostSdrToHdr: Boolean): List<Pair<String, String>> =
  commonSettings(
    // Restore the adaptive gpu-next path that Linear HDR used before 4704d6c. Forcing clip/clip and
    // peak computation made the renderer bypass the mapping decisions libplacebo needs for the
    // active HDR source/display. Reset target metadata so mode switching cannot leave stale values.
    targetColorspaceHint = "yes",
    targetColorspaceHintMode = "target",
    targetPrim = "auto",
    targetTrc = "auto",
    targetPeak = "auto",
    inverseToneMapping = if (boostSdrToHdr) "yes" else "no",
    toneMapping = "auto",
    gamutMappingMode = "auto",
    hdrComputePeak = "auto",
    shaderOptions = "",
  )

/** Apply HDR settings as mpv init-time options (before playback starts). */
fun applyHdrScreenOutputOptions(
  mode: HdrScreenMode,
  pipelineReady: Boolean,
  boostSdrToHdr: Boolean = false,
) {
  val gpuNextRenderer = RenderBackendCompat.isGpuNextOutput()
  hdrScreenOutputSettings(mode, pipelineReady, boostSdrToHdr, gpuNextRenderer).forEach { (property, value) ->
    val resolvedValue =
      if (property == "glsl-shader-opts") {
        mergeHdrShaderOptions(PlaybackSession.getPropertyString(property), value)
      } else {
        value
      }
    PlaybackSession.setOptionString(property, resolvedValue)
  }
}

/**
 * Apply HDR settings during active playback.
 *
 * The settings list is complete for every mode, so OFF/Linear/PQ/HLG/BT.2020 transitions are
 * idempotent and independent of their previous state. gpu-next marks these options UPDATE_VIDEO,
 * therefore property writes trigger the renderer to rebuild its video output state.
 */
fun applyHdrScreenOutputProperties(
  mode: HdrScreenMode,
  pipelineReady: Boolean,
  boostSdrToHdr: Boolean = false,
) {
  val gpuNextRenderer = RenderBackendCompat.isGpuNextOutput()
  hdrScreenOutputSettings(mode, pipelineReady, boostSdrToHdr, gpuNextRenderer).forEach { (property, value) ->
    val resolvedValue =
      if (property == "glsl-shader-opts") {
        mergeHdrShaderOptions(PlaybackSession.getPropertyString(property), value)
      } else {
        value
      }
    PlaybackSession.setPropertyString(property, resolvedValue)
  }
}

/** Replace only hdr-toys options; preserve Flow/Anime4K/other shader parameters. */
private fun mergeHdrShaderOptions(
  existing: String?,
  desiredHdrOptions: String,
): String {
  val retained =
    splitShaderOptions(existing.orEmpty()).filterNot { entry ->
      HDR_TOYS_SHADER_OPTION_KEYS.contains(entry.substringBefore('=').trim())
    }.toMutableList()
  retained += splitShaderOptions(desiredHdrOptions)
  return retained.joinToString(",")
}

private fun splitShaderOptions(raw: String): List<String> {
  if (raw.isBlank()) return emptyList()
  val result = mutableListOf<String>()
  val current = StringBuilder()
  var escaped = false
  raw.forEach { char ->
    when {
      escaped -> {
        current.append(char)
        escaped = false
      }
      char == '\\' -> {
        current.append(char)
        escaped = true
      }
      char == ',' -> {
        current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
        current.clear()
      }
      else -> current.append(char)
    }
  }
  current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
  return result
}
