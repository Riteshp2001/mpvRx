/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.history

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.gyrolet.mpvrx.database.entities.RecentlyPlayedEntity
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.recentlyplayed.repository.RecentlyPlayedRepository
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.utils.media.ScopedHiddenFolderScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject
import java.io.File

object RecentlyPlayedOps {
  private val repository: RecentlyPlayedRepository by inject(RecentlyPlayedRepository::class.java)
  private val preferences: AdvancedPreferences by inject(AdvancedPreferences::class.java)
  private val context: Context by inject(Context::class.java)

  private val MEDIA_STORE_PREFIX = "content://${MediaStore.AUTHORITY}/"

  private class MediaStoreRow(val exists: Boolean, val path: String?)

  suspend fun addRecentlyPlayed(
    filePath: String,
    fileName: String,
    videoTitle: String? = null,
    duration: Long = 0,
    fileSize: Long = 0,
    width: Int = 0,
    height: Int = 0,
    launchSource: String? = null,
    playlistId: Int? = null,
    artworkUrl: String? = null,
  ) {
    // Check if recently played feature is enabled
    if (!preferences.enableRecentlyPlayed.get()) return

    val uri = Uri.parse(filePath)

    if (uri.scheme in listOf("smb", "ftp", "ftps", "webdav", "webdavs")) return
    if (uri.host?.lowercase() in listOf("127.0.0.1", "localhost", "0.0.0.0")) return

    repository.addRecentlyPlayed(
      filePath,
      fileName,
      videoTitle,
      duration,
      fileSize,
      width,
      height,
      launchSource,
      playlistId,
      artworkUrl,
    )
  }

  suspend fun clearAll() {
    repository.clearAll()
  }

  suspend fun updateVideoTitle(
    filePath: String,
    videoTitle: String,
  ) {
    repository.updateVideoTitle(filePath, videoTitle)
  }

  suspend fun updateVideoMetadata(
    filePath: String,
    videoTitle: String?,
    duration: Long,
    fileSize: Long,
    width: Int,
    height: Int,
  ) {
    repository.updateVideoMetadata(filePath, videoTitle, duration, fileSize, width, height)
  }

  suspend fun getLastPlayed(): String? = getLastPlayedEntity()?.filePath

  suspend fun getLastPlayedEntity(): app.gyrolet.mpvrx.database.entities.RecentlyPlayedEntity? {
    return withContext(Dispatchers.IO) {
      val recent = kotlin.runCatching { repository.getRecentlyPlayed(limit = 50) }.getOrDefault(emptyList())
      for (entity in recent) {
        val path = entity.filePath
        val mediaStoreRow = mediaStoreRow(path)
        if (mediaStoreRow != null) {
          // Kept when missing: partial media access also hides videos that still exist.
          if (mediaStoreRow.exists) return@withContext entity
          continue
        }
        if (isNonFileUri(path)) {
          return@withContext entity
        }
        if (fileExists(path)) {
          return@withContext entity
        } else {
          kotlin.runCatching { repository.deleteByFilePath(path) }
        }
      }
      null
    }
  }

  suspend fun getRecentlyPlayed(limit: Int = 50): List<RecentlyPlayedEntity> = repository.getRecentlyPlayed(limit)

  /**
   * The most recently played of [videos]. History is walked back, so when the last played video
   * was deleted or is not among [videos], the one played before it is chosen.
   */
  suspend fun lastPlayedOf(
    videos: List<Video>,
    limit: Int = 100,
  ): Video? =
    withContext(Dispatchers.IO) {
      if (videos.isEmpty()) return@withContext null
      lastPlayedVideo(repository.getRecentlyPlayed(limit), videos)
    }

  /**
   * Matches history against the current listing using both URI and local-file identities.
   * SAF can return the same document under different tree grants, so raw URI equality alone is
   * not sufficient for Tree, Library, and hidden-folder listings.
   */
  fun lastPlayedVideo(
    history: List<RecentlyPlayedEntity>,
    videos: List<Video>,
  ): Video? {
    if (history.isEmpty() || videos.isEmpty()) return null

    val videosByKey = HashMap<String, Video>(videos.size * 3)
    videos.forEach { video ->
      (mediaIdentityKeys(video.path) + mediaIdentityKeys(video.uri.toString())).forEach { key ->
        videosByKey.putIfAbsent(key, video)
      }
    }
    return history.firstNotNullOfOrNull { entity ->
      historyIdentityKeys(entity.filePath).firstNotNullOfOrNull(videosByKey::get)
    }
  }

  fun matchesVideoPath(
    historyPath: String?,
    video: Video,
  ): Boolean {
    if (historyPath.isNullOrBlank()) return false
    val videoKeys = mediaIdentityKeys(video.path) + mediaIdentityKeys(video.uri.toString())
    return mediaIdentityKeys(historyPath).any(videoKeys::contains)
  }

  private fun historyIdentityKeys(path: String): Set<String> =
    buildSet {
      addAll(mediaIdentityKeys(path))
      localPathForKey(path).takeIf { it != path }?.let { addAll(mediaIdentityKeys(it)) }
    }

