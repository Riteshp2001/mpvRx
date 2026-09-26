/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import app.gyrolet.mpvrx.network.SharedHttpClient
import app.gyrolet.mpvrx.network.awaitResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Shared plumbing for the lyrics providers.
 *
 * Every provider is raced against the others, so one that hangs holds up the
 * whole lookup: deadlines here are deliberately far shorter than the shared
 * client's stream-oriented ones. A lyric that arrives after the second chorus
 * is of no use, and the providers still waiting behind it are the better answer.
 */
internal val lyricsJson =
  Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

internal const val LYRICS_AGENT = "mpvRx Music Player/1.0"

private const val TIMEOUT_SECONDS = 6L

/** Hosts that have to resolve a track against an upstream catalogue first. */
private const val PATIENT_TIMEOUT_SECONDS = 15L

private val client: OkHttpClient by lazy {
  SharedHttpClient.derive {
    callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    connectTimeout(3, TimeUnit.SECONDS)
    readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
  }
}

private val patientClient: OkHttpClient by lazy {
  SharedHttpClient.derive {
    callTimeout(PATIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    connectTimeout(10, TimeUnit.SECONDS)
    readTimeout(PATIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  }
}

/** Body of a successful GET, or null for any failure at all. */
internal suspend fun lyricsGet(
  url: String,
  headers: Map<String, String> = emptyMap(),
): String? = lyricsCall(url, headers)

/** [lyricsGet] against a host that needs longer than the usual deadline. */
internal suspend fun lyricsGetPatient(
  url: String,
  headers: Map<String, String> = emptyMap(),
): String? = lyricsCall(url, headers, patient = true)

/** Body of a GET carrying an Apple-style bearer token and browser origin. */
internal suspend fun lyricsGetAuthorized(
  url: String,
  bearer: String,
): String? =
  lyricsGet(
    url,
    buildMap {
      put("Authorization", "Bearer $bearer")
      put("Origin", "https://music.apple.com")
      put("Referer", "https://music.apple.com/")
    },
  )

/** Body of an authenticated GET without service-specific browser headers. */
internal suspend fun lyricsGetBearer(
  url: String,
  bearer: String,
): String? {
  if (bearer.isBlank()) return null
  return lyricsGet(url, mapOf("Authorization" to "Bearer $bearer"))
}

internal suspend fun lyricsPost(
  url: String,
  body: String,
  contentType: String = "application/json; charset=utf-8",
  headers: Map<String, String> = emptyMap(),
): String? =
  runCatchingQuiet {
    val request =
      Request
        .Builder()
        .url(url)
        .header("User-Agent", LYRICS_AGENT)
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .post(body.toRequestBody(contentType.toMediaTypeOrNull()))
        .build()
    execute(client, request)
  }

private suspend fun lyricsCall(
  url: String,
  headers: Map<String, String>,
  patient: Boolean = false,
): String? =
  runCatchingQuiet {
    val request =
      Request
        .Builder()
        .url(url)
        .header("User-Agent", LYRICS_AGENT)
        .header("Accept", "*/*")
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .get()
        .build()
    execute(if (patient) patientClient else client, request)
  }

private suspend fun execute(
  client: OkHttpClient,
  request: Request,
): String? =
  client.newCall(request).awaitResponse().use { response ->
    if (response.isSuccessful) response.body.string().takeIf { it.isNotBlank() } else null
  }

/**
 * A miss is null, not a crash — but a cancelled lookup is a cancelled lookup and
 * must keep travelling up, or a race that has already been won would be revived
 * by the loser it just cancelled.
 */
private inline fun runCatchingQuiet(block: () -> String?): String? =
  try {
    block()
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (_: Exception) {
    null
  }

/** Query-string builder that cannot produce a malformed URL. */
internal fun buildUrl(
  base: String,
  vararg params: Pair<String, String?>,
): String? {
  val url = base.toHttpUrlOrNull() ?: return null
  val builder = url.newBuilder()
  params.forEach { (name, value) ->
    if (!value.isNullOrBlank()) builder.addQueryParameter(name, value)
  }
  return builder.build().toString()
}

internal fun HttpUrl.Builder.addIfNotBlank(
  name: String,
  value: String?,
): HttpUrl.Builder = if (value.isNullOrBlank()) this else addQueryParameter(name, value)
