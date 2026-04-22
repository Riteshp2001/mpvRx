package app.gyrolet.mpvrx.utils.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Folder View Scanner - Optimized for folder list view
 * 
 * Only shows folders with immediate video children (not recursive)
 * Fast scanning using MediaStore + filesystem fallback
 */
object FolderViewScanner {
    private const val TAG = "FolderViewScanner"
    
    // Smart cache with short TTL (10 seconds)
    private var cachedFolderList: List<VideoFolder>? = null
    private var cacheTimestamp: Long = 0
    private var cacheOptionsKey: String? = null
    private const val CACHE_TTL_MS = 10_000L // 10 seconds for faster refresh
    
    /**
     * Clear cache (call when media library changes)
     */
    fun clearCache() {
        cachedFolderList = null
        cacheTimestamp = 0
        cacheOptionsKey = null
    }
    
    /**
     * Folder metadata
     */
    data class FolderData(
        val path: String,
        val name: String,
        val videoCount: Int,
        val totalSize: Long,
        val totalDuration: Long,
        val lastModified: Long,
        val hasSubfolders: Boolean = false
    )
    
    /**
     * Helper data class for video info during scanning
     */
    private data class VideoInfo(
        val size: Long,
        val duration: Long,
        val dateModified: Long
    )

    private data class FolderAggregate(
        var path: String,
        val videos: MutableList<VideoInfo> = mutableListOf()
    )
    
    /**
     * Get all video folders for folder list view
     * Only shows folders with immediate video children (not recursive)
     */
    suspend fun getAllVideoFolders(
        context: Context,
        options: MediaScanOptions = MediaScanOptions(),
        forceFileSystemCheck: Boolean = false,
    ): List<VideoFolder> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        
        // Return cached data if still valid
        cachedFolderList?.let { cached ->
            if (!forceFileSystemCheck && now - cacheTimestamp < CACHE_TTL_MS && cacheOptionsKey == options.cacheKey) {
                return@withContext cached
            }
        }
        
        // Build fresh data
        val allFolders = mutableMapOf<String, FolderData>()
        val noMediaPathFilter = NoMediaPathFilter(options)
        
        // Step 1: Scan MediaStore (fast, covers most cases)
        scanMediaStoreImmediateChildren(context, allFolders, noMediaPathFilter)
        
        // Step 2: Scan filesystem for folders that MediaStore won't expose.
        scanFileSystemRoots(context, allFolders, options, noMediaPathFilter, forceFileSystemCheck)
        
        // Convert to VideoFolder list
        val result = allFolders.values.map { data ->
            VideoFolder(
                bucketId = data.path,
                name = data.name,
                path = data.path,
                videoCount = data.videoCount,
                totalSize = data.totalSize,
                totalDuration = data.totalDuration,
                lastModified = data.lastModified
            )
        }.sortedBy { it.name.lowercase(Locale.getDefault()) }
        
        // Update cache
        cachedFolderList = result
        cacheTimestamp = now
        cacheOptionsKey = options.cacheKey
        
