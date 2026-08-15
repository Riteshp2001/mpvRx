/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import app.gyrolet.mpvrx.network.AndroidCookieJar
import app.gyrolet.mpvrx.network.NetworkUserAgent
import `is`.xyz.mpv.FastThumbnails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class SeekThumbnailSource(
  val decodePath: String,
  val fallbackPath: String?,
  val identity: String,
  val headers: Map<String, String>,
  val isNetwork: Boolean,
  val generation: Long,
  val videoTrack: Int,
  val edition: Int,
  val rotation: Int,
  val seekable: Boolean,
)

/**
 * Serialized frame decoder for seek previews.
 *
 * Local media uses the bundled FFmpeg thumbnail path first. Network media uses Android's
 * retriever first so the same headers and cookies as playback reach manifests, keys, and segment
 * requests. The native fallback remains useful for public formats Android cannot demux.
 */
internal class SeekThumbnailDecoder(
  context: Context,
  private val cookieJar: AndroidCookieJar,
) {
  private val appContext = context.applicationContext
  private val decodeMutex = Mutex()

  @Volatile
  private var fastThumbnailsReady = false

  suspend fun decode(
    source: SeekThumbnailSource,
    positionSeconds: Double,
    maxDimension: Int,
  ): Bitmap? =
    decodeMutex.withLock {
      withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val candidates = listOfNotNull(source.decodePath, source.fallbackPath).distinct()
        val bitmap =
          if (source.isNetwork) {
            candidates.firstNotNullOfOrNull { candidate ->
              decodeWithRetriever(candidate, source, positionSeconds, maxDimension)
            } ?: decodeWithFastThumbnails(source.decodePath, positionSeconds, maxDimension)
              ?.rotate(source.rotation)
          } else {
            decodeWithFastThumbnails(source.decodePath, positionSeconds, maxDimension)
              ?.rotate(source.rotation)
              ?: candidates.firstNotNullOfOrNull { candidate ->
                decodeWithRetriever(candidate, source, positionSeconds, maxDimension)
              }
          }

        try {
          currentCoroutineContext().ensureActive()
        } catch (cancellation: CancellationException) {
          bitmap?.takeUnless { it.isRecycled }?.recycle()
          throw cancellation
        }
        bitmap
      }
    }

  private suspend fun decodeWithFastThumbnails(
    path: String,
    positionSeconds: Double,
    maxDimension: Int,
  ): Bitmap? {
    if (!ensureFastThumbnailsReady()) return null
    return try {
      FastThumbnails.generateAsync(
        path,
        positionSeconds,
        maxDimension,
        useHwDec = false,
      )
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      null
    }
  }

  private fun ensureFastThumbnailsReady(): Boolean {
    if (fastThumbnailsReady) return true
    return runCatching { FastThumbnails.initialize(appContext) }
      .fold(
        onSuccess = {
          fastThumbnailsReady = true
          true
        },
        onFailure = { false },
      )
  }

  private fun decodeWithRetriever(
    path: String,
    source: SeekThumbnailSource,
    positionSeconds: Double,
    maxDimension: Int,
  ): Bitmap? =
    runCatching {
      val retriever = MediaMetadataRetriever()
      try {
        setRetrieverDataSource(retriever, path, requestHeaders(path, source.headers))
        val width =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            ?: 0
        val height =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            ?: 0
        val targetSize = scaledSize(width, height, maxDimension)
        val timeMicros = (positionSeconds.coerceAtLeast(0.0) * MICROS_PER_SECOND).roundToLong()
        val frame =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && targetSize != null) {
            runCatching {
              retriever.getScaledFrameAtTime(
                timeMicros,
                MediaMetadataRetriever.OPTION_CLOSEST,
                targetSize.first,
                targetSize.second,
              )
            }.getOrNull()
          } else {
            null
          } ?: retriever.getFrameAtTime(timeMicros, MediaMetadataRetriever.OPTION_CLOSEST)

        frame
          ?.scaleToMaxDimension(maxDimension)
          ?.rotate(source.rotation)
      } finally {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) retriever.close() else retriever.release()
      }
    }.getOrNull()

  private fun setRetrieverDataSource(
    retriever: MediaMetadataRetriever,
    path: String,
    headers: Map<String, String>,
  ) {
    val uri = Uri.parse(path)
    when {
      uri.scheme.equals("content", ignoreCase = true) ||
        uri.scheme.equals("android.resource", ignoreCase = true) ||
        uri.scheme.equals("file", ignoreCase = true) -> retriever.setDataSource(appContext, uri)
      uri.scheme != null -> retriever.setDataSource(path, headers)
      else -> retriever.setDataSource(path)
    }
  }

  private fun requestHeaders(
    path: String,
    playbackHeaders: Map<String, String>,
  ): Map<String, String> {
    val httpUrl = path.toHttpUrlOrNull() ?: return playbackHeaders
    var headers =
      PlaybackHttpHeaders.withDefault(
        playbackHeaders,
        "User-Agent",
        NetworkUserAgent.resolve(appContext),
      )
    val cookieValue =
      cookieJar
        .loadForRequest(httpUrl)
        .joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }
        .takeIf(String::isNotBlank)
    headers = PlaybackHttpHeaders.withDefault(headers, "Cookie", cookieValue)
    return headers
  }

  private fun scaledSize(
    width: Int,
    height: Int,
    maxDimension: Int,
  ): Pair<Int, Int>? {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return null
    val scale = maxDimension.toDouble() / maxOf(width, height)
    return ((width * scale).roundToInt().coerceAtLeast(1)) to
      ((height * scale).roundToInt().coerceAtLeast(1))
  }

  private fun Bitmap.scaleToMaxDimension(maxDimension: Int): Bitmap {
    if (width <= maxDimension && height <= maxDimension) return this
    val scale = maxDimension.toDouble() / maxOf(width, height)
    val scaled =
      Bitmap.createScaledBitmap(
        this,
        (width * scale).roundToInt().coerceAtLeast(1),
        (height * scale).roundToInt().coerceAtLeast(1),
        true,
      )
    if (scaled !== this && !isRecycled) recycle()
    return scaled
  }

  private fun Bitmap.rotate(rawRotation: Int): Bitmap {
    val rotation = ((rawRotation % 360) + 360) % 360
    if (rotation == 0) return this
    val rotated =
      Bitmap.createBitmap(
        this,
        0,
        0,
        width,
        height,
        Matrix().apply { postRotate(rotation.toFloat()) },
        true,
      )
    if (rotated !== this && !isRecycled) recycle()
    return rotated
  }

  private companion object {
    const val MICROS_PER_SECOND = 1_000_000.0
  }
}
