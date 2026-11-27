/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.panels

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.fonts.GoogleFontsRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.DEFAULT_SUBTITLE_FONT_FAMILY
import app.gyrolet.mpvrx.preferences.LEGACY_DEFAULT_SUBTITLE_FONT_FAMILY
import app.gyrolet.mpvrx.preferences.SubtitleJustification
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.preferences.preference.deleteAndGet
import app.gyrolet.mpvrx.presentation.components.AppComboBox
import app.gyrolet.mpvrx.presentation.components.ExpandableCard
import app.gyrolet.mpvrx.presentation.components.ExposedTextDropDownMenu
import app.gyrolet.mpvrx.presentation.components.SliderItem
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.controls.CARDS_MAX_WIDTH
import app.gyrolet.mpvrx.ui.player.controls.panelCardsColors
import app.gyrolet.mpvrx.ui.player.defaultSubtitleFontDisplayName
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.currentMpvConfigOverrideOptions
import app.gyrolet.mpvrx.utils.media.SubtitleFontChoice
import app.gyrolet.mpvrx.utils.media.loadCustomFontEntries
import app.gyrolet.mpvrx.utils.media.subtitleFontChoices
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
fun SubtitleSettingsTypographyCard(
  viewModel: PlayerViewModel,
  modifier: Modifier = Modifier,
  shape: Shape = MaterialTheme.shapes.large,
) {
  val context = LocalContext.current
  val preferences = koinInject<SubtitlesPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val googleFontsRepository = koinInject<GoogleFontsRepository>()
  val configOwnedOptions = currentMpvConfigOverrideOptions()
  val ownsAny: (Set<String>) -> Boolean = { options -> options.any(configOwnedOptions::contains) }
  val boldOptions = setOf("sub-bold")
  val italicOptions = setOf("sub-italic")
  val justifyOptions = setOf("sub-ass-justify", "sub-justify")
  val fontOptions = setOf("sub-font")
  val fontSizeOptions = setOf("sub-font-size")
  val borderStyleOptions = setOf("sub-border-style")
  val borderSizeOptions =
    setOf("sub-border-size", "sub-outline-size")
  val shadowOffsetOptions = setOf("sub-shadow-offset")
  val backgroundRadiusOptions = setOf("sub-back-radius")
  val typographyOptions =
    boldOptions + italicOptions + justifyOptions + fontOptions + fontSizeOptions +
      borderStyleOptions + borderSizeOptions + shadowOffsetOptions + backgroundRadiusOptions
  var isExpanded by remember { mutableStateOf(true) }
  val effectiveDefaultFont = DEFAULT_SUBTITLE_FONT_FAMILY
  var fonts by remember { mutableStateOf(listOf("Default")) }
  var fontChoices by remember { mutableStateOf<List<SubtitleFontChoice>>(emptyList()) }
  var preferredFontLabel by remember { mutableStateOf("") }
  var fontsLoadingIndicator: (@Composable () -> Unit)? by remember {
    val indicator: (@Composable () -> Unit) = {
      CircularProgressIndicator(Modifier.size(32.dp))
    }
    mutableStateOf(indicator)
  }
  LaunchedEffect(Unit) {
    fontChoices =
      withContext(Dispatchers.IO) {
        val downloaded =
          googleFontsRepository
            .installedFonts(appearancePreferences.googleFontFamily.get())
            .map { it.family }
        val custom =
          loadCustomFontEntries(context)
            .filterNot { googleFontsRepository.isManagedMpvFont(it.file) }
        subtitleFontChoices(downloaded, custom)
          .filter {
            it.familyName != DEFAULT_SUBTITLE_FONT_FAMILY &&
              it.familyName != LEGACY_DEFAULT_SUBTITLE_FONT_FAMILY
          }
      }
    fonts = listOf("Default") + fontChoices.map { it.label }
    fontsLoadingIndicator = null
  }

  ExpandableCard(
    isExpanded = isExpanded,
    onExpand = { isExpanded = !isExpanded },
    title = {
      Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
      ) {
        Icon(Icons.RoundedFilled.FormatColorText, null)
        Text(stringResource(R.string.player_sheets_sub_typography_card_title))
      }
    },
    modifier = modifier.widthIn(max = CARDS_MAX_WIDTH),
    colors = panelCardsColors(),
    shape = shape,
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing)) {
      AssOverrideWarningBanner(viewModel = viewModel, preferences = preferences)

      val isBold by PlaybackSession.propBoolean["sub-bold"].collectAsState()
      val isItalic by PlaybackSession.propBoolean["sub-italic"].collectAsState()
      val mpvJustify by PlaybackSession.propString["sub-justify"].collectAsState()
      val justify by remember {
        derivedStateOf { SubtitleJustification.entries.first { it.value == mpvJustify } }
      }
      val font by PlaybackSession.propString["sub-font"].collectAsState()
      val fontSize by PlaybackSession.propInt["sub-font-size"].collectAsState()
      val mpvBorderStyle by PlaybackSession.propString["sub-border-style"].collectAsState()
      val borderStyle by remember {
        derivedStateOf {
          SubtitlesBorderStyle.entries.firstOrNull { it.value == mpvBorderStyle }
            ?: SubtitlesBorderStyle.OutlineAndShadow
        }
      }
      val borderSize by PlaybackSession.propInt["sub-outline-size"].collectAsState()
      val shadowOffset by PlaybackSession.propInt["sub-shadow-offset"].collectAsState()
      var localShadowOffset by remember(shadowOffset) {
        mutableStateOf(shadowOffset ?: preferences.shadowOffset.get())
      }
      val backgroundRadius by PlaybackSession.propInt["sub-back-radius"].collectAsState()
      // The radius only shapes the background box, so it shows with that style alone.
      val showBackgroundRadius =
        BuildConfig.PLAYER_ENGINE_IS_MPV && borderStyle == SubtitlesBorderStyle.BackgroundBox
      // Only libmpv has the font family and border style tiles.
      val tileCount = (if (BuildConfig.PLAYER_ENGINE_IS_MPV) 6 else 4) + (if (showBackgroundRadius) 1 else 0)
      var tileIndex = 0
      PanelConnectedTile(index = tileIndex++, itemCount = tileCount) {
        Row(
          Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = MaterialTheme.spacing.extraSmall, end = MaterialTheme.spacing.medium),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconToggleButton(
            checked = isBold == true,
            enabled = !ownsAny(boldOptions),
            onCheckedChange = {
              preferences.bold.set(it)
              PlaybackSession.setPropertyBoolean("sub-bold", it)
            },
          ) {
            Icon(
              Icons.RoundedFilled.FormatBold,
              null,
              modifier = Modifier.size(32.dp),
            )
          }
          IconToggleButton(
            checked = isItalic == true,
            enabled = !ownsAny(italicOptions),
            onCheckedChange = {
              preferences.italic.set(it)
              PlaybackSession.setPropertyBoolean("sub-italic", it)
            },
          ) {
            Icon(
              Icons.RoundedFilled.FormatItalic,
              null,
              modifier = Modifier.size(32.dp),
            )
          }
          if (BuildConfig.PLAYER_ENGINE_IS_MPV) {
            SubtitleJustification.entries.minus(SubtitleJustification.Auto).forEach { justification ->
              IconToggleButton(
                checked = justify == justification,
                enabled = !ownsAny(justifyOptions),
                onCheckedChange = {
                  PlaybackSession.setPropertyBoolean("sub-ass-justify", it)
                  if (it) {
                    preferences.justification.set(justification)
                    PlaybackSession.setPropertyString("sub-justify", justification.value)
                  } else {
                    preferences.justification.set(SubtitleJustification.Auto)
                    PlaybackSession.setPropertyString("sub-justify", SubtitleJustification.Auto.value)
                  }
                },
              ) {
                Icon(justification.icon, null)
              }
            }
          }
          Spacer(Modifier.weight(1f))
          TextButton(
            enabled = !ownsAny(typographyOptions),
            onClick = { resetTypography(preferences, effectiveDefaultFont) },
          ) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(Icons.RoundedFilled.FormatClear, null)
              Text(stringResource(R.string.generic_reset))
            }
          }
        }
      }
      if (BuildConfig.PLAYER_ENGINE_IS_MPV) {
        PanelConnectedTile(index = tileIndex++, itemCount = tileCount) {
          Row(
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              Icons.RoundedFilled.BrandFamily,
              null,
              modifier = Modifier.size(32.dp),
            )
            ExposedTextDropDownMenu(
              selectedValue =
                run {
                  val family =
                    font
                      ?.takeUnless {
                        it == effectiveDefaultFont || it == LEGACY_DEFAULT_SUBTITLE_FONT_FAMILY
                      }.orEmpty()
                  preferredFontLabel.takeIf { selected ->
                    fontChoices.any { it.label == selected && it.familyName == family }
                  } ?: fontChoices.firstOrNull { it.familyName == family }?.label
                    ?: family.ifEmpty { defaultSubtitleFontDisplayName(effectiveDefaultFont) }
                },
              options = fonts.toImmutableList(),
              label = stringResource(R.string.player_sheets_sub_typography_font),
              onValueChangedEvent = { selected ->
                preferredFontLabel = selected
                val storedFont =
                  if (selected ==
                    "Default"
                  ) {
                    ""
                  } else {
                    fontChoices.firstOrNull { it.label == selected }?.familyName ?: selected
                  }
                val actualFont = storedFont.ifBlank { effectiveDefaultFont }
                preferences.font.set(storedFont)
                PlaybackSession.setPropertyString("sub-font", actualFont)
              },
              leadingIcon = fontsLoadingIndicator,
              enabled = !ownsAny(fontOptions),
            )
          }
        }
      }
      PanelConnectedTile(index = tileIndex++, itemCount = tileCount) {
        SliderItem(
          label = stringResource(R.string.player_sheets_sub_typography_font_size),
          max = 100,
          min = 1,
          value = fontSize ?: preferences.fontSize.get(),
          valueText = fontSize.toString(),
          onChange = {
            preferences.fontSize.set(it)
            PlaybackSession.setPropertyInt("sub-font-size", it)
          },
          enabled = !ownsAny(fontSizeOptions),
        ) {
          Icon(Icons.RoundedFilled.FormatSize, null)
        }
      }
      if (BuildConfig.PLAYER_ENGINE_IS_MPV) {
        PanelConnectedTile(index = tileIndex++, itemCount = tileCount) {
          Row(
            modifier =
              Modifier.padding(
                horizontal = MaterialTheme.spacing.medium,
                vertical = MaterialTheme.spacing.small,
              ),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              Icons.RoundedFilled.BorderStyle,
              null,
              modifier = Modifier.size(32.dp),
            )
            AppComboBox(
              options = SubtitlesBorderStyle.entries,
              selected = borderStyle,
              onSelect = {
                preferences.borderStyle.set(it)
                PlaybackSession.setPropertyString("sub-border-style", it.value)
              },
              optionLabel = { stringResource(it.titleRes) },
              label = stringResource(R.string.player_sheets_subtitles_border_style),
              enabled = !ownsAny(borderStyleOptions),
            )
          }
        }
      }
      PanelConnectedTile(index = tileIndex++, itemCount = tileCount) {
        SliderItem(
          stringResource(R.string.player_sheets_sub_typography_border_size),
          value = borderSize ?: preferences.borderSize.get(),
          valueText = (borderSize ?: preferences.borderSize.get()).toString(),
          onChange = {
            preferences.borderSize.set(it)
            PlaybackSession.setPropertyInt("sub-border-size", it)
            PlaybackSession.setPropertyInt("sub-outline-size", it)
          },
          max = 20,
          enabled = !ownsAny(borderSizeOptions),
          icon = { Icon(Icons.RoundedFilled.BorderColor, null) },
        )
      }
      PanelConnectedTile(index = tileIndex++, itemCount = tileCount) {
        SliderItem(
          stringResource(R.string.player_sheets_subtitles_shadow_offset),
          value = localShadowOffset.coerceIn(-20, 20),
          valueText =
            localShadowOffset.coerceIn(-20, 20).let {
              if (it > 0) "+$it" else it.toString()
            },
          onChange = {
            localShadowOffset = it
            preferences.shadowOffset.set(it)
            PlaybackSession.setPropertyInt("sub-shadow-offset", it)
          },
          min = -20,
          max = 20,
          enabled = !ownsAny(shadowOffsetOptions),
          icon = { Icon(Icons.RoundedFilled.Shadow, null) },
        )
      }
      if (showBackgroundRadius) {
        PanelConnectedTile(index = tileIndex, itemCount = tileCount) {
          val radius = (backgroundRadius ?: preferences.backgroundRadius.get()).coerceIn(0, 40)
          SliderItem(
            stringResource(R.string.player_sheets_subtitles_background_radius),
            value = radius,
            valueText = radius.toString(),
            onChange = {
              preferences.backgroundRadius.set(it)
              PlaybackSession.setPropertyInt("sub-back-radius", it)
            },
            max = 40,
            enabled = !ownsAny(backgroundRadiusOptions),
            icon = { Icon(Icons.RoundedFilled.RoundedCorner, null) },
          )
        }
      }
    }
  }
}

