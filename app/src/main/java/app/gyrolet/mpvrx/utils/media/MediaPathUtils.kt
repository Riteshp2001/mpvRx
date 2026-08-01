/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.utils.media

import app.gyrolet.mpvrx.preferences.PlayerPreferences
import `is`.xyz.mpv.MPVLib

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
fun String.fileExtension(): String =
  substringBefore('?')
    .substringBefore('#')
    .substringAfterLast('.', "")
    .lowercase()

/**
 * Returns the MPV seek mode string based on the user's precise-seeking preference
 * and the current media duration.  Videos shorter than 2 minutes always use
 * precise seeking for a better scrubbing experience.
 */
fun resolveSeekMode(playerPreferences: PlayerPreferences): String {
  val duration = MPVLib.getPropertyInt("duration") ?: 0
  val usePrecise = playerPreferences.usePreciseSeeking.get() || duration < 120
  return if (usePrecise) "relative+exact" else "relative+keyframes"
}
