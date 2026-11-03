/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.presentation.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.gyrolet.mpvrx.network.awaitResponse
import app.gyrolet.mpvrx.utils.device.DevicePerformance
import app.gyrolet.mpvrx.utils.memory.BitmapMemoryCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.compose.koinInject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Composable
fun RemoteImage(
  url: String,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Fit,
  alignment: Alignment = Alignment.Center,
  alpha: Float = 1f,
  /**
   * Optional max age of the on-disk copy. When set, a cached file older than this is re-downloaded
   * (the stale copy is still used if the network fails). `null` keeps the default behaviour of
   * trusting the disk cache indefinitely.
   */
  cacheTtlMs: Long? = null,
  /** Decode small avatars/covers at their display tier; full-size artwork keeps the default. */
  maxDimensionPx: Int = 1024,
  colorFilter: ColorFilter? = null,
) {
  val context = LocalContext.current
  val client = koinInject<OkHttpClient>()
  var bitmap by remember(url, maxDimensionPx) {
    mutableStateOf(RemoteImageLoader.getFromMemory(url, maxDimensionPx))
  }

  LaunchedEffect(url, maxDimensionPx, cacheTtlMs) {
    if (bitmap == null) {
      bitmap = RemoteImageLoader.load(context, client, url, cacheTtlMs, maxDimensionPx)
    }
  }

  val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
  if (imageBitmap != null) {
    Image(
      bitmap = imageBitmap,
      contentDescription = contentDescription,
      modifier = modifier,
      contentScale = contentScale,
      alignment = alignment,
      alpha = alpha,
      colorFilter = colorFilter,
    )
  }
}

internal object RemoteImageLoader {
  private const val MAX_IMAGE_DIMENSION = 1024
  private const val CACHE_DIRECTORY = "remote_images"
  private const val FAILURE_RETRY_MS = 30_000L
  private const val MAX_DISK_CACHE_BYTES = 128L * 1024L * 1024L
  private val dimensionTiers = intArrayOf(128, 256, 512, MAX_IMAGE_DIMENSION)
  private data class ImageKey(val url: String, val dimension: Int)
  private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val inFlight = ConcurrentHashMap<ImageKey, Deferred<Bitmap?>>()
  private val inFlightDownloads = ConcurrentHashMap<String, Deferred<File?>>()
  private val failedAt = LruCache<String, Long>(256)
  private val memoryCache = BitmapMemoryCache<ImageKey>(heapDivisor = 32, maxSizeMiB = 16)
  private val decodeSemaphore by lazy { Semaphore(if (DevicePerformance.isLowEnd) 1 else 2) }
  private val transferSemaphore = Semaphore(3)
  private val downloadsSinceMaintenance = AtomicInteger()

  fun getFromMemory(url: String, maxDimensionPx: Int = MAX_IMAGE_DIMENSION): Bitmap? {
    val dimension = dimensionTier(maxDimensionPx)
    // A small grid decode must never satisfy the full-size player request. A larger cached copy
    // can serve a smaller card without another allocation.
    for (tier in dimensionTiers) {
      if (tier >= dimension) memoryCache.get(ImageKey(url, tier))?.let { return it }
    }
    return null
  }

  fun putInMemory(url: String, bitmap: Bitmap) {
    memoryCache.put(ImageKey(url, MAX_IMAGE_DIMENSION), bitmap)
  }