        result
    }
    
    /**
     * Scan MediaStore for all videos and build folder map (immediate children only)
     */
    private fun scanMediaStoreImmediateChildren(
        context: Context,
        folders: MutableMap<String, FolderData>,
        noMediaPathFilter: NoMediaPathFilter
    ) {
        val projection = arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED
        )
        
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                
                // Collect videos by folder
                val videosByFolder = mutableMapOf<String, FolderAggregate>()
                
                while (cursor.moveToNext()) {
                    val videoPath = cursor.getString(dataColumn)
                    val file = File(videoPath)
                    
                    if (!file.exists()) continue
                    if (noMediaPathFilter.shouldExcludeDirectory(file.parentFile)) continue
                    
                    val folderPath = normalizeStoragePath(file.parent) ?: continue
                    val folderKey = storagePathKey(folderPath) ?: continue
                    val size = cursor.getLong(sizeColumn)
                    val duration = cursor.getLong(durationColumn)
                    val dateModified = cursor.getLong(dateColumn)
                    
                    val aggregate =
                        videosByFolder.getOrPut(folderKey) {
                            FolderAggregate(path = folderPath)
                        }
                    aggregate.path = choosePreferredStoragePath(aggregate.path, folderPath)
                    aggregate.videos.add(
                        VideoInfo(size, duration, dateModified)
                    )
                }
                
                // Build folder data - only count immediate children videos
                for ((folderKey, aggregate) in videosByFolder) {
                    val folderPath = aggregate.path
                    val videos = aggregate.videos
                    var totalSize = 0L
                    var totalDuration = 0L
                    var lastModified = 0L
                    
                    for (video in videos) {
                        totalSize += video.size
                        totalDuration += video.duration
                        if (video.dateModified > lastModified) {
                            lastModified = video.dateModified
                        }
                    }
                    
                    // Check if this folder has subfolders with videos
                    val hasSubfolders = videosByFolder.values.any { otherFolder ->
                        otherFolder.path != folderPath &&
                        isDirectStorageChild(folderPath, otherFolder.path)
                    }
                    
                    folders[folderKey] = FolderData(
                        path = folderPath,
                        name = leafStorageName(folderPath),
                        videoCount = videos.size,
                        totalSize = totalSize,
                        totalDuration = totalDuration,
                        lastModified = lastModified,
                        hasSubfolders = hasSubfolders
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore scan error", e)
        }
    }
    
    /**
     * Scan external volumes (USB OTG, SD cards) via filesystem
     */
    private fun scanFileSystemRoots(
        context: Context,
        folders: MutableMap<String, FolderData>,
        options: MediaScanOptions,
        noMediaPathFilter: NoMediaPathFilter,
        forceFileSystemCheck: Boolean,
    ) {
        try {
            val rootsToScan = linkedSetOf<File>()
            val primaryStorageRoot = Environment.getExternalStorageDirectory()

            if (shouldIncludePrimaryStorageInFilesystemFolderScan(options, forceFileSystemCheck)) {
                rootsToScan += primaryStorageRoot
            }

            rootsToScan += getPrimaryStorageSupplementalScanRoots(primaryStorageRoot)

            for (volume in StorageVolumeUtils.getExternalStorageVolumes(context)) {
                val volumePath = StorageVolumeUtils.getVolumePath(volume)
                if (volumePath == null) {
                    continue
                }

                rootsToScan += File(volumePath)
            }

            for (root in rootsToScan) {
                if (!root.exists() || !root.canRead() || !root.isDirectory) {
                    continue
                }

                scanDirectoryRecursive(root, folders, maxDepth = 20, options = options, noMediaPathFilter = noMediaPathFilter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Filesystem folder scan error", e)
        }
    }
    
    /**
     * Recursively scan directory for videos
     */
    private fun scanDirectoryRecursive(
        directory: File,
        folders: MutableMap<String, FolderData>,
        maxDepth: Int,
        currentDepth: Int = 0,
        options: MediaScanOptions,
        noMediaPathFilter: NoMediaPathFilter
    ) {
        if (currentDepth >= maxDepth) return
        if (!directory.exists() || !directory.canRead() || !directory.isDirectory) return
        if (FileFilterUtils.shouldSkipFolder(directory, options, noMediaPathFilter)) return
        
        try {
            val files = directory.listFiles() ?: return
            
            val videoFiles = mutableListOf<File>()
            val subdirectories = mutableListOf<File>()
            
            for (file in files) {
                try {
                    when {
                        file.isDirectory -> {
                            if (!FileFilterUtils.shouldSkipFolder(file, options, noMediaPathFilter)) {
                                subdirectories.add(file)
                            }
                        }
                        file.isFile -> {
                            if (FileFilterUtils.shouldSkipFile(file, options, noMediaPathFilter)) {
                                continue
                            }
                            val extension = file.extension.lowercase(Locale.getDefault())
                            if (FileTypeUtils.VIDEO_EXTENSIONS.contains(extension)) {
                                videoFiles.add(file)
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    continue
                }
            }
            
            // Add folder if it has videos
            if (videoFiles.isNotEmpty()) {
                val folderPath = normalizeStoragePath(directory.absolutePath) ?: return
                val folderKey = storagePathKey(folderPath) ?: return
                
                // Skip if already from MediaStore
                if (!folders.containsKey(folderKey)) {
                    var totalSize = 0L
                    var lastModified = 0L
                    
                    for (video in videoFiles) {
                        totalSize += video.length()
                        val modified = video.lastModified()
                        if (modified > lastModified) {
                            lastModified = modified
                        }
                    }
                    
                    folders[folderKey] = FolderData(
                        path = folderPath,
                        name = leafStorageName(folderPath),
                        videoCount = videoFiles.size,
                        totalSize = totalSize,
                        totalDuration = 0L, // Duration not available from filesystem
                        lastModified = lastModified / 1000,
                        hasSubfolders = subdirectories.isNotEmpty()
                    )
                } else {
                    folders[folderKey]?.let { existing ->
                        val preferredPath = choosePreferredStoragePath(existing.path, folderPath)
                        folders[folderKey] =
                            existing.copy(
                                path = preferredPath,
                                name = leafStorageName(preferredPath),
                                hasSubfolders = existing.hasSubfolders || subdirectories.isNotEmpty()
                            )
                    }
                }
            }
            
            // Recurse into subdirectories
            for (subdir in subdirectories) {
                scanDirectoryRecursive(subdir, folders, maxDepth, currentDepth + 1, options, noMediaPathFilter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error scanning: ${directory.absolutePath}", e)
        }
    }
}

