/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.dao.SecureMediaDao
import app.gyrolet.mpvrx.database.entities.SecureMediaEntity
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
import app.gyrolet.mpvrx.utils.logging.Log
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import app.gyrolet.mpvrx.utils.media.PlaybackStateOps
import app.gyrolet.mpvrx.utils.media.ScopedHiddenFolderScanner
import app.gyrolet.mpvrx.utils.permission.PermissionUtils
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles moving media files in and out of app-private "secure" storage as part of the
 * Secure Folder feature, plus permanently deleting hidden files.
 *
 * Storage location: [Context.getFilesDir]/secure_media — private to the app, not visible
 * to other apps, MediaStore, or the system file manager.
 *
 * Play Store builds copy from content URIs on every supported Android version. SAF documents
 * use their provider's deletion API; MediaStore uses Android's approval flow where required.
 * Denied moves roll back their private copies. Android 10+ restores use MediaStore, while older
 * versions use a writable local folder or Movies/Music/Download -> Restored in shared storage.
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

  private data class PendingSecureMove(
    val video: Video,
    val entityId: Long,
    val file: File,
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
   * Moves [videos] into secure storage. Per-file failures don't abort the batch;
   * they're reported back so the caller (ViewModel) can surface which files failed.
   */
  suspend fun moveIn(
    context: Context,
    videos: List<Video>,
  ): Result<BatchResult> =
    withContext(Dispatchers.IO) {
      if (videos.isEmpty()) return@withContext Result.failure(IllegalArgumentException("No files to hide"))
      if (BuildConfig.SCOPED_STORAGE_ONLY) {
        return@withContext moveInScoped(context, videos.distinctBy { it.uri })
      }

      resetOperation()
      val destDir = secureDir(context)
      val succeeded = mutableListOf<Long>()
      val failed = mutableListOf<Long>()
      var firstError: Exception? = null
      val totalBytes = videos.sumOf { it.size }.coerceAtLeast(1)
      var bytesDone = 0L

      videos.forEachIndexed { index, video ->
        try {
          checkCancellation()
          val sourceFile = File(video.path)
          if (!sourceFile.exists() || !sourceFile.canRead()) {
            throw IOException(context.getString(R.string.secure_folder_source_unreadable, video.displayName))
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
            throw IOException(context.getString(R.string.storage_source_delete_failed))
          }

          removeFromMediaStore(context, video)
          RecentlyPlayedOps.onVideoDeleted(video.path)
          PlaybackStateOps.onVideoDeleted(video.path)

          succeeded += video.id
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Failed to hide ${video.path}: ${e.message}", e)
          if (firstError == null) firstError = e
          _progress.value = _progress.value.copy(error = e.localizedMessage)
          failed += video.id
        } finally {
          bytesDone += video.size
        }
      }

      MediaLibraryEvents.notifyChanged()
      _progress.value = _progress.value.copy(isComplete = true, overallProgress = 1f)

      if (succeeded.isEmpty() && failed.isNotEmpty()) {
        Result.failure(firstError ?: IOException("Failed to hide ${failed.size} file(s)"))
      } else {
        Result.success(BatchResult(succeeded, failed))
      }
    }

  private suspend fun moveInScoped(
    context: Context,
    videos: List<Video>,
  ): Result<BatchResult> {
    resetOperation()
    val pending = mutableListOf<PendingSecureMove>()
    val deletedUris = mutableSetOf<Uri>()
    val totalBytes = videos.sumOf { it.size.coerceAtLeast(0L) }.coerceAtLeast(1L)
    var bytesDone = 0L
    var operationError: Exception? = null

    fun recordFailure(
      video: Video,
      error: Exception,
    ) {
      val failure =
        IOException(
          "${video.displayName}: ${error.localizedMessage ?: error.javaClass.simpleName}",
          error,
        )
      if (operationError == null) operationError = failure
      _progress.value = _progress.value.copy(error = failure.message)
    }

    try {
      val destDir = secureDir(context)
      videos.forEachIndexed { index, video ->
        currentCoroutineContext().ensureActive()
        checkCancellation()
        var destination: File? = null
        var recorded = false
        try {
          val fileName = video.displayName.ifBlank { File(video.path).name }
          val destFile = uniqueFileIn(destDir, fileName)
          destination = destFile
          updateProgress(fileName, index + 1, videos.size, 0f, bytesDone, totalBytes)
          val copiedBytes =
            copyScopedSource(context, video, destFile) { progress ->
              updateProgress(
                fileName,
                index + 1,
                videos.size,
                progress,
                bytesDone + (video.size.coerceAtLeast(0L) * progress).toLong(),
                totalBytes,
              )
            }
          val originalPath = ScopedHiddenFolderScanner.localPathOf(video.uri) ?: video.path
          val entity =
            SecureMediaEntity(
              originalPath = originalPath,
              secureFilePath = destFile.absolutePath,
              fileName = fileName,
              fileSize = copiedBytes,
              mimeType = resolveMimeType(video.mimeType, fileName),
              dateHidden = System.currentTimeMillis(),
            )
          // Record the verified copy before Android can delete the source, including across cancellation.
          withContext(NonCancellable) {
            val entityId = dao.insert(entity)
            pending += PendingSecureMove(video, entityId, destFile)
            recorded = true
          }
        } catch (error: CancellationException) {
          throw error
        } catch (error: Exception) {
          Log.e(TAG, "Failed to prepare secure copy of ${video.displayName}", error)
          recordFailure(video, error)
        } finally {
          if (!recorded && destination != null) {
            try {
              discardPrivateCopy(destination)
            } catch (cleanupError: Exception) {
              Log.e(TAG, "Could not remove incomplete secure copy of ${video.displayName}", cleanupError)
              _progress.value = _progress.value.copy(error = cleanupError.message)
            }
          }
          bytesDone += video.size.coerceAtLeast(0L)
        }
      }

      currentCoroutineContext().ensureActive()
      checkCancellation()
      // Once approval is launched, wait for its result before deciding which copies can be rolled back.
      withContext(NonCancellable) {
        for (batch in pending.chunked(2_000)) {
          checkCancellation()
          PermissionUtils.StorageOps.deleteScopedVideosForMove(
            context,
            batch.map { it.video },
            onDeleteFailed = ::recordFailure,
          ) { video ->
            deletedUris += video.uri
          }
        }
      }
    } catch (error: CancellationException) {
      _progress.value = _progress.value.copy(isCancelled = true, error = "Operation cancelled")
      throw error
    } catch (error: Exception) {
      if (operationError == null) operationError = error
      Log.e(TAG, "Scoped secure move failed", error)
      _progress.value = _progress.value.copy(error = error.message)
    } finally {
      withContext(NonCancellable) {
        pending.forEach { move ->
          try {
            if (move.video.uri in deletedUris) {
              PlaybackStateOps.onScopedVideoDeleted(move.video)
            } else {
              dao.deleteById(move.entityId)
              discardPrivateCopy(move.file)
            }
          } catch (error: Exception) {
            Log.e(TAG, "Could not finalize secure move for ${move.video.displayName}", error)
            _progress.value = _progress.value.copy(error = error.message)
          }
        }
      }
      MediaLibraryEvents.notifyChanged()
      _progress.value =
        _progress.value.copy(
          isComplete = true,
          overallProgress = if (_progress.value.isCancelled) _progress.value.overallProgress else 1f,
        )
    }

    val succeeded = videos.filter { it.uri in deletedUris }.map { it.id }
    val failed = videos.filterNot { it.uri in deletedUris }.map { it.id }
    return if (succeeded.isEmpty()) {
      Result.failure(operationError ?: IOException("Failed to hide ${failed.size} file(s)"))
    } else {
      Result.success(BatchResult(succeeded, failed))
    }
  }

  private suspend fun copyScopedSource(
    context: Context,
    video: Video,
    destination: File,
    onProgress: (Float) -> Unit,
  ): Long {
    val descriptor =
      context.contentResolver.openAssetFileDescriptor(video.uri, "r")
        ?: throw IOException("Could not open ${video.displayName}")
    return descriptor.use { source ->
      // Cached MediaStore sizes are progress hints, not proof of an unknown-length stream's size.
      val expectedSize = source.length.takeIf { it >= 0L }
      val progressSize = expectedSize ?: video.size.coerceAtLeast(0L)
      val copied =
        source.createInputStream().use { input ->
          FileOutputStream(destination).use { output ->
            copyWithProgress(input, output, progressSize, onProgress).also { output.fd.sync() }
          }
        }
      if (destination.length() != copied || (expectedSize != null && copied != expectedSize)) {
        throw IOException("Copy verification failed for ${video.displayName}")
      }
      copied
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
      var firstRestoreError: Exception? = null
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

          val onProgress: (Float) -> Unit = { fileProgress ->
            updateProgress(
              currentFile = entity.fileName,
              currentFileIndex = index + 1,
              totalFiles = entities.size,
              currentFileProgress = fileProgress,
              bytesProcessed = bytesDone + (entity.fileSize * fileProgress).toLong(),
              totalBytes = totalBytes,
            )
          }

          if (BuildConfig.SCOPED_STORAGE_ONLY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            restoreScopedFile(context, entity, secureFile, onProgress)
            succeeded += entity.id
            return@forEachIndexed
          }

          val restoreTarget = resolveRestoreTarget(entity)
          restoreTarget.parentFile?.let { if (!it.exists()) it.mkdirs() }
          copyWithProgress(secureFile, restoreTarget, entity.fileSize, onProgress)

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
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Failed to restore ${entity.fileName}: ${e.message}", e)
          if (firstRestoreError == null) firstRestoreError = e
          _progress.value = _progress.value.copy(error = e.localizedMessage)
          failed += entity.id
        } finally {
          bytesDone += entity.fileSize
        }
      }

      MediaLibraryEvents.notifyChanged()
      _progress.value = _progress.value.copy(isComplete = true, overallProgress = 1f)

      if (succeeded.isEmpty() && failed.isNotEmpty()) {
        Result.failure(firstRestoreError ?: IOException("Failed to restore ${failed.size} file(s)"))
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
  // Destroy — wipe the whole Secure Folder
  // ============================================================================

  /**
   * Permanently deletes every secured file, then sweeps any orphaned files left in the secure
   * directory. Fails (without partial success) if anything could not be removed, so the caller
   * only resets the PIN once nothing recoverable is left behind.
   */
  suspend fun destroyAll(context: Context): Result<Int> =
    withContext(Dispatchers.IO) {
      val entities = dao.getAll()
      if (entities.isNotEmpty()) {
        val batch = deleteForever(entities).getOrElse { return@withContext Result.failure(it) }
        if (batch.failedIds.isNotEmpty()) {
          return@withContext Result.failure(IOException("Failed to delete ${batch.failedIds.size} file(s)"))
        }
      }

      val leftovers = secureDir(context).listFiles().orEmpty().count { !it.delete() }
      if (leftovers > 0) {
        return@withContext Result.failure(IOException("Failed to delete $leftovers leftover file(s)"))
      }
      Result.success(entities.size)
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

  private data class ScopedRestoreLocation(
    val collection: Uri,
    val relativePath: String,
  )

  @RequiresApi(Build.VERSION_CODES.Q)
  private suspend fun restoreScopedFile(
    context: Context,
    entity: SecureMediaEntity,
    secureFile: File,
    onProgress: (Float) -> Unit,
  ) {
    val resolver = context.contentResolver
    val mimeType = resolveMimeType(entity.mimeType, entity.fileName)
    val location = resolveScopedRestoreLocation(context, entity.originalPath, mimeType)
    val values =
      ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, entity.fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }

    fun insertPending(target: ScopedRestoreLocation): Uri {
      val targetValues =
        ContentValues(values).apply {
          put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
        }
      return resolver.insert(target.collection, targetValues)
        ?: throw IOException("Could not create restored media for ${entity.fileName}")
    }

    // Inserting a new row lets MediaStore allocate a collision-safe name without overwriting a file.
    val restoredUri =
      try {
        insertPending(location)
      } catch (error: Exception) {
        if (error !is SecurityException && error !is IllegalArgumentException && error !is IOException) throw error
        val fallback = resolveScopedRestoreLocation(context, "", mimeType)
        if (location == fallback) throw error
        Log.w(TAG, "Original restore location rejected; retrying in ${fallback.relativePath}", error)
        try {
          insertPending(fallback)
        } catch (fallbackError: Exception) {
          fallbackError.addSuppressed(error)
          throw fallbackError
        }
      }
    var published = false

    try {
      val expectedSize = secureFile.length()
      val copied =
        FileInputStream(secureFile).use { input ->
          val output =
            resolver.openOutputStream(restoredUri, "w")
              ?: throw IOException("Could not open restored media for ${entity.fileName}")
          output.use { copyWithProgress(input, it, expectedSize, onProgress) }
        }
      if (copied != expectedSize || scopedContentLength(context, restoredUri) != expectedSize) {
        throw IOException("Restore verification failed for ${entity.fileName}")
      }
      currentCoroutineContext().ensureActive()
      checkCancellation()
      withContext(NonCancellable) {
        checkCancellation()
        val publishedRows =
          resolver.update(
            restoredUri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
          )
        if (publishedRows <= 0) throw IOException("Could not publish restored media for ${entity.fileName}")
        published = true
        dao.deleteById(entity.id)
        if (!secureFile.delete()) {
          val message = "Restored but couldn't clean up secure copy: ${entity.secureFilePath}"
          Log.w(TAG, message)
          _progress.value = _progress.value.copy(error = message)
        }
      }
    } catch (error: Exception) {
      if (!published) {
        withContext(NonCancellable) {
          try {
            if (resolver.delete(restoredUri, null, null) <= 0) {
              Log.w(TAG, "Unfinished restore was already removed: $restoredUri")
            }
          } catch (cleanupError: Exception) {
            error.addSuppressed(cleanupError)
            Log.e(TAG, "Could not remove unfinished restore: $restoredUri", cleanupError)
          }
        }
      }
      throw error
    }
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun resolveScopedRestoreLocation(
    context: Context,
    originalPath: String,
    mimeType: String,
  ): ScopedRestoreLocation {
    val audio = mimeType.startsWith("audio/")
    val video = mimeType.startsWith("video/")
    val defaultDirectory =
      when {
        audio -> Environment.DIRECTORY_MUSIC
        video -> Environment.DIRECTORY_MOVIES
        else -> Environment.DIRECTORY_DOWNLOADS
      }

    fun collection(
      volume: String,
      downloads: Boolean,
    ): Uri =
      when {
        downloads -> MediaStore.Downloads.getContentUri(volume)
        audio -> MediaStore.Audio.Media.getContentUri(volume)
        else -> MediaStore.Video.Media.getContentUri(volume)
      }

    val localPath =
      if (originalPath.startsWith("content://")) {
        ScopedHiddenFolderScanner.localPathOf(Uri.parse(originalPath))
      } else {
        originalPath
      }
    val originalParent = localPath?.let { File(it).parent }
    val audioDirectories =
      setOf(
        Environment.DIRECTORY_MUSIC,
        Environment.DIRECTORY_PODCASTS,
        Environment.DIRECTORY_RINGTONES,
        Environment.DIRECTORY_ALARMS,
        Environment.DIRECTORY_NOTIFICATIONS,
        Environment.DIRECTORY_AUDIOBOOKS,
      ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setOf(Environment.DIRECTORY_RECORDINGS) else emptySet()

    for (volume in MediaStore.getExternalVolumeNames(context)) {
      val root =
        if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
          Environment.getExternalStorageDirectory().absolutePath
        } else {
          "/storage/$volume"
        }
      if (originalParent == null || !originalParent.startsWith("$root/", ignoreCase = true)) continue
      val relativePath = originalParent.substring(root.length + 1).trimEnd('/')
      val segments = relativePath.split('/')
      if (segments.any { it.isBlank() || it == ".." || it.startsWith(".") }) continue
      val topDirectory = segments.first()
      val downloads = topDirectory == Environment.DIRECTORY_DOWNLOADS
      val supported =
        downloads ||
          (audio && topDirectory in audioDirectories) ||
          (
            video &&
              topDirectory in
              setOf(
                Environment.DIRECTORY_MOVIES,
                Environment.DIRECTORY_DCIM,
                Environment.DIRECTORY_PICTURES,
              )
          )
      if (supported) return ScopedRestoreLocation(collection(volume, downloads), "$relativePath/")
    }

    val fallbackPath = "$defaultDirectory/Restored/"
    Log.i(TAG, "Original restore directory is unavailable or unsupported; using $fallbackPath")
    return ScopedRestoreLocation(
      collection(MediaStore.VOLUME_EXTERNAL_PRIMARY, defaultDirectory == Environment.DIRECTORY_DOWNLOADS),
      fallbackPath,
    )
  }

  private fun resolveMimeType(
    mimeType: String,
    fileName: String,
  ): String =
    if (mimeType.isBlank() || mimeType.endsWith("/*") || mimeType == "application/octet-stream") {
      FileTypeUtils.getMimeTypeFromExtension(File(fileName).extension)
    } else {
      mimeType
    }

  private suspend fun scopedContentLength(
    context: Context,
    uri: Uri,
  ): Long {
    val descriptor =
      context.contentResolver.openAssetFileDescriptor(uri, "r")
        ?: throw IOException("Could not verify restored media: $uri")
    return descriptor.use { source ->
      if (source.length >= 0L) {
        source.length
      } else {
        source.createInputStream().use { input ->
          val buffer = ByteArray(BUFFER_SIZE)
          var length = 0L
          var read: Int
          while (input.read(buffer).also { read = it } != -1) {
            currentCoroutineContext().ensureActive()
            checkCancellation()
            length += read
          }
          length
        }
      }
    }
  }

  private fun discardPrivateCopy(file: File) {
    if (file.exists() && !file.delete()) throw IOException("Could not remove unfinished secure copy: ${file.name}")
  }

  /** URI grants do not grant filesystem access; only reuse an absolute, writable local folder. */
  private fun resolveRestoreTarget(entity: SecureMediaEntity): File {
    val originalUri = Uri.parse(entity.originalPath)
    val originalLocalPath =
      when (originalUri.scheme) {
        "content" -> ScopedHiddenFolderScanner.localPathOf(originalUri)
        "file" -> originalUri.path
        null -> entity.originalPath
        else -> null
      }
    val originalParent =
      originalLocalPath
        ?.let { File(it) }
        ?.takeIf { it.isAbsolute }
        ?.parentFile
    if (originalParent != null &&
      (originalParent.isDirectory || originalParent.mkdirs()) &&
      originalParent.canWrite()
    ) {
      return uniqueFileIn(originalParent, entity.fileName)
    }

    val mimeType = resolveMimeType(entity.mimeType, entity.fileName)
    val directory =
      when {
        mimeType.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
        mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES
        else -> Environment.DIRECTORY_DOWNLOADS
      }
    val fallback = File(Environment.getExternalStoragePublicDirectory(directory), "Restored")
    Log.i(TAG, "Original restore folder is unavailable or not writable; using ${fallback.absolutePath}")
    return uniqueFileIn(fallback, entity.fileName)
  }

  private fun uniqueFileIn(
    dir: File,
    fileName: String,
  ): File {
    require(
      fileName.isNotBlank() &&
        File(fileName).name == fileName &&
        fileName != "." &&
        fileName != ".." &&
        '\u0000' !in fileName,
    ) { "Invalid media filename" }
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create media directory: ${dir.absolutePath}")
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

  private suspend fun copyWithProgress(
    source: File,
    destination: File,
    fileSize: Long,
    onProgress: (Float) -> Unit,
  ) {
    try {
      FileInputStream(source).use { input ->
        FileOutputStream(destination).use { output ->
          copyWithProgress(input, output, fileSize, onProgress)
        }
      }
      destination.setLastModified(source.lastModified())
    } catch (e: Exception) {
      try {
        discardPrivateCopy(destination)
      } catch (cleanupError: Exception) {
        e.addSuppressed(cleanupError)
        Log.e(TAG, "Could not clean up partial copy", cleanupError)
      }
      throw e
    }
  }

  private suspend fun copyWithProgress(
    input: InputStream,
    output: OutputStream,
    fileSize: Long,
    onProgress: (Float) -> Unit,
  ): Long {
    val buffer = ByteArray(BUFFER_SIZE)
    var bytesCopied = 0L
    var bytesRead: Int
    while (input.read(buffer).also { bytesRead = it } != -1) {
      currentCoroutineContext().ensureActive()
      checkCancellation()
      output.write(buffer, 0, bytesRead)
      bytesCopied += bytesRead
      val progress = if (fileSize > 0) bytesCopied.toFloat() / fileSize else 1f
      onProgress(progress.coerceIn(0f, 1f))
    }
    output.flush()
    return bytesCopied
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
