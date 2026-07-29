/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
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
