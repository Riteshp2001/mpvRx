/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.gyrolet.mpvrx.repository.NetworkRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000

/**
 * Upper bound on the *pixels* a full-size decode may allocate.
 *
 * A long-edge cap is not enough: 8192x8192 is 268 MB at ARGB_8888, and the power-of-two sample
 * size below cannot shrink exactly that edge. Bounding the pixel count puts a hard ceiling of
 * [DEFAULT_MAX_PIXELS] x 4 bytes (about 84 MB) on any single decode.
 */
private const val DEFAULT_MAX_PIXELS = 22_000_000

/** Bytes per pixel for the ARGB_8888 config [BitmapFactory] decodes into by default. */
private const val BYTES_PER_PIXEL = 4

/**
 * Size the thumbnail is stored at on disk, independent of what any caller asked for.
 *
 * A single disk file cannot serve every requested size, so it must be generated large enough that
 * the biggest caller (grid cards, the viewer's full-screen placeholder) can scale down from it.
 * Note this is a *cover* bound: both edges of the stored bitmap end up at or above this value
 * (unless the source is smaller), which is what makes every later request a downscale rather than
 * a blurry upscale. Storing the first-requested size instead would silently upscale every later,
 * larger request, because a down-sampled bitmap cannot regain resolution.
 */
private const val THUMBNAIL_STORE_MIN_EDGE = 512

/**
 * Caps simultaneous image transfers so a large grid cannot open one session per card.
 *
 * Deliberately *not* solved by reusing the browser's active client: `NetworkRepository.listFiles`
 * swaps and closes that client under its private `clientLifecycleMutex` (NetworkRepository.kt:264),
 * so borrowing it without that lock would let a pull-to-refresh tear down a stream mid-download, and
 * taking the lock would serialize image loading against folder listing.
 */
private const val MAX_CONCURRENT_TRANSFERS = 3

private const val DEFAULT_MAX_CACHE_BYTES = 500L * 1024 * 1024

