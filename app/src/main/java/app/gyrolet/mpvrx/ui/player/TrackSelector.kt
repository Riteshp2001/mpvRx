/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.util.Log
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.MpvConfigControlledFeatures
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import java.util.Locale
import java.util.MissingResourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Selects tracks using ordered language or title preferences, with saved selections as fallback.
 * A saved subtitles-off choice remains off. mpv.conf-owned language selection is left to mpv.
 *
 * Based on https://github.com/Chinna95P/mpv-anime-build/blob/main/scripts/track-selector.lua.
 */
class TrackSelector(
  private val audioPreferences: AudioPreferences,
  private val subtitlesPreferences: SubtitlesPreferences,
) {
  private data class Track(
    val id: Int,
    val type: String,
    val lang: String,
    val title: String,
    val isDefault: Boolean,
    val forced: Boolean,
    val hearing: Boolean,
    val external: Boolean,
    val image: Boolean,
  )

  suspend fun onFileLoaded(
    hasState: Boolean = false,
    expectedGeneration: Long = PlaybackSession.state.value.generation,
    savedSubtitlesOff: Boolean = false,
  ) = withContext(Dispatchers.IO) {
    var trackCount = 0
    for (attempt in 0 until 20) {
      if (!PlaybackSession.isCurrentGeneration(expectedGeneration)) return@withContext
      trackCount = PlaybackSession.getPropertyInt("track-list/count") ?: 0
      if (trackCount > 0) break
      delay(50)
    }
    if (trackCount == 0) return@withContext
    val tracks = readTracks(expectedGeneration) ?: return@withContext
    if (tracks.none { it.type == "video" && !it.image }) return@withContext

    if (!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AUDIO_TRACK_SELECTION)) {
      ensureAudioTrackSelected(tracks, hasState, expectedGeneration)
    }
    if (!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.SUBTITLE_TRACK_SELECTION)) {
      ensureSubtitleTrackSelected(tracks, hasState, expectedGeneration, savedSubtitlesOff)
    }
  }

  private fun readTracks(generation: Long): List<Track>? =
    PlaybackSession.readLoadedMedia(generation) { reader ->
      buildList {
        val count = reader.int("track-list/count") ?: 0
        for (index in 0 until count) {
          val prefix = "track-list/$index"
          val id = reader.int("$prefix/id") ?: continue
          val type = reader.string("$prefix/type") ?: continue
          add(
            Track(
              id = id,
              type = type,
              lang = reader.string("$prefix/lang").orEmpty().lowercase(Locale.ROOT),
              title = reader.string("$prefix/title").orEmpty().lowercase(Locale.ROOT),
              isDefault = reader.string("$prefix/default") == "yes",
              forced = reader.string("$prefix/forced") == "yes",
              hearing = reader.string("$prefix/hearing-impaired") == "yes",
              external = reader.string("$prefix/external") == "yes",
              image = reader.string("$prefix/image") == "yes",
            ),
          )
        }
      }
    }

  private fun ensureAudioTrackSelected(tracks: List<Track>, hasState: Boolean, generation: Long) {
    val current = getTrackSelectionId("aid")
    val audioTracks = tracks.filter { it.type == "audio" }
    val preferences = preferences(audioPreferences.preferredLanguages.get())
    if (hasState && preferences.isEmpty() && audioTracks.any { it.id == current }) return

    val cleanTracks = audioTracks.filter { track -> ignoredAudioTitles.none { track.title.contains(it) } }
    for (preference in preferences) {
      val match = cleanTracks.mapNotNull { track -> matchRank(track, preference)?.let { track to it } }
        .minByOrNull { it.second }?.first
      if (match != null) {
        selectTrack("aid", match.id, generation, "Preferred audio: $preference")
        return
      }
    }
    if (audioTracks.any { it.id == current }) return
    cleanTracks.firstOrNull()?.let { selectTrack("aid", it.id, generation, "Clean audio fallback") }
  }

  private fun ensureSubtitleTrackSelected(
    tracks: List<Track>,
    hasState: Boolean,
    generation: Long,
    savedSubtitlesOff: Boolean,
  ) {
    val current = getTrackSelectionId("sid")
    if (!subtitlesPreferences.autoEnableSubtitles.get()) {
      if (current > 0) selectTrack("sid", 0, generation, "Automatic subtitles disabled")
      return
    }
    if (hasState && savedSubtitlesOff) {
      if (current > 0) selectTrack("sid", 0, generation, "Saved subtitles-off choice")
      return
    }

    val subTracks = tracks.filter { it.type == "sub" }
    val configuredPreferences = preferences(subtitlesPreferences.preferredLanguages.get())
    if (hasState && configuredPreferences.isEmpty() && subTracks.any { it.id == current }) return
    val preferred = configuredPreferences.ifEmpty {
      preferences(PlaybackSession.getPropertyString("slang").orEmpty()).ifEmpty { listOf("en") }
    }
    val anime = detectAnimeContext(tracks, generation)
    val externalTracks = subTracks.filter { it.external }
    val externalFallback =
      externalTracks.firstOrNull { languageTag(it.lang) == null } ?: externalTracks.firstOrNull()
    val nativeFallback =
      if (anime) {
        subTracks.singleOrNull { it.isDefault }?.takeIf { languageTag(it.lang)?.substringBefore('-') == "ja" }
      } else {
        null
      }
    if (configuredPreferences.isEmpty()) {
      (externalFallback ?: nativeFallback)?.let {
        selectTrack("sid", it.id, generation, "Unconfigured subtitle default")
        return
      }
    }

    // Resolve each preference across both embedded and external tracks before trying the next.
    for (preference in preferred) {
      val match = subTracks.mapNotNull { track -> matchRank(track, preference)?.let { track to it } }
        .minWithOrNull(
          compareBy<Pair<Track, Int>> { if (isCleanSubtitle(it.first)) 0 else 1 }
            .thenBy { it.second }
            .thenBy { if (it.first.external) 0 else 1 }
            .thenBy {
              if (anime && dialogueTitleKeywords.any { keyword -> it.first.title.contains(keyword) }) 0 else 1
            }
            .thenBy { if (it.first.isDefault) 0 else 1 },
        )?.first
      if (match != null) {
        selectTrack("sid", match.id, generation, "Preferred subtitle: $preference")
        return
      }
    }
    if (hasState && subTracks.any { it.id == current }) return

    (externalFallback ?: nativeFallback)?.let {
      selectTrack("sid", it.id, generation, "Subtitle default fallback")
      return
    }
    subTracks.firstOrNull { track ->
      languageTag(track.lang) == null && isCleanSubtitle(track) &&
        dialogueTitleKeywords.any { track.title.contains(it) }
    }?.let {
      selectTrack("sid", it.id, generation, "Untitled-language dialogue fallback")
      return
    }
    subTracks.filter(::isCleanSubtitle).singleOrNull()?.let {
      selectTrack("sid", it.id, generation, "Single clean subtitle fallback")
    }
  }

  private fun selectTrack(property: String, id: Int, generation: Long, reason: String) {
    if (!PlaybackSession.isCurrentGeneration(generation) || getTrackSelectionId(property) == id) return
    val value = if (id > 0) id.toString() else "no"
    if (PlaybackSession.commandForGeneration(generation, "set", property, value)) {
      Log.d(TAG, "$reason ($property=$value)")
    }
  }

  private fun isCleanSubtitle(track: Track): Boolean =
    !track.forced && !track.hearing && ignoredSubtitleTitles.none { track.title.contains(it) }

  private fun detectAnimeContext(tracks: List<Track>, generation: Long): Boolean {
    val metadata = PlaybackSession.readLoadedMedia(generation) { reader ->
      listOf("path", "media-title", "filename").map { reader.string(it).orEmpty().lowercase(Locale.ROOT) }
    } ?: return false
    val (path, title, filename) = metadata
    val description = "$path $title"
    if (listOf("live action", "live-action", "liveaction", "drama", "real person").any { description.contains(it) }) {
      return false
    }
    return listOf("/anime/", "\\anime\\", "donghua", "cartoon", "animation", "3d_anime").any { path.contains(it) } ||
      Regex("\\[.*\\]").containsMatchIn(title) ||
      Regex("\\[[0-9a-f]{8}\\]").containsMatchIn(filename) ||
      tracks.any { it.type == "audio" && languageTag(it.lang)?.substringBefore('-') == "ja" }
  }

  private fun matchRank(track: Track, preference: String): Int? {
    if (preference == "*") return 2
    val preferredTag = languageTag(preference)
    if (preferredTag == null) {
      return 2.takeIf {
        SubtitleTitleMatcher.findBestMatchIndex(listOf(track.title), listOf(preference)) != null
      }
    }
    val trackTag = languageTag(track.lang)
    if (trackTag != null) {
      return when {
        trackTag == preferredTag -> 0
        trackTag.substringBefore('-') == preferredTag.substringBefore('-') -> 1
        else -> null
      }
    }
    val locale = Locale.forLanguageTag(preferredTag)
    val titleKeywords = buildList {
      add(preference)
      add(preferredTag)
      add(preferredTag.substringBefore('-'))
      add(locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT))
      add(locale.getDisplayLanguage(locale).lowercase(Locale.ROOT))
      try {
        add(locale.isO3Language)
      } catch (_: MissingResourceException) {
        // Unknown locale names remain eligible for literal title matching above.
      }
    }
    return 2.takeIf { SubtitleTitleMatcher.findBestMatchIndex(listOf(track.title), titleKeywords) != null }
  }

  private fun preferences(value: String): List<String> =
    value.split(',').map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotEmpty).distinct()

  private fun languageTag(value: String): String? {
    val tag = value.trim().lowercase(Locale.ROOT).replace('_', '-')
    val base = languageCodes[tag.substringBefore('-')] ?: return null
    return base + tag.substringAfter('-', "").takeIf(String::isNotEmpty)?.let { "-$it" }.orEmpty()
  }

  private companion object {
    const val TAG = "TrackSelector"
    val ignoredAudioTitles = listOf("commentary", "description", "adh", "comment", "extra")
    val ignoredSubtitleTitles = listOf("signs", "songs", "lyrics", "forced", "sdh", "colored", "karaoke")
    val dialogueTitleKeywords = listOf(
      "subtitle", "dialogue", "dialog", "translation", "english", "main", "script", "full", "caption",
    )
    val languageCodes: Map<String, String> by lazy {
      buildMap {
        for (code in Locale.getISOLanguages()) {
          val locale = Locale.forLanguageTag(code)
          put(code, code)
          put(locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT), code)
          try {
            put(locale.isO3Language.lowercase(Locale.ROOT), code)
          } catch (_: MissingResourceException) {
            // Some Android locales do not expose an ISO 639-2 code.
          }
        }
        putAll(
          mapOf(
            "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh",
            "cze" to "cs", "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de",
            "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms",
            "per" to "fa", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
            "jp" to "ja", "iw" to "he", "in" to "id", "ji" to "yi",
          ),
        )
      }
    }
  }
}
