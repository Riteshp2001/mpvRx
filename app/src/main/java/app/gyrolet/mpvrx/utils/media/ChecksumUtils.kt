/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import java.util.zip.CRC32

object ChecksumUtils {
  /**
   * Generates a CRC32 checksum for the given text.
   * Returns the hex string representation.
   */
  fun getCRC32(text: String): String {
    val crc = CRC32()
    crc.update(text.toByteArray(Charsets.UTF_8))
    return java.lang.Long
      .toHexString(crc.value)
      .uppercase()
  }
}
