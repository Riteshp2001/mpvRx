/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.jellyfin

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import app.gyrolet.mpvrx.database.dao.DownloadItemDao
import app.gyrolet.mpvrx.database.entities.DownloadItemEntity
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.domain.download.AppDownloadStatus
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.repository.JellyfinRepository
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity
import app.gyrolet.mpvrx.utils.logging.Log
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Syncs local playback progress and watched status of downloaded Jellyfin movies/episodes back to
 * their originating Jellyfin server whenever playback state is saved or connectivity is restored.
 */
class JellyfinOfflineSyncManager(
  private val context: Context,
  private val downloadItemDao: DownloadItemDao,
  private val playbackStateRepository: PlaybackStateRepository,
  private val jellyfinRepository: JellyfinRepository,
) {
  companion object {
    private const val TAG = "JellyfinOfflineSync"
    private const val LEDGER_PREFS = "jellyfin_offline_sync_ledger"
    private const val TICKS_PER_SECOND = 10_000_000L
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val syncMutex = Mutex()
  private val started = AtomicBoolean(false)
  private val ledger by lazy {
    context.getSharedPreferences(LEDGER_PREFS, Context.MODE_PRIVATE)
  }

  private val networkCallback =
    object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        scope.launch { syncPending() }
      }

      override fun onCapabilitiesChanged(
        network: Network,
        networkCapabilities: NetworkCapabilities,
      ) {
        if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
          scope.launch { syncPending() }
        }
      }
    }

  fun ensureStarted() {
    if (!started.compareAndSet(false, true)) return

    runCatching {
      val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      val request =
        NetworkRequest
          .Builder()
          .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
          .build()
      cm?.registerNetworkCallback(request, networkCallback)
    }.onFailure { error ->
      Log.w(TAG, "Unable to register network callback for offline Jellyfin sync", error)
    }

    scope.launch {
      syncPending()
      PlaybackStateEvents.changes.collectLatest { changedIdentifier ->
        if (changedIdentifier.isBlank() || isDownloadedJellyfinIdentifier(changedIdentifier)) {
          syncPending()
        }
      }
    }
  }

  suspend fun isDownloadedJellyfinIdentifier(mediaIdentifier: String): Boolean =
    withContext(Dispatchers.IO) {
      if (mediaIdentifier.isBlank()) return@withContext false
      completedJellyfinDownloads().any { download ->
        mediaIdentifier in candidateIdentifiers(download)
      }
    }

  /**
   * Pushes any unsynced watched flags and resume positions for downloaded Jellyfin media to their
   * servers. Returns true when at least one item changed on a server.
   */
  suspend fun syncPending(): Boolean =
    withContext(Dispatchers.IO) {
      syncMutex.withLock {
        val downloads = completedJellyfinDownloads()
        if (downloads.isEmpty()) return@withLock false

        var anySynced = false
        val serverCache = HashMap<Long, JellyfinServer?>()

        for (download in downloads) {
          val itemId = download.jellyfinItemId?.takeIf { it.isNotBlank() } ?: continue
          val serverId = download.jellyfinServerId?.toLongOrNull() ?: continue
          val state = resolveLocalPlaybackState(download) ?: continue

          val watchedKey = "watched:$serverId:$itemId"
          val posKey = "pos:$serverId:$itemId"
          val prevWatched = ledger.getBoolean(watchedKey, false)
          val prevPosSec = ledger.getInt(posKey, 0)

          val needsWatchedSync = state.hasBeenWatched && !prevWatched
          val needsPosSync = !state.hasBeenWatched && state.lastPosition > 0 && state.lastPosition != prevPosSec
          if (!needsWatchedSync && !needsPosSync) continue

          val server =
            serverCache.getOrPut(serverId) {
              runCatching { jellyfinRepository.getServerById(serverId) }.getOrNull()
            } ?: continue

          try {
            if (needsWatchedSync) {
              val result = jellyfinRepository.markPlayedById(server, itemId)
              if (result.isSuccess) {
                ledger
                  .edit()
                  .putBoolean(watchedKey, true)
                  .putInt(posKey, 0)
                  .apply()
                anySynced = true
                Log.d(TAG, "Synced offline watched status for Jellyfin item $itemId")
              }
            } else if (needsPosSync) {
              val positionTicks = state.lastPosition.toLong() * TICKS_PER_SECOND
              val result = jellyfinRepository.syncPlaybackPosition(server, itemId, positionTicks)
              if (result.isSuccess) {
                ledger
                  .edit()
                  .putInt(posKey, state.lastPosition)
                  .apply()
                anySynced = true
                Log.d(TAG, "Synced offline playback position (${state.lastPosition}s) for Jellyfin item $itemId")
              }
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            Log.w(TAG, "Deferred offline Jellyfin sync for $itemId: ${error.message}")
          }
        }

        anySynced
      }
    }

  /**
   * Overlays local playback progress and watched flags from downloaded copies onto [items] so the
   * Jellyfin UI reflects offline progress immediately, even before the server is reachable.
   */
  suspend fun applyLocalStateOverlays(
    serverId: Long,
    items: List<JellyfinItem>,
  ): List<JellyfinItem> =
    withContext(Dispatchers.IO) {
      if (items.isEmpty()) return@withContext items
      val serverKey = serverId.toString()
      val downloadsByItemId =
        completedJellyfinDownloads()
          .filter { it.jellyfinServerId == serverKey && !it.jellyfinItemId.isNullOrBlank() }
          .associateBy { it.jellyfinItemId!! }
      if (downloadsByItemId.isEmpty()) return@withContext items

      items.map { item ->
        val download = downloadsByItemId[item.id] ?: return@map item
        val localState = resolveLocalPlaybackState(download) ?: return@map item
        val watchedKey = "watched:$serverId:${item.id}"
        val posKey = "pos:$serverId:${item.id}"
        val unsyncedWatched = localState.hasBeenWatched && !ledger.getBoolean(watchedKey, false)
        val unsyncedPos =
          !localState.hasBeenWatched &&
            localState.lastPosition > 0 &&
            localState.lastPosition != ledger.getInt(posKey, 0)
        when {
          unsyncedWatched && !item.isPlayed ->
            item.copy(isPlayed = true, playbackPositionTicks = 0L)
          unsyncedPos ->
            item.copy(playbackPositionTicks = localState.lastPosition.toLong() * TICKS_PER_SECOND)
          else -> item
        }
      }
    }

  private suspend fun completedJellyfinDownloads(): List<DownloadItemEntity> =
    runCatching {
      downloadItemDao.getAll().filter { entity ->
        !entity.jellyfinItemId.isNullOrBlank() &&
          !entity.jellyfinServerId.isNullOrBlank() &&
          entity.status == AppDownloadStatus.SUCCESS.name &&
          File(entity.dirPath, entity.fileName).isFile
      }
    }.getOrDefault(emptyList())

  private suspend fun resolveLocalPlaybackState(download: DownloadItemEntity): PlaybackStateEntity? {
    for (identifier in candidateIdentifiers(download)) {
      val state = runCatching { playbackStateRepository.getVideoDataByTitle(identifier) }.getOrNull()
      if (state != null) return state
    }
    return null
  }

  private fun candidateIdentifiers(download: DownloadItemEntity): Set<String> {
    val file = File(download.dirPath, download.fileName)
    val absPath = file.absolutePath
    val canonicalPath = runCatching { file.canonicalPath }.getOrDefault(absPath)
    return linkedSetOf(
      PlaybackIdentity.forLocalPath(absPath),
      PlaybackIdentity.forLocalPath(canonicalPath),
      PlaybackIdentity.forUri(absPath),
      PlaybackIdentity.forUri(Uri.fromFile(file).toString()),
      PlaybackIdentity.forUri("file://$absPath"),
    )
  }
}
