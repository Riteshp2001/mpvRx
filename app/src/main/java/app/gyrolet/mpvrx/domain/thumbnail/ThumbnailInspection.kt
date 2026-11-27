// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.domain.thumbnail

import android.graphics.Bitmap
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.preferences.ThumbnailGenerator
import app.gyrolet.mpvrx.preferences.ThumbnailMode

/** Read-only snapshot of the selected thumbnail cache; inspecting never starts frame extraction. */
internal data class ThumbnailInspection(
  val cacheVideo: Video,
  val preview: Bitmap?,
  val generator: ThumbnailGenerator,
  val mode: ThumbnailMode,
  val framePositionPercent: Float,
  val width: Int?,
  val height: Int?,
  val mimeType: String?,
  val filePath: String?,
  val fileSizeBytes: Long?,
  val modifiedAtMillis: Long?,
  val cachedInMemory: Boolean,
  val jpegEncoderQuality: Int,
)
