/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.data.lyrics.LyricsLanguageOptions
import app.gyrolet.mpvrx.preferences.AudioChannels
import app.gyrolet.mpvrx.preferences.AudioOutput
import app.gyrolet.mpvrx.preferences.AudioPassthrough
import app.gyrolet.mpvrx.preferences.AudioPlayerOrientation
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.AudioVisualizerStyle
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.LyricsTranslationDisplayMode
import app.gyrolet.mpvrx.preferences.MediaLibraryType
import app.gyrolet.mpvrx.preferences.MediaServerPreferences
import app.gyrolet.mpvrx.preferences.MusicSourceProvider
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.browser.music.MusicTab
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.currentMpvConfigOverrideOptions
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import app.gyrolet.mpvrx.presentation.components.AppTextField as TextField
import app.gyrolet.mpvrx.ui.components.AppSlider as Slider
import app.gyrolet.mpvrx.ui.preferences.components.AppListPreference as ListPreference
import app.gyrolet.mpvrx.ui.preferences.components.AppSliderPreference as SliderPreference
import app.gyrolet.mpvrx.ui.preferences.components.AppTextFieldPreference as TextFieldPreference

@Serializable
object AudioPreferencesScreen : ScrollableSettingsScreen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val backstack = LocalBackStack.current
    val playerPanel = LocalPlayerSettingsPanel.current
    val preferences = koinInject<AudioPreferences>()
    val configOwnedOptions = currentMpvConfigOverrideOptions()
    val browserPreferences = koinInject<BrowserPreferences>()
    val playerPreferences = koinInject<PlayerPreferences>()

    Scaffold(
      contentWindowInsets = settingsContentWindowInsets(),
      topBar = {
        SettingsTopAppBar(
          title = stringResource(R.string.pref_audio),
          onBack = { backstack.popSafely() },
        )
      },
    ) { padding ->
      ProvideExpressivePreferenceLocals {
        val (settingsListState, settingsHighlight) =
          rememberSettingsSearchList(AudioPreferencesScreen, MaterialTheme.colorScheme.primary)
        LazyColumn(
          state = settingsListState,
          contentPadding = settingsContentPadding(),
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .then(settingsHighlight),
        ) {
          if (!playerPanel) {
            item {
              PreferenceSectionHeader(
                position = PreferenceSectionPosition.First,
                title = stringResource(R.string.pref_media_library_section),
                modifier = Modifier.settingsSearchTarget(R.string.pref_audio),
              )
            }

            item {
              PreferenceCard {
                val includeAudioBrowser by browserPreferences.includeAudioBrowser.collectAsState()
                SwitchPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.ui_include_audio_files),
                  value = includeAudioBrowser,
                  onValueChange = { enabled ->
                    browserPreferences.includeAudioBrowser.set(enabled)
                    if (!enabled) {
                      browserPreferences.mediaLibraryType.set(MediaLibraryType.Video)
                    }
                    // Files/folders use the same MediaScanOptions as the rest of the browser.
                    // Notify active browser screens immediately instead of waiting for a restart.
                    MediaLibraryEvents.notifyChanged()
                  },
                  title = {
                    Text(
                      androidx.compose.ui.res
                        .stringResource(app.gyrolet.mpvrx.R.string.ui_include_audio_files),
                    )
                  },
                  summary = {
                    Text(
                      androidx.compose.ui.res.stringResource(
                        app.gyrolet.mpvrx.R.string.ui_show_audio_files_in_the_browser,
                      ),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )

                // Minimum duration is a library filter, not a child setting of "Include audio files".
                // It always applies to the dedicated Music tab, and it also applies to audio shown
                // alongside videos in Files/Folders whenever that separate switch is enabled.
                PreferenceDivider()
                val minimumAudioDurationSeconds by browserPreferences.minimumAudioDurationSeconds.collectAsState()
                val maxMinimumDurationSeconds = 120f
                val minimumDurationLabel =
                  when {
                    minimumAudioDurationSeconds <= 0 -> "Any duration"
                    minimumAudioDurationSeconds < 60 -> "${minimumAudioDurationSeconds}s and longer"
                    minimumAudioDurationSeconds % 60 == 0 -> "${minimumAudioDurationSeconds / 60} min and longer"
                    else ->
                      "${minimumAudioDurationSeconds / 60}m ${minimumAudioDurationSeconds % 60}s and longer"
                  }

                Column(
                  modifier =
                    Modifier
                      .settingsSearchTarget(R.string.ui_minimum_audio_duration)
                      .fillMaxWidth()
                      .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                  Text(
                    androidx.compose.ui.res
                      .stringResource(app.gyrolet.mpvrx.R.string.ui_minimum_audio_duration),
                    style = MaterialTheme.typography.bodyLarge,
                  )
                  Text(
                    minimumDurationLabel,
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodyMedium,
                  )
                  Slider(
                    value =
                      minimumAudioDurationSeconds
                        .toFloat()
                        .coerceIn(0f, maxMinimumDurationSeconds),
                    onValueChange = { minimumSeconds ->
                      browserPreferences.minimumAudioDurationSeconds.set(
                        minimumSeconds.toInt().coerceIn(0, maxMinimumDurationSeconds.toInt()),
                      )
                    },
                    onValueChangeFinished = { MediaLibraryEvents.notifyChanged() },
                    valueRange = 0f..maxMinimumDurationSeconds,
                    steps = 23,
                  )
                  Text(
                    text =
                      "Applies to Music and audio in Files • Included: ${
                        if (minimumAudioDurationSeconds <= 0) {
                          "0s"
                        } else {
                          minimumDurationLabel.removeSuffix(" and longer")
                        }
                      } – no limit",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall,
                  )
                }

                PreferenceDivider()
                val mediaServerPreferences = koinInject<MediaServerPreferences>()
                val musicSourceProvider by mediaServerPreferences.musicSourceProvider.collectAsState()
                ExpressivePreferenceItem {
                  ListPreference(
                    modifier = Modifier.settingsSearchTarget(R.string.pref_music_player_switch_title),
                    value = musicSourceProvider,
                    onValueChange = { mediaServerPreferences.musicSourceProvider.set(it) },
                    values =
                      listOf(
                        MusicSourceProvider.LOCAL,
                        MusicSourceProvider.JELLYFIN,
                        MusicSourceProvider.NAVIDROME,
                        MusicSourceProvider.AUDIOBOOKS,
                      ),
                    valueToText = { source ->
                      AnnotatedString(
                        when (source) {
                          MusicSourceProvider.LOCAL -> context.getString(R.string.music_source_local)
                          MusicSourceProvider.JELLYFIN -> context.getString(R.string.music_source_jellyfin)
                          MusicSourceProvider.NAVIDROME -> context.getString(R.string.music_source_navidrome)
                          MusicSourceProvider.AUDIOBOOKS -> context.getString(R.string.audiobooks_title)
                        },
                      )
                    },
                    title = { Text(stringResource(R.string.pref_music_player_switch_title)) },
                    summary = {
                      Text(
                        text =
                          when (musicSourceProvider) {
                            MusicSourceProvider.LOCAL -> stringResource(R.string.music_source_local)
                            MusicSourceProvider.JELLYFIN -> stringResource(R.string.music_source_jellyfin)
                            MusicSourceProvider.NAVIDROME -> stringResource(R.string.music_source_navidrome)
                            MusicSourceProvider.AUDIOBOOKS -> stringResource(R.string.audiobooks_title)
                          },
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                  )
                }
              }
            }

            item {
              PreferenceSectionHeader(
                position = PreferenceSectionPosition.Middle,
                title = stringResource(R.string.pref_music_tabs_title),
                modifier = Modifier.settingsSearchTarget(R.string.pref_music_tabs_title),
              )
            }
            item {
              val enabledMusicTabs by preferences.enabledMusicTabs.collectAsState()
              val musicTabOrder by preferences.musicTabOrder.collectAsState()
              val tabMap = MusicTab.entries.associateBy { it.name }
              val orderedTabs = (musicTabOrder.mapNotNull(tabMap::get) + MusicTab.entries).distinct()
              ExpressivePreferenceGroup {
                ReorderableTabPreferences(
                  tabs =
                    orderedTabs.map { tab ->
                      ReorderableTabItem(
                        id = tab.name,
                        titleRes = tab.titleRes,
                        title = stringResource(tab.titleRes),
                        summary = "",
                        enabled = tab.name in enabledMusicTabs,
                      )
                    },
                  onEnabledChange = { id, enabled ->
                    val next = if (enabled) enabledMusicTabs + id else enabledMusicTabs - id
                    if (next.isNotEmpty()) preferences.enabledMusicTabs.set(next)
                  },
                  onMove = { id, destination ->
                    val next = orderedTabs.map { it.name }.toMutableList()
                    next.remove(id)
                    next.add(destination.coerceIn(0, next.size), id)
                    preferences.musicTabOrder.set(next)
                  },
                )
              }
            }

            item {
              PreferenceSectionHeader(
                position = PreferenceSectionPosition.Middle,
                title = stringResource(R.string.pref_section_appearance),
              )
            }

            item {
              PreferenceCard {
                val audioVisualizerStyle by preferences.audioVisualizerStyle.collectAsState()
                ExpressivePreferenceItem {
                  ListPreference(
                    modifier = Modifier.settingsSearchTarget(R.string.pref_audio_visualizer_style_title),
                    value = audioVisualizerStyle,
                    onValueChange = { preferences.audioVisualizerStyle.set(it) },
                    values = AudioVisualizerStyle.entries,
                    valueToText = { AnnotatedString(resources.getString(it.title)) },
                    title = { Text(stringResource(R.string.pref_audio_visualizer_style_title)) },
                    summary = {
                      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                          stringResource(audioVisualizerStyle.title),
                          color = MaterialTheme.colorScheme.outline,
                        )
                        if (audioVisualizerStyle == AudioVisualizerStyle.Galaxy ||
                          audioVisualizerStyle == AudioVisualizerStyle.Cuboid
                        ) {
                          Text(
                            text =
                              if (audioVisualizerStyle == AudioVisualizerStyle.Cuboid) {
                                "Inspired by the Cuboid Warptunnel concept by Niklas Knaack"
                              } else {
                                stringResource(R.string.pref_audio_visualizer_galaxy_credit)
                              },
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = TextDecoration.Underline,
                            modifier =
                              Modifier.clickable {
                                context.startActivity(
                                  Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(
                                      if (audioVisualizerStyle == AudioVisualizerStyle.Cuboid) {
                                        "https://codepen.io/NiklasKnaack/pen/WyWqja"
                                      } else {
                                        "https://codepen.io/Zain-Raza-the-sasster/pen/ByBeKqa"
                                      },
                                    ),
                                  ),
                                )
                              },
                          )
                        }
                      }
                    },
                  )
                }

                PreferenceDivider()
                DevicePreference(R.string.pref_audio_orientation_title) {
                  val audioOrientation by preferences.audioOrientation.collectAsState()
                  ExpressivePreferenceItem {
                    ListPreference(
                      modifier = Modifier.settingsSearchTarget(R.string.pref_audio_orientation_title),
                      value = audioOrientation,
                      onValueChange = { preferences.audioOrientation.set(it) },
                      values = AudioPlayerOrientation.entries,
                      valueToText = { AnnotatedString(resources.getString(it.titleRes)) },
                      title = { Text(stringResource(R.string.pref_audio_orientation_title)) },
                      summary = {
                        Text(
                          stringResource(audioOrientation.titleRes),
                          color = MaterialTheme.colorScheme.outline,
                        )
                      },
                    )
                  }

                  PreferenceDivider()
                }

                val audioAmbientMode by preferences.audioAmbientMode.collectAsState()
                SwitchPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_audio_ambient_mode_title),
                  value = audioAmbientMode,
                  onValueChange = { preferences.audioAmbientMode.set(it) },
                  title = { Text(stringResource(R.string.pref_audio_ambient_mode_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_audio_ambient_mode_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )

                PreferenceDivider()
                val audioWavySeekbar by preferences.audioWavySeekbar.collectAsState()
                SwitchPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_audio_wavy_seekbar_title),
                  value = audioWavySeekbar,
                  onValueChange = { preferences.audioWavySeekbar.set(it) },
                  title = { Text(stringResource(R.string.pref_audio_wavy_seekbar_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_audio_wavy_seekbar_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )

                PreferenceDivider()
                val immersiveArtwork by preferences.immersiveArtwork.collectAsState()
                SwitchPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_audio_immersive_artwork_title),
                  value = immersiveArtwork,
                  onValueChange = { preferences.immersiveArtwork.set(it) },
                  title = { Text(stringResource(R.string.pref_audio_immersive_artwork_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_audio_immersive_artwork_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )

                PreferenceDivider()
                val highQualityArtwork by preferences.highQualityArtwork.collectAsState()
                SwitchPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_audio_high_quality_artwork_title),
                  value = highQualityArtwork,
                  onValueChange = { preferences.highQualityArtwork.set(it) },
                  title = { Text(stringResource(R.string.pref_audio_high_quality_artwork_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_audio_high_quality_artwork_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )

                PreferenceDivider()
                val animatedAlbumArtwork by preferences.animatedAlbumArtwork.collectAsState()
                SwitchPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_audio_animated_artwork_title),
                  value = animatedAlbumArtwork,
                  onValueChange = { preferences.animatedAlbumArtwork.set(it) },
                  title = { Text(stringResource(R.string.pref_audio_animated_artwork_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_audio_animated_artwork_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )

                PreferenceDivider()
                val animatedArtworkAppleMusic by preferences.animatedArtworkAppleMusic.collectAsState()
                SwitchPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_audio_animated_artwork_apple_music_title),
                  value = animatedArtworkAppleMusic,
                  onValueChange = { preferences.animatedArtworkAppleMusic.set(it) },
                  enabled = animatedAlbumArtwork,
                  title = { Text(stringResource(R.string.pref_audio_animated_artwork_apple_music_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_audio_animated_artwork_apple_music_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )

                PreferenceDivider()
                val animatedArtworkTidal by preferences.animatedArtworkTidal.collectAsState()
                SwitchPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_audio_animated_artwork_tidal_title),
                  value = animatedArtworkTidal,
                  onValueChange = { preferences.animatedArtworkTidal.set(it) },
                  enabled = animatedAlbumArtwork,
                  title = { Text(stringResource(R.string.pref_audio_animated_artwork_tidal_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_audio_animated_artwork_tidal_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )

                if (!app.gyrolet.mpvrx.BuildConfig.IS_PLAY_STORE_BUILD) {
                  PreferenceDivider()
                  val animatedArtworkSpotify by preferences.animatedArtworkSpotify.collectAsState()
                  val spotifyCookie by preferences.spotifySpDcCookie.collectAsState()
                  SwitchPreference(
                    modifier = Modifier.settingsSearchTarget(R.string.pref_audio_animated_artwork_spotify_title),
                    value = animatedArtworkSpotify,
                    onValueChange = { preferences.animatedArtworkSpotify.set(it) },
                    enabled = animatedAlbumArtwork,
                    title = { Text(stringResource(R.string.pref_audio_animated_artwork_spotify_title)) },
                    summary = {
                      Text(
                        stringResource(
                          if (spotifyCookie.isNotBlank()) {
                            R.string.pref_audio_animated_artwork_spotify_summary
                          } else {
                            R.string.pref_audio_animated_artwork_spotify_needs_cookie
                          },
                        ),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                  )

                  PreferenceDivider()
                  ExpressivePreferenceItem {
                    TextFieldPreference(
                      modifier = Modifier.settingsSearchTarget(R.string.pref_spotify_sp_dc_cookie),
                      value = spotifyCookie,
                      onValueChange = { preferences.spotifySpDcCookie.set(it) },
                      textToValue = { it.trim() },
                      enabled = animatedAlbumArtwork && animatedArtworkSpotify,
                      title = { Text(stringResource(R.string.pref_spotify_sp_dc_cookie)) },
                      summary = {
                        Text(
                          text =
                            stringResource(
                              if (spotifyCookie.isBlank()) {
                                R.string.pref_spotify_cookie_not_set
                              } else {
                                R.string.pref_spotify_cookie_set
                              },
                            ),
                          color = MaterialTheme.colorScheme.outline,
                        )
                      },
                      textField = { value, onValueChange, _ ->
                        Column {
                          Text(stringResource(R.string.pref_spotify_sp_dc_cookie_dialog_desc))
                          TextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                          )
                        }
                      },
                    )
                  }
                }
              }
            }
          }
          item {
            PreferenceSectionHeader(
              position = PreferenceSectionPosition.Last,
              title = stringResource(R.string.pref_section_playback),
            )
          }

          item {
            PreferenceCard {
              val preferredLanguages by preferences.preferredLanguages.collectAsState()
              ExpressivePreferenceItem {
                TextFieldPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_preferred_languages),
                  value = preferredLanguages,
                  enabled = "alang" !in configOwnedOptions,
                  onValueChange = { preferences.preferredLanguages.set(it) },
                  textToValue = { input ->
                    input
                      .split(",")
                      .map { it.trim().lowercase() }
                      .filter { it.isNotEmpty() }
                      .joinToString(",")
                  },
                  title = { Text(stringResource(R.string.pref_preferred_languages)) },
                  summary = {
                    if (preferredLanguages.isNotBlank()) {
                      Text(
                        preferredLanguages,
                        color = MaterialTheme.colorScheme.outline,
                      )
                    } else {
                      Text(
                        stringResource(R.string.not_set_video_default),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    }
                  },
                  textField = { value, onValueChange, _ ->
                    Column {
                      Text(stringResource(R.string.pref_audio_preferred_language))
                      TextField(
                        value,
                        onValueChange,
                        modifier = Modifier.fillMaxWidth(),
                      )
                    }
                  },
                )
              }

              PreferenceDivider()
              val audioPitchCorrection by preferences.audioPitchCorrection.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_audio_pitch_correction_title),
                value = audioPitchCorrection,
                enabled = "audio-pitch-correction" !in configOwnedOptions,
                onValueChange = { preferences.audioPitchCorrection.set(it) },
                title = { Text(stringResource(R.string.pref_audio_pitch_correction_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_audio_pitch_correction_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              if (app.gyrolet.mpvrx.BuildConfig.PLAYER_ENGINE_IS_MPV) {
                PreferenceDivider()
                val volumeNormalization by preferences.volumeNormalization.collectAsState()
                SwitchPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_audio_volume_normalization_title),
                  value = volumeNormalization,
                  enabled = "af" !in configOwnedOptions,
                  onValueChange = { preferences.volumeNormalization.set(it) },
                  title = { Text(stringResource(R.string.pref_audio_volume_normalization_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_audio_volume_normalization_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )

                PreferenceDivider()
                val spatialAudio by preferences.spatialAudio.collectAsState()
                SwitchPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_audio_spatial_title),
                  value = spatialAudio,
                  enabled = "af" !in configOwnedOptions,
                  onValueChange = { preferences.spatialAudio.set(it) },
                  title = { Text(stringResource(R.string.pref_audio_spatial_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_audio_spatial_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )

                PreferenceDivider()
                val drcEnabled by preferences.drcEnabled.collectAsState()
                SwitchPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_audio_drc_title),
                  value = drcEnabled,
                  enabled = "af" !in configOwnedOptions,
                  onValueChange = { preferences.drcEnabled.set(it) },
                  title = { Text(stringResource(R.string.pref_audio_drc_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_audio_drc_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )
              }

              PreferenceDivider()
              val ignoreAudioFocus by preferences.ignoreAudioFocus.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_ignore_audio_focus_title),
                value = ignoreAudioFocus,
                onValueChange = preferences.ignoreAudioFocus::set,
                title = { Text(stringResource(R.string.pref_ignore_audio_focus_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_ignore_audio_focus_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()
              val autoplayNextAudio by playerPreferences.autoplayNextAudio.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_autoplay_next_audio_title),
                value = autoplayNextAudio,
                onValueChange = playerPreferences.autoplayNextAudio::set,
                title = { Text(stringResource(R.string.pref_autoplay_next_audio_title)) },
                summary = {
                  Text(
                    if (autoplayNextAudio) {
                      stringResource(R.string.pref_autoplay_next_audio_summary)
                    } else {
                      stringResource(R.string.pref_autoplay_next_audio_summary_disabled)
                    },
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              if (app.gyrolet.mpvrx.BuildConfig.PLAYER_ENGINE_IS_MPV) {
                PreferenceDivider()
                val audioChannel by preferences.audioChannels.collectAsState()
                ExpressivePreferenceItem {
                  ListPreference(
                    modifier = Modifier.settingsSearchTarget(R.string.pref_audio_channels),
                    value = audioChannel,
                    onValueChange = { preferences.audioChannels.set(it) },
                    values = AudioChannels.entries,
                    enabled = setOf("audio-channels", "af").none(configOwnedOptions::contains),
                    valueToText = { AnnotatedString(resources.getString(it.title)) },
                    title = { Text(text = stringResource(id = R.string.pref_audio_channels)) },
                    summary = {
                      Text(
                        text = stringResource(audioChannel.title),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                  )
                }
              }

              PreferenceDivider()
              val volumeBoostCap by preferences.volumeBoostCap.collectAsState()
              ExpressivePreferenceItem {
                SliderPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_audio_volume_boost_cap),
                  value = volumeBoostCap.toFloat(),
                  enabled = "volume-max" !in configOwnedOptions,
                  onValueChange = { preferences.volumeBoostCap.set(it.toInt()) },
                  title = { Text(stringResource(R.string.pref_audio_volume_boost_cap)) },
                  valueRange = 0f..200f,
                  summary = {
                    Text(
                      if (volumeBoostCap == 0) {
                        stringResource(R.string.generic_disabled)
                      } else {
                        volumeBoostCap.toString()
                      },
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  onSliderValueChange = { preferences.volumeBoostCap.set(it.toInt()) },
                  sliderValue = volumeBoostCap.toFloat(),
                )
              }

              PreferenceDivider()
              val audioOutput by preferences.audioOutput.collectAsState()
              val audioPassthrough by preferences.audioPassthrough.collectAsState()
              // Passthrough needs AudioTrack, so it overrides this choice while it's on.
              val passthroughOwnsOutput =
                audioPassthrough != AudioPassthrough.Off &&
                  "audio-spdif" !in configOwnedOptions
              ExpressivePreferenceItem {
                ListPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_audio_output_title),
                  value = audioOutput,
                  onValueChange = { preferences.audioOutput.set(it) },
                  values = AudioOutput.entries,
                  enabled = "ao" !in configOwnedOptions && !passthroughOwnsOutput,
                  valueToText = { AnnotatedString(resources.getString(it.titleRes)) },
                  title = { Text(stringResource(R.string.pref_audio_output_title)) },
                  summary = {
                    Text(
                      text =
                        stringResource(
                          if (passthroughOwnsOutput && "ao" !in configOwnedOptions) {
                            R.string.pref_audio_output_passthrough
                          } else {
                            audioOutput.titleRes
                          },
                        ),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )
              }

              PreferenceDivider()
              ExpressivePreferenceItem {
                ListPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_audio_passthrough_title),
                  value = audioPassthrough,
                  onValueChange = { preferences.audioPassthrough.set(it) },
                  values = AudioPassthrough.entries,
                  enabled = "audio-spdif" !in configOwnedOptions,
                  valueToText = { AnnotatedString(resources.getString(it.titleRes)) },
                  title = { Text(stringResource(R.string.pref_audio_passthrough_title)) },
                  summary = {
                    Column {
                      Text(
                        text = stringResource(audioPassthrough.titleRes),
                        color = MaterialTheme.colorScheme.outline,
                      )
                      Text(
                        text = stringResource(R.string.pref_audio_passthrough_summary),
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodySmall,
                      )
                    }
                  },
                )
              }
            }
          }

          if (playerPanel) return@LazyColumn
          item {
            PreferenceSectionHeader(
              position = PreferenceSectionPosition.Middle,
              title = stringResource(R.string.pref_lyrics_sources_category),
            )
          }
          item {
            PreferenceCard {
              val autoFetchLyricsOnline by preferences.lyricsAutoFetchOnline.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_lyrics_auto_fetch_online),
                value = autoFetchLyricsOnline,
                onValueChange = preferences.lyricsAutoFetchOnline::set,
                title = { Text(stringResource(R.string.pref_lyrics_auto_fetch_online)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_lyrics_auto_fetch_online_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              DevicePreference(R.string.pref_floating_lyrics_title) {
                PreferenceDivider()
                FloatingLyricsPreference(preferences)
              }

              if (!app.gyrolet.mpvrx.BuildConfig.IS_PLAY_STORE_BUILD) {
                PreferenceDivider()
                val lyricsAutoTranslate by preferences.lyricsAutoTranslate.collectAsState()
                SwitchPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_lyrics_auto_translate),
                  value = lyricsAutoTranslate,
                  onValueChange = { preferences.lyricsAutoTranslate.set(it) },
                  title = { Text(stringResource(R.string.pref_lyrics_auto_translate)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_lyrics_auto_translate_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                )

                PreferenceDivider()
                val lyricsTargetLanguage by preferences.lyricsTargetLanguage.collectAsState()
                ExpressivePreferenceItem {
                  ListPreference(
                    modifier = Modifier.settingsSearchTarget(R.string.pref_lyrics_target_language),
                    value = lyricsTargetLanguage,
                    onValueChange = { preferences.lyricsTargetLanguage.set(it) },
                    values = LyricsLanguageOptions.ALL_LANGUAGES.map { it.code },
                    valueToText = { AnnotatedString(LyricsLanguageOptions.getDisplayName(it)) },
                    title = { Text(stringResource(R.string.pref_lyrics_target_language)) },
                    summary = {
                      Text(
                        text = LyricsLanguageOptions.getDisplayName(lyricsTargetLanguage),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                  )
                }

                PreferenceDivider()
                val lyricsDisplayMode by preferences.lyricsTranslationDisplayMode.collectAsState()
                ExpressivePreferenceItem {
                  ListPreference(
                    modifier = Modifier.settingsSearchTarget(R.string.pref_lyrics_display_mode),
                    value = lyricsDisplayMode,
                    onValueChange = { preferences.lyricsTranslationDisplayMode.set(it) },
                    values = LyricsTranslationDisplayMode.entries,
                    valueToText = { AnnotatedString(resources.getString(it.title)) },
                    title = { Text(stringResource(R.string.pref_lyrics_display_mode)) },
                    summary = {
                      Text(
                        text = stringResource(lyricsDisplayMode.title),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}
