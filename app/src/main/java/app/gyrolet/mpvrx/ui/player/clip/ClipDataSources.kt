/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.ui.player.clip

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import java.io.File

/** Reopen video and subtitle sources with the same authentication as playback. */
@UnstableApi
internal fun clipDataSourceFactory(
  context: Context,
  headers: Map<String, String>,
): DefaultDataSource.Factory {
  val httpFactory = DefaultHttpDataSource.Factory()
  headers.entries
    .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
    ?.value
    ?.takeIf { it.isNotBlank() }
    ?.let(httpFactory::setUserAgent)
  httpFactory.setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
  return DefaultDataSource.Factory(context, httpFactory)
}

internal fun clipMediaUri(source: String): Uri {
  val parsed = Uri.parse(source)
  if (parsed.scheme in setOf("fd", "fdclose")) {
    // Reopen an mpv-owned descriptor without consuming or closing playback's descriptor.
    val descriptor = source.substringAfter("://").toIntOrNull()
    require(descriptor != null && descriptor >= 0) { "Invalid media file descriptor" }
    return Uri.fromFile(File("/proc/self/fd/$descriptor"))
  }
  return if (parsed.scheme.isNullOrBlank()) Uri.fromFile(File(source)) else parsed
}
