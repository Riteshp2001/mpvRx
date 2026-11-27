/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.seerr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class MediaType(
  val value: String,
) {
  MOVIE("movie"),
  TV("tv"),
  ;

  companion object {
    fun fromApiString(value: String): MediaType =
      when (value.lowercase()) {
        "movie" -> MOVIE
        "tv" -> TV
        else -> MOVIE
      }
  }
}

enum class MediaStatus(
  val value: Int,
) {
  UNKNOWN(1),
  PENDING(2),
  PROCESSING(3),
  PARTIALLY_AVAILABLE(4),
  AVAILABLE(5),
  BLACKLISTED(6),
  DELETED(7),
  ;

  companion object {
    fun fromValue(value: Int?): MediaStatus =
      when (value) {
        1 -> UNKNOWN
        2 -> PENDING
        3 -> PROCESSING
        4 -> PARTIALLY_AVAILABLE
        5 -> AVAILABLE
        6 -> BLACKLISTED
        7 -> DELETED
        else -> UNKNOWN
      }
  }
}

enum class RequestStatus(
  val value: Int,
) {
  PENDING(1),
  APPROVED(2),
  DECLINED(3),
  FAILED(4),
  COMPLETED(5),
  ;

  companion object {
    fun fromValue(value: Int?): RequestStatus = entries.firstOrNull { it.value == value } ?: PENDING
  }
}

@Serializable
data class SearchResultItem(
  @SerialName("id") val id: Int,
  @SerialName("mediaType") val mediaType: String = "movie",
  @SerialName("title") val title: String? = null,
  @SerialName("name") val name: String? = null,
  @SerialName("originalTitle") val originalTitle: String? = null,
  @SerialName("originalName") val originalName: String? = null,
  @SerialName("overview") val overview: String? = null,
  @SerialName("posterPath") val posterPath: String? = null,
  @SerialName("backdropPath") val backdropPath: String? = null,
  @SerialName("releaseDate") val releaseDate: String? = null,
  @SerialName("firstAirDate") val firstAirDate: String? = null,
  @SerialName("voteAverage") val voteAverage: Double? = null,
  @SerialName("voteCount") val voteCount: Int? = null,
  @SerialName("popularity") val popularity: Double? = null,
  @SerialName("genreIds") val genreIds: List<Int>? = null,
  @SerialName("originalLanguage") val originalLanguage: String? = null,
  @SerialName("adult") val adult: Boolean? = null,
  @SerialName("mediaInfo") val mediaInfo: MediaInfo? = null,
) {
  fun getDisplayTitle(): String = title ?: name ?: originalTitle ?: originalName ?: "Unknown"

  fun getMediaType(): MediaType =
    try {
      MediaType.fromApiString(mediaType)
    } catch (_: Exception) {
      MediaType.MOVIE
    }

  fun getPosterUrl(baseUrl: String = "https://image.tmdb.org/t/p/w500"): String? =
    posterPath?.let {
      when {
        it.startsWith("http://") || it.startsWith("https://") -> it
        it.startsWith("/") -> "$baseUrl$it"
        else -> "$baseUrl/$it"
      }
    }

  fun getBackdropUrl(baseUrl: String = "https://image.tmdb.org/t/p/w1280"): String? =
    backdropPath?.let {
      when {
        it.startsWith("http://") || it.startsWith("https://") -> it
        it.startsWith("/") -> "$baseUrl$it"
        else -> "$baseUrl/$it"
      }
    }

  fun getMediaStatus(): MediaStatus? {
    val standardStatus = mediaInfo?.status
    val status4k = mediaInfo?.status4k

    fun isValid(status: Int?) = status != null && status != 1
    return when {
      isValid(standardStatus) -> MediaStatus.fromValue(standardStatus)
      isValid(status4k) -> MediaStatus.fromValue(status4k)
      else -> mediaInfo?.status?.let { MediaStatus.fromValue(it) }
    }
  }

  fun getDisplayStatus(): MediaStatus? {
    val status = getMediaStatus() ?: return null
    if (status == MediaStatus.PENDING) {
      val hasApprovedRequest = mediaInfo?.requests?.any { it.status == RequestStatus.APPROVED.value } == true
      if (hasApprovedRequest) {
        return MediaStatus.PROCESSING
      }
    }
    return status
  }

  fun getRating(): String? =
    voteAverage?.let {
      if (it > 0) String.format("%.1f", it) else null
    }

  fun getReleaseYear(): String? {
    val date = releaseDate ?: firstAirDate
    return date?.take(4)
  }
}

