/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import androidx.compose.ui.graphics.Color

enum class SkipSegmentType {
  INTRO,
  RECAP,
  OUTRO,
  CREDITS,
  PREVIEW,
  NEXT_EPISODE,
  ;

  val label: String
    get() =
      when (this) {
        INTRO -> "Skip intro"
        RECAP -> "Skip recap"
        OUTRO -> "Skip outro"
        CREDITS -> "Skip credits"
        PREVIEW -> "Skip preview"
        NEXT_EPISODE -> "Next episode"
      }

  val accentColor: Color
    get() =
      when (this) {
        INTRO -> Color(0xFFFF7A00)
        RECAP -> Color(0xFF2F80FF)
        OUTRO -> Color(0xFFE05666)
        CREDITS -> Color(0xFFA64DFF)
        PREVIEW -> Color(0xFF00D4C7)
        NEXT_EPISODE -> Color(0xFF4CAF50)
      }
}

data class SkipSegment(
  val type: SkipSegmentType,
  val startSeconds: Double,
  val endSeconds: Double,
  val source: String,
) {
  val isValid: Boolean
    get() = endSeconds > startSeconds

  val label: String
    get() = type.label
}
