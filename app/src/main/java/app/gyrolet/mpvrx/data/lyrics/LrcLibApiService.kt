/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.data.lyrics

import android.util.Log
import app.gyrolet.mpvrx.data.lyrics.providers.LYRICS_AGENT
import app.gyrolet.mpvrx.data.lyrics.providers.recordLyricsFailure
import app.gyrolet.mpvrx.data.lyrics.providers.readLyricsResponse
import app.gyrolet.mpvrx.network.awaitResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class LrcLibResponse(
  val id: Int = 0,
  val name: String = "",
  val trackName: String = "",
  val artistName: String = "",
  val albumName: String = "",
  val duration: Double = 0.0,
  @SerialName("plainLyrics") val plainLyrics: String? = null,
  @SerialName("syncedLyrics") val syncedLyrics: String? = null,
)

class LrcLibApiService(
  okHttpClient: OkHttpClient,
) {
  private val client = okHttpClient.newBuilder()
    .callTimeout(6, TimeUnit.SECONDS)
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(6, TimeUnit.SECONDS)
    .build()
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  suspend fun getLyrics(
    trackName: String,
    artistName: String,
    albumName: String? = null,
    duration: Int? = null,
  ): LrcLibResponse? = withContext(Dispatchers.IO) {
    try {
      val urlBuilder = "https://lrclib.net/api/get".toHttpUrlOrNull()?.newBuilder()
        ?: return@withContext null

      urlBuilder.addQueryParameter("track_name", trackName)
      urlBuilder.addQueryParameter("artist_name", artistName)
      if (!albumName.isNullOrBlank()) {
        urlBuilder.addQueryParameter("album_name", albumName)
      }
      if (duration != null && duration > 0) {
        urlBuilder.addQueryParameter("duration", duration.toString())
      }

      val request = Request.Builder()
        .url(urlBuilder.build())
        .header("User-Agent", LYRICS_AGENT)
        .get()
        .build()

      client.newCall(request).awaitResponse().use { response ->
        val bodyString = readLyricsResponse(response) ?: return@withContext null
        json.decodeFromString<LrcLibResponse>(bodyString)
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      recordLyricsFailure()
      Log.w("LrcLibApiService", "get failed: ${e.javaClass.simpleName}")
      null
    }
  }

  suspend fun searchLyrics(
    query: String? = null,
    trackName: String? = null,
    artistName: String? = null,
    albumName: String? = null,
  ): List<LrcLibResponse> = withContext(Dispatchers.IO) {
    try {
      val urlBuilder = "https://lrclib.net/api/search".toHttpUrlOrNull()?.newBuilder()
        ?: return@withContext emptyList()

      if (!query.isNullOrBlank()) {
        urlBuilder.addQueryParameter("q", query)
      }
      if (!trackName.isNullOrBlank()) {
        urlBuilder.addQueryParameter("track_name", trackName)
      }
      if (!artistName.isNullOrBlank()) {
        urlBuilder.addQueryParameter("artist_name", artistName)
      }
      if (!albumName.isNullOrBlank()) {
        urlBuilder.addQueryParameter("album_name", albumName)
      }

      val request = Request.Builder()
        .url(urlBuilder.build())
        .header("User-Agent", LYRICS_AGENT)
        .get()
        .build()

      client.newCall(request).awaitResponse().use { response ->
        val bodyString = readLyricsResponse(response) ?: return@withContext emptyList()
        json.decodeFromString<List<LrcLibResponse>>(bodyString)
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      recordLyricsFailure()
      Log.w("LrcLibApiService", "search failed: ${e.javaClass.simpleName}")
      emptyList()
    }
  }
}
