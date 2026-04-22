package app.gyrolet.mpvrx.utils.storage

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Tree View Scanner - Optimized for tree/browser view.
 *
 * Includes parent folders, recursive counts, smart single-child flattening,
 * and recursive NEW badge counts for folders.
 */
object TreeViewScanner {
  private const val TAG = "TreeViewScanner"

  private var cachedTreeViewData: Map<String, FolderNode>? = null
  private var cacheTimestamp: Long = 0
  private var cacheOptionsKey: String? = null
  private const val CACHE_TTL_MS = 10_000L

  fun clearCache() {
    cachedTreeViewData = null
    cacheTimestamp = 0
    cacheOptionsKey = null
  }

  data class FolderData(
    val path: String,
    val name: String,
    val videoCount: Int,
    val totalSize: Long,
    val totalDuration: Long,
    val lastModified: Long,
    val hasSubfolders: Boolean = false,
    val newCount: Int = 0,
  )

  private data class VideoInfo(
    val displayName: String,
    val size: Long,
    val duration: Long,
    val dateModified: Long,
  )

  private data class FolderAggregate(
    var path: String,
    val videos: MutableList<VideoInfo> = mutableListOf(),
  )

  private data class FolderNode(
    var path: String,
    var name: String,
    var directVideoCount: Int = 0,
    var directSize: Long = 0L,
    var directDuration: Long = 0L,
    var directLastModified: Long = 0L,
    var directNewCount: Int = 0,
    var hasDirectSubfolders: Boolean = false,
    var isFlattened: Boolean = false,
    var recursiveVideoCount: Int = 0,
    var recursiveSize: Long = 0L,
    var recursiveDuration: Long = 0L,
    var recursiveLastModified: Long = 0L,
    var recursiveNewCount: Int = 0,
  )

  private data class NewBadgeConfig(
    val enabled: Boolean,
    val thresholdMillis: Long,
    val playedMediaTitles: Set<String>,
  )

  suspend fun getFoldersInDirectory(
    context: Context,
    parentPath: String,
    options: MediaScanOptions = MediaScanOptions(),
    forceFileSystemCheck: Boolean = false,
    playedMediaTitles: Set<String> = emptySet(),
    showNewLabels: Boolean = false,
    thresholdDays: Int = 7,
  ): List<FolderData> = withContext(Dispatchers.IO) {
    val allFolders =
      getOrBuildTreeViewData(
        context = context,
        options = options,
        forceFileSystemCheck = forceFileSystemCheck,
        playedMediaTitles = playedMediaTitles,
        showNewLabels = showNewLabels,
        thresholdDays = thresholdDays,
      )

    getEffectiveChildren(parentPath, allFolders)
      .map(::toFolderData)
      .sortedBy { it.name.lowercase(Locale.getDefault()) }
  }

  suspend fun getFolderDataRecursive(
    context: Context,
    folderPath: String,
    options: MediaScanOptions = MediaScanOptions(),
    forceFileSystemCheck: Boolean = false,
    playedMediaTitles: Set<String> = emptySet(),
    showNewLabels: Boolean = false,
    thresholdDays: Int = 7,
  ): FolderData? = withContext(Dispatchers.IO) {
    val allFolders =
      getOrBuildTreeViewData(
        context = context,
        options = options,
        forceFileSystemCheck = forceFileSystemCheck,
        playedMediaTitles = playedMediaTitles,
        showNewLabels = showNewLabels,
        thresholdDays = thresholdDays,
      )
    val normalizedFolderPath = normalizeStoragePath(folderPath) ?: return@withContext null
    val folderKey = storagePathKey(normalizedFolderPath) ?: return@withContext null

    allFolders[folderKey]?.let { return@withContext toFolderData(it) }

    val children = getEffectiveChildren(normalizedFolderPath, allFolders)
    if (children.isEmpty()) {
      return@withContext null
    }

    FolderData(
      path = normalizedFolderPath,
      name = leafStorageName(normalizedFolderPath),
      videoCount = children.sumOf { it.recursiveVideoCount },
      totalSize = children.sumOf { it.recursiveSize },
      totalDuration = children.sumOf { it.recursiveDuration },
      lastModified = children.maxOfOrNull { it.recursiveLastModified } ?: 0L,
      hasSubfolders = true,
      newCount = children.sumOf { it.recursiveNewCount },
    )
  }

  private suspend fun getOrBuildTreeViewData(
    context: Context,
    options: MediaScanOptions,
    forceFileSystemCheck: Boolean,
    playedMediaTitles: Set<String>,
    showNewLabels: Boolean,
    thresholdDays: Int,
  ): Map<String, FolderNode> = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    val cacheKey =
      buildCacheKey(
        options = options,
        showNewLabels = showNewLabels,
        thresholdDays = thresholdDays,
        playedMediaTitles = playedMediaTitles,
      )

