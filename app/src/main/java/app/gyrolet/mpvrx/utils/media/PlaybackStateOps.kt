/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import app.gyrolet.mpvrx.database.MpvRxDatabase
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.ui.browser.videolist.videoPlaybackIdentifiers
import app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
import app.gyrolet.mpvrx.utils.logging.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject
import java.io.File

/**
 * Utility for managing playback state when files are renamed or deleted.
 */
object PlaybackStateOps {
  private const val TAG = "PlaybackStateOps"
  private val repository: PlaybackStateRepository by inject(PlaybackStateRepository::class.java)
  private val database: MpvRxDatabase by inject(MpvRxDatabase::class.java)

  suspend fun setWatched(
    video: Video,
    watched: Boolean,
  ) = setWatched(listOf(video), watched)

  suspend fun setWatched(
    videos: List<Video>,
    watched: Boolean,
  ) = withContext(Dispatchers.IO) {
    try {
      videos.filterNot { it.isAudio }.forEach { video ->
        currentCoroutineContext().ensureActive()
        persistWatched(video, watched)
        database.playlistDao().clearResumePositionForFile(video.path)
      }
    } finally {
      PlaybackStateEvents.notifyChanged(videos.singleOrNull()?.let { videoPlaybackIdentifiers(it).first() }.orEmpty())
    }
  }

  suspend fun areAllWatched(
    videos: List<Video>,
    threshold: Int,
  ): Boolean {
    val videoFiles = videos.filterNot { it.isAudio }
    val watched = watchedPaths(videoFiles, threshold)
    return videoFiles.isNotEmpty() && videoFiles.all { it.path in watched }
  }

  suspend fun watchedPaths(
    videos: List<Video>,
    threshold: Int,
  ): Set<String> =
    withContext(Dispatchers.IO) {
      val states = repository.getAllPlaybackStates().associateBy { it.mediaTitle }
      videos
        .filter { video ->
          if (video.isAudio) return@filter false
          val state = videoPlaybackIdentifiers(video).firstNotNullOfOrNull(states::get)
          val duration = video.duration / 1000.0
          if (app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY) {
            return@filter app.gyrolet.mpvrx.ui.browser.videolist
              .buildVideoWithPlaybackInfo(
                video,
                state,
                System.currentTimeMillis(),
                0,
                threshold,
              ).isWatched
          }
          state != null &&
            (
              state.hasBeenWatched ||
                (threshold > 0 && duration > 0 && (duration - state.timeRemaining) / duration >= threshold / 100.0)
            )
        }.mapTo(mutableSetOf()) { it.path }
    }

  suspend fun resetWatchHistory(
    videos: List<Video>,
    markAsNew: Boolean,
  ) = withContext(Dispatchers.IO) {
    try {
      videos.forEach { video ->
        currentCoroutineContext().ensureActive()
        persistWatched(video, watched = false, newLabelOverride = markAsNew)
        val identifier = videoPlaybackIdentifiers(video).first()
        videoPlaybackIdentifiers(video).filterNot { it == identifier }.forEach { repository.deleteByTitle(it) }
        RecentlyPlayedOps.removeVideoHistory(video)
        database.playlistDao().clearPlayHistoryForFile(video.path)
      }
    } finally {
      PlaybackStateEvents.notifyChanged("")
    }
  }

  suspend fun markLastPlayed(videos: List<Video>): Boolean =
    withContext(Dispatchers.IO) {
      if (!RecentlyPlayedOps.markLastPlayed(videos)) return@withContext false
      try {
        videos.forEach { video ->
          currentCoroutineContext().ensureActive()
          val existing = videoPlaybackIdentifiers(video).firstNotNullOfOrNull { repository.getVideoDataByTitle(it) }
          if (existing == null) {
            persistWatched(video, watched = false, newLabelOverride = false)
          } else {
            repository.upsert(existing.copy(newLabelOverride = false))
          }
          database.playlistDao().markFileLastPlayed(video.path, System.currentTimeMillis())
        }
      } finally {
        PlaybackStateEvents.notifyChanged("")
      }
      true
    }

