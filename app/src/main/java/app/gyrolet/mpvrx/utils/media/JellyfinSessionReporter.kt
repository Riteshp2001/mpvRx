/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import android.net.Uri
import android.util.Log
import app.gyrolet.mpvrx.network.SharedHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class JellyfinSessionReporter(
  private val baseUrl: String,
  private val itemId: String,
  private val apiKey: String,
  private val playSessionId: String?,
  private val mediaSourceId: String?,
  private val coroutineScope: CoroutineScope,
  private val httpClient: OkHttpClient = defaultHttpClient,
) {
  companion object {
    private const val TAG = "JellyfinSessionReporter"

    // Ticks per millisecond in Jellyfin (1 tick = 100 nanoseconds = 10,000 ticks per millisecond)
    private const val TICKS_PER_MILLISECOND = 10000L
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val defaultHttpClient by lazy {
      SharedHttpClient.derive {
        connectTimeout(5, TimeUnit.SECONDS)
        readTimeout(5, TimeUnit.SECONDS)
      }
    }

    fun create(
      url: String,
      coroutineScope: CoroutineScope,
      httpClient: OkHttpClient? = null,
    ): JellyfinSessionReporter? {
      try {
        val uri = Uri.parse(url)
        val pathSegments = uri.pathSegments
        val mediaIndex = pathSegments.indexOfFirst {
          it.equals("Videos", ignoreCase = true) ||
            it.equals("Audio", ignoreCase = true) ||
            it.equals("Items", ignoreCase = true)
        }
        if (mediaIndex == -1 || mediaIndex + 1 >= pathSegments.size) {
          return null
        }
        val itemId = pathSegments[mediaIndex + 1]
        val apiKey = uri.getQueryParameter("api_key") ?: uri.getQueryParameter("ApiKey") ?: return null
        val playSessionId = uri.getQueryParameter("playSessionId") ?: uri.getQueryParameter("PlaySessionId")
        val mediaSourceId = uri.getQueryParameter("mediaSourceId") ?: uri.getQueryParameter("MediaSourceId")

        val scheme = uri.scheme ?: "http"
        val authority = uri.encodedAuthority ?: return null
        val subPathSegments = pathSegments.subList(0, mediaIndex)
        val baseUrl =
          if (subPathSegments.isEmpty()) {
            "$scheme://$authority"
          } else {
            "$scheme://$authority/" + subPathSegments.joinToString("/")
          }

        Log.d(
          TAG,
          "Created JellyfinSessionReporter: baseUrl=$baseUrl, itemId=$itemId, playSessionId=$playSessionId, mediaSourceId=$mediaSourceId",
        )
        return JellyfinSessionReporter(
          baseUrl = baseUrl,
          itemId = itemId,
          apiKey = apiKey,
          playSessionId = playSessionId,
          mediaSourceId = mediaSourceId,
          coroutineScope = coroutineScope,
          httpClient = httpClient ?: defaultHttpClient,
        )
      } catch (e: Exception) {
        Log.e(TAG, "Failed to parse Jellyfin URL: ${e.message}")
        return null
      }
    }
  }

  @Serializable
  private data class PlaybackStartInfo(
    val ItemId: String,
    val PlaySessionId: String? = null,
    val MediaSourceId: String? = null,
    val PositionTicks: Long? = null,
    val CanSeek: Boolean = true,
    val IsPaused: Boolean = false,
    val IsMuted: Boolean = false,
  )

  @Serializable
  private data class PlaybackProgressInfo(
    val ItemId: String,
    val PlaySessionId: String? = null,
    val MediaSourceId: String? = null,
    val PositionTicks: Long? = null,
    val CanSeek: Boolean = true,
    val IsPaused: Boolean = false,
    val IsMuted: Boolean = false,
  )

  @Serializable
  private data class PlaybackStopInfo(
    val ItemId: String,
    val PlaySessionId: String? = null,
    val MediaSourceId: String? = null,
    val PositionTicks: Long? = null,
  )

  fun reportPlaybackStart(positionMs: Long) {
    coroutineScope.launch(Dispatchers.IO) {
      val urlString = "$baseUrl/Sessions/Playing?api_key=$apiKey"
      val info =
        PlaybackStartInfo(
          ItemId = itemId,
          PlaySessionId = playSessionId,
          MediaSourceId = mediaSourceId,
          PositionTicks = positionMs * TICKS_PER_MILLISECOND,
        )
      val jsonBody = Json.encodeToString(info)
      sendPostRequest(urlString, jsonBody)
    }
  }

  fun reportPlaybackProgress(
    positionMs: Long,
    isPaused: Boolean,
  ) {
    coroutineScope.launch(Dispatchers.IO) {
      val urlString = "$baseUrl/Sessions/Playing/Progress?api_key=$apiKey"
      val info =
        PlaybackProgressInfo(
          ItemId = itemId,
          PlaySessionId = playSessionId,
          MediaSourceId = mediaSourceId,
          PositionTicks = positionMs * TICKS_PER_MILLISECOND,
          IsPaused = isPaused,
        )
      val jsonBody = Json.encodeToString(info)
      sendPostRequest(urlString, jsonBody)
    }
  }

  fun reportPlaybackStop(positionMs: Long) {
    coroutineScope.launch(Dispatchers.IO) {
      val urlString = "$baseUrl/Sessions/Playing/Stopped?api_key=$apiKey"
      val info =
        PlaybackStopInfo(
          ItemId = itemId,
          PlaySessionId = playSessionId,
          MediaSourceId = mediaSourceId,
          PositionTicks = positionMs * TICKS_PER_MILLISECOND,
        )
      val jsonBody = Json.encodeToString(info)
      sendPostRequest(urlString, jsonBody)
    }
  }

  private fun sendPostRequest(
    urlString: String,
    jsonBody: String,
  ) {
    try {
      val request =
        Request.Builder()
          .url(urlString)
          .header("Content-Type", "application/json")
          .header("X-Emby-Token", apiKey)
          .header("User-Agent", "mpvRx/1.0")
          .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
          .build()

      httpClient.newCall(request).execute().use { response ->
        if (response.isSuccessful) {
          Log.d(TAG, "Successfully reported status to Jellyfin: $urlString")
        } else {
          Log.e(TAG, "Failed to report status to Jellyfin: $urlString, response code: ${response.code}")
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error sending playback report to Jellyfin: ${e.message}", e)
    }
  }
}
