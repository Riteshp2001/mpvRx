/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.data.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.gyrolet.mpvrx.network.awaitResponse
import app.gyrolet.mpvrx.utils.logging.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The highest-resolution cover the catalogues publish for a song, whatever the file embeds:
 * Apple Music's 3000px original first, then TIDAL's 1280px cover. The original is downloaded
 * once into a disk cache and reused on every later play; misses are remembered too, so a song
 * no catalogue knows is not searched again on each play.
 */
internal class HighQualityArtworkRepository(
  context: Context,
  client: OkHttpClient,
  private val tidal: TidalArtworkRepository,
) {
  private val directory = File(context.applicationContext.cacheDir, CACHE_DIRECTORY)
  private val http = client.newBuilder()
    .cookieJar(CookieJar.NO_COOKIES)
    .callTimeout(60, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()
  private val json = Json { ignoreUnknownKeys = true }
  private val mutex = Mutex()
  private val failedAt = ConcurrentHashMap<String, Long>()
  private val memory = object : LruCache<String, Bitmap>(memoryBudgetBytes()) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
  }

  fun fromMemory(query: MotionArtworkQuery): Bitmap? = memory.get(key(query))

  /** The cover for [query], decoded to at most [maxDimension] px, or null when none is published. */
  suspend fun load(query: MotionArtworkQuery, maxDimension: Int): Bitmap? = withContext(Dispatchers.IO) {
    val key = key(query)
    memory.get(key)?.let { return@withContext it }
    val file = cachedFile(key) ?: mutex.withLock { cachedFile(key) ?: download(query, key) } ?: return@withContext null
    decode(file, maxDimension)?.also { memory.put(key, it) }
  }

  private fun cachedFile(key: String): File? =
    File(directory, "$key.jpg").takeIf { it.isFile && it.length() > 0L }?.also {
      // Recency for the size trim below.
      it.setLastModified(System.currentTimeMillis())
    }

  private suspend fun download(query: MotionArtworkQuery, key: String): File? {
    val now = System.currentTimeMillis()
    val miss = File(directory, "$key.miss")
    if (miss.isFile && now - miss.lastModified() < MISS_TTL_MS) return null
    failedAt[key]?.let { if (now - it < FAILURE_RETRY_MS) return null }
    return try {
      val sources = listOf<suspend () -> String?>(
        { appleArtworkUrl(query) },
        { tidal.findStillCover(query) },
      )
      for (source in sources) {
        val url = source() ?: continue
        save(url, key)?.let { file ->
          failedAt.remove(key)
          return file
        }
      }
      directory.mkdirs()
      miss.writeBytes(ByteArray(0))
      null
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      Log.w(TAG, "High-quality artwork lookup failed; keeping the current cover: ${e.message}")
      failedAt[key] = now
      null
    } catch (e: SerializationException) {
      Log.w(TAG, "Unexpected artwork catalogue response", e)
      failedAt[key] = now
      null
    }
  }

  /** Apple Music's original cover via the public iTunes Search API, asked at its 3000px ceiling. */
  private suspend fun appleArtworkUrl(query: MotionArtworkQuery): String? {
    val title = MotionArtworkMatching.cleanTitle(query.title)
    val terms = listOfNotNull(
      query.album?.takeIf { it.isNotBlank() }?.let { "${query.artist} $title $it" },
      "${query.artist} $title",
    )
    val countries = listOf(MotionArtworkMatching.storefront(), "us").distinct()
    for (country in countries) {
      for (term in terms) {
        val url = ITUNES_SEARCH.toHttpUrl().newBuilder()
          .addQueryParameter("term", term)
          .addQueryParameter("entity", "song")
          .addQueryParameter("limit", "15")
          .addQueryParameter("country", country)
          .build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val body = http.newCall(request).awaitResponse().use { response ->
          currentCoroutineContext().ensureActive()
          when {
            response.isSuccessful -> response.body.string()
            // Not every device region is an iTunes storefront.
            response.code == 400 || response.code == 404 -> null
            else -> throw IOException("iTunes Search HTTP ${response.code}")
          }
        } ?: break
        val results = (json.parseToJsonElement(body) as? JsonObject)?.get("results") as? JsonArray ?: continue
        bestAppleMatch(query, results)?.let { return it }
      }
    }
    return null
  }

  private fun bestAppleMatch(query: MotionArtworkQuery, results: JsonArray): String? {
    val albumTagged = !query.album.isNullOrBlank()
    return results.firstNotNullOfOrNull { element ->
      val track = element as? JsonObject ?: return@firstNotNullOfOrNull null
      val title = track.text("trackName") ?: return@firstNotNullOfOrNull null
      val artist = track.text("artistName") ?: return@firstNotNullOfOrNull null
      if (!MotionArtworkMatching.titleMatches(query, title)) return@firstNotNullOfOrNull null
      if (!MotionArtworkMatching.artistsMatch(query, artist, title)) return@firstNotNullOfOrNull null
      val albumMatched = MotionArtworkMatching.albumMatches(query, track.text("collectionName"))
      // The cover replaces the file's own, so a tagged album must be that release: the same
      // song's single, live or greatest-hits sleeve would be the wrong art, cached for good.
      if (albumTagged && !albumMatched) return@firstNotNullOfOrNull null
      // Untagged, a various-artists compilation's sleeve is never the song's own cover.
      val compilation = track.text("collectionArtistName").equals("Various Artists", ignoreCase = true)
      if (compilation && !albumMatched) return@firstNotNullOfOrNull null
      track.text("artworkUrl100")?.let { largestAppleArtwork(it) }
    }
  }

  private suspend fun save(url: String, key: String): File? {
    directory.mkdirs()
    val target = File(directory, "$key.jpg")
    val temp = File(directory, "$key.part")
    val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
    try {
      http.newCall(request).awaitResponse().use { response ->
        if (!response.isSuccessful) return null
        val length = response.body.contentLength()
        if (length > MAX_DOWNLOAD_BYTES) return null
        response.body.byteStream().use { input ->
          FileOutputStream(temp).use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
              val read = input.read(buffer)
              if (read < 0) break
              total += read
              if (total > MAX_DOWNLOAD_BYTES) return null
              currentCoroutineContext().ensureActive()
              output.write(buffer, 0, read)
            }
          }
        }
      }
      if (!isImage(temp) || !temp.renameTo(target)) return null
      trim()
      return target
    } finally {
      temp.delete()
    }
  }

  private fun isImage(file: File): Boolean {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    return bounds.outWidth > 0 && bounds.outHeight > 0
  }

  private fun decode(file: File, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) {
      file.delete()
      return null
    }
    var sample = 1
    while (longest / (sample * 2) >= maxDimension) sample *= 2
    val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
      ?: return null
    val decodedLongest = maxOf(decoded.width, decoded.height)
    if (decodedLongest <= maxDimension) return decoded
    val scale = maxDimension.toFloat() / decodedLongest
    val scaled = Bitmap.createScaledBitmap(
      decoded,
      (decoded.width * scale).toInt().coerceAtLeast(1),
      (decoded.height * scale).toInt().coerceAtLeast(1),
      true,
    )
    if (scaled !== decoded) decoded.recycle()
    return scaled
  }

  /** Keeps the cache under [MAX_CACHE_BYTES], dropping the least recently played covers first. */
  private fun trim() {
    val files = directory.listFiles { file -> file.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() }
      ?: return
    var total = 0L
    for (file in files) {
      total += file.length()
      if (total > MAX_CACHE_BYTES) file.delete()
    }
  }

  private fun key(query: MotionArtworkQuery): String {
    val identity = listOf(
      MotionArtworkMatching.normalize(query.artist),
      MotionArtworkMatching.normalize(MotionArtworkMatching.cleanTitle(query.title)),
      query.album?.let(MotionArtworkMatching::normalize).orEmpty(),
    ).joinToString("|")
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }.take(40)
  }

  private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

  companion object {
    private const val TAG = "HighQualityArtwork"
    private const val CACHE_DIRECTORY = "hq_artwork"
    private const val ITUNES_SEARCH = "https://itunes.apple.com/search"
    private const val USER_AGENT = "Mozilla/5.0 (Android) mpvRx"
    private const val MAX_DOWNLOAD_BYTES = 25L * 1024 * 1024
    private const val MAX_CACHE_BYTES = 400L * 1024 * 1024
    private const val MISS_TTL_MS = 3L * 24 * 60 * 60 * 1000
    private const val FAILURE_RETRY_MS = 60_000L
    private val APPLE_SIZE_SEGMENT = Regex("""/\d+x\d+[a-z]*\.(?:jpg|jpeg|png|webp)$""", RegexOption.IGNORE_CASE)

    /** Apple's artwork URLs end in a size segment; asking for 3000px returns the original up to that size. */
    fun largestAppleArtwork(url: String): String? =
      url.takeIf { APPLE_SIZE_SEGMENT.containsMatchIn(it) }?.replace(APPLE_SIZE_SEGMENT, "/3000x3000bb.jpg")

    private fun memoryBudgetBytes(): Int =
      (Runtime.getRuntime().maxMemory() / 10).coerceAtMost(64L * 1024 * 1024).toInt()
  }
}