class NetworkImageRepository(
  private val context: Context,
  private val networkRepository: NetworkRepository,
) {

  /** Full-size images cached under [context.cacheDir]/network_images/ */
  private val fullImageDir = File(context.cacheDir, "network_images")

  /** Thumbnails cached under [context.filesDir]/thumbnails/network/ (shared with video thumbnails) */
  private val thumbnailDir = File(context.filesDir, "thumbnails/network").apply { mkdirs() }

  /** In-memory cache for thumbnails shown in browser grids/lists. */
  private val memoryCache: LruCache<String, Bitmap>

  /** Viewer memory cache (lifecycle-bound, cleared on viewer exit). */
  private val viewerMemoryCache: LruCache<String, Bitmap>

  /**
   * In-flight downloads, keyed by cache key. A second caller awaits the same [Deferred] instead of
   * starting its own transfer, which also means one failure is shared rather than retried once per
   * waiter.
   */
  private val ongoingDownloads = ConcurrentHashMap<String, Deferred<File?>>()

  /** Live waiters per in-flight download; see [awaitDownload]. */
  private val downloadWaiters = ConcurrentHashMap<String, Int>()

  private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /**
   * Cache keys whose bytes [BitmapFactory] cannot read — a format this API level has no decoder for
   * (HEIC below 28, AVIF below 31) or a truncated file.
   *
   * Keyed by cache key, which includes the remote mtime, so replacing the file on the server yields
   * a fresh key and a fresh attempt. Without this every appearance of such a file downloads the whole
   * original again only to fail again.
   */
  private val undecodableKeys = ConcurrentHashMap.newKeySet<String>()

  private val transferSemaphore = Semaphore(MAX_CONCURRENT_TRANSFERS)

  init {
    val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
    memoryCache =
      object : LruCache<String, Bitmap>(maxMemoryKb / 32) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
      }
    viewerMemoryCache =
      object : LruCache<String, Bitmap>(maxMemoryKb / 16) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
      }
  }

  /**
   * Returns a thumbnail [Bitmap] for the given network image file.
   *
   * The result is sourced from (in order): memory cache, disk cache, or downloaded
   * and down-sampled from the remote file.
   */
  suspend fun getThumbnail(
    connectionId: Long,
    file: NetworkFile,
    widthPx: Int,
    heightPx: Int,
    /** Re-attempt even if these exact bytes already failed to decode; set by a manual retry. */
    force: Boolean = false,
  ): Bitmap? = withContext(Dispatchers.IO) {
    val key = cacheKey(connectionId, file)
    val memKey = "$key|thumb|$widthPx|$heightPx"

    memoryCache.get(memKey)?.let { return@withContext it }
    if (!force && key in undecodableKeys) return@withContext null

    val thumbFile = File(thumbnailDir, "$key.thumb")
    if (thumbFile.exists() && thumbFile.length() > 0) {
      scaleToRequest(thumbFile, widthPx, heightPx)?.let {
        memoryCache.put(memKey, it)
        return@withContext it
      }
    }

    val fullFile = getFullImageFile(connectionId, file) ?: return@withContext null

    // Generate at a size that serves every caller, then hand this caller its exact size.
    val stored = decodeScaledBitmap(fullFile, THUMBNAIL_STORE_MIN_EDGE, THUMBNAIL_STORE_MIN_EDGE)
    if (stored == null) {
      // A null here is either an unreadable format or a transient allocation failure; only the
      // former is worth remembering, and the header check tells them apart.
      if (!isDecodable(fullFile)) undecodableKeys.add(key)
      // Drop the stored original so the next request re-downloads instead of failing on the same
      // bytes forever.
      runCatching { fullFile.delete() }
      return@withContext null
    }
    runCatching { thumbFile.writeBitmap(stored) }
    undecodableKeys.remove(key)

    val requested = stored.scaleTo(widthPx, heightPx)
    memoryCache.put(memKey, requested)
    requested
  }

  /**
   * Reads the stored (already down-sampled) thumbnail and scales it to exactly [widthPx] x
   * [heightPx] — a cover scale, so the result always fills the box it is drawn into.
   */
  private fun scaleToRequest(file: File, widthPx: Int, heightPx: Int): Bitmap? =
    decodeScaledBitmap(file, widthPx, heightPx)?.scaleTo(widthPx, heightPx)

  private fun Bitmap.scaleTo(widthPx: Int, heightPx: Int): Bitmap {
    val scale = maxOf(widthPx.toFloat() / width, heightPx.toFloat() / height)
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    if (targetWidth == width && targetHeight == height) return this
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
  }

  /**
   * Returns a full-size [Bitmap] for viewer display.
   *
   * Sourced from (in order): viewer memory cache, disk cache decoded under a
   * [maxPixels] allocation budget, or downloaded and decoded.
   */
  suspend fun getFullImage(
    connectionId: Long,
    file: NetworkFile,
    maxPixels: Int = DEFAULT_MAX_PIXELS,
    /** Re-attempt even if these exact bytes already failed to decode; set by a manual retry. */
    force: Boolean = false,
  ): Bitmap? = withContext(Dispatchers.IO) {
    val key = cacheKey(connectionId, file)

    // 1. Check viewer memory cache
    viewerMemoryCache.get(key)?.let {
      return@withContext it
    }
    if (!force && key in undecodableKeys) return@withContext null

    // 2. Check full image disk cache
    val cached = getFullImageFile(connectionId, file) ?: return@withContext null

    val decoded = decodeScaledBitmap(cached, maxPixels)
    if (decoded == null) {
      if (!isDecodable(cached)) undecodableKeys.add(key)
      // The stored file cannot be decoded — drop it so the next request re-downloads instead of
      // serving the same broken bytes forever.
      runCatching { cached.delete() }
      return@withContext null
    }
    viewerMemoryCache.put(key, decoded)
    undecodableKeys.remove(key)
    decoded
  }

  /** Clear viewer memory cache (call when viewer exits). */
  fun clearViewerMemoryCache() {
    viewerMemoryCache.evictAll()
  }

  /**
   * Evicts expired entries from both disk caches (time on both, size on the originals only).
   *
   * Thumbnails are otherwise left to grow and cleared manually, as the requirements ask. But their
   * file name is derived from the remote mtime, so editing a file on the server strands its old
   * thumbnail forever — the age sweep is the only thing that reclaims those.
   */
  /**
   * Age/size sweep for re-downloadable image caches.
   *
   * Only [fullImageDir] is swept. That directory holds the raw downloaded originals under
   * `cacheDir`, so the platform can reclaim it whenever it wants and a stale copy costs nothing
   * but a re-download.
   *
   * [thumbnailDir] is deliberately excluded: it lives under `filesDir` and holds the thumbnails the
   * user has already generated, which are kept for the lifetime of the install and removed only by
   * an explicit cache clear, the same as the local video thumbnails. Ageing them out would silently
   * regenerate work the user already paid for, which is exactly what we do not want.
   */
  fun evictStaleImageCaches(
    maxAgeDays: Int = 30,
    maxSizeBytes: Long = DEFAULT_MAX_CACHE_BYTES,
  ) {
    val now = System.currentTimeMillis()
    val maxAgeMs = maxAgeDays * 24L * 60 * 60 * 1000

    deleteFilesOlderThan(fullImageDir, now, maxAgeMs)

    val remaining = fullImageDir.listFiles()?.filter { it.isFile } ?: return
    var totalSize = remaining.sumOf { it.length() }
    if (totalSize > maxSizeBytes) {
      remaining.sortedBy { it.lastModified() }.forEach { file ->
        if (totalSize <= maxSizeBytes) return@forEach
        totalSize -= file.length()
        file.delete()
      }
    }
  }

  private fun deleteFilesOlderThan(dir: File, now: Long, maxAgeMs: Long) {
    dir
      .listFiles()
      ?.filter { it.isFile && now - it.lastModified() > maxAgeMs }
      ?.forEach { it.delete() }
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  /** Returns a local [File] containing the full remote image (disk cache only). */
  private suspend fun getFullImageFile(
    connectionId: Long,
    file: NetworkFile,
  ): File? = withContext(Dispatchers.IO) {
    val key = cacheKey(connectionId, file)
    val cached = File(fullImageDir, key)

    if (cached.exists() && !cached.isExpired()) {
      return@withContext cached.asCacheHit()
    }

    ongoingDownloads[key]?.let { return@withContext awaitDownload(key, it) }

    // Running in [repositoryScope] rather than the caller's scope: the first caller is often a grid
    // card that leaves composition mid-download, and a transfer cancelled with it would take its
    // waiters' `await()` down too — stranding the viewer on a spinner that never resolves.
    val candidate =
      repositoryScope.async(start = CoroutineStart.LAZY) {
        if (cached.exists() && !cached.isExpired()) {
          cached.asCacheHit()
        } else {
          downloadImage(connectionId, file.path, cached)
        }
      }

    val operation =
      ongoingDownloads.putIfAbsent(key, candidate)?.also {
        candidate.cancel()
      } ?: candidate.also { owned ->
        owned.invokeOnCompletion { ongoingDownloads.remove(key, owned) }
        owned.start()
      }

    awaitDownload(key, operation)
  }

  /**
   * Awaits a shared transfer, dropping it once nobody is left waiting.
   *
   * The transfer runs in [repositoryScope] so one caller leaving cannot strand the others; the flip
   * side is that it would then go on downloading an image no one is left to render. Counting waiters
   * is what lets a cancelled navigation stop an in-flight transfer — an accidental tap into a folder
   * stops costing bandwidth as soon as it is backed out of — without breaking the callers still on it.
   */
  private suspend fun awaitDownload(key: String, operation: Deferred<File?>): File? {
    downloadWaiters.merge(key, 1, Int::plus)
    try {
      return operation.await()
    } catch (cancellation: CancellationException) {
      // Either this caller was cancelled, or it arrived just as the transfer was dropped with no
      // waiters left. The first has to propagate; the second is an ordinary load failure the UI can
      // offer to retry — propagating it would strand the card on a spinner that never resolves.
      if (!currentCoroutineContext().isActive) throw cancellation
      return null
    } finally {
      val remaining =
        downloadWaiters.compute(key) { _, count -> count?.minus(1)?.takeIf { it > 0 } }
      if (remaining == null && ongoingDownloads.remove(key, operation)) operation.cancel()
    }
  }

  private suspend fun downloadImage(
    connectionId: Long,
    path: String,
    destination: File,
  ): File? =
    withContext(Dispatchers.IO) {
      transferSemaphore.withPermit { transferImage(connectionId, path, destination) }
    }

  private suspend fun transferImage(
    connectionId: Long,
    path: String,
    destination: File,
  ): File? {
    // Recreated per download because the cache directory is wiped by the settings screen's
    // "clear cache" action while this repository (a Koin singleton) stays alive.
    destination.parentFile?.mkdirs()
    var tempFile: File? = null
    return try {
      // Created inside the try: a full disk or a missing directory throws here, and that is a
      // normal download failure rather than something the UI should have to catch.
      val staging = File.createTempFile("${destination.name}.", ".tmp", destination.parentFile)
      tempFile = staging
      val connection = networkRepository.getConnectionById(connectionId)
        ?: return null
      val client = networkRepository.createClient(connection.id).getOrThrow()

      // connect() is inside the try so a failed handshake still runs the disconnect below. The
      // disconnect itself must not be cancellable: this coroutine is cancelled every time a card
      // scrolls out of the grid, and a suspended disconnect in a cancelled scope never runs,
      // leaving the SMB/SFTP session open for the life of the process.
      try {
        client.connect().getOrThrow()
        client.getFileStream(path).getOrThrow().use { input ->
          staging.outputStream().use { output ->
            input.copyTo(output)
          }
        }
      } finally {
        withContext(NonCancellable) { runCatching { client.disconnect() } }
      }

      // Atomic commit: only rename after a successful download AND a bitmap header check. A server
      // that answers a bad path with 200 + an HTML error page produces a non-empty file that would
      // otherwise be cached and fail to decode on every subsequent request.
      val readable = staging.length() > 0 && isDecodable(staging)
      if (readable && staging.renameTo(destination)) {
        destination
      } else {
        // A complete but unreadable download is worth remembering so the next request does not fetch
        // it again; the key carries the remote mtime, so a replaced file retries.
        if (staging.length() > 0 && !readable) undecodableKeys.add(destination.name)
        null
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      null
    } finally {
      // Clean up partial download
      tempFile?.delete()
    }
  }

  /**
   * Marks a cache hit as read.
   *
   * The eviction policy reasons about the last *access*, and a file's mtime is all it has to go on,
   * so a hit has to refresh it. Without this, an image opened daily still looks thirty days idle and
   * gets deleted and re-downloaded by the startup sweep.
   */
  private fun File.asCacheHit(): File {
    setLastModified(System.currentTimeMillis())
    return this
  }

  /** True when [file] carries a bitmap header BitmapFactory can read. */
  private fun isDecodable(file: File): Boolean =
    try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.absolutePath, bounds)
      bounds.outWidth > 0 && bounds.outHeight > 0
    } catch (_: Exception) {
      false
    }

  private fun decodeScaledBitmap(file: File, reqWidth: Int, reqHeight: Int): Bitmap? =
    decodeScaledBitmap(file, reqWidth = reqWidth, reqHeight = reqHeight, maxPixels = null)

  private fun decodeScaledBitmap(file: File, maxPixels: Int): Bitmap? =
    decodeScaledBitmap(file, reqWidth = null, reqHeight = null, maxPixels = maxPixels)

  /**
   * Decodes [file], down-sampling until the result satisfies the requested bound.
   *
   * Exactly one of (reqWidth + reqHeight) or [maxPixels] is supplied. The width/height pair is a
   * *cover* bound — the result is guaranteed to be at least the requested size in both directions,
   * which is what `ContentScale.Crop` needs; a fit bound would hand the UI a bitmap it then has to
   * upscale. [maxPixels] is a plain allocation ceiling.
   *
   * `OutOfMemoryError` is caught on purpose: it is an [Error], so a `catch (e: Exception)` would
   * let it escape the coroutine and kill the process, and an undecodable file is a normal outcome
   * for this feature rather than a crash.
   */
  private fun decodeScaledBitmap(
    file: File,
    reqWidth: Int?,
    reqHeight: Int?,
    maxPixels: Int?,
  ): Bitmap? =
    try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.absolutePath, bounds)
      val sample =
        calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight, maxPixels)
      if (sample == null) {
        null
      } else {
        BitmapFactory.decodeFile(
          file.absolutePath,
          BitmapFactory.Options().apply { inSampleSize = sample },
        )
      }
    } catch (_: OutOfMemoryError) {
      null
    } catch (_: Exception) {
      null
    }

  /**
   * Returns the sample size to decode with, or null when the file carries no readable bitmap
   * header — in which case the caller should report failure rather than try.
   *
   * Width/height: the largest power of two that still covers the requested box, so the decoded
   * bitmap is never smaller than the box it will be cropped into. Pixel budget: the smallest power
   * of two that brings the pixel count under [maxPixels].
   */
  private fun calculateInSampleSize(
    rawWidth: Int,
    rawHeight: Int,
    reqWidth: Int?,
    reqHeight: Int?,
    maxPixels: Int?,
  ): Int? {
    if (rawWidth <= 0 || rawHeight <= 0) return null

    var sample = 1
    if (reqWidth != null && reqHeight != null && reqWidth > 0 && reqHeight > 0) {
      // Cover: stop as soon as halving again would drop an edge below the request.
      while (rawWidth / (sample * 2) >= reqWidth && rawHeight / (sample * 2) >= reqHeight) {
        sample *= 2
      }
    }
    if (maxPixels != null) {
      while (pixelsAt(rawWidth, rawHeight, sample) > maxPixels) {
        sample *= 2
      }
    }
    val pixels = pixelsAt(rawWidth, rawHeight, sample)
    return sample.takeIf { pixels > 0 && pixels * BYTES_PER_PIXEL <= Int.MAX_VALUE.toLong() }
  }

  private fun pixelsAt(rawWidth: Int, rawHeight: Int, sample: Int): Long =
    (rawWidth.toLong() / sample) * (rawHeight.toLong() / sample)

  /**
   * Cache identity for one remote image.
   *
   * [NetworkFile.path] is relative to its connection, so the connection id has to be part of the
   * key: two connections to the same server (a WebDAV and an SMB route, or two accounts) can expose
   * the same relative path with the same mtime, and without this they would share cache entries and
   * silently show each other's images. [ThumbnailRepository.networkThumbnailIdentity] keeps the full
   * endpoint for the same reason.
   */
  private fun cacheKey(connectionId: Long, file: NetworkFile): String {
    val raw = "$connectionId|${file.path}|${file.lastModified}"
    val digest = MessageDigest.getInstance("SHA-256").apply { update(raw.toByteArray()) }.digest()
    return digest.joinToString("") { "%02x".format(it) }
  }

  private fun File.isExpired(now: Long = System.currentTimeMillis()): Boolean {
    return !exists() || (now - lastModified() > THIRTY_DAYS_MS)
  }

  private fun File.writeBitmap(bitmap: Bitmap) {
    FileOutputStream(this).use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
    }
  }
}
