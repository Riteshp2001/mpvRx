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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.data.artwork.HighQualityArtworkRepository
import app.gyrolet.mpvrx.data.artwork.MotionArtworkQuery
import app.gyrolet.mpvrx.ui.player.PlaybackPhase
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.utils.device.DevicePerformance
import org.koin.compose.koinInject

@Composable
internal fun rememberArtworkQuery(
  enabled: Boolean,
  phase: PlaybackPhase,
  generation: Long,
  itemTitle: String?,
  mediaTitle: String?,
  artistFallback: String?,
): MotionArtworkQuery? {
  if (!enabled || (phase != PlaybackPhase.READY && phase != PlaybackPhase.BACKGROUND)) return null
  val nativeMetadata by PlaybackSession.propNode["metadata"].collectAsStateWithLifecycle()
  return remember(nativeMetadata, generation, itemTitle, mediaTitle, artistFallback) {
    PlaybackSession.readLoadedMedia(generation) { reader ->
      fun known(value: String?): String? =
        value?.trim()?.takeUnless {
          it.isBlank() ||
            it.equals("<unknown>", ignoreCase = true) ||
            it.equals("Unknown Artist", ignoreCase = true) ||
            it.equals("Unknown Album", ignoreCase = true)
        }

      fun tag(vararg keys: String): String? =
        keys.asSequence().mapNotNull { known(reader.string("metadata/by-key/$it")) }.firstOrNull()

      val artist = tag("artist", "Artist", "ARTIST", "album_artist", "ALBUMARTIST") ?: known(artistFallback)
      val title =
        (tag("title", "Title", "TITLE") ?: known(itemTitle) ?: known(mediaTitle))
          ?.let { cleanSongTitle(it, artist) }
          ?.trim()
      if (artist != null && !title.isNullOrBlank()) {
        MotionArtworkQuery(title = title, artist = artist, album = tag("album", "Album", "ALBUM"))
      } else {
        null
      }
    }
  }
}

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
