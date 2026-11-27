/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/** A credential-free reference to a stream exposed by a saved Xtream account. */
object XtreamPlaybackUri {
  const val SCHEME = "mpvrx-xtream"

  enum class Route(
    val wireValue: String,
    val upstreamPrefix: String?,
  ) {
    LIVE("live", "live"),
    BARE_LIVE("bare-live", null),
    MOVIE("movie", "movie"),
    SERIES("series", "series"),
  }

  data class Reference(
    val accountKey: String,
    val route: Route,
    val streamId: String,
    val extension: String,
  )

  /** Converts a standard Xtream stream URL to a reference that contains no account credentials. */
  fun fromProviderUrl(
    providerUrl: String,
    accountKey: String,
    username: String,
    password: String,
  ): Reference? {
    if (!isValidAccountKey(accountKey) || username.isBlank() || password.isBlank()) return null
    if ('|' in providerUrl) return null

    val url = providerUrl.toHttpUrlOrNull() ?: return null
    if (url.query != null || url.fragment != null) return null
    val segments = url.pathSegments.filter(String::isNotEmpty)
    val credentialIndex =
      (0 until (segments.size - 2))
        .lastOrNull { index -> segments[index] == username && segments[index + 1] == password }
        ?: return null
    if (credentialIndex + 3 != segments.size) return null

    val route =
      when (segments.getOrNull(credentialIndex - 1)?.lowercase()) {
        "live" -> Route.LIVE
        "movie" -> Route.MOVIE
        "series" -> Route.SERIES
        else -> Route.BARE_LIVE
      }
    val streamFile = segments[credentialIndex + 2]
    val separator = streamFile.lastIndexOf('.')
    val streamId = if (separator > 0) streamFile.substring(0, separator) else streamFile
    val extension =
      if (separator > 0) {
        streamFile.substring(separator + 1).lowercase()
      } else if (route == Route.LIVE || route == Route.BARE_LIVE) {
        "ts"
      } else {
        ""
      }

    if (!isValidStreamId(streamId) || !isValidExtension(extension)) return null
    return Reference(accountKey, route, streamId, extension)
  }

  fun create(reference: Reference): String {
    require(isValidAccountKey(reference.accountKey)) { "Invalid Xtream account key" }
    require(isValidStreamId(reference.streamId)) { "Invalid Xtream stream id" }
    require(isValidExtension(reference.extension)) { "Invalid Xtream stream extension" }
    val extension = reference.extension.ifEmpty { "_" }
    return URI(
      SCHEME,
      reference.accountKey,
      "/${reference.route.wireValue}/${reference.streamId}/$extension",
      null,
      null,
    ).toASCIIString()
  }

  fun parse(rawUri: String): Reference? =
    runCatching {
      val uri = URI(rawUri)
      if (!uri.scheme.equals(SCHEME, ignoreCase = true) ||
        uri.userInfo != null ||
        uri.port != -1 ||
        uri.query != null ||
        uri.fragment != null
      ) {
        return@runCatching null
      }

      val accountKey = uri.authority ?: return@runCatching null
      val segments =
        uri.path
          .orEmpty()
          .split('/')
          .filter(String::isNotEmpty)
      if (!isValidAccountKey(accountKey) || segments.size != 3) return@runCatching null
      val route = Route.entries.firstOrNull { it.wireValue == segments[0] } ?: return@runCatching null
      val streamId = segments[1]
      val extension = segments[2].takeUnless { it == "_" }.orEmpty()
      if (!isValidStreamId(streamId) || !isValidExtension(extension)) return@runCatching null
      Reference(accountKey, route, streamId, extension)
    }.getOrNull()

  /** Reconstructs the upstream URL only after the saved account has been resolved securely. */
  fun resolve(
    reference: Reference,
    serverUrl: String,
    username: String,
    password: String,
  ): String {
    require(username.isNotBlank() && password.isNotBlank()) { "Xtream credentials are incomplete" }
    val base = serverUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid Xtream server URL")
    val builder = base.newBuilder()
    reference.route.upstreamPrefix?.let(builder::addPathSegment)
    builder.addPathSegment(username)
    builder.addPathSegment(password)
    builder.addPathSegment(
      if (reference.extension.isBlank()) {
        reference.streamId
      } else {
        "${reference.streamId}.${reference.extension}"
      },
    )
    return builder.build().toString()
  }

  private fun isValidAccountKey(value: String): Boolean =
    value.length in 16..64 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

  private fun isValidStreamId(value: String): Boolean =
    value.isNotEmpty() && value.length <= 20 && value.all(Char::isDigit)

  private fun isValidExtension(value: String): Boolean = value.length <= 12 && value.all(Char::isLetterOrDigit)
}
