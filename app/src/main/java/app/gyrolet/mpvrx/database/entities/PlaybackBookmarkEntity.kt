package app.gyrolet.mpvrx.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "playback_bookmarks",
  foreignKeys = [
    ForeignKey(
      entity = AudiobookTrackEntity::class,
      parentColumns = ["id"],
      childColumns = ["bookTrackId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index(value = ["mediaId"]), Index(value = ["bookTrackId"])],
)
data class PlaybackBookmarkEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val mediaId: String,
  val positionMs: Long,
  val title: String,
  val createdAt: Long = System.currentTimeMillis(),
  val bookTrackId: Long? = null,
) {
  companion object {
    fun audiobookMediaId(bookId: Long): String = "audiobook:$bookId"
  }
}
