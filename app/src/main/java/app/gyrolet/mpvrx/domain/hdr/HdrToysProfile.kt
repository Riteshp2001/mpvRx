/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.hdr

import app.gyrolet.mpvrx.ui.player.HdrToysRenderer

enum class HdrToysProfile(
  val targetPrim: String,
  val targetTrc: String,
  val shaderPaths: List<String>,
  val gpuShaderPaths: List<String>,
  val shaderOptions: List<Pair<String, String>> = emptyList(),
) {
  BT_2100_PQ(
    targetPrim = "bt.2020",
    targetTrc = "pq",
    shaderPaths =
      listOf(
        "utils/clip_both.glsl",
        "transfer-function/pq_inv.glsl",
        "tone-mapping/astra.glsl",
        "gamut-mapping/bottosson.glsl",
        "transfer-function/bt1886.glsl",
      ),
    gpuShaderPaths =
      listOf(
        "utils/clip_both.glsl",
        "transfer-function/pq_inv.glsl",
        "tone-mapping/reinhard.glsl",
        "gamut-mapping/jedypod.glsl",
        "transfer-function/bt1886.glsl",
      ),
    shaderOptions = listOf("astra/auto_exposure_limit_positive" to "1.02"),
  ),
  BT_2100_HLG(
    targetPrim = "bt.2020",
    targetTrc = "hlg",
    shaderPaths =
      listOf(
        "utils/clip_both.glsl",
        "transfer-function/hlg_inv.glsl",
        "tone-mapping/astra.glsl",
        "gamut-mapping/bottosson.glsl",
        "transfer-function/bt1886.glsl",
      ),
    gpuShaderPaths =
      listOf(
        "utils/clip_both.glsl",
        "transfer-function/hlg_inv.glsl",
        "tone-mapping/reinhard.glsl",
        "gamut-mapping/jedypod.glsl",
        "transfer-function/bt1886.glsl",
      ),
  ),
  BT_2020(
    targetPrim = "bt.2020",
    targetTrc = "bt.1886",
    shaderPaths =
      listOf(
        "transfer-function/bt1886_inv.glsl",
        "gamut-mapping/bottosson.glsl",
        "transfer-function/bt1886.glsl",
      ),
    gpuShaderPaths =
      listOf(
        "transfer-function/bt1886_inv.glsl",
        "gamut-mapping/jedypod.glsl",
        "transfer-function/bt1886.glsl",
      ),
  ),
  ;

  /** Absolute mpv paths from one pinned snapshot, with a renderer-compatible profile chain. */
  fun mpvShaderPaths(renderer: HdrToysRenderer): List<String> {
    val paths =
      when (renderer) {
        HdrToysRenderer.GPU_NEXT -> shaderPaths
        HdrToysRenderer.GPU -> gpuShaderPaths
      }
    return paths.map { path -> "$MPV_SHADER_PREFIX$CURRENT_TARGET_DIR/$path" }
  }

  companion object {
    private const val MPV_SHADER_PREFIX = "~~/shaders/"

    fun allShaderPaths(renderer: HdrToysRenderer): Set<String> =
      entries
        .flatMap { profile ->
          when (renderer) {
            HdrToysRenderer.GPU_NEXT -> profile.shaderPaths
            HdrToysRenderer.GPU -> profile.gpuShaderPaths
          }
        }
        .toSet()

    val allShaderPaths: Set<String> =
      entries.flatMap { profile -> profile.shaderPaths + profile.gpuShaderPaths }.toSet()

    const val CURRENT_TARGET_DIR = "hdr-toys-220ba8e"

    fun allMpvShaderPaths(renderer: HdrToysRenderer): Set<String> =
      entries.flatMap { it.mpvShaderPaths(renderer) }.toSet()
  }
}
