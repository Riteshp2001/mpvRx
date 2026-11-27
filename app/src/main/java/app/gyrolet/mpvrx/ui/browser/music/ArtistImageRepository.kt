/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.music

import android.os.SystemClock
import android.util.LruCache
import app.gyrolet.mpvrx.network.awaitResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object ArtistImageRepository {
  private const val FAILURE_RETRY_MS = 60_000L
  private val cache = LruCache<String, String>(300)
  private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val inFlight = ConcurrentHashMap<String, Deferred<String?>>()
  private val failedAt = ConcurrentHashMap<String, Long>()

  suspend fun getArtistImageUrl(
    client: OkHttpClient,
    artistName: String,
  ): String? {
    if (artistName.isBlank() || artistName.equals("Unknown Artist", ignoreCase = true)) return null
    val key = artistName.trim().lowercase()

    synchronized(cache) { cache.get(key) }?.let { return it }
    val failedAtMs = failedAt[key]
    if (failedAtMs != null) {
      if (SystemClock.elapsedRealtime() - failedAtMs < FAILURE_RETRY_MS) return null
      failedAt.remove(key, failedAtMs)
    }

    val candidate =
      loaderScope.async(start = CoroutineStart.LAZY) {
        fetchArtistImageUrl(client, artistName, key)
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

  private suspend fun fetchArtistImageUrl(
    client: OkHttpClient,
    artistName: String,
    key: String,
  ): String? =
    try {
      val encoded = URLEncoder.encode(artistName.trim(), "UTF-8")
      val url = "https://api.deezer.com/search/artist?q=$encoded&limit=1"
      val request = Request.Builder().url(url).build()

      client.newCall(request).awaitResponse().use { response ->
        if (!response.isSuccessful) {
          failedAt[key] = SystemClock.elapsedRealtime()
          return null
        }
        val data = JSONObject(response.body.string()).optJSONArray("data")
        val imageUrl =
          data
            ?.takeIf { it.length() > 0 }
            ?.getJSONObject(0)
            ?.let { item ->
              item
                .optString("picture_medium", "")
                .ifBlank { item.optString("picture_big", "") }
                .ifBlank { item.optString("picture_xl", "") }
            }.orEmpty()
        if (imageUrl.isBlank()) {
          failedAt[key] = SystemClock.elapsedRealtime()
          null
        } else {
          synchronized(cache) { cache.put(key, imageUrl) }
          failedAt.remove(key)
          imageUrl
        }
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      failedAt[key] = SystemClock.elapsedRealtime()
      null
    }
}
