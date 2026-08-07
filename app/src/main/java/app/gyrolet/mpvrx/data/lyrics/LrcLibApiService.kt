/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.data.lyrics

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class LrcLibResponse(
  val id: Int = 0,
  val name: String = "",
  val artistName: String = "",
  val albumName: String = "",
  val duration: Double = 0.0,
  @SerialName("plainLyrics") val plainLyrics: String? = null,
  @SerialName("syncedLyrics") val syncedLyrics: String? = null,
)

class LrcLibApiService(
  private val okHttpClient: OkHttpClient,
) {
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
        .header("User-Agent", "mpvRx Music Player/1.0")
        .get()
        .build()

      okHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@withContext null
        val bodyString = response.body.string()
        json.decodeFromString<LrcLibResponse>(bodyString)
      }
    } catch (e: Exception) {
      Log.w("LrcLibApiService", "Error fetching lyrics from get endpoint: ${e.message}")
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
        .header("User-Agent", "mpvRx Music Player/1.0")
        .get()
        .build()

      okHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@withContext emptyList()
        val bodyString = response.body.string()
        json.decodeFromString<List<LrcLibResponse>>(bodyString)
      }
    } catch (e: Exception) {
      Log.w("LrcLibApiService", "Error searching lyrics from search endpoint: ${e.message}")
      emptyList()
    }
  }
}
