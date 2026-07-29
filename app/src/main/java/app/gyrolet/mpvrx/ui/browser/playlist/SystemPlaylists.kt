/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
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
