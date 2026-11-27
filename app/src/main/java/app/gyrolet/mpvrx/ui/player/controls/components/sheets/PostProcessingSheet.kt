/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.presentation.components.SliderItem
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.PostProcessingParams
import app.gyrolet.mpvrx.ui.player.PostProcessingPreset
import app.gyrolet.mpvrx.ui.player.components.expressive.SectionHeader
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.spacing
import java.util.Locale

private fun formatVal(v: Float): String = String.format(Locale.US, "%.2f", v)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PostProcessingSheet(
  viewModel: PlayerViewModel,
  onDismissRequest: () -> Unit,
) {
  val preset by viewModel.postProcessingPreset.collectAsState()
  val params by viewModel.postProcessingParams.collectAsState()

  PlayerSheet(
    onDismissRequest = onDismissRequest,
    title = stringResource(R.string.ui_post_processing),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // ── Preset Chips ─────────────────────────────────────────────────
      androidx.compose.foundation.layout.FlowRow(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        PostProcessingPreset.entries.forEach { entry ->
          FilterChip(
            selected = preset == entry,
            onClick = { viewModel.setPostProcessingPreset(entry) },
            label = { Text(stringResource(entry.displayNameRes)) },
            colors =
              FilterChipDefaults.filterChipColors(
                containerColor = playerSheetSelectionColor(false),
                selectedContainerColor = playerSheetSelectionColor(true),
                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
              ),
          )
        }
      }

      // ── Preset Description ───────────────────────────────────────────
      if (preset.descriptionRes != 0) {
        Text(
          text = stringResource(preset.descriptionRes),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium, vertical = 4.dp),
        )
      }

      HorizontalDivider(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
      )

      // ── Parameter Sections based on active preset ────────────────────
      when (preset) {
        PostProcessingPreset.None -> {
          // No parameters to tune
        }

        PostProcessingPreset.Natural -> {
          NaturalColorsSection(params, viewModel::updatePostProcessingParams)
        }

        PostProcessingPreset.Vivid -> {
          NaturalColorsSection(params, viewModel::updatePostProcessingParams)
          LevelsSection(params, viewModel::updatePostProcessingParams)
        }

        PostProcessingPreset.Anime -> {
          DenoiseSection(params, viewModel::updatePostProcessingParams)
          SharpenSection(params, viewModel::updatePostProcessingParams)
        }

        PostProcessingPreset.Clean -> {
          DenoiseSection(params, viewModel::updatePostProcessingParams)
          DebandSection(params, viewModel::updatePostProcessingParams)
          SharpenSection(params, viewModel::updatePostProcessingParams)
        }

        PostProcessingPreset.CelShaded -> {
          CelShadingSection(params, viewModel::updatePostProcessingParams)
        }

        PostProcessingPreset.Cartoon -> {
          CartoonSoftSection(params, viewModel::updatePostProcessingParams)
        }

        PostProcessingPreset.Cinematic -> {
          BloomSection(params, viewModel::updatePostProcessingParams)
          FilmicCurveSection(params, viewModel::updatePostProcessingParams)
          SplitToningSection(params, viewModel::updatePostProcessingParams)
          FilmGrainSection(params, viewModel::updatePostProcessingParams)
          VignetteSection(params, viewModel::updatePostProcessingParams)
        }

        PostProcessingPreset.Dreamy -> {
          BlurSection(params, viewModel::updatePostProcessingParams)
          BloomSection(params, viewModel::updatePostProcessingParams)
        }

        PostProcessingPreset.Retro -> {
          ChromaticAberrationSection(params, viewModel::updatePostProcessingParams)
          CrtSection(params, viewModel::updatePostProcessingParams)
          VignetteSection(params, viewModel::updatePostProcessingParams)
        }

        PostProcessingPreset.Bloom -> {
          BloomSection(params, viewModel::updatePostProcessingParams)
        }

        PostProcessingPreset.Scanlines -> {
          ScanlinesSection(params, viewModel::updatePostProcessingParams)
        }

        PostProcessingPreset.WhiteBalance -> {
          WhiteBalanceSection(params, viewModel::updatePostProcessingParams)
        }

        PostProcessingPreset.Film -> {
          FilmGrainSection(params, viewModel::updatePostProcessingParams)
          VignetteSection(params, viewModel::updatePostProcessingParams)
        }
      }

      // ── Reset Defaults Button ────────────────────────────────────────
      if (preset != PostProcessingPreset.None) {
        Spacer(modifier = Modifier.height(8.dp))
        FilledTonalButton(
          onClick = { viewModel.resetPostProcessingParams() },
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = MaterialTheme.spacing.medium),
          colors = ButtonDefaults.filledTonalButtonColors(),
        ) {
          Text(text = stringResource(R.string.generic_reset), fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(8.dp))
      }
    }
  }
}

