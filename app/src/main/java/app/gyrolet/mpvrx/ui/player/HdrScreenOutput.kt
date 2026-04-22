package app.gyrolet.mpvrx.ui.player

import `is`.xyz.mpv.MPVLib

private fun hdrScreenOutputSettings(
  enabled: Boolean,
  pipelineReady: Boolean,
): List<Pair<String, String>> {
  val hdrEnabled = enabled && pipelineReady

  return listOf(
    "target-colorspace-hint" to if (hdrEnabled) "yes" else "no",
    "tone-mapping-visualize" to "no",
    "target-trc" to "auto",
    "target-prim" to "auto",
    "target-peak" to "auto",
    "inverse-tone-mapping" to if (hdrEnabled) "yes" else "no",
    "tone-mapping" to if (hdrEnabled) "clip" else "auto",
    "gamut-mapping-mode" to if (hdrEnabled) "clip" else "auto",
    "hdr-compute-peak" to if (hdrEnabled) "yes" else "auto",
    "hdr-reference-white" to "203",
    // [HDR-High-Quality] profile: reset video eq for accurate HDR colours
    "gamma" to if (hdrEnabled) "0" else "0",
    "contrast" to if (hdrEnabled) "0" else "0",
    "saturation" to if (hdrEnabled) "0" else "0",
    "brightness" to if (hdrEnabled) "0" else "0",
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

