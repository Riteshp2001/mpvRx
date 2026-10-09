/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File

object EmbeddedArtworkCandidates {
  private val artworkExtensions = listOf("jpg", "jpeg", "png", "webp")
  private val genericArtworkNames = listOf("cover", "folder", "poster", "thumbnail")

  fun forVideoPath(path: String): List<String> {
    if (path.isBlank() || path.isRemoteOrOpaqueUri()) return emptyList()
    val normalizedPath = path.replace('\\', '/')
    val parent =
      normalizedPath.substringBeforeLast('/', missingDelimiterValue = "").takeIf { it.isNotBlank() }
        ?: return emptyList()
    val fileName = normalizedPath.substringAfterLast('/')
    val baseName =
      fileName.substringBeforeLast('.', missingDelimiterValue = fileName).takeIf { it.isNotBlank() }
        ?: return emptyList()

    return buildList {
      artworkExtensions.forEach { extension ->
        add("$parent/$baseName.$extension")
      }
      artworkExtensions.forEach { extension ->
        add("$parent/$baseName.cover.$extension")
        add("$parent/$baseName-cover.$extension")
      }
      genericArtworkNames.forEach { name ->
        artworkExtensions.forEach { extension ->
          add("$parent/$name.$extension")
        }
      }
    }.distinct()
  }

  /**
   * Cheap existence probe for a sidecar image next to [videoPath]. Callers use it to skip opening a
   * [MediaMetadataRetriever] when there is provably nothing to find; it stops at the first hit and
   * never decodes.
   */
  fun hasSidecarArtwork(videoPath: String?): Boolean =
    videoPath
      ?.let(EmbeddedArtworkCandidates::forVideoPath)
      ?.any { File(it).isFile } ?: false

  private fun String.isRemoteOrOpaqueUri(): Boolean =
    startsWith("http://", ignoreCase = true) ||
      startsWith("https://", ignoreCase = true) ||
      startsWith("rtmp://", ignoreCase = true) ||
      startsWith("rtsp://", ignoreCase = true) ||
      startsWith("ftp://", ignoreCase = true) ||
      startsWith("sftp://", ignoreCase = true) ||
      startsWith("smb://", ignoreCase = true) ||
      startsWith("content://", ignoreCase = true)
}

internal object EmbeddedArtworkResolver {
  private const val ARTWORK_MAX_SIZE_PX = 1024
  private const val MAX_ARTWORK_BYTES = 24 * 1024 * 1024

  fun decodeArtworkUri(
    context: Context,
    artworkUri: String?,
  ): Bitmap? {
    if (artworkUri.isNullOrBlank()) return null
    app.gyrolet.mpvrx.presentation.components.RemoteImageLoader.getFromMemory(artworkUri)?.let { return it }
    val uri = Uri.parse(artworkUri)
    return runCatching {
      val decoded =
        when (uri.scheme?.lowercase()) {
          null, "" -> decodeFileSampled(artworkUri)
          "file" -> uri.path?.let(::decodeFileSampled)
          "content", "android.resource" -> decodeStreamSampled(context, uri)
          "http", "https" -> decodeHttpSampled(artworkUri, uri)
          else -> null
        }
      decoded?.also {
        app.gyrolet.mpvrx.presentation.components.RemoteImageLoader.putInMemory(artworkUri, it)
      }
    }.getOrNull()
  }

  fun decodeEmbeddedArtwork(
    videoPath: String?,
    retriever: MediaMetadataRetriever,
  ): Bitmap? =
    decodeRetrieverArtwork(retriever)
      ?: MatroskaEmbeddedArtworkExtractor.decode(videoPath)
      ?: decodeSidecar(videoPath)

  fun decodeSidecar(videoPath: String?): Bitmap? =
    videoPath
      ?.let(EmbeddedArtworkCandidates::forVideoPath)
      ?.asSequence()
      ?.map(::File)
      ?.firstNotNullOfOrNull { candidate ->
        candidate
          .takeIf { it.isFile && it.canRead() }
          ?.let { decodeFileSampled(it.path) }
      }

  fun decodeRetrieverArtwork(retriever: MediaMetadataRetriever): Bitmap? {
    retriever.embeddedPicture
      ?.takeIf { it.isNotEmpty() }
      ?.let { bytes -> decodeByteArraySampled(bytes) }
      ?.let { return it }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      runCatching { retriever.getPrimaryImage() }
        .getOrNull()
        ?.let { return it }
    }

    return null
  }

  /**
   * Matches the cap RemoteImageLoader applies to its own decoder: anything decoded here is shared
   * with RemoteImage through that loader's memory cache, so a larger bitmap was never used. A
   * 4000x3000 cover goes from ~48MB to ~3MB. Config is left at the BitmapFactory default so
   * existing transparency behaviour is unchanged.
   */
  private fun sampleOptions(bounds: BitmapFactory.Options): BitmapFactory.Options =
    BitmapFactory.Options().apply {
      inSampleSize = calculateThumbnailSampleSize(bounds.outWidth, bounds.outHeight, ARTWORK_MAX_SIZE_PX)
    }

  private fun decodeFileSampled(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return BitmapFactory.decodeFile(path, sampleOptions(bounds))
  }

  private fun decodeStreamSampled(
    context: Context,
    uri: Uri,
  ): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = context.contentResolver.openInputStream(uri) ?: return null
    boundsStream.use { input ->
      BitmapFactory.decodeStream(input, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return context.contentResolver.openInputStream(uri)?.use { input ->
      BitmapFactory.decodeStream(input, null, sampleOptions(bounds))
    }
  }

  private fun decodeByteArraySampled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampleOptions(bounds))
  }

  private fun decodeHttpSampled(
    artworkUri: String,
    uri: Uri,
  ): Bitmap? {
    val bytes = readBoundedArtworkBytes(artworkUri, uri) ?: return null
    return decodeByteArraySampled(bytes)
  }

  /** Caps the buffered body so a huge or endless response cannot be fully materialised in memory. */
  private fun readBoundedArtworkBytes(
    artworkUri: String,
    uri: Uri,
  ): ByteArray? {
    val token = uri.getQueryParameter("token")
    val connection = (java.net.URL(artworkUri).openConnection() as java.net.HttpURLConnection).apply {
      connectTimeout = 8000
      readTimeout = 8000
      instanceFollowRedirects = true
      setRequestProperty("User-Agent", "Mozilla/5.0 (Android) mpvRx")
      if (!token.isNullOrBlank()) {
        setRequestProperty("Authorization", "Bearer $token")
      }
    }
    return connection.inputStream.use { input ->
      val buffer = ByteArrayOutputStream()
      val chunk = ByteArray(16 * 1024)
      var total = 0
      while (true) {
        val read = input.read(chunk)
        if (read <= 0) break
        total += read
        if (total > MAX_ARTWORK_BYTES) return@use null
        buffer.write(chunk, 0, read)
      }
      buffer.toByteArray()
    }
  }
}
