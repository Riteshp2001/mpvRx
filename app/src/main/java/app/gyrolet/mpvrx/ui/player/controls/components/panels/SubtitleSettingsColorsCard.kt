package app.gyrolet.mpvrx.ui.player.controls.components.panels

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.core.graphics.toColorInt
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.preferences.preference.Preference
import app.gyrolet.mpvrx.preferences.preference.deleteAndGet
import app.gyrolet.mpvrx.presentation.components.ExpandableCard
import app.gyrolet.mpvrx.presentation.components.TintedSliderItem
import app.gyrolet.mpvrx.ui.player.controls.CARDS_MAX_WIDTH
import app.gyrolet.mpvrx.ui.player.controls.panelCardsColors
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.applySubtitleLayout
import `is`.xyz.mpv.MPVLib
import org.koin.compose.koinInject

@Composable
fun SubtitleSettingsColorsCard(
  viewModel: PlayerViewModel,
  modifier: Modifier = Modifier,
) {
  val preferences = koinInject<SubtitlesPreferences>()
  var isExpanded by remember { mutableStateOf(true) }
  ExpandableCard(
    isExpanded = isExpanded,
    onExpand = { isExpanded = !isExpanded },
    title = {
      Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
      ) {
        Icon(Icons.RoundedFilled.Palette, null)
        Text(stringResource(R.string.player_sheets_sub_colors_card_title))
      }
    },
    modifier = modifier.widthIn(max = CARDS_MAX_WIDTH),
    colors = panelCardsColors(),
  ) {
    Column {
      AssOverrideWarningBanner(viewModel = viewModel, preferences = preferences)

      var currentColorType by remember { mutableStateOf(SubColorType.Text) }
      var currentColor by remember { mutableIntStateOf(getCurrentMPVColor(currentColorType)) }
      LaunchedEffect(currentColorType) {
        currentColor = getCurrentMPVColor(currentColorType)
      }
      Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier =
          Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = MaterialTheme.spacing.extraSmall, end = MaterialTheme.spacing.medium),
      ) {
        SubColorType.entries.forEach { type ->
          IconToggleButton(
            checked = currentColorType == type,
            onCheckedChange = { currentColorType = type },
          ) {
            Icon(
              when (type) {
                SubColorType.Text -> Icons.RoundedFilled.FormatColorText
                SubColorType.Border -> Icons.RoundedFilled.BorderColor
                SubColorType.Background -> Icons.RoundedFilled.FormatColorFill
                SubColorType.Shadow -> Icons.RoundedFilled.Shadow
              },
              null,
            )
          }
        }
        Text(stringResource(currentColorType.titleRes))
        Spacer(Modifier.weight(1f))
        TextButton(
          onClick = {
            resetColors(preferences, currentColorType)
            currentColor = getCurrentMPVColor(currentColorType)
          },
        ) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.RoundedFilled.FormatColorReset, null)
            Text(stringResource(R.string.generic_reset))
          }
        }
      }
      SubtitlesColorPicker(
        currentColor,
        onColorChange = {
          currentColor = it
          currentColorType.preference(preferences).set(it)
          val hexColor = it.toColorHexString()
          MPVLib.setPropertyString(currentColorType.property, hexColor)
          val secondaryProp = currentColorType.property.replace("sub-", "secondary-sub-")
          MPVLib.setPropertyString(secondaryProp, hexColor)
        },
      )
    }
  }
}

fun Int.copyAsArgb(
  alpha: Int = this.alpha,
  red: Int = this.red,
  green: Int = this.green,
  blue: Int = this.blue,
) = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

fun Int.toColorHexString(): String {
  val a = (this shr 24 and 0xFF).toString(16).padStart(2, '0')
  val r = (this shr 16 and 0xFF).toString(16).padStart(2, '0')
  val g = (this shr 8 and 0xFF).toString(16).padStart(2, '0')
  val b = (this and 0xFF).toString(16).padStart(2, '0')
  return "#$a$r$g$b".uppercase()
}