    cachedTreeViewData?.let { cached ->
      if (!forceFileSystemCheck && now - cacheTimestamp < CACHE_TTL_MS && cacheOptionsKey == cacheKey) {
        return@withContext cached
      }
    }

    val data =
      buildTreeViewData(
        context = context,
        options = options,
        forceFileSystemCheck = forceFileSystemCheck,
        playedMediaTitles = playedMediaTitles,
        showNewLabels = showNewLabels,
        thresholdDays = thresholdDays,
      )

    cachedTreeViewData = data
    cacheTimestamp = now
    cacheOptionsKey = cacheKey
    data
  }

  private fun buildCacheKey(
    options: MediaScanOptions,
    showNewLabels: Boolean,
    thresholdDays: Int,
    playedMediaTitles: Set<String>,
  ): String {
    val playedTitlesHash =
      if (showNewLabels) {
        playedMediaTitles.toList().sorted().hashCode()
      } else {
        0
      }

    return "${options.cacheKey}|new=$showNewLabels|days=$thresholdDays|played=$playedTitlesHash"
  }

  private suspend fun buildTreeViewData(
    context: Context,
    options: MediaScanOptions,
    forceFileSystemCheck: Boolean,
    playedMediaTitles: Set<String>,
    showNewLabels: Boolean,
    thresholdDays: Int,
  ): Map<String, FolderNode> = withContext(Dispatchers.IO) {
    val allFolders = mutableMapOf<String, FolderNode>()
    val noMediaPathFilter = NoMediaPathFilter(options)
    val storageRootKeys = getStorageRootKeys(context)
    val newBadgeConfig =
      NewBadgeConfig(
        enabled = showNewLabels,
        thresholdMillis = thresholdDays.toLong() * 24L * 60L * 60L * 1000L,
        playedMediaTitles = playedMediaTitles,
      )
    val currentTimeMs = System.currentTimeMillis()

    scanMediaStoreRecursive(context, allFolders, noMediaPathFilter, newBadgeConfig, currentTimeMs)
    scanFileSystemRoots(
      context = context,
      folders = allFolders,
      options = options,
      noMediaPathFilter = noMediaPathFilter,
      forceFileSystemCheck = forceFileSystemCheck,
      newBadgeConfig = newBadgeConfig,
      currentTimeMs = currentTimeMs,
    )
    buildParentHierarchy(allFolders)
    markFlattenedFolders(allFolders, storageRootKeys)

    allFolders.entries.removeIf { it.value.recursiveVideoCount <= 0 && !it.value.isFlattened }
    allFolders
  }

  private fun scanMediaStoreRecursive(
    context: Context,
    folders: MutableMap<String, FolderNode>,
    noMediaPathFilter: NoMediaPathFilter,
    newBadgeConfig: NewBadgeConfig,
    currentTimeMs: Long,
  ) {
    val projection =
      arrayOf(
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.DATE_MODIFIED,
      )

    try {
      context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        null,
      )?.use { cursor ->
        val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
        val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

        val videosByFolder = mutableMapOf<String, FolderAggregate>()

        while (cursor.moveToNext()) {
          val videoPath = cursor.getString(dataColumn)
          val file = File(videoPath)

          if (!file.exists()) continue
          if (noMediaPathFilter.shouldExcludeDirectory(file.parentFile)) continue

          val folderPath = normalizeStoragePath(file.parent) ?: continue
          val folderKey = storagePathKey(folderPath) ?: continue
          val aggregate = videosByFolder.getOrPut(folderKey) { FolderAggregate(path = folderPath) }
          aggregate.path = choosePreferredStoragePath(aggregate.path, folderPath)
          aggregate.videos.add(
            VideoInfo(
              displayName = cursor.getString(displayNameColumn) ?: file.name,
              size = cursor.getLong(sizeColumn),
              duration = cursor.getLong(durationColumn),
              dateModified = cursor.getLong(dateColumn),
            ),
          )
        }

        for ((folderKey, aggregate) in videosByFolder) {
          folders[folderKey] =
            createDirectNode(
              folderPath = aggregate.path,
              videos = aggregate.videos,
              newBadgeConfig = newBadgeConfig,
              currentTimeMs = currentTimeMs,
            )
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "MediaStore scan error", e)
    }
  }

  private fun createDirectNode(
    folderPath: String,
    videos: List<VideoInfo>,
    newBadgeConfig: NewBadgeConfig,
    currentTimeMs: Long,
  ): FolderNode {
    val newCount =
      if (newBadgeConfig.enabled) {
        videos.count { video ->
          isVideoNew(
            displayName = video.displayName,
            dateModifiedSeconds = video.dateModified,
            currentTimeMs = currentTimeMs,
            newBadgeConfig = newBadgeConfig,
          )
        }
      } else {
        0
      }

    return FolderNode(
      path = folderPath,
      name = leafStorageName(folderPath),
      directVideoCount = videos.size,
      directSize = videos.sumOf { it.size },
      directDuration = videos.sumOf { it.duration },
      directLastModified = videos.maxOfOrNull { it.dateModified } ?: 0L,
      directNewCount = newCount,
    )
  }

  private fun isVideoNew(
    displayName: String,
    dateModifiedSeconds: Long,
    currentTimeMs: Long,
    newBadgeConfig: NewBadgeConfig,
  ): Boolean {
    if (!newBadgeConfig.enabled) {
      return false
    }

    if (displayName in newBadgeConfig.playedMediaTitles) {
      return false
    }

    val videoAgeMs = currentTimeMs - (dateModifiedSeconds * 1000L)
    return videoAgeMs <= newBadgeConfig.thresholdMillis
  }

  private fun scanFileSystemRoots(
    context: Context,
    folders: MutableMap<String, FolderNode>,
    options: MediaScanOptions,
    noMediaPathFilter: NoMediaPathFilter,
    forceFileSystemCheck: Boolean,
    newBadgeConfig: NewBadgeConfig,
    currentTimeMs: Long,
  ) {
    try {
      val rootsToScan = linkedSetOf<File>()
      val primaryStorageRoot = Environment.getExternalStorageDirectory()

      if (shouldIncludePrimaryStorageInFilesystemFolderScan(options, forceFileSystemCheck)) {
        rootsToScan += primaryStorageRoot
      }

      rootsToScan += getPrimaryStorageSupplementalScanRoots(primaryStorageRoot)

      for (volume in StorageVolumeUtils.getExternalStorageVolumes(context)) {
        val volumePath = StorageVolumeUtils.getVolumePath(volume) ?: continue
        rootsToScan += File(volumePath)
      }

      for (root in rootsToScan) {
        if (!root.exists() || !root.canRead() || !root.isDirectory) {
          continue
        }

        scanDirectoryRecursive(
          directory = root,
          folders = folders,
          maxDepth = 20,
          options = options,
          noMediaPathFilter = noMediaPathFilter,
          newBadgeConfig = newBadgeConfig,
          currentTimeMs = currentTimeMs,
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Filesystem tree scan error", e)
    }
  }

  private fun scanDirectoryRecursive(
    directory: File,
    folders: MutableMap<String, FolderNode>,
    maxDepth: Int,
    options: MediaScanOptions,
    noMediaPathFilter: NoMediaPathFilter,
    newBadgeConfig: NewBadgeConfig,
    currentTimeMs: Long,
    currentDepth: Int = 0,
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
        } catch (_: SecurityException) {
        }
      }

      val folderPath = normalizeStoragePath(directory.absolutePath) ?: return
      val folderKey = storagePathKey(folderPath) ?: return

      if (videoFiles.isNotEmpty()) {
        val existingNode = folders[folderKey]
        val directNewCount =
          if (newBadgeConfig.enabled) {
            videoFiles.count { file ->
              isVideoNew(
                displayName = file.name,
                dateModifiedSeconds = file.lastModified() / 1000L,
                currentTimeMs = currentTimeMs,
                newBadgeConfig = newBadgeConfig,
              )
            }
          } else {
            0
          }

        if (existingNode == null) {
          folders[folderKey] =
            FolderNode(
              path = folderPath,
              name = leafStorageName(folderPath),
              directVideoCount = videoFiles.size,
              directSize = videoFiles.sumOf { it.length() },
              directDuration = 0L,
              directLastModified = (videoFiles.maxOfOrNull { it.lastModified() } ?: 0L) / 1000L,
              directNewCount = directNewCount,
              hasDirectSubfolders = subdirectories.isNotEmpty(),
            )
        } else {
          existingNode.path = choosePreferredStoragePath(existingNode.path, folderPath)
          existingNode.name = leafStorageName(existingNode.path)
          existingNode.hasDirectSubfolders = existingNode.hasDirectSubfolders || subdirectories.isNotEmpty()
        }
      } else if (subdirectories.isNotEmpty()) {
        folders[folderKey]?.hasDirectSubfolders = true
      }

      for (subdir in subdirectories) {
        scanDirectoryRecursive(
          directory = subdir,
          folders = folders,
          maxDepth = maxDepth,
          options = options,
          noMediaPathFilter = noMediaPathFilter,
          newBadgeConfig = newBadgeConfig,
          currentTimeMs = currentTimeMs,
          currentDepth = currentDepth + 1,
        )
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error scanning: ${directory.absolutePath}", e)
    }
  }

  private fun buildParentHierarchy(folders: MutableMap<String, FolderNode>) {
    for (folderData in folders.values.toList()) {
      var currentPath = parentStoragePath(folderData.path)
      while (currentPath != null && currentPath != "/" && currentPath.length > 1) {
        val currentKey = storagePathKey(currentPath) ?: break
        folders.putIfAbsent(
          currentKey,
          FolderNode(
            path = currentPath,
            name = leafStorageName(currentPath),
          ),
        )
        currentPath = parentStoragePath(currentPath)
      }
    }

    folders.values.forEach { node ->
      node.recursiveVideoCount = node.directVideoCount
      node.recursiveSize = node.directSize
      node.recursiveDuration = node.directDuration
      node.recursiveLastModified = node.directLastModified
      node.recursiveNewCount = node.directNewCount
    }

    val sortedPaths =
      folders.values
        .sortedByDescending { normalizeStoragePath(it.path)?.count { c -> c == '/' } ?: 0 }
        .mapNotNull { storagePathKey(it.path) }
        .distinct()

    for (pathKey in sortedPaths) {
      val childData = folders[pathKey] ?: continue
      val parentPath = parentStoragePath(childData.path) ?: continue
      val parentKey = storagePathKey(parentPath) ?: continue
      val parentData = folders[parentKey] ?: continue

      parentData.path = choosePreferredStoragePath(parentData.path, parentPath)
      parentData.name = leafStorageName(parentData.path)
      parentData.recursiveVideoCount += childData.recursiveVideoCount
      parentData.recursiveSize += childData.recursiveSize
      parentData.recursiveDuration += childData.recursiveDuration
      parentData.recursiveLastModified =
        maxOf(parentData.recursiveLastModified, childData.recursiveLastModified)
      parentData.recursiveNewCount += childData.recursiveNewCount
      parentData.hasDirectSubfolders = true
    }
  }

  private fun markFlattenedFolders(
    folders: MutableMap<String, FolderNode>,
    storageRootKeys: Set<String>,
  ) {
    val sortedPaths =
      folders.values
        .sortedBy { normalizeStoragePath(it.path)?.count { c -> c == '/' } ?: 0 }
        .mapNotNull { storagePathKey(it.path) }
        .distinct()

    for (pathKey in sortedPaths) {
      val node = folders[pathKey] ?: continue
      if (node.directVideoCount > 0) {
        continue
      }

      val childrenWithMedia =
        getDirectChildren(node.path, folders)
          .filter { it.recursiveVideoCount > 0 }

      if (childrenWithMedia.size < 2 && pathKey !in storageRootKeys) {
        node.isFlattened = true
      }
    }
  }

  private fun getDirectChildren(
    parentPath: String,
    allNodes: Map<String, FolderNode>,
  ): List<FolderNode> =
    allNodes.values.filter { node -> isDirectStorageChild(parentPath, node.path) }

  private fun getEffectiveChildren(
    parentPath: String,
    allNodes: Map<String, FolderNode>,
  ): List<FolderNode> {
    val directChildren = getDirectChildren(parentPath, allNodes)
    val result = mutableListOf<FolderNode>()

    for (child in directChildren) {
      if (child.isFlattened) {
        result += getEffectiveChildren(child.path, allNodes)
      } else {
        result += child
      }
    }

    return result
  }

  private fun toFolderData(node: FolderNode): FolderData =
    FolderData(
      path = node.path,
      name = node.name,
      videoCount = node.recursiveVideoCount,
      totalSize = node.recursiveSize,
      totalDuration = node.recursiveDuration,
      lastModified = node.recursiveLastModified,
      hasSubfolders = node.hasDirectSubfolders,
      newCount = node.recursiveNewCount,
    )

  private fun getStorageRootKeys(context: Context): Set<String> {
    val rootKeys = mutableSetOf<String>()
    storagePathKey(Environment.getExternalStorageDirectory().absolutePath)?.let(rootKeys::add)

    for (volume in StorageVolumeUtils.getExternalStorageVolumes(context)) {
      storagePathKey(StorageVolumeUtils.getVolumePath(volume))?.let(rootKeys::add)
    }

    return rootKeys
  }
}
