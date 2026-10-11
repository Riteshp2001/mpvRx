package app.gyrolet.mpvrx.ui.preferences

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.liquidglass.LiquidButton
import app.gyrolet.mpvrx.ui.liquidglass.LiquidToggle
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassEffects
import app.gyrolet.mpvrx.ui.liquidglass.rememberLiquidGlassSettings
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.preferences.components.liquidGlassPreferences
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object LiquidGlassPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val preferences = koinInject<AppearancePreferences>()
    val backstack = LocalBackStack.current
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val enabled by preferences.liquidGlassEnabled.collectAsState()

    Scaffold(
      topBar = {
        SettingsTopAppBar(
          title = stringResource(R.string.pref_appearance_category_liquid_glass),
          onBack = { backstack.popSafely() },
        )
      },
    ) { padding ->
      ProvideExpressivePreferenceLocals {
        val (listState, highlight) =
          rememberSettingsSearchList(LiquidGlassPreferencesScreen, MaterialTheme.colorScheme.primary)
        BoxWithConstraints(
          modifier = Modifier.fillMaxSize().padding(padding),
        ) {
          val compactPreview = maxHeight < 420.dp
          Column(Modifier.fillMaxSize()) {
            LiquidGlassPreview(
              enabled = enabled && supported,
              height = if (compactPreview) 112.dp else 200.dp,
              compact = compactPreview,
            )
            LazyColumn(
              state = listState,
              modifier = Modifier.weight(1f).fillMaxWidth().then(highlight),
              contentPadding = PaddingValues(bottom = 28.dp),
            ) {
              item {
                PreferenceSectionHeader(title = stringResource(R.string.pref_section_general))
              }
              item {
                PreferenceCard {
                  SwitchPreference(
                    value = enabled && supported,
                    onValueChange = preferences.liquidGlassEnabled::set,
                    title = { Text(stringResource(R.string.pref_appearance_liquid_glass_title)) },
                    summary = {
                      Text(
                        text = stringResource(
                          if (supported) R.string.pref_appearance_liquid_glass_summary
                          else R.string.pref_appearance_liquid_glass_summary_unavailable,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    },
                    enabled = supported,
                    modifier = Modifier.settingsSearchTarget(R.string.pref_appearance_liquid_glass_title),
                  )
                }
              }
              liquidGlassPreferences(preferences, enabled = enabled && supported)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun LiquidGlassPreview(enabled: Boolean, height: Dp = 200.dp, compact: Boolean = false) {
  val backdrop = rememberLayerBackdrop()
  val settings = rememberLiquidGlassSettings()
  val colors = MaterialTheme.colorScheme
  val shape = MaterialTheme.shapes.extraLargeIncreased
  var playing by rememberSaveable { mutableStateOf(false) }
  var repeating by rememberSaveable { mutableStateOf(false) }
  val playLabel = stringResource(R.string.pref_gesture_media_play)
  val repeatLabel = stringResource(R.string.btn_label_repeat_mode)

  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .height(height)
        .clip(shape),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(Modifier.matchParentSize().layerBackdrop(backdrop)) {
      val tileSize = 40.dp.toPx()
      val palette = listOf(colors.primaryContainer, colors.secondaryContainer, colors.tertiaryContainer)
      for (row in 0..(size.height / tileSize).toInt()) {
        for (column in 0..(size.width / tileSize).toInt()) {
          drawRect(
            color = palette[(row + column) % palette.size],
            topLeft = Offset(column * tileSize, row * tileSize),
            size = Size(tileSize, tileSize),
          )
        }
      }
    }
    Column(
      modifier =
        Modifier
          .padding(if (compact) 8.dp else 20.dp)
          .fillMaxWidth()
          .then(
            if (enabled) {
              Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                  liquidGlassEffects(
                    settings,
                    6.dp.toPx(),
                    12.dp.toPx(),
                    24.dp.toPx(),
                    vibrant = true,
                    refractionEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                  )
                },
                highlight = { settings.highlight(Highlight.Default) },
                shadow = { settings.shadow(Shadow.Default) },
                innerShadow = { settings.innerShadow(InnerShadow(radius = 4.dp, alpha = 0.2f)) },
                onDrawSurface = { drawRect(settings.surfaceColor(colors.surface.copy(alpha = 0.35f))) },
              )
            } else {
              Modifier.background(
                if (enabled) settings.surfaceColor(colors.surface.copy(alpha = 0.35f)) else colors.surfaceContainerHigh,
                shape,
              )
            },
          ).padding(if (compact) 8.dp else 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!compact) {
        Text(
          text = stringResource(R.string.app_name),
          style = MaterialTheme.typography.titleMedium,
          color = colors.onSurface,
        )
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (enabled) {
          LiquidButton(
            onClick = { playing = !playing },
            backdrop = backdrop,
            modifier = Modifier.size(48.dp),
            horizontalPadding = 0.dp,
          ) {
            Icon(
              if (playing) Icons.RoundedFilled.Pause else Icons.RoundedFilled.PlayArrow,
              contentDescription = playLabel,
              tint = colors.onSurface,
            )
          }
        } else {
          IconButton(onClick = { playing = !playing }, modifier = Modifier.size(48.dp)) {
            Icon(
              if (playing) Icons.RoundedFilled.Pause else Icons.RoundedFilled.PlayArrow,
              contentDescription = playLabel,
              tint = colors.onSurface,
            )
          }
        }
        Row(
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.RoundedFilled.Repeat, contentDescription = null, tint = colors.onSurface)
          if (enabled) {
            LiquidToggle(
              selected = { repeating },
              onSelect = { repeating = it },
              backdrop = backdrop,
              modifier =
                Modifier.width(64.dp).height(48.dp).semantics {
                  contentDescription = repeatLabel
                  toggleableState = if (repeating) ToggleableState.On else ToggleableState.Off
                  onClick {
                    repeating = !repeating
                    true
                  }
                },
            )
          } else {
            Switch(
              checked = repeating,
              onCheckedChange = { repeating = it },
              modifier = Modifier.width(64.dp).height(48.dp).semantics { contentDescription = repeatLabel },
            )
          }
        }
      }
    }
  }
}
