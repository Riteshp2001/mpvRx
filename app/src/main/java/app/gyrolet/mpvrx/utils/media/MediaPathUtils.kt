/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

/**
 * Extracts the file extension from a path or URI string, stripping query
 * parameters and fragment identifiers before extracting the last dot-delimited
 * segment.  Returns a lowercase extension without the leading dot, or an empty
 * string if no extension is found.
 *
 * Example:
 * ```
 * "video.mkv?quality=1080#t=10".fileExtension() == "mkv"
 * "/Audio/clip #edit.mp4".fileExtension() == "mp4"
 * "content://media/123".fileExtension() == ""
 * ```
 */
fun String.fileExtension(): String {
  val scheme = substringBefore("://", missingDelimiterValue = "")
  // '#' and '?' are legal in file names (e.g. hashtags), so paths keep them while they still end in an extension.
  if (scheme.isEmpty() || scheme.equals("file", ignoreCase = true)) {
    lastExtension(this).takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }?.let { return it }
  }
  return lastExtension(substringBefore('?').substringBefore('#'))
}

private fun lastExtension(path: String): String {
  val lastSlash = path.lastIndexOf('/')
  val lastDot = path.lastIndexOf('.')
  return if (lastDot > lastSlash && lastDot != -1 && lastDot < path.length - 1) {
    path.substring(lastDot + 1).lowercase()
  } else {
    ""
  }
}
