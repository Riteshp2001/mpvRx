// SPDX-License-Identifier: AGPL-3.0-or-later

package app.gyrolet.mpvrx.ui.mediainfo

import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetAction
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.ChapterNode
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.ui.player.PlaybackPhase
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.TrackNode
import app.gyrolet.mpvrx.ui.player.reportedVideoBitDepth
import app.gyrolet.mpvrx.ui.player.resolveAudioFormatInfo
import app.gyrolet.mpvrx.ui.player.selectedVideoRangeBadges
import app.gyrolet.mpvrx.ui.player.toObject
import app.gyrolet.mpvrx.utils.clipboard.SafeClipboard
import app.gyrolet.mpvrx.utils.logging.Log
import app.gyrolet.mpvrx.utils.media.MediaInfoOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import java.io.File

private data class FileReport(
  val name: String,
  val data: MediaInfoOps.Report?,
)

private data class PlaybackReport(
  val data: MediaInfoOps.Report?,
)

@Composable
internal fun PlaybackMediaInfoSheet(onDismissRequest: () -> Unit) {
  val state by PlaybackSession.state.collectAsStateWithLifecycle()
  val item = state.currentItem
  val title = stringResource(R.string.ui_media_info)
  val context = LocalContext.current
  key(state.generation) {
    val uri = remember(item) { item?.mediaInfoFileUri() }
    val displayTitle =
      item?.title?.takeIf { it.isNotBlank() && "://" !in it && !it.startsWith("magnet:", ignoreCase = true) }
        ?: item
          ?.networkSource
          ?.relativePath
          ?.substringAfterLast('/')
          ?.takeIf(String::isNotBlank)
        ?: title
    val local by produceState<FileReport?>(null, uri) {
      if (uri != null) {
        val fileName = mediaInfoDisplayName(context, uri, displayTitle)
        val result = MediaInfoOps.getMediaInfoReport(context, uri, fileName)
        if (result.isFailure) Log.w("PlaybackMediaInfo", "File report unavailable; using playback metadata")
        value = FileReport(fileName, result.getOrNull())
      }
    }
    val openedFile = local
    val fileName = openedFile?.name ?: displayTitle
    val fileReport = openedFile?.data
    val readingFile = uri != null && openedFile == null
    val useSnapshot =
      !readingFile &&
        fileReport == null &&
        item != null &&
        (state.phase == PlaybackPhase.READY || state.phase == PlaybackPhase.BACKGROUND)
    val snapshot =
      if (useSnapshot) {
        rememberPlaybackReport(state.generation, fileName)
      } else {
        null
      }
    val report = fileReport ?: snapshot?.data

    PlayerSheet(
      onDismissRequest = onDismissRequest,
      title = title,
      actions = {
        if (report != null) {
          PlayerSheetAction(Icons.RoundedFilled.ContentCopy, stringResource(R.string.ui_copy_all), onClick = {
            SafeClipboard.copyPlainText(context, fileName, report.rawText)
          })
        }
        PlayerSheetAction(Icons.RoundedFilled.Close, stringResource(R.string.ui_close), onClick = onDismissRequest)
      },
    ) {
      when {
        report != null ->
          MediaInfoDetails.Content(
            report,
            fileName,
            uri.takeIf { fileReport != null },
            inPlayer = true,
            playbackSnapshot = fileReport == null,
          )
        readingFile || useSnapshot && snapshot == null || item != null && state.phase == PlaybackPhase.LOADING ->
          MediaInfoDetails.LoadingContent()
        else -> MediaInfoDetails.ErrorContent(stringResource(R.string.media_info_unavailable))
      }
    }
  }
}

/** Reopen only the original local file, never an authenticated stream or the player's fd://. */
private fun PlaybackItem.mediaInfoFileUri(): Uri? {
  if (networkSource != null) return null
  val uri = Uri.parse(originalUri)
  return when {
    uri.scheme == "content" || uri.scheme == "file" -> uri
    uri.scheme == null && originalUri.startsWith('/') -> Uri.fromFile(File(originalUri))
    else -> null
  }
}

@Composable
private fun rememberPlaybackReport(
  generation: Long,
  fileName: String,
): PlaybackReport? {
  val json = koinInject<Json>()
  val context = LocalContext.current
  val tracks by remember { PlaybackSession.propNode["track-list"] }.collectAsStateWithLifecycle()
  val chapters by remember { PlaybackSession.propNode["chapter-list"] }.collectAsStateWithLifecycle()
  val format by remember { PlaybackSession.propString["file-format"] }.collectAsStateWithLifecycle()
  val gamma by remember { PlaybackSession.propString["video-params/gamma"] }.collectAsStateWithLifecycle()
  val report by produceState<PlaybackReport?>(null, generation, fileName, tracks, chapters, format, gamma) {
    val data =
      withContext(Dispatchers.IO) {
        PlaybackSession
          .readLoadedMedia(generation) { reader ->
            reader.playbackDetails(json, fileName)
          }?.let { (mediaInfo, bytes, chapterEntries) ->
            val general =
              mediaInfo.general.copy(
                fileSize = bytes?.let { Formatter.formatFileSize(context, it) }.orEmpty(),
              )
            MediaInfoOps.formatReport(mediaInfo.copy(general = general), fileName, chapterEntries)
          }
      }
    value = PlaybackReport(data)
  }
  return report
}

