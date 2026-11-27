package app.gyrolet.mpvrx.ui.preferences.components

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.LiquidGlassHighlightStyle
import app.gyrolet.mpvrx.preferences.preference.Preference
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.ExpressivePreferenceItem
import app.gyrolet.mpvrx.ui.preferences.PreferenceCard
import app.gyrolet.mpvrx.ui.preferences.PreferenceDivider
import app.gyrolet.mpvrx.ui.preferences.PreferenceSectionHeader
import app.gyrolet.mpvrx.ui.preferences.settingsSearchTarget
import java.text.NumberFormat
import java.util.Locale
import app.gyrolet.mpvrx.ui.preferences.components.AppListPreference as ListPreference
import app.gyrolet.mpvrx.ui.preferences.components.AppSliderPreference as SliderPreference

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
fun LazyListScope.liquidGlassPreferences(
  preferences: AppearancePreferences,
  enabled: Boolean,
) {
  item {
    PreferenceSectionHeader(title = stringResource(R.string.pref_liquid_glass_material))
  }
  item {
    PreferenceCard {
      LiquidGlassSlider(preferences.liquidGlassBlur, R.string.pref_liquid_glass_frost, enabled)
    }
  }
  item {
    PreferenceSectionHeader(title = stringResource(R.string.pref_liquid_glass_optics))
  }
  item {
    val lensSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val refractionHeight by preferences.liquidGlassRefractionHeight.collectAsState()
    val refractionAmount by preferences.liquidGlassRefractionAmount.collectAsState()
    val lensEnabled = enabled && lensSupported
    val lensActive = lensEnabled && refractionHeight > 0f && refractionAmount > 0f
    PreferenceCard {
      LiquidGlassSlider(preferences.liquidGlassOpacity, R.string.pref_liquid_glass_opacity, enabled, 0f..1f)
      PreferenceDivider()
      LiquidGlassSlider(
        preferences.liquidGlassRefractionHeight,
        R.string.pref_liquid_glass_refraction_height,
        lensEnabled,
      )
      PreferenceDivider()
      LiquidGlassSlider(
        preferences.liquidGlassRefractionAmount,
        R.string.pref_liquid_glass_refraction_amount,
        lensEnabled,
      )
      PreferenceDivider()
      LiquidGlassSwitch(preferences.liquidGlassDepthEffect, R.string.pref_liquid_glass_depth, lensActive)
      PreferenceDivider()
      LiquidGlassSwitch(preferences.liquidGlassChromaticAberration, R.string.pref_liquid_glass_chromatic, lensActive)
      if (!lensSupported) {
        Text(
          stringResource(R.string.pref_liquid_glass_lens_unavailable),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
      }
    }
  }

  item {
    PreferenceSectionHeader(title = stringResource(R.string.pref_liquid_glass_colors))
  }
  item {
    PreferenceCard {
      LiquidGlassSwitch(preferences.liquidGlassVibrancy, R.string.pref_liquid_glass_vibrancy, enabled)
      PreferenceDivider()
      LiquidGlassSlider(preferences.liquidGlassSaturation, R.string.player_sheets_filters_Saturation, enabled)
      PreferenceDivider()
      LiquidGlassSlider(preferences.liquidGlassBrightness, R.string.player_sheets_filters_brightness, enabled, -1f..1f)
      PreferenceDivider()
      LiquidGlassSlider(preferences.liquidGlassContrast, R.string.player_sheets_filters_contrast, enabled)
    }
  }

  item {
    PreferenceSectionHeader(title = stringResource(R.string.pref_liquid_glass_highlights))
  }
  item {
    val highlightStyle by preferences.liquidGlassHighlightStyle.collectAsState()
    val highlightLabels =
      mapOf(
        LiquidGlassHighlightStyle.Component to stringResource(R.string.pref_liquid_glass_component_default),
        LiquidGlassHighlightStyle.Default to stringResource(R.string.theme_default),
        LiquidGlassHighlightStyle.Ambient to stringResource(R.string.pref_liquid_glass_highlight_ambient),
        LiquidGlassHighlightStyle.Plain to stringResource(R.string.pref_liquid_glass_highlight_plain),
      )
    PreferenceCard {
      ExpressivePreferenceItem {
        ListPreference(
          value = highlightStyle,
          onValueChange = preferences.liquidGlassHighlightStyle::set,
          values = LiquidGlassHighlightStyle.entries,
          valueToText = { AnnotatedString(highlightLabels.getValue(it)) },
          title = { Text(stringResource(R.string.pref_liquid_glass_highlight_style)) },
          summary = { Text(highlightLabels.getValue(highlightStyle), color = MaterialTheme.colorScheme.outline) },
          enabled = enabled,
          modifier = Modifier.settingsSearchTarget(R.string.pref_liquid_glass_highlight_style),
        )
      }
      PreferenceDivider()
      LiquidGlassSlider(
        preferences.liquidGlassHighlightStrength,
        R.string.pref_liquid_glass_highlight_strength,
        enabled,
      )
      PreferenceDivider()
      LiquidGlassSlider(preferences.liquidGlassHighlightWidth, R.string.pref_liquid_glass_highlight_width, enabled)
      PreferenceDivider()
      LiquidGlassSlider(preferences.liquidGlassHighlightBlur, R.string.pref_liquid_glass_highlight_blur, enabled)
    }
  }
  item {
    TextButton(
      onClick = preferences::resetLiquidGlassOptions,
      enabled = enabled,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
      Icon(Icons.RoundedFilled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text(stringResource(R.string.pref_layout_reset_default))
    }
  }
}

@Composable
private fun LiquidGlassSwitch(
  preference: Preference<Boolean>,
  @StringRes title: Int,
  enabled: Boolean,
) {
  val value by preference.collectAsState()
  SwitchPreference(
    value = value,
    onValueChange = preference::set,
    title = { Text(stringResource(title)) },
    enabled = enabled,
    modifier = Modifier.settingsSearchTarget(title),
  )
}

@Composable
private fun LiquidGlassSlider(
  preference: Preference<Float>,
  @StringRes title: Int,
  enabled: Boolean,
  range: ClosedFloatingPointRange<Float> = 0f..2f,
) {
  val savedValue by preference.collectAsState()
  val value = if (savedValue.isFinite()) savedValue.coerceIn(range) else preference.defaultValue()
  val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
  SliderPreference(
    value = value,
    onValueChange = preference::set,
    sliderValue = value,
    onSliderValueChange = preference::set,
    valueRange = range,
    valueSteps = 19,
    title = { Text(stringResource(title)) },
    summary = {
      Text(
        NumberFormat.getPercentInstance(locale).format(value),
        color = MaterialTheme.colorScheme.outline,
      )
    },
    enabled = enabled,
    modifier = Modifier.settingsSearchTarget(title),
  )
}
