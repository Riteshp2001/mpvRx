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
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.Locale

/**
 * Simple utility for automatically loading subtitle files
 * Finds subtitles in the same directory that match the video filename
 */
object SubtitleOps : KoinComponent {
  private const val TAG = "SubtitleOps"
  private val networkRepository: NetworkRepository by inject()

  suspend fun autoloadSubtitles(
    videoFilePath: String,
    videoFileName: String,
    networkConnectionId: Long = -1L,
    expectedGeneration: Long? = null,
    selectFirst: Boolean = true,
  ) = withContext(Dispatchers.IO) {
    try {
      if (!isGenerationCurrent(expectedGeneration)) return@withContext

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

      if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.SUBTITLE_DISCOVERY)) return@withContext

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
   * Lists files in the same directory and loads matching subtitle files via proxy
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

      // Get base name without extension
      val baseName =
        normalizedVideoPath.segments.lastOrNull()?.substringBeforeLast('.')
          ?: videoFileName.substringBeforeLast('.')

      // List files in the directory
      val filesResult = networkRepository.listFiles(connection, directoryPath)
      if (filesResult.isFailure) {
        Log.w(TAG, "Failed to list network directory: ${filesResult.exceptionOrNull()?.message}")
        return
      }

      val files = filesResult.getOrNull() ?: emptyList()
      if (!isGenerationCurrent(expectedGeneration)) return

      // Filter for subtitle files that match the video base name
      val subtitles =
        files.filter { file ->
          !file.isDirectory &&
            isSubtitleFile(file.name) &&
            file.name.substringBeforeLast('.').startsWith(baseName, ignoreCase = true)
        }

      if (subtitles.isEmpty()) {
        Log.d(TAG, "No matching subtitle files found for: $baseName")
        return
      }

      Log.d(TAG, "Found ${subtitles.size} subtitle file(s)")

      // Load subtitles via proxy
      // Dispatch JNI calls to the Main thread to prevent concurrent JNI usage/crashes.
      withContext(Dispatchers.Main) {
        subtitles.forEachIndexed { index, subtitle ->
          var registeredProxyUrl: String? = null
          try {
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
    val videoDirectory = videoFile.parentFile ?: return
    val baseName = videoFileName.substringBeforeLast('.')

    // Match the name first: isFile is a stat syscall on every entry in the directory, and only a
    // handful of those entries can be this video's sidecar. Pure string checks, so the selected
    // set — and its listFiles order — is unchanged.
    val subtitles =
      videoDirectory.listFiles()?.filter { file ->
        isSubtitleFile(file.name) &&
          file.nameWithoutExtension.startsWith(baseName, ignoreCase = true) &&
          file.isFile
      } ?: emptyList()

    if (subtitles.isNotEmpty()) {
      withContext(Dispatchers.Main) {
        subtitles.forEachIndexed { index, subtitle ->
          if (!isGenerationCurrent(expectedGeneration)) return@forEachIndexed
          // MPV command format: sub-add <url> [<flags> [<title>]]
          // Keep tracks available while respecting the user's current subtitle-off state.
          val flag = if (index == 0 && selectFirst) "select" else "auto"
          val added =
            expectedGeneration?.let { generation ->
              PlaybackSession.commandForGeneration(
                generation,
                "sub-add",
                subtitle.absolutePath,
                flag,
                subtitle.name,
              )
            } ?: run {
              PlaybackSession.command("sub-add", subtitle.absolutePath, flag, subtitle.name)
              true
            }
          if (!added) return@forEachIndexed
          Log.d(TAG, "Loaded local subtitle: ${subtitle.name} (flag=$flag)")
        }
      }
    }
  }

  private fun isSubtitleFile(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
    return extension in
      setOf(
        // Common & modern
        "srt",
        "vtt",
        "ass",
        "ssa",
        // DVD / Blu-ray
        "sub",
        "idx",
        "sup",
        // Streaming / XML / Professional
        "xml",
        "ttml",
        "dfxp",
        "itt",
        "ebu",
        "imsc",
        "usf",
        // Online platforms
        "sbv",
        "srv1",
        "srv2",
        "srv3",
        "json",
        // Legacy & niche
        "sami",
        "smi",
        "mpl",
        "pjs",
        "stl",
        "rt",
        "psb",
        "cap",
        // Broadcast captions
        "scc",
        "vttx",
        // Karaoke / lyrics
        "lrc",
        "krc",
        // Fallback / raw text
        "txt",
        "pgs",
      )
  }

  private fun isGenerationCurrent(expectedGeneration: Long?): Boolean =
    expectedGeneration == null || PlaybackSession.isCurrentGeneration(expectedGeneration)
}
