/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

/**
 * In-process record of audio selections the user made by hand, keyed by media identifier
 * (the PlaybackItem stable id that PlaybackStateEntity rows are also keyed by).
 *
 * A mark binds the exact mpv track id the user picked; 0 means the user turned audio off.
 * Only the explicit pick action in PlayerViewModel creates a mark. Automatic changes —
 * mpv's preferred-language choice, TrackSelector's correction, the audio track paired with
 * a video-quality change, a format reload — never create one, so a later state save can
 * tell "the user chose this" apart from "this happened to be playing". The save path only
 * persists a mark when the track that is current at save time is still the marked one.
 *
 * A yt-dlp pick is the one case where the picked id cannot be marked directly: picking an
 * audio track reloads the item with a new format selector, and the reload renumbers the
 * tracks. Those picks are recorded as reload-pending instead; when the reloaded file has
 * loaded, the restore path resolves the pending mark to whichever track the reload
 * selected, which is the track the user's selector asked for.
 */
internal object ManualAudioSelectionTracker {
  private val marks = mutableMapOf<String, Int>()
  private val reloadPending = mutableSetOf<String>()

  @Synchronized
  fun mark(identifier: String, trackId: Int) {
    if (identifier.isBlank()) return
    marks[identifier] = trackId
    reloadPending.remove(identifier)
  }

  @Synchronized
  fun markedTrackId(identifier: String): Int? = marks[identifier]

  @Synchronized
  fun hasMark(identifier: String): Boolean = marks.containsKey(identifier)

  @Synchronized
  fun markReloadPending(identifier: String) {
    if (identifier.isBlank()) return
    reloadPending.add(identifier)
  }

  @Synchronized
  fun hasReloadPending(identifier: String): Boolean = identifier in reloadPending

  /**
   * Resolves a reload-pending pick to [currentTrackId] (0 = audio off, matching
   * [getTrackSelectionId]). Returns true when a pending pick existed and was resolved.
   */
  @Synchronized
  fun resolveReloadPending(identifier: String, currentTrackId: Int): Boolean {
    if (!reloadPending.remove(identifier)) return false
    marks[identifier] = currentTrackId
    return true
  }
}
