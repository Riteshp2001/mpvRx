/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.liquidglass.LocalKyantPlayerBackdrop
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassActive
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassEffects
import app.gyrolet.mpvrx.ui.liquidglass.rememberLiquidGlassSettings
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

fun percentage(
  value: Float,
  range: ClosedFloatingPointRange<Float>,
): Float = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)

private val LevelPillWidth = 52.dp
private val LevelPillHeight = 200.dp
private val LevelPillInset = 16.dp
private val LevelPillIconSize = 24.dp
private val LevelPillLabelHeight = 20.dp

/**
 * Android TV style level indicator: one capsule filled from the bottom with the theme gradient,
 * the value inside at the top and the icon inside at the bottom. Label and icon switch to the
 * fill's content colour once the level rises under them, so they stay readable at any level.
 */
@Composable
private fun LevelPill(
  fraction: Float,
  overflowFraction: Float,
  label: String,
  icon: AppIcon,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val enableLiquidGlass = liquidGlassActive()
  val glassSettings = rememberLiquidGlassSettings()
  // Glass needs the player's sampled video layer. Until it exists (first frames, renderer switches)
  // the pill keeps its regular darker form instead of drawing a glass rim around nothing.
  val glassBackdrop = LocalKyantPlayerBackdrop.current?.takeIf { enableLiquidGlass }
  val density = LocalDensity.current
  val shape = CircleShape
  val fillSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 380f)
  val level by animateFloatAsState(fraction.coerceIn(0f, 1f), fillSpring, label = "level_pill_fill")
  val overflow by animateFloatAsState(overflowFraction.coerceIn(0f, 1f), fillSpring, label = "level_pill_overflow")
  val covered = maxOf(level, overflow)
  val iconCovered = covered >= (LevelPillInset + LevelPillIconSize / 2) / LevelPillHeight
  val labelCovered = covered >= 1f - (LevelPillInset + LevelPillLabelHeight / 2) / LevelPillHeight
  val overflowOnTop = overflow >= level
  val iconTint by animateColorAsState(
    when {
      !iconCovered -> Color.White
      overflowOnTop -> colors.onError
      else -> colors.onPrimary
    },
    label = "level_pill_icon",
  )
  val labelColor by animateColorAsState(
    when {
      !labelCovered -> Color.White
      overflowOnTop -> colors.onErrorContainer
      else -> colors.onPrimaryContainer
    },
    label = "level_pill_label",
  )
  // Same gradient as before: primaryContainer at the top of the fill, primary at its base.
  val fillBrush =
    remember(colors.primaryContainer, colors.primary) {
      Brush.verticalGradient(listOf(colors.primaryContainer, colors.primary))
    }
  val overflowBrush =
    remember(colors.errorContainer, colors.error) {
      Brush.verticalGradient(listOf(colors.errorContainer, colors.error))
    }

  Box(
    modifier =
      modifier
        .size(LevelPillWidth, LevelPillHeight)
        .then(
          if (glassBackdrop != null) {
            Modifier.drawBackdrop(
              backdrop = glassBackdrop,
              shape = { shape },
              effects = {
                liquidGlassEffects(
                  glassSettings,
                  with(density) { 16.dp.toPx() },
                  with(density) { 16.dp.toPx() },
                  with(density) { 24.dp.toPx() },
                )
              },
              highlight = { glassSettings.highlight(Highlight.Default) },
              shadow = { Shadow(color = Color.Black.copy(alpha = 0.25f), radius = 12.dp) },
              innerShadow = { InnerShadow(color = Color.White.copy(alpha = 0.15f), radius = 2.dp) },
            )
          } else {
            Modifier
          },
        ).clip(shape)
        .background(Color.Black.copy(alpha = if (glassBackdrop != null) 0.25f else 0.45f))
        .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
  ) {
    Box(
      Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .fillMaxHeight(level)
        .background(fillBrush),
    )
    if (overflow > 0f) {
      Box(
        Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .fillMaxHeight(overflow)
          .background(overflowBrush),
      )
    }
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge.withTabularDigits(),
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
      color = labelColor,
      maxLines = 1,
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .padding(top = LevelPillInset)
          .height(LevelPillLabelHeight)
          .stableNumericWidth(label),
    )
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = iconTint,
      modifier =
        Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = LevelPillInset)
          .size(LevelPillIconSize),
    )
  }
}