// ── Collapsible Container ──────────────────────────────────────────────────

@Composable
private fun CollapsibleSection(
  title: String,
  initiallyExpanded: Boolean = true,
  content: @Composable () -> Unit,
) {
  var expanded by remember { mutableStateOf(initiallyExpanded) }
  SectionHeader(
    title = title,
    isExpanded = expanded,
    onClick = { expanded = !expanded },
  )
  AnimatedVisibility(
    visible = expanded,
    enter =
      expandVertically(
        animationSpec =
          spring(
            dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
            stiffness = AppMotion.Spatial.Expressive.stiffness,
          ),
      ) + fadeIn(animationSpec = spring(stiffness = AppMotion.Effect.Alpha.stiffness)),
    exit =
      shrinkVertically(
        animationSpec =
          spring(
            dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
            stiffness = AppMotion.Spatial.Expressive.stiffness,
          ),
      ) + fadeOut(animationSpec = spring(stiffness = AppMotion.Effect.Alpha.stiffness)),
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
      content()
    }
  }
}

// ── Sections ───────────────────────────────────────────────────────────────

@Composable
private fun NaturalColorsSection(
  params: PostProcessingParams,
  onUpdate: (PostProcessingParams) -> Unit,
) {
  CollapsibleSection(title = "Natural Colours") {
    SliderItem(
      label = "Luma Curve",
      value = params.naturalLuma,
      valueText = formatVal(params.naturalLuma),
      onChange = { onUpdate(params.copy(naturalLuma = it)) },
      min = 0.5f,
      max = 2.0f,
    )
    SliderItem(
      label = "Chroma Gain",
      value = params.naturalChroma,
      valueText = formatVal(params.naturalChroma),
      onChange = { onUpdate(params.copy(naturalChroma = it)) },
      min = 0.0f,
      max = 2.0f,
    )
  }
}

@Composable
private fun LevelsSection(
  params: PostProcessingParams,
  onUpdate: (PostProcessingParams) -> Unit,
) {
  CollapsibleSection(title = "Levels") {
    SliderItem(
      label = "Input Black",
      value = params.levelsInputBlack,
      valueText = formatVal(params.levelsInputBlack),
      onChange = { onUpdate(params.copy(levelsInputBlack = it)) },
      min = 0.0f,
      max = 0.5f,
    )
    SliderItem(
      label = "Input White",
      value = params.levelsInputWhite,
      valueText = formatVal(params.levelsInputWhite),
      onChange = { onUpdate(params.copy(levelsInputWhite = it)) },
      min = 0.5f,
      max = 1.0f,
    )
    SliderItem(
      label = "Gamma",
      value = params.levelsGamma,
      valueText = formatVal(params.levelsGamma),
      onChange = { onUpdate(params.copy(levelsGamma = it)) },
      min = 0.2f,
      max = 3.0f,
    )
    SliderItem(
      label = "Output Black",
      value = params.levelsOutputBlack,
      valueText = formatVal(params.levelsOutputBlack),
      onChange = { onUpdate(params.copy(levelsOutputBlack = it)) },
      min = 0.0f,
      max = 0.5f,
    )
    SliderItem(
      label = "Output White",
      value = params.levelsOutputWhite,
      valueText = formatVal(params.levelsOutputWhite),
      onChange = { onUpdate(params.copy(levelsOutputWhite = it)) },
      min = 0.5f,
      max = 1.0f,
    )
  }
}

