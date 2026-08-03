/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.entities

import androidx.room.Entity
import androidx.room.Index

@Entity(
  tableName = "directory_scan_index",
  primaryKeys = ["scanKey", "path"],
  indices = [
    Index(value = ["scanKey", "rootPath"]),
    Index(value = ["scanKey", "isNoMediaRoot"]),
  ],
)
data class DirectoryScanEntity(
  val scanKey: String,
  val path: String,
  val rootPath: String,
  val fingerprint: String,
  val isNoMediaRoot: Boolean,
  val videoCount: Int,
  val totalSize: Long,
  val totalDuration: Long,
  val lastModified: Long,
  val hasSubfolders: Boolean,
  val lastScanned: Long,
)
