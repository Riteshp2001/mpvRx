package app.gyrolet.mpvrx.exoplayer.core.common.extensions

import android.app.UiModeManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.TypedValue
import androidx.core.graphics.drawable.toBitmapOrNull
import androidx.core.text.isDigitsOnly
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import app.gyrolet.mpvrx.exoplayer.core.common.Logger
import org.mozilla.universalchardet.UniversalDetector

val VIDEO_COLLECTION_URI: Uri
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

fun Context.getPath(uri: Uri): String? {
    if (DocumentsContract.isDocumentUri(this, uri)) {
        when {
            uri.isExternalStorageDocument -> {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId
                    .split(":".toRegex())
                    .dropLastWhile { it.isEmpty() }
                    .toTypedArray()
                if ("primary".equals(split[0], ignoreCase = true)) {
                    return Environment.getExternalStorageDirectory().path + "/" + split[1]
                }
            }

            uri.isDownloadsDocument -> {
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.isDigitsOnly()) {
                    return try {
                        val contentUri = ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"),
                            docId.toLong(),
                        )
                        getDataColumn(contentUri, null, null)
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            uri.isMediaDocument -> {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()
                val contentUri = when (split[0]) {
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> return null
                }
                return getDataColumn(contentUri, "_id=?", arrayOf(split[1]))
            }
        }
        return null
    }

    if (ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) {
        if (uri.isLocalPhotoPickerUri) return null
        if (uri.isCloudPhotoPickerUri) return null
        return if (uri.isGooglePhotosUri) uri.lastPathSegment else getDataColumn(uri, null, null)
    }

    if (ContentResolver.SCHEME_FILE.equals(uri.scheme, ignoreCase = true)) {
        return uri.path
    }

    return null
}

private fun Context.getDataColumn(
    uri: Uri,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
): String? {
    val column = MediaStore.Images.Media.DATA
    val projection = arrayOf(column)
    try {
        contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(index)
            }
        }
    } catch (e: Exception) {
        return null
    }
    return null
}

fun Context.getFilenameFromUri(uri: Uri): String = if (ContentResolver.SCHEME_FILE.equals(uri.scheme, ignoreCase = true)) {
    File(uri.toString()).name
} else {
    getFilenameFromContentUri(uri) ?: uri.lastPathSegment ?: ""
}

fun Context.getFilenameFromContentUri(uri: Uri): String? {
    val projection = arrayOf(
        OpenableColumns.DISPLAY_NAME,
    )

    try {
        val cursor = contentResolver.query(uri, projection, null, null, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
        }
    } catch (e: Exception) {
        return null
    }
    return null
}

fun Context.getMediaContentUri(uri: Uri): Uri? {
    val rawPath = getPath(uri) ?: return null
    val path = rawPath.canonicalPathOrSelf()

    val column = MediaStore.Video.Media._ID
    val projection = arrayOf(column)
    try {
        contentResolver.query(
            VIDEO_COLLECTION_URI,
            projection,
            "${MediaStore.Images.Media.DATA} = ?",
            arrayOf(path),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndexOrThrow(column)
                val id = cursor.getLong(index)
                return ContentUris.withAppendedId(VIDEO_COLLECTION_URI, id)
            }
        }
    } catch (e: Exception) {
        return null
    }
    return null
}

