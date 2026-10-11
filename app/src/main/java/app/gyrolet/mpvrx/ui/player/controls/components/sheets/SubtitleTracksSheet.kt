/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.presentation.components.AppPickerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetSearchField
import app.gyrolet.mpvrx.presentation.components.PlayerSheetAction
import app.gyrolet.mpvrx.presentation.components.PlayerSheetSectionHeader
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.liquidglass.LocalKyantPlayerBackdrop
import app.gyrolet.mpvrx.ui.player.TrackNode
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale

sealed class SubtitleItem {
  data class Track(
    val node: TrackNode,
    val groupIndex: Int,
    val groupSize: Int,
  ) : SubtitleItem()

  data class Header(
    val title: String,
  ) : SubtitleItem()

  object Divider : SubtitleItem()

  object Off : SubtitleItem()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SubtitlesSheet(
  tracks: ImmutableList<TrackNode>,
  onToggleSubtitle: (Int) -> Unit,
  isSubtitleSelected: (Int) -> Boolean,
  subtitleSelectionIndicator: (Int) -> String?,
  onAddSubtitle: () -> Unit,
  onOpenSubtitleSettings: () -> Unit,
  onOpenSubtitleDelay: () -> Unit,
  onRemoveSubtitle: (Int) -> Unit,
  onOpenOnlineSearch: () -> Unit,
  onDismissRequest: () -> Unit,
  onTranslateSubtitle: (TrackNode, String) -> Unit,
  onGenerateSubtitle: () -> Unit,
  onStartRealtimeSubtitle: (String) -> Unit,
  onStopRealtimeSubtitle: () -> Unit,
  onCancelTranslation: () -> Unit,
  isTranslating: Boolean,
  translationProgress: Float,
  translationStatus: String,
  realtimeSubsStatus: String,
  translationEnabled: Boolean,
  isGeneratingSubtitles: Boolean,
  isRealtimeSubsActive: Boolean,
  realtimeSubsProgress: Float,
  subtitleGenerationProgress: Float,
  subtitleGenerationStatus: String,
  translatingTrackId: Int? = null,
  translatingTrackName: String = "",
  autoTranslateLanguages: String = "",
  aiEnabled: Boolean = true,
  realtimeSubsEnabled: Boolean = true,
  subtitlesOff: Boolean = false,
  onDisableSubtitles: () -> Unit = {},
  delayControlEnabled: Boolean = true,
  modifier: Modifier = Modifier,
) {
  val isTelevision = DeviceFormFactor.isTelevision(LocalContext.current)
  val initialFocusRequester = rememberTvInitialFocusRequester()
  val items =
    remember(tracks, subtitlesOff) {
      val list = mutableListOf<SubtitleItem>()
      list.add(SubtitleItem.Off)
      val internal = tracks.filter { it.external != true }
      val external = tracks.filter { it.external == true }

      if (internal.isNotEmpty() || external.isNotEmpty()) {
        list.add(SubtitleItem.Header(if (internal.isNotEmpty()) "Embedded Subtitles" else "Local Subtitles"))
        internal.forEachIndexed { index, track ->
          list.add(SubtitleItem.Track(track, groupIndex = index, groupSize = internal.size))
        }
        if (internal.isNotEmpty() && external.isNotEmpty()) {
          list.add(SubtitleItem.Header("External Subtitles"))
        }
        external.forEachIndexed { index, track ->
          list.add(SubtitleItem.Track(track, groupIndex = index, groupSize = external.size))
        }
      }

      list.toImmutableList()
    }

  val configuredLanguages =
    remember(autoTranslateLanguages) {
      autoTranslateLanguages.split(",").filter { it.isNotBlank() }
    }

  val allLanguages =
    remember {
      listOf(
        "Afrikaans",
        "Arabic",
        "Bengali",
        "Bulgarian",
        "Catalan",
        "Chinese (Simplified)",
        "Chinese (Traditional)",
        "Croatian",
        "Czech",
        "Danish",
        "Dutch",
        "English",
        "Estonian",
        "Finnish",
        "French",
        "German",
        "Greek",
        "Gujarati",
        "Hebrew",
        "Hindi",
        "Hungarian",
        "Indonesian",
        "Italian",
        "Japanese",
        "Kannada",
        "Korean",
        "Latvian",
        "Lithuanian",
        "Malay",
        "Malayalam",
        "Marathi",
        "Norwegian",
        "Persian",
        "Polish",
        "Portuguese",
        "Punjabi",
        "Romanian",
        "Russian",
        "Serbian",
        "Slovak",
        "Slovenian",
        "Spanish",
        "Swahili",
        "Swedish",
        "Tamil",
        "Telugu",
        "Thai",
        "Turkish",
        "Ukrainian",
        "Urdu",
        "Vietnamese",
      )
    }

  val codeToName =
    remember {
      mapOf(
        "en" to "English",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "it" to "Italian",
        "pt" to "Portuguese",
        "ru" to "Russian",
        "zh" to "Chinese (Simplified)",
        "ja" to "Japanese",
        "ko" to "Korean",
        "ar" to "Arabic",
        "hi" to "Hindi",
        "bn" to "Bengali",
        "vi" to "Vietnamese",
        "te" to "Telugu",
        "ta" to "Tamil",
        "ur" to "Urdu",
        "tr" to "Turkish",
        "pl" to "Polish",
        "uk" to "Ukrainian",
        "nl" to "Dutch",
        "el" to "Greek",
        "hu" to "Hungarian",
        "sv" to "Swedish",
        "cs" to "Czech",
        "ro" to "Romanian",
        "da" to "Danish",
        "fi" to "Finnish",
        "no" to "Norwegian",
        "he" to "Hebrew",
        "id" to "Indonesian",
        "th" to "Thai",
        "ms" to "Malay",
        "fa" to "Persian",
        "sk" to "Slovak",
        "bg" to "Bulgarian",
        "hr" to "Croatian",
        "sr" to "Serbian",
        "sl" to "Slovenian",
        "et" to "Estonian",
        "lv" to "Latvian",
        "lt" to "Lithuanian",
        "af" to "Afrikaans",
        "sw" to "Swahili",
      )
    }

  var langSearch by remember { mutableStateOf("") }
  var showLanguagePicker by remember { androidx.compose.runtime.mutableStateOf<TrackNode?>(null) }
  var showRealtimeLanguagePicker by remember { mutableStateOf(false) }

  if (showLanguagePicker != null || showRealtimeLanguagePicker) {
    val languagesToShow =
      remember(configuredLanguages, langSearch) {
        val source =
          if (configuredLanguages.size >= 2) {
            configuredLanguages.mapNotNull { codeToName[it] }
          } else {
            allLanguages
          }
        if (langSearch.isBlank()) {
          source
        } else {
          source.filter { it.contains(langSearch, ignoreCase = true) }
        }
      }
    AppPickerSheet(
      onDismissRequest = {
        showLanguagePicker = null
        showRealtimeLanguagePicker = false
        langSearch = ""
      },
      title = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_translate_to),
      scrollContent = false,
      actions = {
        androidx.compose.material3.TextButton(onClick = {
          showLanguagePicker = null
          showRealtimeLanguagePicker = false
          langSearch = ""
        }) {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.generic_cancel),
          )
        }
      },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
          PlayerSheetSearchField(
            query = langSearch,
            onQueryChange = { langSearch = it },
            placeholder =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_search_language),
          )
          LazyColumn(modifier = Modifier.height(280.dp)) {
            items(languagesToShow, key = { it }) { lang ->
              Text(
                text = lang,
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clickable {
                      if (showRealtimeLanguagePicker) {
                        onStartRealtimeSubtitle(lang)
                      } else {
                        showLanguagePicker?.let { track -> onTranslateSubtitle(track, lang) }
                      }
                      showLanguagePicker = null
                      showRealtimeLanguagePicker = false
                      langSearch = ""
                    }.padding(MaterialTheme.spacing.medium),
              )
            }
            if (languagesToShow.isEmpty()) {
              item {
                Text(
                  androidx.compose.ui.res
                    .stringResource(app.gyrolet.mpvrx.R.string.ui_no_languages_found),
                  color = MaterialTheme.colorScheme.outline,
                  modifier = Modifier.padding(MaterialTheme.spacing.medium),
                )
              }
            }
          }
        }
    }
  }

  PlayerSheet(
    onDismissRequest = onDismissRequest,
    title = stringResource(R.string.btn_label_subtitles),
  ) {
    LazyColumn(
      modifier = modifier.fillMaxWidth(),
      contentPadding = PaddingValues(bottom = 8.dp),
    ) {
      item(key = "subtitle_actions") {
        AddTrackRow(
          stringResource(R.string.player_sheets_add_ext_sub),
          onAddSubtitle,
          actions = {
            PlayerSheetAction(Icons.RoundedFilled.Search, stringResource(R.string.settings_search_title), onOpenOnlineSearch)
            if (aiEnabled && realtimeSubsEnabled) {
              PlayerSheetAction(
                icon = if (isRealtimeSubsActive) Icons.RoundedFilled.Close else Icons.RoundedFilled.Translate,
                label = stringResource(R.string.pref_stt_title),
                onClick = {
                  if (isRealtimeSubsActive) {
                    onStopRealtimeSubtitle()
                  } else if (configuredLanguages.isEmpty()) {
                    onStartRealtimeSubtitle("")
                  } else if (configuredLanguages.size == 1) {
                    onStartRealtimeSubtitle(codeToName[configuredLanguages.first()] ?: configuredLanguages.first())
                  } else {
                    showRealtimeLanguagePicker = true
                  }
                },
              )
              PlayerSheetAction(
                icon = Icons.RoundedFilled.Subtitles,
                label = stringResource(R.string.ui_include_auto_generated_subtitles),
                onClick = onGenerateSubtitle,
                enabled = !isGeneratingSubtitles,
              )
            }
            PlayerSheetAction(
              icon = Icons.RoundedFilled.Palette,
              label = stringResource(R.string.player_sheets_subtitles_settings_title),
              onClick = onOpenSubtitleSettings,
            )
            PlayerSheetAction(
              icon = Icons.RoundedFilled.AvTimer,
              label = stringResource(R.string.player_sheets_sub_delay_card_title),
              onClick = onOpenSubtitleDelay,
              enabled = delayControlEnabled,
            )
          },
        )
      }

      if (aiEnabled && isTranslating) {
        item(key = "translation_progress") {
        Column(
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              "${translationStatus.ifBlank {
                "Translating"
              }} $translatingTrackName... ${(translationProgress * 100).toInt()}%",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.weight(1f),
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            PlayerSheetAction(
              icon = Icons.RoundedFilled.Close,
              label = stringResource(R.string.ui_cancel_translation),
              onClick = onCancelTranslation,
            )
          }
          LinearProgressIndicator(
            progress = { translationProgress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
          )
        }
        }
      }

      if (aiEnabled && isGeneratingSubtitles) {
        item(key = "subtitle_generation_progress") {
        androidx.compose.foundation.layout.Column(
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            "${subtitleGenerationStatus.ifBlank {
              "Generating subtitles"
            }}... ${(subtitleGenerationProgress * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
          )
          LinearProgressIndicator(
            progress = { subtitleGenerationProgress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
          )
        }
        }
      }

      if (aiEnabled && isRealtimeSubsActive) {
        item(key = "realtime_subtitle_progress") {
        Column(
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            realtimeSubsStatus.ifBlank { stringResource(R.string.pref_stt_title) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          LinearProgressIndicator(
            progress = { realtimeSubsProgress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
          )
        }
        }
      }

        items(
          items,
          key = { item ->
            when (item) {
              is SubtitleItem.Track -> item.node.id
              is SubtitleItem.Header -> item.title
              is SubtitleItem.Off -> "off"
              is SubtitleItem.Divider -> "divider"
            }
          },
          contentType = { item -> item.javaClass.simpleName },
        ) { item ->
          when (item) {
            is SubtitleItem.Track -> {
              val track = item.node
              SubtitleTrackRow(
                track = track,
                isSelected = isSubtitleSelected(track.id),
                selectionIndicator = subtitleSelectionIndicator(track.id),
                isExternal = track.external == true,
                onToggle = { onToggleSubtitle(track.id) },
                onRemove = { onRemoveSubtitle(track.id) },
                onTranslate = {
                  if (translationEnabled) {
                    if (configuredLanguages.size == 1) {
                      val langName = codeToName[configuredLanguages.first()] ?: configuredLanguages.first()
                      onTranslateSubtitle(track, langName)
                    } else {
                      showLanguagePicker = track
                    }
                  }
                },
                translationEnabled = translationEnabled,
                isCurrentlyTranslating = track.id == translatingTrackId,
                groupIndex = item.groupIndex,
                groupSize = item.groupSize,
              )
            }
            is SubtitleItem.Header -> {
              PlayerSheetSectionHeader(item.title)
            }
            is SubtitleItem.Off -> {
              val haptics = rememberAppHaptics()
              val shape = MaterialTheme.shapes.medium
              val isFrostedOff = LocalKyantPlayerBackdrop.current != null
              Surface(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .tvInitialFocus(initialFocusRequester)
                    .tvFocusHighlight(shape)
                    .clip(shape)
                    .selectable(selected = subtitlesOff, role = Role.RadioButton) {
                      onDisableSubtitles()
                      if (!subtitlesOff) haptics.selection(false)
                    },
                shape = shape,
                color =
                  if (subtitlesOff) {
                    if (isFrostedOff) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f) else MaterialTheme.colorScheme.secondaryContainer
                  } else {
                    if (isFrostedOff) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerHigh
                  },
                border =
                  if (subtitlesOff) {
                    BorderStroke(
                      2.dp,
                      Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                      ),
                    )
                  } else {
                    null
                  },
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  if (isTelevision) {
                    RadioButton(selected = subtitlesOff, onClick = null)
                  } else {
                    Checkbox(checked = subtitlesOff, onCheckedChange = null)
                  }
                  Text(
                    stringResource(R.string.player_sheets_off),
                    style = if (subtitlesOff) MaterialTheme.typography.bodyLargeEmphasized else MaterialTheme.typography.bodyLarge,
                    color = if (subtitlesOff) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                  )
                }
              }
            }
            SubtitleItem.Divider -> {
              HorizontalDivider(
                modifier =
                  Modifier.padding(
                    horizontal = MaterialTheme.spacing.medium,
                    vertical = MaterialTheme.spacing.small,
                  ),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
              )
            }
          }
        }
    }
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SubtitleTrackRow(
  track: TrackNode,
  isSelected: Boolean,
  selectionIndicator: String?,
  isExternal: Boolean,
  onToggle: () -> Unit,
  onRemove: () -> Unit,
  onTranslate: () -> Unit,
  translationEnabled: Boolean,
  isCurrentlyTranslating: Boolean = false,
  groupIndex: Int? = null,
  groupSize: Int? = null,
  modifier: Modifier = Modifier,
) {
  val isTelevision = DeviceFormFactor.isTelevision(LocalContext.current)
  val haptics = rememberAppHaptics()
  val shape =
    if (groupIndex != null && groupSize != null && groupSize > 0) {
      rememberConnectedShape(
        index = groupIndex,
        itemCount = groupSize,
        layout = ConnectedLayout.Vertical,
      )
    } else {
      MaterialTheme.shapes.medium
    }
  val isFrosted = LocalKyantPlayerBackdrop.current != null
  val containerColor by animateColorAsState(
    targetValue =
      if (isSelected) {
        if (isFrosted) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f) else MaterialTheme.colorScheme.secondaryContainer
      } else {
        if (isFrosted) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerHigh
      },
    animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
    label = "subtitleTrackSelection",
  )
  val selectionBorder =
    BorderStroke(
      2.dp,
      Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
      ),
    )
  val badges = subtitleTrackBadges(track, selectionIndicator, isExternal)
  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 2.dp)
        .tvFocusHighlight(shape)
        .clip(shape)
        .toggleable(value = isSelected, role = Role.Checkbox) { selected ->
          onToggle()
          haptics.selection(selected)
        },
    shape = shape,
    color = containerColor,
    border = if (isSelected) selectionBorder else null,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (isTelevision) {
        RadioButton(selected = isSelected, onClick = null)
      } else {
        Checkbox(checked = isSelected, onCheckedChange = null)
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
          text = getTrackTitle(track),
          style = if (isSelected) MaterialTheme.typography.bodyLargeEmphasized else MaterialTheme.typography.bodyLarge,
          color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        )
        TrackBadgeFlow(badges = badges, selected = isSelected)
      }
      if (isCurrentlyTranslating) {
        androidx.compose.material3.CircularProgressIndicator(
          modifier = Modifier.size(MaterialTheme.spacing.large),
          strokeWidth = MaterialTheme.spacing.smaller,
        )
      }
      if (isExternal) {
        if (translationEnabled) {
          PlayerSheetAction(
            icon = Icons.RoundedFilled.Translate,
            label = stringResource(R.string.ui_translate),
            onClick = onTranslate,
            enabled = !isCurrentlyTranslating,
          )
        }
        PlayerSheetAction(Icons.RoundedFilled.Delete, stringResource(R.string.ui_remove), onRemove)
      }
    }
  }
}

