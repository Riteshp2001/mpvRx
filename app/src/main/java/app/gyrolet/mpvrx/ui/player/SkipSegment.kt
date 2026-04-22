package app.gyrolet.mpvrx.ui.player

import androidx.compose.ui.graphics.Color

enum class SkipSegmentType {
  INTRO,
  RECAP,
  OUTRO,
  CREDITS,
  PREVIEW,
  ;

  val label: String
    get() =
      when (this) {
        INTRO -> "Skip intro"
        RECAP -> "Skip recap"
        OUTRO -> "Skip outro"
        CREDITS -> "Skip credits"
        PREVIEW -> "Skip preview"
      }

  val accentColor: Color
    get() =
      when (this) {
        INTRO -> Color(0xFFFF7A00)
        RECAP -> Color(0xFF2F80FF)
        OUTRO -> Color(0xFFE05666)
        CREDITS -> Color(0xFFA64DFF)
        PREVIEW -> Color(0xFF00D4C7)
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
