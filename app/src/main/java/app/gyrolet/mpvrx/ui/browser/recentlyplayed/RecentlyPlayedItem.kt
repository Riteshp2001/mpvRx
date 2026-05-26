package app.gyrolet.mpvrx.ui.browser.recentlyplayed

import androidx.compose.runtime.Immutable
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.domain.media.model.Video

@Immutable
sealed class RecentlyPlayedItem {
  abstract val timestamp: Long

  @Immutable
  data class VideoItem(
    val video: Video,
    override val timestamp: Long,
  ) : RecentlyPlayedItem()

  @Immutable
  data class PlaylistItem(
    val playlist: PlaylistEntity,
    val videoCount: Int,
    val mostRecentVideoPath: String,
    override val timestamp: Long,
  ) : RecentlyPlayedItem()
}