@Composable
private fun subtitleTrackBadges(
  track: TrackNode,
  selectionIndicator: String?,
  isExternal: Boolean,
): List<TrackBadge> {
  val primaryLabel = stringResource(R.string.track_badge_primary)
  val secondaryLabel = stringResource(R.string.track_badge_secondary)
  val defaultLabel = stringResource(R.string.theme_default)
  val forcedLabel = stringResource(R.string.track_badge_forced)
  val externalLabel = stringResource(R.string.track_badge_external)
  return remember(track, selectionIndicator, isExternal, primaryLabel, secondaryLabel, defaultLabel, forcedLabel, externalLabel) {
    buildList {
      selectionIndicator?.let { indicator ->
        val label = when (indicator) {
          "P" -> primaryLabel
          "S" -> secondaryLabel
          else -> indicator
        }
        add(TrackBadge(label, emphasized = true))
      }
      track.effectiveLang?.takeIf(String::isNotBlank)?.let { add(TrackBadge(it.uppercase(Locale.ROOT))) }
      (track.codec?.takeIf(String::isNotBlank) ?: track.codecDesc?.takeIf(String::isNotBlank))
        ?.let { add(TrackBadge(it.uppercase(Locale.ROOT))) }
      if (track.default == true) add(TrackBadge(defaultLabel))
      if (track.forced == true) add(TrackBadge(forcedLabel))
      if (track.hearingImpaired == true) add(TrackBadge("SDH"))
      if (isExternal) add(TrackBadge(externalLabel))
    }.distinctBy(TrackBadge::text)
  }
}