  suspend fun load(
    context: Context,
    client: OkHttpClient,
    url: String,
    cacheTtlMs: Long? = null,
    maxDimensionPx: Int = MAX_IMAGE_DIMENSION,
  ): Bitmap? {
    if (url.isBlank()) return null
    val key = ImageKey(url, dimensionTier(maxDimensionPx))
    getFromMemory(url, key.dimension)?.let { return it }
    val failedAtMs = failedAt.get(url)
    if (failedAtMs != null) {
      if (SystemClock.elapsedRealtime() - failedAtMs < FAILURE_RETRY_MS) return null
      failedAt.remove(url)
    }

    val candidate =
      loaderScope.async(start = CoroutineStart.LAZY) {
        loadUncoalesced(context.applicationContext, client, key, cacheTtlMs).also { result ->
          if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            if (result == null) failedAt.put(url, SystemClock.elapsedRealtime()) else failedAt.remove(url)
          }
        }
      }
    val operation =
      inFlight.putIfAbsent(key, candidate)?.also {
        candidate.cancel()
      } ?: candidate.also { owned ->
        owned.invokeOnCompletion { inFlight.remove(key, owned) }
        owned.start()
      }
    return operation.await()
  }

  private suspend fun loadUncoalesced(
    context: Context,
    client: OkHttpClient,
    key: ImageKey,
    cacheTtlMs: Long? = null,
  ): Bitmap? {
    val url = key.url
    getFromMemory(url, key.dimension)?.let { return it }

    val parsedUri = runCatching { Uri.parse(url) }.getOrNull()
    val scheme = parsedUri?.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
      return decodeSemaphore.withPermit {
        when (scheme) {
          "content", "android.resource" -> decodeSampled(context, parsedUri, key.dimension)
          "file" -> parsedUri.path?.let(::File)?.let { decodeSampled(it, key.dimension) }
          null, "" -> decodeSampled(File(url), key.dimension)
          else -> null
        }?.also { memoryCache.put(key, it) }
      }
    }

    val cached = remoteFile(context, client, url, cacheTtlMs) ?: return null
    return decodeSemaphore.withPermit {
      getFromMemory(url, key.dimension)
        ?: decodeSampled(cached, key.dimension)?.also { memoryCache.put(key, it) }
    }
  }

  private suspend fun remoteFile(
    context: Context,
    client: OkHttpClient,
    url: String,
    cacheTtlMs: Long?,
  ): File? {
    val candidate = loaderScope.async(start = CoroutineStart.LAZY) {
      transferSemaphore.withPermit { downloadOrCachedFile(context, client, url, cacheTtlMs) }
    }
    // Different decode tiers share the same original and never race writes to the disk cache.
    val operation = inFlightDownloads.putIfAbsent(url, candidate)?.also { candidate.cancel() }
      ?: candidate.also { owned ->
        owned.invokeOnCompletion { inFlightDownloads.remove(url, owned) }
        owned.start()
      }
    return operation.await()
  }

  private suspend fun downloadOrCachedFile(
    context: Context,
    client: OkHttpClient,
    url: String,
    cacheTtlMs: Long?,
  ): File? {
    val httpUrl = url.toHttpUrlOrNull() ?: return null

    val cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
    val cacheFile = File(cacheDirectory, hash(url))
    val cacheIsFresh =
      cacheTtlMs == null || System.currentTimeMillis() - cacheFile.lastModified() < cacheTtlMs
    if (cacheIsFresh && isDecodable(cacheFile)) return cacheFile

    val token = httpUrl.queryParameter("token")
    val request =
      runCatching {
        Request
          .Builder()
          .url(httpUrl)
          .header("User-Agent", "Mozilla/5.0 (Android) mpvRx")
          .apply {
            if (!token.isNullOrBlank()) {
              header("Authorization", "Bearer $token")
            }
          }
          .build()
      }.getOrNull() ?: return null

    val downloaded =
      try {
        client.newCall(request).awaitResponse().use { response ->
          if (!response.isSuccessful) return@use null
          val tempFile = File.createTempFile(cacheFile.name, ".tmp", cacheDirectory)
          try {
            // Stream the original to disk: a large poster must not allocate its entire compressed
            // body on the heap before the bounded bitmap decode even starts.
            response.body.byteStream().use { input ->
              FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }
            if (!isDecodable(tempFile)) return@use null
            if (!tempFile.renameTo(cacheFile)) return@use null
            if (downloadsSinceMaintenance.incrementAndGet() % 32 == 0) trimDiskCache(context)
            cacheFile
          } finally {
            tempFile.delete()
          }
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        null
      }
    if (downloaded != null) return downloaded

    // Offline or the host failed: an expired avatar is better than an empty circle.
    return cacheFile.takeIf(::isDecodable)
  }

  private fun decodeSampled(
    context: Context,
    uri: Uri,
    maxDimension: Int,
  ): Bitmap? =
    runCatching {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
      }
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

      var sampleSize = 1
      while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxDimension) {
        sampleSize *= 2
      }
      context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(
          input,
          null,
          BitmapFactory.Options().apply {
            inSampleSize = sampleSize
          },
        )
      }
    }.getOrNull()

  private fun decodeSampled(file: File, maxDimension: Int): Bitmap? {
    if (!file.isFile || file.length() <= 0L) return null
    return runCatching {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.absolutePath, bounds)
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

      var sampleSize = 1
      while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxDimension) {
        sampleSize *= 2
      }
      BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
          inSampleSize = sampleSize
        },
      )
    }.getOrNull()
  }

  private fun dimensionTier(maxDimensionPx: Int): Int =
    dimensionTiers.firstOrNull { it >= maxDimensionPx.coerceAtLeast(1) } ?: MAX_IMAGE_DIMENSION

  private fun isDecodable(file: File): Boolean = runCatching {
    if (!file.isFile || file.length() == 0L) return@runCatching false
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    bounds.outWidth > 0 && bounds.outHeight > 0
  }.getOrDefault(false)

  /** Only disposable remote originals are pruned; generated local/network thumbnails persist. */
  fun trimDiskCache(context: Context) {
    val directory = File(context.cacheDir, CACHE_DIRECTORY)
    val active = inFlightDownloads.keys.mapTo(mutableSetOf(), ::hash)
    val entries = directory.listFiles() ?: return
    val now = System.currentTimeMillis()
    entries.filter { it.isFile && it.name.endsWith(".tmp") }.forEach { file ->
      if (now - file.lastModified() > 24L * 60L * 60L * 1000L && active.none { file.name.startsWith(it) }) {
        file.delete()
      }
    }
    val files = entries.filter { it.isFile && !it.name.endsWith(".tmp") }
    var bytes = files.sumOf { it.length() }
    for (file in files.sortedBy { it.lastModified() }) {
      if (bytes <= MAX_DISK_CACHE_BYTES) break
      val length = file.length()
      if (file.name !in active && file.delete()) bytes -= length
    }
  }

  private fun hash(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.toByteArray())
      .let { bytes ->
        val digits = "0123456789abcdef"
        val result = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
          val value = bytes[index].toInt() and 0xff
          result[index * 2] = digits[value ushr 4]
          result[index * 2 + 1] = digits[value and 0x0f]
        }
        String(result)
      }
}
