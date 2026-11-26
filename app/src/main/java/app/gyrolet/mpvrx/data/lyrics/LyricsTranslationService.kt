/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.data.lyrics

import android.os.Build
import android.util.LruCache
import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.SyncedLine
import app.gyrolet.mpvrx.network.awaitResponse
import app.gyrolet.mpvrx.utils.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class TranslationResult(
  val translation: String,
  val romanization: String? = null,
  val detectedSourceLang: String? = null,
  val isResolved: Boolean = true,
)

data class LyricsTranslationOutcome(
  val lyrics: Lyrics,
  val resolvedLineCount: Int,
  val requestedLineCount: Int,
) {
  val isSuccessful: Boolean get() = resolvedLineCount > 0
  val isComplete: Boolean get() = requestedLineCount > 0 && resolvedLineCount >= requestedLineCount
}

private data class TranslatedLines<T>(
  val lines: List<T>,
  val resolvedLineCount: Int,
  val requestedLineCount: Int,
)

data class SupportedLanguage(
  val code: String,
  val displayName: String,
  val isRomanization: Boolean = false,
  val subtitle: String? = null,
)

object LyricsLanguageOptions {
  val ALL_LANGUAGES = listOf(
    SupportedLanguage("en", "English"),
    SupportedLanguage(
      "romaji",
      "Romaji / Romanized (Pinyin, Academic)",
      isRomanization = true,
      subtitle = "Pronunciation / Romaji / Pinyin",
    ),
    SupportedLanguage(
      "hinglish_casual",
      "Hinglish (Colloquial)",
      isRomanization = true,
      subtitle = "Casual spelling for Hindi, Punjabi, Bengali & more",
    ),
    SupportedLanguage("hi", "Hindi (हिन्दी)"),
    SupportedLanguage("es", "Spanish (Español)"),
    SupportedLanguage("fr", "French (Français)"),
    SupportedLanguage("de", "German (Deutsch)"),
    SupportedLanguage("ja", "Japanese (日本語)"),
    SupportedLanguage("ko", "Korean (한국어)"),
    SupportedLanguage("zh-CN", "Chinese (Simplified)"),
    SupportedLanguage("it", "Italian (Italiano)"),
    SupportedLanguage("pt", "Portuguese (Português)"),
    SupportedLanguage("ru", "Russian (Русский)"),
    SupportedLanguage("ar", "Arabic (العربية)"),
    SupportedLanguage("bn", "Bengali (বাংলা)"),
    SupportedLanguage("ta", "Tamil (தமிழ்)"),
    SupportedLanguage("te", "Telugu (తెలుగు)"),
    SupportedLanguage("mr", "Marathi (मराठी)"),
    SupportedLanguage("pa", "Punjabi (ਪੰਜਾਬੀ)"),
    SupportedLanguage("ur", "Urdu (اردو)"),
  )

  fun getDisplayName(code: String): String {
    if (code.equals("hinglish", ignoreCase = true)) return "Romaji / Romanized"
    return ALL_LANGUAGES.firstOrNull { it.code.equals(code, ignoreCase = true) }?.displayName ?: code.uppercase()
  }
}

