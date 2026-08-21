/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Renders a small, dependency-free subset of GitHub-flavoured Markdown:
 * headings (#..###), unordered/ordered lists, horizontal rules, and the
 * inline spans `**bold**`, `*italic*`/`_italic_`, `~~strikethrough~~`,
 * `` `code` `` and `[label](url)` links. Designed for release notes and
 * changelog bodies rather than arbitrary documents.
 */
@Composable
fun MarkdownText(
  markdown: String,
  modifier: Modifier = Modifier,
) {
  val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    blocks.forEach { block -> MarkdownBlockView(block) }
  }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock) {
  when (block) {
    is MarkdownBlock.Heading -> {
      val style =
        when (block.level) {
          1 -> MaterialTheme.typography.titleLarge
          2 -> MaterialTheme.typography.titleMedium
          else -> MaterialTheme.typography.titleSmall
        }
      Text(
        text = rememberInlineMarkdown(block.text),
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp),
      )
    }

    is MarkdownBlock.Bullet -> {
      Row(modifier = Modifier.padding(start = (16 * block.indentLevel).dp)) {
        Box(
          modifier =
            Modifier
              .padding(top = 8.dp, end = 8.dp)
              .size(5.dp)
              .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Text(
          text = rememberInlineMarkdown(block.text),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    is MarkdownBlock.Numbered -> {
      Row(modifier = Modifier.padding(start = (16 * block.indentLevel).dp)) {
        Text(
          text = "${block.number}.",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(end = 8.dp),
        )
        Text(
          text = rememberInlineMarkdown(block.text),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    MarkdownBlock.Divider ->
      HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
      )

    is MarkdownBlock.Paragraph ->
      Text(
        text = rememberInlineMarkdown(block.text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
  }
}

@Composable
private fun rememberInlineMarkdown(text: String): AnnotatedString {
  val linkColor = MaterialTheme.colorScheme.primary
  val codeBackground = MaterialTheme.colorScheme.surfaceVariant
  val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
  return remember(text, linkColor, codeBackground, codeColor) {
    buildInlineMarkdown(
      text = text,
      linkStyle =
        TextLinkStyles(
          style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
        ),
      codeStyle =
        SpanStyle(
          fontFamily = FontFamily.Monospace,
          background = codeBackground,
          color = codeColor,
        ),
    )
  }
}

internal sealed interface MarkdownBlock {
  data class Heading(
    val level: Int,
    val text: String,
  ) : MarkdownBlock

  data class Bullet(
    val text: String,
    val indentLevel: Int,
  ) : MarkdownBlock

  data class Numbered(
    val number: Int,
    val text: String,
    val indentLevel: Int,
  ) : MarkdownBlock

  data class Paragraph(
    val text: String,
  ) : MarkdownBlock

  data object Divider : MarkdownBlock
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRegex = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val numberedRegex = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val dividerRegex = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")

internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
  val blocks = mutableListOf<MarkdownBlock>()
  markdown.replace("\r\n", "\n").split('\n').forEach { rawLine ->
    val line = rawLine.trimEnd()
    if (line.isBlank()) return@forEach

    headingRegex.matchEntire(line)?.let { match ->
      blocks += MarkdownBlock.Heading(level = match.groupValues[1].length, text = match.groupValues[2].trim())
      return@forEach
    }
    if (dividerRegex.matches(line)) {
      blocks += MarkdownBlock.Divider
      return@forEach
    }
    bulletRegex.matchEntire(line)?.let { match ->
      blocks +=
        MarkdownBlock.Bullet(
          text = match.groupValues[2].trim(),
          indentLevel = (match.groupValues[1].length / 2).coerceAtMost(3),
        )
      return@forEach
    }
    numberedRegex.matchEntire(line)?.let { match ->
      blocks +=
        MarkdownBlock.Numbered(
          number = match.groupValues[2].toIntOrNull() ?: 1,
          text = match.groupValues[3].trim(),
          indentLevel = (match.groupValues[1].length / 2).coerceAtMost(3),
        )
      return@forEach
    }
    blocks += MarkdownBlock.Paragraph(line.trim())
  }
  return blocks
}

private data class InlineToken(
  val range: IntRange,
  val display: String,
  val style: InlineStyle,
  val url: String? = null,
)

private enum class InlineStyle { BOLD, ITALIC, STRIKETHROUGH, CODE, LINK }

private val inlineRegex =
  Regex(
    "`([^`]+)`" + // 1: code
      "|\\[([^\\]]+)]\\(([^)\\s]+)\\)" + // 2,3: link
      "|\\*\\*([^*]+)\\*\\*" + // 4: bold
      "|__([^_]+)__" + // 5: bold
      "|\\*([^*\\s][^*]*)\\*" + // 6: italic
      "|_([^_\\s][^_]*)_" + // 7: italic
      "|~~([^~]+)~~", // 8: strikethrough
  )

internal fun buildInlineMarkdown(
  text: String,
  linkStyle: TextLinkStyles,
  codeStyle: SpanStyle,
): AnnotatedString {
  val tokens =
    inlineRegex.findAll(text).map { match ->
      val groups = match.groupValues
      when {
        groups[1].isNotEmpty() -> InlineToken(match.range, groups[1], InlineStyle.CODE)
        groups[2].isNotEmpty() -> InlineToken(match.range, groups[2], InlineStyle.LINK, url = groups[3])
        groups[4].isNotEmpty() -> InlineToken(match.range, groups[4], InlineStyle.BOLD)
        groups[5].isNotEmpty() -> InlineToken(match.range, groups[5], InlineStyle.BOLD)
        groups[6].isNotEmpty() -> InlineToken(match.range, groups[6], InlineStyle.ITALIC)
        groups[7].isNotEmpty() -> InlineToken(match.range, groups[7], InlineStyle.ITALIC)
        else -> InlineToken(match.range, groups[8], InlineStyle.STRIKETHROUGH)
      }
    }

  return buildAnnotatedString {
    var cursor = 0
    tokens.forEach { token ->
      if (token.range.first > cursor) append(text.substring(cursor, token.range.first))
      when (token.style) {
        InlineStyle.CODE -> withStyle(codeStyle) { append(token.display) }
        InlineStyle.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.display) }
        InlineStyle.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.display) }
        InlineStyle.STRIKETHROUGH ->
          withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(token.display) }
        InlineStyle.LINK -> {
          val url = token.url.orEmpty()
          if (url.startsWith("http://") || url.startsWith("https://")) {
            withLink(LinkAnnotation.Url(url = url, styles = linkStyle)) { append(token.display) }
          } else {
            append(token.display)
          }
        }
      }
      cursor = token.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
  }
}
