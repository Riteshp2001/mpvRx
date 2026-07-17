package app.gyrolet.mpvrx.utils.storage

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.database.dao.DirectoryScanDao
import app.gyrolet.mpvrx.database.entities.DirectoryScanEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
    
    // MediaStore is already invalidated by library events; keep it warm between screen visits.
    private var cachedFolderList: List<VideoFolder>? = null
    private var cacheTimestamp: Long = 0
    private var cacheOptionsKey: String? = null
    private const val CACHE_TTL_MS = 5 * 60_000L
    
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
        if (options.includeAudio) {
            scanAudioMediaStoreImmediateChildren(context, allFolders, noMediaPathFilter, options)
        }
        
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

    suspend fun getIndexedNoMediaFolders(
        options: MediaScanOptions,
        dao: DirectoryScanDao,
    ): List<VideoFolder> = withContext(Dispatchers.IO) {
        if (!options.includeNoMediaFolders) return@withContext emptyList()
        dao.getEntries(options.cacheKey).mapNotNull(::toVideoFolder)
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    /**
     * Scans only hidden MediaStore gaps. Cached folders are available separately and this flow
     * emits small batches as filesystem results become available.
     */
    fun scanNoMediaFoldersIncrementally(
        context: Context,
        options: MediaScanOptions,
        dao: DirectoryScanDao,
        forceDiscovery: Boolean = false,
    ): Flow<List<VideoFolder>> = flow {
        if (!options.includeNoMediaFolders) return@flow

        val scanKey = options.cacheKey
        val cached = dao.getEntries(scanKey).associateBy { storagePathKey(it.path) }
        val knownRoots = dao.getNoMediaRoots(scanKey).map(::File)
        val roots = linkedMapOf<String, File>()
        knownRoots.forEach { root -> storagePathKey(root.absolutePath)?.let { roots[it] = root } }

        if (forceDiscovery || knownRoots.none { root -> File(root, ".nomedia").isFile }) {
            discoverNoMediaRoots(context).forEach { root ->
                storagePathKey(root.absolutePath)?.let { roots[it] = root }
            }
        }

        for (root in roots.values) {
            if (!root.exists() || !root.canRead() || !File(root, ".nomedia").isFile) {
                dao.deleteRoot(scanKey, normalizeStoragePath(root.absolutePath) ?: root.absolutePath)
                continue
            }

            val rootPath = normalizeStoragePath(root.absolutePath) ?: continue
            val entries = mutableListOf<DirectoryScanEntity>()
            val pending = mutableListOf<VideoFolder>()
            scanIndexedDirectory(
                directory = root,
                rootPath = rootPath,
                scanKey = scanKey,
                options = options,
                cached = cached,
                isNoMediaRoot = true,
                entries = entries,
            ) { folder ->
                pending += folder
                if (pending.size >= EMIT_BATCH_SIZE) {
                    emit(pending.toList())
                    pending.clear()
                }
            }
            if (pending.isNotEmpty()) emit(pending.toList())
            val changedEntries = entries.filter { entry -> cached[storagePathKey(entry.path)] != entry }
            dao.reconcileRoot(scanKey, rootPath, entries.map { it.path }, changedEntries)
        }
    }

    private suspend fun scanIndexedDirectory(
        directory: File,
        rootPath: String,
        scanKey: String,
        options: MediaScanOptions,
        cached: Map<String?, DirectoryScanEntity>,
        isNoMediaRoot: Boolean,
        entries: MutableList<DirectoryScanEntity>,
        onFolder: suspend (VideoFolder) -> Unit,
    ) {
        val files = runCatching { directory.listFiles()?.toList().orEmpty() }.getOrElse { return }
        val path = normalizeStoragePath(directory.absolutePath) ?: return
        val fingerprint = directoryFingerprint(directory, files)
        val previous = cached[storagePathKey(path)]
        val subdirectories = files.filter { it.isDirectory && shouldVisitDuringNoMediaScan(it) }

        val entity = if (previous != null && previous.fingerprint == fingerprint) {
            previous.copy(
                rootPath = rootPath,
                isNoMediaRoot = isNoMediaRoot,
            )
        } else {
            var count = 0
            var size = 0L
            var duration = 0L
            var modified = 0L
            for (file in files) {
                if (!file.isFile || file.name.startsWith(".")) continue
                val isAudio = options.includeAudio && FileTypeUtils.isAudioFile(file)
                if (!isAudio && !FileTypeUtils.isVideoFile(file)) continue
                val mediaDuration = if (isAudio) FileTypeUtils.getDurationMs(file) else 0L
                if (isAudio && !options.includesAudioDuration(mediaDuration)) continue
                count++
                size += file.length()
                duration += mediaDuration
                modified = maxOf(modified, file.lastModified() / 1000)
            }
            DirectoryScanEntity(
                scanKey = scanKey,
                path = path,
                rootPath = rootPath,
                fingerprint = fingerprint,
                isNoMediaRoot = isNoMediaRoot,
                videoCount = count,
                totalSize = size,
                totalDuration = duration,
                lastModified = modified,
                hasSubfolders = subdirectories.isNotEmpty(),
                lastScanned = System.currentTimeMillis(),
            )
        }

        entries += entity
        toVideoFolder(entity)?.let { onFolder(it) }
        for (subdirectory in subdirectories) {
            scanIndexedDirectory(
                subdirectory, rootPath, scanKey, options, cached,
                isNoMediaRoot = false, entries = entries, onFolder = onFolder,
            )
        }
    }

    private fun discoverNoMediaRoots(context: Context): List<File> {
        val primary = Environment.getExternalStorageDirectory()
        val searchRoots = linkedSetOf(primary)
        searchRoots += getPrimaryStorageSupplementalScanRoots(primary)
        StorageVolumeUtils.getExternalStorageVolumes(context).mapNotNullTo(searchRoots) { volume ->
            StorageVolumeUtils.getVolumePath(volume)?.let(::File)
        }
        val found = mutableListOf<File>()
        searchRoots.forEach { discoverNoMediaRoots(it, found, 0) }
        return found.distinctBy { storagePathKey(it.absolutePath) }
    }

    private fun discoverNoMediaRoots(
        directory: File,
        found: MutableList<File>,
        depth: Int,
    ) {
        if (depth >= MAX_DISCOVERY_DEPTH || !directory.isDirectory || !directory.canRead()) return
        if (File(directory, ".nomedia").isFile) {
            found += directory
            return
        }
        runCatching { directory.listFiles() }.getOrNull().orEmpty()
            .filter { it.isDirectory && shouldVisitDuringNoMediaScan(it) }
            .forEach { discoverNoMediaRoots(it, found, depth + 1) }
    }

    private fun shouldVisitDuringNoMediaScan(directory: File): Boolean {
        val name = directory.name.lowercase(Locale.ROOT)
        return !name.startsWith(".") && name !in NO_MEDIA_SCAN_SKIP_FOLDERS && directory.canRead()
    }

    private fun directoryFingerprint(directory: File, files: List<File>): String {
        var hash = 17L
        hash = 31 * hash + directory.lastModified()
        files.sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { file ->
            hash = 31 * hash + file.name.hashCode()
            hash = 31 * hash + if (file.isDirectory) 1 else 0
            hash = 31 * hash + file.length()
            hash = 31 * hash + file.lastModified()
        }
        return hash.toString(16)
    }

    private fun toVideoFolder(entity: DirectoryScanEntity): VideoFolder? {
        if (entity.videoCount <= 0) return null
        return VideoFolder(
            bucketId = entity.path,
            name = leafStorageName(entity.path),
            path = entity.path,
            videoCount = entity.videoCount,
            totalSize = entity.totalSize,
            totalDuration = entity.totalDuration,
            lastModified = entity.lastModified,
        )
    }

    private const val EMIT_BATCH_SIZE = 8
    private const val MAX_DISCOVERY_DEPTH = 20
    private val NO_MEDIA_SCAN_SKIP_FOLDERS = setOf(
        ".thumbnails", "thumbnails", "cache", ".cache", "tmp", "temp", "lost.dir",
        "system", ".trash", "trash", "recycler",
    )
    
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
                    if (!FileTypeUtils.isVideoFile(file)) continue
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
                
                // Build parent -> direct children index for O(1) subfolder lookups
                val parentToChildKeys = mutableMapOf<String, MutableSet<String>>()
                for ((folderKey, aggregate) in videosByFolder) {
                    val parentPath = aggregate.path.substringBeforeLast('/')
                    val parentKey = storagePathKey(parentPath)
                    if (parentKey != null) {
                        parentToChildKeys.getOrPut(parentKey) { mutableSetOf() }.add(folderKey)
                    }
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
                    
                    // O(1) subfolder check using pre-built index
                    val hasSubfolders = parentToChildKeys[folderKey]?.isNotEmpty() == true
                    
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

    private fun scanAudioMediaStoreImmediateChildren(
        context: Context,
        folders: MutableMap<String, FolderData>,
        noMediaPathFilter: NoMediaPathFilter,
        options: MediaScanOptions,
    ) {
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val audioByFolder = mutableMapOf<String, FolderAggregate>()
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val file = File(cursor.getString(dataColumn))
                    if (!file.exists() || noMediaPathFilter.shouldExcludeDirectory(file.parentFile)) continue
                    if (!FileTypeUtils.isAudioFile(file)) continue
                    val duration = cursor.getLong(durationColumn)
                    if (!options.includesAudioDuration(duration)) continue
                    val folderPath = normalizeStoragePath(file.parent) ?: continue
                    val folderKey = storagePathKey(folderPath) ?: continue
                    val aggregate = audioByFolder.getOrPut(folderKey) { FolderAggregate(folderPath) }
                    aggregate.videos += VideoInfo(cursor.getLong(sizeColumn), duration, cursor.getLong(dateColumn))
                }
            }

            for ((folderKey, aggregate) in audioByFolder) {
                val existing = folders[folderKey]
                val audioSize = aggregate.videos.sumOf { it.size }
                val audioDuration = aggregate.videos.sumOf { it.duration }
                val audioModified = aggregate.videos.maxOfOrNull { it.dateModified } ?: 0L
                val hasAudioSubfolders =
                    audioByFolder.values.any { child ->
                        areEquivalentStoragePaths(child.path.substringBeforeLast('/'), aggregate.path)
                    }
                folders[folderKey] =
                    if (existing == null) {
                        FolderData(
                            path = aggregate.path,
                            name = leafStorageName(aggregate.path),
                            videoCount = aggregate.videos.size,
                            totalSize = audioSize,
                            totalDuration = audioDuration,
                            lastModified = audioModified,
                            hasSubfolders = hasAudioSubfolders,
                        )
                    } else {
                        existing.copy(
                            videoCount = existing.videoCount + aggregate.videos.size,
                            totalSize = existing.totalSize + audioSize,
                            totalDuration = existing.totalDuration + audioDuration,
                            lastModified = maxOf(existing.lastModified, audioModified),
                            hasSubfolders = existing.hasSubfolders || hasAudioSubfolders,
                        )
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore audio folder scan error", e)
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
            
            val mediaFiles = mutableListOf<File>()
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
                            if (FileTypeUtils.isSupportedMediaFile(file, options)) {
                                val isAudio = FileTypeUtils.isAudioFile(file)
                                val duration = if (isAudio) FileTypeUtils.getDurationMs(file) else 0L
                                if (!isAudio || options.includesAudioDuration(duration)) {
                                    mediaFiles.add(file)
                                }
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    continue
                }
            }
            
            // Add folder if it has videos
            if (mediaFiles.isNotEmpty()) {
                val folderPath = normalizeStoragePath(directory.absolutePath) ?: return
                val folderKey = storagePathKey(folderPath) ?: return
                
                // Skip if already from MediaStore
                if (!folders.containsKey(folderKey)) {
                    var totalSize = 0L
                    var lastModified = 0L
                    
                    var totalDuration = 0L
                    for (media in mediaFiles) {
                        totalSize += media.length()
                        if (FileTypeUtils.isAudioFile(media)) totalDuration += FileTypeUtils.getDurationMs(media)
                        val modified = media.lastModified()
                        if (modified > lastModified) {
                            lastModified = modified
                        }
                    }
                    
                    folders[folderKey] = FolderData(
                        path = folderPath,
                        name = leafStorageName(folderPath),
                        videoCount = mediaFiles.size,
                        totalSize = totalSize,
                        totalDuration = totalDuration,
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

