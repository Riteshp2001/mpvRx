/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitle

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
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
    if (bytes.size > MAX_ARCHIVE_BYTES) return null
    return when {
      isZipArchive(bytes) -> extractBestZip(bytes, preferredName, preferredEpisode)
      isRarArchive(bytes) -> extractBestRar(bytes, preferredName, preferredEpisode)
      else -> null
    }
  }

  private fun extractBestZip(
    bytes: ByteArray,
    preferredName: String?,
    preferredEpisode: Int?,
  ): ExtractedSubtitle? {
    val preferredFileName = preferredName.normalizedFileName()
    val candidates = mutableListOf<Candidate>()

    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        try {
          if (entry.isDirectory || entry.name.isJunkArchiveEntry()) continue
          val extension = extensionFromName(entry.name)?.takeIf { it in subtitleExtensions } ?: continue
          val fileName = entry.name.archiveFileName()
          val payload = zip.readBytesBounded(MAX_SUBTITLE_BYTES) ?: continue
          if (payload.isEmpty()) continue

          candidates +=
            Candidate(
              subtitle = ExtractedSubtitle(payload, extension, fileName),
              score =
                archiveEntryScore(
                  fileName,
                  extension,
                  payload.size.toLong(),
                  preferredFileName,
                  preferredEpisode,
                ),
            )
        } finally {
          zip.closeEntry()
        }
      }
    }

    return selectBest(candidates, preferredEpisode)?.subtitle
  }

  private fun extractBestRar(
    bytes: ByteArray,
    preferredName: String?,
    preferredEpisode: Int?,
  ): ExtractedSubtitle? =
    runCatching {
      val preferredFileName = preferredName.normalizedFileName()
      val candidates =
        withRarArchive(bytes) { archive ->
          buildList {
            var entryIndex = 0
            while (true) {
              val entry = Archive.readNextHeader(archive).takeIf { it != 0L } ?: break
              val currentIndex = entryIndex++
              val fileName = archiveEntryName(entry)
              val extension = extensionFromName(fileName)?.takeIf { it in subtitleExtensions }
              val unpackedSize = ArchiveEntry.size(entry).takeIf { ArchiveEntry.sizeIsSet(entry) } ?: -1L
              if (
                fileName != null &&
                extension != null &&
                !ArchiveEntry.isEncrypted(entry) &&
                !fileName.isJunkArchiveEntry() &&
                unpackedSize in 1..MAX_SUBTITLE_BYTES.toLong()
              ) {
                add(
                  RarCandidate(
                    entryIndex = currentIndex,
                    fileName = fileName.archiveFileName(),
                    extension = extension,
                    score =
                      archiveEntryScore(
                        fileName,
                        extension,
                        unpackedSize,
                        preferredFileName,
                        preferredEpisode,
                      ),
                  ),
                )
              }
              Archive.readDataSkip(archive)
            }
          }
        }
      val eligible =
        if (preferredEpisode != null) {
          candidates.filter { it.score.preferredEpisodeMatch }
        } else {
          candidates
        }

      eligible.sortedWith(archiveEntryComparator { it.score }).firstNotNullOfOrNull { candidate ->
        runCatching { extractRarCandidate(bytes, candidate) }.getOrNull()
      }
    }.getOrNull()

  private fun extractRarCandidate(
    bytes: ByteArray,
    candidate: RarCandidate,
  ): ExtractedSubtitle? =
    withRarArchive<ExtractedSubtitle?>(bytes) { archive ->
      var entryIndex = 0
      var entry = Archive.readNextHeader(archive)
      while (entry != 0L) {
        if (entryIndex++ != candidate.entryIndex) {
          Archive.readDataSkip(archive)
          entry = Archive.readNextHeader(archive)
          continue
        }
        val payload = readCurrentArchiveEntry(archive)
        return@withRarArchive payload.takeIf { it.isNotEmpty() }?.let {
          ExtractedSubtitle(it, candidate.extension, candidate.fileName)
        }
      }
      null
    }

  private fun <T> withRarArchive(
    bytes: ByteArray,
    operation: (Long) -> T,
  ): T {
    val archive = Archive.readNew()
    try {
      Archive.setCharset(archive, StandardCharsets.UTF_8.name().toByteArray(StandardCharsets.UTF_8))
      Archive.readSupportFormatRar(archive)
      Archive.readSupportFormatRar5(archive)
      Archive.readOpenMemory(archive, ByteBuffer.wrap(bytes))
      return operation(archive)
    } finally {
      runCatching { Archive.free(archive) }
    }
  }

  private fun archiveEntryName(entry: Long): String? =
    ArchiveEntry.pathnameUtf8(entry)
      ?: ArchiveEntry.pathname(entry)?.toString(StandardCharsets.UTF_8)

  private fun readCurrentArchiveEntry(archive: Long): ByteArray {
    val output = BoundedByteArrayOutputStream(MAX_SUBTITLE_BYTES)
    val buffer = ByteBuffer.allocateDirect(ARCHIVE_READ_BUFFER_BYTES)
    while (true) {
      buffer.clear()
      Archive.readData(archive, buffer)
      val readCount = buffer.position()
      if (readCount <= 0) break
      buffer.flip()
      val chunk = ByteArray(readCount)
      buffer.get(chunk)
      output.write(chunk)
    }
    return output.toByteArray()
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

  fun isRarArchive(bytes: ByteArray): Boolean =
    bytes.startsWith(RAR_14_SIGNATURE) ||
      bytes.startsWith(RAR_4_SIGNATURE) ||
      bytes.startsWith(RAR_5_SIGNATURE)

  fun isSupportedArchive(bytes: ByteArray): Boolean = isZipArchive(bytes) || isRarArchive(bytes)

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

  private fun String.archiveFileName(): String = substringAfterLast('/').substringAfterLast('\\')

  private fun String?.normalizedFileName(): String? = this?.archiveFileName()?.lowercase(Locale.ROOT)

  private fun archiveEntryScore(
    fileName: String,
    extension: String,
    size: Long,
    preferredFileName: String?,
    preferredEpisode: Int?,
  ): ArchiveEntryScore =
    ArchiveEntryScore(
      preferredNameMatch = preferredFileName != null && fileName.normalizedFileName() == preferredFileName,
      preferredEpisodeMatch = preferredEpisode != null && fileName.episodeNumbers().contains(preferredEpisode),
      extensionPriority = extensionPriority[extension] ?: Int.MAX_VALUE,
      size = size,
    )

  private fun selectBest(
    candidates: List<Candidate>,
    preferredEpisode: Int?,
  ): Candidate? {
    val eligible =
      if (preferredEpisode != null) {
        candidates.filter { it.score.preferredEpisodeMatch }
      } else {
        candidates
      }
    return eligible.minWithOrNull(archiveEntryComparator { it.score })
  }

  private fun <T> archiveEntryComparator(score: (T) -> ArchiveEntryScore): Comparator<T> =
    compareBy<T> { !score(it).preferredEpisodeMatch }
      .thenBy { !score(it).preferredNameMatch }
      .thenBy { score(it).extensionPriority }
      .thenByDescending { score(it).size }

  private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

  private fun java.io.InputStream.readBytesBounded(maxBytes: Int): ByteArray? {
    val output = BoundedByteArrayOutputStream(maxBytes)
    return runCatching {
      copyTo(output)
      output.toByteArray()
    }.getOrNull()
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
    Regex("""(?i)(?<![a-z0-9])\d{1,2}(\d{2})(?![a-z0-9])""")
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

  private data class RarCandidate(
    val entryIndex: Int,
    val fileName: String,
    val extension: String,
    val score: ArchiveEntryScore,
  )

  private data class ArchiveEntryScore(
    val preferredNameMatch: Boolean = false,
    val preferredEpisodeMatch: Boolean = false,
    val extensionPriority: Int = Int.MAX_VALUE,
    val size: Long = 0,
  )

  private class BoundedByteArrayOutputStream(
    private val maxBytes: Int,
  ) : OutputStream() {
    private val delegate = ByteArrayOutputStream()

    override fun write(value: Int) {
      ensureCapacity(1)
      delegate.write(value)
    }

    override fun write(
      buffer: ByteArray,
      offset: Int,
      length: Int,
    ) {
      ensureCapacity(length)
      delegate.write(buffer, offset, length)
    }

    fun toByteArray(): ByteArray = delegate.toByteArray()

    private fun ensureCapacity(additionalBytes: Int) {
      if (additionalBytes < 0 || delegate.size().toLong() + additionalBytes > maxBytes) {
        throw IOException("Subtitle archive entry exceeds $maxBytes bytes")
      }
    }
  }

  private const val MAX_SUBTITLE_BYTES = 32 * 1024 * 1024
  private const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
  private const val ARCHIVE_READ_BUFFER_BYTES = 16 * 1024
  private val RAR_14_SIGNATURE = byteArrayOf(0x52, 0x45, 0x7E, 0x5E)
  private val RAR_4_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
  private val RAR_5_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
}
