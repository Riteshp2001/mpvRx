package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.net.Uri
import android.util.Log
import app.gyrolet.mpvrx.ui.player.openContentFd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
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
 * Parser for M3U and M3U8 playlist files.
 * Supports both simple M3U format and extended M3U format with EXTINF tags.
 * Handles relative URLs for any URI scheme, including http(s), davs://, smb://, ftp://, sftp://.
 */
object M3UParser {
  private const val TAG = "M3UParser"
  private const val TIMEOUT_MS = 30_000 // 30s — generous for slow WebDAV/SMB servers
  private const val DEFAULT_USER_AGENT = "mpvRx/1.0"

  private const val EXTINF_PREFIX = "#EXTINF:"
  private const val KODIPROP_PREFIX = "#KODIPROP:"
  private const val EXTVLCOPT_PREFIX = "#EXTVLCOPT:"
  private const val KODI_LICENSE_TYPE = "inputstream.adaptive.license_type"
  private const val KODI_LICENSE_KEY  = "inputstream.adaptive.license_key"

  private val kodiPropRegex = """([^=]+)=(.+)""".toRegex()
  private val extinfMetaRegex = """([\w\-_.]+)=\s*(?:"([^"]*)"|([\S]+))""".toRegex()
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
      
      // Resolve sourceUrl to its real filesystem path to allow proper base directory resolution
      val resolvedPath = runCatching { uri.openContentFd(context) }.getOrNull()
      val sourceUrl = if (resolvedPath != null && !resolvedPath.startsWith("fd://")) {
        resolvedPath
      } else {
        uri.toString()
      }

