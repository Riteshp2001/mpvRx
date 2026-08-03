/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.browser

import androidx.compose.runtime.Immutable
import app.gyrolet.mpvrx.domain.media.model.Video

/**
 * Represents an item in the filesystem browser (either a folder or a video file)
 */
@Immutable
sealed class FileSystemItem {
  abstract val name: String
  abstract val path: String
  abstract val lastModified: Long

  @Immutable
  data class Folder(
    override val name: String,
    override val path: String,
    override val lastModified: Long,
    val videoCount: Int = 0,
    val totalSize: Long = 0L,
    val totalDuration: Long = 0L,
    val hasSubfolders: Boolean = false,
    val newCount: Int = 0,
  ) : FileSystemItem()

  @Immutable
  data class VideoFile(
    override val name: String,
    override val path: String,
    override val lastModified: Long,
    val video: Video,
  ) : FileSystemItem()
}

/**
 * Represents a path component in the breadcrumb navigation
 */
@Immutable
data class PathComponent(
  val name: String,
  val fullPath: String,
)
