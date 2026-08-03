/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import androidx.annotation.StringRes
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.getEnum

class AudioPreferences(
  preferenceStore: PreferenceStore,
) {
  val preferredLanguages = preferenceStore.getString("audio_preferred_languages")
  val defaultAudioDelay = preferenceStore.getInt("audio_delay_default")
  val audioPitchCorrection = preferenceStore.getBoolean("audio_pitch_correction", true)
  val audioChannels = preferenceStore.getEnum("audio_channels", AudioChannels.AutoSafe)
  val volumeBoostCap = preferenceStore.getInt("audio_volume_boost_cap", 30)
  val backgroundPlayback = preferenceStore.getBoolean("automatic_background_playback", false)
  /** Audio-player-only background playback; video retains [backgroundPlayback]. */
  val audioBackgroundPlayback = preferenceStore.getBoolean("audio_player_background_playback", false)
  val volumeNormalization = preferenceStore.getBoolean("audio_volume_normalization", false)
  val drcEnabled = preferenceStore.getBoolean("audio_drc_enabled", false)
  val audioBlobEnabled = preferenceStore.getBoolean("audio_blob_enabled", true)
  val audioVisualizerStyle = preferenceStore.getEnum("audio_visualizer_style", AudioVisualizerStyle.Blob)
  val audioOrientation = preferenceStore.getEnum("audio_player_orientation", AudioPlayerOrientation.Auto)

  init {
    // Consolidate the old audio-only screen-lock switch into the single global setting.
    val legacyScreenLockPlayback = preferenceStore.getBoolean("play_audio_after_screen_lock", false)
    if (legacyScreenLockPlayback.get()) backgroundPlayback.set(true)
    if (legacyScreenLockPlayback.isSet()) legacyScreenLockPlayback.delete()
  }
}

enum class AudioPlayerOrientation(
  @StringRes val titleRes: Int,
) {
  Auto(R.string.pref_audio_channels_auto),
  Portrait(R.string.pref_player_orientation_portrait),
  Landscape(R.string.pref_player_orientation_landscape),
}

enum class AudioVisualizerStyle(
  @StringRes val title: Int,
) {
  Blob(R.string.pref_audio_visualizer_style_blob),
  Galaxy(R.string.pref_audio_visualizer_style_galaxy),
  Cuboid(R.string.pref_audio_visualizer_style_cuboid),
  Particle(R.string.pref_audio_visualizer_style_particle),
}


enum class AudioChannels(
  @StringRes val title: Int,
  val property: String,
  val value: String,
) {
  /** MPV "auto-safe" — downmix only when safe; avoids channel layout issues. */
  Auto(R.string.pref_audio_channels_auto, "audio-channels", "auto-safe"),
  /** MPV "auto" — always downmix to stereo. */
  AutoSafe(R.string.pref_audio_channels_auto_safe, "audio-channels", "auto"),
  Mono(R.string.pref_audio_channels_mono, "audio-channels", "mono"),
  Stereo(R.string.pref_audio_channels_stereo, "audio-channels", "stereo"),
  ReverseStereo(R.string.pref_audio_channels_stereo_reversed, "af", "pan=[stereo|c0=c1|c1=c0]"),
}
