/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.music

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
enum class MusicTab(val title: String) {
  SONGS("Songs"),
  ALBUMS("Albums"),
  ARTISTS("Artists"),
  PLAYLISTS("Playlists");

  companion object {
    val defaultTabs = entries.toList()
  }
}

@Immutable
data class MusicSong(
  val id: Long,
  val title: String,
  val artist: String,
  val album: String,
  val albumId: Long,
  val durationMs: Long,
  val path: String,
  val uri: Uri,
  val dateAdded: Long,
  val trackNumber: Int = 0,
  val year: Int = 0,
  val albumArtUri: Uri? = null,
  val size: Long = 0L
)

@Immutable
data class MusicAlbum(
  val id: Long,
  val title: String,
  val artist: String,
  val songCount: Int,
  val year: Int = 0,
  val albumArtUri: Uri? = null
)

@Immutable
data class MusicArtist(
  val id: Long,
  val name: String,
  val songCount: Int,
  val albumCount: Int = 0
)

@Immutable
enum class MusicSortField(val displayName: String) {
  TITLE("Title"),
  ARTIST("Artist"),
  ALBUM("Album"),
  DURATION("Duration"),
  DATE_ADDED("Date Added"),
  TRACK_COUNT("Track Count"),
  YEAR("Year")
}

@Immutable
enum class MusicSortOrder {
  ASCENDING,
  DESCENDING
}

@Immutable
enum class MusicViewMode {
  LIST,
  GRID
}