fun resetTypography(
  preferences: SubtitlesPreferences,
  effectiveDefaultFont: String = DEFAULT_SUBTITLE_FONT_FAMILY,
) {
  val bold = preferences.bold.deleteAndGet()
  val italic = preferences.italic.deleteAndGet()
  val justify = preferences.justification.deleteAndGet().value
  val font = preferences.font.deleteAndGet().ifBlank { effectiveDefaultFont }
  val fontSize = preferences.fontSize.deleteAndGet()
  val borderSize = preferences.borderSize.deleteAndGet()
  val shadowOffset = preferences.shadowOffset.deleteAndGet()
  val backgroundRadius = preferences.backgroundRadius.deleteAndGet()
  val borderStyle = preferences.borderStyle.deleteAndGet().value

  PlaybackSession.setPropertyInt("sub-font-size", fontSize)
  // Secondary subtitles share the primary style in mpv (no secondary-sub-* style
  // options exist for font/bold/italic/justify/border/shadow) — only set sub-*.
  PlaybackSession.setPropertyBoolean("sub-bold", bold)
  PlaybackSession.setPropertyBoolean("sub-italic", italic)
  PlaybackSession.setPropertyString("sub-justify", justify)
  PlaybackSession.setPropertyString("sub-font", font)
  PlaybackSession.setPropertyInt("sub-border-size", borderSize)
  PlaybackSession.setPropertyInt("sub-outline-size", borderSize)
  PlaybackSession.setPropertyInt("sub-shadow-offset", shadowOffset)
  PlaybackSession.setPropertyInt("sub-back-radius", backgroundRadius)
  PlaybackSession.setPropertyString("sub-border-style", borderStyle)
  PlaybackSession.setPropertyBoolean("sub-ass-justify", false)
}

enum class SubtitlesBorderStyle(
  val value: String,
  @StringRes val titleRes: Int,
) {
  OutlineAndShadow("outline-and-shadow", R.string.player_sheets_subtitles_border_style_outline_and_shadow),
  OpaqueBox("opaque-box", R.string.player_sheets_subtitles_border_style_opaque_box),
  BackgroundBox("background-box", R.string.player_sheets_subtitles_border_style_background_box),
}