      // Get filename for playlist name with multi-stage fallback
      val rawFilename = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
          cursor.getString(nameIndex)
        } else null
      } ?: (if (resolvedPath != null && !resolvedPath.startsWith("fd://")) File(resolvedPath).name else null)
        ?: uri.lastPathSegment
        ?: "Local M3U Playlist"

      // URL-decode to handle encoded names (e.g. %20 -> space, %3A -> :)
      val decodedFilename = runCatching { java.net.URLDecoder.decode(rawFilename, "UTF-8") }.getOrNull() ?: rawFilename

      // Clean the name: strip extension, replace underscores/dashes with spaces
      val cleanPlaylistName = decodedFilename
        .substringBeforeLast('.')
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .ifEmpty { "Local M3U Playlist" }
      
      parseContent(content, sourceUrl, overridePlaylistName = cleanPlaylistName)
    } catch (e: Exception) {
      Log.e(TAG, "Error parsing M3U playlist from URI", e)
      M3UParseResult.Error("Failed to parse playlist: ${e.message}", e)
    }
  }
  
  /**
   * Parse M3U/M3U8 content from a string.
   *
   * @param content  Raw M3U text
   * @param sourceUrl  The URL/path from which the content was loaded; used to resolve
   *                   relative entry URLs. Pass null only if all entries are absolute.
   */
  fun parseContent(
    content: String,
    sourceUrl: String? = null,
    overridePlaylistName: String? = null,
  ): M3UParseResult {
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
                currentTvgLogo    = extractAttribute(parts[0], "tvg-logo")
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

            // Resolve relative URLs against the base URL of the M3U file.
            // This handles any URI scheme: http(s), davs://, smb://, ftp://, sftp://, etc.
            if (baseUrl != null && !isAbsoluteUrl(mediaUrl)) {
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
      
      // Extract playlist name from override, source URL/filename, or use default
      val playlistName = overridePlaylistName ?: sourceUrl?.let { 
        if (it.startsWith("http://") || it.startsWith("https://")) {
          extractPlaylistNameFromUrl(it)
        } else {
          // It's a filename or a non-http URL — extract name without extension
          it.substringAfterLast('/')
            .substringBeforeLast('.')
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
   * Returns true if the URL string is already absolute (has a scheme or is protocol-relative).
   */
  private fun isAbsoluteUrl(url: String): Boolean =
    url.startsWith("//") || Uri.parse(url).scheme != null

  fun isLikelyHlsMediaManifest(content: String): Boolean {
    val lines = content.lines().map { it.trim() }
    return lines.any { line ->
      line.startsWith("#EXT-X-STREAM-INF") ||
        line.startsWith("#EXT-X-TARGETDURATION") ||
        line.startsWith("#EXT-X-MEDIA-SEQUENCE") ||
        line.startsWith("#EXT-X-MAP") ||
        line.startsWith("#EXT-X-BYTERANGE") ||
        line.startsWith("#EXT-X-ENDLIST")
    }
  }
  
  /**
   * Extract attribute value from an EXTINF attribute string.
   * Example: extractAttribute(line, "tvg-logo") -> "http://example.com/logo.png"
   */
  private fun extractAttribute(line: String, attributeName: String): String? {
    val pattern = """$attributeName="([^"]+)"""".toRegex()
    return pattern.find(line)?.groupValues?.getOrNull(1)
  }

  /**
   * Extract the base directory URL from a full URL.
   *
   * - http/https: uses java.net.URL for accurate path handling
   * - All other schemes (davs://, smb://, ftp://, sftp://, etc.): strips the
   *   filename after the last slash
   */
  private fun extractBaseUrl(url: String): String {
    val scheme = Uri.parse(url).scheme?.lowercase()
    if (scheme == "http" || scheme == "https") {
      return try {
        val urlObj = URL(url)
        val path = urlObj.path
        val lastSlash = path.lastIndexOf('/')
        val basePath = if (lastSlash >= 0) path.substring(0, lastSlash + 1) else "/"
        val userInfo = urlObj.userInfo?.takeIf { it.isNotBlank() }?.let { "$it@" } ?: ""
        "${urlObj.protocol}://$userInfo${urlObj.host}${if (urlObj.port != -1) ":${urlObj.port}" else ""}$basePath"
      } catch (_: Exception) {
        url.substringBeforeLast('/') + "/"
      }
    }
    // Non-http scheme: strip filename after last slash
    val stripped = url.substringBeforeLast('/')
    return if (stripped == url) "$url/" else "$stripped/"
  }
  
  /**
   * Resolve a relative URL against a base URL.
   *
   * Handles all URI schemes correctly:
   * - http/https/ftp → java.net.URL (handles `../` segments properly)
   * - davs://, smb://, sftp://, and any other scheme → pure Uri-based resolution
   *
   * @param baseUrl      Directory URL (always ends with '/' from [extractBaseUrl])
   * @param relativeUrl  Raw URL string from an M3U entry line
   */
  private fun resolveRelativeUrl(baseUrl: String, relativeUrl: String): String {
    // Fast path: already absolute or protocol-relative
    if (isAbsoluteUrl(relativeUrl)) return relativeUrl

    val baseScheme = Uri.parse(baseUrl).scheme?.lowercase()

    // For http/https/ftp, java.net.URL handles `../` traversal correctly
    if (baseScheme == "http" || baseScheme == "https" || baseScheme == "ftp") {
      return try {
        URL(URL(baseUrl), relativeUrl).toString()
      } catch (_: Exception) {
        // Fall through to Uri-based resolver
        resolveWithUri(baseUrl, relativeUrl, baseScheme)
      }
    }

    // For davs://, smb://, sftp://, etc. — use Uri-based resolution
    return resolveWithUri(baseUrl, relativeUrl, baseScheme)
  }

  /**
   * Pure Uri-based relative URL resolver.
   * Works for any URI scheme where java.net.URL is not applicable.
   */
  private fun resolveWithUri(baseUrl: String, relativeUrl: String, baseScheme: String?): String {
    val baseUri = Uri.parse(baseUrl)
    val authority = baseUri.encodedAuthority?.takeIf { it.isNotBlank() }
      ?: baseUri.authority?.takeIf { it.isNotBlank() }
      ?: ""
    val schemePrefix = if (baseScheme != null) "$baseScheme://" else "//"

    return when {
      relativeUrl.startsWith("/") -> {
        // Absolute path on the same server — keep scheme + authority, replace path
        "$schemePrefix$authority$relativeUrl"
      }
      else -> {
        // Relative to the base directory (e.g. "ep2.mkv" or "../s2/ep1.mkv")
        // baseUrl already ends with '/' from extractBaseUrl
        val combined = baseUrl + relativeUrl
        // Use java.net.URI.normalize() to collapse '..' and '.' segments
        try {
          java.net.URI(combined).normalize().toString()
        } catch (_: Exception) {
          combined
        }
      }
    }
  }
  
  /**
   * Extract a human-readable title from a media URL.
   * Falls back to URL-decoding the last path segment and stripping the extension.
   */
  private fun extractTitleFromUrl(url: String): String {
    return try {
      val path = Uri.parse(url).path ?: url
      val filename = path.substringAfterLast('/')
      val nameWithoutExt = filename.substringBeforeLast('.')
      java.net.URLDecoder.decode(nameWithoutExt, "UTF-8")
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .ifBlank { filename.take(60) }
    } catch (_: Exception) {
      url.substringAfterLast('/').take(60)
    }
  }
  
  /**
   * Extract a human-readable playlist name from a URL.
   * Decodes URL encoding and strips the file extension.
   */
  private fun extractPlaylistNameFromUrl(url: String): String {
    return try {
      val path = Uri.parse(url).path ?: return "M3U Playlist"
      val filename = path.substringAfterLast('/')
      val nameWithoutExt = filename.substringBeforeLast('.')
      java.net.URLDecoder.decode(nameWithoutExt, "UTF-8")
        .replace('_', ' ')
        .replace('-', ' ')
        .replaceFirstChar { it.uppercase() }
        .trim()
        .ifBlank { "M3U Playlist" }
    } catch (_: Exception) {
      "M3U Playlist"
    }
  }

}