class LyricsTranslationService(
  okHttpClient: OkHttpClient,
) {
  companion object {
    private const val TAG = "LyricsTranslationService"
    private const val CHUNK_SIZE = 20
    private const val MAX_CONCURRENT_TRANSLATION_REQUESTS = 4
    private const val USER_AGENT =
      "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val RESULT_CONTAINER_REGEX =
      Regex("""<div[^>]*class=["']result-container["'][^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val BRACKET_PATTERN =
      Regex("""\[\s*(\d+)\s*\]\s*(.*?)(?=\[\s*\d+\s*\]|$)""", RegexOption.DOT_MATCHES_ALL)
    private val NUMERIC_ENTITY_REGEX = Regex("""&#(x?[0-9a-fA-F]+);""")

    /** "[1]" as any engine may have written it: ASCII or full-width brackets, any script's digits. */
    private val LOCALISED_MARKER_REGEX = Regex("""[\[\uFF3B\u3010]\s*(\p{Nd}+)\s*[\]\uFF3D\u3011]""")
  }

  // Fast timeout for translation calls (5s connect / 8s read)
  private val client = okHttpClient.newBuilder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .build()

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }
  private val requestSemaphore = Semaphore(MAX_CONCURRENT_TRANSLATION_REQUESTS)
  private val translationCache = LruCache<String, LyricsTranslationOutcome>(64)

  suspend fun translateLyrics(
    lyrics: Lyrics,
    targetLanguage: String,
    cacheKey: String? = null,
  ): LyricsTranslationOutcome = withContext(Dispatchers.IO) {
    val requestedLineCount = lyrics.synced?.count { it.line.isNotBlank() } ?: lyrics.plain?.count { it.isNotBlank() } ?: 0
    if (!lyrics.isValid()) return@withContext LyricsTranslationOutcome(lyrics, 0, requestedLineCount)

    val key = cacheKey?.let { "${it}_${lyrics.hashCode()}_$targetLanguage" }
    if (key != null) {
      translationCache.get(key)?.let { return@withContext it }
    }

    try {
      if (!lyrics.synced.isNullOrEmpty()) {
        val translatedSynced = translateSyncedLines(lyrics.synced, targetLanguage)
        val outcome = LyricsTranslationOutcome(
          lyrics = lyrics.copy(synced = translatedSynced.lines),
          resolvedLineCount = translatedSynced.resolvedLineCount,
          requestedLineCount = translatedSynced.requestedLineCount,
        )
        if (key != null && outcome.isComplete) translationCache.put(key, outcome)
        return@withContext outcome
      } else if (!lyrics.plain.isNullOrEmpty()) {
        val translatedPlain = translatePlainLines(lyrics.plain, targetLanguage)
        val outcome = LyricsTranslationOutcome(
          lyrics = lyrics.copy(plain = translatedPlain.lines),
          resolvedLineCount = translatedPlain.resolvedLineCount,
          requestedLineCount = translatedPlain.requestedLineCount,
        )
        if (key != null && outcome.isComplete) translationCache.put(key, outcome)
        return@withContext outcome
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Log.e(TAG, "Error translating lyrics to $targetLanguage: ${e.message}", e)
    }

    LyricsTranslationOutcome(lyrics, 0, requestedLineCount)
  }

  private suspend fun translateSyncedLines(
    lines: List<SyncedLine>,
    targetLanguage: String,
  ): TranslatedLines<SyncedLine> {
    // The backing voices go in the same batch, after the leads, so they are
    // translated or romanised with the lead they belong to rather than left in
    // the original script under a translated line.
    val backingIndices = lines.indices.filter { lines[it].background != null }
    val textsToTranslate = lines.map { it.line } + backingIndices.map { lines[it].background!!.line }
    val translations = batchTranslate(textsToTranslate, targetLanguage)
    val romanizing = targetLanguage == "romaji" || targetLanguage == "hinglish" || targetLanguage == "hinglish_casual"

    fun SyncedLine.withResult(res: TranslationResult?): SyncedLine {
      if (res == null) return this
      val translatedText = res.translation.takeIf(String::isNotBlank)
      val romanizedText = res.romanization?.takeIf(String::isNotBlank)
      return copy(
        translation = if (romanizing) romanizedText ?: translatedText else translatedText,
        romanization = romanizedText,
      )
    }

    val backingResults = backingIndices.withIndex().associate { (n, lineIndex) -> lineIndex to translations.getOrNull(lines.size + n) }
    val translatedLines = lines.mapIndexed { index, line ->
      val translated = line.withResult(translations.getOrNull(index))
      val backing = line.background
      if (backing == null) translated else translated.copy(background = backing.withResult(backingResults[index]))
    }
    return TranslatedLines(
      lines = translatedLines,
      resolvedLineCount = translations.count { it.isResolved && it.translation.isNotBlank() },
      requestedLineCount = textsToTranslate.count(String::isNotBlank),
    )
  }

  private suspend fun translatePlainLines(
    lines: List<String>,
    targetLanguage: String,
  ): TranslatedLines<String> {
    val translations = batchTranslate(lines, targetLanguage)
    val translatedLines = lines.mapIndexed { index, original ->
      val res = translations.getOrNull(index)
      if (res != null) {
        val text = if (targetLanguage == "romaji" || targetLanguage == "hinglish" || targetLanguage == "hinglish_casual") {
          res.romanization ?: res.translation
        } else {
          res.translation
        }
        if (text.isNotBlank() && !text.equals(original.trim(), ignoreCase = true)) {
          "$original\n$text"
        } else {
          original
        }
      } else {
        original
      }
    }
    return TranslatedLines(
      lines = translatedLines,
      resolvedLineCount = translations.count { it.isResolved && it.translation.isNotBlank() },
      requestedLineCount = lines.count(String::isNotBlank),
    )
  }

  private suspend fun batchTranslate(
    texts: List<String>,
    targetLang: String,
  ): List<TranslationResult> = coroutineScope {
    if (texts.isEmpty()) return@coroutineScope emptyList()

    // Special handling for Romaji / Hinglish / Romanized (formal, diacritic-preserving)
    if (targetLang.equals("romaji", ignoreCase = true) || targetLang.equals("hinglish", ignoreCase = true)) {
      return@coroutineScope handleRomajiTransliteration(texts)
    }

    // Special handling for casual Hinglish (Hindi, Punjabi/Gurmukhi, Bengali, Tamil, etc. -> everyday Latin spelling)
    if (targetLang.equals("hinglish_casual", ignoreCase = true)) {
      return@coroutineScope handleHinglishCasualTransliteration(texts)
    }

    val chunks = texts.chunked(CHUNK_SIZE)
    val deferredChunks = chunks.map { chunk ->
      async(Dispatchers.IO) {
        requestSemaphore.withPermit { translateChunk(chunk, targetLang) }
      }
    }

    deferredChunks.awaitAll().flatten()
  }

  /**
   * Handles Romaji / Hinglish / Romanized pronunciation requests.
   * If the text is already in Latin script (e.g. Hinglish or Romaji), keeps the original pronunciation.
   * If the text is in non-Latin script (Devanagari, Kana/Kanji, Hangul, Cyrillic, etc.),
   * fetches accurate Romanization (Romaji for Japanese, Pinyin for Chinese, etc.).
   */
  private suspend fun handleRomajiTransliteration(texts: List<String>): List<TranslationResult> = coroutineScope {
    // Per line, not per batch: a Hindi song with a few English lines must not skip the
    // Devanagari ones just because the batch is mostly Latin (or the reverse).
    val latinFlags = texts.map { isLatinLine(it) }
    val nonLatinIndices = latinFlags.withIndex().filter { !it.value }.map { it.index }

    val romanizedByIndex: Map<Int, TranslationResult> =
      if (nonLatinIndices.isEmpty()) {
        emptyMap()
      } else {
        val deferredChunks = nonLatinIndices.map { texts[it] }.chunked(CHUNK_SIZE).map { chunk ->
          async(Dispatchers.IO) {
            requestSemaphore.withPermit { romanizeChunk(chunk) }
          }
        }
        nonLatinIndices.zip(deferredChunks.awaitAll().flatten()).toMap()
      }

    texts.mapIndexed { index, line ->
      if (latinFlags[index]) {
        val trimmed = line.trim()
        TranslationResult(translation = trimmed, romanization = trimmed)
      } else {
        romanizedByIndex[index] ?: TranslationResult(translation = line.trim(), isResolved = false)
      }
    }
  }

  /**
   * Handles casual "Hinglish" style transliteration — the way lyrics are typically typed on
   * WhatsApp/YouTube comments (e.g. "seene", "baandhi") rather than academic IAST-style
   * romanization with diacritics (e.g. "sīnē", "bāṁdhī"). Works for any Indic source script
   * (Devanagari, Gurmukhi/Punjabi, Bengali, Tamil, Telugu, Marathi, Urdu, etc.) because it
   * post-processes the same authentic Google romanization output used for "Romaji", rather
   * than needing a separate per-script mapping table.
   *
   * Classification is done PER LINE (not once for the whole batch) so that code-switched
   * lyrics — a very common pattern in Bollywood/Punjabi songs that mix English lines with
   * Devanagari/Gurmukhi lines — route each line correctly instead of one script's lines
   * accidentally being treated as the other's.
   */
  private suspend fun handleHinglishCasualTransliteration(texts: List<String>): List<TranslationResult> = coroutineScope {
    // Determine per-line script so a mixed English + Devanagari/Gurmukhi song doesn't get
    // misclassified as a whole. Latin lines are left completely untouched (no diacritic
    // stripping) so English contractions like "I'm" / "can't" are never mangled.
    val latinFlags = texts.map { isLatinLine(it) }
    val nonLatinIndices = latinFlags.withIndex().filter { !it.value }.map { it.index }

    // For Devanagari lines specifically, pre-apply schwa deletion BEFORE sending to Google's
    // romanization endpoint. Google's raw dt=rm output keeps every akshara's inherent 'a'
    // vowel (e.g. "हम" -> "hama", "करने" -> "karane"), which reads nothing like how Hindi is
    // actually spoken/typed casually ("ham", "karne"). Inserting an explicit virama at the
    // positions where the schwa should be silent lets Google's own (already-correct)
    // consonant-cluster transliteration produce the right result for us.
    val preprocessed = nonLatinIndices.map { idx ->
      val line = texts[idx]
      if (isDevanagariLine(line)) applyHindiSchwaDeletion(line) else line
    }

    val romanizedByIndex: Map<Int, TranslationResult> =
      if (nonLatinIndices.isEmpty()) {
        emptyMap()
      } else {
        val chunks = preprocessed.chunked(CHUNK_SIZE)
        val deferredChunks = chunks.map { chunk ->
          async(Dispatchers.IO) {
            requestSemaphore.withPermit { romanizeChunk(chunk) }
          }
        }
        val romanized = deferredChunks.awaitAll().flatten()
        nonLatinIndices.zip(romanized).toMap()
      }

    texts.mapIndexed { index, line ->
      if (latinFlags[index]) {
        val trimmed = line.trim()
        TranslationResult(translation = trimmed, romanization = trimmed)
      } else {
        val res = romanizedByIndex[index]
        val source = res?.romanization ?: res?.translation ?: line
        val casual = casualizeHinglish(source)
        TranslationResult(
          translation = casual,
          romanization = casual,
          detectedSourceLang = res?.detectedSourceLang,
        )
      }
    }
  }

  /** True if the line contains at least one Devanagari script character. */
  private fun isDevanagariLine(text: String): Boolean {
    return text.any { it.code in 0x0900..0x097F }
  }

  private object Devanagari {
    val CONSONANTS = (0x0915..0x0939).map { it.toChar() }.toSet() + (0x0958..0x095F).map { it.toChar() }.toSet()
    const val VIRAMA = '\u094D'
    val MATRAS = setOf(
      '\u093E', '\u093F', '\u0940', '\u0941', '\u0942', '\u0943', '\u0944',
      '\u0945', '\u0946', '\u0947', '\u0948', '\u0949', '\u094A', '\u094B', '\u094C',
      '\u0955', '\u0956', '\u0957', '\u0962', '\u0963',
    )
    val NASAL_MARKS = setOf('\u0901', '\u0902', '\u0903') // candrabindu, anusvara, visarga
  }

  private data class Syllable(
    val consonantEndIndex: Int, // index right after the consonant cluster (where a virama would be inserted)
    val clusterLength: Int, // length in chars of the consonant cluster (1 = single consonant, 3+ = conjunct)
    val isImplicitSchwa: Boolean, // true if this syllable carries the unwritten inherent 'a' vowel
  )

  /**
   * Applies a simplified Hindi schwa-deletion heuristic directly on Devanagari text, by
   * inserting an explicit virama (्) at akshara positions where the inherent 'a' vowel is
   * silent in natural speech/casual typing but isn't marked in standard spelling.
   *
   * Two rules (the two dominant, well-documented patterns of Hindi schwa deletion):
   *  1. Word-final schwa deletion: the last syllable's inherent vowel is (almost) always
   *     silent, e.g. "हम" (ham) not "hama", "मर" (mar) not "mara".
   *  2. Medial schwa deletion: a non-initial, non-final syllable's inherent vowel is deleted
   *     when the syllable right after it is "light" (a single consonant, not a conjunct
   *     cluster) — e.g. "करने" -> "kar" + "ne" (deleting र's schwa) instead of "karane".
   *
   * This intentionally does NOT touch the first syllable of a word (word-initial schwa is
   * essentially never deleted in Hindi) and never deletes two adjacent syllables' schwas
   * (to avoid producing an unpronounceable triple-consonant cluster).
   *
   * This is a heuristic, not a complete linguistic model — Hindi schwa deletion has edge
   * cases and exceptions — but it fixes the overwhelmingly common patterns that make Google's
   * raw (schwa-retaining) romanization read as academic/broken casual Hinglish.
   */
  private fun applyHindiSchwaDeletion(text: String): String {
    // Process each maximal run of Devanagari characters as one "word" so syllable position
    // (first/medial/last) is computed relative to that word, leaving spaces, punctuation, and
    // any non-Devanagari text completely untouched.
    val result = StringBuilder()
    var i = 0
    while (i < text.length) {
      if (text[i].code in 0x0900..0x097F) {
        val start = i
        while (i < text.length && text[i].code in 0x0900..0x097F) i++
        result.append(applyHindiSchwaDeletionToWord(text.substring(start, i)))
      } else {
        result.append(text[i])
        i++
      }
    }
    return result.toString()
  }

  private fun applyHindiSchwaDeletionToWord(word: String): String {
    val syllables = mutableListOf<Syllable>()
    var i = 0
    while (i < word.length) {
      val c = word[i]
      if (c in Devanagari.CONSONANTS) {
        val start = i
        i++
        // Consume virama+consonant chains (conjuncts, e.g. र्त, क्ष)
        while (i + 1 < word.length && word[i] == Devanagari.VIRAMA && word[i + 1] in Devanagari.CONSONANTS) {
          i += 2
        }
        val consonantEnd = i
        val clusterLength = consonantEnd - start

        if (i < word.length && word[i] == Devanagari.VIRAMA) {
          // Already explicitly dead (spelled with a trailing virama) — not a schwa candidate.
          i++
          syllables.add(Syllable(consonantEnd, clusterLength, isImplicitSchwa = false))
          continue
        }

        var hasExplicitVowel = false
        if (i < word.length && word[i] in Devanagari.MATRAS) {
          hasExplicitVowel = true
          i++
        }
        var hasNasal = false
        if (i < word.length && word[i] in Devanagari.NASAL_MARKS) {
          hasNasal = true
          i++
        }
        // A nasalized implicit vowel (e.g. हँसना) is usually still voiced, so don't treat it
        // as a silent-schwa candidate.
        val isImplicitSchwa = !hasExplicitVowel && !hasNasal
        syllables.add(Syllable(consonantEnd, clusterLength, isImplicitSchwa))
      } else {
        // Independent vowel, punctuation, digit, etc. — not part of consonant-syllable analysis
        i++
      }
    }

    if (syllables.isEmpty()) return word

    val n = syllables.size
    val toDelete = BooleanArray(n)
    for (idx in 0 until n) {
      val syl = syllables[idx]
      if (!syl.isImplicitSchwa) continue
      if (idx == n - 1) {
        if (n > 1) toDelete[idx] = true // Rule 1: word-final schwa deletion
      } else if (idx in 1 until n - 1) {
        val next = syllables[idx + 1]
        if (next.clusterLength == 1) toDelete[idx] = true // Rule 2: before a "light" syllable
      }
      // idx == 0 (word-initial): never deleted
    }
    // Safety: never delete two adjacent syllables' schwas (would leave an unpronounceable
    // consonant pile-up) — keep the later one.
    for (idx in 0 until n - 1) {
      if (toDelete[idx] && toDelete[idx + 1]) toDelete[idx] = false
    }

    // Insert virama at each marked position, right-to-left so earlier indices stay valid.
    val out = StringBuilder(word)
    for (idx in n - 1 downTo 0) {
      if (toDelete[idx]) out.insert(syllables[idx].consonantEndIndex, Devanagari.VIRAMA)
    }
    return out.toString()
  }

  /**
   * Converts formal/academic diacritic romanization (IAST-style, as returned by Google's
   * romanization endpoint) into everyday casual Hinglish spelling.
   *
   * IMPORTANT: only call this on already-romanized Latin text (i.e. Google's dt=rm output),
   * never on native-script text (Devanagari/Gurmukhi/etc.) — Indic vowel signs and virama
   * share the same Unicode "combining mark" category as Latin diacritics, so running this on
   * native script would corrupt it (e.g. "वाले" -> "वाल").
   *
   * Strategy:
   * 1. Special-case sibilants (ś/ṣ -> "sh") since Unicode decomposition alone would strip
   *    them down to plain "s", losing the "sh" sound.
   * 2. Double the long vowels (ā->aa, ī->ee, ū->oo) — this is the standard WhatsApp/YouTube
   *    Hinglish convention for marking a long vowel (e.g. "vaale" not "vale", "seene" not
   *    "sine") and disambiguates them from the short vowels they'd otherwise collapse into.
   * 3. Convert nasalization marks (candrabindu/anusvara, however Google represents them —
   *    precomposed ṁ/ṃ, or a combining tilde/candrabindu riding on the vowel) into a trailing
   *    "n", matching how nasalized vowels are conventionally typed in casual Hinglish
   *    (e.g. "hain", "nahin", "jaenge").
   * 4. Unicode-normalize (NFD) and strip any remaining combining diacritical marks (dots,
   *    etc.) — this generically handles ē->e, ō->o, ṅ/ñ/ṇ->n, ṭ->t, ḍ->d, ṛ->r, ḥ->h, ḷ->l
   *    regardless of which Indic source script produced them.
   * 5. Strip stray apostrophes that sometimes appear in Google's raw romanization to mark a
   *    glottal/schwa boundary (e.g. "ga'i", "hathi'ara") — casual Hinglish typing never uses
   *    these, so they just read as typos otherwise.
   * 6. Re-normalize to NFC and tidy up leftover spacing.
   *
   * This works uniformly across all Indic languages without needing a dedicated per-script
   * transliteration table.
   */
  private fun casualizeHinglish(text: String): String {
    if (text.isBlank()) return text

    // Explicit handling for sounds that don't casualize correctly via generic
    // diacritic-stripping alone (must run before NFD stripping below):
    //  - ś/ṣ would otherwise collapse to plain "s" and lose the "sh" sound
    //  - long vowels are doubled to preserve the length distinction casually
    //  - ṁ/ṃ (anusvara/nasalization) reads as "n" in casual typing, not "m"
    var result = text
      .replace("ś", "sh").replace("Ś", "Sh")
      .replace("ṣ", "sh").replace("Ṣ", "Sh")
      .replace("ā", "aa").replace("Ā", "Aa")
      .replace("ī", "ee").replace("Ī", "Ee")
      .replace("ū", "oo").replace("Ū", "Oo")
      .replace("ṁ", "n").replace("Ṁ", "N")
      .replace("ṃ", "n").replace("Ṃ", "N")

    // Google sometimes represents nasalization as a combining mark riding on the vowel
    // instead of a standalone ṁ/ṃ character (e.g. a combining tilde or candrabindu). Convert
    // those to a trailing "n" too, rather than letting the generic strip below silently drop
    // the nasalization entirely.
    val nfd = java.text.Normalizer.normalize(result, java.text.Normalizer.Form.NFD)
    val nasalConverted = StringBuilder()
    for (c in nfd) {
      if (c == '\u0303' || c == '\u0310') { // combining tilde, combining candrabindu
        nasalConverted.append('n')
      } else {
        nasalConverted.append(c)
      }
    }
    result = nasalConverted.toString()

    // Strip any remaining combining diacritical marks (dots-above/below, etc.)
    val stripped = result.replace(Regex("\\p{Mn}+"), "")
    result = java.text.Normalizer.normalize(stripped, java.text.Normalizer.Form.NFC)

    // Strip stray apostrophes left over from Google's romanization (e.g. "ga'i" -> "gai")
    result = result.replace("'", "").replace("’", "")

    // Collapse accidental double spaces left behind and trim
    return result.replace(Regex("\\s{2,}"), " ").trim()
  }

  /** Per-line check: true if this individual line is already predominantly Latin script. */
  private fun isLatinLine(text: String): Boolean {
    var latinCount = 0
    var nonLatinLetterCount = 0
    for (c in text) {
      if (Character.isLetter(c)) {
        val block = Character.UnicodeBlock.of(c)
        if (block == Character.UnicodeBlock.BASIC_LATIN ||
          block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT ||
          block == Character.UnicodeBlock.LATIN_EXTENDED_A ||
          block == Character.UnicodeBlock.LATIN_EXTENDED_B ||
          block == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL
        ) {
          latinCount++
        } else {
          nonLatinLetterCount++
        }
      }
    }
    if (latinCount == 0 && nonLatinLetterCount == 0) return true // no letters at all (punctuation/blank) — leave untouched
    return latinCount >= nonLatinLetterCount
  }

  private suspend fun romanizeChunk(chunk: List<String>): List<TranslationResult> {
    val stringBuilder = StringBuilder()
    val indexMap = mutableMapOf<Int, Int>()
    var marker = 1

    for ((localIdx, line) in chunk.withIndex()) {
      val trimmed = line.trim()
      if (trimmed.isNotEmpty()) {
        stringBuilder.append("[$marker] ").append(trimmed).append("\n")
        indexMap[marker] = localIdx
        marker++
      }
    }

    if (indexMap.isEmpty()) {
      return chunk.map { TranslationResult(translation = "") }
    }

    val queryText = stringBuilder.toString().trim()

    // 1. Primary: Fetch Google Romanization using client=it & dt=rm (authentic Japanese Romaji, Korean Romaja, etc.)
    // Only trust this response if it covers every marked line — a partial match (e.g. Google
    // only returning the last sentence's transliteration) would otherwise leave earlier lines
    // stuck in their original script while later ones get romanized.
    try {
      val googleRom = fetchGoogleRomanization(queryText)
      if (!googleRom.isNullOrBlank()) {
        val normalized = normalizeDigitsAndBrackets(googleRom)
        val markersFound = BRACKET_PATTERN.findAll(normalized).count { it.groupValues[2].trim().isNotEmpty() }
        if (markersFound >= indexMap.size) {
          return parseIndexedTranslations(googleRom, chunk.size, indexMap, chunk)
        }
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Log.w(TAG, "Google romanization error: ${e.message}")
    }

    // 2. Fallback: ICU transliteration for non-Kanji scripts (applied per-line so results are consistent)
    return chunk.map { line ->
      val trimmed = line.trim()
      if (trimmed.isEmpty()) {
        TranslationResult(translation = "")
      } else {
        val rom = transliterateToLatin(trimmed)
        TranslationResult(translation = rom, romanization = rom)
      }
    }
  }

  private suspend fun fetchGoogleRomanization(query: String): String? {
    val url = HttpUrl.Builder()
      .scheme("https")
      .host("translate.google.com")
      .addPathSegment("translate_a")
      .addPathSegment("single")
      .addQueryParameter("client", "it")
      .addQueryParameter("sl", "auto")
      .addQueryParameter("tl", "en")
      .addQueryParameter("dt", "t")
      .addQueryParameter("dt", "rm")
      .addQueryParameter("q", query)
      .build()

    val request = Request.Builder()
      .url(url)
      .header("User-Agent", USER_AGENT)
      .get()
      .build()

    client.newCall(request).awaitResponse().use { response ->
      if (!response.isSuccessful) return null
      val bodyStr = response.body.string()
      val root = json.parseToJsonElement(bodyStr).jsonArray
      val sentences = root.getOrNull(0)?.jsonArray ?: return null

      // Check sentences array for the full romanization block (in the last element)
      for (i in sentences.indices.reversed()) {
        val elem = sentences.getOrNull(i)?.jsonArray
        val candidate = elem?.getOrNull(3)?.jsonPrimitive?.content
          ?: elem?.getOrNull(2)?.jsonPrimitive?.content
        if (!candidate.isNullOrBlank() && candidate != "null") {
          return candidate.trim()
        }
      }

      val sb = StringBuilder()
      for (elem in sentences) {
        val sub = elem.jsonArray
        val rom = sub.getOrNull(3)?.jsonPrimitive?.content
          ?: sub.getOrNull(2)?.jsonPrimitive?.content
        if (!rom.isNullOrBlank() && rom != "null") {
          sb.append(rom).append(" ")
        }
      }
      return sb.toString().trim().ifEmpty { null }
    }
  }

  private fun transliterateToLatin(text: String): String {
    if (text.isBlank()) return text
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      try {
        val transliterator = android.icu.text.Transliterator.getInstance("Any-Latin")
        val result = transliterator.transliterate(text)
        if (!result.isNullOrBlank()) return result.trim()
      } catch (e: Throwable) {
        Log.w(TAG, "ICU transliteration error: ${e.message}")
      }
    }
    return text
  }

  private suspend fun translateChunk(
    chunk: List<String>,
    targetLang: String,
  ): List<TranslationResult> {
    val stringBuilder = StringBuilder()
    val indexMap = mutableMapOf<Int, Int>() // marker (1-based) -> index in chunk
    var marker = 1

    for ((localIdx, line) in chunk.withIndex()) {
      val trimmed = line.trim()
      if (trimmed.isNotEmpty()) {
        stringBuilder.append("[$marker] ").append(trimmed).append("\n")
        indexMap[marker] = localIdx
        marker++
      }
    }

    // If entire chunk is blank lines
    if (indexMap.isEmpty()) {
      return chunk.map { TranslationResult(translation = "") }
    }

    val queryText = stringBuilder.toString().trim()

    // 1. Primary engine: Google Mobile Web (Fast, robust, translates multiline chunks in <500ms)
    try {
      val googleResult = translateWithGoogleWeb(queryText, targetLang)
      if (!googleResult.isNullOrBlank()) {
        val parsed = parseIndexedTranslations(googleResult, chunk.size, indexMap, chunk)
        if (parsed.any(TranslationResult::isResolved)) {
          return parsed
        }
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Log.w(TAG, "Google web translation failed for chunk: ${e.message}")
    }

    // 2. Secondary fallback engine: MyMemory API
    try {
      val myMemoryResult = translateWithMyMemory(queryText, targetLang)
      if (!myMemoryResult.isNullOrBlank()) {
        val parsed = parseIndexedTranslations(myMemoryResult, chunk.size, indexMap, chunk)
        if (parsed.any(TranslationResult::isResolved)) {
          return parsed
        }
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Log.w(TAG, "MyMemory translation failed for chunk: ${e.message}")
    }

    // 3. Fallback: Google single-call translate_a
    try {
      val fallbackResult = translateWithGoogleApiFallback(queryText, targetLang)
      if (!fallbackResult.isNullOrBlank()) {
        val parsed = parseIndexedTranslations(fallbackResult, chunk.size, indexMap, chunk)
        if (parsed.any(TranslationResult::isResolved)) {
          return parsed
        }
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Log.w(TAG, "Google API fallback failed for chunk: ${e.message}")
    }

    return chunk.map {
      TranslationResult(
        translation = it.trim(),
        romanization = it.trim(),
        isResolved = false,
      )
    }
  }

  private suspend fun translateWithGoogleWeb(
    query: String,
    targetLang: String,
  ): String? {
    val url = HttpUrl.Builder()
      .scheme("https")
      .host("translate.google.com")
      .addPathSegment("m")
      .addQueryParameter("sl", "auto")
      .addQueryParameter("tl", targetLang)
      .addQueryParameter("hl", "en-US")
      .addQueryParameter("q", query)
      .build()

    val request = Request.Builder()
      .url(url)
      .header("User-Agent", USER_AGENT)
      .get()
      .build()

    client.newCall(request).awaitResponse().use { response ->
      if (!response.isSuccessful) {
        Log.w(TAG, "Google web translate HTTP ${response.code}")
        return null
      }
      val html = response.body.string()
      val match = RESULT_CONTAINER_REGEX.find(html) ?: return null
      val rawText = match.groupValues[1]
      return unescapeHtml(rawText).trim()
    }
  }

  private suspend fun translateWithMyMemory(
    query: String,
    targetLang: String,
  ): String? {
    val langPair = "autodetect|$targetLang"
    val url = HttpUrl.Builder()
      .scheme("https")
      .host("api.mymemory.translated.net")
      .addPathSegment("get")
      .addQueryParameter("q", query)
      .addQueryParameter("langpair", langPair)
      .build()

    val request = Request.Builder()
      .url(url)
      .header("User-Agent", USER_AGENT)
      .get()
      .build()

    client.newCall(request).awaitResponse().use { response ->
      if (!response.isSuccessful) {
        Log.w(TAG, "MyMemory translate HTTP ${response.code}")
        return null
      }
      val bodyStr = response.body.string()
      val jsonElement = json.parseToJsonElement(bodyStr).jsonObject
      val responseData = jsonElement["responseData"]?.jsonObject ?: return null
      val translatedText = responseData["translatedText"]?.jsonPrimitive?.content ?: return null
      return unescapeHtml(translatedText).trim()
    }
  }

  private suspend fun translateWithGoogleApiFallback(
    query: String,
    targetLang: String,
  ): String? {
    val formBody = FormBody.Builder()
      .add("client", "gtx")
      .add("sl", "auto")
      .add("tl", targetLang)
      .add("dt", "t")
      .add("q", query)
      .build()

    val request = Request.Builder()
      .url("https://translate.googleapis.com/translate_a/single")
      .header("User-Agent", USER_AGENT)
      .post(formBody)
      .build()

    client.newCall(request).awaitResponse().use { response ->
      if (!response.isSuccessful) return null
      val bodyStr = response.body.string()
      val root = json.parseToJsonElement(bodyStr).jsonArray
      val sentencesArray = root.getOrNull(0)?.jsonArray ?: return null
      val sb = StringBuilder()
      for (element in sentencesArray) {
        val subArray = element.jsonArray
        val trans = subArray.getOrNull(0)?.jsonPrimitive?.content
        if (!trans.isNullOrBlank() && trans != "null") {
          sb.append(trans)
        }
      }
      return sb.toString().trim()
    }
  }

  private fun parseIndexedTranslations(
    translatedText: String,
    chunkSize: Int,
    indexMap: Map<Int, Int>,
    originalChunk: List<String>,
  ): List<TranslationResult> {
    // Default each position with the original line so missing markers never leave lines blank
    val results = Array(chunkSize) { idx ->
      val orig = originalChunk[idx].trim()
      TranslationResult(translation = orig, romanization = orig, isResolved = false)
    }

    // Normalize Indic/Arabic/Fullwidth digits and brackets so regex matching works across all languages
    val normalizedText = normalizeDigitsAndBrackets(translatedText)

    val parsedByMarker = mutableMapOf<Int, String>()

    for (match in BRACKET_PATTERN.findAll(normalizedText)) {
      val marker = match.groupValues[1].toIntOrNull()
      val content = match.groupValues[2].trim()
      if (marker != null && content.isNotEmpty()) {
        parsedByMarker[marker] = content
      }
    }

    if (parsedByMarker.isNotEmpty()) {
      for ((marker, content) in parsedByMarker) {
        val localIdx = indexMap[marker]
        if (localIdx != null && localIdx in 0 until chunkSize) {
          val orig = originalChunk[localIdx].trim()
          val cleanContent = cleanLine(content)
          results[localIdx] = TranslationResult(
            translation = cleanContent.ifEmpty { orig },
            romanization = cleanContent.ifEmpty { orig },
            isResolved = cleanContent.isNotEmpty(),
          )
        }
      }
      return results.toList()
    }

    // Fallback: If markers were stripped, try 1-to-1 line matching
    val nonBlankLines = normalizedText.lines().map { cleanLine(it) }.filter { it.isNotEmpty() }
    if (nonBlankLines.size == indexMap.size) {
      val sortedEntries = indexMap.entries.sortedBy { it.key }
      for ((i, entry) in sortedEntries.withIndex()) {
        val localIdx = entry.value
        val orig = originalChunk[localIdx].trim()
        val trans = nonBlankLines[i].ifEmpty { orig }
        results[localIdx] = TranslationResult(
          translation = trans,
          romanization = trans,
        )
      }
      return results.toList()
    }

    for ((_, localIdx) in indexMap) {
      results[localIdx] = TranslationResult(
        translation = originalChunk[localIdx].trim(),
        romanization = originalChunk[localIdx].trim(),
        isResolved = false,
      )
    }
    return results.toList()
  }

  private fun cleanLine(line: String): String {
    return line.replace(Regex("""^\s*\[?\d+\]?[.:\- ]*"""), "").trim()
  }

  /**
   * Rewrites only the index markers an engine may have localised — "［１］", "【१】",
   * "[١]" — back to "[1]", so the marker pattern can find them. Everything else is
   * left exactly as the engine returned it: parentheses and brackets are part of the
   * lyric ("(ooh yeah)"), and rewriting them turned every backing vocal into "[ooh yeah]".
   */
  private fun normalizeDigitsAndBrackets(text: String): String =
    LOCALISED_MARKER_REGEX.replace(text) { match ->
      val digits =
        match.groupValues[1].map { c ->
          val digit = Character.digit(c, 10)
          if (digit >= 0) ('0'.code + digit).toChar() else c
        }.joinToString("")
      "[$digits]"
    }

  private fun unescapeHtml(text: String): String {
    var result = text
      .replace("&quot;", "\"")
      .replace("&#39;", "'")
      .replace("&apos;", "'")
      .replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&nbsp;", " ")

    // Unescape numeric and hex character entities like &#20320; or &#x4F60;
    if (result.contains("&#")) {
      result = NUMERIC_ENTITY_REGEX.replace(result) { matchResult ->
        val entity = matchResult.groupValues[1]
        try {
          val codePoint = if (entity.startsWith("x", ignoreCase = true)) {
            entity.substring(1).toInt(16)
          } else {
            entity.toInt(10)
          }
          String(Character.toChars(codePoint))
        } catch (_: Exception) {
          matchResult.value
        }
      }
    }

    return result
  }
}
