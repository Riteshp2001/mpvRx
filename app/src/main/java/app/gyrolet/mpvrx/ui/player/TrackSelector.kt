/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.MpvConfigControlledFeatures
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.utils.logging.Log
import app.gyrolet.mpvrx.utils.media.normalizePlaybackLanguageTag as languageTag
import java.util.Locale
import java.util.MissingResourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Decides audio and subtitle tracks after a file loads, in a fixed order:
 *
 * 1. When mpv.conf owns `alang` (audio) or `slang` (subtitles), that side is left to mpv
 *    entirely — no restore and no correction.
 * 2. A saved manual audio selection is restored earlier, inside the playback-state restore
 *    (see [applySavedAudioSelection]); when it applied, [onFileLoaded] is told through
 *    `audioAlreadyDecided` and the automatic audio step below does not run for this load.
 * 3. Otherwise mpv has already picked by preferred language (`alang`). The automatic step
 *    only corrects that result: it keeps mpv's track when it is a clean track (title free of
 *    commentary/description markers) in any preferred language; otherwise it picks among
 *    clean tracks by preference order, the file's default track winning ties; otherwise it
 *    falls back to a clean track so a commentary track or a missing selection is not left
 *    in place.
 *
 * A saved subtitles-off choice remains off.
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
    audioAlreadyDecided: Boolean = false,
  ) = withContext(Dispatchers.IO) {
    val tracks = awaitTracks(expectedGeneration) ?: return@withContext
    if (tracks.none { it.type == "video" && !it.image }) return@withContext

    if (!audioAlreadyDecided &&
      !MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AUDIO_TRACK_SELECTION)
    ) {
      ensureAudioTrackSelected(tracks, expectedGeneration)
    }
    if (!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.SUBTITLE_TRACK_SELECTION)) {
      ensureSubtitleTrackSelected(tracks, hasState, expectedGeneration, savedSubtitlesOff)
    }
  }

  /**
   * Applies a saved manual audio selection during the playback-state restore, before the
   * position restore completes and before the automatic correction in [onFileLoaded] runs.
   * The caller must already have re-added the file's external audio tracks: a manual pick
   * can point at one of them, and it only exists again once re-added.
   *
   * Returns true when the manual record decided this load (including manual audio-off).
   * Returns false when there is no manual record, when mpv.conf owns audio selection, or
   * when the saved track id is not usable for this load — the file may have been replaced
   * or re-encoded, or the id may now belong to a track of another type. That last case is
   * "not applicable this time", not "record void": the stored record is left untouched and
   * the automatic rules decide this open.
   *
   * Validation reads the same track list the correction step reads anyway; it adds no
   * database query and no other mpv property reads.
   */
  suspend fun applySavedAudioSelection(
    state: PlaybackStateEntity,
    expectedGeneration: Long = PlaybackSession.state.value.generation,
  ): Boolean = withContext(Dispatchers.IO) {
    if (!state.hasManualAudioSelection) return@withContext false
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AUDIO_TRACK_SELECTION)) {
      return@withContext false
    }
    val manualId = state.manualAudioId
    if (manualId < 0) return@withContext false
    if (manualId == 0) {
      selectTrack("aid", 0, expectedGeneration, "Saved manual audio-off")
      return@withContext true
    }
    val tracks = awaitTracks(expectedGeneration) ?: return@withContext false
    // mpv track ids are per type: the video, audio and subtitle lists each number from 1.
    // The search must stay inside the audio tracks — searching the whole list finds the
    // video track first whenever the saved id is 1 and rejects a usable audio track.
    val target = tracks.firstOrNull { it.type == "audio" && it.id == manualId }
    if (target == null) {
      Log.d(TAG, "Saved manual audio track $manualId not usable for this load; keeping the record")
      return@withContext false
    }
    selectTrack("aid", manualId, expectedGeneration, "Saved manual audio")
    true
  }

  /**
   * The automatic audio correction on its own, for loads that have no Activity restore
   * path (detached playback driven by MediaPlaybackService). Same rules as the audio step
   * of [onFileLoaded]; subtitle selection is not part of it.
   */
  suspend fun applyAutomaticAudioSelection(
    expectedGeneration: Long = PlaybackSession.state.value.generation,
  ) = withContext(Dispatchers.IO) {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AUDIO_TRACK_SELECTION)) {
      return@withContext
    }
    val tracks = awaitTracks(expectedGeneration) ?: return@withContext
    ensureAudioTrackSelected(tracks, expectedGeneration)
  }

  /** Waits for the loaded file's track list and reads it once, as [onFileLoaded] always has. */
  private suspend fun awaitTracks(generation: Long): List<Track>? {
    var trackCount = 0
    for (attempt in 0 until 20) {
      if (!PlaybackSession.isCurrentGeneration(generation)) return null
      trackCount = PlaybackSession.getPropertyInt("track-list/count") ?: 0
      if (trackCount > 0) break
      delay(50)
    }
    if (trackCount == 0) return null
    return readTracks(generation)
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

  private fun ensureAudioTrackSelected(tracks: List<Track>, generation: Long) {
    val audioTracks = tracks.filter { it.type == "audio" }
    if (audioTracks.isEmpty()) return
    val currentTrack = audioTracks.firstOrNull { it.id == getTrackSelectionId("aid") }
    val preferences = preferences(audioPreferences.preferredLanguages.get())
    val cleanTracks = audioTracks.filter(::isCleanAudio)

    // Keep mpv's own pick when it is already a clean track in any preferred language.
    // This step only corrects automatic results; a restored manual selection never
    // reaches this function (see onFileLoaded's audioAlreadyDecided).
    if (currentTrack != null && isCleanAudio(currentTrack) &&
      preferences.any { preference -> matchRank(currentTrack, preference) != null }
    ) {
      return
    }

    // Otherwise choose among clean tracks by preference order; on equal rank the file's
    // default track wins.
    for (preference in preferences) {
      val match =
        cleanTracks.mapNotNull { track -> matchRank(track, preference)?.let { track to it } }
          .minWithOrNull(
            compareBy<Pair<Track, Int>> { it.second }
              .thenBy { if (it.first.isDefault) 0 else 1 },
          )?.first
      if (match != null) {
        selectTrack("aid", match.id, generation, "Preferred audio: $preference")
        return
      }
    }

    // Fallback: a clean current track stands (it is mpv's default choice). Only a
    // non-clean current track, or no selection at all, is moved to a clean track.
    if (currentTrack != null && isCleanAudio(currentTrack)) return
    val fallback = cleanTracks.firstOrNull { it.isDefault } ?: cleanTracks.firstOrNull() ?: return
    selectTrack("aid", fallback.id, generation, "Clean audio fallback")
  }

  private fun isCleanAudio(track: Track): Boolean =
    ignoredAudioTitles.none { track.title.contains(it) }

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

  private companion object {
    const val TAG = "TrackSelector"
    // Substring matches on the lowercased title; the plural forms are listed explicitly so
    // "comments", "descriptions" and "extras" tracks are excluded as well.
    val ignoredAudioTitles =
      listOf("commentary", "commentaries", "comment", "comments", "description", "descriptions", "adh", "extra", "extras")
    val ignoredSubtitleTitles = listOf("signs", "songs", "lyrics", "forced", "sdh", "colored", "karaoke")
    val dialogueTitleKeywords = listOf(
      "subtitle", "dialogue", "dialog", "translation", "english", "main", "script", "full", "caption",
    )
  }
}
