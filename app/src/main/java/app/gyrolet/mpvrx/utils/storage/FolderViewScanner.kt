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
import app.gyrolet.mpvrx.database.dao.DirectoryScanDao
import app.gyrolet.mpvrx.database.entities.DirectoryScanEntity
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.utils.media.ProgressiveResultsPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.PriorityQueue

/**
 * Folder View Scanner - Optimized for folder list view
 *
 * Only shows folders with immediate video children (not recursive)
 * Fast scanning using MediaStore + filesystem fallback
 */
object FolderViewScanner {
  private const val TAG = "FolderViewScanner"

  private data class FolderCache(
    val folders: List<VideoFolder>,
    val createdAt: Long,
    val optionsKey: String,
  )

  // MediaStore is already invalidated by library events; keep it warm between screen visits.
  @Volatile
  private var folderCache: FolderCache? = null
  private const val CACHE_TTL_MS = 5 * 60_000L

  /**
   * Clear cache (call when media library changes)
   */
  fun clearCache() {
    folderCache = null
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
    val hasSubfolders: Boolean = false,
  )

  /**
   * Helper data class for video info during scanning
   */
  private data class VideoInfo(
    val size: Long,
    val duration: Long,
    val dateModified: Long,
  )

  private data class FolderAggregate(
    var path: String,
    val videos: MutableList<VideoInfo> = mutableListOf(),
  )

  private data class DirectoryWork(
    val file: File,
    val rootPath: String,
    val isNoMediaRoot: Boolean,
    val lastScanned: Long,
  )

  private data class IndexedDirectorySnapshot(
    val entity: DirectoryScanEntity,
    val subdirectories: List<File>,
    val contentChanged: Boolean,
  )

  /**
   * Get all video folders for folder list view
   * Only shows folders with immediate video children (not recursive)
   */
  suspend fun getAllVideoFolders(
    context: Context,
    options: MediaScanOptions = MediaScanOptions(),
    forceFileSystemCheck: Boolean = false,
    onSnapshot: (suspend (List<VideoFolder>) -> Unit)? = null,
  ): List<VideoFolder> =
    withContext(Dispatchers.IO) {
      val now = System.currentTimeMillis()

      // Return cached data if still valid
      folderCache?.let { cached ->
        if (!forceFileSystemCheck && now - cached.createdAt < CACHE_TTL_MS && cached.optionsKey == options.cacheKey) {
          onSnapshot?.invoke(cached.folders)
          return@withContext cached.folders
        }
      }

      // Build fresh data
      val allFolders = mutableMapOf<String, FolderData>()
      val noMediaPathFilter = NoMediaPathFilter(options)
      val publisher = ProgressiveResultsPublisher(onSnapshot) {
        allFolders.values.map { data ->
          VideoFolder(data.path, data.name, data.path, data.videoCount, data.totalSize, data.totalDuration, data.lastModified)
        }
      }

      // Step 1: Scan MediaStore (fast, covers most cases)
      var scanError: Exception? = null
      try {
        scanMediaStoreImmediateChildren(context, allFolders, noMediaPathFilter, publisher)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        scanError = error
      }
      if (options.includeAudio) {
        try {
          scanAudioMediaStoreImmediateChildren(context, allFolders, noMediaPathFilter, options, publisher)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          scanError = error
        }
      }
      publisher.publishIfNeeded(force = true)
      scanFileSystemRoots(context, allFolders, options, noMediaPathFilter, forceFileSystemCheck, publisher)

      // Convert to VideoFolder list
      val result =
        allFolders.values
          .map { data ->
            VideoFolder(
              bucketId = data.path,
              name = data.name,
              path = data.path,
              videoCount = data.videoCount,
              totalSize = data.totalSize,
              totalDuration = data.totalDuration,
              lastModified = data.lastModified,
            )
          }.sortedBy { it.name.lowercase(Locale.getDefault()) }

      // Update cache
      currentCoroutineContext().ensureActive()
      if (scanError == null) folderCache = FolderCache(result, now, options.cacheKey)

      onSnapshot?.invoke(result)
      if (onSnapshot != null) scanError?.let { throw it }
      result
    }

