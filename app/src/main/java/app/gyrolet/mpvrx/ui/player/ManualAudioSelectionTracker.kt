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
 * A mark also records which load the pick was made in (the PlaybackSession generation at
 * pick time). What carries a pick across loads is the persisted record, applied by the
 * restore; the in-process mark only closes the "already decided" gate for the load the
 * pick was made in — a pick made while that load's restore and subtitle autoload were
 * still running, which the automatic correction must not override. A pick from an
 * earlier load in the same process must not close the gate: when this load's restore
 * did not apply the saved record, the automatic rules decide this open.
 *
 * A yt-dlp pick is the one case where the picked id cannot be marked directly: picking an
 * audio track reloads the item with a new format selector, and the reload renumbers the
 * tracks. Those picks are recorded as reload-pending instead; when the reloaded file has
 * loaded, the restore path resolves the pending mark to whichever track the reload
 * selected, which is the track the user's selector asked for.
 */
internal object ManualAudioSelectionTracker {
  private data class Mark(
    val trackId: Int,
    val generation: Long,
  )

  private val marks = mutableMapOf<String, Mark>()
  private val reloadPending = mutableSetOf<String>()

  @Synchronized
  fun mark(
    identifier: String,
    trackId: Int,
  ) {
    if (identifier.isBlank()) return
    marks[identifier] = Mark(trackId, PlaybackSession.state.value.generation)
    reloadPending.remove(identifier)
  }

  @Synchronized
  fun markedTrackId(identifier: String): Int? = marks[identifier]?.trackId

  /**
   * True only when a pick for [identifier] was made during load [generation] itself.
   * Marks from earlier loads in this process do not count (see the class doc).
   */
  @Synchronized
  fun hasMarkForGeneration(
    identifier: String,
    generation: Long,
  ): Boolean = marks[identifier]?.generation == generation

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
  fun resolveReloadPending(
    identifier: String,
    currentTrackId: Int,
  ): Boolean {
    if (!reloadPending.remove(identifier)) return false
    marks[identifier] = Mark(currentTrackId, PlaybackSession.state.value.generation)
    return true
  }
}