@Serializable
data class JellyseerrSearchResult(
  @SerialName("page") val page: Int = 1,
  @SerialName("totalPages") val totalPages: Int = 1,
  @SerialName("totalResults") val totalResults: Int = 0,
  @SerialName("results") val results: List<SearchResultItem> = emptyList(),
)

@Serializable
data class MediaDetails(
  @SerialName("id") val id: Int,
  @SerialName("title") val title: String? = null,
  @SerialName("name") val name: String? = null,
  @SerialName("overview") val overview: String? = null,
  @SerialName("posterPath") val posterPath: String? = null,
  @SerialName("backdropPath") val backdropPath: String? = null,
  @SerialName("releaseDate") val releaseDate: String? = null,
  @SerialName("numberOfSeason") val numberOfSeason: Int? = null,
  @SerialName("numberOfEpisodes") val numberOfEpisodes: Int? = null,
  @SerialName("seasons") val seasons: List<Season>? = null,
  @SerialName("firstAirDate") val firstAirDate: String? = null,
  @SerialName("lastAirDate") val lastAirDate: String? = null,
  @SerialName("status") val status: String? = null,
  @SerialName("voteAverage") val voteAverage: Double? = null,
  @SerialName("voteCount") val voteCount: Int? = null,
  @SerialName("popularity") val popularity: Double? = null,
  @SerialName("inProduction") val inProduction: Boolean? = null,
  @SerialName("mediaInfo") val mediaInfo: MediaInfo? = null,
  @SerialName("tagline") val tagline: String? = null,
  @SerialName("runtime") val runtime: Int? = null,
  @SerialName("originalLanguage") val originalLanguage: String? = null,
  @SerialName("genres") val genres: List<Genre>? = null,
  @SerialName("credits") val credits: Credits? = null,
  @SerialName("originalTitle") val originalTitle: String? = null,
  @SerialName("originalName") val originalName: String? = null,
) {
  fun getDisplayTitle(): String = title ?: name ?: originalTitle ?: originalName ?: "Unknown"

  fun getPosterUrl(baseUrl: String = "https://image.tmdb.org/t/p/w500"): String? =
    posterPath?.let {
      when {
        it.startsWith("http://") || it.startsWith("https://") -> it
        it.startsWith("/") -> "$baseUrl$it"
        else -> "$baseUrl/$it"
      }
    }

  fun getBackdropUrl(baseUrl: String = "https://image.tmdb.org/t/p/w1280"): String? =
    backdropPath?.let {
      when {
        it.startsWith("http://") || it.startsWith("https://") -> it
        it.startsWith("/") -> "$baseUrl$it"
        else -> "$baseUrl/$it"
      }
    }

  fun getRating(): String? =
    voteAverage?.let {
      if (it > 0) String.format("%.1f", it) else null
    }

  fun getYear(): String? {
    val date = releaseDate ?: firstAirDate
    return date?.take(4)
  }

  fun getMediaStatus(): MediaStatus? {
    val standardStatus = mediaInfo?.status
    val status4k = mediaInfo?.status4k

    fun isValid(status: Int?) = status != null && status != 1
    return when {
      isValid(standardStatus) -> MediaStatus.fromValue(standardStatus)
      isValid(status4k) -> MediaStatus.fromValue(status4k)
      else -> mediaInfo?.status?.let { MediaStatus.fromValue(it) }
    }
  }

  fun getDisplayStatus(): MediaStatus? {
    val status = getMediaStatus() ?: return null
    if (status == MediaStatus.PENDING) {
      val hasApprovedRequest = mediaInfo?.requests?.any { it.status == RequestStatus.APPROVED.value } == true
      if (hasApprovedRequest) {
        return MediaStatus.PROCESSING
      }
    }
    return status
  }
}

