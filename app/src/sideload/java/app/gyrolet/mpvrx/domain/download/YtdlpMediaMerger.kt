/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.download

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
internal object YtdlpMediaMerger {
  suspend fun merge(
    context: Context,
    candidates: List<File>,
    outputFile: File,
  ): File {
    val inputs = withContext(Dispatchers.IO) { findInputs(candidates) }
    val stagingFile = File(outputFile.parentFile, "${outputFile.name}.merging")

    return withContext(Dispatchers.Main.immediate) {
      suspendCancellableCoroutine { continuation ->
        stagingFile.delete()
        val videoItem =
          EditedMediaItem
            .Builder(MediaItem.fromUri(Uri.fromFile(inputs.video)))
            .setRemoveAudio(true)
            .build()
        val audioItem =
          EditedMediaItem
            .Builder(MediaItem.fromUri(Uri.fromFile(inputs.audio)))
            .setRemoveVideo(true)
            .build()
        val composition =
          Composition
            .Builder(
              EditedMediaItemSequence.withVideoFrom(listOf(videoItem)),
              EditedMediaItemSequence.withAudioFrom(listOf(audioItem)),
            ).build()

        lateinit var transformer: Transformer
        transformer =
          Transformer
            .Builder(context.applicationContext)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(
              object : Transformer.Listener {
                override fun onCompleted(
                  composition: Composition,
                  exportResult: ExportResult,
                ) {
                  CoroutineScope(continuation.context).launch(Dispatchers.IO) {
                    runCatching { publish(stagingFile, outputFile) }
                      .onSuccess { published ->
                        if (continuation.isActive) continuation.resume(published)
                      }.onFailure { error ->
                        stagingFile.delete()
                        if (continuation.isActive) continuation.resumeWithException(error)
                      }
                  }
                }

                override fun onError(
                  composition: Composition,
                  exportResult: ExportResult,
                  exportException: ExportException,
                ) {
                  stagingFile.delete()
                  if (continuation.isActive) continuation.resumeWithException(exportException)
                }
              },
            ).build()

        continuation.invokeOnCancellation {
          Handler(Looper.getMainLooper()).post {
            transformer.cancel()
            stagingFile.delete()
          }
        }
        runCatching { transformer.start(composition, stagingFile.absolutePath) }
          .onFailure { error ->
            stagingFile.delete()
            if (continuation.isActive) continuation.resumeWithException(error)
          }
      }
    }
  }

  private fun findInputs(candidates: List<File>): MergeInputs {
    val inspected =
      candidates
        .asSequence()
        .filter { it.isFile && it.length() > 0L }
        .mapNotNull(::inspect)
        .toList()
    val video =
      inspected.firstOrNull { it.hasVideo && !it.hasAudio } ?: inspected.firstOrNull { it.hasVideo }
        ?: throw IllegalStateException("Downloaded video stream was not found")
    val audio =
      inspected.firstOrNull { it.file != video.file && it.hasAudio && !it.hasVideo }
        ?: inspected.firstOrNull { it.file != video.file && it.hasAudio }
        ?: throw IllegalStateException("Downloaded audio stream was not found")
    return MergeInputs(video.file, audio.file)
  }

  private fun inspect(file: File): InspectedFile? =
    runCatching {
      val extractor = MediaExtractor()
      try {
        extractor.setDataSource(file.absolutePath)
        var hasVideo = false
        var hasAudio = false
        repeat(extractor.trackCount) { index ->
          when {
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true ->
              hasVideo =
                true
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true ->
              hasAudio =
                true
          }
        }
        InspectedFile(file, hasVideo, hasAudio).takeIf { it.hasVideo || it.hasAudio }
      } finally {
        extractor.release()
      }
    }.getOrNull()

  private fun publish(
    stagingFile: File,
    outputFile: File,
  ): File {
    check(stagingFile.isFile && stagingFile.length() > 0L) { "Media merge produced no output" }
    outputFile.parentFile?.mkdirs()
    DownloadOutputReservations.publish(stagingFile, outputFile)
    check(outputFile.isFile && outputFile.length() > 0L) { "Could not finalize merged download" }
    return outputFile
  }

  private data class InspectedFile(
    val file: File,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
  )

  private data class MergeInputs(
    val video: File,
    val audio: File,
  )
}
