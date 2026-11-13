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
          null, "" -> BitmapFactory.decodeFile(artworkUri)
          "file" -> BitmapFactory.decodeFile(uri.path)
          "content", "android.resource" ->
            context.contentResolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input) }
          "http", "https" -> {
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
            connection.inputStream.use { input ->
              BitmapFactory.decodeStream(input)
            }
          }
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
    pictureBytes: ByteArray? = runCatching { retriever.embeddedPicture }.getOrNull(),
    maxSize: Int = MAX_THUMBNAIL_SIZE,
  ): Bitmap? =
    decodeRetrieverArtwork(retriever, pictureBytes, maxSize)
      ?: decodeContainerOrSidecar(videoPath, maxSize = maxSize)

  fun decodeContainerOrSidecar(
    videoPath: String?,
    context: Context? = null,
    videoUri: Uri? = null,
    displayName: String? = null,
    maxSize: Int = MAX_THUMBNAIL_SIZE,
  ): Bitmap? =
    MatroskaEmbeddedArtworkExtractor.decode(videoPath, maxSize)
      ?: (if (context != null) {
        videoUri?.takeIf { it.scheme == "content" }?.let { uri ->
          MatroskaEmbeddedArtworkExtractor.decode(context, uri, displayName ?: videoPath, maxSize)
        }
      } else {
        null
      })
      ?: decodeSidecar(videoPath, maxSize)

  fun decodeSidecar(videoPath: String?, maxSize: Int = MAX_THUMBNAIL_SIZE): Bitmap? =
    videoPath
      ?.let(EmbeddedArtworkCandidates::forVideoPath)
      ?.asSequence()
      ?.map(::File)
      ?.firstNotNullOfOrNull { candidate ->
        candidate
          .takeIf { it.isFile && it.canRead() }
          ?.let { file ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            BitmapFactory.decodeFile(
              file.path,
              BitmapFactory.Options().apply {
                inSampleSize = calculateThumbnailSampleSize(bounds.outWidth, bounds.outHeight, maxSize)
                inPreferredConfig = Bitmap.Config.ARGB_8888
              },
            )
          }
      }

  fun decodeRetrieverArtwork(
    retriever: MediaMetadataRetriever,
    pictureBytes: ByteArray? = runCatching { retriever.embeddedPicture }.getOrNull(),
    maxSize: Int = MAX_THUMBNAIL_SIZE,
  ): Bitmap? {
    pictureBytes
      ?.takeIf { it.isNotEmpty() }
      ?.let { bytes ->
        runCatching {
          val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
          BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
              inSampleSize = calculateThumbnailSampleSize(bounds.outWidth, bounds.outHeight, maxSize)
              // Album art is photographic and often contains smooth gradients; preserve full color.
              inPreferredConfig = Bitmap.Config.ARGB_8888
            },
          )
        }.getOrNull()
      }
      ?.let { return it }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      runCatching { retriever.getPrimaryImage() }
        .getOrNull()
        ?.let { return it }
    }

    return null
  }
}
