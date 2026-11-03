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
 * "content://media/123".fileExtension() == ""
 * ```
 */
fun String.fileExtension(): String {
  val cleanPath = substringBefore('?').substringBefore('#')
  val lastSlash = cleanPath.lastIndexOf('/')
  val lastDot = cleanPath.lastIndexOf('.')
  return if (lastDot > lastSlash && lastDot != -1 && lastDot < cleanPath.length - 1) {
    cleanPath.substring(lastDot + 1).lowercase()
  } else {
    ""
  }
}
