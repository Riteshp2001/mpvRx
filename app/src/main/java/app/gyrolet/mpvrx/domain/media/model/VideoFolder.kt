/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.domain.media.model

import androidx.compose.runtime.Immutable

@Immutable
data class VideoFolder(
  val bucketId: String,
  val name: String,
  val path: String,
  val videoCount: Int,
  val totalSize: Long = 0L,
  val totalDuration: Long = 0L, // in milliseconds
  val lastModified: Long = 0L,
)

