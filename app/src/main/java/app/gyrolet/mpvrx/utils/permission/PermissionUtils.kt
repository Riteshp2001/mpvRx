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
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
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
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import app.gyrolet.mpvrx.utils.media.PlaybackStateOps
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Simplified storage permission utilities with MANAGE_EXTERNAL_STORAGE support.
 */
object PermissionUtils {
  private const val FILE_ACCESS_TAG = "FileAccessRequest"

  private var mediaRequestLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
  private var resultOkCallback: () -> Unit = {}
  private var resultCancelledCallback: () -> Unit = {}

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
    if (resultCode == Activity.RESULT_OK) {
      resultOkCallback()
    } else {
      resultCancelledCallback()
    }
  }

  suspend fun requestScopedWriteAccess(
    context: Context,
    uris: List<Uri>,
  ): Boolean = requestWriteAccess(context, uris)

  suspend fun requestScopedDeleteAccess(
    context: Context,
    uris: List<Uri>,
  ): Boolean = requestDeleteAccess(context, uris)

  private suspend fun requestWriteAccess(
    context: Context,
    uris: List<Uri>,
  ): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return true
    return withContext(Dispatchers.Main) {
      suspendCancellableCoroutine { continuation ->
        val launcher =
          mediaRequestLauncher ?: run {
            continuation.resumeWith(Result.success(false))
            return@suspendCancellableCoroutine
          }

        resultOkCallback = { continuation.resumeWith(Result.success(true)) }
        resultCancelledCallback = { continuation.resumeWith(Result.success(false)) }

        val pendingIntent = MediaStore.createWriteRequest(context.contentResolver, uris)
        launcher.launch(IntentSenderRequest.Builder(pendingIntent).build())
      }
    }
  }

  private suspend fun requestDeleteAccess(
    context: Context,
    uris: List<Uri>,
  ): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return true
    return withContext(Dispatchers.Main) {
      suspendCancellableCoroutine { continuation ->
        val launcher =
          mediaRequestLauncher ?: run {
            continuation.resumeWith(Result.success(false))
            return@suspendCancellableCoroutine
          }

        resultOkCallback = { continuation.resumeWith(Result.success(true)) }
        resultCancelledCallback = { continuation.resumeWith(Result.success(false)) }

        val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
        launcher.launch(IntentSenderRequest.Builder(pendingIntent).build())
      }
    }
  }

  /**
   * Returns READ_EXTERNAL_STORAGE permission for all Android versions.
   * On Android 11+, MANAGE_EXTERNAL_STORAGE provides full file access.
   */
  fun getStoragePermission(audioOnly: Boolean = false): String =
    when {
      Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> {
        // Android 9 and below need WRITE permission to create folders/files (e.g., mpvsnaps)
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
  fun rememberStoragePermissionState(audioOnly: Boolean = false): PermissionState =
    rememberPermissionState(getStoragePermission(audioOnly))

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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
          android.os.Environment.isExternalStorageManager()
        ) {
          object : PermissionState {
            override val permission = permissionState.permission
            override val status = PermissionStatus.Granted

            override fun launchPermissionRequest() = permissionState.launchPermissionRequest()
          }
        } else {
          permissionState
        }
      }

    LaunchedEffect(effectivePermissionState.status) {
      if (effectivePermissionState.status == PermissionStatus.Granted) {
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
     * Check if MANAGE_EXTERNAL_STORAGE permission is available and granted
     */
    private fun hasManageStoragePermission(): Boolean =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
      } else {
        true // Pre-Android 11 devices don't need this permission
      }

    /**
     * Delete videos using direct file operations (requires MANAGE_EXTERNAL_STORAGE on Android 11+)
     */
    suspend fun deleteVideos(
      context: Context,
      videos: List<Video>,
    ): Pair<Int, Int> =
      withContext(Dispatchers.IO) {
        // A content URI is never a filesystem path, even when broad storage access is available.
        // Always route it through ContentResolver/MediaStore and use direct deletion only for
        // actual file-backed sources.
        val (contentVideos, fileVideos) =
          videos.partition { it.uri.scheme.equals("content", ignoreCase = true) }
        val contentResult =
          if (contentVideos.isEmpty()) {
            0 to 0
          } else {
            deleteVideosScoped(context, contentVideos)
          }
        val fileResult =
          if (fileVideos.isEmpty()) {
            0 to 0
          } else if (!BuildConfig.SCOPED_STORAGE_ONLY || hasManageStoragePermission()) {
            deleteVideosDirectly(fileVideos)
          } else {
            deleteVideosScoped(context, fileVideos)
          }

        (contentResult.first + fileResult.first) to (contentResult.second + fileResult.second)
      }

    /**
     * Delete videos using direct file operations (requires MANAGE_EXTERNAL_STORAGE on Android 11+)
     */
    private suspend fun deleteVideosDirectly(videos: List<Video>): Pair<Int, Int> =
      withContext(Dispatchers.IO) {
        var deleted = 0
        var failed = 0

        for (video in videos) {
          try {
            val file = File(video.path)
            if (file.exists() && file.delete()) {
              deleted++
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
          MediaLibraryEvents.notifyChanged()
        }

        Pair(deleted, failed)
      }

    /**
     * Delete videos via scoped storage (Play Store flavor / no MANAGE_EXTERNAL_STORAGE)
     */
    private suspend fun deleteVideosScoped(
      context: Context,
      videos: List<Video>,
    ): Pair<Int, Int> =
      withContext(Dispatchers.IO) {
        var deleted = 0
        var failed = 0

        val contentVideos = videos.filter { it.uri.scheme.equals("content", ignoreCase = true) }
        val fileVideos = videos.filterNot { it.uri.scheme.equals("content", ignoreCase = true) }

        if (contentVideos.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          val granted = requestDeleteAccess(context, contentVideos.map { it.uri })
          if (granted) {
            contentVideos.forEach { video ->
              deleted++
              RecentlyPlayedOps.onVideoDeleted(video.path)
              PlaybackStateOps.onVideoDeleted(video.path)
              Log.d(TAG, "✓ Deleted (scoped request): ${video.displayName}")
            }
          } else {
            failed += contentVideos.size
            Log.w(TAG, "✗ Delete request denied/cancelled for ${contentVideos.size} item(s)")
          }
        } else {
          for (video in contentVideos) {
            try {
              val rows = context.contentResolver.delete(video.uri, null, null)
              if (rows > 0) {
                deleted++
                RecentlyPlayedOps.onVideoDeleted(video.path)
                PlaybackStateOps.onVideoDeleted(video.path)
                Log.d(TAG, "✓ Deleted (scoped): ${video.displayName}")
              } else {
                failed++
                Log.w(TAG, "✗ Failed to delete (scoped): ${video.displayName}")
              }
            } catch (e: Exception) {
              failed++
              Log.e(TAG, "✗ Error deleting (scoped) ${video.displayName}", e)
            }
          }
        }

        for (video in fileVideos) {
          try {
            val file = File(video.path)
            if (!file.exists() || file.delete()) {
              deleted++
              RecentlyPlayedOps.onVideoDeleted(video.path)
              PlaybackStateOps.onVideoDeleted(video.path)
              Log.d(TAG, "✓ Deleted (file fallback): ${video.displayName}")
            } else {
              failed++
              Log.w(TAG, "✗ Failed to delete (file fallback): ${video.displayName}")
            }
          } catch (e: Exception) {
            failed++
            Log.e(TAG, "✗ Error deleting (file fallback) ${video.displayName}", e)
          }
        }

        if (deleted > 0) {
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
        if (!BuildConfig.SCOPED_STORAGE_ONLY || hasManageStoragePermission()) {
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
                arrayOf(newFile.absolutePath),
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

          val updated = context.contentResolver.update(video.uri, values, null, null)
          if (updated > 0) {
            val oldPath = video.path
            val parent = File(oldPath).parentFile
            val newPath = parent?.let { File(it, newDisplayName).absolutePath } ?: oldPath

            RecentlyPlayedOps.onVideoRenamed(oldPath, newPath)
            PlaybackStateOps.onVideoRenamed(oldPath, newPath)
            MediaLibraryEvents.notifyChanged()

            Log.d(TAG, "✓ Renamed (scoped): ${video.displayName} -> $newDisplayName")
            Result.success(Unit)
          } else {
            Log.w(TAG, "✗ Rename failed (scoped): ${video.displayName}")
            Result.failure(IllegalStateException("Rename operation failed"))
          }
        } catch (e: Exception) {
          Log.e(TAG, "✗ Error renaming (scoped) ${video.displayName}", e)
          Result.failure(e)
        }
      }
  }
}
