/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest

/** Stores finished batches so a cancelled or interrupted translation resumes where it stopped. */
interface TranslationBatchCache {
  fun load(key: String): Map<Int, List<String>>

  fun save(
    key: String,
    batches: Map<Int, List<String>>,
  )

  fun clear(key: String)
}

/**
 * Translates a parsed subtitle file cue text only, in batches sized for the engine, and validates
 * every answer. A batch whose answer doesn't line up is split in half and retried, so one bad
 * response can never shift translations onto the wrong cues.
 */
class SubtitleTranslationEngine(
  private val cache: TranslationBatchCache,
  private val retryDelaysMs: List<Long> = listOf(1_500, 4_000, 10_000),
) {
  data class Progress(
    val completedBatches: Int,
    val totalBatches: Int,
    val resumed: Boolean,
  ) {
    val fraction: Float get() = if (totalBatches == 0) 1f else completedBatches.toFloat() / totalBatches
  }

  data class Result(
    val content: String,
    val translatedUnits: Int,
    /** Units the engine returned nothing usable for; they keep the original text. */
    val failedUnits: Int,
  )

  suspend fun translate(
    content: String,
    format: SubtitleFormat,
    translator: SubtitleTranslator,
    sourceLanguage: String?,
    targetLanguage: String,
    rtlFix: Boolean,
    onProgress: (Progress) -> Unit = {},
  ): Result {
    val document = SubtitleDocument.parse(content, format)
    val processor = CueTextProcessor(format)
    val plans = document.cues.map(processor::plan)

    // Only units with real words are sent; everything else keeps its original text.
    data class Ref(
      val plan: Int,
      val unit: Int,
    )
    val pending = mutableListOf<Ref>()
    plans.forEachIndexed { p, plan ->
      plan.units.forEachIndexed { u, unit -> if (processor.isTranslatable(unit)) pending += Ref(p, u) }
    }
    val texts =
      pending.map { ref ->
        val unit = plans[ref.plan].units[ref.unit]
        if (translator.preservesInlineMarkup) unit.text else processor.plainText(unit)
      }
    val batches = batch(texts, translator.maxTextsPerRequest, translator.maxCharsPerRequest)

    val key = cacheKey(content, format, translator, sourceLanguage, targetLanguage)
    val done = cache.load(key).filter { (index, result) -> index in batches.indices && result.size == batches[index].count() }.toMutableMap()
    val resumed = done.isNotEmpty()
    onProgress(Progress(done.size, batches.size, resumed))

    var failed = 0
    for ((index, range) in batches.withIndex()) {
      if (index in done) continue
      currentCoroutineContext().ensureActive()
      val output = translateRecovering(translator, texts.subList(range.first, range.last + 1), sourceLanguage, targetLanguage)
      done[index] = output
      cache.save(key, done)
      onProgress(Progress(done.size, batches.size, resumed))
    }

    val translatedTexts = MutableList(texts.size) { "" }
    batches.forEachIndexed { index, range ->
      done.getValue(index).forEachIndexed { offset, value -> translatedTexts[range.first + offset] = value }
    }
    val byRef = HashMap<Ref, String>(pending.size)
    pending.forEachIndexed { i, ref ->
      val value = translatedTexts[i]
      if (value.isBlank()) failed++
      byRef[ref] = value.ifBlank { plans[ref.plan].units[ref.unit].text }
    }

    val rtl = rtlFix && TranslationLanguages.isRtl(targetLanguage)
    val rebuilt =
      plans.mapIndexed { p, plan ->
        if (plan.units.indices.none { Ref(p, it) in byRef }) {
          null
        } else {
          processor.rebuild(plan, plan.units.mapIndexed { u, unit -> byRef[Ref(p, u)] ?: unit.text }, rtl)
        }
      }
    val output = document.render { cue -> rebuilt[cue.index] }
    cache.clear(key)
    return Result(output, translatedUnits = pending.size - failed, failedUnits = failed)
  }

  /** Greedy batches by count and characters; returns index ranges into [texts]. */
  internal fun batch(
    texts: List<String>,
    maxTexts: Int,
    maxChars: Int,
  ): List<IntRange> {
    val batches = mutableListOf<IntRange>()
    var start = 0
    var chars = 0
    for (i in texts.indices) {
      val size = texts[i].length + 1
      if (i > start && (i - start >= maxTexts || chars + size > maxChars)) {
        batches += start until i
        start = i
        chars = 0
      }
      chars += size
    }
    if (start < texts.size) batches += start until texts.size
    return batches
  }

  /**
   * Retries transient failures, and splits batches the engine answered badly. A single text that
   * still fails validation comes back blank (the caller keeps the original) instead of failing the
   * whole file.
   */
  private suspend fun translateRecovering(
    translator: SubtitleTranslator,
    texts: List<String>,
    sourceLanguage: String?,
    targetLanguage: String,
  ): List<String> {
    val answer =
      try {
        withRetry { translator.translate(texts, sourceLanguage, targetLanguage) }
      } catch (_: SubtitleTranslationException.InvalidResponse) {
        null
      }
    if (answer != null && answer.size == texts.size && answer.indices.none { answer[it].isBlank() && texts[it].isNotBlank() }) {
      return answer
    }
    if (texts.size == 1) return listOf(answer?.singleOrNull().orEmpty())
    val half = texts.size / 2
    return translateRecovering(translator, texts.subList(0, half), sourceLanguage, targetLanguage) +
      translateRecovering(translator, texts.subList(half, texts.size), sourceLanguage, targetLanguage)
  }

  private suspend fun <T> withRetry(block: suspend () -> T): T {
    var attempt = 0
    while (true) {
      try {
        return block()
      } catch (e: SubtitleTranslationException) {
        if (!e.retryable || attempt >= retryDelaysMs.size) throw e
        val wait = (e as? SubtitleTranslationException.RateLimited)?.retryAfterMs ?: retryDelaysMs[attempt]
        attempt++
        delay(wait)
      }
    }
  }

  private fun cacheKey(
    content: String,
    format: SubtitleFormat,
    translator: SubtitleTranslator,
    sourceLanguage: String?,
    targetLanguage: String,
  ): String =
    sha256(
      listOf(
        CACHE_VERSION,
        content,
        format.name,
        translator.cacheId,
        translator.maxTextsPerRequest.toString(),
        translator.maxCharsPerRequest.toString(),
        sourceLanguage.orEmpty(),
        targetLanguage,
      ).joinToString("\u001f"),
    )

  companion object {
    private const val CACHE_VERSION = "1"

    fun sha256(value: String): String =
      MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
  }
}
