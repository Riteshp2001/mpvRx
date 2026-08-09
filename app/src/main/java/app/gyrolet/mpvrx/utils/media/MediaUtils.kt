/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.torrent.isTorrentSource
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.ui.player.PlayerLookupHints
import app.gyrolet.mpvrx.ui.torrent.TorrentSelectionActivity
import `is`.xyz.mpv.Utils
import java.io.File
import kotlin.math.pow

data class PlaybackSubtitleTrack(
  val url: String,
  val label: String = "",
  val languageCode: String? = null,
)

/**
 * Central entry point for video playback operations.
 *
 * ## Architecture
 *
 * **MediaUtils.playFile()** - High-level API (this class)
 * - Called by UI components (Video List, FAB buttons, dialogs)
 * - Creates Intent and launches PlayerActivity
 * - Handles Video objects, URI strings, and file paths
 *
 * **BaseMPVView.playFile()** - Low-level MPV control (library)
 * - Called internally by PlayerActivity.onCreate()
 * - **Do not call directly from UI code**
 *
 * ## Flow
 * ```
 * UI → MediaUtils.playFile() → Intent → PlayerActivity → BaseMPVView.playFile() → MPV
 * ```
 *
 * ## Special Cases
 * External apps use ACTION_SEND/ACTION_VIEW intents directly to PlayerActivity,
 * bypassing MediaUtils.
 */
object MediaUtils {
  /**
   * Play video content from any source.
   *
   * Supports:
   * - Video objects (from media library)
   * - URI strings (http://, content://, file://)
   * - File paths (absolute or relative)
   *
   * @param source Video object, URI string, android.net.Uri, or file path
   * @param launchSource Analytics identifier (e.g., "open_file", "recently_played")
   */
  fun playFile(
    source: Any,
    context: Context,
    launchSource: String? = null,
    title: String? = null,
    headers: Map<String, String>? = null,
    subtitles: List<Uri> = emptyList(),
    enabledSubtitles: List<Uri> = emptyList(),
    subtitleTracks: List<PlaybackSubtitleTrack> = emptyList(),
    lookupHints: PlayerLookupHints = PlayerLookupHints(),
    torrentFileIndex: Int? = null,
    torrentPreparationId: String? = null,
    mediaDescription: String? = null,
    posterUrl: String? = null,
    backdropUrl: String? = null,
  ) {
    val uri =
      when (source) {
        is Video -> {
          val intent = Intent(Intent.ACTION_VIEW, source.uri)
          val torrentSource = source.uri.toString().takeIf { isTorrentSource(it, source.mimeType) }
          intent.setClass(
            context,
            if (torrentSource != null && torrentFileIndex == null && torrentPreparationId == null) {
              TorrentSelectionActivity::class.java
            } else {
              PlayerActivity::class.java
            },
          )
          intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
          intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          intent.putExtra("internal_launch", true) // Enables subtitle autoload
          source.path.takeIf { java.io.File(it).isFile }?.let { intent.putExtra("local_media_path", it) }
          intent.putExtra("is_audio", source.isAudio)
          applyPlaybackExtras(
            intent = intent,
            launchSource = launchSource,
            title =
              title
                ?: source.title.takeIf { shouldForwardVideoTitle(source) && it.isNotBlank() }
                ?: source.displayName.takeIf { shouldForwardVideoTitle(source) && it.isNotBlank() }
                ?: if (launchSource != null &&
                  (launchSource.contains("playlist") || launchSource == "m3u_playlist")
                ) {
                  source.displayName
                } else {
                  null
                },
            headers = headers,
            subtitles = subtitles,
            enabledSubtitles = enabledSubtitles,
            subtitleTracks = subtitleTracks,
            lookupHints = lookupHints,
            torrentFileIndex = torrentFileIndex,
            torrentPreparationId = torrentPreparationId,
            torrentSource = torrentSource,
            mediaDescription = mediaDescription,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
          )
          context.startActivity(intent)
          return
        }

        is String -> {
          if (source.isBlank()) return
          // Handle file paths with # characters properly
          if (source.startsWith("/") || source.startsWith("file://")) {
            // It's a local file path - create URI safely
            val filePath =
              if (source.startsWith("file://")) {
                source.removePrefix("file://")
              } else {
                source
              }
            Uri.fromFile(java.io.File(filePath))
          } else {
            // It's likely a network URI - parse normally
            val parsedUri = source.toUri()
            parsedUri.scheme?.let { parsedUri } ?: "file://$source".toUri()
          }
        }

        is android.net.Uri -> source
        else -> {
          android.util.Log.e("MediaUtils", "Unsupported source type: ${source::class.java}")
          return
        }
      }

    val intent = Intent(Intent.ACTION_VIEW, uri)
    val torrentSource =
      when (source) {
        is String -> source.trim()
        is Uri -> source.toString()
        else -> uri.toString()
      }.takeIf { isTorrentSource(it) }
    intent.setClass(
      context,
      if (torrentSource != null && torrentFileIndex == null && torrentPreparationId == null) {
        TorrentSelectionActivity::class.java
      } else {
        PlayerActivity::class.java
      },
    )
    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    applyPlaybackExtras(
      intent = intent,
      launchSource = launchSource,
      title = title,
      headers = headers,
      subtitles = subtitles,
      enabledSubtitles = enabledSubtitles,
      subtitleTracks = subtitleTracks,
      lookupHints = lookupHints,
      torrentFileIndex = torrentFileIndex,
      torrentPreparationId = torrentPreparationId,
      torrentSource = torrentSource,
      mediaDescription = mediaDescription,
      posterUrl = posterUrl,
      backdropUrl = backdropUrl,
    )
    context.startActivity(intent)
  }

