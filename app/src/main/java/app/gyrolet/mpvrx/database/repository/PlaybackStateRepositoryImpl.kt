/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.repository

import app.gyrolet.mpvrx.database.MpvRxDatabase
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class PlaybackStateRepositoryImpl(
  private val database: MpvRxDatabase,
) : PlaybackStateRepository {
  // Library screens can ask for the complete playback-state set several times while navigating or
  // refreshing. Keep one process-local snapshot after the first unavoidable Room load and maintain
  // it incrementally on writes instead of allocating/querying the full table on every request.
  private val stateCache = ConcurrentHashMap<String, PlaybackStateEntity>()
  private val allStatesLoaded = AtomicBoolean(false)
  private val fullLoadMutex = Mutex()

  override suspend fun upsert(playbackState: PlaybackStateEntity) {
    database.videoDataDao().upsert(playbackState)
    stateCache[playbackState.mediaTitle] = playbackState
  }

  override suspend fun getVideoDataByTitle(mediaTitle: String): PlaybackStateEntity? {
    stateCache[mediaTitle]?.let { return it }
    return database.videoDataDao().getVideoDataByTitle(mediaTitle)?.also { state ->
      stateCache[state.mediaTitle] = state
    }
  }

  override suspend fun getAllPlaybackStates(): List<PlaybackStateEntity> {
    if (allStatesLoaded.get()) return stateCache.values.toList()

    return fullLoadMutex.withLock {
      if (!allStatesLoaded.get()) {
        val loaded = database.videoDataDao().getAllPlaybackStates()
        stateCache.clear()
        loaded.forEach { state -> stateCache[state.mediaTitle] = state }
        allStatesLoaded.set(true)
      }
      stateCache.values.toList()
    }
  }

  override suspend fun clearAllPlaybackStates() {
    database.videoDataDao().clearAllPlaybackStates()
    stateCache.clear()
    allStatesLoaded.set(true)
  }

  override suspend fun deleteByTitle(mediaTitle: String) {
    database.videoDataDao().deleteByTitle(mediaTitle)
    stateCache.remove(mediaTitle)
  }

  override suspend fun updateMediaTitle(
    oldTitle: String,
    newTitle: String,
  ) {
    database.videoDataDao().updateMediaTitle(oldTitle, newTitle)
    stateCache.remove(oldTitle)?.let { state ->
      stateCache[newTitle] = state.copy(mediaTitle = newTitle)
    }
  }
}
