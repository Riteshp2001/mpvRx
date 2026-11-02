/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import android.util.Log
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.preferences.MpvConfigControlledFeatures
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

/**
 * Simple utility for automatically loading subtitle files
 * Finds matching sidecars beside the video and up to two subdirectories below it.
 */
object SubtitleOps : KoinComponent {
  private const val TAG = "SubtitleOps"
  private val networkRepository: NetworkRepository by inject()
  private val directoryCache = SubtitleDirectoryCache()

  suspend fun autoloadSubtitles(
    videoFilePath: String,
    videoFileName: String,
    networkConnectionId: Long = -1L,
    expectedGeneration: Long? = null,
    selectFirst: Boolean = true,
  ) = withContext(Dispatchers.IO) {
    try {
      if (!isGenerationCurrent(expectedGeneration) ||
        MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.SUBTITLE_DISCOVERY)
      ) return@withContext

      // PlayerActivity is singleTask and its original launch Intent can outlive several queue
      // selections. For a network queue, PlaybackSession is the authoritative source for the item
      // that actually reached FILE_LOADED. Prefer that source so next/previous never reuses the
      // first WebDAV/SMB/FTP item's cached path while discovering external subtitles.
      val activeNetworkSource =
        if (networkConnectionId != -1L) {
          PlaybackSession.queue.value.currentItem?.networkSource
        } else {
          null
        }
      val effectiveVideoFilePath = activeNetworkSource?.relativePath ?: videoFilePath
      val effectiveNetworkConnectionId = activeNetworkSource?.connectionId ?: networkConnectionId
      if (activeNetworkSource != null &&
        (effectiveVideoFilePath != videoFilePath || effectiveNetworkConnectionId != networkConnectionId)
      ) {
        Log.d(TAG, "Using active queue network source for subtitle discovery: $effectiveVideoFilePath")
      }

      // Skip file descriptor URIs (these don't have a parent directory concept)
      if (effectiveVideoFilePath.startsWith("fd://")) return@withContext

      // For content:// URIs, we can't autoload (no access to parent directory)
      if (effectiveVideoFilePath.startsWith("content://")) return@withContext

      // Check if this is a network file with connection ID (SMB/FTP/WebDAV via proxy)
      if (effectiveNetworkConnectionId != -1L) {
        // For network files, scan the directory using network client
        autoloadNetworkFileSubtitles(
          effectiveVideoFilePath,
          videoFileName,
          effectiveNetworkConnectionId,
          expectedGeneration,
          selectFirst,
        )
        return@withContext
      }

      // Check if this is a network stream (http, https, ftp, ftps, smb, webdav, etc.)
      val isNetworkStream = effectiveVideoFilePath.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*"))

      if (isNetworkStream) {
        Log.d(TAG, "Skipping direct network subtitle autoload for: $effectiveVideoFilePath")
        return@withContext
      } else {
        // For local files, scan the directory
        autoloadLocalSubtitles(effectiveVideoFilePath, videoFileName, expectedGeneration, selectFirst)
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Log.e(TAG, "Error loading subtitles", e)
    }
  }