@Composable
fun BrightnessSlider(
  brightness: Float,
  positiveRange: ClosedFloatingPointRange<Float>,
  negativeRange: ClosedFloatingPointRange<Float>,
  modifier: Modifier = Modifier,
) {
  val coercedBrightness = brightness.coerceIn(-negativeRange.endInclusive, positiveRange.endInclusive)
  val percentInt = (coercedBrightness * 100).toInt()
  val percentText = remember(percentInt) { "$percentInt%" }
  val level = percentage(coercedBrightness.coerceIn(0f, positiveRange.endInclusive), positiveRange)
  LevelPill(
    fraction = level,
    overflowFraction = percentage((-coercedBrightness).coerceIn(0f, negativeRange.endInclusive), negativeRange),
    label = percentText,
    icon =
      when {
        coercedBrightness < 0 -> Icons.RoundedFilled.Brightness6
        level <= 0.3f -> Icons.RoundedFilled.BrightnessLow
        level <= 0.6f -> Icons.RoundedFilled.BrightnessMedium
        else -> Icons.RoundedFilled.BrightnessHigh
      },
    modifier = modifier,
  )
}

@Composable
fun VolumeSlider(
  volume: Int,
  volumePercentage: Int,
  mpvVolume: Int,
  range: ClosedRange<Int>,
  boostRange: ClosedRange<Int>?,
  modifier: Modifier = Modifier,
  displayAsPercentage: Boolean = false,
) {
  val volumePercent = volumePercentage.coerceIn(0, 100)
  val boostVolume = mpvVolume - 100
  val textStr = getVolumeSliderText(volume, mpvVolume, boostVolume, volumePercent, displayAsPercentage)
  val volumeText =
    remember(textStr, displayAsPercentage) {
      textStr + if (displayAsPercentage && !textStr.contains('%')) "%" else ""
    }
  val level =
    if (displayAsPercentage) {
      volumePercent / 100f
    } else {
      percentage(volume.toFloat(), range.start.toFloat()..range.endInclusive.toFloat())
    }
  val boostLevel =
    boostRange?.let {
      percentage(boostVolume.toFloat(), it.start.toFloat()..it.endInclusive.toFloat())
    } ?: 0f
  LevelPill(
    fraction = level,
    overflowFraction = boostLevel,
    label = volumeText,
    icon =
      when (volumePercent) {
        0 -> Icons.RoundedFilled.VolumeOff
        in 0..30 -> Icons.RoundedFilled.VolumeMute
        in 30..60 -> Icons.RoundedFilled.VolumeDown
        in 60..100 -> Icons.RoundedFilled.VolumeUp
        else -> Icons.RoundedFilled.VolumeOff
      },
    modifier = modifier,
  )
}

@Composable
fun getVolumeSliderText(
  volume: Int,
  mpvVolume: Int,
  boostVolume: Int,
  percentage: Int,
  displayAsPercentage: Boolean,
): String =
  when {
    mpvVolume == 100 ->
      if (displayAsPercentage) {
        "$percentage"
      } else {
        "$volume"
      }

    mpvVolume > 100 -> {
      if (displayAsPercentage) {
        "${percentage + boostVolume}"
      } else {
        stringResource(R.string.volume_slider_absolute_value, volume + boostVolume)
      }
    }

    else -> {
      if (displayAsPercentage) {
        "$percentage"
      } else {
        "$volume"
      }
    }
  }