@Composable
private fun SharpenSection(
  params: PostProcessingParams,
  onUpdate: (PostProcessingParams) -> Unit,
) {
  CollapsibleSection(title = "Sharpen") {
    SliderItem(
      label = "Amount",
      value = params.sharpenAmount,
      valueText = formatVal(params.sharpenAmount),
      onChange = { onUpdate(params.copy(sharpenAmount = it)) },
      min = 0.0f,
      max = 3.0f,
    )
  }
}

@Composable
private fun BloomSection(
  params: PostProcessingParams,
  onUpdate: (PostProcessingParams) -> Unit,
) {
  CollapsibleSection(title = "Bloom") {
    SliderItem(
      label = "Radius",
      value = params.bloomRadius,
      valueText = formatVal(params.bloomRadius),
      onChange = { onUpdate(params.copy(bloomRadius = it)) },
      min = 0.0f,
      max = 4.0f,
    )
    SliderItem(
      label = "Amount",
      value = params.bloomAmount,
      valueText = formatVal(params.bloomAmount),
      onChange = { onUpdate(params.copy(bloomAmount = it)) },
      min = 0.0f,
      max = 2.0f,
    )
  }
}

@Composable
private fun DenoiseSection(
  params: PostProcessingParams,
  onUpdate: (PostProcessingParams) -> Unit,
) {
  CollapsibleSection(title = "Denoise") {
    SliderItem(
      label = "Strength",
      value = params.denoiseStrength,
      valueText = formatVal(params.denoiseStrength),
      onChange = { onUpdate(params.copy(denoiseStrength = it)) },
      min = 0.01f,
      max = 0.5f,
    )
    SliderItem(
      label = "Radius",
      value = params.denoiseRadius,
      valueText = formatVal(params.denoiseRadius),
      onChange = { onUpdate(params.copy(denoiseRadius = it)) },
      min = 0.3f,
      max = 2.0f,
    )
    SliderItem(
      label = "Curve",
      value = params.denoiseCurve,
      valueText = formatVal(params.denoiseCurve),
      onChange = { onUpdate(params.copy(denoiseCurve = it)) },
      min = 0.0f,
      max = 2.0f,
    )
  }
}

@Composable
private fun DebandSection(
  params: PostProcessingParams,
  onUpdate: (PostProcessingParams) -> Unit,
) {
  CollapsibleSection(title = "Deband") {
    SliderItem(
      label = "Threshold",
      value = params.debandThreshold,
      valueText = formatVal(params.debandThreshold),
      onChange = { onUpdate(params.copy(debandThreshold = it)) },
      min = 0.002f,
      max = 0.05f,
    )
    SliderItem(
      label = "Radius",
      value = params.debandRadius,
      valueText = formatVal(params.debandRadius),
      onChange = { onUpdate(params.copy(debandRadius = it)) },
      min = 1.0f,
      max = 32.0f,
    )
    SliderItem(
      label = "Grain",
      value = params.debandGrain,
      valueText = formatVal(params.debandGrain),
      onChange = { onUpdate(params.copy(debandGrain = it)) },
      min = 0.0f,
      max = 0.02f,
    )
  }
}