  suspend fun getIndexedNoMediaFolders(
    options: MediaScanOptions,
    dao: DirectoryScanDao,
  ): List<VideoFolder> =
    withContext(Dispatchers.IO) {
      if (!options.includeNoMediaFolders) return@withContext emptyList()
      dao
        .getEntries(options.cacheKey)
        .mapNotNull(::toVideoFolder)
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
  ): Flow<List<VideoFolder>> =
    flow {
      if (!options.includeNoMediaFolders) return@flow

      val scanKey = options.cacheKey
      val cached =
        dao
          .getEntries(scanKey)
          .associateByTo(mutableMapOf()) { scanEntryKey(it.path) }
      val knownRoots = dao.getNoMediaRoots(scanKey).map(::File)
      val roots = linkedMapOf<String, File>()
      knownRoots.forEach { root ->
        val rootPath = normalizeStoragePath(root.absolutePath) ?: root.absolutePath
        if (root.exists() && root.canRead() && isHiddenMediaRoot(root, options)) {
          roots[scanEntryKey(rootPath)] = root
        } else {
          dao.deleteRoot(scanKey, rootPath)
          removeCachedRoot(cached, rootPath)
        }
      }

      discoverNoMediaRootsIncrementally(
        context = context,
        options = options,
        dao = dao,
        forceDiscovery = forceDiscovery,
      ).forEach { root ->
        roots[scanEntryKey(root.absolutePath)] = root
      }

      val staleBefore = System.currentTimeMillis() - HIDDEN_DIRECTORY_RESCAN_INTERVAL_MS
      for (root in roots.values) {
        val rootPath = normalizeStoragePath(root.absolutePath) ?: continue
        val rootKey = scanEntryKey(rootPath)
        val queue = newDirectoryQueue()
        val queued = hashSetOf<String>()
        val indexUpdates = linkedMapOf<String, DirectoryScanEntity>()
        val childrenByParent = indexChildrenByParent(cached.values, rootPath)

        fun enqueue(work: DirectoryWork) {
          val key = scanEntryKey(work.file.absolutePath)
          if (queued.add(key)) queue += work
        }

        fun enqueueNewDirectory(
          directory: File,
          isNoMediaRoot: Boolean,
        ) {
          val path = normalizeStoragePath(directory.absolutePath) ?: return
          val key = scanEntryKey(path)
          if (key !in cached) {
            pendingDirectoryEntry(scanKey, path, rootPath, isNoMediaRoot).also { entry ->
              cached[key] = entry
              indexUpdates[key] = entry
            }
          }
          val entry = cached[key] ?: return
          enqueue(DirectoryWork(directory, rootPath, isNoMediaRoot, entry.lastScanned))
        }

        cached.values
          .asSequence()
          .filter { entry -> scanEntryKey(entry.rootPath) == rootKey }
          .filter { entry -> forceDiscovery || entry.lastScanned <= staleBefore }
          .forEach { entry ->
            enqueue(
              DirectoryWork(
                file = File(entry.path),
                rootPath = rootPath,
                isNoMediaRoot = scanEntryKey(entry.path) == rootKey,
                lastScanned = entry.lastScanned,
              ),
            )
          }

        val existingRoot = cached[rootKey]
        if (existingRoot == null) {
          enqueueNewDirectory(root, isNoMediaRoot = true)
        } else if (!existingRoot.isNoMediaRoot || scanEntryKey(existingRoot.rootPath) != rootKey) {
          existingRoot
            .copy(
              rootPath = rootPath,
              isNoMediaRoot = true,
              lastScanned = 0L,
            ).also { promoted ->
              cached[rootKey] = promoted
              indexUpdates[rootKey] = promoted
              enqueue(DirectoryWork(root, rootPath, isNoMediaRoot = true, lastScanned = 0L))
            }
        }

        val folderUpdates = mutableListOf<VideoFolder>()
        val invalidRootKeys = hashSetOf<String>()
        var processed = 0
        try {
          while (queue.isNotEmpty() && processed < MAX_INDEXED_DIRECTORIES_PER_ROOT) {
            val work = queue.remove()
            val workRootKey = scanEntryKey(work.rootPath)
            if (workRootKey in invalidRootKeys) continue
            val directory = work.file
            val path = normalizeStoragePath(directory.absolutePath) ?: continue
            val pathKey = scanEntryKey(path)
            processed++

            if (!directory.exists() || !directory.canRead() || !directory.isDirectory) {
              deleteIndexedSubtree(dao, scanKey, path, cached, indexUpdates)
              continue
            }

            val files = runCatching { directory.listFiles()?.toList() }.getOrNull() ?: continue
            if (work.isNoMediaRoot && !isHiddenMediaRoot(directory, options, files)) {
              invalidRootKeys += workRootKey
              dao.deleteRoot(scanKey, work.rootPath)
              removeCachedRoot(cached, work.rootPath)
              removeCachedRoot(indexUpdates, work.rootPath)
              continue
            }
            val snapshot =
              inspectIndexedDirectory(
                directory = directory,
                files = files,
                rootPath = rootPath,
                scanKey = scanKey,
                options = options,
                previous = cached[pathKey],
                isNoMediaRoot = work.isNoMediaRoot,
              )
            cached[pathKey] = snapshot.entity
            indexUpdates[pathKey] = snapshot.entity

            val currentChildKeys = snapshot.subdirectories.mapTo(hashSetOf()) { child -> scanEntryKey(child.absolutePath) }
            childrenByParent[pathKey]
              .orEmpty()
              .filterNot(currentChildKeys::contains)
              .forEach { missingChildKey ->
                val missingPath = cached[missingChildKey]?.path ?: return@forEach
                deleteIndexedSubtree(dao, scanKey, missingPath, cached, indexUpdates)
              }
            childrenByParent[pathKey] = currentChildKeys

            if (snapshot.contentChanged) {
              toVideoFolder(snapshot.entity)?.let(folderUpdates::add)
              if (folderUpdates.size >= EMIT_BATCH_SIZE) {
                emit(folderUpdates.toList())
                folderUpdates.clear()
              }
            }

            snapshot.subdirectories.forEach { child ->
              val childKey = scanEntryKey(child.absolutePath)
              if (childKey !in cached) {
                enqueueNewDirectory(child, isNoMediaRoot = false)
                childrenByParent.getOrPut(pathKey, ::hashSetOf) += childKey
              }
            }

            if (indexUpdates.size >= INDEX_WRITE_BATCH_SIZE) {
              flushIndexUpdates(dao, indexUpdates)
            }
          }
        } finally {
          flushIndexUpdates(dao, indexUpdates)
        }

        if (folderUpdates.isNotEmpty()) emit(folderUpdates.toList())

        if (queue.isNotEmpty()) {
          Log.d(TAG, "Paused hidden-folder indexing with ${queue.size} directories queued: $rootPath")
        }
      }
    }