  private fun mediaIdentityKeys(path: String): Set<String> =
    buildSet {
      if (path.isBlank()) return@buildSet
      add(path)
      val uri = Uri.parse(path)
      when (uri.scheme?.lowercase()) {
        "file" -> uri.path?.let(::add)
        "content" -> {
          add(ScopedHiddenFolderScanner.documentKey(uri))
          ScopedHiddenFolderScanner.localPathOf(uri)?.let(::add)
        }
      }
    }

  /**
   * The Play Store build records MediaStore videos by their content:// URI, while the browsers
   * identify videos by path. Returns the path behind such a key, otherwise the key itself.
   * Queries MediaStore, so call it off the main thread.
   */
  fun localPathForKey(key: String): String = mediaStoreRow(key)?.path ?: key

  suspend fun getRecentlyPlayedCount(): Int = repository.getRecentlyPlayedCount()

  @OptIn(ExperimentalCoroutinesApi::class)
  fun observeLastPlayedPath(): Flow<String?> =
    repository
      .observeLastPlayedForHighlight()
      .mapLatest { entity ->
        val path = entity?.filePath
        val mediaStoreRow = path?.let { mediaStoreRow(it) }
        if (path.isNullOrEmpty()) {
          null
        } else if (mediaStoreRow != null) {
          if (mediaStoreRow.exists) mediaStoreRow.path ?: path else null
        } else if (fileExists(path)) {
          path
        } else {
          null
        }
      }.distinctUntilChanged()
      .flowOn(Dispatchers.IO)

  suspend fun removeVideoHistory(video: Video) = withContext(Dispatchers.IO) {
    setOf(video.path, video.uri.toString(), Uri.fromFile(File(video.path)).toString()).forEach { path ->
      repository.deleteByFilePath(path)
    }
  }

  suspend fun markLastPlayed(videos: List<Video>): Boolean =
    withContext(Dispatchers.IO) {
      if (!preferences.enableRecentlyPlayed.get() || videos.isEmpty()) return@withContext false
      videos.asReversed().forEach { video ->
        addRecentlyPlayed(
          filePath = video.path,
          fileName = video.displayName,
          videoTitle = video.title,
          duration = video.duration,
          fileSize = video.size,
          width = video.width,
          height = video.height,
          launchSource = "normal",
        )
        repository.markLastPlayed(video.path, System.currentTimeMillis())
      }
      true
    }

  suspend fun onVideoDeleted(filePath: String) {
    if (filePath.isBlank()) return
    withContext(Dispatchers.IO) {
      kotlin.runCatching { repository.deleteByFilePath(filePath) }
    }
  }

  suspend fun onVideoRenamed(
    oldPath: String,
    newPath: String,
    displayName: String? = null,
  ) {
    if (oldPath.isBlank() || newPath.isBlank()) return

    val newFileName = displayName ?: java.io.File(newPath).name
    kotlin
      .runCatching {
        repository.updateFilePath(oldPath, newPath, newFileName)
        android.util.Log.d("RecentlyPlayedOps", "Updated history: $oldPath -> $newPath")
      }.onFailure { e ->
        android.util.Log.w("RecentlyPlayedOps", "Failed to update history path: ${e.message}")
      }
  }

  /** Looks up a MediaStore history key. Null when [key] is not one or the lookup fails. */
  private fun mediaStoreRow(key: String): MediaStoreRow? {
    if (!key.startsWith(MEDIA_STORE_PREFIX)) return null
    return kotlin
      .runCatching {
        context.contentResolver
          .query(Uri.parse(key), arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
          ?.use { cursor ->
            if (cursor.moveToFirst()) {
              MediaStoreRow(exists = true, path = cursor.getString(0)?.takeIf { it.startsWith("/") })
            } else {
              MediaStoreRow(exists = false, path = null)
            }
          }
      }.getOrNull()
  }

  @SuppressLint("UseKtx")
  private fun fileExists(path: String): Boolean =
    kotlin
      .runCatching {
        // For file paths, don't use Uri.parse as it treats # as fragment separator
        // Instead, check if it looks like a file path directly
        if (path.startsWith("/") || path.startsWith("file://")) {
          // It's a local file path - use it directly
          val filePath =
            if (path.startsWith("file://")) {
              path.removePrefix("file://")
            } else {
              path
            }
          java.io.File(filePath).exists()
        } else {
          // It's likely a network URI - parse it normally
          val uri = Uri.parse(path)
          val scheme = uri.scheme
          if (scheme == null || scheme.equals("file", ignoreCase = true)) {
            java.io.File(path).exists()
          } else {
            true
          }
        }
      }.getOrDefault(false)

  @SuppressLint("UseKtx")
  private fun isNonFileUri(path: String): Boolean =
    kotlin
      .runCatching {
        // For file paths starting with / or file://, don't parse as URI
        if (path.startsWith("/") || path.startsWith("file://")) {
          false
        } else {
          // For other paths, check if they have a non-file scheme
          val scheme = Uri.parse(path).scheme
          scheme != null && !scheme.equals("file", ignoreCase = true)
        }
      }.getOrDefault(false)
}
