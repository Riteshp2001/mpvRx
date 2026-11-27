/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.annotation.StringRes
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.hdr.HdrToysProfile
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy

/**
 * Available HDR screen output modes.
 *
 * Every mode below owns the mpv HDR/color-output state needed by its pipeline. Linear HDR requires
 * inverse tone mapping and clipping; the other modes restore automatic tone mapping.
 *
 * - [OFF]                — restore mpv's normal automatic SDR/HDR handling.
 * - [LINEAR_PASSTHROUGH] — hdr-toys pipeline that sends PQ HDR to an HDR display.
 * - [BT_2100_PQ]         — HDR10 hdr-toys pipeline.
 * - [BT_2100_HLG]        — HLG hdr-toys pipeline.
 * - [BT_2020]            — BT.2020 hdr-toys gamut pipeline.
 * - [LINEAR]             — mpv-native gpu-next HDR path, without hdr-toys shaders.
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
  LINEAR_PASSTHROUGH(
    titleRes = R.string.hdr_mode_linear_passthrough,
    shortTitleRes = R.string.hdr_mode_linear_passthrough_short,
    descriptionRes = R.string.hdr_mode_linear_passthrough_description,
    hdrToysProfile = HdrToysProfile.LINEAR_PASSTHROUGH,
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
    val selectableModes = listOf(LINEAR_PASSTHROUGH, BT_2100_PQ, BT_2100_HLG, BT_2020, LINEAR)

    /** Preferred mode when HDR output is enabled for the first time. */
    val defaultMode = LINEAR_PASSTHROUGH
  }
}

internal fun HdrScreenMode.isSupportedBy(
  videoOutput: String,
  gpuApi: String,
): Boolean =
  when (this) {
    HdrScreenMode.OFF -> true
    HdrScreenMode.LINEAR, HdrScreenMode.LINEAR_PASSTHROUGH -> videoOutput == "gpu-next" && gpuApi == "vulkan"
    // Astra exceeds the legacy GPU renderer's 16-parameter limit.
    HdrScreenMode.BT_2100_PQ, HdrScreenMode.BT_2100_HLG -> videoOutput == "gpu-next"
    HdrScreenMode.BT_2020 -> videoOutput == "gpu" || videoOutput == "gpu-next"
  }

internal fun HdrScreenMode.resolveForRenderer(
  videoOutput: String,
  gpuApi: String,
): HdrScreenMode =
  when {
    isSupportedBy(videoOutput, gpuApi) -> this
    HdrScreenMode.BT_2100_PQ.isSupportedBy(videoOutput, gpuApi) -> HdrScreenMode.BT_2100_PQ
    else -> HdrScreenMode.OFF
  }

/**
 * All mpv options owned by the HDR mode controller.
 *
 * Keep this list synchronized with every mode builder below.
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
    "hdr-compute-peak",
    "hdr-reference-white",
    "tone-mapping-visualize",
    "glsl-shader-opts",
  )

/**
 * The brightness hdr-toys maps HDR output to: the display's desired peak, limited to the 1000 nits
 * reference_white allows. A display that reports no HDR peak gets 1000, as hdr-toys' README uses.
 */
internal fun hdrToysDisplayPeakNits(context: Context): Int {
  val peak =
    runCatching {
      context
        .getSystemService(DisplayManager::class.java)
        ?.getDisplay(Display.DEFAULT_DISPLAY)
        ?.hdrCapabilities
        ?.desiredMaxLuminance
    }.getOrNull()
  return if (peak != null && peak.isFinite() && peak > HDR_REFERENCE_WHITE_NITS) {
    peak.toInt().coerceAtMost(HDR_TOYS_MAX_REFERENCE_WHITE_NITS)
  } else {
    HDR_TOYS_MAX_REFERENCE_WHITE_NITS
  }
}

private const val HDR_REFERENCE_WHITE_NITS = 203
private const val HDR_TOYS_MAX_REFERENCE_WHITE_NITS = 1000

