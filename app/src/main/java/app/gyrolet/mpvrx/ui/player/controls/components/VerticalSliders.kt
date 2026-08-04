/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.theme.spacing

fun percentage(
  value: Float,
  range: ClosedFloatingPointRange<Float>,
): Float = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)

fun percentage(
  value: Int,
  range: ClosedRange<Int>,
): Float = ((value - range.start - 0f) / (range.endInclusive - range.start)).coerceIn(0f, 1f)

@Composable
fun VerticalSlider(
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  modifier: Modifier = Modifier,
  overflowValue: Float? = null,
  overflowRange: ClosedFloatingPointRange<Float>? = null,
  colorStart: Color = MaterialTheme.colorScheme.primaryContainer,
  colorEnd: Color = MaterialTheme.colorScheme.primary,
) {
  val coercedValue = value.coerceIn(range)
  val gradientBrush = remember(colorStart, colorEnd) { Brush.verticalGradient(listOf(colorStart, colorEnd)) }
  Box(
    modifier =
      modifier
        .height(130.dp)
        .width(36.dp)
        .clip(AppShapeScale.largeIncreased)
        .background(Color.Black.copy(alpha = 0.3f)),
    contentAlignment = Alignment.BottomCenter,
  ) {
    val targetHeight by animateFloatAsState(
      percentage(coercedValue, range),
      animationSpec = spring(dampingRatio = 0.75f, stiffness = 300f),
      label = "vsliderheight",
    )
    Box(
      Modifier
        .fillMaxWidth()
        .fillMaxHeight(targetHeight.coerceAtLeast(0.05f)) // Keep a tiny amount visible
        .clip(AppShapeScale.largeIncreased)
        .background(gradientBrush),
    )
    if (overflowRange != null && overflowValue != null) {
      val overflowHeight by animateFloatAsState(
        percentage(overflowValue, overflowRange),
        label = "vslideroverflowheight",
      )
      Box(
        Modifier
          .fillMaxWidth()
          .fillMaxHeight(overflowHeight)
          .clip(AppShapeScale.largeIncreased)
          .background(MaterialTheme.colorScheme.errorContainer),
      )
    }
  }
}

@Composable
fun VerticalSlider(
  value: Int,
  range: ClosedRange<Int>,
  modifier: Modifier = Modifier,
  overflowValue: Int? = null,
  overflowRange: ClosedRange<Int>? = null,
  colorStart: Color = MaterialTheme.colorScheme.primaryContainer,
  colorEnd: Color = MaterialTheme.colorScheme.primary,
) {
  val coercedValue = value.coerceIn(range)
  val gradientBrush = remember(colorStart, colorEnd) { Brush.verticalGradient(listOf(colorStart, colorEnd)) }
  Box(
    modifier =
      modifier
        .height(130.dp)
        .width(36.dp)
        .clip(AppShapeScale.largeIncreased)
        .background(Color.Black.copy(alpha = 0.3f)),
    contentAlignment = Alignment.BottomCenter,
  ) {
    val targetHeight by animateFloatAsState(
      percentage(coercedValue, range),
      animationSpec = spring(dampingRatio = 0.75f, stiffness = 300f),
      label = "vsliderheight",
    )
    Box(
      Modifier
        .fillMaxWidth()
        .fillMaxHeight(targetHeight.coerceAtLeast(0.05f))
        .clip(AppShapeScale.largeIncreased)
        .background(gradientBrush),
    )
    if (overflowRange != null && overflowValue != null) {
      val overflowHeight by animateFloatAsState(
        percentage(overflowValue, overflowRange),
        label = "vslideroverflowheight",
      )
      Box(
        Modifier
          .fillMaxWidth()
          .fillMaxHeight(overflowHeight)
          .clip(AppShapeScale.largeIncreased)
          .background(MaterialTheme.colorScheme.errorContainer),
      )
    }
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
  Surface(
    modifier = modifier,
    shape = AppShapeScale.extraLarge,
    color = Color.Black.copy(alpha = 0.5f),
    contentColor = Color.White,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
      Text(
        text = percentText,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 48.dp),
      )
      VerticalSlider(
        coercedBrightness.coerceIn(0f, positiveRange.endInclusive),
        positiveRange,
        overflowValue = (-coercedBrightness).coerceIn(0f, negativeRange.endInclusive),
        overflowRange = negativeRange,
        colorStart = MaterialTheme.colorScheme.primaryContainer,
        colorEnd = MaterialTheme.colorScheme.primary,
      )
      Icon(
        when {
          coercedBrightness < 0 -> Icons.RoundedFilled.Brightness6
          percentage(coercedBrightness, positiveRange) <= 0.3f -> Icons.RoundedFilled.BrightnessLow
          percentage(coercedBrightness, positiveRange) <= 0.6f -> Icons.RoundedFilled.BrightnessMedium
          else -> Icons.RoundedFilled.BrightnessHigh
        },
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
    }
  }
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
  val percentage = volumePercentage.coerceIn(0, 100)
  Surface(
    modifier = modifier,
    shape = AppShapeScale.extraLarge,
    color = Color.Black.copy(alpha = 0.5f),
    contentColor = Color.White,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
      val boostVolume = mpvVolume - 100
      val textStr = getVolumeSliderText(volume, mpvVolume, boostVolume, percentage, displayAsPercentage)
      val volumeText = remember(textStr, displayAsPercentage) {
        textStr + if (displayAsPercentage && !textStr.contains('%')) "%" else ""
      }
      Text(
        text = volumeText,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 48.dp),
      )
      VerticalSlider(
        if (displayAsPercentage) percentage else volume,
        if (displayAsPercentage) 0..100 else range,
        overflowValue = boostVolume,
        overflowRange = boostRange,
        colorStart = MaterialTheme.colorScheme.primaryContainer,
        colorEnd = MaterialTheme.colorScheme.primary,
      )
      Icon(
        when (percentage) {
          0 -> Icons.RoundedFilled.VolumeOff
          in 0..30 -> Icons.RoundedFilled.VolumeMute
          in 30..60 -> Icons.RoundedFilled.VolumeDown
          in 60..100 -> Icons.RoundedFilled.VolumeUp
          else -> Icons.RoundedFilled.VolumeOff
        },
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

val getVolumeSliderText: @Composable (Int, Int, Int, Int, Boolean) -> String =
  { volume, mpvVolume, boostVolume, percentage, displayAsPercentage ->
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
  }