  /**
   * Autoload subtitles for network files (SMB/FTP/WebDAV)
   * Uses the same bounded discovery rules as local playback, loading matching files via proxy.
   */
  private suspend fun autoloadNetworkFileSubtitles(
    videoFilePath: String,
    videoFileName: String,
    networkConnectionId: Long,
    expectedGeneration: Long?,
    selectFirst: Boolean,
  ) {
    try {
      Log.d(TAG, "Autoloading subtitles for network file: $videoFilePath")

      // Get the network connection
      val connection = networkRepository.getConnectionById(networkConnectionId)
      if (connection == null) {
        Log.w(TAG, "Network connection not found: $networkConnectionId")
        return
      }

      // Get the directory path (parent of the video file)
      val normalizedVideoPath = NetworkPath.from(videoFilePath)
      val directoryPath = NetworkPath.from(normalizedVideoPath.segments.dropLast(1).joinToString("/")).value

      Log.d(TAG, "Scanning directory: $directoryPath")

      val subtitles =
        SubtitleDiscovery.find(
          directoryPath = directoryPath,
          videoFileName = normalizedVideoPath.segments.lastOrNull() ?: videoFileName,
          videoPath = normalizedVideoPath.value,
          isCurrent = { isGenerationCurrent(expectedGeneration) },
        ) { path, limit, shouldContinue ->
          val key = "network:${connection.id}:$path"
          directoryCache.get(key, null, limit)?.let { return@find it }
          currentCoroutineContext().ensureActive()
          if (!shouldContinue()) return@find SubtitleDiscovery.Listing(emptyList(), complete = false)
          val files = networkRepository.listFiles(connection, path).getOrElse { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Failed to list subtitle directory: ${error.message}")
            return@find SubtitleDiscovery.Listing(emptyList(), complete = false)
          }
          val parent = NetworkPath.from(path)
          val entries = files.take(limit).map { file ->
            currentCoroutineContext().ensureActive()
            // DLNA paths contain opaque IDs, not display names. Validate containment without
            // rebuilding the path from the name, which could point at a different remote file.
            val child = runCatching { NetworkPath.from(file.path) }.getOrNull()
              ?.takeIf { it.segments.dropLast(1) == parent.segments }
            SubtitleDiscovery.Entry(
              name = if (child != null) file.name else "",
              path = child?.value.orEmpty(),
              isDirectory = child != null && file.isDirectory,
              size = file.size,
            )
          }
          SubtitleDiscovery.Listing(entries, complete = files.size <= limit)
            .also { directoryCache.put(key, null, it) }
        }

      if (subtitles.isEmpty()) {
        Log.d(TAG, "No matching subtitle files found for: $videoFileName")
        return
      }

      Log.d(TAG, "Found ${subtitles.size} subtitle file(s)")

      // Load subtitles via proxy
      // Dispatch JNI calls to the Main thread to prevent concurrent JNI usage/crashes.
      withContext(Dispatchers.Main) {
        subtitles.forEachIndexed { index, subtitle ->
          var registeredProxyUrl: String? = null
          try {
            currentCoroutineContext().ensureActive()
            if (!isGenerationCurrent(expectedGeneration)) return@forEachIndexed
            // Extract just the filename without path for display
            // Handle both forward slashes and backslashes
            val displayName =
              subtitle.name
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .takeIf { it.isNotBlank() } ?: subtitle.name

            Log.d(
              TAG,
              "Processing subtitle - name: '${subtitle.name}', displayName: '$displayName', path: '${subtitle.path}'",
            )

            val proxyUrl =
              PlaybackSession.registerAuxiliaryNetworkStream(
                connectionId = connection.id,
                filePath = subtitle.path,
                fileSize = subtitle.size,
                mimeType = "text/plain",
                expectedGeneration = expectedGeneration,
              ) ?: return@forEachIndexed
            registeredProxyUrl = proxyUrl

            // Keep tracks available while respecting the user's current subtitle-off state.
            val flag = if (index == 0 && selectFirst) "select" else "auto"
            val added =
              expectedGeneration?.let { generation ->
                PlaybackSession.commandForGeneration(generation, "sub-add", proxyUrl, flag, displayName)
              } ?: run {
                PlaybackSession.command("sub-add", proxyUrl, flag, displayName)
                true
              }
            if (!added) {
              PlaybackSession.unregisterAuxiliaryNetworkStream(proxyUrl)
              registeredProxyUrl = null
              return@forEachIndexed
            }
            Log.d(TAG, "Loaded network subtitle: '$displayName' via proxy (flag=$flag)")
            registeredProxyUrl = null
          } catch (cancellation: CancellationException) {
            registeredProxyUrl?.let(PlaybackSession::unregisterAuxiliaryNetworkStream)
            throw cancellation
          } catch (e: Exception) {
            registeredProxyUrl?.let(PlaybackSession::unregisterAuxiliaryNetworkStream)
            Log.e(TAG, "Failed to load subtitle ${subtitle.name}: ${e.message}", e)
          }
        }
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Log.e(TAG, "Error autoloading network subtitles", e)
    }
  }

  private suspend fun autoloadLocalSubtitles(
    videoFilePath: String,
    videoFileName: String,
    expectedGeneration: Long?,
    selectFirst: Boolean,
  ) {
    val videoFile = File(videoFilePath)
    val videoDirectory = videoFile.canonicalFile.parentFile ?: return

    val subtitles =
      SubtitleDiscovery.find(
        directoryPath = videoDirectory.path,
        videoFileName = videoFile.name.ifBlank { videoFileName },
        videoPath = videoFile.canonicalPath,
        isCurrent = { isGenerationCurrent(expectedGeneration) },
      ) { path, limit, shouldContinue ->
        val stamp = File(path).lastModified()
        directoryCache.get(path, stamp, limit)?.let { return@find it }
        val entries = mutableListOf<SubtitleDiscovery.Entry>()
        var complete = true
        try {
          // DirectoryStream avoids allocating an unbounded File[] for large download folders.
          Files.newDirectoryStream(File(path).toPath()).use { children ->
            for (child in children) {
              currentCoroutineContext().ensureActive()
              if (entries.size >= limit || !shouldContinue()) {
                complete = false
                break
              }
              val attributes =
                runCatching { Files.readAttributes(child, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }.getOrNull()
              // Count every directory entry against the budget, but never follow symlinks or
              // open special files. An empty name makes these entries ineligible for discovery.
              val name =
                if (attributes?.isRegularFile == true || attributes?.isDirectory == true) {
                  child.fileName.toString()
                } else {
                  ""
                }
              entries.add(SubtitleDiscovery.Entry(
                name = name,
                path = child.toString(),
                isDirectory = attributes?.isDirectory == true,
              ))
            }
          }
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (error: Exception) {
          complete = false
          Log.d(TAG, "Cannot list subtitle directory: $path", error)
        }
        SubtitleDiscovery.Listing(entries, complete).also { directoryCache.put(path, stamp, it) }
      }

    if (subtitles.isNotEmpty()) {
      withContext(Dispatchers.Main) {
        if (!isGenerationCurrent(expectedGeneration)) return@withContext
        val loadedPaths = (0 until (PlaybackSession.getPropertyInt("track-list/count") ?: 0).coerceAtMost(512))
          .mapNotNullTo(hashSetOf()) { PlaybackSession.getPropertyString("track-list/$it/external-filename") }
        subtitles.forEachIndexed { index, subtitle ->
          currentCoroutineContext().ensureActive()
          if (!isGenerationCurrent(expectedGeneration)) return@forEachIndexed
          if (subtitle.path in loadedPaths) return@forEachIndexed
          // MPV command format: sub-add <url> [<flags> [<title>]]
          // Keep tracks available while respecting the user's current subtitle-off state.
          val flag = if (index == 0 && selectFirst) "select" else "auto"
          val added =
            expectedGeneration?.let { generation ->
              PlaybackSession.commandForGeneration(
                generation,
                "sub-add",
                subtitle.path,
                flag,
                subtitle.name,
              )
            } ?: run {
              PlaybackSession.command("sub-add", subtitle.path, flag, subtitle.name)
              true
            }
          if (!added) return@forEachIndexed
          Log.d(TAG, "Loaded local subtitle: ${subtitle.name} (flag=$flag)")
        }
      }
    }
  }

  private fun isGenerationCurrent(expectedGeneration: Long?): Boolean =
    expectedGeneration == null || PlaybackSession.isCurrentGeneration(expectedGeneration)
}
