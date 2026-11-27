/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.permission

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
import app.gyrolet.mpvrx.utils.logging.Log
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import app.gyrolet.mpvrx.utils.media.PlaybackStateOps
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Simplified storage permission utilities with MANAGE_EXTERNAL_STORAGE support.
 */
object PermissionUtils {
  private var mediaRequestLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
  private var resultOkCallback: () -> Unit = {}
  private var resultCancelledCallback: () -> Unit = {}
  private var mediaRequestPending = false

  /**
   * Set the media access launcher from MainActivity.
   */
  fun setMediaAccessLauncher(launcher: ActivityResultLauncher<IntentSenderRequest>) {
    mediaRequestLauncher = launcher
  }

  /**
   * Handle result from MainActivity's launcher callback.
   */
  fun handleMediaAccessResult(resultCode: Int) {
    val callback = if (resultCode == Activity.RESULT_OK) resultOkCallback else resultCancelledCallback
    mediaRequestPending = false
    resultOkCallback = {}
    resultCancelledCallback = {}
    callback()
  }

  private suspend fun requestMediaApproval(createSender: () -> IntentSender): Boolean =
    withContext(Dispatchers.Main) {
      if (mediaRequestPending) {
        Log.w("PermissionUtils", "A media permission request is already pending")
        throw IllegalStateException("Another media permission request is already pending")
      }
      val launcher = mediaRequestLauncher
      if (launcher == null) {
        Log.w("PermissionUtils", "No activity is available for media permission approval")
        throw IllegalStateException("No activity is available for media permission approval")
      }
      val sender = createSender()
      suspendCancellableCoroutine { continuation ->
        mediaRequestPending = true
        resultOkCallback = {
          if (continuation.isActive) continuation.resumeWith(Result.success(true))
        }
        resultCancelledCallback = {
          if (continuation.isActive) continuation.resumeWith(Result.success(false))
        }
        try {
          launcher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (error: Exception) {
          mediaRequestPending = false
          resultOkCallback = {}
          resultCancelledCallback = {}
          Log.e("PermissionUtils", "Could not launch media permission approval", error)
          if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }
      }
    }

  private fun isMediaStoreUri(uri: Uri): Boolean = uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY

  suspend fun requestScopedDeleteAccess(
    context: Context,
    uris: List<Uri>,
  ): Boolean = requestDeleteAccess(context, uris)

  private suspend fun requestWriteAccess(
    context: Context,
    uris: List<Uri>,
  ): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return true
    return requestMediaApproval {
      MediaStore.createWriteRequest(context.contentResolver, uris).intentSender
    }
  }

