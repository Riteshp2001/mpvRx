package app.gyrolet.mpvrx.ui.player.controls.components

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
  Box(
    modifier =
      modifier
        .height(130.dp)
        .width(36.dp)
        .clip(RoundedCornerShape(18.dp))
        .background(Color.Black.copy(alpha = 0.3f)),
    contentAlignment = Alignment.BottomCenter,
  ) {
    val targetHeight by animateFloatAsState(
      percentage(coercedValue, range),
      animationSpec = spring(dampingRatio = 0.75f, stiffness = 300f),
      label = "vsliderheight"
    )
    Box(
      Modifier
        .fillMaxWidth()
        .fillMaxHeight(targetHeight.coerceAtLeast(0.05f)) // Keep a tiny amount visible
        .clip(RoundedCornerShape(18.dp))
        .background(Brush.verticalGradient(listOf(colorStart, colorEnd))),
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
          .clip(RoundedCornerShape(18.dp))
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
  Box(
    modifier =
      modifier
        .height(130.dp)
        .width(36.dp)
        .clip(RoundedCornerShape(18.dp))
        .background(Color.Black.copy(alpha = 0.3f)),
    contentAlignment = Alignment.BottomCenter,
  ) {
    val targetHeight by animateFloatAsState(
      percentage(coercedValue, range),
      animationSpec = spring(dampingRatio = 0.75f, stiffness = 300f),
      label = "vsliderheight"
    )
    Box(
      Modifier
        .fillMaxWidth()
        .fillMaxHeight(targetHeight.coerceAtLeast(0.05f))
        .clip(RoundedCornerShape(18.dp))
        .background(Brush.verticalGradient(listOf(colorStart, colorEnd))),
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
          .clip(RoundedCornerShape(18.dp))
          .background(MaterialTheme.colorScheme.errorContainer),
      )
    }
  }
}

@Composable
fun BrightnessSlider(
  brightness: Float,
  range: ClosedFloatingPointRange<Float>,
  modifier: Modifier = Modifier,
) {
  val coercedBrightness = brightness.coerceIn(range)
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(24.dp),
    color = Color.Black.copy(alpha = 0.5f),
    contentColor = Color.White,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
      Text(
        "${(coercedBrightness * 100).toInt()}%",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 48.dp)
      )
      VerticalSlider(
        coercedBrightness,
        range,
        colorStart = MaterialTheme.colorScheme.primaryContainer,
        colorEnd = MaterialTheme.colorScheme.primary,
      )
      Icon(
        when (percentage(coercedBrightness, range)) {
          in 0f..0.3f -> Icons.Default.BrightnessLow
          in 0.3f..0.6f -> Icons.Default.BrightnessMedium
          in 0.6f..1f -> Icons.Default.BrightnessHigh
          else -> Icons.Default.BrightnessMedium
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
    shape = RoundedCornerShape(24.dp),
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
      Text(
        textStr + if (displayAsPercentage && !textStr.contains('%')) "%" else "",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 48.dp)
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
          0 -> Icons.Default.VolumeOff
          in 0..30 -> Icons.Default.VolumeMute
          in 30..60 -> Icons.Default.VolumeDown
          in 60..100 -> Icons.Default.VolumeUp
          else -> Icons.Default.VolumeOff
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




