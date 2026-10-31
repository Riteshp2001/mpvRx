/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.preview

import app.gyrolet.mpvrx.ui.player.PlaybackHttpHeaders
import org.json.JSONArray
import org.json.JSONObject

/** Snapshot taken under the playback lock; URLs and credentials never enter logs or disk caches. */
internal data class SeekPreviewInput(
  val original: String,
  val runtime: String?,
  val headers: Map<String, String>,
  val rotation: Int,
  val ytdlpJson: String?,
) {
  fun resolve(): SeekPreviewSource? = resolveSeekPreviewSource(original, runtime, headers, rotation, ytdlpJson)
}

internal data class SeekPreviewSource(
  val path: String,
  val headers: Map<String, String>,
  val rotation: Int,
  val storyboard: Storyboard? = null,
)

internal data class Storyboard(
  val columns: Int,
  val rows: Int,
  val frameWidth: Int,
  val frameHeight: Int,
  val fps: Double,
  val sheets: List<StoryboardSheet>,
  val headers: Map<String, String>,
) {
  fun frameAt(seconds: Double): Pair<StoryboardSheet, Int>? {
    var start = 0.0
    for (sheet in sheets) {
      if (seconds < start + sheet.duration) {
        // fps is authoritative: the final sheet is often only partially populated.
        val index = ((seconds - start) * fps).toInt().coerceIn(0, columns * rows - 1)
        return sheet to index
      }
      start += sheet.duration
    }
    return null
  }
}

internal data class StoryboardSheet(val url: String, val duration: Double)

internal fun resolveSeekPreviewSource(
  original: String?,
  runtime: String?,
  headers: Map<String, String>,
  rotation: Int,
  ytdlpJson: String?,
): SeekPreviewSource? {
  val info = ytdlpJson?.takeIf { it.length <= 16 * 1024 * 1024 }?.let {
    runCatching { JSONObject(it) }.getOrNull()
  }
  // The hook's previous result can remain in user-data when the next item is a direct file.
  val matchingInfo = info?.takeIf {
    !original.isNullOrBlank() && (
      it.optString("webpage_url") == original || it.optString("original_url") == original ||
        it.optString("url") == runtime ||
        it.optJSONArray("formats").objects().any { format ->
          val url = format.optString("url")
          url.isNotBlank() && (url == runtime || runtime?.contains(url) == true)
        }
      )
  }
  val formats = matchingInfo?.optJSONArray("formats").objects()
  val storyboardFormat = formats.filter {
    it.optString("protocol") == "mhtml" && it.optDouble("fps", 0.0).let { fps -> fps.isFinite() && fps > 0.0 } &&
      it.optInt("rows") in 1..100 && it.optInt("columns") in 1..100 &&
      it.optInt("width") in 1..1024 && it.optInt("height") in 1..1024
  }.minByOrNull { kotlin.math.abs(it.optInt("width") - 240) }
  val storyboard = storyboardFormat?.let { format ->
    val sheets = format.optJSONArray("fragments").objects().mapNotNull { fragment ->
      val url = fragment.optString("url")
      val duration = fragment.optDouble("duration", 0.0)
      if (isPreviewHttpUrl(url) && duration.isFinite() && duration > 0) StoryboardSheet(url, duration) else null
    }
    sheets.takeIf { it.isNotEmpty() && it.size == format.optJSONArray("fragments")?.length() }?.let {
      Storyboard(
        format.optInt("columns"), format.optInt("rows"), format.optInt("width"), format.optInt("height"),
        format.optDouble("fps"), it,
        PlaybackHttpHeaders.merge(matchingInfo?.optJSONObject("http_headers").headers(), format.optJSONObject("http_headers").headers()),
      )
    }
  }
  // Use a small, independently playable video format when mpv combines DASH video/audio into EDL.
  // Never pass an EDL string or a website page to FFmpeg's thumbnail decoder.
  val directFormat = (matchingInfo?.optJSONArray("requested_formats").objects() + formats).filter {
    it.optString("vcodec", "none") != "none" && !it.optBoolean("has_drm") &&
      isPreviewHttpUrl(it.optString("url")) && it.optJSONArray("fragments") == null
  }.minByOrNull { kotlin.math.abs(it.optInt("height", 360) - 360) }
  val path = when {
    runtime?.startsWith("edl://") == true -> directFormat?.optString("url")
    runtime?.startsWith("fd://") == true -> original
    !runtime.isNullOrBlank() -> runtime
    else -> original
  }?.takeIf { it.startsWith('/') || it.startsWith("content://") || it.startsWith("file://") || isPreviewHttpUrl(it) }
  if (path == null && storyboard == null) return null
  val sourceHeaders = if (runtime?.startsWith("edl://") == true) {
    PlaybackHttpHeaders.merge(headers, matchingInfo?.optJSONObject("http_headers").headers(), directFormat?.optJSONObject("http_headers").headers())
  } else headers
  return SeekPreviewSource(path.orEmpty(), sourceHeaders, rotation, storyboard)
}

internal fun isPreviewHttpUrl(value: String): Boolean =
  value.startsWith("https://", true) || value.startsWith("http://", true)

private fun JSONArray?.objects(): List<JSONObject> =
  if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject?.headers(): Map<String, String> =
  if (this == null) emptyMap() else keys().asSequence().associateWith { optString(it) }
