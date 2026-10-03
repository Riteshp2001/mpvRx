/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.storage

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

  /**
   * Published as one immutable value so a reader can never pair a tree with another write's
   * timestamp or options key.
   */
  private class TreeCache(
    val index: TreeIndex,
    val timestamp: Long,
    val optionsKey: String,
  )

  @Volatile private var cache: TreeCache? = null

  /**
   * Wide enough that walking between sibling folders reuses the tree. The previous 10s window
   * was shorter than a single dot-folder scan, and it was measured from the start of the build,
   * so any build slower than the TTL produced a cache that was already expired and the next tap
   * re-walked shared storage.
   */
  private const val CACHE_TTL_MS = 30_000L

  /**
   * Directories one filesystem pass may touch, per root. Shared storage can hold far more than
   * any tree can usefully render; without a ceiling a single pass is unbounded and never paints.
   */
  private const val MAX_SCANNED_DIRECTORIES = 12_000

  fun clearCache() {
    cache = null
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
    val filePath: String,
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
    val newLabelOverrides: Map<String, Boolean>,
  )

  /**
   * The whole tree plus a parent-to-children lookup.
   *
   * Looking children up by rescanning every node made each folder listing cost O(N) and the
   * flatten pass O(N^2). Indexing children once at build time makes both proportional to the
   * number of direct children.
   */
  private class TreeIndex(
    val nodes: Map<String, FolderNode>,
    val childrenByParentKey: Map<String, List<FolderNode>>,
  ) {
    fun childrenOf(parentPath: String): List<FolderNode> =
      childrenByParentKey[storagePathKey(parentPath)].orEmpty()
  }

  suspend fun getFoldersInDirectory(
    context: Context,
    parentPath: String,
    options: MediaScanOptions = MediaScanOptions(),
    forceFileSystemCheck: Boolean = false,
    playedMediaTitles: Set<String> = emptySet(),
    showNewLabels: Boolean = false,
    thresholdDays: Int = 7,
    maxAutoFlattenLevels: Int = -1,
    newLabelOverrides: Map<String, Boolean> = emptyMap(),
  ): List<FolderData> =
    withContext(Dispatchers.IO) {
      val index =
        getOrBuildTreeViewData(
          context = context,
          options = options,
          forceFileSystemCheck = forceFileSystemCheck,
          playedMediaTitles = playedMediaTitles,
          showNewLabels = showNewLabels,
          thresholdDays = thresholdDays,
          newLabelOverrides = newLabelOverrides,
        )

      getEffectiveChildren(parentPath, index, maxAutoFlattenLevels)
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
    newLabelOverrides: Map<String, Boolean> = emptyMap(),
  ): FolderData? =
    withContext(Dispatchers.IO) {
      val index =
        getOrBuildTreeViewData(
          context = context,
          options = options,
          forceFileSystemCheck = forceFileSystemCheck,
          playedMediaTitles = playedMediaTitles,
          showNewLabels = showNewLabels,
          thresholdDays = thresholdDays,
          newLabelOverrides = newLabelOverrides,
        )
      val normalizedFolderPath = normalizeStoragePath(folderPath) ?: return@withContext null
      val folderKey = storagePathKey(normalizedFolderPath) ?: return@withContext null

      index.nodes[folderKey]?.let { return@withContext toFolderData(it) }

      val children = getEffectiveChildren(normalizedFolderPath, index)
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
    newLabelOverrides: Map<String, Boolean>,
  ): TreeIndex =
    withContext(Dispatchers.IO) {
      val cacheKey =
        buildCacheKey(
          options = options,
          showNewLabels = showNewLabels,
          thresholdDays = thresholdDays,
          playedMediaTitles = playedMediaTitles,
          newLabelOverrides = newLabelOverrides,
        )

      cache?.let { cached ->
        val age = System.currentTimeMillis() - cached.timestamp
        if (!forceFileSystemCheck && age < CACHE_TTL_MS && cached.optionsKey == cacheKey) {
          return@withContext cached.index
        }
      }

      val index =
        buildTreeViewData(
          context = context,
          options = options,
          forceFileSystemCheck = forceFileSystemCheck,
          playedMediaTitles = playedMediaTitles,
          showNewLabels = showNewLabels,
          thresholdDays = thresholdDays,
          newLabelOverrides = newLabelOverrides,
        )

      // Stamp on completion, not on start, so a slow build still yields a full window of hits.
      cache = TreeCache(index = index, timestamp = System.currentTimeMillis(), optionsKey = cacheKey)
      index
    }

  private fun buildCacheKey(
    options: MediaScanOptions,
    showNewLabels: Boolean,
    thresholdDays: Int,
    playedMediaTitles: Set<String>,
    newLabelOverrides: Map<String, Boolean>,
  ): String {
    val playedTitlesHash =
      if (showNewLabels) {
        playedMediaTitles.toList().sorted().hashCode()
      } else {
        0
      }

    return "${options.cacheKey}|new=$showNewLabels|days=$thresholdDays|played=$playedTitlesHash" +
      "|marks=${newLabelOverrides.hashCode()}"
  }

  private suspend fun buildTreeViewData(
    context: Context,
    options: MediaScanOptions,
    forceFileSystemCheck: Boolean,
    playedMediaTitles: Set<String>,
    showNewLabels: Boolean,
    thresholdDays: Int,
    newLabelOverrides: Map<String, Boolean>,
  ): TreeIndex =
    withContext(Dispatchers.IO) {
      val allFolders = mutableMapOf<String, FolderNode>()
      val noMediaPathFilter = NoMediaPathFilter(options)
      val storageRootKeys = getStorageRootKeys(context)
      val newBadgeConfig =
        NewBadgeConfig(
          enabled = showNewLabels,
          thresholdMillis = thresholdDays.toLong() * 24L * 60L * 60L * 1000L,
          playedMediaTitles = playedMediaTitles,
          newLabelOverrides = newLabelOverrides,
        )
      val currentTimeMs = System.currentTimeMillis()
      val mediaStoreStartedAt = System.currentTimeMillis()

      scanMediaStoreRecursive(context, allFolders, noMediaPathFilter, newBadgeConfig, currentTimeMs)
      if (options.includeAudio) {
        scanAudioMediaStoreRecursive(
          context,
          allFolders,
          noMediaPathFilter,
          newBadgeConfig,
          currentTimeMs,
          options,
        )
      }
      val mediaStoreElapsed = System.currentTimeMillis() - mediaStoreStartedAt

      val fileSystemStartedAt = System.currentTimeMillis()
      scanFileSystemRoots(
        context = context,
        folders = allFolders,
        options = options,
        noMediaPathFilter = noMediaPathFilter,
        forceFileSystemCheck = forceFileSystemCheck,
        newBadgeConfig = newBadgeConfig,
        currentTimeMs = currentTimeMs,
      )
      val fileSystemElapsed = System.currentTimeMillis() - fileSystemStartedAt

      Log.d(
        TAG,
        "Tree scan: MediaStore ${mediaStoreElapsed}ms, filesystem ${fileSystemElapsed}ms, " +
          "${allFolders.size} nodes, nomedia=${options.includeNoMediaFolders}",
      )
      buildParentHierarchy(allFolders)
      // Flattening inspects every node, so it needs the pre-prune tree; the index handed to
      // callers is built after pruning so pruned placeholders cannot resurface as children.
      markFlattenedFolders(allFolders, storageRootKeys, buildChildrenIndex(allFolders))

      allFolders.entries.removeIf { it.value.recursiveVideoCount <= 0 && !it.value.isFlattened }
      TreeIndex(nodes = allFolders, childrenByParentKey = buildChildrenIndex(allFolders))
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
      context.contentResolver
        .query(
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
            if (!FileTypeUtils.isVideoFile(file)) continue
            if (noMediaPathFilter.shouldExcludeDirectory(file.parentFile)) continue

            val folderPath = normalizeStoragePath(file.parent) ?: continue
            val folderKey = storagePathKey(folderPath) ?: continue
            val aggregate = videosByFolder.getOrPut(folderKey) { FolderAggregate(path = folderPath) }
            aggregate.path = choosePreferredStoragePath(aggregate.path, folderPath)
            aggregate.videos.add(
              VideoInfo(
                displayName = cursor.getString(displayNameColumn) ?: file.name,
                filePath = file.absolutePath,
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
            filePath = video.filePath,
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

  private fun scanAudioMediaStoreRecursive(
    context: Context,
    folders: MutableMap<String, FolderNode>,
    noMediaPathFilter: NoMediaPathFilter,
    newBadgeConfig: NewBadgeConfig,
    currentTimeMs: Long,
    options: MediaScanOptions,
  ) {
    val projection =
      arrayOf(
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATE_MODIFIED,
      )
    try {
      context.contentResolver
        .query(
          MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
          projection,
          null,
          null,
          null,
        )?.use { cursor ->
          val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
          val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
          val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
          val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
          val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
          val audioByFolder = mutableMapOf<String, FolderAggregate>()

          while (cursor.moveToNext()) {
            val file = File(cursor.getString(dataColumn))
            if (!file.exists() || noMediaPathFilter.shouldExcludeDirectory(file.parentFile)) continue
            if (!FileTypeUtils.isAudioFile(file)) continue
            val duration = cursor.getLong(durationColumn)
            if (!options.includesAudioDuration(duration)) continue
            val folderPath = normalizeStoragePath(file.parent) ?: continue
            val folderKey = storagePathKey(folderPath) ?: continue
            audioByFolder.getOrPut(folderKey) { FolderAggregate(folderPath) }.videos +=
              VideoInfo(
                displayName = cursor.getString(nameColumn) ?: file.name,
                filePath = file.absolutePath,
                size = cursor.getLong(sizeColumn),
                duration = duration,
                dateModified = cursor.getLong(dateColumn),
              )
          }

          for ((folderKey, aggregate) in audioByFolder) {
            val audioNode = createDirectNode(aggregate.path, aggregate.videos, newBadgeConfig, currentTimeMs)
            val existing = folders[folderKey]
            folders[folderKey] =
              if (existing == null) {
                audioNode
              } else {
                existing.apply {
                  directVideoCount += audioNode.directVideoCount
                  directSize += audioNode.directSize
                  directDuration += audioNode.directDuration
                  directLastModified = maxOf(directLastModified, audioNode.directLastModified)
                  directNewCount += audioNode.directNewCount
                }
              }
          }
        }
    } catch (e: Exception) {
      Log.e(TAG, "MediaStore audio tree scan error", e)
    }
  }

  private fun isVideoNew(
    displayName: String,
    filePath: String,
    dateModifiedSeconds: Long,
    currentTimeMs: Long,
    newBadgeConfig: NewBadgeConfig,
  ): Boolean {
    if (!newBadgeConfig.enabled) {
      return false
    }

    // Title lookup is a plain set hit; only compute the digest identity when it misses, since
    // hashing runs for every file in the tree on every rebuild.
    if (displayName in newBadgeConfig.playedMediaTitles) {
      return false
    }
    val identifier = PlaybackIdentity.forLocalPath(filePath)
    if (identifier in newBadgeConfig.playedMediaTitles) {
      return false
    }
    newBadgeConfig.newLabelOverrides[identifier]?.let { return it }

    val videoAgeMs = currentTimeMs - (dateModifiedSeconds * 1000L)
    return newBadgeConfig.thresholdMillis == 0L || videoAgeMs <= newBadgeConfig.thresholdMillis
  }

  private suspend fun scanFileSystemRoots(
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

      // Shared across every root so overlapping roots (primary storage already contains
      // Android/data and Android/media) are walked once, and so a symlink pointing at an
      // ancestor cannot re-enter a subtree that has already been visited.
      val visitedDirectories = mutableSetOf<String>()

      for (root in rootsToScan) {
        currentCoroutineContext().ensureActive()
        if (!root.exists() || !root.canRead() || !root.isDirectory) {
          continue
        }

        // Budgeted per root so a heavy primary-storage tree cannot consume the whole
        // allowance and starve removable volumes.
        var scannedForRoot = 0
        scanDirectoryRecursive(
          directory = root,
          folders = folders,
          maxDepth = 20,
          options = options,
          noMediaPathFilter = noMediaPathFilter,
          newBadgeConfig = newBadgeConfig,
          currentTimeMs = currentTimeMs,
          visitedDirectories = visitedDirectories,
          remainingBudget = MAX_SCANNED_DIRECTORIES,
          onDirectoryScanned = { scannedForRoot++ },
        )
        if (scannedForRoot >= MAX_SCANNED_DIRECTORIES) {
          Log.w(TAG, "Tree scan of ${root.absolutePath} hit the $scannedForRoot directory cap; subtree is partial")
        }
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Log.e(TAG, "Filesystem tree scan error", e)
    }
  }

  private suspend fun scanDirectoryRecursive(
    directory: File,
    folders: MutableMap<String, FolderNode>,
    maxDepth: Int,
    options: MediaScanOptions,
    noMediaPathFilter: NoMediaPathFilter,
    newBadgeConfig: NewBadgeConfig,
    currentTimeMs: Long,
    visitedDirectories: MutableSet<String>,
    remainingBudget: Int,
    onDirectoryScanned: () -> Unit,
    currentDepth: Int = 0,
  ) {
    if (remainingBudget <= 0) return
    currentCoroutineContext().ensureActive()
    if (currentDepth >= maxDepth) return
    if (!directory.exists() || !directory.canRead() || !directory.isDirectory) return
    if (FileFilterUtils.shouldSkipFolder(directory, options, noMediaPathFilter)) return

    try {
      if (!visitedDirectories.add(directory.canonicalPath)) return
      onDirectoryScanned()
      val files = directory.listFiles() ?: return
      val mediaFiles = mutableListOf<File>()
      val subdirectories = mutableListOf<File>()

      for (file in files) {
        currentCoroutineContext().ensureActive()
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
        } catch (_: SecurityException) {
        }
      }

      val folderPath = normalizeStoragePath(directory.absolutePath) ?: return
      val folderKey = storagePathKey(folderPath) ?: return

      if (mediaFiles.isNotEmpty()) {
        val existingNode = folders[folderKey]
        if (existingNode == null) {
          folders[folderKey] =
            FolderNode(
              path = folderPath,
              name = leafStorageName(folderPath),
              directVideoCount = mediaFiles.size,
              directSize = mediaFiles.sumOf { it.length() },
              directDuration = mediaFiles.filter(FileTypeUtils::isAudioFile).sumOf(FileTypeUtils::getDurationMs),
              directLastModified = (mediaFiles.maxOfOrNull { it.lastModified() } ?: 0L) / 1000L,
              directNewCount = countNewMedia(mediaFiles, newBadgeConfig, currentTimeMs),
              hasDirectSubfolders = subdirectories.isNotEmpty(),
            )
        } else {
          // MediaStore already counted this folder, so only its shape is corrected here.
          // Scoring NEW badges now would hash every file and then discard the result.
          existingNode.path = choosePreferredStoragePath(existingNode.path, folderPath)
          existingNode.name = leafStorageName(existingNode.path)
          existingNode.hasDirectSubfolders = existingNode.hasDirectSubfolders || subdirectories.isNotEmpty()
        }
      } else if (subdirectories.isNotEmpty()) {
        folders[folderKey]?.hasDirectSubfolders = true
      }

      // This directory already charged itself to the budget via onDirectoryScanned().
      var budgetLeft = remainingBudget - 1
      for (subdir in subdirectories) {
        if (budgetLeft <= 0) break
        val visitedBefore = visitedDirectories.size
        scanDirectoryRecursive(
          directory = subdir,
          folders = folders,
          maxDepth = maxDepth,
          options = options,
          noMediaPathFilter = noMediaPathFilter,
          newBadgeConfig = newBadgeConfig,
          currentTimeMs = currentTimeMs,
          visitedDirectories = visitedDirectories,
          remainingBudget = budgetLeft,
          onDirectoryScanned = onDirectoryScanned,
          currentDepth = currentDepth + 1,
        )
        budgetLeft -= visitedDirectories.size - visitedBefore
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Log.w(TAG, "Error scanning: ${directory.absolutePath}", e)
    }
  }

  private fun countNewMedia(
    mediaFiles: List<File>,
    newBadgeConfig: NewBadgeConfig,
    currentTimeMs: Long,
  ): Int {
    if (!newBadgeConfig.enabled) return 0
    return mediaFiles.count { file ->
      isVideoNew(
        displayName = file.name,
        filePath = file.absolutePath,
        dateModifiedSeconds = file.lastModified() / 1000L,
        currentTimeMs = currentTimeMs,
        newBadgeConfig = newBadgeConfig,
      )
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
    childrenByParentKey: Map<String, List<FolderNode>>,
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
        storagePathKey(node.path)
          ?.let { childrenByParentKey[it] }
          .orEmpty()
          .filter { it.recursiveVideoCount > 0 }

      if (childrenWithMedia.size == 1 && pathKey !in storageRootKeys) {
        node.isFlattened = true
      }
    }
  }

  private fun buildChildrenIndex(nodes: Map<String, FolderNode>): Map<String, List<FolderNode>> {
    val childrenByParentKey = mutableMapOf<String, MutableList<FolderNode>>()
    for (node in nodes.values) {
      val parentPath = parentStoragePath(node.path) ?: continue
      val parentKey = storagePathKey(parentPath) ?: continue
      childrenByParentKey.getOrPut(parentKey) { mutableListOf() }.add(node)
    }
    return childrenByParentKey
  }

  private fun getEffectiveChildren(
    parentPath: String,
    index: TreeIndex,
    remainingLevels: Int = -1,
  ): List<FolderNode> {
    val directChildren = index.childrenOf(parentPath)
    if (directChildren.isEmpty()) return emptyList()

    val result = mutableListOf<FolderNode>()
    for (child in directChildren) {
      if (child.isFlattened && remainingLevels != 0) {
        val nextLevel = if (remainingLevels < 0) -1 else remainingLevels - 1
        result += getEffectiveChildren(child.path, index, nextLevel)
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