@Composable
private fun CelShadingSection(
  params: PostProcessingParams,
  onUpdate: (PostProcessingParams) -> Unit,
) {
  CollapsibleSection(title = "Cel Shading") {
    SliderItem(
      label = "Bands",
      value = params.celBands,
      valueText = formatVal(params.celBands),
      onChange = { onUpdate(params.copy(celBands = it)) },
      min = 2.0f,
      max = 16.0f,
    )
    SliderItem(
      label = "Band Contrast",
      value = params.celBandContrast,
      valueText = formatVal(params.celBandContrast),
      onChange = { onUpdate(params.copy(celBandContrast = it)) },
      min = 0.0f,
      max = 1.0f,
    )
    SliderItem(
      label = "Band Edge",
      value = params.celBandEdge,
      valueText = formatVal(params.celBandEdge),
      onChange = { onUpdate(params.copy(celBandEdge = it)) },
      min = 0.01f,
      max = 0.5f,
    )
    SliderItem(
      label = "Detail",
      value = params.celDetail,
      valueText = formatVal(params.celDetail),
      onChange = { onUpdate(params.copy(celDetail = it)) },
      min = 0.0f,
      max = 1.0f,
    )
    SliderItem(
      label = "Outline Strength",
      value = params.celOutlineStrength,
      valueText = formatVal(params.celOutlineStrength),
      onChange = { onUpdate(params.copy(celOutlineStrength = it)) },
      min = 0.0f,
      max = 2.0f,
    )
    SliderItem(
      label = "Outline Threshold",
      value = params.celOutlineThreshold,
      valueText = formatVal(params.celOutlineThreshold),
      onChange = { onUpdate(params.copy(celOutlineThreshold = it)) },
      min = 0.01f,
      max = 0.5f,
    )
    SliderItem(
      label = "Saturation",
      value = params.celSaturation,
      valueText = formatVal(params.celSaturation),
      onChange = { onUpdate(params.copy(celSaturation = it)) },
      min = 0.5f,
      max = 2.0f,
    )
  }
}

@Composable
private fun CartoonSoftSection(
  params: PostProcessingParams,
  onUpdate: (PostProcessingParams) -> Unit,
) {
  CollapsibleSection(title = "Cartoon Soft") {
    SliderItem(
      label = "Edge Strength",
      value = params.cartoonSoftEdgeStrength,
      valueText = formatVal(params.cartoonSoftEdgeStrength),
      onChange = { onUpdate(params.copy(cartoonSoftEdgeStrength = it)) },
      min = 0.0f,
      max = 2.0f,
    )
    SliderItem(
      label = "Shadow Guard",
      value = params.cartoonSoftShadowGuard,
      valueText = formatVal(params.cartoonSoftShadowGuard),
      onChange = { onUpdate(params.copy(cartoonSoftShadowGuard = it)) },
      min = 0.0f,
      max = 1.0f,
    )
    SliderItem(
      label = "Levels",
      value = params.cartoonSoftLevels,
      valueText = formatVal(params.cartoonSoftLevels),
      onChange = { onUpdate(params.copy(cartoonSoftLevels = it)) },
      min = 2.0f,
      max = 16.0f,
    )
    SliderItem(
      label = "Smoothing",
      value = params.cartoonSoftSmoothing,
      valueText = formatVal(params.cartoonSoftSmoothing),
      onChange = { onUpdate(params.copy(cartoonSoftSmoothing = it)) },
      min = 0.0f,
      max = 1.0f,
    )
    SliderItem(
      label = "Saturation",
      value = params.cartoonSoftSaturation,
      valueText = formatVal(params.cartoonSoftSaturation),
      onChange = { onUpdate(params.copy(cartoonSoftSaturation = it)) },
      min = 0.5f,
      max = 2.0f,
    )
  }
}

@Composable
private fun FilmicCurveSection(
  params: PostProcessingParams,
  onUpdate: (PostProcessingParams) -> Unit,
) {
  CollapsibleSection(title = "Filmic Curve") {
    SliderItem(
      label = "Exposure",
      value = params.filmicExposure,
      valueText = formatVal(params.filmicExposure),
      onChange = { onUpdate(params.copy(filmicExposure = it)) },
      min = 0.2f,
      max = 3.0f,
    )
    SliderItem(
      label = "Toe",
      value = params.filmicToe,
      valueText = formatVal(params.filmicToe),
      onChange = { onUpdate(params.copy(filmicToe = it)) },
      min = 0.5f,
      max = 3.0f,
    )
    SliderItem(
      label = "Shoulder",
      value = params.filmicShoulder,
      valueText = formatVal(params.filmicShoulder),
      onChange = { onUpdate(params.copy(filmicShoulder = it)) },
      min = 0.5f,
      max = 3.0f,
    )
    SliderItem(
      label = "Amount",
      value = params.filmicAmount,
      valueText = formatVal(params.filmicAmount),
      onChange = { onUpdate(params.copy(filmicAmount = it)) },
      min = 0.0f,
      max = 1.0f,
    )
  }
}

