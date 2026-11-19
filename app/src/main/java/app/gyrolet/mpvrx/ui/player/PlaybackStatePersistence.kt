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

internal const val PLAYBACK_STATE_MILLISECONDS_TO_SECONDS = 1000
internal const val DEFAULT_PLAYBACK_STATE_SPEED = 1.0
internal const val DEFAULT_PLAYBACK_STATE_SUB_SPEED = 1.0

internal data class PlaybackStateSnapshot(
  val mediaIdentifier: String,
  val mediaTitle: String,
  val currentPosition: Int,
  val duration: Int,
  val playbackSpeed: Double,
  val videoZoom: Float,
  val sid: Int,
  val secondarySid: Int,
  val subDelayMs: Int,
  val subSpeed: Double,
  val aid: Int,
  val audioDelayMs: Int,
  val externalSubtitles: String,
  // Non-null only when the user picked the audio track by hand during this session and the
  // track current at capture time is still that pick (0 = audio off by hand). Null means
  // "no manual decision in this snapshot": buildEntity then keeps the stored manual record
  // untouched, so an automatic track change can never overwrite or erase it.
  val sessionManualAudioId: Int? = null,
  // Separator-joined ("|") external audio URIs known to the capturing side. Blank keeps
  // the stored list, mirroring how the service treats externalSubtitles.
  val externalAudio: String = "",
  val isPositionRestorePending: Boolean = false,
  /** A live stream's position is a moment in a broadcast that will be gone on the next visit. */
  val isLive: Boolean = false,
)

internal object PlaybackStatePersistence {
  fun buildEntity(
    oldState: PlaybackStateEntity?,
    snapshot: PlaybackStateSnapshot,
    savePositionOnQuit: Boolean,
    watchedThreshold: Int,
  ): PlaybackStateEntity {
    val lastPosition =
      if (snapshot.isLive) {
        0
      } else {
        calculateSavePosition(
          oldState = oldState,
          currentPosition = snapshot.currentPosition,
          duration = snapshot.duration,
          savePositionOnQuit = savePositionOnQuit,
          isPositionRestorePending = snapshot.isPositionRestorePending,
        )
      }
    val duration = snapshot.duration
    val timeRemaining = if (duration > lastPosition) duration - lastPosition else 0

    // A manual record is written only from an explicit in-session pick. Without one, the
    // stored record is carried over as-is: this save's aid is an automatic result and must
    // neither replace the user's pick nor clear it.
    val hasManualAudioSelection =
      snapshot.sessionManualAudioId != null || oldState?.hasManualAudioSelection == true
    val manualAudioId =
      if (hasManualAudioSelection) {
        snapshot.sessionManualAudioId ?: oldState?.manualAudioId ?: -1
      } else {
        -1
      }

    return PlaybackStateEntity(
      mediaTitle = snapshot.mediaIdentifier,
      lastPosition = lastPosition,
      playbackSpeed = snapshot.playbackSpeed,
      videoZoom = snapshot.videoZoom,
      sid = snapshot.sid,
      secondarySid = snapshot.secondarySid,
      subDelay = snapshot.subDelayMs,
      subSpeed = snapshot.subSpeed,
      aid = snapshot.aid,
      audioDelay = snapshot.audioDelayMs,
      timeRemaining = timeRemaining,
      externalSubtitles = snapshot.externalSubtitles,
      hasManualAudioSelection = hasManualAudioSelection,
      manualAudioId = manualAudioId,
      externalAudio = snapshot.externalAudio.ifBlank { oldState?.externalAudio.orEmpty() },
      hasBeenWatched =
        isWatched(
          oldState = oldState,
          currentPosition = snapshot.currentPosition,
          lastPosition = lastPosition,
          duration = duration,
          watchedThreshold = watchedThreshold,
        ),
    )
  }

  fun calculateSavePosition(
    oldState: PlaybackStateEntity?,
    currentPosition: Int,
    duration: Int,
    savePositionOnQuit: Boolean,
    isPositionRestorePending: Boolean = false,
  ): Int {
    if (!savePositionOnQuit) {
      return oldState?.lastPosition ?: 0
    }
    if (isPositionRestorePending) {
      return oldState?.lastPosition ?: currentPosition.coerceAtLeast(0)
    }

    if (duration <= 0) return currentPosition.takeIf { it > 0 } ?: oldState?.lastPosition ?: 0

    return if (currentPosition < duration - 1) currentPosition.coerceAtLeast(0) else 0
  }

  private fun isWatched(
    oldState: PlaybackStateEntity?,
    currentPosition: Int,
    lastPosition: Int,
    duration: Int,
    watchedThreshold: Int,
  ): Boolean {
    // Watched threshold 0 means "Infinitely": the video is never auto-marked as
    // watched based on progress (only an explicit flag keeps it marked).
    if (watchedThreshold == 0) return oldState?.hasBeenWatched == true

    val durationSeconds = duration.toFloat()
    if (durationSeconds <= 0f) return oldState?.hasBeenWatched == true

    val isFinished = currentPosition >= durationSeconds - 1
    val currentProgress = currentPosition.toFloat() / durationSeconds
    val savedProgress = lastPosition.toFloat() / durationSeconds
    val threshold = watchedThreshold / 100f

    return currentProgress >= threshold ||
      isFinished ||
      savedProgress >= threshold ||
      (oldState?.hasBeenWatched == true)
  }
}
