package app.gyrolet.mpvrx.ui.player

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
  val title: String,
  val shortTitle: String,
  val description: String,
  val hdrToysProfile: HdrToysProfile? = null,
) {
  OFF(
    title = "Off",
    shortTitle = "Off",
    description = "Normal SDR output",
  ),
  BT_2100_PQ(
    title = "BT.2100 PQ",
    shortTitle = "PQ",
    description = "HDR10-style PQ with Astra tone mapping and Bottosson gamut mapping",
    hdrToysProfile = HdrToysProfile.BT_2100_PQ,
  ),
  BT_2100_HLG(
    title = "BT.2100 HLG",
    shortTitle = "HLG",
    description = "HLG HDR through the hdr-toys Astra and Bottosson shader chain",
    hdrToysProfile = HdrToysProfile.BT_2100_HLG,
  ),
  BT_2020(
    title = "BT.2020",
    shortTitle = "BT.2020",
    description = "BT.2020 / BT.1886 conversion using hdr-toys gamut mapping",
    hdrToysProfile = HdrToysProfile.BT_2020,
  ),
  LINEAR(
    title = "Linear HDR",
    shortTitle = "Linear",
    description = "High-quality mpv HDR output without hdr-toys shaders",
  );

  companion object {
    /** Modes shown in the HDR panel (excludes OFF — that is handled by the toggle button). */
    val selectableModes = listOf(BT_2100_PQ, BT_2100_HLG, BT_2020, LINEAR)

    /** Mode activated when the user first enables HDR from the toggle button. */
    val defaultEnabledMode = BT_2100_PQ
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

private fun offSettings(): List<Pair<String, String>> = listOf(
  "target-colorspace-hint"      to "auto",
  "target-colorspace-hint-mode" to "target",
  "target-trc"                  to "auto",
  "target-prim"                 to "auto",
  "target-peak"                 to "auto",
  "inverse-tone-mapping"        to "no",
  "tone-mapping"                to "auto",
  "gamut-mapping-mode"          to "auto",
  "hdr-compute-peak"            to "no",
  "hdr-reference-white"         to "203",
  "tone-mapping-visualize"      to "no",
  "glsl-shader-opts"            to "",        // clear any hdr-toys shader options
  "gamma"                       to "0",
  "contrast"                    to "0",
  "saturation"                  to "0",
  "brightness"                  to "0",
)

private fun hdrToysSettings(profile: HdrToysProfile): List<Pair<String, String>> = listOf(
  // Disable mpv's built-in colorspace management so hdr-toys shaders have full control.
  "target-colorspace-hint"      to "no",
  "target-colorspace-hint-mode" to "target",
  "target-prim"                 to profile.targetPrim,
  "target-trc"                  to profile.targetTrc,
  "target-peak"                 to "auto",
  "inverse-tone-mapping"        to "no",
  "tone-mapping"                to "clip",    // hdr-toys handles tone-mapping in GLSL
  "gamut-mapping-mode"          to "clip",    // hdr-toys handles gamut-mapping in GLSL
  "hdr-compute-peak"            to "no",
  "hdr-reference-white"         to "203",
  "tone-mapping-visualize"      to "no",
  "glsl-shader-opts"            to profile.shaderOptionsValue,
  "gamma"                       to "0",
  "contrast"                    to "0",
  "saturation"                  to "0",
  "brightness"                  to "0",
)

private fun linearHdrSettings(hdrEnabled: Boolean): List<Pair<String, String>> = listOf(
  "target-colorspace-hint"      to if (hdrEnabled) "yes" else "no",
  "tone-mapping-visualize"      to "no",
  "target-trc"                  to "auto",
  "target-prim"                 to "auto",
  "target-peak"                 to "auto",
  "inverse-tone-mapping"        to if (hdrEnabled) "yes" else "no",
  "tone-mapping"                to if (hdrEnabled) "clip" else "auto",
  "gamut-mapping-mode"          to if (hdrEnabled) "clip" else "auto",
  "hdr-compute-peak"            to if (hdrEnabled) "yes" else "auto",
  "hdr-reference-white"         to "203",
  "glsl-shader-opts"            to "",
  "gamma"                       to "0",
  "contrast"                    to "0",
  "saturation"                  to "0",
  "brightness"                  to "0",
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
