/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.util.Log
import app.gyrolet.mpvrx.network.SharedHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class ScriptCurlBridge(
  private val scope: CoroutineScope,
) {
  companion object {
    private const val TAG = "ScriptCurlBridge"
    private const val RESPONSE_PROPERTY = "user-data/mpvrx/curl_response"

    private const val DEFAULT_TIMEOUT_SECONDS = 30L
    private const val MAX_TIMEOUT_SECONDS = 120L
    private const val MAX_JSON_UNWRAP_DEPTH = 8

    private const val MAX_HEADER_COUNT = 64
    private const val MAX_BODY_BYTES = 8L * 1024 * 1024
    private const val USER_AGENT = "mpvRx-script-curl/1.0"

    private val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
    private val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH")

    /** Header names must be RFC 7230 tokens; this also blocks CRLF request splitting. */
    private fun isValidHeaderName(name: String): Boolean =
      name.isNotEmpty() && name.all { it.code in 33..126 && it != ':' }

    private fun containsCrlf(value: String): Boolean = value.any { it == '\r' || it == '\n' }
  }

  private val json =
    Json {
      ignoreUnknownKeys = true
      isLenient = true
      encodeDefaults = true
      explicitNulls = false
    }

  @Serializable
  private data class CurlRequest(
    val id: String? = null,
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val content_type: String = "text/plain; charset=utf-8",
    val timeout: Int = DEFAULT_TIMEOUT_SECONDS.toInt(),
  )

  @Serializable
  private data class CurlResponse(
    val id: String,
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
    val error: String?,
  )

  fun handleRequest(rawJson: String) {
    Log.d(TAG, "Received curl_request: $rawJson")

    val request =
      try {
        parseRequest(rawJson)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to parse request", e)

        writeErrorResponse(
          id = "unknown",
          error = "Invalid request JSON: ${e.message}",
        )
        return
      }

    val requestId =
      request.id ?: UUID.randomUUID().toString()

    val finalRequest =
      request.copy(
        id = requestId,
      )

    if (finalRequest.url.isBlank()) {
      writeErrorResponse(
        id = requestId,
        error = "URL must not be blank",
      )
      return
    }

    val timeoutSec =
      finalRequest.timeout.coerceIn(
        1,
        MAX_TIMEOUT_SECONDS.toInt(),
      )

    scope.launch(Dispatchers.IO) {
      val response =
        executeRequest(
          finalRequest,
          timeoutSec,
        )

      writeResponse(response)
    }
  }

  private fun parseRequest(rawJson: String): CurlRequest {
    val original = rawJson.trim()

    require(original.isNotEmpty()) {
      "Request JSON cannot be empty"
    }

    var current = original
    val seen = LinkedHashSet<String>()
    val errors = mutableListOf<String>()

    repeat(MAX_JSON_UNWRAP_DEPTH) { depth ->
      current = current.trim()

      if (!seen.add(current)) {
        throw buildParseRequestException(
          original = original,
          current = current,
          errors = errors + "Stopped because JSON decoding entered a loop at depth $depth.",
        )
      }

      // 1. Best case: current is already a CurlRequest JSON object.
      try {
        return json.decodeFromString<CurlRequest>(current)
      } catch (e: Exception) {
        errors += "Depth $depth direct CurlRequest parse failed: ${e.message}"
      }

      // 2. Handle valid JSON string-wrapped object:
      //
      // Example:
      // "{\"url\":\"https://example.com\",\"method\":\"GET\"}"
      val decodedJsonString = decodeIfJsonString(current)

      if (decodedJsonString != null && decodedJsonString != current) {
        current = decodedJsonString
        return@repeat
      }

      // 3. Handle broken input where the outer quotes were stripped:
      //
      // Example:
      // {\"url\":\"https://example.com\",\"method\":\"GET\"}
      //
      // This is not valid JSON by itself, but it is the body of a JSON string.
      val decodedStrippedJsonString = decodeIfStrippedJsonStringBody(current)

      if (decodedStrippedJsonString != null && decodedStrippedJsonString != current) {
        current = decodedStrippedJsonString
        return@repeat
      }

      throw buildParseRequestException(
        original = original,
        current = current,
        errors = errors,
      )
    }

    throw buildParseRequestException(
      original = original,
      current = current,
      errors = errors + "Exceeded max JSON unwrap depth: $MAX_JSON_UNWRAP_DEPTH.",
    )
  }

  private fun decodeIfJsonString(value: String): String? =
    try {
      val element = json.parseToJsonElement(value)

      if (element is JsonPrimitive && element.toString().startsWith("\"")) {
        element.content.trim()
      } else {
        null
      }
    } catch (_: Exception) {
      null
    }

  private fun decodeIfStrippedJsonStringBody(value: String): String? {
    val current = value.trim()

    if (!looksLikeStrippedEncodedJsonObject(current)) {
      return null
    }

    return try {
      // Important:
      // This intentionally adds only the missing outer quotes.
      // Do NOT use JsonPrimitive(current).toString() here because that would preserve
      // the backslashes instead of decoding the escaped JSON body.
      val wrapped = "\"$current\""
      json.decodeFromString<String>(wrapped).trim()
    } catch (_: Exception) {
      null
    }
  }

  private fun looksLikeStrippedEncodedJsonObject(value: String): Boolean =
    value.startsWith("{") &&
      value.endsWith("}") &&
      (
        value.contains("\\\"") ||
          value.contains("\\\\") ||
          value.contains("\\/")
      )

  private fun buildParseRequestException(
    original: String,
    current: String,
    errors: List<String>,
  ): IllegalArgumentException {
    val originalPreview = original.take(500)
    val currentPreview = current.take(500)

    return IllegalArgumentException(
      buildString {
        appendLine("Unable to parse CurlRequest JSON.")
        appendLine()
        appendLine("Original input preview:")
        appendLine(originalPreview)
        appendLine()
        appendLine("Last normalized input preview:")
        appendLine(currentPreview)

        if (errors.isNotEmpty()) {
          appendLine()
          appendLine("Parser attempts:")
          errors.takeLast(10).forEach {
            appendLine("- $it")
          }
        }
      },
    )
  }

  private fun executeRequest(
    request: CurlRequest,
    timeoutSec: Int,
  ): CurlResponse {
    val id = request.id ?: "unknown"

    fun fail(error: String) = CurlResponse(id = id, status = 0, body = "", headers = emptyMap(), error = error)

    val method = request.method.uppercase()
    if (method !in ALLOWED_METHODS) return fail("Unsupported HTTP method")

    // Restricting the scheme keeps scripts off file://, ftp:// and other local-reach protocols.
    val url = request.url.toHttpUrlOrNull() ?: return fail("Invalid or non-HTTP(S) URL")

    if (request.headers.size > MAX_HEADER_COUNT) return fail("Too many headers")
    if (containsCrlf(request.content_type)) return fail("Invalid content type")
    request.headers.forEach { (name, value) ->
      if (!isValidHeaderName(name) || containsCrlf(value)) return fail("Invalid header")
    }

    val body =
      when {
        method !in METHODS_WITH_BODY -> null
        else ->
          (request.body ?: "").toByteArray()
            .toRequestBody(request.content_type.ifBlank { null }?.toMediaTypeOrNull())
      }

    val httpRequest =
      Request
        .Builder()
        .url(url)
        .method(method, body)
        .header("User-Agent", USER_AGENT)
        .apply { request.headers.forEach { (name, value) -> header(name, value) } }
        .build()

    val client =
      SharedHttpClient.derive {
        connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        callTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
      }

    return try {
      client.newCall(httpRequest).execute().use { response ->
        // Buffer at most the cap so a hostile endpoint cannot stream an unbounded body into memory.
        val source = response.body.source()
        source.request(MAX_BODY_BYTES + 1)
        val bodyText = source.buffer.readUtf8(minOf(source.buffer.size, MAX_BODY_BYTES))
        CurlResponse(
          id = id,
          status = response.code,
          body = bodyText,
          headers = response.headers.names().associateWith { response.headers.values(it).joinToString(", ") },
          error = null,
        )
      }
    } catch (e: IOException) {
      Log.e(TAG, "Request failed", e)
      fail(e.message ?: "Network error")
    } catch (e: IllegalArgumentException) {
      Log.e(TAG, "Request rejected", e)
      fail(e.message ?: "Invalid request")
    }
  }

  private fun writeResponse(response: CurlResponse) {
    val responseJson =
      json.encodeToString(response)

    PlaybackSession.setPropertyString(
      RESPONSE_PROPERTY,
      responseJson,
    )
  }

  private fun writeErrorResponse(
    id: String,
    error: String,
  ) {
    writeResponse(
      CurlResponse(
        id = id,
        status = 0,
        body = "",
        headers = emptyMap(),
        error = error,
      ),
    )
  }
}
