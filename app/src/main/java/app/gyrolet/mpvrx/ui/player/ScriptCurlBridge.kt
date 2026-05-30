package app.gyrolet.mpvrx.ui.player

import android.util.Log
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.isString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.parseToJsonElement
import java.util.UUID

class ScriptCurlBridge(
    private val scope: CoroutineScope,
) {

    private external fun nativeExecute(
        url: String,
        method: String,
        headerKeys: Array<String>?,
        headerValues: Array<String>?,
        body: String?,
        contentType: String?,
        timeout: Int,
    ): String

    companion object {
        private const val TAG = "ScriptCurlBridge"
        private const val RESPONSE_PROPERTY = "user-data/mpvrx/curl_response"

        private const val DEFAULT_TIMEOUT_SECONDS = 30L
        private const val MAX_TIMEOUT_SECONDS = 120L
        private const val MAX_JSON_UNWRAP_DEPTH = 8

        init {
            System.loadLibrary("curl_bridge")
        }
    }

    private val json = Json {
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

        val request = try {
            parseRequest(rawJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse request", e)

            writeErrorResponse(
                id = "unknown",
                error = "Invalid request JSON: ${e.message}"
            )
            return
        }

        val requestId =
            request.id ?: UUID.randomUUID().toString()

        val finalRequest = request.copy(
            id = requestId
        )

        if (finalRequest.url.isBlank()) {
            writeErrorResponse(
                id = requestId,
                error = "URL must not be blank"
            )
            return
        }

        val timeoutSec =
            finalRequest.timeout.coerceIn(
                1,
                MAX_TIMEOUT_SECONDS.toInt()
            )

        scope.launch(Dispatchers.IO) {
            val response =
                executeRequest(
                    finalRequest,
                    timeoutSec
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
                    errors = errors + "Stopped because JSON decoding entered a loop at depth $depth."
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
                errors = errors
            )
        }

        throw buildParseRequestException(
            original = original,
            current = current,
            errors = errors + "Exceeded max JSON unwrap depth: $MAX_JSON_UNWRAP_DEPTH."
        )
    }

    private fun decodeIfJsonString(value: String): String? {
        return try {
            val element = json.parseToJsonElement(value)

            if (element is JsonPrimitive && element.isString) {
                element.content.trim()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
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

    private fun looksLikeStrippedEncodedJsonObject(value: String): Boolean {
        return value.startsWith("{") &&
            value.endsWith("}") &&
            (
                value.contains("\\\"") ||
                    value.contains("\\\\") ||
                    value.contains("\\/")
            )
    }

    private fun buildParseRequestException(
        original: String,
        current: String,
        errors: List<String>
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
            }
        )
    }

    private fun executeRequest(
        request: CurlRequest,
        timeoutSec: Int,
    ): CurlResponse {

        val nativeJson = try {

            nativeExecute(
                url = request.url,
                method = request.method.uppercase(),
                headerKeys =
                    if (request.headers.isNotEmpty())
                        request.headers.keys.toTypedArray()
                    else
                        null,
                headerValues =
                    if (request.headers.isNotEmpty())
                        request.headers.values.toTypedArray()
                    else
                        null,
                body = request.body,
                contentType =
                    request.content_type.ifBlank {
                        null
                    },
                timeout = timeoutSec,
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Request failed",
                e
            )

            return CurlResponse(
                id = request.id ?: "unknown",
                status = 0,
                body = "",
                headers = emptyMap(),
                error = e.message
                    ?: "Native curl error",
            )
        }

        return runCatching {

            val obj =
                json.parseToJsonElement(
                    nativeJson
                ).jsonObject

            CurlResponse(
                id = request.id ?: "unknown",
                status =
                    obj["status"]
                        ?.jsonPrimitive
                        ?.content
                        ?.toIntOrNull()
                        ?: 0,
                body =
                    obj["body"]
                        ?.jsonPrimitive
                        ?.content
                        ?: "",
                headers =
                    obj["headers"]
                        ?.jsonObject
                        ?.mapValues { (_, v) ->
                            v.jsonPrimitive.content
                        }
                        ?: emptyMap(),
                error =
                    when (
                        val e = obj["error"]
                    ) {
                        null,
                        is JsonNull -> null

                        else ->
                            e.jsonPrimitive.content
                    },
            )

        }.getOrElse { e ->

            CurlResponse(
                id = request.id ?: "unknown",
                status = 0,
                body = "",
                headers = emptyMap(),
                error =
                    "Failed to parse native response: ${e.message}",
            )
        }
    }

    private fun writeResponse(
        response: CurlResponse
    ) {

        val responseJson =
            json.encodeToString(response)

        MPVLib.setPropertyString(
            RESPONSE_PROPERTY,
            responseJson
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
            )
        )
    }
}