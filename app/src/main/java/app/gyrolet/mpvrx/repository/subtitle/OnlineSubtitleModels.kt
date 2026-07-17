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

fun OnlineSubtitle.subdlGroupEpisodeRange(): IntRange? {
  if (provider != SubtitleProvider.MPVRX_SUBTITLE_HUB || source != "SubDL.com") return null
  val start = metadata[SUBDL_GROUP_EPISODE_START_KEY]?.toIntOrNull() ?: return null
  val end = metadata[SUBDL_GROUP_EPISODE_END_KEY]?.toIntOrNull() ?: return null
  if (start <= 0 || end < start || end - start > MAX_SUBDL_GROUP_EPISODES) return null
  return start..end
}

fun OnlineSubtitle.selectedSubdlGroupEpisode(): Int? =
  metadata[SUBDL_SELECTED_EPISODE_KEY]?.toIntOrNull()

fun OnlineSubtitle.withSelectedSubdlGroupEpisode(episode: Int): OnlineSubtitle =
  copy(metadata = metadata + (SUBDL_SELECTED_EPISODE_KEY to episode.toString()))

const val SUBDL_GROUP_EPISODE_START_KEY = "subdlGroupEpisodeStart"
const val SUBDL_GROUP_EPISODE_END_KEY = "subdlGroupEpisodeEnd"
const val SUBDL_SELECTED_EPISODE_KEY = "subdlSelectedEpisode"

private const val MAX_SUBDL_GROUP_EPISODES = 500

interface OnlineSubtitleProvider {
  val provider: SubtitleProvider

  suspend fun search(request: OnlineSubtitleSearchRequest): Result<List<OnlineSubtitle>>

  suspend fun searchIncrementally(
    request: OnlineSubtitleSearchRequest,
    onResults: suspend (List<OnlineSubtitle>) -> Unit,
  ): Result<List<OnlineSubtitle>> =
    search(request).also { result ->
      result.getOrNull()?.let { onResults(it) }
    }

  suspend fun download(
    subtitle: OnlineSubtitle,
    mediaTitle: String,
  ): Result<Uri>
}