private data class PlaybackDetails(
  val data: MediaInfoOps.MediaInfoData,
  val fileSize: Long?,
  val chapters: List<Pair<String, String>>,
)

private fun PlaybackSession.PropertyReader.playbackDetails(
  json: Json,
  fileName: String,
): PlaybackDetails? {
  val tracks =
    try {
      node("track-list")?.toObject<List<TrackNode>>(json).orEmpty()
    } catch (error: SerializationException) {
      Log.w("PlaybackMediaInfo", "mpv returned an unreadable track list", error)
      return null
    }
  val container = string("file-format").orEmpty()
  if (tracks.isEmpty() && container.isBlank()) return null
  val selectedVideo = node("current-tracks/video")?.asMap().orEmpty()
  val currentVideoId = selectedVideo["id"]?.asInt()
  val currentAudioId = int("current-tracks/audio/id")
  val decodedAudio = node("audio-params")?.asMap().orEmpty()
  val reportedVideo = node("video-params")?.asMap().orEmpty()
  val decodedVideo = node("video-dec-params")?.asMap() ?: reportedVideo
  val bitDepth = decodedVideo.reportedVideoBitDepth() ?: reportedVideo.reportedVideoBitDepth()
  val videoRange =
    if (currentVideoId != null &&
      tracks.any { it.id.toLong() == currentVideoId && it.isVideo && !it.isAlbumArtwork }
    ) {
      selectedVideoRangeBadges(selectedVideo, decodedVideo, reportedVideo, bitDepth).joinToString(" / ") { it.label }
    } else {
      ""
    }

  val data =
    MediaInfoOps.MediaInfoData(
      general =
        MediaInfoOps.GeneralInfo(
          format = container,
          title = fileName,
          duration =
            double("duration")
              ?.takeIf { it.isFinite() && it >= 0.0 }
              ?.let { DateUtils.formatElapsedTime(it.toLong()) }
              .orEmpty(),
        ),
      videoStreams =
        tracks.filter { it.isVideo && !it.isAlbumArtwork }.mapIndexed { index, track ->
          val selected = track.id.toLong() == currentVideoId
          MediaInfoOps.VideoStreamInfo(
            streamIndex = index,
            id = track.id.toString(),
            format = track.codec.orEmpty(),
            formatInfo = track.codecDesc.orEmpty(),
            formatProfile = track.codecProfile.orEmpty(),
            width = (track.demuxW ?: if (selected) decodedVideo["w"]?.asInt() else null)?.toString().orEmpty(),
            height = (track.demuxH ?: if (selected) decodedVideo["h"]?.asInt() else null)?.toString().orEmpty(),
            frameRate =
              track.demuxFps
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.toString()
                .orEmpty(),
            bitDepth = if (selected) bitDepth?.toString().orEmpty() else "",
            hdrFormat = if (selected) videoRange else "",
            bitRate = track.effectiveBitrate.bitRateLabel(),
          )
        },
      audioStreams =
        tracks.filter(TrackNode::isAudio).mapIndexed { index, track ->
          val selected = track.id == currentAudioId
          val audioFormat = resolveAudioFormatInfo(track.codec, track.codecProfile, null, null, null, track.metadata)
          MediaInfoOps.AudioStreamInfo(
            streamIndex = index,
            id = track.id.toString(),
            format = track.codec.orEmpty(),
            formatInfo = track.codecDesc.orEmpty(),
            formatProfile = track.codecProfile.orEmpty(),
            channels =
              (track.demuxChannelCount ?: if (selected) decodedAudio["channel-count"]?.asInt() else null)
                ?.toString()
                .orEmpty(),
            channelLayout =
              (track.demuxChannels ?: if (selected) decodedAudio["channels"]?.asString() else null)
                .orEmpty(),
            samplingRate = track.demuxSampleRate?.let { "$it Hz" }.orEmpty(),
            bitRate = track.effectiveBitrate.bitRateLabel(),
            title = track.effectiveTitle.orEmpty(),
            language = track.effectiveLang.orEmpty(),
            bitDepth = audioFormat?.sourceBitDepth?.toString().orEmpty(),
          )
        },
      textStreams =
        tracks.filter(TrackNode::isSubtitle).mapIndexed { index, track ->
          MediaInfoOps.TextStreamInfo(
            streamIndex = index,
            id = track.id.toString(),
            format = track.codec.orEmpty(),
            title = track.effectiveTitle.orEmpty(),
            language = track.effectiveLang.orEmpty(),
            hearingImpaired = track.hearingImpaired == true,
          )
        },
    )
  val chapters =
    try {
      node("chapter-list")?.toObject<List<ChapterNode>>(json).orEmpty()
    } catch (error: SerializationException) {
      Log.w("PlaybackMediaInfo", "mpv returned an unreadable chapter list", error)
      emptyList()
    }.filter { it.time.isFinite() && it.time >= 0f }
      .mapIndexed { index, chapter ->
        DateUtils.formatElapsedTime(chapter.time.toLong()) to chapter.title.ifBlank { "#${index + 1}" }
      }
  return PlaybackDetails(data, string("file-size")?.toLongOrNull()?.takeIf { it > 0L }, chapters)
}

private fun Long?.bitRateLabel(): String = this?.takeIf { it >= 1_000L }?.let { "${it / 1_000L} kb/s" }.orEmpty()
