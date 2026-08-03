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
import `is`.xyz.mpv.MPVLib

/**
 * Available HDR screen output modes.
 *
 * - [OFF]         — SDR output; all HDR pipeline options reset to safe defaults.
 * - [BT_2100_PQ]  — HDR10 via hdr-toys (PQ inverse-EOTF → Astra TM → Bottosson GM → bt.1886).
 * - [BT_2100_HLG] — HLG via hdr-toys (HLG inverse-EOTF → Astra TM → Bottosson GM → bt.1886).
 * - [BT_2020]     — BT.2020/BT.1886 gamut mapping only via hdr-toys Bottosson shader.
 * - [LINEAR]      — High-quality mpv-native HDR output without hdr-toys shaders.
 *
 * [OFF] is the default; the player's HDR toggle button switches between [OFF] and
 * [defaultEnabledMode].  The HDR panel only exposes [selectableModes].
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
    /** Modes shown in the HDR panel (excludes OFF — that is handled by the toggle button). */
    val selectableModes = listOf(BT_2100_PQ, BT_2100_HLG, BT_2020, LINEAR)

    /** Mode activated when the user first enables HDR from the toggle button. */
    // The lightest hdr-toys profile; it also works on the legacy GPU renderer.
    val defaultEnabledMode = BT_2020
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Internal mpv property lists
// ─────────────────────────────────────────────────────────────────────────────

@Suppress("UNUSED_PARAMETER")
internal fun hdrScreenOutputSettings(
  mode: HdrScreenMode,
  pipelineReady: Boolean,
  boostSdrToHdr: Boolean = false,
): List<Pair<String, String>> {
  val activeMode = if (pipelineReady) mode else HdrScreenMode.OFF

  return when (activeMode) {
    HdrScreenMode.OFF -> offSettings()
    HdrScreenMode.LINEAR -> linearHdrSettings(hdrEnabled = true)
    else -> hdrToysSettings(activeMode.hdrToysProfile ?: HdrToysProfile.BT_2100_PQ)
  }
}

private fun offSettings(): List<Pair<String, String>> =
  listOf(
    "target-colorspace-hint" to "auto",
    "target-colorspace-hint-mode" to "target",
    "target-prim" to "auto",
    "target-trc" to "auto",
    "target-peak" to "auto",
    "inverse-tone-mapping" to "auto",
    "tone-mapping" to "auto",
    "gamut-mapping-mode" to "auto",
    "hdr-compute-peak" to "auto",
    "hdr-reference-white" to "203",
    "tone-mapping-visualize" to "no",
    "glsl-shader-opts" to "",
  )

private fun hdrToysSettings(profile: HdrToysProfile): List<Pair<String, String>> =
  listOf(
    // Disable mpv's built-in colorspace management so hdr-toys shaders have full control.
    "target-colorspace-hint" to "no",
    "target-colorspace-hint-mode" to "target",
    "target-prim" to profile.targetPrim,
    "target-trc" to profile.targetTrc,
    "target-peak" to "auto",
    "inverse-tone-mapping" to "no",
    "tone-mapping" to "clip", // hdr-toys handles tone-mapping in GLSL
    "gamut-mapping-mode" to "clip", // hdr-toys handles gamut-mapping in GLSL
    "hdr-compute-peak" to "no",
    "hdr-reference-white" to "203",
    "tone-mapping-visualize" to "no",
    "glsl-shader-opts" to profile.shaderOptionsValue,
  )

private fun linearHdrSettings(hdrEnabled: Boolean): List<Pair<String, String>> =
  listOf(
    "target-colorspace-hint" to if (hdrEnabled) "yes" else "no",
    "tone-mapping-visualize" to "no",
    "inverse-tone-mapping" to if (hdrEnabled) "yes" else "no",
    "tone-mapping" to "clip",
    "gamut-mapping-mode" to if (hdrEnabled) "clip" else "auto",
    "hdr-compute-peak" to if (hdrEnabled) "yes" else "auto",
    "hdr-reference-white" to "203",
    "glsl-shader-opts" to "",
  )

// ─────────────────────────────────────────────────────────────────────────────
// Public apply helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Apply HDR settings as mpv init-time options (call before playback starts). */
fun applyHdrScreenOutputOptions(
  mode: HdrScreenMode,
  pipelineReady: Boolean,
  boostSdrToHdr: Boolean = false,
) {
  hdrScreenOutputSettings(mode, pipelineReady, boostSdrToHdr).forEach { (property, value) ->
    MPVLib.setOptionString(property, value)
  }
}

/** Apply HDR settings as mpv runtime properties (call during active playback). */
fun applyHdrScreenOutputProperties(
  mode: HdrScreenMode,
  pipelineReady: Boolean,
  boostSdrToHdr: Boolean = false,
) {
  hdrScreenOutputSettings(mode, pipelineReady, boostSdrToHdr).forEach { (property, value) ->
    MPVLib.setPropertyString(property, value)
  }
}
