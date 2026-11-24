/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.gyrolet.mpvrx.data.artwork.HighQualityArtworkRepository
import app.gyrolet.mpvrx.data.artwork.MotionArtworkQuery
import app.gyrolet.mpvrx.utils.device.DevicePerformance
import org.koin.compose.koinInject

/**
 * The catalogue's highest-resolution cover for [query], from the disk cache once fetched, or
 * null while it loads, when [query] is null, or when no catalogue publishes one.
 */
@Composable
internal fun rememberHighQualityArtwork(query: MotionArtworkQuery?): Bitmap? {
  val repository = koinInject<HighQualityArtworkRepository>()
  // Large enough to stay crisp on a full-width immersive cover; low-end devices keep memory down.
  val maxDimension = remember { if (DevicePerformance.isLowEnd) 1280 else 2048 }
  // Keyed on the song: the album tag landing a beat later must not blank a loaded cover.
  var artwork by remember(query?.title, query?.artist) {
    mutableStateOf(query?.let(repository::fromMemory))
  }
  LaunchedEffect(query, repository) {
    if (query == null) return@LaunchedEffect
    repository.load(query, maxDimension)?.let { artwork = it }
  }
  return artwork.takeIf { query != null }
}
