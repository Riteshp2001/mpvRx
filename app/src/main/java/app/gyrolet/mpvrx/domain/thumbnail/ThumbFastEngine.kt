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
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.core.net.toUri
import `is`.xyz.mpv.FastThumbnails
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * High-performance, thread-safe thumbnail engine for mpvRx.
 *
 * Provides asynchronous frame extraction with time-bucketed in-memory LRU caching,
 * failure cooldowns, and full support for both local files (including content:// URIs)
 * and remote network streams (HTTP/HTTPS, HLS/DASH, WebDAV, SMB, yt-dlp).
 */
object ThumbFastEngine {
  private const val TAG = "ThumbFastEngine"

  const val DEFAULT_THUMBNAIL_DIMENSION = 320
  const val BUCKETS_PER_SECOND = 1.0
  private const val FAILURE_COOLDOWN_MS = 10_000L
  private const val MAX_FAILURE_CACHE_SIZE = 256
  private const val DECODE_TIMEOUT_MS = 3_000L
  private const val NETWORK_DECODE_TIMEOUT_MS = 6_000L

  private val isInitialized = AtomicBoolean(false)
  @Volatile private var storedAppContext: Context? = null

  private val nonDecodableSchemes =
    setOf(
      "fd",
      "fdclose",
      "edl",
      "bd",
      "dvd",
      "null",
      "memory",
      "hex",
      "lavf",
      "slice",
      "concat",
      "archive",
      "mf",
      "dvb",
    )

  // In-Memory LRU Cache sized according to available heap memory (1/8th of RAM, 16MB to 64MB)
  private val memoryCache: LruCache<String, Bitmap> by lazy {
    val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    val cacheSizeKb = (maxMemoryKb / 8).coerceIn(16 * 1024, 64 * 1024)
    object : LruCache<String, Bitmap>(cacheSizeKb) {
      override fun sizeOf(
        key: String,
        value: Bitmap,
      ): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
  }

  private val failureTimestamps = ConcurrentHashMap<String, Long>()

  /**
   * Initializes the native FastThumbnails subsystem.
   */
  fun initialize(context: Context) {
    storedAppContext = context.applicationContext
    if (isInitialized.compareAndSet(false, true)) {
      try {
        if (!FastThumbnails.isInitialized()) {
          FastThumbnails.initialize(context.applicationContext)
        }
        Log.i(TAG, "ThumbFastEngine initialized successfully")
      } catch (error: Exception) {
        isInitialized.set(false)
        Log.e(TAG, "Failed to initialize native FastThumbnails in ThumbFastEngine", error)
      }
    }
  }

  /**
   * Checks whether the given URI or path is decodable directly by the thumbnail engine.
   */
  fun isSourceDecodable(source: String): Boolean {
    if (source.isBlank()) return false
    val scheme = source.substringBefore("://", missingDelimiterValue = "").lowercase()
    return scheme !in nonDecodableSchemes
  }

  /**
   * Checks whether the given source represents a remote network stream.
   */
  fun isNetworkSource(source: String): Boolean =
    source.startsWith("http://", ignoreCase = true) ||
      source.startsWith("https://", ignoreCase = true) ||
      source.startsWith("rtmp://", ignoreCase = true) ||
      source.startsWith("rtmps://", ignoreCase = true) ||
      source.startsWith("rtsp://", ignoreCase = true) ||
      source.startsWith("mms://", ignoreCase = true)

  fun getBucket(positionSeconds: Double): Int =
    (positionSeconds * BUCKETS_PER_SECOND).roundToInt().coerceAtLeast(0)

  fun getBucketTime(
    bucket: Int,
    durationSeconds: Double = 0.0,
  ): Double {
    val raw = (bucket / BUCKETS_PER_SECOND).coerceAtLeast(0.0)
    return if (durationSeconds > 0.0) {
      raw.coerceAtMost((durationSeconds - 0.1).coerceAtLeast(0.0))
    } else {
      raw
    }
  }

  fun getCacheKey(
    mediaIdentifier: String,
    bucket: Int,
    dimension: Int = DEFAULT_THUMBNAIL_DIMENSION,
  ): String = "$mediaIdentifier|$bucket|$dimension"

  /**
   * Retrieves a cached thumbnail bitmap for the specified timestamp bucket if available.
   */
  fun getCached(
    mediaPath: String,
    positionSeconds: Double,
    dimension: Int = DEFAULT_THUMBNAIL_DIMENSION,
  ): Bitmap? {
    val bucket = getBucket(positionSeconds)
    val key = getCacheKey(mediaPath, bucket, dimension)
    return memoryCache.get(key)?.takeUnless { it.isRecycled }
  }

  /**
   * Finds the nearest cached thumbnail within a bucket radius to minimize blank preview flashes.
   */
  fun getNearestCached(
    mediaPath: String,
    positionSeconds: Double,
    dimension: Int = DEFAULT_THUMBNAIL_DIMENSION,
    radiusBuckets: Int = 4,
  ): Bitmap? {
    val centerBucket = getBucket(positionSeconds)
    getCached(mediaPath, positionSeconds, dimension)?.let { return it }

    for (distance in 1..radiusBuckets) {
      val prevKey = getCacheKey(mediaPath, (centerBucket - distance).coerceAtLeast(0), dimension)
      memoryCache.get(prevKey)?.takeUnless { it.isRecycled }?.let { return it }

      val nextKey = getCacheKey(mediaPath, centerBucket + distance, dimension)
      memoryCache.get(nextKey)?.takeUnless { it.isRecycled }?.let { return it }
    }
    return null
  }

  /**
   * Extracts or retrieves a thumbnail for the specified media source and timestamp.
   */
  suspend fun getThumbnail(
    mediaPath: String,
    positionSeconds: Double,
    durationSeconds: Double = 0.0,
    context: Context? = null,
    dimension: Int = DEFAULT_THUMBNAIL_DIMENSION,
    isNetwork: Boolean = isNetworkSource(mediaPath),
  ): Bitmap? {
    if (!isSourceDecodable(mediaPath)) return null

    val bucket = getBucket(positionSeconds)
    val cacheKey = getCacheKey(mediaPath, bucket, dimension)

    // Check Memory Cache
    memoryCache.get(cacheKey)?.takeUnless { it.isRecycled }?.let { return it }

    // Check Failure Cooldown
    val lastFailed = failureTimestamps[cacheKey]
    if (lastFailed != null && SystemClock.elapsedRealtime() - lastFailed < FAILURE_COOLDOWN_MS) {
      return null
    }

    val targetTime = getBucketTime(bucket, durationSeconds)
    val timeoutMs = if (isNetwork) NETWORK_DECODE_TIMEOUT_MS else DECODE_TIMEOUT_MS

    val decoded =
      withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) {
          try {
            ensureNativeReady(context)
            decodeFrame(mediaPath, targetTime, dimension, context)
          } catch (cancellation: CancellationException) {
            throw cancellation
          } catch (e: Exception) {
            Log.w(TAG, "Thumbnail decode exception for $mediaPath at $targetTime: ${e.message}")
            null
          }
        }
      }

    if (decoded != null && !decoded.isRecycled) {
      memoryCache.put(cacheKey, decoded)
      failureTimestamps.remove(cacheKey)
    } else {
      if (failureTimestamps.size >= MAX_FAILURE_CACHE_SIZE) {
        failureTimestamps.clear()
      }
      failureTimestamps[cacheKey] = SystemClock.elapsedRealtime()
    }
    return decoded
  }

  private fun ensureNativeReady(context: Context?) {
    if (!FastThumbnails.isInitialized()) {
      val ctx = context?.applicationContext ?: storedAppContext
      if (ctx != null) {
        try {
          FastThumbnails.initialize(ctx)
          isInitialized.set(true)
        } catch (e: Exception) {
          Log.e(TAG, "Error initializing FastThumbnails during decode", e)
        }
      }
    }
  }

  private suspend fun decodeFrame(
    mediaPath: String,
    timeSeconds: Double,
    dimension: Int,
    context: Context?,
  ): Bitmap? {
    if (mediaPath.startsWith("content://", ignoreCase = true)) {
      val ctx = context?.applicationContext ?: storedAppContext ?: return null
      val uri = mediaPath.toUri()
      val pfd = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
      return pfd.use {
        FastThumbnails.generateAsync(
          "/proc/self/fd/${it.fd}",
          timeSeconds,
          dimension,
          useHwDec = false,
        )
      }
    }

    return FastThumbnails.generateAsync(
      mediaPath,
      timeSeconds,
      dimension,
      useHwDec = false,
    )
  }

  /**
   * Clears the in-memory thumbnail cache.
   */
  fun clearMemoryCache() {
    memoryCache.evictAll()
  }

  /**
   * Clears all failed decode cooldown records.
   */
  fun clearFailures() {
    failureTimestamps.clear()
  }
}
