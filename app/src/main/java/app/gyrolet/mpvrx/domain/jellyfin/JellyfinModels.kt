/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.jellyfin

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class JellyfinServer(
  val id: Long = 0,
  val name: String,
  val serverUrl: String,
  val userId: String,
  val username: String,
  val accessToken: String,
  val lastConnected: Long = 0,
)

enum class JellyfinAuthMode {
  CREDENTIALS,
  TOKEN,
}

enum class JellyfinSortBy(val apiValue: String, val displayName: String) {
  NAME("SortName", "Title"),
  DATE_ADDED("DateCreated", "Recently Added"),
  PREMIERE_DATE("PremiereDate", "Release Date"),
  RATING("CommunityRating", "Rating"),
  RUNTIME("Runtime", "Duration"),
}

enum class JellyfinSortOrder(val apiValue: String, val displayName: String) {
  ASCENDING("Ascending", "Ascending"),
  DESCENDING("Descending", "Descending"),
}

@Immutable
data class JellyfinQueryResult(
  val items: List<JellyfinItem>,
  val totalRecordCount: Int,
  val startIndex: Int,
)

@Immutable
@Serializable
data class JellyfinItem(
  val id: String,
  val name: String,
  val type: String, // CollectionFolder, Movie, Series, Season, Episode, Audio, Folder, etc.
  val collectionType: String? = null,
  val overview: String? = null,
  val runTimeTicks: Long? = null,
  val playbackPositionTicks: Long? = null,
  val isPlayed: Boolean = false,
  val seriesName: String? = null,
  val seasonName: String? = null,
  val indexNumber: Int? = null, // Episode number
  val parentIndexNumber: Int? = null, // Season number
  val productionYear: Int? = null,
  val communityRating: Double? = null,
  val isFolder: Boolean = false,
  val primaryImageTag: String? = null,
  val backdropImageTag: String? = null,
  val childCount: Int? = null,
  val container: String? = null,
) {
  val isVideo: Boolean
    get() = type == "Movie" || type == "Episode" || type == "Video"

  val isAudio: Boolean
    get() = type == "Audio"

  val isSeries: Boolean
    get() = type == "Series"

  val isSeason: Boolean
    get() = type == "Season"

  val durationSeconds: Long
    get() = (runTimeTicks ?: 0L) / 10_000_000L

  val resumePositionSeconds: Long
    get() = (playbackPositionTicks ?: 0L) / 10_000_000L

  val progressPercent: Float
    get() {
      val dur = runTimeTicks ?: return 0f
      val pos = playbackPositionTicks ?: return 0f
      if (dur <= 0L) return 0f
      return (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
    }

  val searchPriority: Int
    get() =
      when (type) {
        "Series" -> 0
        "Movie" -> 1
        "CollectionFolder", "Folder" -> 2
        "Season" -> 3
        "Episode" -> 4
        "Audio" -> 5
        else -> 6
      }
}

@Serializable
data class JellyfinAuthResult(
  val accessToken: String,
  val userId: String,
  val username: String,
  val serverId: String? = null,
  val audioLanguage: String? = null,
  val subtitleLanguage: String? = null,
  val normalizedServerUrl: String = "",
)

@Serializable
data class JellyfinUser(
  val id: String,
  val name: String,
  val serverId: String? = null,
  val audioLanguage: String? = null,
  val subtitleLanguage: String? = null,
  val normalizedServerUrl: String = "",
)
