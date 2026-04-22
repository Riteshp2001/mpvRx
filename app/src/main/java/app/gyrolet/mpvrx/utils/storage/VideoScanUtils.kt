package app.gyrolet.mpvrx.utils.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import android.util.Log
import app.gyrolet.mpvrx.database.repository.VideoMetadataCacheRepository
import app.gyrolet.mpvrx.domain.media.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * Video Scanning Utilities
 * Handles video file scanning and metadata extraction
 */
object VideoScanUtils : KoinComponent {
    private const val TAG = "VideoScanUtils"
    private val metadataCache: VideoMetadataCacheRepository by inject()

    // Extensions where MediaStore duration is unreliable (returns 0)
    private val MEDIASTORE_DURATION_UNRELIABLE = setOf("ts", "mts", "m2ts")
    
    /**
     * Get all videos in a specific folder
     * MediaStore first, filesystem fallback for external devices
     */
    suspend fun getVideosInFolder(
        context: Context,
        folderPath: String,
        options: MediaScanOptions = MediaScanOptions(),
        forceFileSystemCheck: Boolean = false,
    ): List<Video> = withContext(Dispatchers.IO) {
        val normalizedFolderPath = normalizeStoragePath(folderPath) ?: return@withContext emptyList()
        val videosMap = mutableMapOf<String, Video>()
        val noMediaPathFilter = NoMediaPathFilter(options)
        val folder = File(normalizedFolderPath)

        if (noMediaPathFilter.shouldExcludeDirectory(folder)) {
            return@withContext emptyList()
        }

        // Try MediaStore first (fast)
        scanVideosFromMediaStore(context, normalizedFolderPath, videosMap, noMediaPathFilter)

        // MediaStore returns 0 duration for .ts/.mts/.m2ts — fix those entries now
        val zeroTsKeys = videosMap.keys.filter { key ->
            val v = videosMap[key] ?: return@filter false
            v.duration <= 0L && v.path.substringAfterLast('.').lowercase() in MEDIASTORE_DURATION_UNRELIABLE
        }
        for (key in zeroTsKeys) {
            val v = videosMap[key] ?: continue
            try {
                val file = File(v.path)
                if (!file.exists()) continue
                val meta = metadataCache.getOrExtractMetadata(file, v.uri, v.displayName)
                if (meta != null && meta.durationMs > 0L) {
                    videosMap[key] = v.copy(
                        duration = meta.durationMs,
                        durationFormatted = formatDuration(meta.durationMs),
                    )
                }
            } catch (_: Exception) { }
        }

        // Manual refreshes force a filesystem verification pass so new/deleted files are reflected
        // even before MediaStore catches up.
        if (
            folder.exists() &&
            folder.canRead() &&
            shouldRunFilesystemVideoCheck(forceFileSystemCheck, videosMap.size)
        ) {
            scanVideosFromFileSystem(context, folder, videosMap, options, noMediaPathFilter)
        }

        videosMap.values.sortedBy { it.displayName.lowercase(Locale.getDefault()) }
    }
    
    /**
     * Scan videos from MediaStore
     */
    private fun scanVideosFromMediaStore(
        context: Context,
        folderPath: String,
        videosMap: MutableMap<String, Video>,
        noMediaPathFilter: NoMediaPathFilter,
    ) {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )
        