  private suspend fun persistWatched(
    video: Video,
    watched: Boolean,
    newLabelOverride: Boolean? = null,
  ) {
    val identifier = videoPlaybackIdentifiers(video).first()
    val existing = videoPlaybackIdentifiers(video).firstNotNullOfOrNull { repository.getVideoDataByTitle(it) }
    val recordedDuration =
      if (app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY) {
        existing?.let { it.lastPosition.toLong() + it.timeRemaining.toLong() } ?: 0L
      } else {
        0L
      }
    val durationSeconds =
      ((video.duration / 1000L).takeIf { it > 0L } ?: recordedDuration)
        .coerceIn(0L, Int.MAX_VALUE.toLong())
        .toInt()
    val state =
      existing ?: PlaybackStateEntity(
        mediaTitle = identifier,
        lastPosition = 0,
        playbackSpeed = 1.0,
        sid = -1,
        secondarySid = -1,
        subDelay = 0,
        subSpeed = 1.0,
        aid = -1,
        audioDelay = 0,
      )
    repository.upsert(
      state.copy(
        mediaTitle = identifier,
        lastPosition = 0,
        timeRemaining = if (watched) 0 else durationSeconds,
        hasBeenWatched = watched,
        newLabelOverride = newLabelOverride,
      ),
    )
  }

  /** Provider-aware history migration for scoped rename/move operations. */
  suspend fun onScopedVideoRenamed(
    video: Video,
    newPath: String,
    newUri: android.net.Uri,
    newName: String,
  ) {
    val oldKeys = videoPlaybackIdentifiers(video)
    val destination = video.copy(path = newPath, uri = newUri, displayName = newName)
    val newKey = videoPlaybackIdentifiers(destination).first()
    val state = oldKeys.firstNotNullOfOrNull { repository.getVideoDataByTitle(it) }
    if (state != null) repository.upsert(state.copy(mediaTitle = newKey))
    oldKeys.filterNot { it == newKey }.forEach { repository.deleteByTitle(it) }
    setOf(video.path, video.uri.toString()).forEach { oldPath ->
      RecentlyPlayedOps.onVideoRenamed(oldPath, newPath, newName)
      database.playlistDao().updateFileLocation(oldPath, newPath, newName)
    }
    PlaybackStateEvents.notifyChanged("")
  }

  suspend fun onScopedVideoDeleted(video: Video) {
    videoPlaybackIdentifiers(video).forEach { repository.deleteByTitle(it) }
    RecentlyPlayedOps.removeVideoHistory(video)
    PlaybackStateEvents.notifyChanged("")
  }

  /**
   * Called when a video file is renamed
   * Updates the playback state entry to use the new filename
   *
   * @param oldPath The original file path
   * @param newPath The new file path after renaming
   */
  suspend fun onVideoRenamed(
    oldPath: String,
    newPath: String,
  ) {
    if (oldPath.isBlank() || newPath.isBlank()) return

    try {
      val oldFileName = File(oldPath).name
      val newFileName = File(newPath).name

      // Only update if the filename actually changed
      if (oldFileName != newFileName) {
        repository.updateMediaTitle(oldFileName, newFileName)
        Log.d(TAG, "✓ Updated playback state: $oldFileName -> $newFileName")
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to update playback state: ${e.message}")
    }
  }

  /**
   * Called when a video file is deleted
   * Removes its playback state entry
   *
   * @param filePath The path of the deleted file
   */
  suspend fun onVideoDeleted(filePath: String) {
    if (filePath.isBlank()) return

    try {
      val fileName = File(filePath).name
      repository.deleteByTitle(fileName)
      Log.d(TAG, "✓ Deleted playback state for: $fileName")
    } catch (e: Exception) {
      Log.w(TAG, "Failed to delete playback state: ${e.message}")
    }
  }
}
