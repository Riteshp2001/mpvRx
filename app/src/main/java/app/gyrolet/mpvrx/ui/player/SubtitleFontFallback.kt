/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.graphics.Paint
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.lang.UScript
import android.icu.util.ULocale
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.fonts.FontCoverageMismatchException
import app.gyrolet.mpvrx.domain.fonts.GoogleFontFamily
import app.gyrolet.mpvrx.domain.fonts.GoogleFontsRepository
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A kind of font that subtitle text needs and the device lacks. */
internal sealed interface FallbackFontNeed {
  data object Emoji : FallbackFontNeed

  data object Math : FallbackFontNeed

  data object Music : FallbackFontNeed

  data object Symbols : FallbackFontNeed

  /** A writing system, by its Google Fonts subset name, e.g. `ol-chiki`. */
  data class Script(val subset: String) : FallbackFontNeed
}

/**
 * Finds the characters in subtitle text that neither Android's fonts nor the downloaded fallback
 * fonts can draw, grouped by the kind of font that would. Not thread-safe.
 */
internal class SubtitleGlyphCoverage {
  private val paint = Paint()
  private val systemGlyphs = HashMap<Int, Boolean>()

  fun missing(
    text: CharSequence,
    fallbackCoverage: Set<Int>,
  ): Map<FallbackFontNeed, Set<Int>> {
    val missing = HashMap<FallbackFontNeed, MutableSet<Int>>()
    var index = 0
    while (index < text.length) {
      val codePoint = Character.codePointAt(text, index)
      index += Character.charCount(codePoint)
      if (codePoint < 0x80 || codePoint in fallbackCoverage || !hasOwnGlyph(codePoint)) continue
      val next = if (index < text.length) Character.codePointAt(text, index) else -1
      val need =
        when {
          // libass draws only outlines, so even emoji that Android shows need a monochrome font.
          isEmoji(codePoint, next) -> FallbackFontNeed.Emoji
          hasSystemGlyph(codePoint) -> null
          else -> needFor(codePoint)
        } ?: continue
      missing.getOrPut(need) { HashSet() }.add(codePoint)
    }
    return missing
  }

  /** The fonts in /system/fonts and /product/fonts, which fontconfig also hands to libass. */
  private fun hasSystemGlyph(codePoint: Int): Boolean =
    systemGlyphs.getOrPut(codePoint) { paint.hasGlyph(String(Character.toChars(codePoint))) }

  /** Spaces, controls and combining marks have no glyph of their own to look for. */
  private fun hasOwnGlyph(codePoint: Int): Boolean =
    when (Character.getType(codePoint).toByte()) {
      Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
      Character.UNASSIGNED, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR,
      Character.PARAGRAPH_SEPARATOR, Character.NON_SPACING_MARK, Character.ENCLOSING_MARK,
      Character.COMBINING_SPACING_MARK,
      -> false
      else -> codePoint != REPLACEMENT_CHARACTER
    }

  private fun isEmoji(
    codePoint: Int,
    next: Int,
  ): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_PRESENTATION) ||
        (next == EMOJI_VARIATION_SELECTOR && UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI))
    } else {
      codePoint in 0x1F000..0x1FAFF || (next == EMOJI_VARIATION_SELECTOR && codePoint in 0x2000..0x2BFF)
    }

  private fun needFor(codePoint: Int): FallbackFontNeed? =
    when (val script = UScript.getScript(codePoint)) {
      UScript.COMMON, UScript.INHERITED -> symbolNeed(codePoint)
      UScript.UNKNOWN -> null
      else -> FallbackFontNeed.Script(googleFontsSubset(script))
    }

  private fun symbolNeed(codePoint: Int): FallbackFontNeed =
    when (Character.UnicodeBlock.of(codePoint)) {
      Character.UnicodeBlock.MATHEMATICAL_OPERATORS,
      Character.UnicodeBlock.SUPPLEMENTAL_MATHEMATICAL_OPERATORS,
      Character.UnicodeBlock.MISCELLANEOUS_MATHEMATICAL_SYMBOLS_A,
      Character.UnicodeBlock.MISCELLANEOUS_MATHEMATICAL_SYMBOLS_B,
      Character.UnicodeBlock.MATHEMATICAL_ALPHANUMERIC_SYMBOLS,
      -> FallbackFontNeed.Math
      Character.UnicodeBlock.MUSICAL_SYMBOLS,
      Character.UnicodeBlock.BYZANTINE_MUSICAL_SYMBOLS,
      Character.UnicodeBlock.ANCIENT_GREEK_MUSICAL_NOTATION,
      -> FallbackFontNeed.Music
      else -> FallbackFontNeed.Symbols
    }

  private companion object {
    const val REPLACEMENT_CHARACTER = 0xFFFD
    const val EMOJI_VARIATION_SELECTOR = 0xFE0F
  }
}

