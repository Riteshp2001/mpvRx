/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.framecapture

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.gyrolet.mpvrx.database.MpvRxDatabase
import app.gyrolet.mpvrx.database.dao.FrameCaptureDao
import app.gyrolet.mpvrx.database.dao.SnapshotFolderDao
import app.gyrolet.mpvrx.database.entities.FrameCaptureEntity
import app.gyrolet.mpvrx.database.entities.SnapshotFolderEntity
import app.gyrolet.mpvrx.domain.network.NetworkPlaybackUri
import app.gyrolet.mpvrx.repository.NetworkProbeResult
import app.gyrolet.mpvrx.repository.NetworkRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounded so a route that silently drops packets cannot wait out the HTTP client's own connect
 * timeout before the viewer hears anything. A premature "unreachable" is recoverable — the user can
 * tap Jump to video again.
 */
private const val NETWORK_PROBE_TIMEOUT_MS = 3_000L

class FrameCaptureRepositoryImpl(
  private val context: Context,
  private val dao: FrameCaptureDao,
  private val folderDao: SnapshotFolderDao,
  private val database: MpvRxDatabase,
  private val networkRepository: NetworkRepository,
) : FrameCaptureRepository {

  override fun observeAll(): Flow<List<FrameCapture>> =
    dao.observeAll().map { rows -> rows.map(FrameCaptureEntity::toDomain) }

  override fun observeFolders(): Flow<List<SnapshotFolder>> =
    folderDao.observeAll().map { rows -> rows.map(SnapshotFolderEntity::toDomain) }

  override suspend fun createFolder(name: String): FolderWriteResult =
    withContext(Dispatchers.IO) {
      val trimmed = name.trim()
      if (trimmed.isEmpty()) return@withContext FolderWriteResult.BlankName
      // The check is case-insensitive while the unique index is not, so it is the stricter of the
      // two; the index only ever catches an exact duplicate that slipped past a race.
      if (folderDao.findByName(trimmed) != null) return@withContext FolderWriteResult.DuplicateName
      FolderWriteResult.Ok(folderDao.insert(SnapshotFolderEntity(name = trimmed)))
    }

  override suspend fun renameFolder(
    id: Long,
    name: String,
  ): FolderWriteResult =
    withContext(Dispatchers.IO) {
      val trimmed = name.trim()
      if (trimmed.isEmpty()) return@withContext FolderWriteResult.BlankName
      val clash = folderDao.findByName(trimmed)
      if (clash != null && clash.id != id) return@withContext FolderWriteResult.DuplicateName
      folderDao.updateName(id, trimmed)
      FolderWriteResult.Ok(id)
    }

  override suspend fun deleteFolderWithCaptures(id: Long) =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        dao.deleteByFolder(id)
        folderDao.delete(id)
      }
    }

  override suspend fun moveCaptures(
    ids: Collection<Long>,
    folderId: Long?,
  ) = withContext(Dispatchers.IO) {
    if (ids.isEmpty()) return@withContext
    dao.updateFolder(ids.toList(), folderId)
  }

  override suspend fun record(capture: FrameCapture): Long =
    withContext(Dispatchers.IO) { dao.insert(capture.toEntity()) }

  override suspend fun recordImageSizes(sizes: Map<Long, ImageDimensions>) =
    withContext(Dispatchers.IO) {
      if (sizes.isEmpty()) return@withContext
      database.withTransaction {
        sizes.forEach { (id, size) -> dao.updateImageSize(id, size.width, size.height) }
      }
    }

  override suspend fun delete(id: Long) =
    withContext(Dispatchers.IO) { dao.delete(id) }

  override suspend fun deleteAll(ids: Collection<Long>) =
    withContext(Dispatchers.IO) { dao.deleteByIds(ids.toList()) }

  override suspend fun videoSource(capture: FrameCapture): VideoSource =
    withContext(Dispatchers.IO) {
      val reference = NetworkPlaybackUri.parse(capture.videoUri)
        ?: return@withContext VideoSource(
          protocolLabel = null,
          connectionName = null,
          path = capture.videoPath ?: capture.videoUri,
        )
      // A connection deleted since the capture leaves its name unknown rather than failing the row.
      val connection = networkRepository.getConnectionById(reference.connectionId)
      VideoSource(
        protocolLabel = connection?.protocol?.displayName,
        connectionName = connection?.name,
        path = reference.path.value,
      )
    }

  override suspend fun isImageAvailable(capture: FrameCapture): Boolean =
    withContext(Dispatchers.IO) {
      val uri = capture.imageUri
      if (uri != null) {
        // A MediaStore row survives the file being deleted from disk, so ask the resolver.
        return@withContext runCatching {
          context.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false
        }.getOrDefault(false)
      }
      val path = capture.imagePath ?: return@withContext false
      File(path).exists()
    }

  /**
   * A bare path and `file://` both name a local file and get probed on the filesystem: mpv reports
   * local media as a bare path, so a null scheme means local rather than remote, and `file://` still
   * needs its path extracted before the check. A detached `fd://` or `memory://` descriptor is
   * one-shot and long dead by the time a snapshot is reopened, so those report unavailable instead
   * of being waved through as remote.
   */
  override suspend fun videoAvailability(capture: FrameCapture): VideoAvailability =
    withContext(Dispatchers.IO) {
      val uri = Uri.parse(capture.videoUri)
      when (uri.scheme?.lowercase()) {
        // Bare path: mpv hands local media over this way, so videoUri is itself the filesystem path.
        null -> File(capture.videoPath ?: capture.videoUri).exists().toAvailability()
        "file" -> (uri.path?.let { File(it).exists() } ?: false).toAvailability()
        "content" ->
          runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
          }.getOrDefault(false).toAvailability()
        NetworkPlaybackUri.SCHEME -> networkShareAvailability(capture.videoUri)
        // PlayerViewModel filters these out of its `path` property for the same reason: a detached
        // descriptor cannot be replayed.
        "fd",
        "memory",
        -> VideoAvailability.UNAVAILABLE
        // http, https and torrent streams: available until actually asked to play.
        else -> VideoAvailability.AVAILABLE
      }
    }

  /**
   * The Network tab owns the connection lifecycle, so its live state has the final say on whether the
   * user can play at all: an unconnected share gets a prompt they can act on, rather than a dial that
   * opens a session they cannot see or manage. Only an already-open share is worth probing — and it
   * still is, because an open session can go stale.
   */
  private suspend fun networkShareAvailability(videoUri: String): VideoAvailability {
    val reference = NetworkPlaybackUri.parse(videoUri) ?: return VideoAvailability.UNAVAILABLE
    // A deleted connection reads the same as a dead one — either way there is nothing to open.
    val connection = networkRepository.getConnectionById(reference.connectionId) ?: return VideoAvailability.UNAVAILABLE
    if (!networkRepository.isConnected(connection.id)) return VideoAvailability.NOT_CONNECTED

    val probed = withTimeoutOrNull(NETWORK_PROBE_TIMEOUT_MS) { networkRepository.probe(connection) }
    return when (probed) {
      NetworkProbeResult.REACHABLE -> VideoAvailability.AVAILABLE
      // Open but not answering, or answering with a rejection: either way the next thing to look at
      // is the connection, not the record.
      NetworkProbeResult.UNREACHABLE,
      NetworkProbeResult.AUTHENTICATION_FAILED,
      null,
      -> VideoAvailability.UNREACHABLE
    }
  }
}

private fun Boolean.toAvailability(): VideoAvailability =
  if (this) VideoAvailability.AVAILABLE else VideoAvailability.UNAVAILABLE

private fun FrameCaptureEntity.toDomain(): FrameCapture =
  FrameCapture(
    id = id,
    imageUri = imageUri,
    imagePath = imagePath,
    videoUri = videoUri,
    videoPath = videoPath,
    videoTitle = videoTitle,
    positionMs = positionMs,
    capturedAt = capturedAt,
    folderId = folderId,
    imageWidth = imageWidth,
    imageHeight = imageHeight,
  )

private fun FrameCapture.toEntity(): FrameCaptureEntity =
  FrameCaptureEntity(
    id = id,
    imageUri = imageUri,
    imagePath = imagePath,
    videoUri = videoUri,
    videoPath = videoPath,
    videoTitle = videoTitle,
    positionMs = positionMs,
    capturedAt = if (capturedAt > 0L) capturedAt else System.currentTimeMillis(),
    folderId = folderId,
    imageWidth = imageWidth,
    imageHeight = imageHeight,
  )

private fun SnapshotFolderEntity.toDomain(): SnapshotFolder =
  SnapshotFolder(id = id, name = name, createdAt = createdAt)