  private fun applyPlaybackExtras(
    intent: Intent,
    launchSource: String?,
    title: String?,
    headers: Map<String, String>?,
    subtitles: List<Uri>,
    enabledSubtitles: List<Uri>,
    subtitleTracks: List<PlaybackSubtitleTrack>,
    lookupHints: PlayerLookupHints,
    torrentFileIndex: Int?,
    torrentPreparationId: String?,
    torrentSource: String?,
    mediaDescription: String?,
    posterUrl: String?,
    backdropUrl: String?,
  ) {
    launchSource?.let { intent.putExtra("launch_source", it) }
    title?.let {
      intent.putExtra("title", it)
      intent.putExtra(EXTRA_MEDIA_TITLE, it)
    }
    torrentFileIndex?.takeIf { it >= 0 }?.let { intent.putExtra(EXTRA_TORRENT_FILE_INDEX, it) }
    torrentPreparationId?.takeIf { it.isNotBlank() }?.let { intent.putExtra(EXTRA_TORRENT_PREPARATION_ID, it) }
    torrentSource?.takeIf { it.isNotBlank() }?.let { intent.putExtra(EXTRA_TORRENT_SOURCE, it) }
    mediaDescription?.takeIf { it.isNotBlank() }?.let { intent.putExtra(EXTRA_MEDIA_DESCRIPTION, it) }
    posterUrl?.takeIf { it.isNotBlank() }?.let { intent.putExtra(EXTRA_MEDIA_POSTER_URL, it) }
    backdropUrl?.takeIf { it.isNotBlank() }?.let { intent.putExtra(EXTRA_MEDIA_BACKDROP_URL, it) }
    lookupHints.canonicalTitle?.takeIf { it.isNotBlank() }?.let { intent.putExtra("introdb_title", it) }
    lookupHints.imdbId?.takeIf { it.isNotBlank() }?.let { intent.putExtra("introdb_imdb_id", it) }
    lookupHints.tmdbId?.let { intent.putExtra("introdb_tmdb_id", it) }
    lookupHints.mediaType?.takeIf { it.isNotBlank() }?.let { intent.putExtra("introdb_media_type", it) }
    lookupHints.season?.let { intent.putExtra("introdb_season", it) }
    lookupHints.episode?.let { intent.putExtra("introdb_episode", it) }

    if (!headers.isNullOrEmpty()) {
      // PlayerActivity expects a flat array: [key1, value1, key2, value2, ...]
      val flat = headers.entries.flatMap { listOf(it.key, it.value) }.toTypedArray()
      intent.putExtra("headers", flat)
    }

    val effectiveSubtitleTracks =
      if (subtitleTracks.isNotEmpty()) {
        subtitleTracks.mapNotNull { track ->
          track.url
            .takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
            ?.let { uri -> uri to track }
        }
      } else {
        subtitles.map { uri ->
          uri to
            PlaybackSubtitleTrack(
              url = uri.toString(),
              label = "",
              languageCode = null,
            )
        }
      }

    if (effectiveSubtitleTracks.isNotEmpty()) {
      intent.putExtra("subs", effectiveSubtitleTracks.map { it.first }.toTypedArray())
      intent.putExtra(
        "subs.titles",
        effectiveSubtitleTracks.map { (_, track) -> track.label.ifBlank { "" } }.toTypedArray(),
      )
      intent.putExtra(
        "subs.langs",
        effectiveSubtitleTracks.map { (_, track) -> track.languageCode.orEmpty() }.toTypedArray(),
      )
    }

    val subtitleUris = effectiveSubtitleTracks.map { it.first }
    val enabled = enabledSubtitles.filter(subtitleUris::contains)
    if (enabled.isNotEmpty()) {
      intent.putExtra("subs.enable", enabled.toTypedArray())
    }
  }

