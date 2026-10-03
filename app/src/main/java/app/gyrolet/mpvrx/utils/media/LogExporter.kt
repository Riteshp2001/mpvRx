/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a captured log bundle into the user-selected mpvrx configuration folder.
 *
 * Goes through the same Storage Access Framework tree the rest of the configuration uses, so it
 * works wherever the user pointed the app (internal storage, SD card, USB OTG) without asking for
 * access again.
 */
object LogExporter {
  const val LOGS_DIRECTORY_NAME = "logs"

  private const val EXPORT_FILE_PREFIX = "mpvrx_logs_"
  private const val FILE_EXTENSION = ".txt"
  private const val FILE_MIME_TYPE = "text/plain"
  private const val TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss"

  /** Keeps only the newest few exports so repeated exports cannot fill the folder. */
  private const val RETAINED_EXPORTS = 10

  /**
   * Creates `logs/` under [treeUriString] when missing and writes [logText] into a timestamped
   * file. Returns the file name on success.
   */
  suspend fun exportToConfigurationFolder(
    context: Context,
    treeUriString: String,
    logText: String,
  ): Result<String> =
    withContext(Dispatchers.IO) {
      try {
        export(context, treeUriString, logText)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  private fun export(
    context: Context,
    treeUriString: String,
    logText: String,
  ): Result<String> {
    if (treeUriString.isBlank()) {
      error(context.getString(R.string.ui_no_storage_location_set))
    }
    if (logText.isBlank()) {
      error(context.getString(R.string.pref_advanced_logs_export_empty))
    }

    val root =
      openPersistedTreeDocument(context, treeUriString, requireWrite = true)
        ?: error(context.getString(R.string.ui_no_storage_location_set))

    val logsDirectory =
      root.findFile(LOGS_DIRECTORY_NAME)
        ?.takeIf { existing -> existing.isDirectory }
        ?: root.createDirectory(LOGS_DIRECTORY_NAME)
        ?: error(context.getString(R.string.pref_advanced_logs_folder_failed))

    val fileName = exportFileName()
    val created =
      logsDirectory.createFile(FILE_MIME_TYPE, fileName)
        // createFile may disambiguate a colliding name; the caller is told the real one.
        ?.also { file -> file.renameTo(fileName) }
        ?: error(context.getString(R.string.ui_failed_to_create_file))

    val stream =
      context.contentResolver.openOutputStream(created.uri, "wt")
        ?: error(context.getString(R.string.ui_failed_to_open_output_stream))
    stream.use { output ->
      output.write(logText.toByteArray(Charsets.UTF_8))
      output.flush()
    }

    pruneOldExports(logsDirectory)
    return Result.success(created.name ?: fileName)
  }

  private fun exportFileName(): String =
    EXPORT_FILE_PREFIX + SimpleDateFormat(TIMESTAMP_PATTERN, Locale.ROOT).format(Date()) + FILE_EXTENSION

  /**
   * Sorted by name rather than timestamp: the timestamp is part of the name, and SAF providers
   * are not required to expose a modification date, which would make every entry tie.
   */
  private fun pruneOldExports(logsDirectory: DocumentFile) {
    listTreeFilesSafely(logsDirectory)
      .filter { file -> file.isFile && file.name.orEmpty().startsWith(EXPORT_FILE_PREFIX) }
      .sortedByDescending { file -> file.name.orEmpty() }
      .drop(RETAINED_EXPORTS)
      .forEach { file -> runCatching { file.delete() } }
  }
}