@Suppress("UNUSED_PARAMETER")
internal fun hdrScreenOutputSettings(
  mode: HdrScreenMode,
  displayPeakNits: Int = HDR_TOYS_MAX_REFERENCE_WHITE_NITS,
  boostSdrToHdr: Boolean = false,
): List<Pair<String, String>> {
  val settings =
    when (mode) {
      HdrScreenMode.OFF -> offSettings()
      HdrScreenMode.LINEAR -> linearHdrSettings()
      else -> hdrToysSettings(checkNotNull(mode.hdrToysProfile), displayPeakNits)
    }

  // Defensive invariant for future modes: never allow a partial color-state profile to ship.
  check(settings.map { it.first }.toSet() == HDR_OWNED_PROPERTIES.toSet()) {
    "Incomplete HDR output settings for $mode"
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
  hdrComputePeak: String,
  shaderOptions: String,
  toneMapping: String = "auto",
): List<Pair<String, String>> =
  listOf(
    "target-colorspace-hint" to targetColorspaceHint,
    "target-colorspace-hint-mode" to targetColorspaceHintMode,
    "target-prim" to targetPrim,
    "target-trc" to targetTrc,
    "target-peak" to targetPeak,
    "inverse-tone-mapping" to inverseToneMapping,
    "tone-mapping" to toneMapping,
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
    inverseToneMapping = "no",
    hdrComputePeak = "auto",
    shaderOptions = "",
  )

private fun hdrToysSettings(
  profile: HdrToysProfile,
  displayPeakNits: Int,
): List<Pair<String, String>> =
  commonSettings(
    // hdr-toys owns transfer/gamut/tone processing in GLSL, and target-prim/target-trc match its
    // input so mpv converts nothing. Only an HDR output profile hints its PQ result to the
    // display; the others must not get a second HDR output transform from gpu-next.
    targetColorspaceHint = if (profile.outputsHdr) "yes" else "no",
    targetColorspaceHintMode = "target",
    targetPrim = profile.targetPrim,
    targetTrc = profile.targetTrc,
    targetPeak = "auto",
    inverseToneMapping = "no",
    hdrComputePeak = "no",
    shaderOptions = profile.shaderOptionsValue(displayPeakNits),
  )

private fun linearHdrSettings(): List<Pair<String, String>> =
  commonSettings(
    // Preserve the existing mpv-native HDR rendering policy, but reset every target value that a
    // previous hdr-toys mode may have overridden. Without these resets Linear HDR depends on
    // mode history and can keep rendering with stale primaries/TRC after the UI says Linear.
    targetColorspaceHint = "yes",
    targetColorspaceHintMode = "target",
    targetPrim = "auto",
    targetTrc = "auto",
    targetPeak = "auto",
    inverseToneMapping = "yes",
    hdrComputePeak = "yes",
    shaderOptions = "",
    toneMapping = "clip",
  )

/** Apply HDR settings as mpv init-time options (before playback starts). */
fun applyHdrScreenOutputOptions(
  mode: HdrScreenMode,
  displayPeakNits: Int,
  boostSdrToHdr: Boolean = false,
) {
  hdrScreenOutputSettings(mode, displayPeakNits, boostSdrToHdr).forEach { (property, value) ->
    PlaybackSession.setOptionString(property, value)
  }
}

/**
 * Apply HDR settings during active playback.
 *
 * The settings list is complete for every mode, so transitions between any two modes are
 * idempotent and independent of their previous state. gpu-next marks these options UPDATE_VIDEO,
 * therefore property writes trigger the renderer to rebuild its video output state.
 */
fun applyHdrScreenOutputProperties(
  mode: HdrScreenMode,
  displayPeakNits: Int,
  boostSdrToHdr: Boolean = false,
) {
  val changedOptions =
    hdrScreenOutputSettings(mode, displayPeakNits, boostSdrToHdr)
      .filter { (property, value) ->
        !MpvConfigOverridePolicy.isOwnedByMpvConf(property) &&
          PlaybackSession.getPropertyString(property) != value
      }
  if (changedOptions.isEmpty()) return

  // All HDR options are documented mpv properties. A single load-config-file command keeps
  // the color profile changes together instead of repeatedly crossing JNI and triggering VO
  // reconfiguration between unrelated intermediate HDR/SDR configurations.
  val config =
    changedOptions.joinToString("\n") { (property, value) ->
      require(value.none { it == '\n' || it == '\r' || it == '\u0000' })
      "$property=%${value.toByteArray(Charsets.UTF_8).size}%$value"
    }
  PlaybackSession.command("load-config-file", "memory://$config")
}
