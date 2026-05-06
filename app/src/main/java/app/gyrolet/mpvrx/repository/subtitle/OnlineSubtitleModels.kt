package app.gyrolet.mpvrx.repository.subtitle

import android.net.Uri

enum class SubtitleProvider(
  val displayName: String,
) {
  WYZIE("Wyzie"),
  MPVRX_SUBTITLE_HUB("SubHub"),
}

enum class OnlineSubtitleSearchMode(
  val displayName: String,
) {
  WYZIE("Wyzie"),
  SUBHUB("SubHub"),
  HYBRID("Hybrid"),
}

data class OnlineSubtitleSearchRequest(
  val query: String,
  val tmdbId: Int? = null,
  val season: Int? = null,
  val episode: Int? = null,
  val year: String? = null,
  val movieHash: String? = null,
)

data class OnlineSubtitle(
  val provider: SubtitleProvider,
  val id: String? = null,
  val url: String,
  val fileName: String? = null,
  val release: String? = null,
  val media: String? = null,
  val displayName: String,
  val displayLanguage: String,
  val language: String? = null,
  val source: String? = null,
  val format: String? = null,
  val encoding: String? = null,
  val downloadCount: Int? = null,
  val isHashMatch: Boolean = false,
  val isHearingImpaired: Boolean = false,
  val metadata: Map<String, String> = emptyMap(),
)

interface OnlineSubtitleProvider {
  val provider: SubtitleProvider

  suspend fun search(request: OnlineSubtitleSearchRequest): Result<List<OnlineSubtitle>>

  suspend fun download(
    subtitle: OnlineSubtitle,
    mediaTitle: String,
  ): Result<Uri>
}
