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
 * A captured frame, decoupled from the Room row.
 *
 * [imageUri] and [imagePath] are two views of the same gallery file: the MediaStore URI on API 29+,
 * an absolute path below that. Only one is populated, and readers should prefer the URI. [videoUri]
 * holds whatever mpv reported for the source — a bare path for local files, a scheme-qualified URI
 * otherwise — with [videoPath] filled in for the local case.
 */
data class FrameCapture(
  val id: Long,
  val imageUri: String?,
  val imagePath: String?,
  val videoUri: String,
  val videoPath: String?,
  val videoTitle: String,
  val positionMs: Long,
  val capturedAt: Long,
  /** The folder this snapshot was filed into, or null for the root of the snapshot library. */
  val folderId: Long? = null,
  /**
   * The captured image's own pixel size. Null for a row written before the mosaic layout existed;
   * the mosaic falls back to a square tile for those until the backfill reads the real size.
   *
   * A row whose image can no longer be read holds 0 × 0 rather than null — see
   * [ImageDimensions.UNREADABLE]. Readers treat any non-positive size as unknown either way.
   */
  val imageWidth: Int? = null,
  val imageHeight: Int? = null,
) {
  /**
   * Whether this snapshot's image size has been settled, including where the answer was "cannot be
   * read". The mosaic's backfill only reads rows that are still open, so this is what keeps a
   * snapshot whose gallery file was deleted from being probed again on every visit.
   */
  val hasResolvedImageSize: Boolean
    get() = imageWidth != null && imageHeight != null

  /** Position formatted as `H:MM:SS` (or `M:SS` under an hour) for the grid caption. */
  val formattedPosition: String
    get() = formatPosition(positionMs)
}

private fun formatPosition(positionMs: Long): String {
  val totalSeconds = (positionMs / 1000L).coerceAtLeast(0L)
  val hours = totalSeconds / 3600L
  val minutes = (totalSeconds % 3600L) / 60L
  val seconds = totalSeconds % 60L
  return if (hours > 0L) {
    String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
  } else {
    String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
  }
}