@Serializable
data class Season(
  @SerialName("id") val id: Int,
  @SerialName("seasonNumber") val seasonNumber: Int? = null,
  @SerialName("name") val name: String? = null,
  @SerialName("overview") val overview: String? = null,
  @SerialName("episodeCount") val episodeCount: Int? = null,
  @SerialName("posterPath") val posterPath: String? = null,
  @SerialName("airDate") val airDate: String? = null,
)

@Serializable
data class Credits(
  @SerialName("cast") val cast: List<CastMember>? = null,
  @SerialName("crew") val crew: List<CrewMember>? = null,
)

@Serializable
data class CastMember(
  @SerialName("id") val id: Int,
  @SerialName("name") val name: String,
  @SerialName("character") val character: String? = null,
  @SerialName("profilePath") val profilePath: String? = null,
) {
  fun getProfileUrl(baseUrl: String = "https://image.tmdb.org/t/p/w185"): String? =
    profilePath?.let {
      when {
        it.startsWith("http://") || it.startsWith("https://") -> it
        it.startsWith("/") -> "$baseUrl$it"
        else -> "$baseUrl/$it"
      }
    }
}

@Serializable
data class CrewMember(
  @SerialName("id") val id: Int,
  @SerialName("name") val name: String,
  @SerialName("job") val job: String,
  @SerialName("department") val department: String? = null,
)

@Serializable
data class MediaInfo(
  @SerialName("id") val id: Int,
  @SerialName("mediaType") val mediaType: String,
  @SerialName("tmdbId") val tmdbId: Int? = null,
  @SerialName("tvdbId") val tvdbId: Int? = null,
  @SerialName("status") val status: Int? = null,
  @SerialName("status4k") val status4k: Int? = null,
  @SerialName("mediaAddedAt") val mediaAddedAt: String? = null,
  @SerialName("seasons") val seasons: List<MediaInfoSeason>? = null,
  @SerialName("requests") val requests: List<JellyseerrRequest>? = null,
  @SerialName("createdAt") val createdAt: String? = null,
  @SerialName("updatedAt") val updatedAt: String? = null,
  @SerialName("title") val title: String? = null,
  @SerialName("name") val name: String? = null,
  @SerialName("posterPath") val posterPath: String? = null,
  @SerialName("backdropPath") val backdropPath: String? = null,
  @SerialName("releaseDate") val releaseDate: String? = null,
  @SerialName("firstAirDate") val firstAirDate: String? = null,
  @SerialName("jellyfinMediaId") val jellyfinMediaId: String? = null,
  @SerialName("jellyfinMediaId4k") val jellyfinMediaId4k: String? = null,
) {
  fun getDisplayTitle(): String = title ?: name ?: "Unknown"

  fun getReleaseYear(): String? {
    val date = releaseDate ?: firstAirDate
    return if (!date.isNullOrBlank() && date.length >= 4) date.take(4) else null
  }

  fun getPosterUrl(baseUrl: String = "https://image.tmdb.org/t/p/w500"): String? =
    posterPath?.let {
      when {
        it.startsWith("http://") || it.startsWith("https://") -> it
        it.startsWith("/") -> "$baseUrl$it"
        else -> "$baseUrl/$it"
      }
    }

  fun getBackdropUrl(baseUrl: String = "https://image.tmdb.org/t/p/w1280"): String? =
    backdropPath?.let {
      when {
        it.startsWith("http://") || it.startsWith("https://") -> it
        it.startsWith("/") -> "$baseUrl$it"
        else -> "$baseUrl/$it"
      }
    }

  fun getJellyfinItemId(): String? {
    val raw = jellyfinMediaId ?: jellyfinMediaId4k ?: return null
    return if (raw.length == 32 && !raw.contains("-")) {
      "${raw.take(8)}-${raw.substring(8, 12)}-${raw.substring(12, 16)}-${raw.substring(16, 20)}-${raw.substring(20)}"
    } else {
      raw
    }
  }

  fun toSearchResultItem(): SearchResultItem =
    SearchResultItem(
      id = tmdbId ?: id,
      mediaType = mediaType,
      title = title,
      name = name,
      posterPath = posterPath,
      backdropPath = backdropPath,
      releaseDate = releaseDate,
      firstAirDate = firstAirDate,
      mediaInfo = this,
    )
}

