/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.framecapture

/**
 * An in-app folder for snapshots, decoupled from the Room row.
 *
 * There is no count here on purpose: how many snapshots a folder holds is derived from the capture
 * flow in the UI layer, so that the number on a folder card and the number of rows in the folder can
 * never disagree.
 */
data class SnapshotFolder(
  val id: Long,
  val name: String,
  val createdAt: Long,
)

/** The outcome of creating or renaming a folder. The UI turns each case into its own message. */
sealed interface FolderWriteResult {
  data class Ok(
    val id: Long,
  ) : FolderWriteResult

  data object BlankName : FolderWriteResult

  data object DuplicateName : FolderWriteResult
}