enum class SubColorType(
  @StringRes val titleRes: Int,
  val property: String,
  val preference: (SubtitlesPreferences) -> Preference<Int>,
) {
  Text(
    R.string.player_sheets_subtitles_color_text,
    "sub-color",
    preference = SubtitlesPreferences::textColor,
  ),
  Border(
    R.string.player_sheets_subtitles_color_border,
    "sub-border-color",
    preference = SubtitlesPreferences::borderColor,
  ),
  Background(
    R.string.player_sheets_subtitles_color_background,
    "sub-back-color",
    preference = SubtitlesPreferences::backgroundColor,
  ),
  Shadow(
    R.string.player_sheets_subtitles_color_shadow,
    "sub-shadow-color",
    preference = SubtitlesPreferences::shadowColor,
  ),
}

fun resetColors(
  preferences: SubtitlesPreferences,
  type: SubColorType,
) {
  val hexColor = when (type) {
    SubColorType.Text -> preferences.textColor.deleteAndGet().toColorHexString()
    SubColorType.Border -> preferences.borderColor.deleteAndGet().toColorHexString()
    SubColorType.Background -> preferences.backgroundColor.deleteAndGet().toColorHexString()
    SubColorType.Shadow -> preferences.shadowColor.deleteAndGet().toColorHexString()
  }
  MPVLib.setPropertyString(type.property, hexColor)
  MPVLib.setPropertyString(type.property.replace("sub-", "secondary-sub-"), hexColor)
}

val getCurrentMPVColor: (SubColorType) -> Int = {
  MPVLib.getPropertyString(it.property)?.uppercase()?.toColorInt() ?: 0xFFFFFFFF.toInt()
}

@Composable
fun SubtitlesColorPicker(
  color: Int,
  onColorChange: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier) {
    TintedSliderItem(
      stringResource(R.string.player_sheets_sub_color_red),
      color.red,
      color.red.toString(),
      onChange = { onColorChange(color.copyAsArgb(red = it)) },
      max = 255,
      tint = Color.Red,
    )

    TintedSliderItem(
      stringResource(R.string.player_sheets_sub_color_green),
      color.green,
      color.green.toString(),
      onChange = { onColorChange(color.copyAsArgb(green = it)) },
      max = 255,
      tint = Color.Green,
    )

    TintedSliderItem(
      stringResource(R.string.player_sheets_sub_color_blue),
      color.blue,
      color.blue.toString(),
      onChange = { onColorChange(color.copyAsArgb(blue = it)) },
      max = 255,
      tint = Color.Blue,
    )

    TintedSliderItem(
      stringResource(R.string.player_sheets_sub_color_alpha),
      color.alpha,
      color.alpha.toString(),
      onChange = { onColorChange(color.copyAsArgb(alpha = it)) },
      max = 255,
      tint = Color.White,
    )
  }
}

@Composable
fun AssOverrideWarningBanner(
  viewModel: PlayerViewModel,
  preferences: SubtitlesPreferences,
  modifier: Modifier = Modifier,
) {
  val subtitleTracks by viewModel.subtitleTracks.collectAsState()
  val activeSubTrack = subtitleTracks.find { it.isSelected }
  val isActiveSubAss = activeSubTrack?.codec?.contains("ass", ignoreCase = true) == true ||
                       activeSubTrack?.codec?.contains("ssa", ignoreCase = true) == true

  var overrideAssSubs by remember {
    mutableStateOf(preferences.overrideAssSubs.get())
  }

  LaunchedEffect(preferences.overrideAssSubs.get()) {
    overrideAssSubs = preferences.overrideAssSubs.get()
  }

  if (isActiveSubAss && !overrideAssSubs) {
    androidx.compose.material3.Card(
      colors = androidx.compose.material3.CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer
      ),
      border = androidx.compose.foundation.BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
      ),
      modifier = modifier
        .fillMaxWidth()
        .padding(horizontal = MaterialTheme.spacing.medium)
        .padding(bottom = MaterialTheme.spacing.medium)
    ) {
      Row(
        modifier = Modifier.padding(MaterialTheme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)
      ) {
        Icon(
          Icons.RoundedFilled.Warning,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.player_sheets_sub_ass_override_warning),
            style = MaterialTheme.typography.bodySmall
          )
          Spacer(Modifier.height(MaterialTheme.spacing.extraSmall))
          TextButton(
            onClick = {
              preferences.overrideAssSubs.set(true)
              overrideAssSubs = true
              applySubtitleLayout(MPVLib.getPropertyInt("sub-pos") ?: preferences.subPos.get(), true)
            },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp)
          ) {
            Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_enable_ass_override),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.error
            )
          }
        }
      }
    }
  }
}





