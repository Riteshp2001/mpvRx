/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.framecapture

import kotlinx.coroutines.flow.Flow

/**
 * Why a snapshot's source video can or cannot be opened right now.
 *
 * Kept apart because each one needs a different answer from the user: connect the share, check the
 * connection, or drop a record that has nothing left to open.
 */
enum class VideoAvailability {
  /** Playable now. */
  AVAILABLE,

  /** A saved network share that is not open — the user has to connect it before playback. */
  NOT_CONNECTED,

  /** The share is open but failed its probe — the connection is worth looking at, not the record. */
  UNREACHABLE,

  /** Nothing to open: deleted, moved, a detached descriptor, or a connection that no longer exists. */
  UNAVAILABLE,
}

/** Where a snapshot's video came from, for the viewer's caption. */
data class VideoSource(
  /** Display name of the network protocol, or null when the source is a local file. */
  val protocolLabel: String?,
  /** Saved connection name, when the source is a network share. */
  val connectionName: String?,
  /** Path below the connection root, or the local file path. */
  val path: String,
)

interface FrameCaptureRepository {
  /** Newest first. */
  fun observeAll(): Flow<List<FrameCapture>>

  /** Every folder, ordered by name. Counts are derived by the caller, not stored. */
  fun observeFolders(): Flow<List<SnapshotFolder>>

  /** Returns [FolderWriteResult.BlankName] or [FolderWriteResult.DuplicateName] instead of throwing. */
  suspend fun createFolder(name: String): FolderWriteResult

  suspend fun renameFolder(
    id: Long,
    name: String,
  ): FolderWriteResult

  /** One transaction: the folder's records go first, then the folder. The gallery files stay. */
  suspend fun deleteFolderWithCaptures(id: Long)

  /** `folderId` null moves the snapshots back to the root, so "move out" needs no separate call. */
  suspend fun moveCaptures(
    ids: Collection<Long>,
    folderId: Long?,
  )

  suspend fun record(capture: FrameCapture): Long

  /**
   * Stores the size of each snapshot's image, for rows that predate the mosaic layout. Rows recorded
   * from a fresh capture already carry it, and a row whose image cannot be read is stored as
   * [ImageDimensions.UNREADABLE] so it is not asked for again.
   *
   * Batched into one transaction so a folder's worth of backfill invalidates the capture query once
   * rather than once per row.
   */
  suspend fun recordImageSizes(sizes: Map<Long, ImageDimensions>)

  suspend fun delete(id: Long)

  /** Removes several records at once; the grid's multi-select deletes in one pass. */
  suspend fun deleteAll(ids: Collection<Long>)

  /**
   * Whether the snapshot image can still be read.
   *
   * This opens the content stream — a MediaStore row outlives the file it points at, so asking the
   * resolver is the only honest test. It never decodes the image, which keeps the cost to one IPC
   * rather than a full bitmap read.
   */
  suspend fun isImageAvailable(capture: FrameCapture): Boolean

  /**
   * Where the video came from, resolved from the saved connection rather than stored on the row: a
   * connection can be renamed, and the record should follow it.
   */
  suspend fun videoSource(capture: FrameCapture): VideoSource

  /**
   * Whether the source video can still be reached, asked at the moment the user wants to open it.
   *
   * Local media is probed on the filesystem and `content://` through the resolver. A saved network
   * share is checked against the live connection first and only dialled as a fallback, per the
   * project's lazy-availability rule: the connection is *tested* when the user asks, but the test
   * never opens a session on the user's behalf. Bare http(s) and torrent streams stay assumed
   * available; nothing cheap can decide those.
   *
   * Bounded, so a black-holed route cannot stall the caller for the transport's own timeout.
   */
  suspend fun videoAvailability(capture: FrameCapture): VideoAvailability
}
