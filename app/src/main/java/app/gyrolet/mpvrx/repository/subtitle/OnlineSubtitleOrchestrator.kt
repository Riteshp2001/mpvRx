package app.gyrolet.mpvrx.repository.subtitle

import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class OnlineSubtitleOrchestrator(
  private val wyzieProvider: OnlineSubtitleProvider,
  private val subtitleHubProvider: OnlineSubtitleProvider,
) {
  suspend fun search(
    request: OnlineSubtitleSearchRequest,
    mode: OnlineSubtitleSearchMode,
  ): Result<List<OnlineSubtitle>> =
    when (mode) {
      OnlineSubtitleSearchMode.WYZIE -> wyzieProvider.search(request).map { it.scopeToEpisode(request) }
      OnlineSubtitleSearchMode.SUBHUB -> subtitleHubProvider.search(request).map { it.scopeToEpisode(request) }
      OnlineSubtitleSearchMode.HYBRID -> searchParallel(request, hybridProviders())
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

  private fun hybridProviders(): List<OnlineSubtitleProvider> =
    listOf(wyzieProvider, subtitleHubProvider)

  private suspend fun searchSequential(
    request: OnlineSubtitleSearchRequest,
    providers: List<OnlineSubtitleProvider>,
  ): Result<List<OnlineSubtitle>> {
    val collected = mutableListOf<OnlineSubtitle>()
    val failures = mutableListOf<Throwable>()

    for (provider in providers) {
      provider.search(request)
        .onSuccess { collected += it }
        .onFailure { failures += it }
    }

    if (collected.isNotEmpty()) return Result.success(normalize(collected))
    return Result.failure(failures.firstOrNull() ?: IllegalStateException("No subtitle providers are available"))
  }

  private suspend fun searchParallel(
    request: OnlineSubtitleSearchRequest,
    providers: List<OnlineSubtitleProvider>,
  ): Result<List<OnlineSubtitle>> =
    coroutineScope {
      val results = providers.map { provider -> async { provider.search(request) } }.map { it.await() }
      val collected = results.flatMap { it.getOrElse { emptyList() } }
      if (collected.isNotEmpty()) {
        Result.success(normalize(collected).scopeToEpisode(request))
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
