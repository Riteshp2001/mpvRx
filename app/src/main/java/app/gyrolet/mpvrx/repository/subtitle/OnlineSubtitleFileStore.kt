/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitle

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.utils.media.ChecksumUtils
import app.gyrolet.mpvrx.utils.media.resolveSubtitleStorageDirectory
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class OnlineSubtitleFileStore(
  private val context: Context,
  private val preferences: SubtitlesPreferences,
) {
  fun save(
    bytes: ByteArray,
    subtitle: OnlineSubtitle,
    mediaTitle: String,
  ): Uri {
    if (SubtitleArchiveExtractor.looksLikeHtml(bytes)) {
      throw IllegalStateException("Downloaded file is HTML, not a subtitle")
    }

    val selectedEpisode = subtitle.selectedSubdlGroupEpisode()
    val extracted =
      SubtitleArchiveExtractor.extractBest(
        bytes = bytes,
        preferredName = subtitle.fileName ?: subtitle.displayName,
        preferredEpisode = selectedEpisode,
      )
    if (extracted == null && SubtitleArchiveExtractor.isZipArchive(bytes)) {
      val selectedEpisodeMessage = selectedEpisode?.let { " for episode $it" }.orEmpty()
      throw IllegalStateException(
        "Downloaded subtitle archive did not contain a supported subtitle file$selectedEpisodeMessage",
      )
    }
    val extension =
      extracted?.extension
        ?: subtitle.format?.lowercase()?.takeIf { it in STANDARD_SUBTITLE_EXTENSIONS }
        ?: SubtitleArchiveExtractor.extensionFromName(subtitle.fileName)?.takeIf { it in STANDARD_SUBTITLE_EXTENSIONS }
        ?: SubtitleArchiveExtractor.extensionFromName(subtitle.url)?.takeIf { it in STANDARD_SUBTITLE_EXTENSIONS }
        ?: "srt"

    val payload = extracted?.bytes ?: bytes
    if (SubtitleArchiveExtractor.looksLikeHtml(payload)) {
      throw IllegalStateException("Downloaded file is HTML, not a subtitle")
    }

    val saveFolderUri = preferences.subtitleSaveFolder.get()
    val folderName = ChecksumUtils.getCRC32(mediaTitle)
    val subFileName =
      buildSubtitleFileName(
        mediaTitle = mediaTitle,
        subtitle = subtitle,
        extractedFileName = extracted?.fileName,
        extension = extension,
      )

    if (saveFolderUri.isNotBlank()) {
      val parentDir = resolveSubtitleStorageDirectory(context, saveFolderUri, createIfMissing = true)
      if (parentDir?.exists() == true) {
        val movieDir = parentDir.findFile(folderName) ?: parentDir.createDirectory(folderName)
        if (movieDir != null) {
          val subFile = movieDir.findFile(subFileName) ?: movieDir.createFile(mimeForSubtitle(extension), subFileName)
          if (subFile != null) {
            context.contentResolver.openOutputStream(subFile.uri)?.use { it.write(payload) }
            return subFile.uri
          }
        }
      }
    }

    val internalMoviesDir = File(context.getExternalFilesDir(null), "Movies")
    val movieDir = File(internalMoviesDir, folderName).apply { if (!exists()) mkdirs() }
    val file = File(movieDir, subFileName)
    FileOutputStream(file).use { it.write(payload) }
    return Uri.fromFile(file)
  }

  fun delete(uri: Uri): Boolean {
    val file =
      if (uri.scheme == "content") {
        DocumentFile.fromSingleUri(context, uri)
      } else {
        DocumentFile.fromFile(File(uri.path ?: uri.toString()))
      }
    return file?.takeIf { it.exists() }?.delete() == true
  }

  private fun String.sanitizeFilePart(): String =
    replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
      .replace(Regex("""\s+"""), " ")
      .trim(' ', '.')
      .ifBlank { "subtitle" }

  private fun buildSubtitleFileName(
    mediaTitle: String,
    subtitle: OnlineSubtitle,
    extractedFileName: String?,
    extension: String,
  ): String {
    val mediaBase =
      cleanFileStem(mediaTitle)
        ?.takeUnless(::isGenericSubtitleName)
        ?: cleanFileStem(subtitle.media)
        ?: "subtitle"
    val sourcePart = cleanSourceName(subtitle.source)
    val releasePart =
      listOf(
        subtitle.fileName,
        extractedFileName,
        subtitle.release,
        subtitle.displayName,
      ).firstNotNullOfOrNull { raw ->
        cleanFileStem(raw)
          ?.takeIf { isUsefulDescriptor(it, mediaBase, sourcePart) }
      }
    val languagePart =
      (subtitle.language ?: subtitle.displayLanguage)
        .sanitizeFilePart()
        .takeUnless(::isGenericSubtitleName)
        ?: "und"

    val parts =
      listOf(mediaBase, releasePart, sourcePart, languagePart)
        .mapNotNull { it?.sanitizeFilePart()?.takeIf(String::isNotBlank) }
        .distinctBy { it.normalizedFilePart() }
        .map { it.take(MAX_FILE_PART_LENGTH).trim(' ', '.') }

    return "${parts.joinToString(".").take(MAX_FILE_STEM_LENGTH).trim(' ', '.')}.$extension"
  }

  private fun cleanSourceName(value: String?): String? =
    cleanFileStem(value)
      ?.replace(Regex("""\s+(com|org|net)$""", RegexOption.IGNORE_CASE), "")
      ?.trim()
      ?.takeUnless(::isGenericSubtitleName)

  private fun cleanFileStem(value: String?): String? {
    if (value.isNullOrBlank()) return null
    var stem =
      value
        .substringBefore('?')
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()
    while (true) {
      val extension = stem.substringAfterLast('.', "").lowercase(Locale.ROOT)
      if (extension !in STRIPPED_FILE_EXTENSIONS || !stem.contains('.')) break
      stem = stem.substringBeforeLast('.').trim()
    }
    return stem
      .replace(Regex("""[._]+"""), " ")
      .sanitizeFilePart()
      .takeIf { it.isNotBlank() }
  }

  private fun isUsefulDescriptor(
    value: String,
    mediaBase: String,
    sourcePart: String?,
  ): Boolean {
    if (isGenericSubtitleName(value)) return false
    val normalized = value.normalizedFilePart()
    if (normalized == mediaBase.normalizedFilePart()) return false
    if (sourcePart != null && normalized == sourcePart.normalizedFilePart()) return false
    if (normalized.length <= 3 && mediaBase.normalizedFilePart().contains(normalized)) return false
    return true
  }

  private fun isGenericSubtitleName(value: String): Boolean = value.normalizedFilePart() in GENERIC_SUBTITLE_NAMES

  private fun String.normalizedFilePart(): String = lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]+"""), "")

  private fun mimeForSubtitle(extension: String): String =
    when (extension) {
      "vtt" -> "text/vtt"
      "srt", "ass", "ssa", "sub" -> "text/plain"
      else -> "application/octet-stream"
    }

  private companion object {
    val STANDARD_SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub")
    val STRIPPED_FILE_EXTENSIONS =
      STANDARD_SUBTITLE_EXTENSIONS +
        setOf("zip", "rar", "7z", "txt", "mkv", "mp4", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts")
    val GENERIC_SUBTITLE_NAMES =
      setOf(
        "sub",
        "subs",
        "subtitle",
        "subtitles",
        "download",
        "file",
        "unknown",
        "und",
        "en",
        "eng",
        "english",
        "es",
        "spa",
        "spanish",
        "fr",
        "fre",
        "french",
        "de",
        "ger",
        "german",
        "it",
        "ita",
        "italian",
        "pt",
        "por",
        "portuguese",
      )
    const val MAX_FILE_PART_LENGTH = 80
    const val MAX_FILE_STEM_LENGTH = 180
  }
}
