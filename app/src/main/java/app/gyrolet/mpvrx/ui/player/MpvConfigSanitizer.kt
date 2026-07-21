package app.gyrolet.mpvrx.ui.player

internal object MpvConfigSanitizer {
  data class Result(
    val content: String,
    val warnings: List<String>,
  )

  fun sanitize(content: String): Result {
    val warnings = mutableListOf<String>()
    val sanitized = content.lineSequence().mapIndexed { index, line ->
      val trimmed = line.trim()
      if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("[")) {
        return@mapIndexed line
      }
      val option = trimmed.substringBefore('=', missingDelimiterValue = trimmed)
        .substringBefore(' ')
        .removePrefix("--")
        .trim()
        .lowercase()
        .replace('_', '-')
      if (option in unsupportedOptions) {
        val warning = "line ${index + 1}: unsupported option '$option' was disabled"
        warnings += warning
        "# mpvRx: $warning\n# $line"
      } else {
        line
      }
    }.joinToString("\n")
    return Result(sanitized, warnings)
  }

  private val unsupportedOptions = setOf("tone-map-metadata")
}
