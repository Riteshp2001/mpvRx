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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

object HttpUtils {
  private const val TAG = "HttpUtils"
  private const val CONNECTION_TIMEOUT = 3000
  private const val READ_TIMEOUT = 3000
  private val directMediaExtensions =
    setOf(
      "mp4",
      "m4v",
      "mkv",
      "webm",
      "avi",
      "mov",
      "wmv",
      "flv",
      "ts",
      "m2ts",
      "mp3",
      "m4a",
      "aac",
      "flac",
      "wav",
      "ogg",
      "opus",
      "m3u",
      "m3u8",
      "mpd",
    )
  private val genericRouteTitles =
    setOf(
      "watch",
      "stream",
      "video",
      "play",
      "embed",
      "download",
      "media",
      "live",
      "reel",
      "reels",
      "short",
      "shorts",
      "player",
    )

  data class YouTubeMetadata(
    val title: String,
    val author: String? = null,
    val thumbnailUrl: String? = null,
  )

  fun isYouTubeUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.lowercase()
    return lower.contains("youtube.com") || lower.contains("youtu.be")
  }

  fun isYouTubeUrl(uri: Uri?): Boolean {
    if (uri == null) return false
    val host = uri.host?.lowercase().orEmpty()
    return host.contains("youtube.com") || host == "youtu.be"
  }

  fun extractYouTubeVideoId(uri: Uri?): String? {
    if (uri == null) return null
    val host = uri.host?.lowercase().orEmpty()
    val path = uri.path.orEmpty()
    return when {
      host == "youtu.be" -> uri.pathSegments.firstOrNull()?.trim()
      host.contains("youtube.com") -> {
        when {
          path.contains("/watch") -> uri.getQueryParameter("v")?.trim()
          path.contains("/shorts/") -> uri.pathSegments.getOrNull(1)?.trim()
          path.contains("/live/") -> uri.pathSegments.getOrNull(1)?.trim()
          path.contains("/embed/") -> uri.pathSegments.getOrNull(1)?.trim()
          else -> uri.getQueryParameter("v")?.trim()
        }
      }
      else -> null
    }?.takeIf { it.isNotBlank() && it.length in 5..30 }
  }

  suspend fun fetchYouTubeMetadata(url: String): YouTubeMetadata? =
    withContext(Dispatchers.IO) {
      var connection: HttpURLConnection? = null
      try {
        val oEmbedUrl = "https://www.youtube.com/oembed?url=" + java.net.URLEncoder.encode(url, "UTF-8") + "&format=json"
        connection = URL(oEmbedUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECTION_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        connection.connect()

        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
          val response = connection.inputStream.bufferedReader().use { it.readText() }
          val json = org.json.JSONObject(response)
          val title = json.optString("title").takeIf { it.isNotBlank() }
          val author = json.optString("author_name").takeIf { it.isNotBlank() }
          val thumbnail = json.optString("thumbnail_url").takeIf { it.isNotBlank() }
          if (title != null) {
            return@withContext YouTubeMetadata(title = title, author = author, thumbnailUrl = thumbnail)
          }
        }
        null
      } catch (e: Exception) {
        Log.w(TAG, "Failed to fetch YouTube oEmbed metadata for $url: ${e.message}")
        null
      } finally {
        connection?.disconnect()
      }
    }

  suspend fun extractFilenameFromUrl(url: String): String? =
    withContext(Dispatchers.IO) {
      try {
        val uri = Uri.parse(url)
        if (isYouTubeUrl(uri)) {
          val yt = fetchYouTubeMetadata(url)
          if (yt != null && yt.title.isNotBlank()) {
            Log.d(TAG, "Extracted YouTube title: ${yt.title}")
            return@withContext yt.title
          }
          val videoId = extractYouTubeVideoId(uri)
          if (!videoId.isNullOrBlank()) {
            return@withContext "YouTube Video ($videoId)"
          }
        }

        val filenameFromHeaders = getFilenameFromHttpHeaders(url)
        if (filenameFromHeaders != null) {
          Log.d(TAG, "Extracted filename from headers: $filenameFromHeaders")
          return@withContext filenameFromHeaders
        }
        val filenameFromUrl = extractFilenameFromUrlPath(uri)
        Log.d(TAG, "Extracted filename from URL: $filenameFromUrl")
        return@withContext filenameFromUrl
      } catch (e: Exception) {
        Log.e(TAG, "Error extracting filename: ${e.message}")
        null
      }
    }

  private fun getFilenameFromHttpHeaders(url: String): String? {
    var connection: HttpURLConnection? = null
    try {
      connection = URL(url).openConnection() as HttpURLConnection
      connection.requestMethod = "HEAD"
      connection.connectTimeout = CONNECTION_TIMEOUT
      connection.readTimeout = READ_TIMEOUT
      connection.setRequestProperty("User-Agent", "mpvRx/1.0")
      connection.instanceFollowRedirects = true
      connection.connect()

      val responseCode = connection.responseCode
      if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
        Log.w(TAG, "HTTP response: $responseCode")
      }

      connection.getHeaderField("Content-Disposition")?.let {
        parseContentDisposition(it)?.let { filename -> return filename }
      }

      connection.getHeaderField("Content-Location")?.let {
        return extractFilenameFromUrlPath(Uri.parse(it))
      }

      return null
    } catch (e: IOException) {
      Log.e(TAG, "IO error: ${e.message}")
      return null
    } catch (e: Exception) {
      Log.e(TAG, "Error: ${e.message}")
      return null
    } finally {
      connection?.disconnect()
    }
  }

  private fun parseContentDisposition(contentDisposition: String): String? {
    try {
      val filenameStarPattern = Regex("""filename\*=(?:UTF-8|utf-8)?''?"?([^";\r\n]+)"?""", RegexOption.IGNORE_CASE)
      filenameStarPattern.find(contentDisposition)?.let { match ->
        val encodedFilename = match.groupValues[1]
        return try {
          URLDecoder.decode(encodedFilename, "UTF-8")
        } catch (e: Exception) {
          encodedFilename
        }
      }

      val filenamePattern = Regex("""filename="?([^";\r\n]+)"?""", RegexOption.IGNORE_CASE)
      filenamePattern.find(contentDisposition)?.let { match ->
        return match.groupValues[1].trim()
      }

      return null
    } catch (e: Exception) {
      Log.e(TAG, "Error parsing Content-Disposition: ${e.message}")
      return null
    }
  }

  private fun extractFilenameFromUrlPath(uri: Uri): String {
    val path = uri.path ?: return uri.host ?: "Network Stream"
    val lastSegment = path.substringAfterLast("/")

    if (lastSegment.isNotBlank()) {
      return try {
        URLDecoder
          .decode(lastSegment, "UTF-8")
          .substringBefore("?")
          .substringBefore("#")
          .takeIf { it.isNotBlank() } ?: uri.host ?: "Network Stream"
      } catch (e: Exception) {
        lastSegment.substringBefore("?").substringBefore("#")
      }
    }

    return uri.host ?: "Network Stream"
  }

  fun isNetworkStream(uri: Uri?): Boolean {
    if (uri == null) return false
    val scheme = uri.scheme?.lowercase()
    return scheme in
      listOf("http", "https", "rtmp", "rtmps", "rtsp", "rtsps", "mms", "mmsh", "ftp", "ftps", "gopher", "sctp")
  }

  fun shouldPreferResolvedMediaTitle(
    uri: Uri?,
    fallbackTitle: String?,
  ): Boolean {
    if (uri == null || !isNetworkStream(uri)) return false
    val scheme = uri.scheme?.lowercase()
    if (scheme !in listOf("http", "https")) return false
    return isLikelyJunkTitle(fallbackTitle) || !hasDirectMediaExtension(uri)
  }

  /**
   * True when [uri] is a network stream that points directly at a media/manifest file
   * (e.g. .m3u8/.mpd/.mp4/.ts). Such URLs should bypass yt-dlp and be handed straight to
   * mpv/ffmpeg's native demuxers, exactly like a dedicated player (MX Player/VLC) would.
   */
  fun isDirectMediaUrl(uri: Uri?): Boolean {
    if (uri == null || !isNetworkStream(uri)) return false
    return hasDirectMediaExtension(uri)
  }

  private fun hasDirectMediaExtension(uri: Uri): Boolean {
    val lastSegment =
      uri.lastPathSegment
        ?.substringAfterLast('/')
        ?.let(Uri::decode)
        .orEmpty()
    if (lastSegment.isBlank()) return false
    val extension = lastSegment.substringAfterLast('.', "").lowercase()
    return extension in directMediaExtensions
  }

  /**
   * Extracts the referer domain from a Uri.
   * Returns the full origin (scheme + host + port) to be used as Referer header.
   *
   * @param uri The Uri to extract the referer from
   * @return The referer origin string, or null if extraction fails
   */
  fun extractRefererDomain(uri: Uri?): String? {
    if (uri == null) return null

    return try {
      val scheme = uri.scheme ?: return null
      val host = uri.host ?: return null
      val port = uri.port

      // Build the referer origin
      if (port != -1 && port != 80 && port != 443) {
        // Include non-standard port
        "$scheme://$host:$port"
      } else {
        // Standard port or no port specified
        "$scheme://$host"
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error extracting referer domain: ${e.message}")
      null
    }
  }

  /**
   * Checks if a title is likely to be a raw URL, query string, or generic filename
   * rather than a human-readable media title.
   */
  fun isLikelyJunkTitle(title: String?): Boolean {
    if (title.isNullOrBlank()) return true

    val lower = title.lowercase()

    if (lower in genericRouteTitles) return true

    // Check for common URL patterns
    if (lower.startsWith("http") || lower.contains("://") || lower.contains("www.")) return true

    // Check for query parameters or common dynamic file types
    if (lower.contains("?") ||
      lower.contains("&") ||
      lower.contains("=") ||
      lower.contains(".aspx") ||
      lower.contains(".php") ||
      lower.contains(".jsp") ||
      lower.contains(".cfm")
    ) {
      return true
    }

    // Check for "download" prefix followed by nonsense
    if (lower.startsWith("download.")) return true

    // Check for specific junk seen in user screenshots
    if (lower.contains("share=") || lower.contains("tokens=")) return true

    // Unusually long strings with no spaces are likely URLs or hashes
    if (title.length > 60 && !title.contains(" ")) return true

    return false
  }
}
