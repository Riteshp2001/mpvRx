/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Derived from BitChord's SpotifyCanvasQuery
 * (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later.
 */

package app.gyrolet.mpvrx.data.artwork

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * How Spotify's web player asks for a track's Canvas: the `canvas` GraphQL query on Pathfinder
 * v2. Kept free of any HTTP client so the request/response shapes can be tested offline.
 *
 * Pathfinder operations are named by a persisted-query hash that changes when Spotify rebuilds
 * the web player; [QueryHashes] reads the live one out of the player's own scripts and falls
 * back to [KNOWN_CANVAS_HASH].
 */
internal object SpotifyCanvasQuery {
  const val ENDPOINT = "https://api-partner.spotify.com/pathfinder/v2/query"
  private const val OPERATION = "canvas"

  /** The `canvas` query's hash as of January 2026. */
  const val KNOWN_CANVAS_HASH = "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f"

  private val json =
    Json {
      ignoreUnknownKeys = true
      isLenient = true
    }

  fun requestBody(
    trackUri: String,
    hash: String,
  ): String =
    buildJsonObject {
      put("operationName", OPERATION)
      putJsonObject("variables") { put("trackUri", trackUri) }
      putJsonObject("extensions") {
        putJsonObject("persistedQuery") {
          put("version", 1)
          put("sha256Hash", hash)
        }
      }
    }.toString()

  sealed interface Answer {
    data class Found(
      val url: String,
    ) : Answer

    /** Spotify answered without a video this can play (no canvas, or a still image). */
    data class NoCanvas(
      val detail: String,
    ) : Answer

    /** The query itself failed; a stale persisted-query hash is worth looking up again once. */
    data class Failed(
      val reason: String,
      val staleHash: Boolean = false,
    ) : Answer
  }

  /** Reads `data.trackUnion.canvas.url` out of an answer. */
  fun parse(body: String): Answer {
    val root =
      runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return Answer.Failed("not JSON")
    val errors = (root["errors"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.text("message") }.orEmpty()
    val data = root["data"] as? JsonObject
    if (data == null) {
      val reason = errors.joinToString("; ").ifBlank { "no data" }
      return Answer.Failed(reason, staleHash = errors.any { it.contains("PersistedQueryNotFound", ignoreCase = true) })
    }
    val canvas =
      (data["trackUnion"] as? JsonObject)?.get("canvas") as? JsonObject
        ?: return Answer.NoCanvas("canvas null")
    val detail = "type=${canvas.text("type")} url=${canvas.text("url")}"
    val url = canvas.text("url")?.takeIf { it.startsWith("https://") } ?: return Answer.NoCanvas(detail)
    // Some canvases are still images; only video loops are played.
    if (!VIDEO_URL.containsMatchIn(url)) return Answer.NoCanvas(detail)
    return Answer.Found(url)
  }

  private val VIDEO_URL = Regex("""\.mp4(?:[?#]|$)""", RegexOption.IGNORE_CASE)

  /** `"canvas","query","<64 hex>"`, the way the web player's bundle declares its operations. */
  private val OPERATION_DECLARATION =
    Regex("""["'](\w+)["']\s*,\s*["']query["']\s*,\s*["']([0-9a-f]{64})["']""")

  fun findQueryHash(
    script: String,
    operation: String = OPERATION,
  ): String? =
    OPERATION_DECLARATION
      .findAll(script)
      .firstOrNull { it.groupValues[1] == operation }
      ?.groupValues
      ?.get(2)

  fun webPlayerScripts(html: String): List<String> =
    WEB_PLAYER_SCRIPT
      .findAll(html)
      .map { it.value }
      .distinct()
      .toList()

  private val WEB_PLAYER_SCRIPT =
    Regex("""https://open\.spotifycdn\.com/cdn/build/web-player/[A-Za-z0-9._~-]+\.js""")

  /** The live `canvas` hash, remembered for a while; [fetch] is the caller's own GET. */
  class QueryHashes(
    private val fetch: suspend (String) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
  ) {
    @Volatile private var hash: String? = null

    @Volatile private var checkedAt = 0L

    suspend fun canvasHash(forceRefresh: Boolean = false): String {
      val current = hash
      val age = now() - checkedAt
      if (!forceRefresh && checkedAt != 0L && age < (if (current != null) FOUND_TTL_MS else MISSED_TTL_MS)) {
        return current ?: KNOWN_CANVAS_HASH
      }
      checkedAt = now()
      lookUp()?.let { hash = it }
      return hash ?: KNOWN_CANVAS_HASH
    }

    private suspend fun lookUp(): String? {
      val html = fetch("https://open.spotify.com/") ?: return null
      for (script in webPlayerScripts(html).take(MAX_SCRIPTS)) {
        val body = fetch(script) ?: continue
        findQueryHash(body)?.let { return it }
      }
      return null
    }

    private companion object {
      const val FOUND_TTL_MS = 12L * 60 * 60 * 1000
      const val MISSED_TTL_MS = 30L * 60 * 1000
      const val MAX_SCRIPTS = 8
    }
  }

  private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
}
