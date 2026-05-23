package app.gyrolet.mpvrx.exoplayer.feature.player.extensions

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.os.BundleCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.gyrolet.mpvrx.exoplayer.core.common.Logger
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.convertToUTF8
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.getFilenameFromUri
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.subtitleCacheDir

fun Uri.getSubtitleMime(displayName: String? = null): String {
    val name = displayName ?: path ?: ""
    return when {
        name.endsWith(".ssa", ignoreCase = true) ||
            name.endsWith(".ass", ignoreCase = true) -> MimeTypes.TEXT_SSA
        name.endsWith(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
        name.endsWith(".ttml", ignoreCase = true) ||
            name.endsWith(".xml", ignoreCase = true) ||
            name.endsWith(".dfxp", ignoreCase = true) -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

val Uri.isSchemaContent: Boolean
    get() = ContentResolver.SCHEME_CONTENT.equals(scheme, ignoreCase = true)

suspend fun Context.uriToSubtitleConfiguration(
    uri: Uri,
    subtitleEncoding: String = "",
    isSelected: Boolean = false,
): MediaItem.SubtitleConfiguration {
    val charset = if (subtitleEncoding.isNotEmpty() && Charset.isSupported(subtitleEncoding)) {
        Charset.forName(subtitleEncoding)
    } else {
        null
    }
    val label = getFilenameFromUri(uri)
    val mimeType = uri.getSubtitleMime(displayName = label)
    val utf8ConvertedUri = convertToUTF8(uri = uri, charset = charset)
    val subtitleUri = normalizeAssFonts(uri = utf8ConvertedUri, label = label, mimeType = mimeType)
    return MediaItem.SubtitleConfiguration.Builder(subtitleUri).apply {
        setId(uri.toString())
        setMimeType(mimeType)
        setLabel(label)
        if (isSelected) setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
    }.build()
}

private suspend fun Context.normalizeAssFonts(
    uri: Uri,
    label: String,
    mimeType: String,
): Uri {
    if (mimeType != MimeTypes.TEXT_SSA) return uri

    return withContext(Dispatchers.IO) {
        try {
            if (uri.scheme?.let { it in NETWORK_URI_SCHEMES } == true) return@withContext uri
            val sourceText = contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.reader(StandardCharsets.UTF_8).readText()
            } ?: return@withContext uri
            val normalizedText = sourceText.normalizeAssSubtitleText()
            if (normalizedText == sourceText) return@withContext uri

            val file = File(subtitleCacheDir, normalizedAssFontFileName(label = label, uri = uri))
            file.writeText(normalizedText, StandardCharsets.UTF_8)
            Uri.fromFile(file)
        } catch (exception: Exception) {
            Logger.error(SUBTITLE_CONFIG_TAG, "Failed to normalize ASS subtitle fonts", exception)
            uri
        }
    }
}

internal fun String.normalizeAssSubtitleText(): String {
    val lineSeparator = detectLineSeparator()
    var section = AssSection.Other
    var styleFontNameIndex: Int? = null
    var eventTextIndex: Int? = null
    var hasChanges = false
    val normalizedLines = lineSequence().map { line ->
        val trimmedLine = line.trim()
        if (trimmedLine.startsWith("[") && trimmedLine.endsWith("]")) {
            section = trimmedLine.assSection()
            styleFontNameIndex = null
            eventTextIndex = null
            return@map line
        }

        val normalizedLine = when {
            section == AssSection.Style && trimmedLine.startsWith("Format:", ignoreCase = true) -> {
                styleFontNameIndex = trimmedLine.assFormatFieldIndex("Fontname")
                line
            }
            section == AssSection.Style && trimmedLine.startsWith("Style:", ignoreCase = true) -> {
                styleFontNameIndex?.let(line::normalizeAssStyleFont) ?: line
            }
            section == AssSection.Event && trimmedLine.startsWith("Format:", ignoreCase = true) -> {
                eventTextIndex = trimmedLine.assFormatFieldIndex("Text")
                line
            }
            section == AssSection.Event && isAssEventLine(trimmedLine) -> {
                eventTextIndex?.let(line::normalizeAssEventText) ?: line
            }
            else -> line
        }
        if (normalizedLine != line) hasChanges = true
        normalizedLine
    }.toList()

    if (!hasChanges) return this

    val normalizedText = normalizedLines.joinToString(separator = lineSeparator)
    return if (endsWith("\n") || endsWith("\r")) normalizedText + lineSeparator else normalizedText
}

private fun String.assSection(): AssSection = when (lowercase(Locale.US)) {
    in ASS_STYLE_SECTION_HEADERS -> AssSection.Style
    "[events]" -> AssSection.Event
    else -> AssSection.Other
}

private fun String.assFormatFieldIndex(name: String): Int? = substringAfter(':')
    .split(',')
    .indexOfFirst { field -> field.trim().equals(name, ignoreCase = true) }
    .takeIf { index -> index >= 0 }

private fun String.normalizeAssStyleFont(fontNameIndex: Int): String {
    val headerEndIndex = indexOf(':')
    if (headerEndIndex < 0) return this

    val prefix = substring(0, headerEndIndex + 1)
    val fields = substring(headerEndIndex + 1).split(',').toMutableList()
    if (fontNameIndex !in fields.indices) return this

    val fontField = fields[fontNameIndex]
    val fontName = fontField.trim().trim('"')
    if (fontName.lowercase(Locale.US) !in ASS_FONT_NAMES_REQUIRING_FALLBACK) return this

    val leadingWhitespace = fontField.takeWhile(Char::isWhitespace)
    val trailingWhitespace = fontField.takeLastWhile(Char::isWhitespace)
    fields[fontNameIndex] = leadingWhitespace + ASS_ANDROID_FALLBACK_FONT + trailingWhitespace
    return prefix + fields.joinToString(",")
}

private fun String.normalizeAssEventText(textIndex: Int): String {
    val headerEndIndex = indexOf(':')
    if (headerEndIndex < 0) return this

    val prefix = substring(0, headerEndIndex + 1)
    return prefix + substring(headerEndIndex + 1).normalizeAssDialogueFields(textIndex)
}

internal fun String.normalizeAssDialogueFields(textIndex: Int): String {
    val fields = split(',', limit = textIndex + 1).toMutableList()
    if (textIndex !in fields.indices) return this

    fields[textIndex] = fields[textIndex].normalizeAssDialogueText()
    return fields.joinToString(",")
}

internal fun String.normalizeAssDialogueText(): String = normalizeAssInlineFonts()
    .widenLatinSpaces()

private fun String.normalizeAssInlineFonts(): String = ASS_INLINE_FONT_REGEX.replace(this) { match ->
    val fontName = match.groupValues[1].trim()
    if (fontName.lowercase(Locale.US) in ASS_FONT_NAMES_REQUIRING_FALLBACK) {
        "\\fn$ASS_ANDROID_FALLBACK_FONT"
    } else {
        match.value
    }
}

private fun String.widenLatinSpaces(): String = buildString(length) {
    var isInOverrideBlock = false
    for (index in this@widenLatinSpaces.indices) {
        val char = this@widenLatinSpaces[index]
        when (char) {
            '{' -> isInOverrideBlock = true
            '}' -> isInOverrideBlock = false
        }
        if (char == ' ' && !isInOverrideBlock && hasLatinNeighbor(index)) {
            append(' ')
            append(' ')
        } else {
            append(char)
        }
    }
}

private fun String.hasLatinNeighbor(index: Int): Boolean = previousVisibleChar(index)?.isLatinLetterOrDigit() == true &&
    nextVisibleChar(index)?.isLatinLetterOrDigit() == true

private fun String.previousVisibleChar(index: Int): Char? {
    var currentIndex = index - 1
    while (currentIndex >= 0 && this[currentIndex].isWhitespace()) currentIndex--
    return getOrNull(currentIndex)
}

private fun String.nextVisibleChar(index: Int): Char? {
    var currentIndex = index + 1
    while (currentIndex < length && this[currentIndex].isWhitespace()) currentIndex++
    return getOrNull(currentIndex)
}

private fun Char.isLatinLetterOrDigit(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

private fun isAssEventLine(line: String): Boolean = line.startsWith("Dialogue:", ignoreCase = true) ||
    line.startsWith("Comment:", ignoreCase = true)

private fun String.detectLineSeparator(): String = when {
    contains("\r\n") -> "\r\n"
    contains("\r") -> "\r"
    else -> "\n"
}

private fun normalizedAssFontFileName(label: String, uri: Uri): String {
    val baseName = label.ifBlank { "subtitle" }.substringBeforeLast('.', missingDelimiterValue = label.ifBlank { "subtitle" })
    val extension = label.substringAfterLast('.', missingDelimiterValue = "ass")
    val cacheKey = uri.toString().hashCode().toUInt().toString(radix = 16)
    return "$baseName-$cacheKey.ass-font.$extension"
}

fun Bundle.getParcelableUriArray(key: String): ArrayList<Uri>? = BundleCompat.getParcelableArrayList(this, key, Uri::class.java)

private const val SUBTITLE_CONFIG_TAG = "SubtitleConfig"
private const val ASS_ANDROID_FALLBACK_FONT = "Roboto"

private enum class AssSection {
    Style,
    Event,
    Other,
}

private val ASS_INLINE_FONT_REGEX = Regex("\\\\fn([^\\\\}]+)")

private val NETWORK_URI_SCHEMES = setOf("http", "https", "ftp")

private val ASS_STYLE_SECTION_HEADERS = setOf(
    "[v4 styles]",
    "[v4+ styles]",
)

private val ASS_FONT_NAMES_REQUIRING_FALLBACK = setOf(
    "arial",
    "calibri",
    "cambria",
    "candara",
    "comic sans ms",
    "consolas",
    "courier new",
    "georgia",
    "helvetica",
    "microsoft sans serif",
    "ms sans serif",
    "segoe ui",
    "tahoma",
    "times new roman",
    "trebuchet ms",
    "verdana",
)

suspend fun Context.getPath(uri: Uri): String? = withContext(Dispatchers.IO) {
    if (uri.scheme == "file") {
        return@withContext uri.path
    }
    return@withContext null
}