  private fun shouldForwardVideoTitle(source: Video): Boolean {
    if (source.isAudio) return true
    val scheme = source.uri.scheme?.lowercase() ?: return false
    return scheme !in setOf("file", "content", "android.resource")
  }

  const val EXTRA_TORRENT_SOURCE = "torrent_source"
  const val EXTRA_TORRENT_FILE_INDEX = "torrent_file_index"
  const val EXTRA_TORRENT_PREPARATION_ID = "torrent_preparation_id"
  const val EXTRA_MEDIA_TITLE = "torrent_media_title"
  const val EXTRA_MEDIA_DESCRIPTION = "torrent_media_description"
  const val EXTRA_MEDIA_POSTER_URL = "torrent_media_poster_url"
  const val EXTRA_MEDIA_BACKDROP_URL = "torrent_media_backdrop_url"

  /**
   * Validate URL structure and protocol support.
   * Checks only URL format and MPV protocol support (http, https, rtsp, rtmp, etc.).
   * Network errors are detected when MPV attempts to open the stream.
   */
  fun isURLValid(url: String): Boolean =
    isTorrentSource(url) ||
      url.toUri().let { uri ->
        val structureOk =
          uri.isHierarchical && !uri.isRelative && (!uri.host.isNullOrBlank() || !uri.path.isNullOrBlank())
        structureOk && Utils.PROTOCOLS.contains(uri.scheme)
      }

  /**
   * Share videos via system share sheet.
   *
   * Uses ACTION_SEND for single video, ACTION_SEND_MULTIPLE for multiple videos.
   */
  fun shareVideos(
    context: Context,
    videos: List<Video>,
  ) {
    if (videos.isEmpty()) {
      android.util.Log.w("MediaUtils", "Cannot share: video list is empty")
      return
    }

    fun toSharableUri(v: Video): android.net.Uri? =
      v.uri.takeIf { it.scheme.equals("content", true) } ?: run {
        try {
          FileProvider.getUriForFile(context, "${context.packageName}.provider", File(v.path))
        } catch (e: IllegalArgumentException) {
          android.util.Log.e("MediaUtils", "FileProvider failed for ${v.path}: ${e.message}")
          null
        } catch (e: Exception) {
          android.util.Log.e("MediaUtils", "Failed to generate URI for ${v.path}", e)
          null
        }
      }

    val uris = videos.mapNotNull { toSharableUri(it) }

    if (uris.isEmpty()) {
      android.util.Log.w("MediaUtils", "Cannot share: no valid URIs generated for any videos")
      return
    }

    if (uris.size < videos.size) {
      android.util.Log.w("MediaUtils", "Only ${uris.size}/${videos.size} videos could be shared")
    }

    val intent =
      if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
          type = "video/*"
          putExtra(Intent.EXTRA_STREAM, uris.first())
          putExtra(Intent.EXTRA_SUBJECT, videos.first().displayName)
          putExtra(Intent.EXTRA_TITLE, videos.first().displayName)
          clipData = android.content.ClipData.newRawUri(videos.first().displayName, uris.first())
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
      } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
          type = "video/*"
          putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
          putExtra(Intent.EXTRA_SUBJECT, "Sharing ${uris.size} videos")
          val clip = android.content.ClipData.newRawUri(videos.first().displayName, uris.first())
          uris.drop(1).forEach { u -> clip.addItem(android.content.ClipData.Item(u)) }
          clipData = clip
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
      }

    context.startActivity(
      Intent.createChooser(
        intent,
        if (uris.size == 1) "Share video" else "Share ${uris.size} videos",
      ),
    )
  }

  fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (kotlin.math.ln(bytes.toDouble()) / kotlin.math.ln(1024.0)).toInt().coerceIn(0, units.size - 1)
    return "${java.text.DecimalFormat("#,##0.#").format(bytes / 1024.0.pow(digitGroups))} ${units[digitGroups]}"
  }
}
