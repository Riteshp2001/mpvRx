/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.hdr

/** The hdr-toys shader chains of the HDR screen modes, as in upstream's hdr-toys.conf. */
enum class HdrToysProfile(
  val targetPrim: String,
  val targetTrc: String,
  val shaderPaths: List<String>,
  val shaderOptions: List<Pair<String, String>> = emptyList(),
  /** Ends in PQ for an HDR display, so mpv must hint the display instead of showing SDR. */
  val outputsHdr: Boolean = false,
) {
  BT_2100_PQ(
    targetPrim = "bt.2020",
    targetTrc = "pq",
    shaderPaths =
      listOf(
        "hdr-toys/utils/clip_both.glsl",
        "hdr-toys/transfer-function/pq_inv.glsl",
        "hdr-toys/tone-mapping/astra.glsl",
        "hdr-toys/gamut-mapping/bottosson.glsl",
        "hdr-toys/transfer-function/bt1886.glsl",
      ),
    shaderOptions = listOf("auto_exposure_limit_positive" to "1.02"),
  ),
  BT_2100_HLG(
    targetPrim = "bt.2020",
    targetTrc = "hlg",
    shaderPaths =
      listOf(
        "hdr-toys/utils/clip_both.glsl",
        "hdr-toys/transfer-function/hlg_inv.glsl",
        "hdr-toys/tone-mapping/astra.glsl",
        "hdr-toys/gamut-mapping/bottosson.glsl",
        "hdr-toys/transfer-function/bt1886.glsl",
      ),
  ),
  BT_2020(
    targetPrim = "bt.2020",
    targetTrc = "bt.1886",
    shaderPaths =
      listOf(
        "hdr-toys/transfer-function/bt1886_inv.glsl",
        "hdr-toys/gamut-mapping/bottosson.glsl",
        "hdr-toys/transfer-function/bt1886.glsl",
      ),
  ),

  /**
   * hdr-toys' recipe for an HDR display: the BT.2100 PQ chain, but Astra maps to the display's
   * peak ([shaderOptionsValue] sets reference_white to it) and pq.glsl encodes PQ instead of
   * BT.1886. The output stays BT.2020, so no gamut mapping is needed.
   */
  LINEAR_PASSTHROUGH(
    targetPrim = "bt.2020",
    targetTrc = "pq",
    shaderPaths =
      listOf(
        "hdr-toys/utils/clip_both.glsl",
        "hdr-toys/transfer-function/pq_inv.glsl",
        "hdr-toys/tone-mapping/astra.glsl",
        "hdr-toys/transfer-function/pq.glsl",
      ),
    shaderOptions =
      listOf(
        "auto_exposure_limit_positive" to "1.02",
        "contrast_ratio" to "1000000",
      ),
    outputsHdr = true,
  ),
  ;

  /**
   * Comma-separated key=value string passed to mpv's glsl-shader-opts. An HDR output profile maps
   * reference white to [displayPeakNits].
   */
  fun shaderOptionsValue(displayPeakNits: Int): String {
    val options =
      if (outputsHdr) shaderOptions + ("reference_white" to displayPeakNits.toString()) else shaderOptions
    return options.joinToString(",") { (name, value) -> "$name=$value" }
  }

  /** Absolute mpv paths using the ~~/shaders/ config-dir prefix. */
  val mpvShaderPaths: List<String>
    get() = shaderPaths.map { path -> "$MPV_SHADER_PREFIX$path" }

  companion object {
    private const val MPV_SHADER_PREFIX = "~~/shaders/"

    /** Deduplicated set of relative shader paths across all profiles. */
    val allShaderPaths: Set<String> =
      entries
        .flatMap { it.shaderPaths }
        .toSet()

    /** Deduplicated set of absolute mpv shader paths across all profiles. */
    val allMpvShaderPaths: Set<String> =
      allShaderPaths
        .map { path -> "$MPV_SHADER_PREFIX$path" }
        .toSet()
  }
}
