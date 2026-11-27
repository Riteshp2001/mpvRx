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

/**
 * Named presets for the Post-Processing feature.
 * Each entry maps to an ordered list of shaders that are baked + injected at runtime
 * via [buildShaders]. Chains marked with [Eden] comment match Eden's `.fxp` preset files.
 */
enum class PostProcessingPreset(
  @StringRes val displayNameRes: Int,
  @StringRes val descriptionRes: Int,
) {
  None(R.string.pp_preset_none, R.string.pp_desc_none),
  Natural(R.string.pp_preset_natural, R.string.pp_desc_natural),
  Vivid(R.string.pp_preset_vivid, R.string.pp_desc_vivid),
  Anime(R.string.pp_preset_anime, R.string.pp_desc_anime),
  Clean(R.string.pp_preset_clean, R.string.pp_desc_clean), // Eden: Clean.fxp
  CelShaded(R.string.pp_preset_cel_shaded, R.string.pp_desc_cel_shaded),
  Cartoon(R.string.pp_preset_cartoon, R.string.pp_desc_cartoon),
  Cinematic(R.string.pp_preset_cinematic, R.string.pp_desc_cinematic), // Eden: Cinematic.fxp
  Dreamy(R.string.pp_preset_dreamy, R.string.pp_desc_dreamy), // Eden: Dreamy.fxp
  Retro(R.string.pp_preset_retro, R.string.pp_desc_retro), // Eden: Retro.fxp
  Bloom(R.string.pp_preset_bloom, R.string.pp_desc_bloom),
  Scanlines(R.string.pp_preset_scanlines, R.string.pp_desc_scanlines),
  WhiteBalance(R.string.pp_preset_white_balance, R.string.pp_desc_white_balance),
  Film(R.string.pp_preset_film, R.string.pp_desc_film),
}

/**
 * Builds the ordered list of GLSL shader sources for this preset.
 * Each string is a complete `//!HOOK MAIN` pass ready to write to disk.
 */
fun PostProcessingPreset.buildShaders(p: PostProcessingParams): List<String> =
  when (this) {
    PostProcessingPreset.None -> emptyList()

    PostProcessingPreset.Natural ->
      listOf(
        NaturalColorsShaderBuilder.build(p),
      )

    PostProcessingPreset.Vivid ->
      listOf(
        NaturalColorsShaderBuilder.build(p),
        LevelsShaderBuilder.build(p),
      )

    PostProcessingPreset.Anime ->
      listOf(
        DenoiseShaderBuilder.build(p),
        SharpenShaderBuilder.build(p),
      )

    PostProcessingPreset.Clean ->
      listOf( // Eden: Clean.fxp
        DenoiseShaderBuilder.build(p),
        DebandShaderBuilder.build(p),
        SharpenShaderBuilder.build(p),
      )

    PostProcessingPreset.CelShaded ->
      listOf(
        CelShadingShaderBuilder.build(p),
      )

    PostProcessingPreset.Cartoon ->
      listOf(
        CartoonSoftShaderBuilder.build(p),
      )

    PostProcessingPreset.Cinematic ->
      listOf( // Eden: Cinematic.fxp
        BloomShaderBuilder.build(p),
        FilmicCurveShaderBuilder.build(p),
        SplitToningShaderBuilder.build(p),
        FilmGrainShaderBuilder.build(p),
        VignetteShaderBuilder.build(p),
      )

    PostProcessingPreset.Dreamy ->
      listOf( // Eden: Dreamy.fxp
        BlurShaderBuilder.build(p),
        BloomShaderBuilder.build(p),
      )

    PostProcessingPreset.Retro ->
      listOf( // Eden: Retro.fxp
        ChromaticAberrationShaderBuilder.build(p),
        CRTShaderBuilder.build(p),
        VignetteShaderBuilder.build(p),
      )

    PostProcessingPreset.Bloom ->
      listOf(
        BloomShaderBuilder.build(p),
      )

    PostProcessingPreset.Scanlines ->
      listOf(
        ScanlinesShaderBuilder.build(p),
      )

    PostProcessingPreset.WhiteBalance ->
      listOf(
        WhiteBalanceShaderBuilder.build(p),
      )

    PostProcessingPreset.Film ->
      listOf(
        FilmGrainShaderBuilder.build(p),
        VignetteShaderBuilder.build(p),
      )
  }
