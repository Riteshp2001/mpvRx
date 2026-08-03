/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.playlist

import app.gyrolet.mpvrx.database.entities.PlaylistEntity

const val ALL_VIDEOS_PLAYLIST_ID = -2
const val ALL_VIDEOS_PLAYLIST_NAME = "All Videos"

fun isAllVideosPlaylist(playlistId: Int): Boolean = playlistId == ALL_VIDEOS_PLAYLIST_ID

fun buildAllVideosPlaylistEntity(updatedAt: Long = System.currentTimeMillis()): PlaylistEntity =
  PlaylistEntity(
    id = ALL_VIDEOS_PLAYLIST_ID,
    name = ALL_VIDEOS_PLAYLIST_NAME,
    createdAt = 0L,
    updatedAt = updatedAt,
  )