@Composable
private fun SplitToningSection(
  params: PostProcessingParams,
  onUpdate: (PostProcessingParams) -> Unit,
) {
  CollapsibleSection(title = "Split Toning") {
    SliderItem(
      label = "Shadow Hue",
      value = params.splitShadowHue,
      valueText = formatVal(params.splitShadowHue),
      onChange = { onUpdate(params.copy(splitShadowHue = it)) },
      min = 0.0f,
      max = 360.0f,
    )
    SliderItem(
      label = "Shadow Strength",
      value = params.splitShadowStrength,
      valueText = formatVal(params.splitShadowStrength),
      onChange = { onUpdate(params.copy(splitShadowStrength = it)) },
      min = 0.0f,
      max = 1.0f,
    )
    SliderItem(
      label = "Highlight Hue",
      value = params.splitHighlightHue,
      valueText = formatVal(params.splitHighlightHue),
      onChange = { onUpdate(params.copy(splitHighlightHue = it)) },
      min = 0.0f,
      max = 360.0f,
    )
    SliderItem(
      label = "Highlight Strength",
      value = params.splitHighlightStrength,
      valueText = formatVal(params.splitHighlightStrength),
      onChange = { onUpdate(params.copy(splitHighlightStrength = it)) },
      min = 0.0f,
      max = 1.0f,
    )
    SliderItem(
      label = "Balance",
      value = params.splitBalance,
      valueText = formatVal(params.splitBalance),
      onChange = { onUpdate(params.copy(splitBalance = it)) },
      min = -1.0f,
      max = 1.0f,
    )
  }
}

@Composable
private fun FilmGrainSection(
  params: PostProcessingParams,
  onUpdate: (PostProcessingParams) -> Unit,
) {
  CollapsibleSection(title = "Film Grain") {
    SliderItem(
      label = "Intensity",
      value = params.grainIntensity,
      valueText = formatVal(params.grainIntensity),
      onChange = { onUpdate(params.copy(grainIntensity = it)) },
      min = 0.0f,
      max = 0.2f,
    )
    SliderItem(
      label = "Size",
      value = params.grainSize,
      valueText = formatVal(params.grainSize),
      onChange = { onUpdate(params.copy(grainSize = it)) },
      min = 1.0f,
      max = 4.0f,
    )
  }
}

@Composable
private fun VignetteSection(
  params: PostProcessingParams,
  onUpdate: (PostProcessingParams) -> Unit,
) {
  CollapsibleSection(title = "Vignette") {
    SliderItem(
      label = "Strength",
      value = params.vignetteStrength,
      valueText = formatVal(params.vignetteStrength),
      onChange = { onUpdate(params.copy(vignetteStrength = it)) },
      min = 0.0f,
      max = 2.0f,
    )
    SliderItem(
      label = "Aspect Ratio",
      value = params.vignetteAspect,
      valueText = formatVal(params.vignetteAspect),
      onChange = { onUpdate(params.copy(vignetteAspect = it)) },
      min = 0.5f,
      max = 2.0f,
    )
  }
}

@Composable
private fun BlurSection(
  params: PostProcessingParams,
  onUpdate: (PostProcessingParams) -> Unit,
) {
  CollapsibleSection(title = "Blur") {
    SliderItem(
      label = "Radius",
      value = params.blurRadius,
      valueText = formatVal(params.blurRadius),
      onChange = { onUpdate(params.copy(blurRadius = it)) },
      min = 1.0f,
      max = 12.0f,
    )
    SliderItem(
      label = "Strength",
      value = params.blurStrength,
      valueText = formatVal(params.blurStrength),
      onChange = { onUpdate(params.copy(blurStrength = it)) },
      min = 0.0f,
      max = 1.0f,
    )
    SliderItem(
      label = "Centre Focus Size",
      value = params.blurFocusSize,
      valueText = formatVal(params.blurFocusSize),
      onChange = { onUpdate(params.copy(blurFocusSize = it)) },
      min = 0.0f,
      max = 1.0f,
    )
    SliderItem(
      label = "Focus Softness",
      value = params.blurFocusSoftness,
      valueText = formatVal(params.blurFocusSoftness),
      onChange = { onUpdate(params.copy(blurFocusSoftness = it)) },
      min = 0.05f,
      max = 1.0f,
    )
  }
}

