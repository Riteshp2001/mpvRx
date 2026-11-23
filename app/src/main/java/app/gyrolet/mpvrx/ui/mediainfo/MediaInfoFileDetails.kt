/* SPDX-License-Identifier: AGPL-3.0-or-later */

package app.gyrolet.mpvrx.ui.mediainfo

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.DateUtils
import android.util.Log
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.player.resolveLocalPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** Device-side history that MediaInfo cannot read through an opened file descriptor. */
private data class MediaFileHistory(
  val addedMillis: Long?,
  val modifiedMillis: Long?,
  val takenMillis: Long?,
  val ownerPackage: String?,
)

/** Labelled creation, download, and modification details for the Overview metadata list. */
internal suspend fun loadMediaFileHistory(
  context: Context,
  uri: Uri,
): List<Pair<String, String>> =
  withContext(Dispatchers.IO) {
    val localPath = uri.resolveLocalPath(context)
    val history = queryMediaStore(context, uri, localPath)
    val createdMillis = localCreatedMillis(localPath)
    val modifiedMillis = history?.modifiedMillis ?: localModifiedMillis(context, uri, localPath)
    val downloadedFrom = if (history != null) queryDownloadSource(context, uri, localPath) else null
    buildList {
      createdMillis?.let { add(context.getString(R.string.media_info_date_created) to formatDate(context, it)) }
      history?.addedMillis?.let { add(context.getString(R.string.media_info_date_added) to formatDate(context, it)) }
      history?.takenMillis?.let { add(context.getString(R.string.media_info_date_taken) to formatDate(context, it)) }
      modifiedMillis?.let { add(context.getString(R.string.media_info_date_modified) to formatDate(context, it)) }
      downloadedFrom?.let { add(context.getString(R.string.media_info_downloaded_from) to it) }
      history?.ownerPackage?.let { add(context.getString(R.string.media_info_saved_by) to appLabel(context, it)) }
    }
  }

private fun queryMediaStore(
  context: Context,
  uri: Uri,
  localPath: String?,
): MediaFileHistory? {
  val projection =
    buildList {
      add(MediaStore.MediaColumns.DATE_ADDED)
      add(MediaStore.MediaColumns.DATE_MODIFIED)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        add(MediaStore.MediaColumns.DATE_TAKEN)
        add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
      }
    }.toTypedArray()
  return try {
    val cursor =
      when {
        uri.authority == MediaStore.AUTHORITY -> context.contentResolver.query(uri, projection, null, null, null)
        localPath != null ->
          context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            "${MediaStore.MediaColumns.DATA}=?",
            arrayOf(localPath),
            null,
          )
        else -> null
      }
    cursor?.use {
      if (!it.moveToFirst()) return@use null
      MediaFileHistory(
        // MediaStore stores added/modified in seconds and the capture time in milliseconds.
        addedMillis = it.positiveLong(MediaStore.MediaColumns.DATE_ADDED)?.times(1_000L),
        modifiedMillis = it.positiveLong(MediaStore.MediaColumns.DATE_MODIFIED)?.times(1_000L),
        takenMillis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          it.positiveLong(MediaStore.MediaColumns.DATE_TAKEN)
        } else {
          null
        },
        ownerPackage =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            it.text(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
          } else {
            null
          },
      )
    }
  } catch (error: SecurityException) {
    Log.w(TAG, "MediaStore details are not accessible", error)
    null
  } catch (error: IllegalArgumentException) {
    Log.w(TAG, "MediaStore rejected the file details query", error)
    null
  }
}

/** Android records the source URL only for files saved into the Downloads collection. */
private fun queryDownloadSource(
  context: Context,
  uri: Uri,
  localPath: String?,
): String? {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
  val mediaId = uri.takeIf { it.authority == MediaStore.AUTHORITY }?.lastPathSegment?.toLongOrNull()
  val (selection, arguments) =
    when {
      mediaId != null && mediaId >= 0 -> "${MediaStore.MediaColumns._ID}=?" to arrayOf(mediaId.toString())
      localPath != null -> "${MediaStore.MediaColumns.DATA}=?" to arrayOf(localPath)
      else -> return null
    }
  return try {
    val volume = if (mediaId != null) MediaStore.getVolumeName(uri) else MediaStore.VOLUME_EXTERNAL
    context.contentResolver
      .query(
        MediaStore.Downloads.getContentUri(volume),
        arrayOf(MediaStore.DownloadColumns.DOWNLOAD_URI, MediaStore.DownloadColumns.REFERER_URI),
        selection,
        arguments,
        null,
      )?.use {
        if (it.moveToFirst()) {
          it.text(MediaStore.DownloadColumns.DOWNLOAD_URI) ?: it.text(MediaStore.DownloadColumns.REFERER_URI)
        } else {
          null
        }
      }
  } catch (error: SecurityException) {
    Log.w(TAG, "Download source is not accessible", error)
    null
  } catch (error: IllegalArgumentException) {
    Log.w(TAG, "MediaStore rejected the download source query", error)
    null
  }
}

private fun localModifiedMillis(
  context: Context,
  uri: Uri,
  localPath: String?,
): Long? {
  localPath?.let(::File)?.lastModified()?.takeIf { it > 0L }?.let { return it }
  if (!DocumentsContract.isDocumentUri(context, uri)) return null
  return try {
    context.contentResolver
      .query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)
      ?.use { if (it.moveToFirst()) it.positiveLong(DocumentsContract.Document.COLUMN_LAST_MODIFIED) else null }
  } catch (error: SecurityException) {
    Log.w(TAG, "Document modification time is not accessible", error)
    null
  } catch (error: IllegalArgumentException) {
    Log.w(TAG, "Provider does not expose a modification time", error)
    null
  }
}

private fun localCreatedMillis(localPath: String?): Long? {
  if (localPath == null) return null
  return try {
    val attributes = Files.readAttributes(File(localPath).toPath(), BasicFileAttributes::class.java)
    val created = attributes.creationTime().toMillis()
    // Filesystems without a birth time may return lastModifiedTime instead; do not relabel it.
    created.takeIf { it > 0L && it != attributes.lastModifiedTime().toMillis() }
  } catch (error: IOException) {
    Log.w(TAG, "File creation time is not accessible", error)
    null
  } catch (error: SecurityException) {
    Log.w(TAG, "Permission to read file creation time was denied", error)
    null
  } catch (error: UnsupportedOperationException) {
    Log.w(TAG, "The filesystem does not expose a creation time", error)
    null
  }
}

private fun appLabel(
  context: Context,
  packageName: String,
): String =
  try {
    val packageManager = context.packageManager
    val info =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
      } else {
        @Suppress("DEPRECATION")
        packageManager.getApplicationInfo(packageName, 0)
      }
    packageManager.getApplicationLabel(info).toString()
  } catch (_: PackageManager.NameNotFoundException) {
    // Android 11+ hides packages this app has no visibility of.
    packageName
  }

private fun formatDate(
  context: Context,
  millis: Long,
): String =
  DateUtils.formatDateTime(
    context,
    millis,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
  )

private fun Cursor.positiveLong(column: String): Long? {
  val index = getColumnIndex(column)
  return if (index >= 0 && !isNull(index)) getLong(index).takeIf { it > 0L } else null
}

private fun Cursor.text(column: String): String? {
  val index = getColumnIndex(column)
  return if (index >= 0 && !isNull(index)) getString(index)?.takeIf(String::isNotBlank) else null
}

private const val TAG = "MediaFileHistory"