@Serializable
data class MediaInfoSeason(
  @SerialName("id") val id: Int? = null,
  @SerialName("seasonNumber") val seasonNumber: Int? = null,
  @SerialName("status") val status: Int? = null,
)

@Serializable
data class MediaResultsResponse(
  @SerialName("pageInfo") val pageInfo: PageInfo? = null,
  @SerialName("results") val results: List<MediaInfo> = emptyList(),
)

@Serializable
data class JellyseerrRequest(
  @SerialName("id") val id: Int,
  @SerialName("status") val status: Int,
  @SerialName("media") val media: MediaInfo,
  @SerialName("requestedBy") val requestedBy: RequestUser,
  @SerialName("modifiedBy") val modifiedBy: RequestUser? = null,
  @SerialName("createdAt") val createdAt: String,
  @SerialName("updatedAt") val updatedAt: String,
  @SerialName("seasons") val seasons: List<SeasonRequest>? = null,
  @SerialName("is4k") val is4k: Boolean = false,
  @SerialName("serverId") val serverId: Int? = null,
  @SerialName("profileId") val profileId: Int? = null,
  @SerialName("rootFolder") val rootFolder: String? = null,
) {
  fun getRequestStatus(): RequestStatus = RequestStatus.fromValue(status)

  fun getMediaType(): MediaType =
    try {
      MediaType.fromApiString(media.mediaType)
    } catch (_: Exception) {
      MediaType.MOVIE
    }
}

@Serializable
data class SeasonRequest(
  @SerialName("id") val id: Int,
  @SerialName("seasonNumber") val seasonNumber: Int,
  @SerialName("status") val status: Int,
)

@Serializable
data class RequestUser(
  @SerialName("id") val id: Int,
  @SerialName("email") val email: String? = null,
  @SerialName("username") val username: String? = null,
  @SerialName("displayName") val displayName: String? = null,
  @SerialName("avatar") val avatar: String? = null,
  @SerialName("permissions") val permissions: Long? = null,
)

@Serializable
data class RequestsResponse(
  @SerialName("pageInfo") val pageInfo: PageInfo? = null,
  @SerialName("results") val results: List<JellyseerrRequest> = emptyList(),
)

@Serializable
data class PageInfo(
  @SerialName("page") val page: Int = 1,
  @SerialName("pages") val pages: Int = 1,
  @SerialName("results") val results: Int = 0,
)

@Serializable
data class CreateRequestBody(
  @SerialName("mediaType") val mediaType: String,
  @SerialName("mediaId") val mediaId: Int,
  @SerialName("seasons") val seasons: List<Int>? = null,
  @SerialName("is4k") val is4k: Boolean = false,
  @SerialName("serverId") val serverId: Int? = null,
  @SerialName("profileId") val profileId: Int? = null,
  @SerialName("rootFolder") val rootFolder: String? = null,
)

@Serializable
data class SeerrServiceProfile(
  @SerialName("id") val id: Int? = null,
  @SerialName("name") val name: String? = null,
)

@Serializable
data class SeerrRootFolder(
  @SerialName("id") val id: Int? = null,
  @SerialName("path") val path: String? = null,
)

