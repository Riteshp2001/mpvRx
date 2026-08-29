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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * High-performance, thread-safe thumbnail engine for mpvRx.
 *
 * Provides asynchronous frame extraction with time-bucketed in-memory LRU caching,
 * debounced in-flight decode management, failure cooldowns, and full support for both
 * local content and remote network streams (HTTP/HTTPS, HLS/DASH, WebDAV, SMB, yt-dlp).
 */
object ThumbFastEngine {
  private const val TAG = "ThumbFastEngine"

  const val DEFAULT_THUMBNAIL_DIMENSION = 320
  const val BUCKET_STEP_SECONDS = 0.5
  private const val FAILURE_COOLDOWN_MS = 10_000L
  private const val MAX_FAILURE_CACHE_SIZE = 256
  private const val MAX_IN_FLIGHT_DECODES = 4
  private const val DECODE_TIMEOUT_MS = 4_000L

  private val isInitialized = AtomicBoolean(false)
  @Volatile private var appContext: Context? = null

  private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val engineDispatcher = Dispatchers.IO.limitedParallelism(2)

  private val nonDecodableSchemes =
    setOf(
      "fd",
      "edl",
      "bd",
      "dvd",
      "null",
      "memory",
      "hex",
      "lavf",
      "slice",
      "concat",
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

  private val inFlightDecodes = ConcurrentHashMap<String, Deferred<Bitmap?>>()
  private val failureTimestamps = ConcurrentHashMap<String, Long>()

  /**
   * Initializes the native FastThumbnails subsystem.
   */
  fun initialize(context: Context) {
    appContext = context.applicationContext
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
    (positionSeconds / BUCKET_STEP_SECONDS).roundToInt().coerceAtLeast(0)

  fun getBucketTime(bucket: Int): Double = bucket * BUCKET_STEP_SECONDS

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
    radiusBuckets: Int = 6,
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

    // Coalesce duplicate requests with in-flight Deferred
    inFlightDecodes[cacheKey]?.let { ongoing ->
      return try {
        ongoing.await()
      } catch (_: Exception) {
        null
      }
    }

    if (inFlightDecodes.size >= MAX_IN_FLIGHT_DECODES) {
      return null
    }

    val deferred =
      engineScope.async(engineDispatcher) {
        val targetTime = getBucketTime(bucket)
        var decoded: Bitmap? = null
        try {
          ensureNativeReady()
          decoded = decodeFrame(mediaPath, targetTime, dimension)
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (e: Exception) {
          Log.w(TAG, "Thumbnail decode failed for $mediaPath at $targetTime: ${e.message}")
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
        decoded
      }

    inFlightDecodes[cacheKey] = deferred
    deferred.invokeOnCompletion { inFlightDecodes.remove(cacheKey, deferred) }

    return try {
      withTimeoutOrNull(if (isNetwork) DECODE_TIMEOUT_MS * 2 else DECODE_TIMEOUT_MS) {
        deferred.await()
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      null
    }
  }

  private fun ensureNativeReady() {
    if (!FastThumbnails.isInitialized()) {
      appContext?.let { ctx ->
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
  ): Bitmap? {
    if (mediaPath.startsWith("content://", ignoreCase = true)) {
      val uri = mediaPath.toUri()
      val resolver = appContext?.contentResolver ?: return null
      val pfd = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
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
