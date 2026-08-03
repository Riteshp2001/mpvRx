/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.repository

import android.content.Context
import android.util.Log
import app.gyrolet.mpvrx.database.dao.SecureMediaDao
import app.gyrolet.mpvrx.database.entities.SecureMediaEntity
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import app.gyrolet.mpvrx.utils.media.PlaybackStateOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles moving media files in and out of app-private "secure" storage as part of the
 * Secure Folder feature, plus permanently deleting hidden files.
 *
 * Storage location: [Context.getFilesDir]/secure_media — private to the app, not visible
 * to other apps, MediaStore, or the system file manager.
 *
 * Mirrors the buffered-copy-with-progress approach used by
 * [app.gyrolet.mpvrx.utils.media.CopyPasteOps] so the UI layer (Step 4 progress dialog)
 * can drive off a familiar [StateFlow].
 */
class SecureFolderRepository(
  private val dao: SecureMediaDao,
) {
  companion object {
    private const val TAG = "SecureFolderRepository"
    private const val BUFFER_SIZE = 8 * 1024 // 8KB, matches CopyPasteOps
    private const val SECURE_DIR_NAME = "secure_media"
    private const val MAX_FILENAME_ATTEMPTS = 1000
  }

  data class SecureOperationProgress(
    val currentFile: String = "",
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 0,
    val currentFileProgress: Float = 0f,
    val overallProgress: Float = 0f,
    val isComplete: Boolean = false,
    val isCancelled: Boolean = false,
    val error: String? = null,
  )

  /** Result of a batch delete/restore: which ids succeeded vs failed, so DB rollback can be precise. */
  data class BatchResult(
    val succeededIds: List<Long>,
    val failedIds: List<Long>,
  )

  private val _progress = MutableStateFlow(SecureOperationProgress())
  val progress: StateFlow<SecureOperationProgress> = _progress.asStateFlow()

  private val isCancelled = AtomicBoolean(false)

  fun cancelOperation() {
    isCancelled.set(true)
  }

  private fun resetOperation() {
    isCancelled.set(false)
    _progress.value = SecureOperationProgress()
  }

  private fun secureDir(context: Context): File =
    File(context.filesDir, SECURE_DIR_NAME).apply { if (!exists()) mkdirs() }

  // ============================================================================
  // Move IN (hide) — MediaStore/filesystem video -> app-private secure storage
  // ============================================================================

  /**
   * Moves [videos] into secure storage one by one. Per-file failures don't abort the batch;
   * they're reported back so the caller (ViewModel) can surface which files failed.
   */
  suspend fun moveIn(
    context: Context,
    videos: List<Video>,
  ): Result<BatchResult> =
    withContext(Dispatchers.IO) {
      if (videos.isEmpty()) return@withContext Result.failure(IllegalArgumentException("No files to hide"))

      resetOperation()
      val destDir = secureDir(context)
      val succeeded = mutableListOf<Long>()
      val failed = mutableListOf<Long>()
      val totalBytes = videos.sumOf { it.size }.coerceAtLeast(1)
      var bytesDone = 0L

      videos.forEachIndexed { index, video ->
        try {
          checkCancellation()
          val sourceFile = File(video.path)
          if (!sourceFile.exists() || !sourceFile.canRead()) {
            Log.w(TAG, "Skipping unreadable source: ${video.path}")
            failed += video.id
            return@forEachIndexed
          }

          val destFile = uniqueFileIn(destDir, sourceFile.name)

          copyWithProgress(sourceFile, destFile, video.size) { fileProgress ->
            updateProgress(
              currentFile = sourceFile.name,
              currentFileIndex = index + 1,
              totalFiles = videos.size,
              currentFileProgress = fileProgress,
              bytesProcessed = bytesDone + (video.size * fileProgress).toLong(),
              totalBytes = totalBytes,
            )
          }

          // Verify before touching the original
          if (!destFile.exists() || destFile.length() != sourceFile.length()) {
            destFile.delete()
            throw IOException("Copy verification failed for ${sourceFile.name}")
          }

          val entityId =
            dao.insert(
              SecureMediaEntity(
                originalPath = sourceFile.absolutePath,
                secureFilePath = destFile.absolutePath,
                fileName = sourceFile.name,
                fileSize = sourceFile.length(),
                mimeType = video.mimeType,
                dateHidden = System.currentTimeMillis(),
              ),
            )

          // Only remove the original once it's safely recorded in the DB.
          val sourceDeleted = sourceFile.delete()
          if (!sourceDeleted) {
            // Rollback: we now have a duplicate on disk + a dangling DB row. Undo both
            // rather than leave the user with two copies and a broken "hidden" entry.
            Log.w(TAG, "Failed to delete original after copy, rolling back: ${video.path}")
            dao.deleteById(entityId)
            destFile.delete()
            failed += video.id
            return@forEachIndexed
          }

          removeFromMediaStore(context, video)
          RecentlyPlayedOps.onVideoDeleted(video.path)
          PlaybackStateOps.onVideoDeleted(video.path)

          succeeded += video.id
        } catch (e: Exception) {
          Log.e(TAG, "Failed to hide ${video.path}: ${e.message}", e)
          failed += video.id
        } finally {
          bytesDone += video.size
        }
      }

      MediaLibraryEvents.notifyChanged()
      _progress.value = _progress.value.copy(isComplete = true, overallProgress = 1f)

      if (succeeded.isEmpty() && failed.isNotEmpty()) {
        Result.failure(IOException("Failed to hide ${failed.size} file(s)"))
      } else {
        Result.success(BatchResult(succeeded, failed))
      }
    }

  // ============================================================================
  // Restore (unhide) — secure storage -> original location (or a safe fallback)
  // ============================================================================

  suspend fun restore(
    context: Context,
    entities: List<SecureMediaEntity>,
  ): Result<BatchResult> =
    withContext(Dispatchers.IO) {
      if (entities.isEmpty()) return@withContext Result.failure(IllegalArgumentException("No files to restore"))

      resetOperation()
      val succeeded = mutableListOf<Long>()
      val failed = mutableListOf<Long>()
      val totalBytes = entities.sumOf { it.fileSize }.coerceAtLeast(1)
      var bytesDone = 0L

      entities.forEachIndexed { index, entity ->
        try {
          checkCancellation()
          val secureFile = File(entity.secureFilePath)
          if (!secureFile.exists()) {
            Log.w(TAG, "Secure file missing, dropping DB row: ${entity.secureFilePath}")
            dao.deleteById(entity.id)
            failed += entity.id
            return@forEachIndexed
          }

          val restoreTarget = resolveRestoreTarget(entity)
          restoreTarget.parentFile?.let { if (!it.exists()) it.mkdirs() }

          copyWithProgress(secureFile, restoreTarget, entity.fileSize) { fileProgress ->
            updateProgress(
              currentFile = entity.fileName,
              currentFileIndex = index + 1,
              totalFiles = entities.size,
              currentFileProgress = fileProgress,
              bytesProcessed = bytesDone + (entity.fileSize * fileProgress).toLong(),
              totalBytes = totalBytes,
            )
          }

          if (!restoreTarget.exists() || restoreTarget.length() != secureFile.length()) {
            restoreTarget.delete()
            throw IOException("Restore verification failed for ${entity.fileName}")
          }

          // Only clear the secure copy + DB row once the restored file is confirmed on disk.
          val secureDeleted = secureFile.delete()
          if (!secureDeleted) {
            Log.w(TAG, "Restored but couldn't clean up secure copy: ${entity.secureFilePath}")
            // Not fatal for the user — the file is back. Still drop the DB row so it
            // doesn't show as hidden anymore; the orphaned secure copy is harmless.
          }
          dao.deleteById(entity.id)

          triggerMediaScan(context, restoreTarget.absolutePath)
          succeeded += entity.id
        } catch (e: Exception) {
          Log.e(TAG, "Failed to restore ${entity.fileName}: ${e.message}", e)
          failed += entity.id
        } finally {
          bytesDone += entity.fileSize
        }
      }

      MediaLibraryEvents.notifyChanged()
      _progress.value = _progress.value.copy(isComplete = true, overallProgress = 1f)

      if (succeeded.isEmpty() && failed.isNotEmpty()) {
        Result.failure(IOException("Failed to restore ${failed.size} file(s)"))
      } else {
        Result.success(BatchResult(succeeded, failed))
      }
    }

  // ============================================================================
  // Permanent delete — remove from secure storage entirely
  // ============================================================================

  /**
   * Permanently deletes secure files. If the physical delete fails for an entry, its DB row
   * is deliberately kept (rollback) rather than silently losing track of an orphaned file.
   */
  suspend fun deleteForever(entities: List<SecureMediaEntity>): Result<BatchResult> =
    withContext(Dispatchers.IO) {
      if (entities.isEmpty()) return@withContext Result.failure(IllegalArgumentException("No files to delete"))

      resetOperation()
      val succeeded = mutableListOf<Long>()
      val failed = mutableListOf<Long>()

      entities.forEachIndexed { index, entity ->
        try {
          checkCancellation()
          updateProgress(
            currentFile = entity.fileName,
            currentFileIndex = index + 1,
            totalFiles = entities.size,
            currentFileProgress = 1f,
            bytesProcessed = 0,
            totalBytes = 1,
          )

          val secureFile = File(entity.secureFilePath)
          val deleted = !secureFile.exists() || secureFile.delete()

          if (deleted) {
            dao.deleteById(entity.id)
            succeeded += entity.id
          } else {
            // Rollback: keep the DB row so the user can retry instead of losing the entry
            // for a file that's still sitting on disk.
            Log.w(TAG, "Failed to delete secure file, keeping DB row: ${entity.secureFilePath}")
            failed += entity.id
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to delete ${entity.fileName}: ${e.message}", e)
          failed += entity.id
        }
      }

      _progress.value = _progress.value.copy(isComplete = true, overallProgress = 1f)

      if (succeeded.isEmpty() && failed.isNotEmpty()) {
        Result.failure(IOException("Failed to delete ${failed.size} file(s)"))
      } else {
        Result.success(BatchResult(succeeded, failed))
      }
    }

  // ============================================================================
  // Reads
  // ============================================================================

  fun observeAll() = dao.observeAll()

  fun observeCount() = dao.observeCount()

  suspend fun getAll(): List<SecureMediaEntity> = dao.getAll()

  suspend fun getByIds(ids: List<Long>): List<SecureMediaEntity> = dao.getByIds(ids)

  // ============================================================================
  // Private helpers
  // ============================================================================

  /** Prefers restoring next to where the file originally lived; falls back if that's gone. */
  private fun resolveRestoreTarget(entity: SecureMediaEntity): File {
    val originalFile = File(entity.originalPath)
    val originalParent = originalFile.parentFile
    val candidateDir =
      if (originalParent != null && (originalParent.exists() || originalParent.mkdirs())) {
        originalParent
      } else {
        File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "Restored")
      }
    return uniqueFileIn(candidateDir, entity.fileName)
  }

  private fun uniqueFileIn(
    dir: File,
    fileName: String,
  ): File {
    if (!dir.exists()) dir.mkdirs()
    var candidate = File(dir, fileName)
    if (!candidate.exists()) return candidate

    val nameWithoutExt = candidate.nameWithoutExtension
    val ext = candidate.extension
    for (counter in 1..MAX_FILENAME_ATTEMPTS) {
      val newName = if (ext.isNotEmpty()) "${nameWithoutExt}_$counter.$ext" else "${nameWithoutExt}_$counter"
      candidate = File(dir, newName)
      if (!candidate.exists()) return candidate
    }
    throw IOException("Could not generate unique filename for $fileName")
  }

  private fun copyWithProgress(
    source: File,
    destination: File,
    fileSize: Long,
    onProgress: (Float) -> Unit,
  ) {
    try {
      FileInputStream(source).use { input ->
        FileOutputStream(destination).use { output ->
          val buffer = ByteArray(BUFFER_SIZE)
          var bytesCopied = 0L
          var bytesRead: Int

          while (input.read(buffer).also { bytesRead = it } != -1) {
            checkCancellation()
            output.write(buffer, 0, bytesRead)
            bytesCopied += bytesRead
            val fileProgress = if (fileSize > 0) bytesCopied.toFloat() / fileSize else 1f
            onProgress(fileProgress.coerceIn(0f, 1f))
          }
          output.flush()
        }
      }
      destination.setLastModified(source.lastModified())
    } catch (e: Exception) {
      destination.delete() // clean up partial file
      throw e
    }
  }

  private fun removeFromMediaStore(
    context: Context,
    video: Video,
  ) {
    runCatching {
      context.contentResolver.delete(video.uri, null, null)
    }.onFailure { e ->
      // Not fatal: the physical file is already gone, MediaStore will settle on next scan.
      Log.w(TAG, "Could not remove MediaStore row for ${video.path}: ${e.message}")
    }
  }

  private fun triggerMediaScan(
    context: Context,
    path: String,
  ) {
    runCatching {
      android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    }
  }

  private fun updateProgress(
    currentFile: String,
    currentFileIndex: Int,
    totalFiles: Int,
    currentFileProgress: Float,
    bytesProcessed: Long,
    totalBytes: Long,
  ) {
    val overall = if (totalBytes > 0) bytesProcessed.toFloat() / totalBytes else 0f
    _progress.value =
      _progress.value.copy(
        currentFile = currentFile,
        currentFileIndex = currentFileIndex,
        totalFiles = totalFiles,
        currentFileProgress = currentFileProgress.coerceIn(0f, 1f),
        overallProgress = overall.coerceIn(0f, 1f),
      )
  }

  private fun checkCancellation() {
    if (isCancelled.get()) {
      _progress.value = _progress.value.copy(isCancelled = true, error = "Operation cancelled")
      throw IOException("Operation cancelled by user")
    }
  }
}