  private suspend fun requestDeleteAccess(
    context: Context,
    uris: List<Uri>,
  ): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return true
    require(uris.all(::isMediaStoreUri)) { "Delete approval requires MediaStore URIs" }
    return requestMediaApproval {
      MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
    }
  }

  /**
   * Returns the runtime permission for the active storage model and media type.
   */
  fun getStoragePermission(audioOnly: Boolean = false): String =
    when {
      BuildConfig.SCOPED_STORAGE_ONLY && Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
        android.Manifest.permission.READ_EXTERNAL_STORAGE

      Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q -> {
        // Legacy storage on Android 10 and below needs write access for file management.
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
      }

      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
        // Android 13+: request media-specific permission.
        // Audio-only browsers (e.g. Music > Folders) need READ_MEDIA_AUDIO, not READ_MEDIA_VIDEO,
        // or MediaStore audio queries return nothing even though the user "granted" access.
        if (audioOnly) {
          android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
          android.Manifest.permission.READ_MEDIA_VIDEO
        }
      }

      else -> android.Manifest.permission.READ_EXTERNAL_STORAGE
    }

  /**
   * Creates a permission state for storage access.
   */
  @OptIn(ExperimentalPermissionsApi::class)
  @Composable
  fun rememberStoragePermissionState(audioOnly: Boolean = false): PermissionState {
    val storagePermission = getStoragePermission(audioOnly)
    if (BuildConfig.IS_PLAY_STORE_BUILD &&
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      !audioOnly
    ) {
      val mediaPermissionsState =
        rememberMultiplePermissionsState(
          listOf(storagePermission, android.Manifest.permission.READ_MEDIA_AUDIO),
        )
      val videoPermissionState =
        mediaPermissionsState.permissions.first { it.permission == storagePermission }
      return remember(mediaPermissionsState, videoPermissionState) {
        object : PermissionState {
          override val permission = storagePermission
          override val status: PermissionStatus
            get() = videoPermissionState.status

          override fun launchPermissionRequest() = mediaPermissionsState.launchMultiplePermissionRequest()
        }
      }
    }
    return rememberPermissionState(storagePermission)
  }

  /**
   * Handles storage permission and invokes [onPermissionGranted] when granted.
   * On Android 11+, also checks MANAGE_EXTERNAL_STORAGE permission.
   *
   * @param audioOnly When true, requests READ_MEDIA_AUDIO instead of READ_MEDIA_VIDEO on Android 13+,
   * for screens that only browse the audio library (e.g. Music > Folders).
   */
  @OptIn(ExperimentalPermissionsApi::class)
  @Composable
  fun handleStoragePermission(
    audioOnly: Boolean = false,
    onPermissionGranted: () -> Unit,
  ): PermissionState {
    val permissionState = rememberStoragePermissionState(audioOnly)
    val context = LocalContext.current
    var lifecycleTrigger by remember { mutableIntStateOf(0) }

    // Re-check permission when app resumes from Settings
    DisposableEffect(Unit) {
      val lifecycleOwner = context as? LifecycleOwner
      val observer =
        LifecycleEventObserver { _, event ->
          if (event == Lifecycle.Event.ON_RESUME) {
            lifecycleTrigger++
          }
        }
      lifecycleOwner?.lifecycle?.addObserver(observer)
      onDispose {
        lifecycleOwner?.lifecycle?.removeObserver(observer)
      }
    }

    // Wrap permission state to consider MANAGE_EXTERNAL_STORAGE on Android 11+
    val effectivePermissionState =
      remember(permissionState.status, lifecycleTrigger) {
        if (!BuildConfig.SCOPED_STORAGE_ONLY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          if (android.os.Environment.isExternalStorageManager()) {
            object : PermissionState {
              override val permission = permissionState.permission
              override val status = PermissionStatus.Granted

              override fun launchPermissionRequest() = permissionState.launchPermissionRequest()
            }
          } else {
            object : PermissionState {
              override val permission = permissionState.permission
              override val status = PermissionStatus.Denied(false)

              override fun launchPermissionRequest() = permissionState.launchPermissionRequest()
            }
          }
        } else {
          permissionState
        }
      }

    var previouslyGranted by androidx.compose.runtime.saveable.rememberSaveable(audioOnly) {
      androidx.compose.runtime.mutableStateOf(effectivePermissionState.status == PermissionStatus.Granted)
    }
    LaunchedEffect(effectivePermissionState.status) {
      val isGranted = effectivePermissionState.status == PermissionStatus.Granted
      val becameGranted = isGranted && !previouslyGranted
      previouslyGranted = isGranted
      if (becameGranted) {
        onPermissionGranted()
      }
    }

    return effectivePermissionState
  }

  // --------------------------------------------------------------------------
  // Storage operations (automatically uses scoped storage or direct file access)
  // --------------------------------------------------------------------------

  object StorageOps {
    private const val TAG = "StorageOps"

    /**
     * Delete videos using direct file operations (requires MANAGE_EXTERNAL_STORAGE on Android 11+)
     */
    suspend fun deleteVideos(
      context: Context,
      videos: List<Video>,
      onDeleted: (Video) -> Unit = {},
    ): Pair<Int, Int> =
      withContext(Dispatchers.IO) {
        if (!BuildConfig.SCOPED_STORAGE_ONLY) {
          deleteVideosDirectly(context, videos, onDeleted)
        } else {
          deleteVideosScoped(context, videos, onDeleted)
        }
      }

    /**
     * Delete videos using direct file operations (requires MANAGE_EXTERNAL_STORAGE on Android 11+)
     */
    private suspend fun deleteVideosDirectly(
      context: Context,
      videos: List<Video>,
      onDeleted: (Video) -> Unit,
    ): Pair<Int, Int> =
      withContext(Dispatchers.IO) {
        var deleted = 0
        var failed = 0
        val deletedPaths = mutableListOf<String>()

        for (video in videos) {
          try {
            val file = File(video.path)
            if (file.exists() && file.delete()) {
              deleted++
              deletedPaths += video.path
              onDeleted(video)
              RecentlyPlayedOps.onVideoDeleted(video.path)
              PlaybackStateOps.onVideoDeleted(video.path)
              Log.d(TAG, "✓ Deleted: ${video.displayName}")
            } else {
              failed++
              Log.w(TAG, "✗ Failed to delete: ${video.displayName}")
            }
          } catch (e: Exception) {
            failed++
            Log.e(TAG, "✗ Error deleting ${video.displayName}", e)
          }
        }

        // Notify that media library has changed
        if (deleted > 0) {
          scanChangedPaths(context, deletedPaths)
          MediaLibraryEvents.notifyChanged()
        }

        Pair(deleted, failed)
      }

    /**
     * Delete videos via scoped storage (Play Store flavor / no MANAGE_EXTERNAL_STORAGE)
     */
    internal suspend fun deleteScopedVideosForMove(
      context: Context,
      videos: List<Video>,
      onDeleteFailed: ((Video, Exception) -> Unit)? = null,
      onDeleted: (Video) -> Unit,
    ): Pair<Int, Int> {
      check(BuildConfig.SCOPED_STORAGE_ONLY)
      return deleteVideosScoped(
        context,
        videos,
        onDeleted,
        preserveHistory = true,
        onDeleteFailed = onDeleteFailed,
      )
    }

    private suspend fun deleteVideosScoped(
      context: Context,
      videos: List<Video>,
      onDeleted: (Video) -> Unit,
      preserveHistory: Boolean = false,
      onDeleteFailed: ((Video, Exception) -> Unit)? = null,
    ): Pair<Int, Int> =
      withContext(Dispatchers.IO) {
        var deleted = 0
        var failed = 0
        val deletedFilePaths = mutableListOf<String>()

        val contentVideos = videos.filter { isMediaStoreUri(it.uri) }
        val documentVideos = videos.filter { it.uri.scheme == "content" && !isMediaStoreUri(it.uri) }
        val fileVideos = videos.filter { it.uri.scheme != "content" }

        if (contentVideos.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          // Android 16 caps each request at 2,000 URIs.
          for (batch in contentVideos.chunked(2_000)) {
            var requestError: Exception? = null
            val granted =
              try {
                requestDeleteAccess(context, batch.map { it.uri })
              } catch (error: CancellationException) {
                throw error
              } catch (error: Exception) {
                Log.e(TAG, "Could not request media deletion", error)
                requestError = error
                false
              }
            if (granted) {
              batch.forEach { video ->
                deleted++
                onDeleted(video)
                if (!preserveHistory) PlaybackStateOps.onScopedVideoDeleted(video)
                Log.d(TAG, "✓ Deleted (scoped request): ${video.displayName}")
              }
            } else {
              failed += batch.size
              Log.w(TAG, "✗ Delete request denied/cancelled for ${batch.size} item(s)")
              val error = requestError ?: IOException(context.getString(R.string.storage_source_delete_not_approved))
              batch.forEach { video -> onDeleteFailed?.invoke(video, error) }
            }
          }
        } else {
          for (video in contentVideos) {
            try {
              val rows =
                try {
                  context.contentResolver.delete(video.uri, null, null)
                } catch (error: SecurityException) {
                  if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q &&
                    error is RecoverableSecurityException &&
                    requestMediaApproval { error.userAction.actionIntent.intentSender }
                  ) {
                    context.contentResolver.delete(video.uri, null, null)
                  } else {
                    throw error
                  }
                }
              if (rows > 0) {
                deleted++
                onDeleted(video)
                if (!preserveHistory) PlaybackStateOps.onScopedVideoDeleted(video)
                Log.d(TAG, "✓ Deleted (scoped): ${video.displayName}")
              } else {
                failed++
                Log.w(TAG, "✗ Failed to delete (scoped): ${video.displayName}")
                onDeleteFailed?.invoke(video, IOException(context.getString(R.string.storage_source_delete_failed)))
              }
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              failed++
              Log.e(TAG, "✗ Error deleting (scoped) ${video.displayName}", e)
              onDeleteFailed?.invoke(video, e)
            }
          }
        }

        for (video in documentVideos) {
          try {
            check(DocumentsContract.isDocumentUri(context, video.uri)) { "Unsupported content provider" }
            check(DocumentsContract.deleteDocument(context.contentResolver, video.uri)) { "Document deletion failed" }
            deleted++
            onDeleted(video)
            if (!preserveHistory) PlaybackStateOps.onScopedVideoDeleted(video)
          } catch (error: CancellationException) {
            throw error
          } catch (error: Exception) {
            failed++
            Log.e(TAG, "Cannot delete document: ${video.displayName}", error)
            onDeleteFailed?.invoke(video, error)
          }
        }

        for (video in fileVideos) {
          try {
            val file = File(video.path)
            if (file.exists() && file.delete()) {
              deleted++
              deletedFilePaths += video.path
              onDeleted(video)
              if (!preserveHistory) PlaybackStateOps.onScopedVideoDeleted(video)
              Log.d(TAG, "✓ Deleted (file fallback): ${video.displayName}")
            } else {
              failed++
              Log.w(TAG, "✗ Failed to delete (file fallback): ${video.displayName}")
              onDeleteFailed?.invoke(video, IOException(context.getString(R.string.storage_source_delete_failed)))
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            failed++
            Log.e(TAG, "✗ Error deleting (file fallback) ${video.displayName}", e)
            onDeleteFailed?.invoke(video, e)
          }
        }

        if (deleted > 0) {
          scanChangedPaths(context, deletedFilePaths)
          MediaLibraryEvents.notifyChanged()
        }

        Pair(deleted, failed)
      }

    /**
     * Rename video using direct file operations (requires MANAGE_EXTERNAL_STORAGE on Android 11+)
     */
    suspend fun renameVideo(
      context: Context,
      video: Video,
      newDisplayName: String,
    ): Result<Unit> =
      withContext(Dispatchers.IO) {
        if (!isValidFileName(newDisplayName)) {
          return@withContext Result.failure(IllegalArgumentException("Invalid file name"))
        }
        if (!BuildConfig.SCOPED_STORAGE_ONLY) {
          renameVideoDirectly(context, video, newDisplayName)
        } else {
          renameVideoScoped(context, video, newDisplayName)
        }
      }

    /**
     * Rename video using direct file operations (requires MANAGE_EXTERNAL_STORAGE on Android 11+)
     */
    private suspend fun renameVideoDirectly(
      context: Context,
      video: Video,
      newDisplayName: String,
    ): Result<Unit> =
      withContext(Dispatchers.IO) {
        try {
          val oldFile = File(video.path)
          val newFile = File(oldFile.parentFile, newDisplayName)

          if (oldFile.absolutePath == newFile.absolutePath) {
            return@withContext Result.success(Unit)
          }

          if (newFile.exists()) {
            return@withContext Result.failure(FileAlreadyExistsException(newFile, null, "Target file already exists"))
          }

          if (oldFile.exists() && oldFile.renameTo(newFile)) {
            // Update history
            RecentlyPlayedOps.onVideoRenamed(oldFile.absolutePath, newFile.absolutePath)
            PlaybackStateOps.onVideoRenamed(oldFile.absolutePath, newFile.absolutePath)

            // Notify that media library has changed
            MediaLibraryEvents.notifyChanged()

            Log.d(TAG, "✓ Renamed: ${video.displayName} -> $newDisplayName")
            try {
              android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(oldFile.absolutePath, newFile.absolutePath),
                null,
                null,
              )
            } catch (e: Exception) {
              Log.w(TAG, "Media scan failed after rename: ${e.message}")
            }
            Result.success(Unit)
          } else {
            Log.w(TAG, "✗ Rename failed: ${video.displayName}")
            Result.failure(IllegalStateException("Rename operation failed"))
          }
        } catch (e: Exception) {
          Log.e(TAG, "✗ Error renaming ${video.displayName}", e)
          Result.failure(e)
        }
      }

    suspend fun renameFolder(
      context: Context,
      sourcePath: String,
      newName: String,
    ): Boolean =
      withContext(Dispatchers.IO) {
        if (BuildConfig.SCOPED_STORAGE_ONLY) {
          Log.w(TAG, "Folder rename is unavailable with scoped storage")
          return@withContext false
        }
        val source = File(sourcePath)
        val parent = source.parentFile ?: return@withContext false
        if (!isValidFileName(newName)) return@withContext false
        val destination = File(parent, newName)
        if (!source.isDirectory) return@withContext false
        if (source.absolutePath == destination.absolutePath) return@withContext true
        if (destination.exists()) return@withContext false

        val mediaPaths =
          source
            .walkTopDown()
            .filter { file ->
              file.isFile &&
                (
                  file.extension.lowercase() in FileTypeUtils.VIDEO_EXTENSIONS ||
                    file.extension.lowercase() in FileTypeUtils.AUDIO_EXTENSIONS
                )
            }.map { file ->
              file.absolutePath to File(destination, file.relativeTo(source).path).absolutePath
            }.toList()

        if (!source.renameTo(destination)) return@withContext false

        mediaPaths.forEach { (oldPath, newPath) ->
          RecentlyPlayedOps.onVideoRenamed(oldPath, newPath)
          PlaybackStateOps.onVideoRenamed(oldPath, newPath)
        }
        scanChangedPaths(context, mediaPaths.flatMap { (oldPath, newPath) -> listOf(oldPath, newPath) })
        MediaLibraryEvents.notifyChanged()
        true
      }

    private fun scanChangedPaths(
      context: Context,
      paths: Collection<String>,
    ) {
      if (paths.isEmpty()) return
      runCatching {
        android.media.MediaScannerConnection.scanFile(context, paths.distinct().toTypedArray(), null, null)
      }.onFailure { error ->
        Log.w(TAG, "Media scan failed after storage operation", error)
      }
    }

    private fun isValidFileName(name: String): Boolean =
      name.isNotBlank() &&
        name != "." &&
        name != ".." &&
        '/' !in name &&
        '\\' !in name &&
        '\u0000' !in name

    /**
     * Rename video via MediaStore update (scoped storage path)
     */
    private suspend fun renameVideoScoped(
      context: Context,
      video: Video,
      newDisplayName: String,
    ): Result<Unit> =
      withContext(Dispatchers.IO) {
        try {
          if (video.uri.scheme != "content") {
            return@withContext renameVideoDirectly(context, video, newDisplayName)
          }

          if (!isMediaStoreUri(video.uri)) {
            check(DocumentsContract.isDocumentUri(context, video.uri)) { "Unsupported content provider" }
            val renamedUri =
              DocumentsContract.renameDocument(context.contentResolver, video.uri, newDisplayName)
                ?: return@withContext Result.failure(IllegalStateException("Document rename failed"))
            PlaybackStateOps.onScopedVideoRenamed(video, renamedUri.toString(), renamedUri, newDisplayName)
            MediaLibraryEvents.notifyChanged()
            return@withContext Result.success(Unit)
          }

          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val granted = requestWriteAccess(context, listOf(video.uri))
            if (!granted) {
              Log.w(TAG, "✗ Rename request denied/cancelled: ${video.displayName}")
              return@withContext Result.failure(SecurityException("Rename permission denied"))
            }
          }

          val values =
            ContentValues().apply {
              put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName)
            }

          val updated =
            try {
              context.contentResolver.update(video.uri, values, null, null)
            } catch (error: SecurityException) {
              if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q &&
                error is RecoverableSecurityException &&
                requestMediaApproval { error.userAction.actionIntent.intentSender }
              ) {
                context.contentResolver.update(video.uri, values, null, null)
              } else {
                throw error
              }
            }
          if (updated > 0) {
            val oldPath = video.path
            val parent = oldPath.takeIf { it.startsWith('/') }?.let { File(it).parentFile }
            val newPath = parent?.let { File(it, newDisplayName).absolutePath } ?: video.uri.toString()

            PlaybackStateOps.onScopedVideoRenamed(video, newPath, video.uri, newDisplayName)
            MediaLibraryEvents.notifyChanged()

            Log.d(TAG, "✓ Renamed (scoped): ${video.displayName} -> $newDisplayName")
            Result.success(Unit)
          } else {
            Log.w(TAG, "✗ Rename failed (scoped): ${video.displayName}")
            Result.failure(IllegalStateException("Rename operation failed"))
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "✗ Error renaming (scoped) ${video.displayName}", e)
          Result.failure(e)
        }
      }
  }
}
