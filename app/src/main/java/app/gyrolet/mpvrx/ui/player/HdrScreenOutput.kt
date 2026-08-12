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
 * Every mode owns the complete mpv HDR/color-output state, so a transition never inherits color
 * properties from the previous mode. OFF restores normal mpv handling; PQ, HLG and BT.2020 use
 * renderer-specific chains from one pinned HDR Toys snapshot; LINEAR is mpv-native HDR output.
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

    // The lightest HDR Toys profile. The controller selects a chain from the one current snapshot.
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

internal fun hdrScreenOutputSettings(
  mode: HdrScreenMode,
  pipelineReady: Boolean,
  boostSdrToHdr: Boolean = false,
  hdrShaderOptions: List<Pair<String, String>> = mode.hdrToysProfile?.shaderOptions.orEmpty(),
): List<Pair<String, String>> {
  val activeMode = if (pipelineReady) mode else HdrScreenMode.OFF
  val settings =
    when (activeMode) {
      HdrScreenMode.OFF -> offSettings()
      HdrScreenMode.LINEAR -> linearHdrSettings(boostSdrToHdr)
      else -> hdrToysSettings(activeMode.hdrToysProfile ?: HdrToysProfile.BT_2100_PQ, hdrShaderOptions)
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

private fun hdrToysSettings(
  profile: HdrToysProfile,
  shaderOptionValues: List<Pair<String, String>>,
): List<Pair<String, String>> =
  commonSettings(
    // hdr-toys owns transfer/gamut/tone processing in GLSL. Do not ask gpu-next to negotiate a
    // second HDR output transform on top of the shader pipeline.
    targetColorspaceHint = "no",
    targetColorspaceHintMode = "target",
    targetPrim = profile.targetPrim,
    targetTrc = profile.targetTrc,
    targetPeak = "auto",
    inverseToneMapping = "no",
    toneMapping = "clip",
    gamutMappingMode = "clip",
    hdrComputePeak = "no",
    shaderOptions =
      ShaderOptionRegistry.merge(
        current = "",
        owner = ShaderOptionOwner.HDR_TOYS,
        values = shaderOptionValues,
      ),
  )

private fun linearHdrSettings(boostSdrToHdr: Boolean): List<Pair<String, String>> =
  commonSettings(
    // Preserve the existing mpv-native HDR rendering policy, but reset every target value that a
    // previous PQ/HLG/BT.2020 mode may have overridden. Without these resets Linear HDR depends on
    // mode history and can keep rendering with stale primaries/TRC after the UI says Linear.
    targetColorspaceHint = "yes",
    targetColorspaceHintMode = "target",
    targetPrim = "auto",
    targetTrc = "auto",
    targetPeak = "auto",
    inverseToneMapping = if (boostSdrToHdr) "yes" else "no",
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
  hdrShaderOptions: List<Pair<String, String>> = mode.hdrToysProfile?.shaderOptions.orEmpty(),
) {
  hdrScreenOutputSettings(mode, pipelineReady, boostSdrToHdr, hdrShaderOptions).forEach { (property, value) ->
    if (property != GLSL_SHADER_OPTIONS) {
      PlaybackSession.setOptionString(property, value)
    }
  }
  if (pipelineReady) {
    hdrShaderOptions.forEach { (key, value) ->
      // Key/value-list append retains foreign entries and replaces the same key if configured.
      PlaybackSession.setOptionString("$GLSL_SHADER_OPTIONS-append", "$key=$value")
    }
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
  hdrShaderOptions: List<Pair<String, String>> = mode.hdrToysProfile?.shaderOptions.orEmpty(),
) {
  hdrScreenOutputSettings(mode, pipelineReady, boostSdrToHdr, hdrShaderOptions).forEach { (property, value) ->
    if (property == GLSL_SHADER_OPTIONS) {
      val shaderOptions =
        hdrShaderOptions.takeIf { pipelineReady }.orEmpty()
      ShaderOptionRegistry.replace(ShaderOptionOwner.HDR_TOYS, shaderOptions)
    } else {
      PlaybackSession.setPropertyString(property, value)
    }
  }
}

private const val GLSL_SHADER_OPTIONS = "glsl-shader-opts"
