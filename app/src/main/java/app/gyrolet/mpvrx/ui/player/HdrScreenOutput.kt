package app.gyrolet.mpvrx.ui.player

import `is`.xyz.mpv.MPVLib

enum class HdrScreenMode(
  val title: String,
  val shortTitle: String,
  val description: String,
) {
  OFF(
    title = "Off",
    shortTitle = "Off",
    description = "Normal SDR / normal output",
  ),
  SDR_IN_HDR(
    title = "SDR with HDR",
    shortTitle = "SDR + HDR",
    description = "Use the HDR screen pipeline for SDR video without artificial boost",
  ),
  HDR(
    title = "Normal HDR",
    shortTitle = "HDR",
    description = "Passthrough-style HDR output for real HDR video",
  ),
}

private fun hdrScreenOutputSettings(
  mode: HdrScreenMode,
  pipelineReady: Boolean,
  boostSdrToHdr: Boolean = false,
): List<Pair<String, String>> {
  val activeMode = if (pipelineReady) mode else HdrScreenMode.OFF

  return when (activeMode) {
    HdrScreenMode.OFF -> listOf(
      "target-colorspace-hint" to "auto",
      "target-colorspace-hint-mode" to "target",
      "target-trc" to "auto",
      "target-prim" to "auto",
      "target-peak" to "auto",
      "inverse-tone-mapping" to "no",
      "tone-mapping" to "auto",
      "gamut-mapping-mode" to "auto",
      "hdr-compute-peak" to "no",
      "hdr-reference-white" to "203",
      "tone-mapping-visualize" to "no",
      "gamma" to "0",
      "contrast" to "0",
      "saturation" to "0",
      "brightness" to "0",
    )

    HdrScreenMode.SDR_IN_HDR -> listOf(
      "target-colorspace-hint" to "yes",
      "target-colorspace-hint-mode" to "target",
      "target-trc" to "auto",
      "target-prim" to "auto",
      "target-peak" to "auto",
      "inverse-tone-mapping" to if (boostSdrToHdr) "yes" else "no",
      "tone-mapping" to "auto",
      "gamut-mapping-mode" to "auto",
      "hdr-compute-peak" to "no",
      "hdr-reference-white" to "203",
      "tone-mapping-visualize" to "no",
      "gamma" to "0",
      "contrast" to "0",
      "saturation" to "0",
      "brightness" to "0",
    )

    HdrScreenMode.HDR -> listOf(
      "target-colorspace-hint" to "yes",
      "target-colorspace-hint-mode" to "source",
      "target-trc" to "auto",
      "target-prim" to "auto",
      "target-peak" to "auto",
      "inverse-tone-mapping" to "no",
      "tone-mapping" to "auto",
      "gamut-mapping-mode" to "auto",
      "hdr-compute-peak" to "no",
      "hdr-reference-white" to "203",
      "tone-mapping-visualize" to "no",
      "gamma" to "0",
      "contrast" to "0",
      "saturation" to "0",
      "brightness" to "0",
    )
  }
}

fun applyHdrScreenOutputOptions(
  mode: HdrScreenMode,
  pipelineReady: Boolean,
) {
  hdrScreenOutputSettings(mode, pipelineReady).forEach { (property, value) ->
    MPVLib.setOptionString(property, value)
  }
}

fun applyHdrScreenOutputProperties(
  mode: HdrScreenMode,
  pipelineReady: Boolean,
) {
  hdrScreenOutputSettings(mode, pipelineReady).forEach { (property, value) ->
    MPVLib.setPropertyString(property, value)
  }
}

