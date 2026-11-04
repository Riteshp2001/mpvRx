package app.gyrolet.mpvrx.ui.browser.playlist

import android.net.Uri
import app.gyrolet.mpvrx.domain.archive.ZipArchiveMedia
import app.gyrolet.mpvrx.preferences.PlaylistSortType
import app.gyrolet.mpvrx.preferences.SortOrder
import app.gyrolet.mpvrx.utils.sort.SortUtils
import java.util.Locale

internal fun playlistGridColumnLimit(availableWidthDp: Int, isLibrary: Boolean = false): Int {
  val spacing = if (isLibrary) 12 else 8
  val minimumWidth = if (isLibrary) 156 else 160
  return ((availableWidthDp - 16 + spacing) / (minimumWidth + spacing)).coerceIn(1, 8)
}

@androidx.annotation.StringRes
internal fun playlistSortLabelRes(type: PlaylistSortType): Int =
  when (type) {
    PlaylistSortType.Original -> app.gyrolet.mpvrx.R.string.playlist_sort_original
    PlaylistSortType.Name -> app.gyrolet.mpvrx.R.string.ui_name
    PlaylistSortType.Location -> app.gyrolet.mpvrx.R.string.playlist_location
    PlaylistSortType.DateAdded -> app.gyrolet.mpvrx.R.string.playlist_date_added
    PlaylistSortType.LastPlayed -> app.gyrolet.mpvrx.R.string.video_swipe_last_played
    PlaylistSortType.ItemCount -> app.gyrolet.mpvrx.R.string.playlist_item_count
    PlaylistSortType.Category -> app.gyrolet.mpvrx.R.string.playlist_category
  }

internal fun playlistSourceLocation(source: String?): String {
  if (source.isNullOrBlank()) return ""
  ZipArchiveMedia.displayPath(source)?.let { return playlistSourceLocation(it) }
  val uri = Uri.parse(source)
  return when (uri.scheme?.lowercase(Locale.ROOT)) {
    null -> source
    "file" -> uri.path.orEmpty()
    "content" -> uri.lastPathSegment?.substringAfter(':').orEmpty()
    "mpvrx-network" -> ""
    else -> uri.host.orEmpty()
  }
}

internal fun PlaylistVideoItem.displayLocation(): String =
  listOfNotNull(connectionName?.takeIf(String::isNotBlank), sourcePath?.takeIf(String::isNotBlank))
    .joinToString(" / ")
    .ifBlank { playlistSourceLocation(video.path) }

internal fun sortPlaylists(
  items: List<PlaylistWithCount>,
  type: PlaylistSortType,
  order: SortOrder,
): List<PlaylistWithCount> {
  val naturalOrder = SortUtils.NaturalOrderComparator.DEFAULT
  val comparator =
    when (type) {
      PlaylistSortType.Name -> compareBy<PlaylistWithCount, String>(naturalOrder) { it.playlist.name }
      PlaylistSortType.Location ->
        compareBy<PlaylistWithCount, String>(naturalOrder) {
          playlistSourceLocation(it.playlist.m3uSourceUrl ?: it.playlist.xtreamServerUrl)
        }
      PlaylistSortType.DateAdded -> compareBy { item: PlaylistWithCount -> item.playlist.createdAt }
      PlaylistSortType.ItemCount -> compareBy { item: PlaylistWithCount -> item.itemCount }
      else -> return if (order.isAscending) items else items.asReversed()
    }
  val ordered = if (order.isAscending) comparator else comparator.reversed()
  return items.sortedWith(ordered.thenBy { it.playlist.id })
}

internal fun sortPlaylistItems(
  items: List<PlaylistVideoItem>,
  type: PlaylistSortType,
  order: SortOrder,
): List<PlaylistVideoItem> {
  val naturalOrder = SortUtils.NaturalOrderComparator.DEFAULT
  val comparator =
    when (type) {
      PlaylistSortType.Name -> compareBy<PlaylistVideoItem, String>(naturalOrder) { it.video.displayName }
      PlaylistSortType.Location -> compareBy<PlaylistVideoItem, String>(naturalOrder) { it.displayLocation() }
      PlaylistSortType.DateAdded -> compareBy { item: PlaylistVideoItem -> item.playlistItem.addedAt }
      PlaylistSortType.LastPlayed -> compareBy { item: PlaylistVideoItem -> item.playlistItem.lastPlayedAt }
      PlaylistSortType.Category ->
        compareBy<PlaylistVideoItem, String>(naturalOrder) { it.playlistItem.groupTitle.orEmpty() }
          .thenBy(naturalOrder) { it.video.displayName }
      else -> return if (order.isAscending) items else items.asReversed()
    }
  val ordered = if (order.isAscending) comparator else comparator.reversed()
  return items.sortedWith(ordered.thenBy { it.playlistItem.position }.thenBy { it.playlistItem.id })
}