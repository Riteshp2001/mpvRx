package app.gyrolet.mpvrx.repository.subtitle

import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OnlineSubtitleOrchestrator(
  private val wyzieProvider: OnlineSubtitleProvider,
  private val subtitleHubProvider: OnlineSubtitleProvider,
) {
  suspend fun search(
    request: OnlineSubtitleSearchRequest,
    mode: OnlineSubtitleSearchMode,
    subtitleHubRequest: OnlineSubtitleSearchRequest = request,
    includeWyzie: Boolean = true,
    includeSubtitleHub: Boolean = true,
    onResults: suspend (List<OnlineSubtitle>) -> Unit = {},
  ): Result<List<OnlineSubtitle>> =
    when (mode) {
      OnlineSubtitleSearchMode.WYZIE ->
        wyzieProvider.searchIncrementally(request) { results ->
          onResults(results.scopeToEpisode(request))
        }.map { it.scopeToEpisode(request) }
      OnlineSubtitleSearchMode.SUBHUB -> subtitleHubProvider.searchIncrementally(subtitleHubRequest, onResults)
      OnlineSubtitleSearchMode.HYBRID ->
        searchHybrid(
          wyzieRequest = request,
          subtitleHubRequest = subtitleHubRequest,
          includeWyzie = includeWyzie,
          includeSubtitleHub = includeSubtitleHub,
          onResults = onResults,
        )
    }

  suspend fun download(
    subtitle: OnlineSubtitle,
    mediaTitle: String,
  ): Result<Uri> {
    val provider =
      when (subtitle.provider) {
        SubtitleProvider.WYZIE -> wyzieProvider
        SubtitleProvider.MPVRX_SUBTITLE_HUB -> subtitleHubProvider
      }

    return provider.download(subtitle, mediaTitle)
  }

  private suspend fun searchHybrid(
    wyzieRequest: OnlineSubtitleSearchRequest,
    subtitleHubRequest: OnlineSubtitleSearchRequest,
    includeWyzie: Boolean,
    includeSubtitleHub: Boolean,
    onResults: suspend (List<OnlineSubtitle>) -> Unit,
  ): Result<List<OnlineSubtitle>> =
    coroutineScope {
      val resultLock = Mutex()
      val providerResults = mutableMapOf<SubtitleProvider, List<OnlineSubtitle>>()
      suspend fun publish(provider: SubtitleProvider, results: List<OnlineSubtitle>) {
        resultLock.withLock {
          providerResults[provider] = results
          onResults(normalize(providerResults.values.flatten()))
        }
      }

      val jobs =
        buildList {
          if (includeWyzie) {
            add(async {
              wyzieProvider.searchIncrementally(wyzieRequest) { results ->
                publish(SubtitleProvider.WYZIE, results.scopeToEpisode(wyzieRequest))
              }.map { it.scopeToEpisode(wyzieRequest) }
            })
          }
          if (includeSubtitleHub) {
            add(async {
              subtitleHubProvider.searchIncrementally(subtitleHubRequest) { results ->
                publish(SubtitleProvider.MPVRX_SUBTITLE_HUB, results)
              }
            })
          }
        }
      if (jobs.isEmpty()) return@coroutineScope Result.failure(IllegalStateException("No subtitle providers are available"))

      val results = jobs.map { it.await() }
      val collected = results.flatMap { it.getOrElse { emptyList() } }
      if (collected.isNotEmpty()) {
        Result.success(normalize(collected))
      } else {
        Result.failure(
          results.firstOrNull { it.isFailure }?.exceptionOrNull()
            ?: IllegalStateException("No subtitle providers are available"),
        )
      }
    }

  private fun normalize(subtitles: List<OnlineSubtitle>): List<OnlineSubtitle> {
    val providerOrder =
      mapOf(
        SubtitleProvider.WYZIE to 0,
        SubtitleProvider.MPVRX_SUBTITLE_HUB to 1,
      )

    return subtitles
      .distinctBy { subtitle ->
        subtitle.url.lowercase().ifBlank {
          "${subtitle.provider}:${subtitle.id}:${subtitle.displayName}:${subtitle.language}"
        }
      }
      .sortedWith(
        compareByDescending<OnlineSubtitle> { it.isHashMatch }
          .thenBy { providerOrder[it.provider] ?: Int.MAX_VALUE }
          .thenByDescending { it.downloadCount ?: 0 }
          .thenBy { it.displayName.lowercase() },
      )
  }

  private fun List<OnlineSubtitle>.scopeToEpisode(request: OnlineSubtitleSearchRequest): List<OnlineSubtitle> =
    EpisodeScopeMatcher.filter(this, request.season, request.episode)
}
