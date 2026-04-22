package app.gyrolet.mpvrx.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.gyrolet.mpvrx.database.converters.NetworkProtocolConverter
import app.gyrolet.mpvrx.database.dao.NetworkConnectionDao
import app.gyrolet.mpvrx.database.dao.PlaybackStateDao
import app.gyrolet.mpvrx.database.dao.PlaylistDao
import app.gyrolet.mpvrx.database.dao.RecentlyPlayedDao
import app.gyrolet.mpvrx.database.dao.VideoMetadataDao
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.database.entities.PlaylistItemEntity
import app.gyrolet.mpvrx.database.entities.RecentlyPlayedEntity
import app.gyrolet.mpvrx.database.entities.VideoMetadataEntity
import app.gyrolet.mpvrx.domain.network.NetworkConnection

@Database(
  entities = [
    PlaybackStateEntity::class,
    RecentlyPlayedEntity::class,
    VideoMetadataEntity::class,
    NetworkConnection::class,
    PlaylistEntity::class,
    PlaylistItemEntity::class,
  ],
  version = 9,
  exportSchema = true,
)
@TypeConverters(NetworkProtocolConverter::class)
abstract class MpvRxDatabase : RoomDatabase() {
  abstract fun videoDataDao(): PlaybackStateDao

  abstract fun recentlyPlayedDao(): RecentlyPlayedDao

  abstract fun videoMetadataDao(): VideoMetadataDao

  abstract fun networkConnectionDao(): NetworkConnectionDao

  abstract fun playlistDao(): PlaylistDao
}

