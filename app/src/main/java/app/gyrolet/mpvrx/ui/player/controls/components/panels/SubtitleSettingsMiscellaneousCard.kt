/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.panels

import app.gyrolet.mpvrx.ui.player.PlaybackSession

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.preferences.preference.deleteAndGet
import app.gyrolet.mpvrx.presentation.components.ExpandableCard
import app.gyrolet.mpvrx.presentation.components.SliderItem
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.applySubtitleLayout
import app.gyrolet.mpvrx.ui.player.controls.CARDS_MAX_WIDTH
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.toFixed
import app.gyrolet.mpvrx.ui.player.controls.panelCardsColors
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.currentMpvConfigOverrideOptions
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import org.koin.compose.koinInject

@Composable
fun SubtitlesMiscellaneousCard(modifier: Modifier = Modifier) {
  val preferences = koinInject<SubtitlesPreferences>()
  val playerPreferences = koinInject<PlayerPreferences>()
  val configOwnedOptions = currentMpvConfigOverrideOptions()
  val layoutOptions = setOf("sub-ass-override", "secondary-sub-ass-override", "sub-pos", "secondary-sub-pos")
  val scaleOptions = setOf("sub-scale", "secondary-sub-scale")
  // mpv has no secondary-sub-scale-by-window / secondary-sub-use-margins — secondary
  // inherits the primary windowing behavior.
  val scaleByWindowOptions =
    setOf("sub-scale-by-window", "sub-use-margins")
  val blendOptions = setOf("blend-subtitles")
  val bidiOptions = setOf("sub-vsfilter-bidi-compat")
  val miscellaneousOptions = layoutOptions + scaleOptions + scaleByWindowOptions + blendOptions + bidiOptions
  var isExpanded by remember { mutableStateOf(true) }
  ExpandableCard(
    isExpanded,
    title = {
      Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)) {
        Icon(Icons.RoundedFilled.Tune, null)
        Text(stringResource(R.string.player_sheets_sub_misc_card_title))
      }
    },
    onExpand = { isExpanded = !isExpanded },
    modifier.widthIn(max = CARDS_MAX_WIDTH),
    colors = panelCardsColors(),
  ) {
    ProvidePreferenceLocals {
      Column {
        var overrideAssSubs by remember {
          mutableStateOf(preferences.overrideAssSubs.get())
        }
        LaunchedEffect(preferences.overrideAssSubs.get()) {
          overrideAssSubs = preferences.overrideAssSubs.get()
        }
        SwitchPreference(
          overrideAssSubs,
          enabled = layoutOptions.none(configOwnedOptions::contains),
          onValueChange = {
            overrideAssSubs = it
            preferences.overrideAssSubs.set(it)
            applySubtitleLayout(PlaybackSession.getPropertyInt("sub-pos") ?: preferences.subPos.get(), it)
          },
          title = { Text(stringResource(R.string.player_sheets_sub_override_ass)) },
        )
        var scaleByWindow by remember {
          mutableStateOf(PlaybackSession.getPropertyString("sub-scale-by-window") == "yes")
        }
        SwitchPreference(
          scaleByWindow,
          enabled = scaleByWindowOptions.none(configOwnedOptions::contains),
          onValueChange = {
            scaleByWindow = it
            preferences.scaleByWindow.set(it)
            val value = if (it) "yes" else "no"
            PlaybackSession.setPropertyString("sub-scale-by-window", value)
            PlaybackSession.setPropertyString("sub-use-margins", value)
          },
          title = { Text(stringResource(R.string.player_sheets_sub_scale_by_window)) },
          summary = { Text(stringResource(R.string.player_sheets_sub_scale_by_window_summary)) },
        )
        var secondaryPinchZoom by remember {
          mutableStateOf(preferences.secondarySubPinchZoom.get())
        }
        LaunchedEffect(preferences.secondarySubPinchZoom.get()) {
          secondaryPinchZoom = preferences.secondarySubPinchZoom.get()
        }
        SwitchPreference(
          secondaryPinchZoom,
          enabled = scaleOptions.none(configOwnedOptions::contains),
          onValueChange = {
            secondaryPinchZoom = it
            preferences.secondarySubPinchZoom.set(it)
          },
          title = { Text(stringResource(R.string.pref_subtitles_secondary_pinch_zoom)) },
          summary = { Text(stringResource(R.string.pref_subtitles_secondary_pinch_zoom_summary)) },
        )
        var blendSubtitlesWithVideo by remember {
          mutableStateOf(preferences.blendSubtitlesWithVideo.get())
        }
        SwitchPreference(
          blendSubtitlesWithVideo,
          enabled = blendOptions.none(configOwnedOptions::contains),
          onValueChange = {
            blendSubtitlesWithVideo = it
            preferences.blendSubtitlesWithVideo.set(it)
            val blendMode = if (it && playerPreferences.isAmbientEnabled.get()) "video" else "no"
            PlaybackSession.setPropertyString("blend-subtitles", blendMode)
          },
          title = { Text(stringResource(R.string.player_sheets_sub_blend_with_video)) },
          summary = { Text(stringResource(R.string.player_sheets_sub_blend_with_video_summary)) },
        )
        var forceRtlSubtitles by remember {
          mutableStateOf(preferences.forceRtlSubtitles.get())
        }
        SwitchPreference(
          forceRtlSubtitles,
          enabled = bidiOptions.none(configOwnedOptions::contains),
          onValueChange = {
            forceRtlSubtitles = it
            preferences.forceRtlSubtitles.set(it)
            PlaybackSession.setPropertyString("sub-vsfilter-bidi-compat", if (it) "yes" else "no")
            PlaybackSession.command("sub-reload")
          },
          title = { Text(stringResource(R.string.player_sheets_sub_force_rtl)) },
          summary = { Text(stringResource(R.string.player_sheets_sub_force_rtl_summary)) },
        )
        val subScale by PlaybackSession.propFloat["sub-scale"].collectAsState()
        val subPos by PlaybackSession.propInt["sub-pos"].collectAsState()
        val secondarySid by PlaybackSession.propInt["secondary-sid"].collectAsState()
        val isSecondaryActive = (secondarySid ?: PlaybackSession.getPropertyInt("secondary-sid") ?: 0) > 0
        val secondarySubScale by PlaybackSession.propFloat["secondary-sub-scale"].collectAsState()
        val secondarySubPos by PlaybackSession.propInt["secondary-sub-pos"].collectAsState()
        SliderItem(
          label = stringResource(
            if (isSecondaryActive) R.string.player_sheets_sub_primary_scale else R.string.player_sheets_sub_scale,
          ),
          value = subScale ?: preferences.subScale.get(),
          valueText = (subScale ?: preferences.subScale.get()).toFixed(2).toString(),
          onChange = {
            preferences.subScale.set(it)
            PlaybackSession.setPropertyFloat("sub-scale", it)
          },
          max = 5f,
          enabled = scaleOptions.none(configOwnedOptions::contains),
          icon = {
            Icon(
              Icons.RoundedFilled.FormatSize,
              null,
            )
          },
        )
        SliderItem(
          label = stringResource(
            if (isSecondaryActive) R.string.player_sheets_sub_primary_position else R.string.player_sheets_sub_position,
          ),
          value = subPos ?: preferences.subPos.get(),
          valueText = (subPos ?: preferences.subPos.get()).toString(),
          onChange = {
            preferences.subPos.set(it)
            applySubtitleLayout(it, preferences.overrideAssSubs.get())
          },
          max = 150,
          enabled = layoutOptions.none(configOwnedOptions::contains),
          icon = {
            Icon(
              Icons.RoundedFilled.AlignVerticalCenter,
              null,
            )
          },
        )
        if (isSecondaryActive) {
          SliderItem(
            label = stringResource(R.string.player_sheets_secondary_sub_scale),
            value = secondarySubScale ?: preferences.secondarySubScale.get(),
            valueText = (secondarySubScale ?: preferences.secondarySubScale.get()).toFixed(2).toString(),
            onChange = {
              preferences.secondarySubScale.set(it)
              PlaybackSession.setPropertyFloat("secondary-sub-scale", it)
            },
            max = 5f,
            enabled = scaleOptions.none(configOwnedOptions::contains),
            icon = {
              Icon(Icons.RoundedFilled.FormatSize, null)
            },
          )
          SliderItem(
            label = stringResource(R.string.player_sheets_secondary_sub_position),
            value = secondarySubPos ?: preferences.secondarySubPos.get(),
            valueText = (secondarySubPos ?: preferences.secondarySubPos.get()).toString(),
            onChange = {
              preferences.secondarySubPos.set(it)
              PlaybackSession.setPropertyInt("secondary-sub-pos", it)
            },
            max = 150,
            enabled = layoutOptions.none(configOwnedOptions::contains),
            icon = {
              Icon(Icons.RoundedFilled.AlignVerticalCenter, null)
            },
          )
        }
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(end = MaterialTheme.spacing.medium, bottom = MaterialTheme.spacing.medium),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(
            enabled = miscellaneousOptions.none(configOwnedOptions::contains),
            onClick = {
              val defaultSubPos = preferences.subPos.deleteAndGet()
              preferences.subScale.deleteAndGet().let {
                PlaybackSession.setPropertyFloat("sub-scale", it)
              }
              preferences.secondarySubScale.deleteAndGet().let {
                PlaybackSession.setPropertyFloat("secondary-sub-scale", it)
              }
              preferences.secondarySubPos.delete()
              val defaultOverride = preferences.overrideAssSubs.deleteAndGet()
              overrideAssSubs = defaultOverride
              applySubtitleLayout(defaultSubPos, defaultOverride)
              val defaultScaleByWindow = preferences.scaleByWindow.deleteAndGet()
              scaleByWindow = defaultScaleByWindow
              val scaleValue = if (defaultScaleByWindow) "yes" else "no"
              PlaybackSession.setPropertyString("sub-scale-by-window", scaleValue)
              PlaybackSession.setPropertyString("sub-use-margins", scaleValue)
              val defaultBlendSubtitles = preferences.blendSubtitlesWithVideo.deleteAndGet()
              blendSubtitlesWithVideo = defaultBlendSubtitles
              val blendMode = if (defaultBlendSubtitles && playerPreferences.isAmbientEnabled.get()) "video" else "no"
              PlaybackSession.setPropertyString("blend-subtitles", blendMode)
              val defaultForceRtl = preferences.forceRtlSubtitles.deleteAndGet()
              forceRtlSubtitles = defaultForceRtl
              PlaybackSession.setPropertyString("sub-vsfilter-bidi-compat", if (defaultForceRtl) "yes" else "no")
              PlaybackSession.command("sub-reload")
            },
          ) {
            Row {
              Icon(Icons.RoundedFilled.EditOff, null)
              Text(stringResource(R.string.generic_reset))
            }
          }
        }
      }
    }
  }
}
