/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player

/** Lossless codec for mpv's colon-separated path-list option syntax. */
object MpvPathList {
  fun decode(raw: String?): List<String> {
    if (raw.isNullOrEmpty()) return emptyList()
    val values = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    raw.forEach { character ->
      when {
        escaped -> {
          current.append(character)
          escaped = false
        }
        character == '\\' -> escaped = true
        character == ':' -> {
          current.toString().takeIf(String::isNotEmpty)?.let(values::add)
          current.clear()
        }
        else -> current.append(character)
      }
    }
    if (escaped) current.append('\\')
    current.toString().takeIf(String::isNotEmpty)?.let(values::add)
    return values
  }

  fun encode(paths: List<String>): String =
    paths.joinToString(":") { path ->
      buildString(path.length) {
        path.forEach { character ->
          if (character == '\\' || character == ':') append('\\')
          append(character)
        }
      }
    }
}
