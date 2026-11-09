/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import app.gyrolet.mpvrx.repository.IntroDbSegment
import java.text.Normalizer
import java.util.Locale

internal data class ChapterSkipMarker(
  val title: String?,
  val startSeconds: Double,
)

internal data class ProviderSkipMarker(
  val type: String?,
  val startSeconds: Double?,
  val endSeconds: Double?,
)

/** Shared, deterministic skip-marker rules kept independent from mpv and Android lifecycle state. */
internal object SkipMarkerResolver {
  private const val EOF_SEEK_GUARD_SECONDS = 0.25
  private val MARKER_SEPARATORS = Regex("""[\p{P}\p{Z}\s_]+""")
  private val LATIN_DIACRITICS = Regex("""(?<=\p{IsLatin})\p{M}+""")
  private val CAMEL_CASE_SEPARATOR = Regex("""(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])""")
  private val OPENING_CREDITS_TITLE = Regex("""(?:^|\s)opening\s+credits?(?:\s|$)""")
  private val POST_CREDITS_SCENE = Regex("""(?:^|\s)(?:(?:post|after|mid)\s+credits?|bonus\s+scene|easter\s+egg|stinger)(?:\s|$)""")
  private val POST_CREDITS_LABELS = listOf(
    "エンドロール後", "クレジット後", "片尾彩蛋", "片尾字幕后", "片尾字幕後",
    "после титров", "після титрів", "مشهد بعد النهاية", "مشهد ما بعد النهاية",
    "postcréditos", "scène post-générique", "nach dem abspann",
    "क्रेडिट के बाद",
  )

  private fun normalizeMarkerText(value: String): String =
    Normalizer.normalize(
      Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(LATIN_DIACRITICS, "")
        .replace(CAMEL_CASE_SEPARATOR, " "),
      Normalizer.Form.NFKC,
    ).lowercase(Locale.ROOT)

  fun parseCustomKeywords(value: String): List<String> =
    value
      .split(Regex("""[,;\n\r]+"""))
      .map(String::trim)
      .filter(String::isNotEmpty)

  fun classifyTitle(
    title: String?,
    introKeywords: List<String>,
    outroKeywords: List<String>,
    recapKeywords: List<String>,
    creditsKeywords: List<String>,
    previewKeywords: List<String>,
  ): SkipSegmentType? {
    if (title.isNullOrBlank()) return null
    val lowered = normalizeMarkerText(title)
    val normalizedLatin = lowered.replace(Regex("""[^a-z0-9]+"""), " ").trim()
    if (POST_CREDITS_SCENE.containsMatchIn(normalizedLatin)) return null
    val compactRaw = lowered.replace(MARKER_SEPARATORS, "")
    if (POST_CREDITS_LABELS.any { compactRaw.contains(normalizeMarkerText(it).replace(MARKER_SEPARATORS, "")) }) return null

    fun hasKeyword(keywords: List<String>): Boolean =
      keywords.any { rawKeyword ->
        val keyword = normalizeMarkerText(rawKeyword).trim()
        when {
          keyword.isEmpty() -> false
          keyword.matches(Regex("""[a-z0-9]+""")) ->
            normalizedLatin.contains(Regex("""(?:^|\s)${Regex.escape(keyword)}(?:\s|$)"""))
          else -> compactRaw.contains(keyword.replace(MARKER_SEPARATORS, ""))
        }
      }

    val hasIntro = hasKeyword(introKeywords)
    val hasRecap = hasKeyword(recapKeywords)
    val hasCredits = hasKeyword(creditsKeywords)
    val hasPreview = hasKeyword(previewKeywords)
    val hasOutro = hasKeyword(outroKeywords)
    return when {
      hasRecap -> SkipSegmentType.RECAP
      hasPreview -> SkipSegmentType.PREVIEW
      hasIntro && hasCredits -> SkipSegmentType.INTRO
      hasCredits && (OPENING_CREDITS_TITLE.containsMatchIn(normalizedLatin) || lowered.contains("オープニング")) -> null
      hasCredits -> SkipSegmentType.CREDITS
      hasOutro && !hasIntro -> SkipSegmentType.OUTRO
      hasIntro -> SkipSegmentType.INTRO
      else -> null
    }
  }

