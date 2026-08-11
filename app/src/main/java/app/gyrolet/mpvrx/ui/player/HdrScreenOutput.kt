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
 * The HDR-toys modes intentionally follow the v2.0.0 output contract and are allowed on both
 * legacy `vo=gpu` and `vo=gpu-next`. LINEAR remains a separate mpv-native path and the player UI
 * only exposes it when gpu-next + Vulkan are selected.
 *
 * Every mode owns the complete mpv HDR/color-output state so a mode switch cannot inherit stale
 * target primaries, transfer functions, tone mapping or shader options from the previous mode.
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

    // v2.0.0 default: the lightest hdr-toys profile and valid on the legacy GPU renderer too.
    val defaultEnabledMode = BT_2020
  }
}

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

@Suppress("UNUSED_PARAMETER")
internal fun hdrScreenOutputSettings(
  mode: HdrScreenMode,
  pipelineReady: Boolean,
  boostSdrToHdr: Boolean = false,
): List<Pair<String, String>> {
  val activeMode = if (pipelineReady) mode else HdrScreenMode.OFF
  val settings =
    when (activeMode) {
      HdrScreenMode.OFF -> offSettings()
      HdrScreenMode.LINEAR -> linearHdrSettings()
      else -> hdrToysSettings(activeMode.hdrToysProfile ?: HdrToysProfile.BT_2100_PQ)
    }

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

/** v2.0.0 HDR-toys contract: shaders own transfer/tone/gamut processing on both GPU renderers. */
private fun hdrToysSettings(profile: HdrToysProfile): List<Pair<String, String>> =
  commonSettings(
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
 * Restore the v2.0.0 Linear HDR behavior. The target metadata fields are still reset explicitly so
 * entering LINEAR after PQ/HLG cannot retain a stale transfer function; the effective mapping is the
 * original hint=yes, inverse-tone-mapping=yes, clip/clip, compute-peak=yes pipeline.
 */
private fun linearHdrSettings(): List<Pair<String, String>> =
  commonSettings(
    targetColorspaceHint = "yes",
    targetColorspaceHintMode = "target",
    targetPrim = "auto",
    targetTrc = "auto",
    targetPeak = "auto",
    inverseToneMapping = "yes",
    toneMapping = "clip",
    gamutMappingMode = "clip",
    hdrComputePeak = "yes",
    shaderOptions = "",
  )

/** Apply HDR settings as mpv init-time options (before playback starts). */
fun applyHdrScreenOutputOptions(
  mode: HdrScreenMode,
  pipelineReady: Boolean,
  boostSdrToHdr: Boolean = false,
) {
  hdrScreenOutputSettings(mode, pipelineReady, boostSdrToHdr).forEach { (property, value) ->
    val resolvedValue =
      if (property == "glsl-shader-opts") {
        mergeHdrShaderOptions(PlaybackSession.getPropertyString(property), value)
      } else {
        value
      }
    PlaybackSession.setOptionString(property, resolvedValue)
  }
}

/** Apply the complete HDR state during active playback. */
fun applyHdrScreenOutputProperties(
  mode: HdrScreenMode,
  pipelineReady: Boolean,
  boostSdrToHdr: Boolean = false,
) {
  hdrScreenOutputSettings(mode, pipelineReady, boostSdrToHdr).forEach { (property, value) ->
    val resolvedValue =
      if (property == "glsl-shader-opts") {
        mergeHdrShaderOptions(PlaybackSession.getPropertyString(property), value)
      } else {
        value
      }
    PlaybackSession.setPropertyString(property, resolvedValue)
  }
}

/** Replace only hdr-toys-owned parameters; preserve Flow/Anime4K/other shader parameters. */
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
