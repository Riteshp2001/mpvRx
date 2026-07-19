package app.gyrolet.mpvrx.utils.media

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class JellyfinSessionReporter(
  private val baseUrl: String,
  private val itemId: String,
  private val apiKey: String,
  private val playSessionId: String?,
  private val mediaSourceId: String?,
  private val coroutineScope: CoroutineScope
) {
  companion object {
    private const val TAG = "JellyfinSessionReporter"

    // Ticks per millisecond in Jellyfin (1 tick = 100 nanoseconds = 10,000 ticks per millisecond)
    private const val TICKS_PER_MILLISECOND = 10000L

    fun create(url: String, coroutineScope: CoroutineScope): JellyfinSessionReporter? {
      try {
        val uri = Uri.parse(url)
        val pathSegments = uri.pathSegments
        val videosIndex = pathSegments.indexOf("Videos")
        if (videosIndex == -1 || videosIndex + 1 >= pathSegments.size) {
          return null
        }
        val itemId = pathSegments[videosIndex + 1]
        val apiKey = uri.getQueryParameter("api_key") ?: uri.getQueryParameter("ApiKey") ?: return null
        val playSessionId = uri.getQueryParameter("playSessionId") ?: uri.getQueryParameter("PlaySessionId")
        val mediaSourceId = uri.getQueryParameter("mediaSourceId") ?: uri.getQueryParameter("MediaSourceId")

        val scheme = uri.scheme ?: "http"
        val authority = uri.encodedAuthority ?: return null
        val subPathSegments = pathSegments.subList(0, videosIndex)
        val baseUrl = if (subPathSegments.isEmpty()) {
          "$scheme://$authority"
        } else {
          "$scheme://$authority/" + subPathSegments.joinToString("/")
        }

        Log.d(TAG, "Created JellyfinSessionReporter: baseUrl=$baseUrl, itemId=$itemId, playSessionId=$playSessionId, mediaSourceId=$mediaSourceId")
        return JellyfinSessionReporter(baseUrl, itemId, apiKey, playSessionId, mediaSourceId, coroutineScope)
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
    val IsMuted: Boolean = false
  )

  @Serializable
  private data class PlaybackProgressInfo(
    val ItemId: String,
    val PlaySessionId: String? = null,
    val MediaSourceId: String? = null,
    val PositionTicks: Long? = null,
    val CanSeek: Boolean = true,
    val IsPaused: Boolean = false,
    val IsMuted: Boolean = false
  )

  @Serializable
  private data class PlaybackStopInfo(
    val ItemId: String,
    val PlaySessionId: String? = null,
    val MediaSourceId: String? = null,
    val PositionTicks: Long? = null
  )

  fun reportPlaybackStart(positionMs: Long) {
    coroutineScope.launch(Dispatchers.IO) {
      val urlString = "$baseUrl/Sessions/Playing?api_key=$apiKey"
      val info = PlaybackStartInfo(
        ItemId = itemId,
        PlaySessionId = playSessionId,
        MediaSourceId = mediaSourceId,
        PositionTicks = positionMs * TICKS_PER_MILLISECOND
      )
      val jsonBody = Json.encodeToString(info)
      sendPostRequest(urlString, jsonBody)
    }
  }

  fun reportPlaybackProgress(positionMs: Long, isPaused: Boolean) {
    coroutineScope.launch(Dispatchers.IO) {
      val urlString = "$baseUrl/Sessions/Playing/Progress?api_key=$apiKey"
      val info = PlaybackProgressInfo(
        ItemId = itemId,
        PlaySessionId = playSessionId,
        MediaSourceId = mediaSourceId,
        PositionTicks = positionMs * TICKS_PER_MILLISECOND,
        IsPaused = isPaused
      )
      val jsonBody = Json.encodeToString(info)
      sendPostRequest(urlString, jsonBody)
    }
  }

  fun reportPlaybackStop(positionMs: Long) {
    coroutineScope.launch(Dispatchers.IO) {
      val urlString = "$baseUrl/Sessions/Playing/Stopped?api_key=$apiKey"
      val info = PlaybackStopInfo(
        ItemId = itemId,
        PlaySessionId = playSessionId,
        MediaSourceId = mediaSourceId,
        PositionTicks = positionMs * TICKS_PER_MILLISECOND
      )
      val jsonBody = Json.encodeToString(info)
      sendPostRequest(urlString, jsonBody)
    }
  }

  private fun sendPostRequest(urlString: String, jsonBody: String) {
    var connection: HttpURLConnection? = null
    try {
      val url = URL(urlString)
      connection = url.openConnection() as HttpURLConnection
      connection.requestMethod = "POST"
      connection.connectTimeout = 5000
      connection.readTimeout = 5000
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "application/json")
      connection.setRequestProperty("X-Emby-Token", apiKey)
      connection.setRequestProperty("User-Agent", "mpvRx/1.0")

      OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
        writer.write(jsonBody)
        writer.flush()
      }

      val responseCode = connection.responseCode
      if (responseCode in 200..299) {
        Log.d(TAG, "Successfully reported status to Jellyfin: $urlString")
      } else {
        Log.e(TAG, "Failed to report status to Jellyfin: $urlString, response code: $responseCode")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error sending playback report to Jellyfin: ${e.message}", e)
    } finally {
      connection?.disconnect()
    }
  }
}