        val normalizedFolderPath = normalizeStoragePath(folderPath) ?: return
        val normalizedFolderKey = storagePathKey(normalizedFolderPath) ?: return
        val selection = "LOWER(${MediaStore.Video.Media.DATA}) LIKE ?"
        val selectionArgs = arrayOf("$normalizedFolderKey/%")
        
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Video.Media.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataColumn)
                    val file = File(path)
                    val normalizedPath = normalizeStoragePath(path) ?: continue

                    // Only direct children
                    if (!areEquivalentStoragePaths(file.parent, normalizedFolderPath)) continue
                    if (!file.exists()) continue
                    if (noMediaPathFilter.shouldExcludeDirectory(file.parentFile)) continue

                    val id = cursor.getLong(idColumn)
                    val displayName = cursor.getString(nameColumn)
                    val title = file.nameWithoutExtension
                    val size = cursor.getLong(sizeColumn)
                    val duration = cursor.getLong(durationColumn)
                    val dateModified = cursor.getLong(dateModifiedColumn)
                    val dateAdded = cursor.getLong(dateAddedColumn)
                    val mimeType = cursor.getString(mimeTypeColumn) ?: "video/*"
                    val width = cursor.getInt(widthColumn)
                    val height = cursor.getInt(heightColumn)

                    val uri = Uri.withAppendedPath(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )

                    val videoKey = storagePathKey(normalizedPath) ?: normalizedPath
                    videosMap[videoKey] = Video(
                        id = id,
                        title = title,
                        displayName = displayName,
                        path = normalizedPath,
                        uri = uri,
                        duration = duration,
                        durationFormatted = formatDuration(duration),
                        size = size,
                        sizeFormatted = formatFileSize(size),
                        dateModified = dateModified,
                        dateAdded = dateAdded,
                        mimeType = mimeType,
                        bucketId = normalizedFolderPath,
                        bucketDisplayName = leafStorageName(normalizedFolderPath),
                        width = width,
                        height = height,
                        fps = 0f,
                        resolution = formatResolution(width, height),
                        hasEmbeddedSubtitles = false,
                        subtitleCodec = ""
                    )
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore video scan error", e)
        }
    }
    
    /**
     * Scan videos from filesystem (fallback)
     */
    private suspend fun scanVideosFromFileSystem(
        context: Context,
        folder: File,
        videosMap: MutableMap<String, Video>,
        options: MediaScanOptions,
        noMediaPathFilter: NoMediaPathFilter,
    ) {
        try {
            val files = folder.listFiles() ?: return
            val filesToProcess = mutableListOf<File>()

            for (file in files) {
                if (!file.isFile) continue
                if (FileFilterUtils.shouldSkipFile(file, options, noMediaPathFilter)) continue

                val extension = file.extension.lowercase(Locale.getDefault())
                if (!FileTypeUtils.VIDEO_EXTENSIONS.contains(extension)) continue

                val path = normalizeStoragePath(file.absolutePath) ?: continue
                val videoKey = storagePathKey(path) ?: path
                if (videosMap.containsKey(videoKey)) continue

                filesToProcess.add(file)
            }

            if (filesToProcess.isEmpty()) return

            val metadataMap =
                metadataCache.getOrExtractMetadataBatch(
                    filesToProcess.map { file ->
                        Triple(file, Uri.fromFile(file), file.name)
                    }
                )

            for (file in filesToProcess) {
                try {
                    val path = normalizeStoragePath(file.absolutePath) ?: continue
                    val videoKey = storagePathKey(path) ?: path
                    val uri = Uri.fromFile(file)
                    val displayName = file.name
                    val title = file.nameWithoutExtension
                    val fileSize = file.length()
                    val dateModified = file.lastModified() / 1000
                    val cachedMetadata = metadataMap[path]
                    val resolvedSize = cachedMetadata?.sizeBytes?.takeIf { it > 0 } ?: fileSize
                    val mimeType = FileTypeUtils.getMimeTypeFromExtension(file.extension.lowercase())

                    videosMap[videoKey] = Video(
                        id = path.hashCode().toLong(),
                        title = title,
                        displayName = displayName,
                        path = path,
                        uri = uri,
                        duration = cachedMetadata?.durationMs ?: 0L,
                        durationFormatted = formatDuration(cachedMetadata?.durationMs ?: 0L),
                        size = resolvedSize,
                        sizeFormatted = formatFileSize(resolvedSize),
                        dateModified = dateModified,
                        dateAdded = dateModified,
                        mimeType = mimeType,
                        bucketId = normalizeStoragePath(folder.absolutePath) ?: folder.absolutePath,
                        bucketDisplayName = leafStorageName(folder.absolutePath),
                        width = cachedMetadata?.width ?: 0,
                        height = cachedMetadata?.height ?: 0,
                        fps = cachedMetadata?.fps ?: 0f,
                        resolution = formatResolution(cachedMetadata?.width ?: 0, cachedMetadata?.height ?: 0),
                        hasEmbeddedSubtitles = cachedMetadata?.hasEmbeddedSubtitles ?: false,
                        subtitleCodec = cachedMetadata?.subtitleCodec ?: ""
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Error processing file: ${file.absolutePath}", e)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Filesystem video scan error", e)
        }
    }
    
    // Formatting utilities
    
    private fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0) return "0s"
        
        val seconds = durationMs / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return when {
            hours > 0 -> String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, secs)
            minutes > 0 -> String.format(Locale.getDefault(), "%d:%02d", minutes, secs)
            else -> "${secs}s"
        }
    }
    
    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
        return String.format(
            Locale.getDefault(),
            "%.1f %s",
            bytes / 1024.0.pow(digitGroups.toDouble()),
            units[digitGroups]
        )
    }
    
    private fun formatResolution(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "--"
        
        return when {
            width >= 7680 || height >= 4320 -> "4320p"
            width >= 3840 || height >= 2160 -> "2160p"
            width >= 2560 || height >= 1440 -> "1440p"
            width >= 1920 || height >= 1080 -> "1080p"
            width >= 1280 || height >= 720 -> "720p"
            width >= 854 || height >= 480 -> "480p"
            width >= 640 || height >= 360 -> "360p"
            width >= 426 || height >= 240 -> "240p"
            else -> "${height}p"
        }
    }
}

/**
 * File Type Utilities
 * Handles file type detection
 */