  fun resolveChapters(
    chapters: List<ChapterSkipMarker>,
    durationSeconds: Double,
    classify: (String?) -> SkipSegmentType?,
  ): List<SkipSegment> {
    if (!durationSeconds.isFinite() || durationSeconds <= 0.0) return emptyList()

    val orderedChapters =
      chapters
        .filter { marker ->
          marker.startSeconds.isFinite() &&
            marker.startSeconds >= 0.0 &&
            marker.startSeconds < durationSeconds
        }.sortedBy(ChapterSkipMarker::startSeconds)

    return orderedChapters.mapIndexedNotNull { index, marker ->
      val type = classify(marker.title) ?: return@mapIndexedNotNull null
      val nextStart =
        orderedChapters
          .asSequence()
          .drop(index + 1)
          .map(ChapterSkipMarker::startSeconds)
          .firstOrNull { it > marker.startSeconds }
      val endSeconds = (nextStart ?: durationSeconds).coerceAtMost(durationSeconds)
      if (endSeconds <= marker.startSeconds) return@mapIndexedNotNull null

      SkipSegment(
        type = type,
        startSeconds = marker.startSeconds,
        endSeconds = endSeconds,
        source = "chapter",
      )
    }
  }

  fun resolveProviderSegment(
    segmentType: String?,
    startSeconds: Double?,
    endSeconds: Double?,
    durationSeconds: Double,
    source: String,
  ): SkipSegment? {
    val marker =
      IntroDbSegment(segmentType = segmentType, start = startSeconds, end = endSeconds)
        .validatedForDuration(durationSeconds) ?: return null
    val type =
      when (marker.segmentType) {
        "intro" -> SkipSegmentType.INTRO
        "recap" -> SkipSegmentType.RECAP
        "outro" -> SkipSegmentType.OUTRO
        "credits" -> SkipSegmentType.CREDITS
        "preview" -> SkipSegmentType.PREVIEW
        else -> return null
      }
    return SkipSegment(
      type = type,
      startSeconds = marker.normalizedStart,
      endSeconds = marker.normalizedEnd,
      source = source,
    )
  }

  fun resolveProviderSegments(
    markers: List<ProviderSkipMarker>,
    durationSeconds: Double,
    source: String,
  ): List<SkipSegment> =
    markers.mapNotNull { marker ->
      resolveProviderSegment(
        segmentType = marker.type,
        startSeconds = marker.startSeconds,
        endSeconds = marker.endSeconds,
        durationSeconds = durationSeconds,
        source = source,
      )
    }

  fun merge(segments: Iterable<SkipSegment>): List<SkipSegment> {
    val merged = mutableListOf<SkipSegment>()
    segments
      .filter { segment ->
        segment.startSeconds.isFinite() &&
          segment.endSeconds.isFinite() &&
          segment.startSeconds >= 0.0 &&
          segment.isValid
      }.sortedWith(
        compareBy<SkipSegment> { it.endSeconds - it.startSeconds }
          .thenBy(SkipSegment::startSeconds)
          .thenBy { it.type.ordinal },
      ).forEach { candidate ->
        val overlaps =
          merged.any { existing ->
            existing.startSeconds < candidate.endSeconds && candidate.startSeconds < existing.endSeconds
          }
        if (!overlaps) merged += candidate
      }
    return merged.sortedBy(SkipSegment::startSeconds)
  }

  /** Avoids seeking to mpv's exact EOF while still allowing a final marker to be skipped. */
  fun seekTarget(
    segment: SkipSegment,
    durationSeconds: Double,
  ): Double {
    if (!durationSeconds.isFinite() || durationSeconds <= 0.0) return segment.endSeconds
    val endSeconds = segment.endSeconds.coerceAtMost(durationSeconds)
    if (endSeconds < durationSeconds) return endSeconds

    val guardedTarget = durationSeconds - EOF_SEEK_GUARD_SECONDS
    return if (guardedTarget > segment.startSeconds) {
      guardedTarget
    } else {
      segment.startSeconds + ((durationSeconds - segment.startSeconds) / 2.0)
    }.coerceIn(segment.startSeconds, endSeconds)
  }
}
