/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.repository.ai

internal data class SanitizedAiOutput(
  val finalText: String,
  val reasoning: String? = null,
)

internal object AiOutputSanitizer {
  private val pairedReasoningBlock =
    Regex(
      "<(think|thinking|analysis|reasoning)>\\s*(.*?)\\s*</\\1>",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
  private val finalMarker =
    Regex(
      "(?:^|\\n)\\s*(?:final(?: answer)?|answer|output)\\s*:\\s*",
      RegexOption.IGNORE_CASE,
    )
  private val openingReasoningTag =
    Regex(
      "^\\s*<(think|thinking|analysis|reasoning)>\\s*",
      RegexOption.IGNORE_CASE,
    )

  fun splitReasoning(raw: String): SanitizedAiOutput {
    val reasoning =
      pairedReasoningBlock
        .findAll(raw)
        .map { it.groupValues[2].trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .ifBlank { null }
    var finalText = pairedReasoningBlock.replace(raw, "").trim()
    val marker = finalMarker.findAll(finalText).lastOrNull()
    if (marker != null) {
      finalText = finalText.substring(marker.range.last + 1).trim()
    } else {
      val unclosedReasoning = openingReasoningTag.find(finalText)
      if (unclosedReasoning != null) {
        val content = finalText.substring(unclosedReasoning.range.last + 1).trim()
        val combinedReasoning = content.ifBlank { reasoning.orEmpty() }.takeIf { it.isNotBlank() }
        return SanitizedAiOutput(finalText = "", reasoning = combinedReasoning)
      }
    }
    finalText = stripCodeFence(finalText)
    return SanitizedAiOutput(finalText, reasoning)
  }

  fun stripCodeFence(value: String): String {
    val trimmed = value.trim()
    if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) return trimmed
    return trimmed
      .removePrefix("```")
      .substringAfter('\n', "")
      .removeSuffix("```")
      .trim()
  }
}
