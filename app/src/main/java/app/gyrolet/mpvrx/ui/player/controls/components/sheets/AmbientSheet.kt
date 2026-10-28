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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.presentation.components.SliderItem
import app.gyrolet.mpvrx.ui.components.themedSegmentedButtonColors
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.AmbientShaderPresets
import app.gyrolet.mpvrx.ui.player.AmbientStyle
import app.gyrolet.mpvrx.ui.player.FxAmbientPresets
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.components.expressive.SectionHeader
import app.gyrolet.mpvrx.ui.player.matchesFxPreset
import app.gyrolet.mpvrx.ui.player.matchesGlowPreset
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.icons.Icon as AppSymbolIcon

@Composable
fun AmbientSheet(
  viewModel: PlayerViewModel,
  onDismissRequest: () -> Unit,
) {
  // ── Collect all state flows ──────────────────────────────────────────────
  val ambientStyle by viewModel.ambientStyle.collectAsState()
  val blurSamples by viewModel.ambientBlurSamples.collectAsState()
  val maxRadius by viewModel.ambientMaxRadius.collectAsState()
  val glowIntensity by viewModel.ambientGlowIntensity.collectAsState()
  val satBoost by viewModel.ambientSatBoost.collectAsState()
  val vignetteStrength by viewModel.ambientVignetteStrength.collectAsState()
  val warmth by viewModel.ambientWarmth.collectAsState()
  val fadeCurve by viewModel.ambientFadeCurve.collectAsState()
  val opacity by viewModel.ambientOpacity.collectAsState()
  val glowEdgeBlend by viewModel.ambientGlowEdgeBlend.collectAsState()
  val youTubeEdgeBlend by viewModel.ambientYouTubeEdgeBlend.collectAsState()
  val edgeBlend = when (ambientStyle) {
    AmbientStyle.Glow    -> glowEdgeBlend
    AmbientStyle.YouTube -> youTubeEdgeBlend
    else                 -> 0f  // Cinema/Echo/Mirror: no exposed edge-blend parameter
  }
  val isFast =
    remember(
      ambientStyle, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity,
    ) {
      when (ambientStyle) {
        AmbientStyle.Glow -> matchesGlowPreset(AmbientShaderPresets.glowFast, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity)
        AmbientStyle.Ambilight -> matchesFxPreset(FxAmbientPresets.ambilightFast, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity)
        AmbientStyle.Cinema -> matchesFxPreset(FxAmbientPresets.cinemaFast, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity)
        AmbientStyle.Echo -> matchesFxPreset(FxAmbientPresets.echoFast, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity)
        AmbientStyle.Mirror -> matchesFxPreset(FxAmbientPresets.mirrorFast, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity)
        AmbientStyle.YouTube, AmbientStyle.YouTube2 -> false
      }
    }
  val isBalanced =
    remember(
      ambientStyle, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity,
    ) {
      when (ambientStyle) {
        AmbientStyle.Glow -> matchesGlowPreset(AmbientShaderPresets.glowBalanced, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity)
        AmbientStyle.Ambilight -> matchesFxPreset(FxAmbientPresets.ambilightBalanced, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity)
        AmbientStyle.Cinema -> matchesFxPreset(FxAmbientPresets.cinemaBalanced, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity)
        AmbientStyle.Echo -> matchesFxPreset(FxAmbientPresets.echoBalanced, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity)
        AmbientStyle.Mirror -> matchesFxPreset(FxAmbientPresets.mirrorBalanced, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity)
        AmbientStyle.YouTube, AmbientStyle.YouTube2 -> false
      }
    }
  val isHQ =
    remember(
      ambientStyle, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity,
    ) {
      when (ambientStyle) {
        AmbientStyle.Glow -> matchesGlowPreset(AmbientShaderPresets.glowHighQuality, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity)
        AmbientStyle.Ambilight -> matchesFxPreset(FxAmbientPresets.ambilightHighQuality, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity)
        AmbientStyle.Cinema -> matchesFxPreset(FxAmbientPresets.cinemaHighQuality, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity)
        AmbientStyle.Echo -> matchesFxPreset(FxAmbientPresets.echoHighQuality, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity)
        AmbientStyle.Mirror -> matchesFxPreset(FxAmbientPresets.mirrorHighQuality, blurSamples, maxRadius, glowIntensity, satBoost, vignetteStrength, warmth, fadeCurve, opacity)
        AmbientStyle.YouTube, AmbientStyle.YouTube2 -> false
      }
    }
  PlayerSheet(
    onDismissRequest = onDismissRequest,
    title = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_ambience_mode),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
      ) {
        SingleChoiceSegmentedButtonRow {
          AmbientStyle.entries.forEachIndexed { index, style ->
            SegmentedButton(
              selected = ambientStyle == style,
              onClick = { viewModel.setAmbientStyle(style) },
              shape = SegmentedButtonDefaults.itemShape(index, AmbientStyle.entries.size),
              colors = themedSegmentedButtonColors(),
            ) {
              Text(text = stringResource(style.titleRes))
            }
          }
        }
      }

      HorizontalDivider(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
      )

      // Edge-blend is only relevant for modes where it is a tunable parameter.
      // Cinema, Echo, and Mirror blend the transition internally at fixed values.
      if (ambientStyle == AmbientStyle.Glow || ambientStyle == AmbientStyle.YouTube) {
        SliderItem(
          label = stringResource(R.string.ambient_edge_blend),
          valueText = "%.1f%%".format(edgeBlend * 100f),
          value = edgeBlend,
          onChange = { viewModel.setAmbientEdgeBlend(ambientStyle, it) },
          min = 0f,
          max = 0.1f,
          steps = 19,
          icon = {
            AppSymbolIcon(
              imageVector = Icons.RoundedFilled.Gradient,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(20.dp),
            )
          },
        )
      }

      if (ambientStyle != AmbientStyle.YouTube && ambientStyle != AmbientStyle.YouTube2) {
      // ── Quality Presets ──────────────────────────────────────────────
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.medium),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ExpressivePresetButton(
          label = "Fast",
          selected = isFast,
          onClick = { viewModel.applyAmbientProfileFast() },
        )
        ExpressivePresetButton(
          label = "Balanced",
          selected = isBalanced,
          onClick = { viewModel.applyAmbientProfileBalanced() },
        )
        ExpressivePresetButton(
          label = "HQ",
          selected = isHQ,
          onClick = { viewModel.applyAmbientProfileHighQuality() },
        )
      }

      HorizontalDivider(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
      )

      // ── Section: Mode Effect ─────────────────────────────────────────
      var effectExpanded by remember { mutableStateOf(true) }
      SectionHeader(
        title = stringResource(ambientStyle.titleRes),
        isExpanded = effectExpanded,
        onClick = { effectExpanded = !effectExpanded },
      )
      AnimatedVisibility(
        visible = effectExpanded,
        enter =
          expandVertically(
            animationSpec =
              spring(
                dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
                stiffness = AppMotion.Spatial.Expressive.stiffness,
              ),
          ) +
            fadeIn(animationSpec = spring(stiffness = AppMotion.Effect.Alpha.stiffness)),
        exit =
          shrinkVertically(
            animationSpec =
              spring(
                dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
                stiffness = AppMotion.Spatial.Expressive.stiffness,
              ),
          ) +
            fadeOut(animationSpec = spring(stiffness = AppMotion.Effect.Alpha.stiffness)),
      ) {
        Column(
          verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
          val minSamples = if (ambientStyle == AmbientStyle.Mirror || ambientStyle == AmbientStyle.Echo) 3 else 4
          val maxSamples = when (ambientStyle) {
            AmbientStyle.Glow -> 64
            AmbientStyle.Ambilight -> 32
            AmbientStyle.Cinema -> 32
            AmbientStyle.Echo -> 15
            AmbientStyle.Mirror -> 9
            AmbientStyle.YouTube, AmbientStyle.YouTube2 -> 32
          }
          SliderItem(
            label = "Blur Samples",
            valueText = "$blurSamples",
            value = blurSamples,
            onChange = { viewModel.updateAmbientParams(blurSamples = it) },
            min = minSamples,
            max = maxSamples,
            icon = {
              AppSymbolIcon(
                imageVector = Icons.RoundedFilled.BlurOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
              )
            },
          )

          SliderItem(
            label = "Spread",
            valueText = "%.2f".format(maxRadius),
            value = maxRadius,
            onChange = { viewModel.updateAmbientParams(maxRadius = it) },
            min = 0.02f,
            max = if (ambientStyle == AmbientStyle.Glow) 0.80f else 0.40f,
            steps = if (ambientStyle == AmbientStyle.Glow) 75 else 38,
            icon = {
              AppSymbolIcon(
                imageVector = Icons.RoundedFilled.Gradient,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
              )
            },
          )

          SliderItem(
            label = if (ambientStyle == AmbientStyle.Glow) "Glow Intensity" else "Intensity",
            valueText = "%.1f".format(glowIntensity),
            value = glowIntensity,
            onChange = { viewModel.updateAmbientParams(glowIntensity = it) },
            min = 0.2f,
            max = 3.0f,
            steps = 28,
            icon = {
              AppSymbolIcon(
                imageVector = Icons.RoundedFilled.Brightness6,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
              )
            },
          )

          SliderItem(
            label = "Fade Curve",
            valueText = "%.1f".format(fadeCurve),
            value = fadeCurve,
            onChange = { viewModel.updateAmbientParams(fadeCurve = it) },
            min = 0.5f,
            max = 3.0f,
            steps = 25,
            icon = {
              AppSymbolIcon(
                imageVector = Icons.RoundedFilled.WbSunny,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
              )
            },
          )
        }
      }

      HorizontalDivider(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
      )

      // ── Section: Color ───────────────────────────────────────────────
      var colorExpanded by remember { mutableStateOf(true) }
      SectionHeader(
        title = stringResource(R.string.ambient_color),
        isExpanded = colorExpanded,
        onClick = { colorExpanded = !colorExpanded },
      )
      AnimatedVisibility(
        visible = colorExpanded,
        enter =
          expandVertically(
            animationSpec =
              spring(
                dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
                stiffness = AppMotion.Spatial.Expressive.stiffness,
              ),
          ) +
            fadeIn(animationSpec = spring(stiffness = AppMotion.Effect.Alpha.stiffness)),
        exit =
          shrinkVertically(
            animationSpec =
              spring(
                dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
                stiffness = AppMotion.Spatial.Expressive.stiffness,
              ),
          ) +
            fadeOut(animationSpec = spring(stiffness = AppMotion.Effect.Alpha.stiffness)),
      ) {
        Column(
          verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
          SliderItem(
            label = "Saturation",
            valueText = "%.1f".format(satBoost),
            value = satBoost,
            onChange = { viewModel.updateAmbientParams(satBoost = it) },
            min = 0.0f,
            max = 3.0f,
            steps = 30,
            icon = {
              AppSymbolIcon(
                imageVector = Icons.RoundedFilled.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
              )
            },
          )

          SliderItem(
            label = "Warmth",
            valueText = if (warmth == 0f) "0" else "%.2f".format(warmth),
            value = warmth,
            onChange = { viewModel.updateAmbientParams(warmth = it) },
            min = -1.0f,
            max = 1.0f,
            steps = 40,
            icon = {
              AppSymbolIcon(
                imageVector = Icons.RoundedFilled.Thermostat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
              )
            },
          )
        }
      }

      HorizontalDivider(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
      )

      // ── Section: Compositing ─────────────────────────────────────────
      var compositingExpanded by remember { mutableStateOf(true) }
      SectionHeader(
        title = stringResource(R.string.ambient_compositing),
        isExpanded = compositingExpanded,
        onClick = { compositingExpanded = !compositingExpanded },
      )
      AnimatedVisibility(
        visible = compositingExpanded,
        enter =
          expandVertically(
            animationSpec =
              spring(
                dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
                stiffness = AppMotion.Spatial.Expressive.stiffness,
              ),
          ) +
            fadeIn(animationSpec = spring(stiffness = AppMotion.Effect.Alpha.stiffness)),
        exit =
          shrinkVertically(
            animationSpec =
              spring(
                dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
                stiffness = AppMotion.Spatial.Expressive.stiffness,
              ),
          ) +
            fadeOut(animationSpec = spring(stiffness = AppMotion.Effect.Alpha.stiffness)),
      ) {
        Column(
          verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
          SliderItem(
            label = "Opacity",
            valueText = "%.2f".format(opacity),
            value = opacity,
            onChange = { viewModel.updateAmbientParams(opacity = it) },
            min = 0.0f,
            max = 1.0f,
            steps = 20,
            icon = {
              AppSymbolIcon(
                imageVector = Icons.RoundedFilled.Opacity,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
              )
            },
          )

          SliderItem(
            label = "Vignette",
            valueText = "%.1f".format(vignetteStrength),
            value = vignetteStrength,
            onChange = { viewModel.updateAmbientParams(vignetteStrength = it) },
            min = 0.0f,
            max = 1.0f,
            steps = 10,
            icon = {
              AppSymbolIcon(
                imageVector = Icons.RoundedFilled.Vignette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
              )
            },
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))
      } else {
        Text(
          text = stringResource(if (ambientStyle == AmbientStyle.YouTube2) R.string.ambient_youtube_2_auto_hint else R.string.ambient_youtube_auto_hint),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = MaterialTheme.spacing.large, vertical = 8.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
      }
    }
  }
}

// ── Helper: expressive preset button ─────────────────────────────────────────
@Composable
private fun RowScope.ExpressivePresetButton(
  label: String,
  selected: Boolean,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  val targetScale = if (selected) 1.02f else 1.0f
  val scale by androidx.compose.animation.core.animateFloatAsState(
    targetValue = targetScale,
    animationSpec = AppMotion.Spatial.Expressive,
    label = "PresetButtonScale",
  )

  FilledTonalButton(
    onClick = onClick,
    enabled = enabled,
    modifier =
      Modifier
        .weight(1f)
        .graphicsLayer(scaleX = scale, scaleY = scale),
    colors =
      if (selected) {
        ButtonDefaults.filledTonalButtonColors(
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
        )
      } else {
        ButtonDefaults.filledTonalButtonColors()
      },
  ) {
    Text(label, fontWeight = FontWeight.Bold)
  }
}
