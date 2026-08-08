/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.gyrolet.mpvrx.database.converters.NetworkProtocolConverter
import app.gyrolet.mpvrx.database.converters.NetworkStreamEntryTypeConverter
import app.gyrolet.mpvrx.database.dao.DirectoryScanDao
import app.gyrolet.mpvrx.database.dao.NetworkConnectionDao
import app.gyrolet.mpvrx.database.dao.NetworkStreamEntryDao
import app.gyrolet.mpvrx.database.dao.PlaybackStateDao
import app.gyrolet.mpvrx.database.dao.PlaylistDao
import app.gyrolet.mpvrx.database.dao.RecentlyPlayedDao
import app.gyrolet.mpvrx.database.dao.SecureMediaDao
import app.gyrolet.mpvrx.database.dao.VideoMetadataDao
import app.gyrolet.mpvrx.database.entities.DirectoryScanEntity
import app.gyrolet.mpvrx.database.entities.NetworkStreamEntryEntity
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.database.entities.PlaylistItemEntity
import app.gyrolet.mpvrx.database.entities.RecentlyPlayedEntity
import app.gyrolet.mpvrx.database.entities.SecureMediaEntity
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
    DirectoryScanEntity::class,
    SecureMediaEntity::class,
    NetworkStreamEntryEntity::class,
  ],
  version = 13,
  exportSchema = true,
)
@TypeConverters(NetworkProtocolConverter::class, NetworkStreamEntryTypeConverter::class)
abstract class MpvRxDatabase : RoomDatabase() {
  abstract fun videoDataDao(): PlaybackStateDao

  abstract fun recentlyPlayedDao(): RecentlyPlayedDao

  abstract fun videoMetadataDao(): VideoMetadataDao

  abstract fun networkConnectionDao(): NetworkConnectionDao

  abstract fun networkStreamEntryDao(): NetworkStreamEntryDao

  abstract fun playlistDao(): PlaylistDao

  abstract fun directoryScanDao(): DirectoryScanDao

  abstract fun secureMediaDao(): SecureMediaDao
}
