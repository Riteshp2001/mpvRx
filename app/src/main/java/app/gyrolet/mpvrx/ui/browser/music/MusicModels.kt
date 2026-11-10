/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.music

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import app.gyrolet.mpvrx.R

@Immutable
enum class MusicTab(@StringRes val titleRes: Int) {
  SONGS(R.string.ui_songs),
  ALBUMS(R.string.ui_albums),
  ARTISTS(R.string.ui_artists),
  PLAYLISTS(R.string.ui_playlists),
  FOLDERS(R.string.search_category_folders);

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
  val size: Long = 0L,
  val hasAlbumTag: Boolean = true,
  val dateModified: Long = dateAdded,
  val mimeType: String? = null,
) {
  val albumKey: Long?
    get() = if (hasAlbumTag) albumId.takeIf { it > 0 } ?: album.hashCode().toLong() else null
}

internal fun Iterable<MusicSong>.sortedByAlbumTrack(): List<MusicSong> =
  sortedWith(
    compareBy<MusicSong> { it.trackNumber <= 0 }
      .thenBy { song -> if (song.trackNumber >= 1000) song.trackNumber / 1000 else 1 }
      .thenBy { song -> song.trackNumber.takeIf { it > 0 }?.rem(1000)?.takeIf { it > 0 } ?: Int.MAX_VALUE }
      .thenBy { it.title.lowercase() }
      .thenBy { it.id },
  )

@Immutable
data class MusicAlbum(
  val id: Long,
  val title: String,
  val artist: String,
  val songCount: Int,
  val year: Int = 0,
  val albumArtUri: Uri? = null,
  val artworkSong: MusicSong? = null,
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

internal val MusicTab.availableSortFields: List<MusicSortField>
  get() = when (this) {
    MusicTab.SONGS -> listOf(
      MusicSortField.TITLE, MusicSortField.ARTIST, MusicSortField.ALBUM,
      MusicSortField.DURATION, MusicSortField.DATE_ADDED, MusicSortField.YEAR,
    )
    MusicTab.ALBUMS -> listOf(
      MusicSortField.TITLE, MusicSortField.ARTIST, MusicSortField.TRACK_COUNT, MusicSortField.YEAR,
    )
    MusicTab.ARTISTS -> listOf(MusicSortField.TITLE, MusicSortField.TRACK_COUNT)
    MusicTab.PLAYLISTS, MusicTab.FOLDERS -> listOf(MusicSortField.TITLE)
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
