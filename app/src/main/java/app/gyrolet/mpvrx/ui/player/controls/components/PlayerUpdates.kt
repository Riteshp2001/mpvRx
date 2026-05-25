package app.gyrolet.mpvrx.ui.player.controls.components

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.components.LiquidButton
import app.gyrolet.mpvrx.ui.theme.spacing
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.koin.compose.koinInject

private val tabularFigures = "tnum"

@Composable
fun PlayerUpdate(
  modifier: Modifier = Modifier,
  backdrop: Backdrop = rememberLayerBackdrop(),
  content: @Composable () -> Unit = {},
) {
  val preferences = koinInject<AppearancePreferences>()
  val enableLiquidGlass by preferences.enableLiquidGlass.collectAsState()

  if (enableLiquidGlass) {
    LiquidButton(
      onClick = {},
      backdrop = backdrop,
      isInteractive = false,
      modifier = modifier.height(45.dp)
    ) {
      content()
    }
  } else {
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.35f),
      contentColor = MaterialTheme.colorScheme.onSurface,
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
      border = BorderStroke(
        1.dp,
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
      ),
      modifier = modifier
        .height(45.dp)
        .animateContentSize(),
    ) {
      Box(
        modifier = Modifier.padding(
          vertical = MaterialTheme.spacing.small,
          horizontal = MaterialTheme.spacing.medium,
        ),
        contentAlignment = Alignment.Center,
      ) {
        content()
      }
    }
  }
}

@Composable
fun TextPlayerUpdate(
  text: String,
  modifier: Modifier = Modifier,
) {
  val stableTextStyle = MaterialTheme.typography.bodyMedium.copy(
    fontFeatureSettings = tabularFigures,
    shadow = Shadow(
      color = Color.Black.copy(alpha = 0.8f),
      offset = Offset(2f, 2f),
      blurRadius = 4f
    )
  )
  PlayerUpdate(modifier) {
    Text(
      text = text,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
      color = Color.White,
      style = stableTextStyle,
    )
  }
}

@Composable
fun MultipleSpeedPlayerUpdate(
  currentSpeed: Float,
  modifier: Modifier = Modifier,
) {
  CompactSpeedIndicator(currentSpeed = currentSpeed, modifier = modifier)
}

@Composable
@Preview
private fun PreviewMultipleSpeedPlayerUpdate() {
  MultipleSpeedPlayerUpdate(currentSpeed = 2f)
}
@Composable
fun SeekPlayerUpdate(
  currentTime: String,
  seekDelta: String,
  modifier: Modifier = Modifier,
) {
  val stableTextStyle = MaterialTheme.typography.bodyMedium.copy(
    fontFeatureSettings = tabularFigures,
    shadow = Shadow(
      color = Color.Black.copy(alpha = 0.8f),
      offset = Offset(2f, 2f),
      blurRadius = 4f
    )
  )
  PlayerUpdate(modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = currentTime,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = Color.White,
        style = stableTextStyle,
      )
      
      Text(
        text = " $seekDelta",
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center,
        style = stableTextStyle,
        color = Color.White.copy(alpha = 0.8f),
      )
    }
  }
}




