/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network.proxy

internal data class HttpByteRange(
  val start: Long,
  val endInclusive: Long,
) {
  val length: Long = endInclusive - start + 1L

  companion object {
    private val syntax = Regex("bytes=(\\d*)-(\\d*)", RegexOption.IGNORE_CASE)

    /** Returns null for malformed, multiple, overflowing, or unsatisfiable ranges. */
    fun parse(
      header: String,
      completeLength: Long,
    ): HttpByteRange? {
      if (completeLength <= 0L) return null
      val match = syntax.matchEntire(header.trim()) ?: return null
      val startText = match.groupValues[1]
      val endText = match.groupValues[2]
      if (startText.isEmpty() && endText.isEmpty()) return null

      if (startText.isEmpty()) {
        val suffixLength = endText.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val start = (completeLength - suffixLength).coerceAtLeast(0L)
        return HttpByteRange(start, completeLength - 1L)
      }

      val start = startText.toLongOrNull() ?: return null
      if (start >= completeLength) return null
      val requestedEnd =
        if (endText.isEmpty()) {
          completeLength - 1L
        } else {
          endText.toLongOrNull() ?: return null
        }
      if (requestedEnd < start) return null
      return HttpByteRange(start, requestedEnd.coerceAtMost(completeLength - 1L))
    }
  }
}

