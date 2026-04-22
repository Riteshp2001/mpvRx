package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Data class representing a parsed M3U playlist entry
 */
data class M3UPlaylistItem(
  val url: String,
  val title: String? = null,
  val duration: Int = -1, // Duration in seconds, -1 if unknown
  val tvgId: String? = null, // TVG channel ID (e.g. EPG mapping)
  val tvgName: String? = null, // TVG display name override
  val tvgLogo: String? = null, // Logo URL if present in EXTINF
  val groupTitle: String? = null, // Group title for categorization
  val licenseType: String? = null, // DRM license type (e.g. com.widevine.alpha)
  val licenseKey: String? = null, // DRM license key URL
  val userAgent: String? = null, // Per-stream user-agent from EXTVLCOPT
)

/**
 * Result of M3U playlist parsing
 */
sealed class M3UParseResult {
  data class Success(val playlistName: String, val items: List<M3UPlaylistItem>) : M3UParseResult()
  data class Error(val message: String, val exception: Throwable? = null) : M3UParseResult()
}

/**
 * Parser for M3U and M3U8 playlist files
 * Supports both simple M3U format and extended M3U format with EXTINF tags
 */
object M3UParser {
  private const val TAG = "M3UParser"
  private const val TIMEOUT_MS = 15000
  private const val DEFAULT_USER_AGENT = "MpvRx/1.0"

  private const val EXTINF_PREFIX = "#EXTINF:"
  private const val KODIPROP_PREFIX = "#KODIPROP:"
  private const val EXTVLCOPT_PREFIX = "#EXTVLCOPT:"
  private const val KODI_LICENSE_TYPE = "inputstream.adaptive.license_type"
  private const val KODI_LICENSE_KEY  = "inputstream.adaptive.license_key"

  private val kodiPropRegex = """([^=]+)=(.+)""".toRegex()
  private val extinfMetaRegex = """([\w\-_.]+)=\s*(?:"([^"]*)"|(\S+))""".toRegex()
  private val extinfInfoRegex = """(-?\d+)(.*),(.*)""".toRegex()

