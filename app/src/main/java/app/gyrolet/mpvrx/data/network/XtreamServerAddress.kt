/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Credentials from a pasted provider link stay in memory and never enter saved address suggestions. */
class XtreamServerAddress private constructor(
  val serverUrl: String,
  val username: String?,
  val password: String?,
) {
  override fun toString(): String = "XtreamServerAddress(credentials=<redacted>)"

  companion object {
    fun parse(raw: String): XtreamServerAddress {
      val input = raw.trim()
      require(input.isNotEmpty() && input.none { it.isISOControl() }) { "Invalid Xtream server URL" }
      // Xtream providers commonly supply host:port. An explicit HTTPS URL is always preserved.
      val parsed =
        (if ("://" in input) input else "http://$input").toHttpUrlOrNull()
          ?: throw IllegalArgumentException("Invalid Xtream server URL")
      require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Enter credentials in their own fields" }
      val segments = parsed.encodedPathSegments.dropLastWhile { it.isEmpty() }
      val isEndpoint = segments.lastOrNull()?.lowercase() in setOf("get.php", "player_api.php", "xmltv.php")
      require(parsed.query == null || isEndpoint) { "Use a server address or an Xtream provider link" }
      val baseSegments = if (isEndpoint) segments.dropLast(1) else segments
      val base =
        parsed
          .newBuilder()
          .encodedPath("/" + baseSegments.joinToString("/"))
          .query(null)
          .fragment(null)
          .build()
          .toString()
          .trimEnd('/')
      return XtreamServerAddress(base, parsed.queryParameter("username"), parsed.queryParameter("password"))
    }
  }
}
