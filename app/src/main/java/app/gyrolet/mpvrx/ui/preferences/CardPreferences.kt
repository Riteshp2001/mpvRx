package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import org.koin.compose.koinInject

/**
 * A card container for grouping related preferences, mimicking modern Android settings UI.
 */
@Composable
fun PreferenceCard(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val preferences = koinInject<AppearancePreferences>()
  val enableLiquidGlass by preferences.enableLiquidGlass.collectAsState()
  val liquidBlur by preferences.liquidDialogBlur.collectAsState()
  val liquidSaturation by preferences.liquidDialogSaturation.collectAsState()
  val liquidBrightness by preferences.liquidDialogBrightness.collectAsState()
  val liquidLensRadius by preferences.liquidDialogLensRadius.collectAsState()
  val liquidLensDepth by preferences.liquidDialogLensDepth.collectAsState()
  val liquidAlpha by preferences.liquidDialogContainerAlpha.collectAsState()
  val density = LocalDensity.current
  val shape = RoundedCornerShape(28.dp)
  val surfaceColor = MaterialTheme.colorScheme.surfaceContainer

  Card(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp)
      .then(
        if (enableLiquidGlass) {
          Modifier.drawBackdrop(
            backdrop = rememberLayerBackdrop(),
            shape = { shape },
            effects = {
              colorControls(
                brightness = liquidBrightness * 0.5f, // Subtler for cards
                saturation = liquidSaturation
              )
              blur(with(density) { (liquidBlur * 0.8f).dp.toPx() })
              lens(
                with(density) { (liquidLensRadius * 0.8f).dp.toPx() },
                with(density) { (liquidLensDepth * 0.8f).dp.toPx() },
                depthEffect = true
              )
            },
            highlight = { Highlight.Plain },
            onDrawSurface = {
                drawRect(surfaceColor.copy(alpha = liquidAlpha * 0.6f))
            }
          )
        } else {
          Modifier
        }
      ),
    shape = shape,
    colors = CardDefaults.cardColors(
      containerColor = if (enableLiquidGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
    ),
    elevation = CardDefaults.cardElevation(
      defaultElevation = 0.dp,
    ),
  ) {
    Column(
      modifier = Modifier.padding(vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
      content()
    }
  }
}

/**
 * A divider to separate preferences within a card.
 */
@Composable
fun PreferenceDivider(
  modifier: Modifier = Modifier,
) {
  HorizontalDivider(
    modifier = modifier.padding(horizontal = 16.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
  )
}

/**
 * A section header for preferences, displayed outside cards.
 */
@Composable
fun PreferenceSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
) {
  Text(
    text = title,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = modifier.padding(horizontal = 32.dp, vertical = 16.dp),
  )
}