  /**
   * Parse an M3U/M3U8 playlist from a URL
   */
  suspend fun parseFromUrl(url: String, userAgent: String? = null): M3UParseResult = withContext(Dispatchers.IO) {
    try {
      Log.d(TAG, "Parsing M3U playlist from URL: $url")
      
      val urlObj = URL(url)
      val connection = urlObj.openConnection() as HttpURLConnection
      connection.connectTimeout = TIMEOUT_MS
      connection.readTimeout = TIMEOUT_MS
      connection.requestMethod = "GET"
      connection.setRequestProperty("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT)
      
      val responseCode = connection.responseCode
      if (responseCode != HttpURLConnection.HTTP_OK) {
        return@withContext M3UParseResult.Error("HTTP error: $responseCode")
      }
      
      val content = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { reader ->
        reader.readText()
      }
      
      connection.disconnect()
      
      parseContent(content, url)
    } catch (e: Exception) {
      Log.e(TAG, "Error parsing M3U playlist", e)
      M3UParseResult.Error("Failed to parse playlist: ${e.message}", e)
    }
  }
  
  /**
   * Parse an M3U/M3U8 playlist from a local file URI
   */
  suspend fun parseFromUri(context: Context, uri: Uri): M3UParseResult = withContext(Dispatchers.IO) {
    try {
      Log.d(TAG, "Parsing M3U playlist from URI: $uri")
      
      val content = context.contentResolver.openInputStream(uri)?.use { inputStream ->
        BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { reader ->
          reader.readText()
        }
      } ?: return@withContext M3UParseResult.Error("Failed to open file")
      
      // Get filename for playlist name
      val filename = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
          cursor.getString(nameIndex)
        } else null
      } ?: uri.lastPathSegment ?: "Local M3U Playlist"
      
      parseContent(content, filename)
    } catch (e: Exception) {
      Log.e(TAG, "Error parsing M3U playlist from URI", e)
      M3UParseResult.Error("Failed to parse playlist: ${e.message}", e)
    }
  }
  
  /**
   * Parse M3U/M3U8 content from string
   */
  fun parseContent(content: String, sourceUrl: String? = null): M3UParseResult {
    try {
      val lines = content.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }

      if (lines.isEmpty()) {
        return M3UParseResult.Error("Playlist is empty")
      }

      val items = mutableListOf<M3UPlaylistItem>()
      var currentTitle: String? = null
      var currentDuration: Int = -1
      var currentTvgId: String? = null
      var currentTvgName: String? = null
      var currentTvgLogo: String? = null
      var currentGroupTitle: String? = null
      var currentLicenseType: String? = null
      var currentLicenseKey: String? = null
      var currentUserAgent: String? = null

      val baseUrl = sourceUrl?.let { extractBaseUrl(it) }

      for (line in lines) {
        when {
          line.startsWith("#EXTM3U") -> {
            // Playlist header, skip
            continue
          }

          line.startsWith(EXTINF_PREFIX) -> {
            val info = line.substring(EXTINF_PREFIX.length).trim()
            val match = extinfInfoRegex.matchEntire(info)
            if (match != null) {
              currentDuration = match.groups[1]?.value?.toIntOrNull() ?: -1
              currentTitle = match.groups[3]?.value?.trim()?.ifBlank { null }
              val metaText = match.groups[2]?.value.orEmpty().trim()
              for (m in extinfMetaRegex.findAll(metaText)) {
                val key   = m.groups[1]?.value?.trim() ?: continue
                val value = (m.groups[2]?.value ?: m.groups[3]?.value)?.ifBlank { null } ?: continue
                when (key) {
                  "tvg-id"      -> currentTvgId = value
                  "tvg-name"    -> currentTvgName = value
                  "tvg-logo"    -> currentTvgLogo = value
                  "group-title" -> currentGroupTitle = value
                }
              }
            } else {
              // Fallback: old comma-based split
              val parts = info.split(",", limit = 2)
              currentDuration = parts.firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.toIntOrNull() ?: -1
              currentTitle = if (parts.size > 1) parts[1].trim().ifBlank { null } else null
              if (parts.isNotEmpty()) {
                currentTvgLogo   = extractAttribute(parts[0], "tvg-logo")
                currentGroupTitle = extractAttribute(parts[0], "group-title")
                currentTvgId      = extractAttribute(parts[0], "tvg-id")
                currentTvgName    = extractAttribute(parts[0], "tvg-name")
              }
            }
          }

          line.startsWith(KODIPROP_PREFIX) -> {
            val kodi = line.substring(KODIPROP_PREFIX.length).trim()
            val m = kodiPropRegex.matchEntire(kodi) ?: continue
            val key   = m.groups[1]?.value?.trim() ?: continue
            val value = m.groups[2]?.value?.trim()?.ifBlank { null } ?: continue
            when (key) {
              KODI_LICENSE_TYPE -> currentLicenseType = value
              KODI_LICENSE_KEY  -> currentLicenseKey  = value
            }
          }

          line.startsWith(EXTVLCOPT_PREFIX) -> {
            val opt = line.substring(EXTVLCOPT_PREFIX.length).trim()
            if (opt.startsWith("http-user-agent=")) {
              currentUserAgent = opt.removePrefix("http-user-agent=").trim()
            }
          }

          line.startsWith("#EXT-X-") -> {
            // HLS-specific tags, skip
            continue
          }

          line.startsWith("#") -> {
            continue
          }

          else -> {
            // This is a media URL
            var mediaUrl = line.trim()
            if (mediaUrl.isEmpty()) continue

            // If URL is relative and we have a base URL, make it absolute
            if (!mediaUrl.startsWith("http://") && !mediaUrl.startsWith("https://") && baseUrl != null) {
              mediaUrl = resolveRelativeUrl(baseUrl, mediaUrl)
            }

            // Generate a title if none was provided
            val title = currentTitle ?: currentTvgName ?: extractTitleFromUrl(mediaUrl)

            items.add(
              M3UPlaylistItem(
                url = mediaUrl,
                title = title,
                duration = currentDuration,
                tvgId = currentTvgId,
                tvgName = currentTvgName,
                tvgLogo = currentTvgLogo,
                groupTitle = currentGroupTitle,
                licenseType = currentLicenseType,
                licenseKey = currentLicenseKey,
                userAgent = currentUserAgent,
              )
            )

            // Reset current info for next entry
            currentTitle = null
            currentDuration = -1
            currentTvgId = null
            currentTvgName = null
            currentTvgLogo = null
            currentGroupTitle = null
            currentLicenseType = null
            currentLicenseKey = null
            currentUserAgent = null
          }
        }
      }
      
      if (items.isEmpty()) {
        return M3UParseResult.Error("No valid media URLs found in playlist")
      }
      
      // Extract playlist name from source URL/filename or use default
      val playlistName = sourceUrl?.let { 
        // Check if it's a URL or a filename
        if (it.startsWith("http://") || it.startsWith("https://")) {
          extractPlaylistNameFromUrl(it)
        } else {
          // It's a filename, extract name without extension
          it.substringBeforeLast('.', it)
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .ifEmpty { "M3U Playlist" }
        }
      } ?: "M3U Playlist"
      
      Log.d(TAG, "Successfully parsed M3U playlist with ${items.size} items")
      return M3UParseResult.Success(playlistName, items)
      
    } catch (e: Exception) {
      Log.e(TAG, "Error parsing M3U content", e)
      return M3UParseResult.Error("Failed to parse playlist content: ${e.message}", e)
    }
  }
  
  /**
   * Extract attribute value from EXTINF line
   * Example: tvg-logo="http://example.com/logo.png"
   */
  private fun extractAttribute(line: String, attributeName: String): String? {
    val pattern = """$attributeName="([^"]+)"""".toRegex()
    return pattern.find(line)?.groupValues?.getOrNull(1)
  }
  
  /**
   * Extract base URL from a full URL
   */
  private fun extractBaseUrl(url: String): String {
    return try {
      val urlObj = URL(url)
      val path = urlObj.path
      val lastSlash = path.lastIndexOf('/')
      val basePath = if (lastSlash >= 0) path.substring(0, lastSlash + 1) else "/"
      "${urlObj.protocol}://${urlObj.host}${if (urlObj.port != -1) ":${urlObj.port}" else ""}$basePath"
    } catch (_: Exception) {
      url.substringBeforeLast('/') + "/"
    }
  }
  
  /**
   * Resolve a relative URL against a base URL
   */
  private fun resolveRelativeUrl(baseUrl: String, relativeUrl: String): String {
    return try {
      URL(URL(baseUrl), relativeUrl).toString()
    } catch (_: Exception) {
      // Fallback to simple concatenation
      if (relativeUrl.startsWith("/")) {
        val base = URL(baseUrl)
        "${base.protocol}://${base.host}${if (base.port != -1) ":${base.port}" else ""}$relativeUrl"
      } else {
        baseUrl + relativeUrl
      }
    }
  }
  
  /**
   * Extract a readable title from a URL
   */
  private fun extractTitleFromUrl(url: String): String {
    return try {
      val urlObj = URL(url)
      val path = urlObj.path
      val filename = path.substringAfterLast('/')
      
      // Remove extension and decode
      val nameWithoutExt = filename.substringBeforeLast('.')
      java.net.URLDecoder.decode(nameWithoutExt, "UTF-8")
        .replace('_', ' ')
        .replace('-', ' ')
    } catch (_: Exception) {
      url.substringAfterLast('/').take(50)
    }
  }
  
  /**
   * Extract playlist name from URL
   */
  private fun extractPlaylistNameFromUrl(url: String): String {
    return try {
      val urlObj = URL(url)
      val path = urlObj.path
      val filename = path.substringAfterLast('/')
      
      // Remove extension
      val nameWithoutExt = filename.substringBeforeLast('.')
      java.net.URLDecoder.decode(nameWithoutExt, "UTF-8")
        .replace('_', ' ')
        .replace('-', ' ')
        .replaceFirstChar { it.uppercase() }
    } catch (_: Exception) {
      "M3U Playlist"
    }
  }

}