object FileTypeUtils {

  // Video file extensions
    val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "3g2",
        "mpg", "mpeg", "m2v", "ogv", "ts", "mts", "m2ts", "vob", "divx", "xvid",
        "f4v", "rm", "rmvb", "asf"
    )

  /**
     * Checks if a file is a video based on extension
     */
    fun isVideoFile(file: File): Boolean {
        val extension = file.extension.lowercase(Locale.getDefault())
        return VIDEO_EXTENSIONS.contains(extension)
    }

  /**
     * Gets MIME type from file extension
     */
    fun getMimeTypeFromExtension(extension: String): String =
        when (extension.lowercase()) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "m4v" -> "video/x-m4v"
            "3gp" -> "video/3gpp"
            "mpg", "mpeg" -> "video/mpeg"
            else -> "video/*"
        }
}

/**
 * File Filter Utilities
 * Handles file and folder filtering logic
 */
object FileFilterUtils {
    private const val TAG = "FileFilterUtils"

    // Folders to skip during scanning (system/cache folders)
    private val SKIP_FOLDERS = setOf(
        // System & OS Junk
        "android", "data", "obb", "system", "lost.dir", ".android_secure", "android_secure",

        // Hidden & Temp Files
        ".thumbnails", "thumbnails", "thumbs", ".thumbs",
        ".cache", "cache", "temp", "tmp", ".temp", ".tmp",

        // Trash & Recycle Bins
        ".trash", "trash", ".trashbin", ".trashed", "recycle", "recycler",

        // App Clutters
        "log", "logs", "backup", "backups",
        "stickers", "whatsapp stickers", "telegram stickers"
    )

    /**
     * Checks if a folder contains a .nomedia file
     */
    fun hasNoMediaFile(folder: File): Boolean {
        if (!folder.isDirectory || !folder.canRead()) {
            return false
        }

        return try {
            val noMediaFile = File(folder, ".nomedia")
            noMediaFile.exists()
        } catch (e: Exception) {
            Log.w(TAG, "Error checking for .nomedia file in: ${folder.absolutePath}", e)
            false
        }
    }

    /**
     * Checks if a folder should be skipped during scanning
     */
    fun shouldSkipFolder(
        folder: File,
        options: MediaScanOptions = MediaScanOptions(),
        noMediaPathFilter: NoMediaPathFilter = NoMediaPathFilter(options)
    ): Boolean {
        if (isAndroidDataAccessiblePath(folder)) {
            // Allow navigation/scanning into Android/data so app-specific video folders
            // can appear in both the folder list and filesystem browser.
            return folder.name.startsWith(".")
        }

        if (noMediaPathFilter.shouldExcludeDirectory(folder)) {
            return true
        }

        val name = folder.name.lowercase()
        val isHidden = name.startsWith(".")
        return isHidden || SKIP_FOLDERS.contains(name)
    }

    /**
     * Checks if a file should be skipped during file listing
     */
    fun shouldSkipFile(
        file: File,
        options: MediaScanOptions = MediaScanOptions(),
        noMediaPathFilter: NoMediaPathFilter = NoMediaPathFilter(options)
    ): Boolean {
        if (file.name.startsWith(".")) {
            return true
        }

        return noMediaPathFilter.shouldExcludeFile(file)
    }
}

/**
 * Storage Volume Utilities
 * Handles storage volume detection and management
 */
object StorageVolumeUtils {
    private const val TAG = "StorageVolumeUtils"

    /**
     * Gets all mounted storage volumes
     */
    fun getAllStorageVolumes(context: Context): List<StorageVolume> =
        try {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            storageManager.storageVolumes.filter { volume ->
                volume.state == Environment.MEDIA_MOUNTED ||
                    (getVolumePath(volume)?.let { path -> File(path).exists() } == true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting storage volumes", e)
            emptyList()
        }

    /**
     * Gets non-primary (external) storage volumes (SD cards, USB OTG)
     */
    fun getExternalStorageVolumes(context: Context): List<StorageVolume> =
        getAllStorageVolumes(context).filter { !it.isPrimary }

  /**
     * Gets the physical path of a storage volume
     */
    fun getVolumePath(volume: StorageVolume): String? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val directory = volume.directory
                if (directory != null) {
                    return directory.absolutePath
                }
            }

            val method = volume.javaClass.getMethod("getPath")
            val path = method.invoke(volume) as? String
            if (path != null) {
                return path
            }

            volume.uuid?.let { uuid ->
                val possiblePaths = listOf(
                    "/storage/$uuid",
                    "/mnt/media_rw/$uuid",
                )
                for (possiblePath in possiblePaths) {
                    if (File(possiblePath).exists()) {
                        return possiblePath
                    }
                }
            }

            return null
        } catch (e: Exception) {
            Log.w(TAG, "Could not get volume path", e)
            return null
        }
    }
}

