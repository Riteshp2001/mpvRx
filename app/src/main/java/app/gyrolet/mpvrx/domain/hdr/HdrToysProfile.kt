/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.hdr

enum class HdrToysProfile(
  val configSection: String,
  val targetPrim: String,
  val targetTrc: String,
  val shaderPaths: List<String>,
  val shaderOptions: List<Pair<String, String>> = emptyList(),
) {
  BT_2100_PQ(
    configSection = "bt.2100-pq",
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
    configSection = "bt.2100-hlg",
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
    configSection = "bt.2020",
    targetPrim = "bt.2020",
    targetTrc = "bt.1886",
    shaderPaths =
      listOf(
        "hdr-toys/transfer-function/bt1886_inv.glsl",
        "hdr-toys/gamut-mapping/bottosson.glsl",
        "hdr-toys/transfer-function/bt1886.glsl",
      ),
  ),
  LINEAR(
    configSection = "linear",
    targetPrim = "bt.2020",
    targetTrc = "linear",
    shaderPaths =
      listOf(
        "hdr-toys/utils/clip_black.glsl",
        "hdr-toys/utils/clip_alpha.glsl",
        "hdr-toys/tone-mapping/astra.glsl",
        "hdr-toys/gamut-mapping/bottosson.glsl",
        "hdr-toys/transfer-function/bt1886.glsl",
      ),
    shaderOptions =
      listOf(
        "spatial_stable_iterations" to "0",
        // Renamed upstream from temporal_stable_window.
        "temporal_stable_duration" to "0",
        "enable_metering" to "1",
      ),
  ),
  ;

  val shaderOptionsValue: String
    get() = shaderOptions.joinToString(",") { (name, value) -> "$name=$value" }

  val mpvShaderPaths: List<String>
    get() = shaderPaths.map { path -> "$MPV_SHADER_PREFIX$path" }

  companion object {
    private const val MPV_SHADER_PREFIX = "~~/shaders/"

    val allShaderPaths: Set<String> =
      entries
        .flatMap { it.shaderPaths }
        .toSet()

    val allMpvShaderPaths: Set<String> =
      allShaderPaths
        .map { path -> "$MPV_SHADER_PREFIX$path" }
        .toSet()
  }
}
