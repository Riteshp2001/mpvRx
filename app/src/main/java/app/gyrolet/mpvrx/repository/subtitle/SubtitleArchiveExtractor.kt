/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.repository.subtitle

import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.ZipInputStream

object SubtitleArchiveExtractor {
  private val subtitleExtensions = setOf("srt", "vtt", "ass", "ssa", "sub")
  private val extensionPriority =
    mapOf(
      "srt" to 0,
      "vtt" to 1,
      "ass" to 2,
      "ssa" to 3,
      "sub" to 4,
    )

  fun extractBest(
    bytes: ByteArray,
    preferredName: String? = null,
    preferredEpisode: Int? = null,
  ): ExtractedSubtitle? {
    if (!isZipArchive(bytes)) return null

    val preferredFileName = preferredName?.substringAfterLast('/')?.substringAfterLast('\\')?.lowercase(Locale.ROOT)
    val candidates = mutableListOf<Candidate>()

    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        try {
          if (entry.isDirectory || entry.name.isJunkArchiveEntry()) continue
          val extension = extensionFromName(entry.name)?.takeIf { it in subtitleExtensions } ?: continue
          val fileName = entry.name.substringAfterLast('/').substringAfterLast('\\')
          val payload = zip.readBytes()
          if (payload.isEmpty()) continue

          candidates +=
            Candidate(
              subtitle =
                ExtractedSubtitle(
                  bytes = payload,
                  extension = extension,
                  fileName = fileName,
                ),
              score =
                ArchiveEntryScore(
                  preferredNameMatch =
                    preferredFileName != null &&
                      fileName.lowercase(
                        Locale.ROOT,
                      ) == preferredFileName,
                  preferredEpisodeMatch =
                    preferredEpisode != null && fileName.episodeNumbers().contains(preferredEpisode),
                  extensionPriority = extensionPriority[extension] ?: Int.MAX_VALUE,
                  size = payload.size,
                ),
            )
        } finally {
          zip.closeEntry()
        }
      }
    }

    val filteredCandidates =
      if (preferredEpisode != null) {
        candidates.filter { it.score.preferredEpisodeMatch }
      } else {
        candidates
      }

    return filteredCandidates
      .minWithOrNull(
        compareBy<Candidate> { !it.score.preferredEpisodeMatch }
          .thenBy { !it.score.preferredNameMatch }
          .thenBy { it.score.extensionPriority }
          .thenByDescending { it.score.size },
      )?.subtitle
  }

  fun extensionFromName(value: String?): String? =
    value
      ?.substringBefore("?")
      ?.substringAfterLast('/')
      ?.substringAfterLast('\\')
      ?.substringAfterLast(".", "")
      ?.lowercase(Locale.ROOT)
      ?.takeIf { it.isNotBlank() && it.length <= 5 }

  fun isZipArchive(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
      bytes[0] == 'P'.code.toByte() &&
      bytes[1] == 'K'.code.toByte() &&
      (
        bytes[2] == 3.toByte() ||
          bytes[2] == 5.toByte() ||
          bytes[2] == 7.toByte()
      )

  fun looksLikeHtml(bytes: ByteArray): Boolean {
    val sample =
      bytes
        .take(512)
        .toByteArray()
        .toString(Charsets.UTF_8)
        .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        .lowercase(Locale.ROOT)

    return sample.startsWith("<!doctype html") ||
      sample.startsWith("<html") ||
      sample.startsWith("<style") ||
      sample.contains("<head>") &&
      sample.contains("<body")
  }

  private fun String.isJunkArchiveEntry(): Boolean {
    val normalized = replace('\\', '/')
    val fileName = normalized.substringAfterLast('/')
    return normalized.startsWith("__MACOSX/", ignoreCase = true) ||
      fileName.startsWith("._") ||
      fileName.equals(".DS_Store", ignoreCase = true)
  }

  private fun String.episodeNumbers(): Set<Int> {
    val fileName = substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.', this)
    val numbers = linkedSetOf<Int>()
    Regex("""(?i)\bS\d{1,2}E(\d{1,4})\b""")
      .findAll(fileName)
      .mapNotNullTo(numbers) { it.groupValues[1].toIntOrNull() }
    Regex("""(?i)\b\d{1,2}x(\d{1,4})\b""")
      .findAll(fileName)
      .mapNotNullTo(numbers) { it.groupValues[1].toIntOrNull() }
    Regex("""(?:^|[^\d])0*(\d{1,4})(?=$|[^\d])""")
      .findAll(fileName)
      .mapNotNullTo(numbers) { it.groupValues[1].toIntOrNull() }
    return numbers
  }

  data class ExtractedSubtitle(
    val bytes: ByteArray,
    val extension: String,
    val fileName: String,
  )

  private data class Candidate(
    val subtitle: ExtractedSubtitle,
    val score: ArchiveEntryScore,
  )

  private data class ArchiveEntryScore(
    val preferredNameMatch: Boolean = false,
    val preferredEpisodeMatch: Boolean = false,
    val extensionPriority: Int = Int.MAX_VALUE,
    val size: Int = 0,
  )
}