fun Context.getMediaFileContentUri(path: String): Uri? {
    val filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val projection = arrayOf(MediaStore.Files.FileColumns._ID)

    return try {
        contentResolver.query(
            filesUri,
            projection,
            "${MediaStore.Files.FileColumns.DATA} = ?",
            arrayOf(path),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                ContentUris.withAppendedId(filesUri, cursor.getLong(index))
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }
}

suspend fun Context.scanPaths(paths: List<String>): Boolean {
    if (paths.isEmpty()) return true

    return withTimeoutOrNull(15_000L) {
        suspendCancellableCoroutine { continuation ->
            try {
                var remaining = paths.size
                var hasSuccess = false
                MediaScannerConnection.scanFile(
                    this@scanPaths,
                    paths.toTypedArray(),
                    null,
                ) { path, uri ->
                    Logger.debug("ScanPath", "scanPaths: path=$path, uri=$uri")
                    synchronized(paths) {
                        if (uri != null) {
                            hasSuccess = true
                        }
                        remaining--
                        if (remaining == 0) {
                            continuation.resumeWith(Result.success(hasSuccess))
                        }
                    }
                }
            } catch (e: Exception) {
                continuation.resumeWith(Result.failure(e))
            }
        }
    } ?: false
}

suspend fun Context.scanFileForContentUri(
    path: String,
    timeoutMs: Long = 3000L,
): Uri? = withContext(Dispatchers.IO) {
    try {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                MediaScannerConnection.scanFile(
                    this@scanFileForContentUri,
                    arrayOf(path),
                    null,
                ) { _, uri ->
                    continuation.resumeWith(Result.success(uri))
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}

suspend fun Context.scanStorage(
    storagePath: String? = Environment.getExternalStorageDirectory()?.path,
): Boolean = withContext(Dispatchers.IO) {
    if (storagePath == null) return@withContext false

    scanPaths(listOf(storagePath))
}

suspend fun Context.convertToUTF8(uri: Uri, charset: Charset? = null): Uri = withContext(Dispatchers.IO) {
    try {
        when {
            uri.scheme?.let { it in listOf("http", "https", "ftp") } == true -> {
                val url = URL(uri.toString())
                val detectedCharset = charset ?: detectCharset(url)
                if (detectedCharset == StandardCharsets.UTF_8) {
                    uri
                } else {
                    convertNetworkUriToUTF8(url = url, sourceCharset = detectedCharset)
                }
            }

            else -> {
                val detectedCharset = charset ?: detectCharset(uri = uri, context = this@convertToUTF8)
                if (detectedCharset == StandardCharsets.UTF_8) {
                    uri
                } else {
                    convertLocalUriToUTF8(uri = uri, sourceCharset = detectedCharset)
                }
            }
        }
    } catch (exception: Exception) {
        exception.printStackTrace()
        uri
    }
}

private fun detectCharset(uri: Uri, context: Context): Charset = context.contentResolver.openInputStream(uri)?.use { inputStream ->
    detectCharsetFromStream(inputStream)
} ?: StandardCharsets.UTF_8

private fun detectCharset(url: URL): Charset = url.openStream().use { inputStream ->
    detectCharsetFromStream(inputStream)
}

private fun detectCharsetFromStream(inputStream: InputStream): Charset {
    return BufferedInputStream(inputStream).use { bufferedStream ->
        val maxBytes = 1024 * 100 // 100 KB
        val data = ByteArray(maxBytes)
        val bytesRead = bufferedStream.read(data, 0, maxBytes)

        if (bytesRead <= 0) {
            return@use Charset.forName(StandardCharsets.UTF_8.name())
        }

        UniversalDetector(null).run {
            handleData(data, 0, data.size)
            dataEnd()
            Charset.forName(detectedCharset ?: StandardCharsets.UTF_8.name())
        }
    }
}

private fun Context.convertLocalUriToUTF8(uri: Uri, sourceCharset: Charset): Uri {
    val fileName = getFilenameFromUri(uri)
    val file = File(subtitleCacheDir, fileName)

    contentResolver.openInputStream(uri)?.use { inputStream ->
        inputStream.reader(sourceCharset).buffered().use { reader ->
            file.outputStream().writer(StandardCharsets.UTF_8).buffered().use { writer ->
                reader.copyTo(writer)
            }
        }
    }

    return Uri.fromFile(file)
}

private fun Context.convertNetworkUriToUTF8(url: URL, sourceCharset: Charset): Uri {
    val fileName = url.path.substringAfterLast('/')
    val file = File(subtitleCacheDir, fileName)

    url.openStream().use { inputStream ->
        inputStream.reader(sourceCharset).buffered().use { reader ->
            file.outputStream().writer(StandardCharsets.UTF_8).buffered().use { writer ->
                reader.copyTo(writer)
            }
        }
    }

    return Uri.fromFile(file)
}

fun Context.isDeviceTvBox(): Boolean {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    if (uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    if (packageManager.hasSystemFeature("amazon.hardware.fire_tv")) return true
    if (!hasStorageAccessFrameworkChooser()) return true

    return false
}

fun Context.hasStorageAccessFrameworkChooser(): Boolean {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
    intent.addCategory(Intent.CATEGORY_OPENABLE)
    intent.type = "video/*"
    return intent.resolveActivity(packageManager) != null
}

fun Context.pxToDp(px: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, px, resources.displayMetrics)

fun Context.dpToPx(dp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

val Context.subtitleCacheDir: File
    get() {
        val dir = File(cacheDir, "subtitles")
        if (!dir.exists()) dir.mkdir()
        return dir
    }

val Context.thumbnailCacheDir: File
    get() {
        val dir = File(cacheDir, "thumbnails")
        if (!dir.exists()) dir.mkdir()
        return dir
    }

val Context.externalSubtitleFontDir: File
    get() {
        val dir = File(filesDir, "subtitle-fonts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

val Context.externalSubtitleFontFile: File
    get() = File(externalSubtitleFontDir, "current.font")

val Context.externalSubtitleFontTempFile: File
    get() = File(externalSubtitleFontDir, "importing.font")

val Context.externalSubtitleFontMetaFile: File
    get() = File(externalSubtitleFontDir, "current.json")

val Context.externalSubtitleFontTempMetaFile: File
    get() = File(externalSubtitleFontDir, "importing.json")

suspend fun ContentResolver.updateMedia(
    uri: Uri,
    contentValues: ContentValues,
): Boolean = withContext(Dispatchers.IO) {
    return@withContext try {
        update(
            uri,
            contentValues,
            null,
            null,
        ) > 0
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

suspend fun ContentResolver.deleteMedia(
    uri: Uri,
): Boolean = withContext(Dispatchers.IO) {
    return@withContext try {
        delete(uri, null, null) > 0
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun Context.getStorageVolumes() = try {
    getExternalFilesDirs(null)?.mapNotNull {
        File(it.path.substringBefore("/Android")).takeIf { file -> file.exists() }
    } ?: listOf(Environment.getExternalStorageDirectory())
} catch (e: Exception) {
    listOf(Environment.getExternalStorageDirectory())
}

fun Context.appIcon(): Bitmap? = packageManager.getApplicationInfo(packageName, 0).loadIcon(packageManager)?.toBitmapOrNull()

val Context.isPipFeatureSupported: Boolean
    get() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
