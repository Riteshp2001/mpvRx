/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.torrent.isTorrentSource
import app.gyrolet.mpvrx.ui.browser.NavigationBarState
import app.gyrolet.mpvrx.ui.player.MediaPlaybackService
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.ui.player.PlaybackPerformanceTrace
import app.gyrolet.mpvrx.ui.player.PlaybackPhase
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.ui.player.PlayerLookupHints
import app.gyrolet.mpvrx.ui.player.PreparedPlaybackLaunchStore
import app.gyrolet.mpvrx.ui.torrent.TorrentSelectionActivity
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
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
  fun shouldPlayInMiniPlayerOnly(isAudio: Boolean): Boolean {
    if (!isAudio) return false
    if (userScriptRuntimeNeedsReload()) return false
    val audioPreferences =
      runCatching { org.koin.core.context.GlobalContext.get().get<app.gyrolet.mpvrx.preferences.AudioPreferences>() }.getOrNull()
    if (audioPreferences?.miniPlayerTrackSwitching?.get() != true) return false
    val sessionState = PlaybackSession.state.value
    val isServiceActive = MediaPlaybackService.isForegroundActive()
    val isMiniPlayerVisible = NavigationBarState.isMiniPlayerVisible
    val isSessionActive =
      sessionState.phase == PlaybackPhase.READY ||
        sessionState.phase == PlaybackPhase.BACKGROUND
    return isServiceActive || isMiniPlayerVisible || isSessionActive
  }

  fun playInMiniPlayer(
    context: Context,
    queueItems: List<PlaybackItem>,
    startIndex: Int = 0,
  ) {
    if (queueItems.isEmpty()) return
    val selectedIndex = startIndex.coerceIn(queueItems.indices)
    if (userScriptRuntimeNeedsReload()) {
      val token = PreparedPlaybackLaunchStore.stage(queueItems, selectedIndex, isExplicitQueue = true)
      context.startActivity(Intent(context, PlayerActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .setData(Uri.parse(queueItems[selectedIndex].originalUri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra("internal_launch", true)
        .putExtra("is_audio", true)
        .putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_QUEUE, true)
        .putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_TOKEN, token)
        .putExtra("playlist_index", selectedIndex))
      return
    }
    PlaybackSession.replaceQueue(queueItems, selectedIndex, isExplicitQueue = true)
    val item = queueItems[selectedIndex]
    PlaybackSession.load(item)
    PlaybackSession.setPropertyBoolean("pause", false)
    PlaybackSession.markBackground()
    NavigationBarState.isMiniPlayerVisible = true

    val serviceIntent =
      Intent(context, MediaPlaybackService::class.java).apply {
        putExtra("media_title", item.title)
        putExtra("media_artist", item.artist)
        putExtra("media_uri", item.originalUri)
        putExtra("media_identifier", item.stableId)
        putExtra("audio_background_playback", true)
        putExtra("is_audio", true)
      }
    runCatching {
      ContextCompat.startForegroundService(context, serviceIntent)
    }
  }

  private fun userScriptRuntimeNeedsReload(): Boolean {
    val preferences = org.koin.core.context.GlobalContext.get().get<app.gyrolet.mpvrx.preferences.AdvancedPreferences>()
    return PlaybackSession.userScriptsNeedReload(preferences.userScriptsConfigurationKey())
  }

  fun playFiles(
    videos: List<Video>,
    context: Context,
    startIndex: Int = 0,
    launchSource: String = "playlist",
  ) {
    if (videos.isEmpty()) return
    val selectedIndex = startIndex.coerceIn(videos.indices)
    if (videos.size == 1) {
      playFile(videos.single(), context, launchSource)
      return
    }

    val selected = videos[selectedIndex]
    val isAudioMedia = selected.isAudio || videos.all { it.isAudio }

    val queueItems =
      videos.map { video ->
        PlaybackItem.fromUri(
          uri = video.uri.toString(),
          stableId = playbackIdentity(video),
          title = video.displayName,
          mimeType = if (video.isAudio) "audio/*" else video.mimeType,
          durationSeconds = (video.duration / 1000L).toInt().takeIf { it > 0 },
          videoWidth = video.width,
          videoHeight = video.height,
        )
      }

    if (shouldPlayInMiniPlayerOnly(isAudioMedia)) {
      playInMiniPlayer(context, queueItems, selectedIndex)
      return
    }

    val launchToken =
      PreparedPlaybackLaunchStore.stage(
        items = queueItems,
        currentIndex = selectedIndex,
        isExplicitQueue = true,
      )
    val intent =
      Intent(Intent.ACTION_VIEW, selected.uri).apply {
        setClass(context, PlayerActivity::class.java)
        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra("internal_launch", true)
        putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_QUEUE, true)
        putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_TOKEN, launchToken)
        putExtra("playlist_index", selectedIndex)
        putExtra("launch_source", launchSource)
        putExtra("is_audio", selected.isAudio)
        putExtra("title", selected.displayName)
        localPlaybackPath(selected)?.let { putExtra("local_media_path", it) }
        putExtra(PlayerActivity.EXTRA_VIDEO_WIDTH, selected.width)
        putExtra(PlayerActivity.EXTRA_VIDEO_HEIGHT, selected.height)
        putExtra(PlayerActivity.EXTRA_VIDEO_ROTATION, selected.rotation)
      }
    PlaybackPerformanceTrace.mark("OPEN_REQUEST", "source=$launchSource queue=${videos.size}")
    context.startActivity(intent)
  }

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
    playlist: List<Uri> = emptyList(),
    playlistIndex: Int = 0,
    playlistTitles: List<String> = emptyList(),
    playlistArtists: List<String> = emptyList(),
    playlistArtworkUrls: List<String> = emptyList(),
    isAudio: Boolean = false,
    playlistDurationsSeconds: List<Int> = emptyList(),
    startPositionSeconds: Double? = null,
  ) {
    val videoSource = source as? Video
    val localPath =
      when {
        videoSource != null -> localPlaybackPath(videoSource)
        source is String && source.startsWith("file://", ignoreCase = true) -> source.removePrefix("file://")
        source is String && source.startsWith("/") -> source
        source is Uri && source.scheme.equals("file", ignoreCase = true) -> source.path
        else -> null
      }?.takeIf(String::isNotBlank)

    val playbackUri: Uri =
      when {
        videoSource != null -> {
          if (launchSource.isHistoryResumeLaunch() &&
            localPath != null &&
            !videoSource.uri.scheme.equals("content", ignoreCase = true)
          ) {
            resolveMediaStoreUri(context, localPath, videoSource.isAudio) ?: videoSource.uri
          } else {
            videoSource.uri
          }
        }
        source is String -> {
          if (source.isBlank()) return
          if (source.startsWith("/") || source.startsWith("file://")) {
            val filePath = if (source.startsWith("file://")) source.removePrefix("file://") else source
            Uri.fromFile(File(filePath))
          } else {
            val parsedUri = source.toUri()
            parsedUri.scheme?.let { parsedUri } ?: "file://$source".toUri()
          }
        }
        source is Uri -> source
        else -> {
          android.util.Log.e("MediaUtils", "Unsupported source type: ${source::class.java}")
          return
        }
      }

    val isAudioMedia =
      isAudio ||
        videoSource?.isAudio == true ||
        (localPath?.let { File(it).extension.lowercase() in FileTypeUtils.AUDIO_EXTENSIONS } ?: false)

    if (shouldPlayInMiniPlayerOnly(isAudioMedia)) {
      val queueItems =
        if (playlist.isNotEmpty()) {
          val selIndex = playlistIndex.coerceIn(playlist.indices)
          playlist.mapIndexed { idx, pUri ->
            PlaybackItem.fromUri(
              uri = pUri.toString(),
              title = playlistTitles.getOrNull(idx) ?: title,
              artist = playlistArtists.getOrNull(idx),
              mimeType = "audio/*",
              headers = headers.orEmpty(),
              artworkUri =
                playlistArtworkUrls.getOrNull(idx)?.takeIf(String::isNotBlank)
                  ?: posterUrl?.takeIf { idx == selIndex },
              durationSeconds = playlistDurationsSeconds.getOrNull(idx)?.takeIf { it > 0 },
            )
          }
        } else {
          val durSec =
            playlistDurationsSeconds.firstOrNull()?.takeIf { it > 0 }
              ?: videoSource?.let { (it.duration / 1000L).toInt().takeIf { d -> d > 0 } }
          listOf(
            PlaybackItem.fromUri(
              uri = playbackUri.toString(),
              stableId = localPath?.let(PlaybackIdentity::forLocalPath),
              title = title ?: videoSource?.displayName,
              artist = playlistArtists.firstOrNull(),
              mimeType = "audio/*",
              headers = headers.orEmpty(),
              artworkUri = posterUrl ?: playlistArtworkUrls.firstOrNull(),
              durationSeconds = durSec,
            ),
          )
        }
      playInMiniPlayer(context, queueItems, playlistIndex)
      return
    }

    val intent = Intent(Intent.ACTION_VIEW, playbackUri)
    val torrentSource =
      when (source) {
        is String -> source.trim()
        is Uri -> source.toString()
        else -> playbackUri.toString().takeIf { videoSource != null && isTorrentSource(it, videoSource.mimeType) }
      }?.takeIf { isTorrentSource(it) }
    intent.setClass(
      context,
      if (torrentSource != null && torrentFileIndex == null && torrentPreparationId == null) {
        TorrentSelectionActivity::class.java
      } else {
        PlayerActivity::class.java
      },
    )
    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.putExtra("internal_launch", true)
    localPath?.let { intent.putExtra("local_media_path", it) }
    if (videoSource != null) {
      intent.putExtra("is_audio", videoSource.isAudio)
      intent.putExtra(PlayerActivity.EXTRA_VIDEO_WIDTH, videoSource.width)
      intent.putExtra(PlayerActivity.EXTRA_VIDEO_HEIGHT, videoSource.height)
      intent.putExtra(PlayerActivity.EXTRA_VIDEO_ROTATION, videoSource.rotation)
    }
    startPositionSeconds?.takeIf { it.isFinite() && it >= 0 }?.let {
      intent.putExtra(PlayerActivity.EXTRA_START_POSITION_SECONDS, it)
    }
    applyPlaybackExtras(
      intent = intent,
      launchSource = launchSource,
      title =
        title
          ?: videoSource?.title?.takeIf { shouldForwardVideoTitle(videoSource) && it.isNotBlank() }
          ?: videoSource?.displayName?.takeIf { shouldForwardVideoTitle(videoSource) && it.isNotBlank() }
          ?: if (launchSource != null &&
            (launchSource.contains("playlist") || launchSource == "m3u_playlist")
          ) {
            videoSource?.displayName
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
      playlist = playlist,
      playlistIndex = playlistIndex,
      playlistTitles = playlistTitles,
      playlistArtists = playlistArtists,
      playlistArtworkUrls = playlistArtworkUrls,
      isAudio = isAudioMedia,
      playlistDurationsSeconds = playlistDurationsSeconds.ifEmpty {
        videoSource?.let { listOf((it.duration / 1000L).toInt().takeIf { d -> d > 0 } ?: 0) } ?: emptyList()
      },
    )
    PlaybackPerformanceTrace.mark(
      "OPEN_REQUEST",
      "source=${launchSource ?: (if (videoSource != null) "library" else "direct")} kind=${if (videoSource != null) "video" else (playbackUri.scheme ?: "path")}",
    )
    context.startActivity(intent)
  }

  private fun playbackIdentity(video: Video): String =
    if (video.uri.scheme.equals("archive", ignoreCase = true)) {
      PlaybackIdentity.forUri(video.uri.toString())
    } else {
      video.path.takeIf(String::isNotBlank)?.let(PlaybackIdentity::forLocalPath)
        ?: PlaybackIdentity.forUri(video.uri.toString())
    }

  private fun localPlaybackPath(video: Video): String? = video.path.takeIf { path ->
    path.isNotBlank() && Uri.parse(path).scheme?.lowercase() in setOf(null, "file")
  }

  private fun String?.isHistoryResumeLaunch(): Boolean =
    this == "recently_played" ||
      this == "recently_played_button" ||
      this == "quick_play_fab"

  @Suppress("DEPRECATION")
  private fun resolveMediaStoreUri(
    context: Context,
    filePath: String,
    isAudio: Boolean,
  ): Uri? {
    if (filePath.isBlank()) return null
    val collection =
      if (isAudio) {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
      } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
      }

    return runCatching {
      context.contentResolver
        .query(
          collection,
          arrayOf(MediaStore.MediaColumns._ID),
          "${MediaStore.MediaColumns.DATA} = ?",
          arrayOf(filePath),
          null,
        )?.use { cursor ->
          if (!cursor.moveToFirst()) return@use null
          val idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
          if (idColumn < 0) return@use null
          ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
        }
    }.getOrNull()
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
    playlist: List<Uri> = emptyList(),
    playlistIndex: Int = 0,
    playlistTitles: List<String> = emptyList(),
    playlistArtists: List<String> = emptyList(),
    playlistArtworkUrls: List<String> = emptyList(),
    isAudio: Boolean = false,
    playlistDurationsSeconds: List<Int> = emptyList(),
  ) {
    if (isAudio) {
      intent.putExtra("is_audio", true)
      intent.putExtra("media_library_audio", true)
    }
    if (playlist.isNotEmpty()) {
      val selectedIndex = playlistIndex.coerceIn(playlist.indices)
      val queueItems =
        playlist.mapIndexed { index, uri ->
          PlaybackItem.fromUri(
            uri = uri.toString(),
            title = playlistTitles.getOrNull(index),
            artist = playlistArtists.getOrNull(index),
            mimeType = if (isAudio) "audio/*" else "video/*",
            headers = headers.orEmpty(),
            artworkUri =
              playlistArtworkUrls.getOrNull(index)?.takeIf(String::isNotBlank)
                ?: posterUrl?.takeIf { index == selectedIndex },
            durationSeconds = playlistDurationsSeconds.getOrNull(index)?.takeIf { it > 0 },
          )
        }
      val launchToken =
        PreparedPlaybackLaunchStore.stage(
          items = queueItems,
          currentIndex = selectedIndex,
          isExplicitQueue = true,
        )
      intent.putExtra("internal_launch", true)
      intent.putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_QUEUE, true)
      intent.putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_TOKEN, launchToken)
      intent.putExtra("playlistIndex", selectedIndex)
      intent.putExtra("playlist_index", selectedIndex)
    }
    val selectedDuration = playlistDurationsSeconds.getOrNull(if (playlist.isNotEmpty()) playlistIndex else 0)?.takeIf { it > 0 }
    if (selectedDuration != null) {
      intent.putExtra("duration", selectedDuration)
    }
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
        "subs.name",
        effectiveSubtitleTracks.map { (_, track) -> track.label.ifBlank { "" } }.toTypedArray(),
      )
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

  fun formatRelativeTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val now = System.currentTimeMillis()
    val diff = (now - epochMillis).coerceAtLeast(0L)
    val seconds = diff / 1000L
    val minutes = seconds / 60L
    val hours = minutes / 60L
    val days = hours / 24L

    return when {
      seconds < 60 -> "Just now"
      minutes < 60 -> "${minutes}m ago"
      hours < 24 -> "${hours}h ago"
      days == 1L -> "Yesterday"
      days < 7L -> "${days}d ago"
      else -> {
        val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
        sdf.format(java.util.Date(epochMillis))
      }
    }
  }

  fun formatIsoRelativeTime(isoString: String?): String {
    if (isoString.isNullOrBlank()) return ""
    return runCatching {
      val cleanIso = isoString.trim()
      val date =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
          java.time.Instant.parse(cleanIso).toEpochMilli()
        } else {
          val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
          sdf.parse(cleanIso)?.time ?: return ""
        }
      formatRelativeTime(date)
    }.getOrDefault("")
  }
}
