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

enum class JellyfinSearchCategory(val displayName: String) {
  ALL("All"),
  MOVIES("Movies"),
  SHOWS("TV Shows"),
  EPISODES("Episodes"),
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
  val isFavorite: Boolean = false,
  val seriesName: String? = null,
  val seasonName: String? = null,
  val indexNumber: Int? = null, // Episode number
  val parentIndexNumber: Int? = null, // Season number
  val productionYear: Int? = null,
  val communityRating: Double? = null,
  val criticRating: Double? = null,
  val officialRating: String? = null, // PG-13, TV-MA, R, etc.
  val taglines: List<String> = emptyList(),
  val genres: List<String> = emptyList(),
  val isFolder: Boolean = false,
  val primaryImageTag: String? = null,
  val backdropImageTag: String? = null,
  val childCount: Int? = null,
  val container: String? = null,
  val videoCodec: String? = null,
  val videoResolution: String? = null,
  val videoHdrType: String? = null,
  val audioCodec: String? = null,
  val audioChannels: String? = null,
  val premiereDate: String? = null,
  val status: String? = null,
  val lastPlayedDate: String? = null,
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

  val formattedDuration: String
    get() {
      val totalSec = durationSeconds
      if (totalSec <= 0L) return ""
      val hours = totalSec / 3600
      val minutes = (totalSec % 3600) / 60
      return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
      }
    }

  val formattedRemainingDuration: String
    get() {
      val totalSec = durationSeconds
      val curSec = resumePositionSeconds
      val remaining = (totalSec - curSec).coerceAtLeast(0)
      if (remaining <= 0L) return ""
      val hours = remaining / 3600
      val minutes = (remaining % 3600) / 60
      return when {
        hours > 0 -> "${hours}h ${minutes}m left"
        minutes > 0 -> "${minutes}m left"
        else -> "<1m left"
      }
    }

  val formattedRating: String?
    get() = communityRating?.let { "%.1f".format(it) }

  val qualityBadge: String?
    get() =
      when {
        !videoHdrType.isNullOrBlank() && !videoResolution.isNullOrBlank() -> "$videoResolution $videoHdrType"
        !videoResolution.isNullOrBlank() -> videoResolution
        !videoHdrType.isNullOrBlank() -> videoHdrType
        else -> null
      }

  val audioBadge: String?
    get() =
      when {
        !audioCodec.isNullOrBlank() && !audioChannels.isNullOrBlank() -> "$audioCodec $audioChannels"
        !audioCodec.isNullOrBlank() -> audioCodec
        else -> null
      }

  val genresString: String
    get() = genres.take(3).joinToString(" • ")

  val displayTitle: String
    get() =
      when {
        isSeries -> name
        seriesName != null && indexNumber != null -> {
          val s = parentIndexNumber ?: 1
          "$seriesName S${s}E$indexNumber - $name"
        }
        else -> name
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