  private fun inspectIndexedDirectory(
    directory: File,
    files: List<File>,
    rootPath: String,
    scanKey: String,
    options: MediaScanOptions,
    previous: DirectoryScanEntity?,
    isNoMediaRoot: Boolean,
  ): IndexedDirectorySnapshot {
    val path = normalizeStoragePath(directory.absolutePath) ?: directory.absolutePath
    val fingerprint = directoryFingerprint(directory, files)
    val subdirectories = files.filter { it.isDirectory && shouldVisitDuringNoMediaScan(it) }
    val unchangedEntity = previous?.takeIf { it.fingerprint == fingerprint }
    val contentChanged = unchangedEntity == null

    val entity =
      if (unchangedEntity != null) {
        unchangedEntity.copy(
          rootPath = rootPath,
          isNoMediaRoot = isNoMediaRoot,
          lastScanned = System.currentTimeMillis(),
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

    return IndexedDirectorySnapshot(entity, subdirectories, contentChanged)
  }

  private suspend fun discoverNoMediaRootsIncrementally(
    context: Context,
    options: MediaScanOptions,
    dao: DirectoryScanDao,
    forceDiscovery: Boolean,
  ): List<File> {
    val discoveryScanKey = options.rootDiscoveryCacheKey
    if (forceDiscovery) dao.deleteScan(discoveryScanKey)

    val cached =
      dao
        .getEntries(discoveryScanKey)
        .associateByTo(mutableMapOf()) { scanEntryKey(it.path) }
    val primary = Environment.getExternalStorageDirectory()
    val searchRoots = linkedSetOf(primary)
    searchRoots += getPrimaryStorageSupplementalScanRoots(primary)
    StorageVolumeUtils.getExternalStorageVolumes(context).mapNotNullTo(searchRoots) { volume ->
      StorageVolumeUtils.getVolumePath(volume)?.let(::File)
    }

    val queue = newDirectoryQueue()
    val queued = hashSetOf<String>()
    val indexUpdates = linkedMapOf<String, DirectoryScanEntity>()
    val staleBefore = System.currentTimeMillis() - ROOT_DISCOVERY_RESCAN_INTERVAL_MS
    val found = linkedMapOf<String, File>()

    cached.values
      .asSequence()
      .filter(DirectoryScanEntity::isNoMediaRoot)
      .map { entry -> File(entry.path) }
      .filter { root -> root.exists() && root.canRead() && isHiddenMediaRoot(root, options) }
      .forEach { root -> found[scanEntryKey(root.absolutePath)] = root }

    fun enqueue(work: DirectoryWork) {
      val key = scanEntryKey(work.file.absolutePath)
      if (queued.add(key)) queue += work
    }

    fun enqueueNewDirectory(
      directory: File,
      rootPath: String,
    ) {
      val path = normalizeStoragePath(directory.absolutePath) ?: return
      val key = scanEntryKey(path)
      if (key !in cached) {
        pendingDirectoryEntry(discoveryScanKey, path, rootPath, isNoMediaRoot = false).also { entry ->
          cached[key] = entry
          indexUpdates[key] = entry
        }
      }
      val entry = cached[key] ?: return
      enqueue(DirectoryWork(directory, rootPath, isNoMediaRoot = false, entry.lastScanned))
    }

    cached.values
      .asSequence()
      .filter { entry -> entry.lastScanned <= staleBefore }
      .forEach { entry ->
        enqueue(DirectoryWork(File(entry.path), entry.rootPath, isNoMediaRoot = false, entry.lastScanned))
      }
    searchRoots.forEach { root ->
      val rootPath = normalizeStoragePath(root.absolutePath) ?: return@forEach
      if (scanEntryKey(rootPath) !in cached) enqueueNewDirectory(root, rootPath)
    }

    var processed = 0
    try {
      while (queue.isNotEmpty() && processed < MAX_DISCOVERY_DIRECTORIES) {
        val work = queue.remove()
        val directory = work.file
        val path = normalizeStoragePath(directory.absolutePath) ?: continue
        val pathKey = scanEntryKey(path)
        processed++

        if (!directory.exists() || !directory.canRead() || !directory.isDirectory) {
          deleteIndexedSubtree(dao, discoveryScanKey, path, cached, indexUpdates)
          continue
        }

        val files = runCatching { directory.listFiles()?.toList() }.getOrNull() ?: continue
        val isHiddenRoot = isHiddenMediaRoot(directory, options, files)
        DirectoryScanEntity(
          scanKey = discoveryScanKey,
          path = path,
          rootPath = work.rootPath,
          fingerprint = directoryFingerprint(directory, files),
          isNoMediaRoot = isHiddenRoot,
          videoCount = 0,
          totalSize = 0L,
          totalDuration = 0L,
          lastModified = 0L,
          hasSubfolders = files.any { file -> file.isDirectory },
          lastScanned = System.currentTimeMillis(),
        ).also { entry ->
          cached[pathKey] = entry
          indexUpdates[pathKey] = entry
        }

        if (isHiddenRoot) {
          found[pathKey] = directory
        } else {
          files
            .asSequence()
            .filter { file -> file.isDirectory && shouldVisitDuringNoMediaScan(file) }
            .forEach { child ->
              if (scanEntryKey(child.absolutePath) !in cached) {
                enqueueNewDirectory(child, work.rootPath)
              }
            }
        }

        if (indexUpdates.size >= INDEX_WRITE_BATCH_SIZE) {
          flushIndexUpdates(dao, indexUpdates)
        }
      }
    } finally {
      flushIndexUpdates(dao, indexUpdates)
    }

    if (queue.isNotEmpty()) {
      Log.d(TAG, "Paused hidden-folder discovery with ${queue.size} directories queued")
    }
    return found.values.toList()
  }

  private fun pendingDirectoryEntry(
    scanKey: String,
    path: String,
    rootPath: String,
    isNoMediaRoot: Boolean,
  ): DirectoryScanEntity =
    DirectoryScanEntity(
      scanKey = scanKey,
      path = path,
      rootPath = rootPath,
      fingerprint = "",
      isNoMediaRoot = isNoMediaRoot,
      videoCount = 0,
      totalSize = 0L,
      totalDuration = 0L,
      lastModified = 0L,
      hasSubfolders = false,
      lastScanned = 0L,
    )

  private fun newDirectoryQueue(): PriorityQueue<DirectoryWork> =
    PriorityQueue(
      compareBy<DirectoryWork> { work -> work.lastScanned }
        .thenBy { work -> relativeScanDepth(work.file, work.rootPath) }
        .thenBy { work -> scanEntryKey(work.file.absolutePath) },
    )

  private fun indexChildrenByParent(
    entries: Collection<DirectoryScanEntity>,
    rootPath: String,
  ): MutableMap<String, MutableSet<String>> {
    val rootKey = scanEntryKey(rootPath)
    val result = mutableMapOf<String, MutableSet<String>>()
    entries.forEach { entry ->
      if (scanEntryKey(entry.rootPath) != rootKey) return@forEach
      val parent = parentStoragePath(entry.path) ?: return@forEach
      result.getOrPut(scanEntryKey(parent), ::hashSetOf) += scanEntryKey(entry.path)
    }
    return result
  }

  private fun relativeScanDepth(
    file: File,
    rootPath: String,
  ): Int {
    val path = scanEntryKey(file.absolutePath)
    val root = scanEntryKey(rootPath)
    if (path == root) return 0
    return path.removePrefix(root).count { it == '/' }
  }

  private suspend fun deleteIndexedSubtree(
    dao: DirectoryScanDao,
    scanKey: String,
    path: String,
    cached: MutableMap<String, DirectoryScanEntity>,
    pendingUpdates: MutableMap<String, DirectoryScanEntity>,
  ) {
    dao.deleteSubtree(scanKey, path, "$path/")
    val key = scanEntryKey(path)
    cached.keys.removeAll { candidate -> candidate == key || candidate.startsWith("$key/") }
    pendingUpdates.keys.removeAll { candidate -> candidate == key || candidate.startsWith("$key/") }
  }

  private suspend fun flushIndexUpdates(
    dao: DirectoryScanDao,
    pendingUpdates: MutableMap<String, DirectoryScanEntity>,
  ) {
    if (pendingUpdates.isEmpty()) return
    val updates = pendingUpdates.values.toList()
    withContext(NonCancellable) { dao.upsert(updates) }
    pendingUpdates.clear()
  }

  private fun removeCachedRoot(
    cached: MutableMap<String, DirectoryScanEntity>,
    rootPath: String,
  ) {
    val rootKey = scanEntryKey(rootPath)
    cached.entries.removeAll { (_, entry) -> scanEntryKey(entry.rootPath) == rootKey }
  }

  private fun scanEntryKey(path: String): String =
    storagePathKey(path) ?: path.replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)

  private fun shouldVisitDuringNoMediaScan(directory: File): Boolean {
    val name = directory.name.lowercase(Locale.ROOT)
    return name !in NO_MEDIA_SCAN_SKIP_FOLDERS && directory.canRead()
  }

  private fun isHiddenMediaRoot(
    directory: File,
    options: MediaScanOptions,
    files: List<File>? = null,
  ): Boolean =
    directory.name.startsWith(".") ||
      if (files == null) {
        options.normalizedHiddenFolderMarkerNames.any { File(directory, it).isFile }
      } else {
        files.any { file -> file.isFile && file.name in options.normalizedHiddenFolderMarkerNames }
      }

  private fun directoryFingerprint(
    directory: File,
    files: List<File>,
  ): String {
    var xor = 0L
    var sum = 0L
    files.forEach { file ->
      var entryHash = 17L
      entryHash = 31 * entryHash + file.name.hashCode()
      entryHash = 31 * entryHash + if (file.isDirectory) 1 else 0
      entryHash = 31 * entryHash + file.length()
      entryHash = 31 * entryHash + file.lastModified()
      val mixed = mixFingerprint(entryHash)
      xor = xor xor mixed
      sum += mixed
    }
    return buildString {
      append(java.lang.Long.toUnsignedString(directory.lastModified(), 16))
      append(':')
      append(files.size.toString(16))
      append(':')
      append(java.lang.Long.toUnsignedString(xor, 16))
      append(':')
      append(java.lang.Long.toUnsignedString(sum, 16))
    }
  }

  private fun mixFingerprint(value: Long): Long {
    var mixed = value
    mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
    mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
    return mixed xor (mixed ushr 31)
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

  private const val EMIT_BATCH_SIZE = 64
  private const val INDEX_WRITE_BATCH_SIZE = 256
  private const val MAX_DISCOVERY_DIRECTORIES = 6_000
  private const val MAX_INDEXED_DIRECTORIES_PER_ROOT = 8_000
  private const val HIDDEN_DIRECTORY_RESCAN_INTERVAL_MS = 15 * 60_000L
  private const val ROOT_DISCOVERY_RESCAN_INTERVAL_MS = 30 * 60_000L
  private val NO_MEDIA_SCAN_SKIP_FOLDERS =
    setOf(
      ".thumbnails",
      "thumbnails",
      "cache",
      ".cache",
      "tmp",
      "temp",
      "lost.dir",
      "system",
      ".trash",
      "trash",
      "recycler",
    )

  /**
   * Scan MediaStore for all videos and build folder map (immediate children only)
   */
  private suspend fun scanMediaStoreImmediateChildren(
    context: Context,
    folders: MutableMap<String, FolderData>,
    noMediaPathFilter: NoMediaPathFilter,
    publisher: ProgressiveResultsPublisher<VideoFolder>,
  ) {
    val projection =
      arrayOf(
        MediaStore.Video.Media.DATA,
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
          val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
          val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
          val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

          // Collect videos by folder
          val videosByFolder = mutableMapOf<String, FolderAggregate>()

          while (cursor.moveToNext()) {
            currentCoroutineContext().ensureActive()
            val videoPath = cursor.getString(dataColumn) ?: continue
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
              VideoInfo(size, duration, dateModified),
            )
            val previous = folders[folderKey]
            folders[folderKey] = FolderData(
              path = aggregate.path, name = leafStorageName(aggregate.path),
              videoCount = (previous?.videoCount ?: 0) + 1,
              totalSize = (previous?.totalSize ?: 0L) + size,
              totalDuration = (previous?.totalDuration ?: 0L) + duration,
              lastModified = maxOf(previous?.lastModified ?: 0L, dateModified),
            )
            publisher.publishIfNeeded()
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

            folders[folderKey] =
              FolderData(
                path = folderPath,
                name = leafStorageName(folderPath),
                videoCount = videos.size,
                totalSize = totalSize,
                totalDuration = totalDuration,
                lastModified = lastModified,
                hasSubfolders = hasSubfolders,
              )
          }
        }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (e: Exception) {
      Log.e(TAG, "MediaStore scan error", e)
      throw e
    }
  }

  private suspend fun scanAudioMediaStoreImmediateChildren(
    context: Context,
    folders: MutableMap<String, FolderData>,
    noMediaPathFilter: NoMediaPathFilter,
    options: MediaScanOptions,
    publisher: ProgressiveResultsPublisher<VideoFolder>,
  ) {
    val projection =
      arrayOf(
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATE_MODIFIED,
      )
    val audioByFolder = mutableMapOf<String, FolderAggregate>()
    val originalFolders = folders.toMap()
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
          val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
          val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
          val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
          while (cursor.moveToNext()) {
            currentCoroutineContext().ensureActive()
            val file = File(cursor.getString(dataColumn) ?: continue)
            if (!file.exists() || noMediaPathFilter.shouldExcludeDirectory(file.parentFile)) continue
            if (!FileTypeUtils.isAudioFile(file)) continue
            val duration = cursor.getLong(durationColumn)
            if (!options.includesAudioDuration(duration)) continue
            val folderPath = normalizeStoragePath(file.parent) ?: continue
            val folderKey = storagePathKey(folderPath) ?: continue
            val aggregate = audioByFolder.getOrPut(folderKey) { FolderAggregate(folderPath) }
            aggregate.videos += VideoInfo(cursor.getLong(sizeColumn), duration, cursor.getLong(dateColumn))
            val previous = folders[folderKey]
            folders[folderKey] = FolderData(
              path = previous?.path ?: folderPath, name = previous?.name ?: leafStorageName(folderPath),
              videoCount = (previous?.videoCount ?: 0) + 1,
              totalSize = (previous?.totalSize ?: 0L) + cursor.getLong(sizeColumn),
              totalDuration = (previous?.totalDuration ?: 0L) + duration,
              lastModified = maxOf(previous?.lastModified ?: 0L, cursor.getLong(dateColumn)),
              hasSubfolders = previous?.hasSubfolders == true,
            )
            publisher.publishIfNeeded()
          }
        }

      for ((folderKey, aggregate) in audioByFolder) {
        val existing = originalFolders[folderKey]
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
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (e: Exception) {
      Log.e(TAG, "MediaStore audio folder scan error", e)
      throw e
    }
  }

  /**
   * Scan external volumes (USB OTG, SD cards) via filesystem
   */
  private suspend fun scanFileSystemRoots(
    context: Context,
    folders: MutableMap<String, FolderData>,
    options: MediaScanOptions,
    noMediaPathFilter: NoMediaPathFilter,
    forceFileSystemCheck: Boolean,
    publisher: ProgressiveResultsPublisher<VideoFolder>,
  ) {
    try {
      val rootsToScan = linkedSetOf<File>()
      val primaryStorageRoot = Environment.getExternalStorageDirectory()

      if (shouldIncludePrimaryStorageInFilesystemFolderScan(options, forceFileSystemCheck)) {
        rootsToScan += primaryStorageRoot
      } else {
        rootsToScan += File(primaryStorageRoot, Environment.DIRECTORY_MOVIES)
      }
      rootsToScan += getPrimaryStorageSupplementalScanRoots(primaryStorageRoot)

      for (volume in StorageVolumeUtils.getExternalStorageVolumes(context)) {
        val volumePath = StorageVolumeUtils.getVolumePath(volume)
        if (volumePath == null) {
          continue
        }

        rootsToScan += File(volumePath)
      }

      val visitedDirectories = mutableSetOf<String>()
      for (root in rootsToScan) {
        currentCoroutineContext().ensureActive()
        if (!root.exists() || !root.canRead() || !root.isDirectory) {
          continue
        }

        scanDirectoryRecursive(
          root,
          folders,
          maxDepth = 20,
          options = options,
          noMediaPathFilter = noMediaPathFilter,
          visitedDirectories = visitedDirectories,
          publisher = publisher,
        )
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Log.e(TAG, "Filesystem folder scan error", e)
    }
  }

  /**
   * Recursively scan directory for videos
   */
  private suspend fun scanDirectoryRecursive(
    directory: File,
    folders: MutableMap<String, FolderData>,
    maxDepth: Int,
    currentDepth: Int = 0,
    options: MediaScanOptions,
    noMediaPathFilter: NoMediaPathFilter,
    visitedDirectories: MutableSet<String>,
    publisher: ProgressiveResultsPublisher<VideoFolder>,
  ) {
    currentCoroutineContext().ensureActive()
    if (currentDepth >= maxDepth) return
    if (!directory.exists() || !directory.canRead() || !directory.isDirectory) return
    if (FileFilterUtils.shouldSkipFolder(directory, options, noMediaPathFilter)) return

    try {
      if (!visitedDirectories.add(directory.canonicalPath)) return
      val files = directory.listFiles() ?: return

      val mediaFiles = mutableListOf<File>()
      val subdirectories = mutableListOf<File>()
      val currentPath = normalizeStoragePath(directory.absolutePath) ?: return
      val currentKey = storagePathKey(currentPath) ?: return
      val discoveredHere = currentKey !in folders
      var discoveredSize = 0L
      var discoveredDuration = 0L
      var discoveredModified = 0L

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
                  if (discoveredHere) {
                    discoveredSize += file.length()
                    discoveredDuration += duration
                    discoveredModified = maxOf(discoveredModified, file.lastModified() / 1000)
                    folders[currentKey] = FolderData(currentPath, leafStorageName(currentPath), mediaFiles.size,
                      discoveredSize, discoveredDuration, discoveredModified, subdirectories.isNotEmpty())
                    publisher.publishIfNeeded()
                  }
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

          folders[folderKey] =
            FolderData(
              path = folderPath,
              name = leafStorageName(folderPath),
              videoCount = mediaFiles.size,
              totalSize = totalSize,
              totalDuration = totalDuration,
              lastModified = lastModified / 1000,
              hasSubfolders = subdirectories.isNotEmpty(),
            )
        } else {
          folders[folderKey]?.let { existing ->
            val preferredPath = choosePreferredStoragePath(existing.path, folderPath)
            folders[folderKey] =
              existing.copy(
                path = preferredPath,
                name = leafStorageName(preferredPath),
                hasSubfolders = existing.hasSubfolders || subdirectories.isNotEmpty(),
              )
          }
        }
      }
      publisher.publishIfNeeded()

      // Recurse into subdirectories
      for (subdir in subdirectories) {
        scanDirectoryRecursive(subdir, folders, maxDepth, currentDepth + 1, options, noMediaPathFilter, visitedDirectories, publisher)
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Log.w(TAG, "Error scanning: ${directory.absolutePath}", e)
    }
  }
}
