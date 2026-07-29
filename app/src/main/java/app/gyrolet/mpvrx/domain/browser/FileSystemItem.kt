/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
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
