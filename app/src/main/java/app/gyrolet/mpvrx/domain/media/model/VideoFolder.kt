/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