/** Google Fonts names its subsets after Unicode scripts: Ol_Chiki is `ol-chiki`. */
internal fun googleFontsSubset(script: Int): String {
  val subset = UScript.getName(script).lowercase(Locale.ROOT).replace('_', '-')
  // One subset covers both Meroitic scripts.
  return if (subset.startsWith("meroitic")) "meroitic" else subset
}

/** Sample text in the script [language] is usually written in, e.g. Ol Chiki for Santali (`sat`). */
internal fun languageSampleText(language: String): String? {
  val script =
    runCatching { ULocale.addLikelySubtags(ULocale.forLanguageTag(language)).script }.getOrNull()
  if (script.isNullOrEmpty()) return null
  val code = UScript.getCodeFromName(script)
  if (code == UScript.INVALID_CODE) return null
  return UScript.getSampleString(code).takeIf(String::isNotEmpty)
}

/**
 * Downloads a Google font when subtitles use a script, emoji or symbols this device cannot draw,
 * then has mpv reload its subtitle fonts. The fonts go to the fallback folder that mpvRx's
 * fonts.conf adds, where libass looks for any character the subtitle font lacks.
 *
 * It checks the language of each selected track and the text of external subtitle files as soon
 * as they are selected, then every line shown, for tracks with neither.
 */
class SubtitleFontFallbackController(
  private val context: Context,
  private val fonts: GoogleFontsRepository,
  private val preferences: SubtitlesPreferences,
  private val subtitleTracks: StateFlow<List<TrackNode>>,
  private val showText: (String) -> Unit,
) {
  private sealed interface Work {
    data class Text(val text: String) : Work

    data class Track(val track: TrackNode) : Work
  }

  // Confined to the single consumer coroutine in watchSubtitles().
  private val coverage = SubtitleGlyphCoverage()
  private var fallbackCoverage: MutableSet<Int>? = null
  private val installedFamilies = HashSet<String>()

  /** What a family that was downloaded but not kept maps, so it is tried again only if it helps. */
  private val rejectedCoverage = HashMap<String, Set<Int>>()
  private val failedAt = HashMap<String, Long>()
  private val scannedSources = HashSet<String>()
  private var catalog: List<GoogleFontFamily>? = null
  private var catalogFailedAt: Long? = null

  fun start(scope: CoroutineScope) {
    scope.launch {
      preferences.autoDownloadFonts.changes().collectLatest { enabled ->
        if (enabled) watchSubtitles()
      }
    }
  }

  private suspend fun watchSubtitles() =
    coroutineScope {
      val work = Channel<Work>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
      launch {
        subtitleTracks
          .map { tracks -> tracks.filter { it.selected == true && it.image != true } }
          .distinctUntilChanged()
          .collect { selected -> selected.forEach { work.trySend(Work.Track(it)) } }
      }
      launch {
        merge(PlaybackSession.propString["sub-text"], PlaybackSession.propString["secondary-sub-text"])
          .filterNotNull()
          .filter { text -> text.any { it.code >= 0x80 } }
          .collect { work.trySend(Work.Text(it)) }
      }
      withContext(Dispatchers.IO) {
        for (item in work) {
          when (item) {
            is Work.Text -> handle(item.text)
            is Work.Track -> trackSamples(item.track).forEach { handle(it) }
          }
        }
      }
    }

  /** What a selected track will show before its first line: its language's script and file. */
  private fun trackSamples(track: TrackNode): List<String> =
    buildList {
      track.lang?.takeIf(String::isNotBlank)?.let { language ->
        if (scannedSources.add("lang:$language")) languageSampleText(language)?.let(::add)
      }
      val file = track.externalFilename?.takeIf { track.external == true } ?: return@buildList
      if (scannedSources.add("file:$file")) readSubtitleFile(file)?.let(::add)
    }

  private suspend fun handle(text: String) {
    val covered =
      fallbackCoverage ?: HashSet<Int>().also { codePoints ->
        fonts.fallbackFonts().forEach { (family, mapped) ->
          installedFamilies += family
          codePoints += mapped
        }
        fallbackCoverage = codePoints
      }
    val missing = coverage.missing(text, covered)
    // mpv reaches the fallback fonts only through fontconfig.
    if (missing.isEmpty() || PlaybackSession.getPropertyString("sub-font-provider") == "none") return

    val added = mutableListOf<String>()
    for ((need, codePoints) in missing) {
      var remaining = codePoints
      for (family in candidates(need)) {
        if (remaining.isEmpty()) break
        if (!shouldTry(family, remaining)) continue
        fonts
          .installFallbackFont(family, remaining)
          .onSuccess { mapped ->
            installedFamilies += family
            rejectedCoverage -= family
            covered += mapped
            remaining = remaining - mapped
            added += family
          }.onFailure { error ->
            if (error is FontCoverageMismatchException) {
              rejectedCoverage[family] = error.codePoints
            } else {
              failedAt[family] = SystemClock.elapsedRealtime()
            }
            Log.w(TAG, "Could not add subtitle fallback font $family", error)
          }
      }
    }
    if (added.isNotEmpty() && PlaybackSession.reloadSubtitleFonts()) {
      showText(context.getString(R.string.subtitle_font_added, added.joinToString()))
    }
  }

  private fun shouldTry(
    family: String,
    needed: Set<Int>,
  ): Boolean {
    if (family in installedFamilies) return false
    rejectedCoverage[family]?.let { mapped -> if (needed.none(mapped::contains)) return false }
    val failed = failedAt[family] ?: return true
    return SystemClock.elapsedRealtime() - failed > RETRY_AFTER_MS
  }

  private suspend fun candidates(need: FallbackFontNeed): List<String> =
    when (need) {
      FallbackFontNeed.Emoji -> listOf("Noto Emoji")
      FallbackFontNeed.Math -> listOf("Noto Sans Math")
      FallbackFontNeed.Music -> listOf("Noto Music")
      FallbackFontNeed.Symbols -> listOf("Noto Sans Symbols 2", "Noto Sans Symbols", "Noto Sans Math")
      is FallbackFontNeed.Script -> scriptFamilies(need.subset)
    }

  /** Noto first: it covers nearly every script, and its per-script families are small. */
  private suspend fun scriptFamilies(subset: String): List<String> {
    val families = catalog ?: loadCatalog() ?: return emptyList()
    return families
      .filter { subset in it.subsets }
      .sortedWith(compareBy<GoogleFontFamily>({ notoRank(it.family) }, { it.subsets.size }, { it.popularity }))
      .take(MAX_SCRIPT_CANDIDATES)
      .map(GoogleFontFamily::family)
  }

  private suspend fun loadCatalog(): List<GoogleFontFamily>? {
    catalogFailedAt?.let { if (SystemClock.elapsedRealtime() - it < RETRY_AFTER_MS) return null }
    return fonts
      .loadCatalog()
      .onFailure { catalogFailedAt = SystemClock.elapsedRealtime() }
      .getOrNull()
      ?.also { catalog = it }
  }

  private fun notoRank(family: String): Int =
    when {
      family.startsWith("Noto Sans ") -> 0
      family.startsWith("Noto Serif ") -> 1
      family.startsWith("Noto ") -> 2
      else -> 3
    }

  /** A local or content:// subtitle file as text, or null when it is not UTF-8 or UTF-16. */
  private fun readSubtitleFile(path: String): String? =
    runCatching {
      val input: InputStream =
        when {
          path.startsWith("/") -> File(path).takeIf(File::isFile)?.inputStream()
          path.startsWith("file://") -> Uri.parse(path).path?.let { File(it) }?.takeIf(File::isFile)?.inputStream()
          path.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(path))
          else -> null
        } ?: return@runCatching null
      val bytes = input.use { it.readAtMost(MAX_SCANNED_FILE_BYTES) }
      val text =
        when {
          bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, Charsets.UTF_16)
          bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, Charsets.UTF_16)
          else -> String(bytes, Charsets.UTF_8)
        }
      // A legacy code page decodes to replacement characters; its script is not knowable here.
      text.takeUnless { it.contains('\uFFFD') }
    }.getOrNull()

  private fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
      val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
      if (read < 0) break
      out.write(buffer, 0, read)
    }
    return out.toByteArray()
  }

  private companion object {
    const val TAG = "SubtitleFontFallback"
    const val RETRY_AFTER_MS = 10 * 60 * 1000L
    const val MAX_SCRIPT_CANDIDATES = 2
    const val MAX_SCANNED_FILE_BYTES = 4 * 1024 * 1024
  }
}