@Serializable
data class SeerrRadarrServer(
  @SerialName("id") val id: Int? = null,
  @SerialName("name") val name: String? = null,
  @SerialName("is4k") val is4k: Boolean? = false,
  @SerialName("isDefault") val isDefault: Boolean? = false,
  @SerialName("activeProfileId") val activeProfileId: Int? = null,
  @SerialName("activeDirectory") val activeDirectory: String? = null,
  @SerialName("profiles") val profiles: List<SeerrServiceProfile> = emptyList(),
  @SerialName("rootFolders") val rootFolders: List<SeerrRootFolder> = emptyList(),
)

@Serializable
data class SeerrRadarrServerResponse(
  @SerialName("server") val server: SeerrRadarrServer? = null,
  @SerialName("profiles") val profiles: List<SeerrServiceProfile> = emptyList(),
  @SerialName("rootFolders") val rootFolders: List<SeerrRootFolder> = emptyList(),
)

@Serializable
data class SeerrSonarrServer(
  @SerialName("id") val id: Int? = null,
  @SerialName("name") val name: String? = null,
  @SerialName("is4k") val is4k: Boolean? = false,
  @SerialName("isDefault") val isDefault: Boolean? = false,
  @SerialName("activeProfileId") val activeProfileId: Int? = null,
  @SerialName("activeDirectory") val activeDirectory: String? = null,
  @SerialName("profiles") val profiles: List<SeerrServiceProfile> = emptyList(),
  @SerialName("rootFolders") val rootFolders: List<SeerrRootFolder> = emptyList(),
)

@Serializable
data class SeerrSonarrServerResponse(
  @SerialName("server") val server: SeerrSonarrServer? = null,
  @SerialName("profiles") val profiles: List<SeerrServiceProfile> = emptyList(),
  @SerialName("rootFolders") val rootFolders: List<SeerrRootFolder> = emptyList(),
)

@Serializable
data class Genre(
  @SerialName("id") val id: Int,
  @SerialName("name") val name: String,
)

@Serializable
data class Network(
  @SerialName("id") val id: Int,
  @SerialName("name") val name: String,
  @SerialName("logoPath") val logoPath: String? = null,
)

@Serializable
data class JellyseerrUser(
  @SerialName("id") val id: Int,
  @SerialName("email") val email: String? = null,
  @SerialName("username") val username: String? = null,
  @SerialName("displayName") val displayName: String? = null,
  @SerialName("avatar") val avatar: String? = null,
  @SerialName("permissions") val permissions: Long = 0,
  @SerialName("userType") val userType: Int? = null,
  @SerialName("createdAt") val createdAt: String? = null,
  @SerialName("updatedAt") val updatedAt: String? = null,
  @SerialName("requestCount") val requestCount: Int = 0,
) {
  fun isAdmin(): Boolean = Permissions.hasPermission(permissions, Permissions.ADMIN)
}

object Permissions {
  const val NONE = 0L
  const val ADMIN = 1L shl 1
  const val MANAGE_SETTINGS = 1L shl 2
  const val MANAGE_USERS = 1L shl 3
  const val MANAGE_REQUESTS = 1L shl 4
  const val REQUEST = 1L shl 5
  const val VOTE = 1L shl 6
  const val AUTO_APPROVE = 1L shl 7
  const val AUTO_APPROVE_MOVIE = 1L shl 8
  const val AUTO_APPROVE_TV = 1L shl 9
  const val REQUEST_4K = 1L shl 10
  const val REQUEST_4K_MOVIE = 1L shl 11
  const val REQUEST_4K_TV = 1L shl 12
  const val REQUEST_ADVANCED = 1L shl 13
  const val AUTO_APPROVE_4K = 1L shl 14
  const val AUTO_APPROVE_4K_MOVIE = 1L shl 15
  const val AUTO_APPROVE_4K_TV = 1L shl 16

  fun hasPermission(
    permissions: Long,
    permission: Long,
  ): Boolean {
    if ((permissions and ADMIN) == ADMIN) return true
    return (permissions and permission) == permission
  }
}
