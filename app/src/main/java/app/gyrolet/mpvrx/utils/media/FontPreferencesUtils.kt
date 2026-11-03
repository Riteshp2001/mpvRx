/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import com.github.k1rakishou.fsaf.FileManager
import com.yubyf.truetypeparser.TTFFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Copies font files from the selected directory to the app's internal storage. */
@SuppressLint("UseKtx")
fun copyFontsFromDirectory(
  context: Context,
  fileManager: FileManager,
  uriString: String,
): Int {
  return runCatching {
    val destinationPath = context.filesDir.path + "/fonts"
    val destinationDir = fileManager.fromPath(destinationPath)
    var copiedCount = 0

    if (!fileManager.exists(destinationDir)) {
      File(destinationPath).mkdirs()
    }

    val sourceDir = fileManager.fromUri(Uri.parse(uriString))
    if (sourceDir != null && fileManager.exists(sourceDir)) {
      fileManager.listFiles(sourceDir).forEach { file ->
        if (fileManager.isFile(file)) {
          val fileName = fileManager.getName(file)
          if (fileName.lowercase(Locale.ROOT).matches(".*\\.(ttf|otf|ttc)$".toRegex())) {
            val inputStream = fileManager.getInputStream(file) ?: return@forEach
            val outputFile = File(destinationPath, fileName)
            outputFile.outputStream().use { outputStream ->
              inputStream.use { it.copyTo(outputStream) }
            }
            copiedCount++
          }
        }
      }
    }
    copiedCount
  }.onFailure { e ->
    Log.e("SubtitlesPreferences", "Error copying fonts", e)
  }.getOrDefault(0)
}

// getSimplifiedPathFromUri is defined in AdvancedPreferencesScreen.kt within this package.

/** A physical font file, not a deduplicated family-name entry. */
data class CustomFontEntry(
  val familyName: String,
  val file: File,
)

/**
 * mpv selects fonts by family name, but users still need to see every imported file.
 * Give files that share a family a distinct, human-readable label without changing
 * the family name sent to mpv.
 */
data class SubtitleFontChoice(
  val label: String,
  val familyName: String,
)

fun subtitleFontChoices(
  downloadedFamilies: List<String>,
  customFonts: List<CustomFontEntry>,
): List<SubtitleFontChoice> {
  val downloaded = downloadedFamilies.distinct().toSet()
  val customFamilyCounts = customFonts.groupingBy { it.familyName }.eachCount()
  return (
    downloaded.map { family -> SubtitleFontChoice(label = family, familyName = family) } +
      customFonts.map { entry ->
        val nameConflict = (customFamilyCounts[entry.familyName] ?: 0) > 1 || entry.familyName in downloaded
        SubtitleFontChoice(
          label = if (nameConflict) "${entry.familyName} (${entry.file.name})" else entry.familyName,
          familyName = entry.familyName,
        )
      }
    ).distinctBy { it.label }
    .sortedBy { it.label.lowercase(Locale.ROOT) }
}

/**
 * Loads one entry for every valid custom font file, including files with matching
 * internal family names. A failed name-table parse must not silently discard a
 * font that Android's font engine can open.
 */
suspend fun loadCustomFontEntries(context: Context): List<CustomFontEntry> =
  withContext(Dispatchers.IO) {
    val fontsDir = File(context.filesDir, "fonts")
    if (!fontsDir.isDirectory) return@withContext emptyList()

    fontsDir.listFiles()
      ?.asSequence()
      ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in setOf("ttf", "otf", "ttc") }
      ?.mapNotNull { file ->
        val name = runCatching {
          file.inputStream().use { stream ->
            TTFFile.open(stream).families.values.firstOrNull()?.trim()
          }
        }.getOrNull()?.takeIf(String::isNotEmpty)
        if (name != null) {
          CustomFontEntry(name, file)
        } else {
          // Some valid merged / OpenType fonts cannot be named by TTFFile.
          if (runCatching { Typeface.createFromFile(file) }.isSuccess) {
            CustomFontEntry(file.nameWithoutExtension, file)
          } else {
            null
          }
        }
      }
      ?.sortedWith(
        compareBy<CustomFontEntry>({ it.familyName.lowercase(Locale.ROOT) }, { it.file.name.lowercase(Locale.ROOT) }),
      )
      ?.toList()
      .orEmpty()
  }
