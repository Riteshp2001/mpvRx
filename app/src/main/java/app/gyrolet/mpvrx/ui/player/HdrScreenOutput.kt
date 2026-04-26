package app.gyrolet.mpvrx.ui.player

import `is`.xyz.mpv.MPVLib

private fun hdrScreenOutputSettings(
  enabled: Boolean,
  pipelineReady: Boolean,
): List<Pair<String, String>> {
  val hdrEnabled = enabled && pipelineReady

  return listOf(
    /*
     * Main HDR metadata/output colorspace hint.
     *
     * mpv docs recommend auto/yes for correct non-sRGB output signaling.
     * Avoid "no" unless you are sure the output is plain sRGB.
     */
    "target-colorspace-hint" to if (hdrEnabled) "yes" else "auto",

    /*
     * Traditional passthrough-style mode.
     *
     * source = use source HDR metadata directly.
     * target = adapt output to target display.
     *
     * Requires recent mpv + vo=gpu-next.
     * If your build says "option not found", remove this line.
     */
    "target-colorspace-hint-mode" to if (hdrEnabled) "source" else "target",

    /*
     * Keep display/output parameters automatic.
     * Do not force PQ / BT.2020 / peak unless you know the exact display pipeline.
     */
    "target-trc" to "auto",
    "target-prim" to "auto",
    "target-peak" to "auto",

    /*
     * Important:
     * For passthrough, do not expand SDR/HDR brightness artificially.
     */
    "inverse-tone-mapping" to "no",

    /*
     * Do not force clip.
     * clip can crush highlights and distort out-of-range values.
     */
    "tone-mapping" to "auto",

    /*
     * Do not force gamut clipping.
     * auto lets mpv/libplacebo choose the safest mapping if needed.
     */
    "gamut-mapping-mode" to "auto",

    /*
     * For passthrough-style output, do not use dynamic peak detection.
     * hdr-compute-peak is mainly useful for dynamic tone-mapping.
     */
    "hdr-compute-peak" to "no",

    /*
     * mpv/libplacebo default SDR diffuse white in HDR container.
     */
    "hdr-reference-white" to "203",

    /*
     * Debug visualization off.
     */
    "tone-mapping-visualize" to "no",

    /*
     * Keep video EQ neutral for accurate color.
     */
    "gamma" to "0",
    "contrast" to "0",
    "saturation" to "0",
    "brightness" to "0",
  )
}

fun applyHdrScreenOutputOptions(
  enabled: Boolean,
  pipelineReady: Boolean,
) {
  hdrScreenOutputSettings(enabled, pipelineReady).forEach { (property, value) ->
    MPVLib.setOptionString(property, value)
  }
}

fun applyHdrScreenOutputProperties(
  enabled: Boolean,
  pipelineReady: Boolean,
) {
  hdrScreenOutputSettings(enabled, pipelineReady).forEach { (property, value) ->
    MPVLib.setPropertyString(property, value)
  }
}

