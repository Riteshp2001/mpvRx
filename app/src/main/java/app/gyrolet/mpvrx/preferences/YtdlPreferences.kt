/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.getEnum
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlCodecPreference
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlContainerPreference
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlHdrPreference
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlPlaylistMode
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlAudioPreference
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlAudioQuality

class YtdlPreferences(
  preferenceStore: PreferenceStore,
) {
  val ytdlFormat = preferenceStore.getString("video_ytdl_format", "")
  val ytdlQuality = preferenceStore.getInt("ytdl_quality", -1) // -1 for any
  val preferH264 = preferenceStore.getBoolean("ytdl_prefer_h264", false)
  val codecPreference = preferenceStore.getEnum("ytdl_codec_preference", YtdlCodecPreference.AUTO)
  val maxFps = preferenceStore.getInt("ytdl_max_fps", 0)
  val hdrPreference = preferenceStore.getEnum("ytdl_hdr_preference", YtdlHdrPreference.ANY)
  val containerPreference = preferenceStore.getEnum("ytdl_container_preference", YtdlContainerPreference.ANY)
  val audioPreference = preferenceStore.getEnum("ytdl_audio_preference", YtdlAudioPreference.AUTO)
  val audioQuality = preferenceStore.getEnum("ytdl_audio_quality", YtdlAudioQuality.AUTO)
  val formatSort = preferenceStore.getString("ytdl_format_sort", "")
  val mergeOutputFormat = preferenceStore.getString("ytdl_merge_output_format", "")

  // Subtitles and advanced configurations
  val writeSubs = preferenceStore.getBoolean("ytdl_write_subs", true)
  val writeAutoSubs = preferenceStore.getBoolean("ytdl_write_auto_subs", false)
  val subtitleLanguages = preferenceStore.getString("ytdl_subtitle_languages", "")
  val customUserAgent = preferenceStore.getString("ytdl_custom_user_agent", "")
  val referer = preferenceStore.getString("ytdl_referer", "")
  val cookiesFile = preferenceStore.getString("ytdl_cookies_file", "")
  val proxy = preferenceStore.getString("ytdl_proxy", "")
  val extractorArgs = preferenceStore.getString("ytdl_extractor_args", "")
  val geoBypass = preferenceStore.getBoolean("ytdl_geo_bypass", false)
  val playlistMode = preferenceStore.getEnum("ytdl_playlist_mode", YtdlPlaylistMode.DEFAULT)
  val liveFromStart = preferenceStore.getBoolean("ytdl_live_from_start", false)
  val sponsorBlockMark = preferenceStore.getString("ytdl_sponsorblock_mark", "")
  val sponsorBlockRemove = preferenceStore.getString("ytdl_sponsorblock_remove", "")
  val customRawOptions = preferenceStore.getString("ytdl_custom_raw_options", "")
}
