/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.imageviewer

import android.graphics.Bitmap

/**
 * UI state for a single image page inside the viewer.
 *
 * @property bitmap The full-resolution bitmap, null until loaded.
 * @property thumbnail A low-res thumbnail used as placeholder while loading.
 * @property isLoading True when a full-image load is in flight.
 * @property error Non-null if the last load attempt failed.
 */
data class ImageViewerItemState(
  val bitmap: Bitmap? = null,
  val thumbnail: Bitmap? = null,
  val isLoading: Boolean = false,
  val error: Throwable? = null,
)
