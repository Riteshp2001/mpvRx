/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.thumbfast

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable

/**
 * State representation of the floating ThumbFast seek preview window.
 */
@Immutable
data class ThumbFastPreviewState(
  val isVisible: Boolean = false,
  val positionSeconds: Float = 0f,
  val durationSeconds: Float = 0f,
  val formattedTime: String = "",
  val relativeDeltaText: String? = null,
  val chapterTitle: String? = null,
  val bitmap: Bitmap? = null,
  val isLoading: Boolean = false,
  val normalizedXFraction: Float = 0f,
)