@Composable
private fun ChromaticAberrationSection(
  params: PostProcessingParams,
  onUpdate: (PostProcessingParams) -> Unit,
) {
  CollapsibleSection(title = "Chromatic Aberration") {
    SliderItem(
      label = "Strength",
      value = params.caStrength,
      valueText = formatVal(params.caStrength),
      onChange = { onUpdate(params.copy(caStrength = it)) },
      min = 0.0f,
      max = 8.0f,
    )
    SliderItem(
      label = "Falloff",
      value = params.caFalloff,
      valueText = formatVal(params.caFalloff),
      onChange = { onUpdate(params.copy(caFalloff = it)) },
      min = 1.0f,
      max = 4.0f,
    )
  }
}

@Composable
private fun CrtSection(
  params: PostProcessingParams,
  onUpdate: (PostProcessingParams) -> Unit,
) {
  CollapsibleSection(title = "CRT") {
    SliderItem(
      label = "Density",
      value = params.crtDensity,
      valueText = formatVal(params.crtDensity),
      onChange = { onUpdate(params.copy(crtDensity = it)) },
      min = 100.0f,
      max = 600.0f,
    )
    SliderItem(
      label = "Roll Speed",
      value = params.crtRollSpeed,
      valueText = formatVal(params.crtRollSpeed),
      onChange = { onUpdate(params.copy(crtRollSpeed = it)) },
      min = 0.0f,
      max = 5.0f,
    )
    SliderItem(
      label = "Bleed",
      value = params.crtBleed,
      valueText = formatVal(params.crtBleed),
      onChange = { onUpdate(params.copy(crtBleed = it)) },
      min = 0.0f,
      max = 3.0f,
    )
  }
}

@Composable
private fun ScanlinesSection(
  params: PostProcessingParams,
  onUpdate: (PostProcessingParams) -> Unit,
) {
  CollapsibleSection(title = "Scanlines") {
    SliderItem(
      label = "Density",
      value = params.scanlinesDensity,
      valueText = formatVal(params.scanlinesDensity),
      onChange = { onUpdate(params.copy(scanlinesDensity = it)) },
      min = 60.0f,
      max = 720.0f,
    )
    SliderItem(
      label = "Intensity",
      value = params.scanlinesIntensity,
      valueText = formatVal(params.scanlinesIntensity),
      onChange = { onUpdate(params.copy(scanlinesIntensity = it)) },
      min = 0.0f,
      max = 1.0f,
    )
    SliderItem(
      label = "Phosphor Tint",
      value = params.scanlinesTint,
      valueText = formatVal(params.scanlinesTint),
      onChange = { onUpdate(params.copy(scanlinesTint = it)) },
      min = 0.0f,
      max = 1.0f,
    )
  }
}

@Composable
private fun WhiteBalanceSection(
  params: PostProcessingParams,
  onUpdate: (PostProcessingParams) -> Unit,
) {
  CollapsibleSection(title = "White Balance") {
    SliderItem(
      label = "Temperature",
      value = params.wbTemperature,
      valueText = formatVal(params.wbTemperature),
      onChange = { onUpdate(params.copy(wbTemperature = it)) },
      min = -100.0f,
      max = 100.0f,
    )
    SliderItem(
      label = "Tint",
      value = params.wbTint,
      valueText = formatVal(params.wbTint),
      onChange = { onUpdate(params.copy(wbTint = it)) },
      min = -100.0f,
      max = 100.0f,
    )
  }
}
