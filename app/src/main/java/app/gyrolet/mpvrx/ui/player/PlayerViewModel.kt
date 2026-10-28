/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import `is`.xyz.mpv.MPVNode
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.util.Log
import android.util.LruCache
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.data.network.client.NetworkMimeTypes
import app.gyrolet.mpvrx.data.network.proxy.NetworkStreamingProxy
import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager
import app.gyrolet.mpvrx.domain.autocrop.AutoCropAnalyzer
import app.gyrolet.mpvrx.domain.autocrop.AutoCropEdges
import app.gyrolet.mpvrx.domain.hdr.HdrToysManager
import app.gyrolet.mpvrx.domain.network.NetworkPlaybackUri
import app.gyrolet.mpvrx.domain.network.XtreamPlaybackUri
import app.gyrolet.mpvrx.domain.torrent.TorrentStreamingState
import app.gyrolet.mpvrx.domain.syncplay.SyncplayFile
import app.gyrolet.mpvrx.domain.syncplay.SyncplayPlaybackState
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.AudioChannels
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.MpvConfigControlledFeatures
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import app.gyrolet.mpvrx.preferences.IntroSegmentProvider
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.repository.IntroDbLookupOutcome
import app.gyrolet.mpvrx.repository.IntroDbLookupRequest
import app.gyrolet.mpvrx.repository.IntroDbRepository
import app.gyrolet.mpvrx.repository.ai.RealtimeMediaInput
import app.gyrolet.mpvrx.repository.ai.RealtimeSubtitleRequest
import app.gyrolet.mpvrx.repository.ai.SubtitleGenerationService
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitle
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleOrchestrator
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleSearchMode
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleSearchRequest
import app.gyrolet.mpvrx.repository.wyzie.WyzieSearchRepository
import app.gyrolet.mpvrx.ui.player.ScriptCurlBridge
import app.gyrolet.mpvrx.ui.player.anime4k.Anime4KUiState
import app.gyrolet.mpvrx.ui.player.anime4k.applyAnime4KShaderChain
import app.gyrolet.mpvrx.ui.player.anime4k.applyAnime4KStabilityOptions
import app.gyrolet.mpvrx.ui.player.anime4k.clearAnime4KShaders
import app.gyrolet.mpvrx.ui.player.anime4k.selectRuntimeStableAnime4K
import app.gyrolet.mpvrx.ui.player.controls.components.panels.toColorHexString
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.EQ_MAX_DB
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.EQ_MIN_DB
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.EQ_TONE_STEPS
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.EqualizerMode
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.EqualizerFilterKind
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.EqualizerPreset
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.EqualizerState
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.activeFilters
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.recommendedPreampDb
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.ui.player.screenshot.ScreenshotSaver
import app.gyrolet.mpvrx.ui.player.screenshot.ScreenshotSettings
import app.gyrolet.mpvrx.ui.preferences.CustomButton
import app.gyrolet.mpvrx.ui.preferences.CustomButtonScriptLanguage
import app.gyrolet.mpvrx.utils.media.ChecksumUtils
import app.gyrolet.mpvrx.utils.media.HttpUtils
import app.gyrolet.mpvrx.utils.media.MediaInfoParser
import app.gyrolet.mpvrx.utils.media.ParsedMediaInfo
import app.gyrolet.mpvrx.utils.media.SubtitleHashUtils
import app.gyrolet.mpvrx.utils.media.fileExtension
import app.gyrolet.mpvrx.utils.media.resolveSubtitleLookupDirectories
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.properties.ReadOnlyProperty
import kotlin.random.Random
import kotlin.reflect.KProperty

// ~45% opaque charcoal (#AARRGGBB): visible on bright scenes without hiding the video like solid black.
private const val SUBTITLE_GESTURE_HIGHLIGHT_COLOR = "#73202429"
private const val SUBTITLE_GESTURE_HIGHLIGHT_PADDING = 8f

private data class SubtitleGestureBoxStyle(
  val borderStyle: String?,
  val backgroundColor: String?,
  val shadowOffset: String?,
)

enum class AutoCropState {
  IDLE,
  ANALYZING,
  APPLIED,
  NO_BARS,
  UNSUPPORTED,
  ERROR,
}

/**
 * @param hostedInBrowser the in-app music sheet adopts a core owned by [MediaPlaybackService]. It
 * never claims process-wide bindings (Syncplay, ambient/post-processing shaders, subtitle styling)
 * and only reads the core between [attachToDetachedPlayback] and [detachFromDetachedPlayback].
 */
@Suppress("TooManyFunctions")
class PlayerViewModel(
  private val hostedInBrowser: Boolean = false,
) : ViewModel(),
  KoinComponent {
  private var hostReference = WeakReference<PlayerHost>(null)
  private val host: PlayerHost?
    get() = hostReference.get()

  fun minimizeToMiniPlayer(): Boolean = hostReference.get()?.minimizeToMiniPlayer() == true

  fun attachHost(host: PlayerHost) {
    hostReference = WeakReference(host)
  }

  fun detachHost(host: PlayerHost) {
    if (hostReference.get() === host) hostReference.clear()
  }
  enum class IntroDbStatusState {
    IDLE,
    LOOKING_UP,
    LOADED,
    NO_SEGMENTS,
    UNRESOLVED,
    ERROR,
    DISABLED,
  }

  data class IntroDbStatus(
    val state: IntroDbStatusState = IntroDbStatusState.IDLE,
    val message: String = "",
    val imdbId: String? = null,
    val segmentCount: Int = 0,
  )

  @Serializable
  private data class IntroMarkerCacheEntry(
    val providerSourceKey: String,
    val outcomeType: String,
    val imdbId: String? = null,
    val message: String = "",
    val segments: List<app.gyrolet.mpvrx.repository.IntroDbSegment> = emptyList(),
    val cachedAtMs: Long = System.currentTimeMillis(),
  )

  private val playerPreferences: PlayerPreferences by inject()
  private val appContext: Context by inject()
  private val gesturePreferences: GesturePreferences by inject()
  private val audioPreferences: AudioPreferences by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val aiPreferences: app.gyrolet.mpvrx.preferences.AiPreferences by inject()
  private val advancedPreferences: AdvancedPreferences by inject()
  private val decoderPreferences: DecoderPreferences by inject()
  private val anime4kManager: Anime4KManager by inject()
  private val hdrToysManager: HdrToysManager by inject()
  private val json: Json by inject()
  private val playbackStateDao: app.gyrolet.mpvrx.database.dao.PlaybackStateDao by inject()
  private val playbackBookmarkDao: app.gyrolet.mpvrx.database.dao.PlaybackBookmarkDao by inject()
  private val aiService: app.gyrolet.mpvrx.repository.ai.AiService by inject()
  private val subtitleGenerationService: SubtitleGenerationService by inject()
  private val realtimeSubtitleService: app.gyrolet.mpvrx.repository.ai.RealtimeSubtitleService by inject()
  private val wyzieRepository: WyzieSearchRepository by inject()
  private val onlineSubtitleOrchestrator: OnlineSubtitleOrchestrator by inject()
  private val introDbRepository: IntroDbRepository by inject()
  val syncplayManager: app.gyrolet.mpvrx.domain.syncplay.SyncplayManager by inject()
  private val lyricsRepository: app.gyrolet.mpvrx.repository.lyrics.LyricsRepository by inject()
  private val lyricsTranslationService: app.gyrolet.mpvrx.data.lyrics.LyricsTranslationService by inject()
  private val introMarkerCachePrefs by lazy {
    appContext.getSharedPreferences(INTRO_MARKER_CACHE_PREFS, Context.MODE_PRIVATE)
  }

  private val initialAnime4KUiState
    get() =
      Anime4KUiState(
        isEnabled = decoderPreferences.enableAnime4K.get(),
        selectedMode = decoderPreferences.anime4kMode.get(),
        usesGpuNext = decoderPreferences.gpuNext.get(),
        usesVulkan = decoderPreferences.useVulkan.get(),
        enableIn4k = decoderPreferences.anime4kIn4k.get(),
      )

  private val anime4KPreferenceState =
    combine(
      decoderPreferences.enableAnime4K.changes(),
      decoderPreferences.anime4kMode.changes(),
      decoderPreferences.gpuNext.changes(),
      decoderPreferences.useVulkan.changes(),
      decoderPreferences.anime4kIn4k.changes(),
    ) { enabled, mode, gpuNext, useVulkan, enableIn4k ->
      Anime4KUiState(
        isEnabled = enabled,
        selectedMode = mode,
        usesGpuNext = gpuNext,
        usesVulkan = useVulkan,
        enableIn4k = enableIn4k,
      )
    }

  val anime4KUiState =
    anime4KPreferenceState
      .combine(PlaybackSession.propInt["video-params/w"]) { state, width ->
        state.copy(videoWidth = width ?: 0)
      }.combine(PlaybackSession.propInt["video-params/h"]) { state, height ->
        state.copy(videoHeight = height ?: 0)
      }.distinctUntilChanged()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = initialAnime4KUiState,
      )

  // HTTP bridge for Lua/JS scripts — executes curl_request payloads via native libcurl
  private val scriptCurlBridge = ScriptCurlBridge(scope = viewModelScope)

  // Playlist items for the playlist sheet
  private val _playlistItems =
    kotlinx.coroutines.flow
      .MutableStateFlow<List<app.gyrolet.mpvrx.ui.player.controls.components.sheets.PlaylistItem>>(
        emptyList(),
      )
  val playlistItems:
    kotlinx.coroutines.flow.StateFlow<List<app.gyrolet.mpvrx.ui.player.controls.components.sheets.PlaylistItem>> =
    _playlistItems
      .asStateFlow()

  private val _onlineSubtitleSearchResults = MutableStateFlow<List<OnlineSubtitle>>(emptyList())
  val onlineSubtitleSearchResults: StateFlow<List<OnlineSubtitle>> = _onlineSubtitleSearchResults.asStateFlow()

  private val _isDownloadingSub = MutableStateFlow(false)
  val isDownloadingSub: StateFlow<Boolean> = _isDownloadingSub.asStateFlow()

  private val _isSearchingSub = MutableStateFlow(false)
  val isSearchingSub: StateFlow<Boolean> = _isSearchingSub.asStateFlow()

  private val _isOnlineSectionExpanded = MutableStateFlow(true)
  val isOnlineSectionExpanded: StateFlow<Boolean> = _isOnlineSectionExpanded.asStateFlow()

  private val _isTranslatingSub = MutableStateFlow(false)
  val isTranslatingSub: StateFlow<Boolean> = _isTranslatingSub.asStateFlow()

  private val _translatingTrackId = MutableStateFlow<Int?>(null)
  val translatingTrackId: StateFlow<Int?> = _translatingTrackId.asStateFlow()

  private val _translatingTrackName = MutableStateFlow("")
  val translatingTrackName: StateFlow<String> = _translatingTrackName.asStateFlow()

  private val _translationProgress = MutableStateFlow(0f)
  val translationProgress: StateFlow<Float> = _translationProgress.asStateFlow()

  private val _translationStatus = MutableStateFlow("")
  val translationStatus: StateFlow<String> = _translationStatus.asStateFlow()

  private val _isGeneratingSubtitles = MutableStateFlow(false)
  val isGeneratingSubtitles: StateFlow<Boolean> = _isGeneratingSubtitles.asStateFlow()

  private val _subtitleGenerationProgress = MutableStateFlow(0f)
  val subtitleGenerationProgress: StateFlow<Float> = _subtitleGenerationProgress.asStateFlow()

  private val _subtitleGenerationStatus = MutableStateFlow("")
  val subtitleGenerationStatus: StateFlow<String> = _subtitleGenerationStatus.asStateFlow()

  private val _isRealtimeSubsActive = MutableStateFlow(false)
  val isRealtimeSubsActive: StateFlow<Boolean> = _isRealtimeSubsActive.asStateFlow()

  private val _realtimeSubsLanguage = MutableStateFlow("")
  val realtimeSubsLanguage: StateFlow<String> = _realtimeSubsLanguage.asStateFlow()

  private val _realtimeSubsProgress = MutableStateFlow(0f)
  val realtimeSubsProgress: StateFlow<Float> = _realtimeSubsProgress.asStateFlow()

  private val _realtimeSubsStatus = MutableStateFlow("")
  val realtimeSubsStatus: StateFlow<String> = _realtimeSubsStatus.asStateFlow()

  private val _torrentState = MutableStateFlow<TorrentStreamingState>(TorrentStreamingState.Idle)
  val torrentState: StateFlow<TorrentStreamingState> = _torrentState.asStateFlow()

  private var realtimeSrtFile: java.io.File? = null
  private var realtimeSubtitleTrackId: Int? = null
  private var realtimeTargetLanguage: String? = null
  private var realtimeSubtitleSessionId = 0L
  private var realtimePlaybackGeneration = -1L
  private val realtimeSubtitleUpdateMutex = Mutex()

  private var playlistMetadataJob: Job? = null
  private var controlsVisibleForPolling = false
  private var seekBarVisibleForPolling = false
  private val skippedSegments = mutableSetOf<SkipSegment>()
  private var chapterDerivedSegments: List<SkipSegment> = emptyList()
  private var introDbSegments: List<app.gyrolet.mpvrx.repository.IntroDbSegment> = emptyList()
  private var introDbSourceKey: String = IntroSegmentProvider.INTRO_DB.sourceKey
  private var introLookupJob: Job? = null
  private var introLookupGeneration = 0L
  private val introKeywordPatterns =
    listOf(
      // English/general
      "intro",
      "opening",
      "opening theme",
      "theme song",
      "title song",
      "creditless opening",
      "clean opening",
      "cold open",
      "prologue",
      "prelude",
      "op",
      "op1",
      "op2",
      "op3",
      "ncop",
      "ncop1",
      "ncop2",
      "nco",
      // Japanese
      "オープニング",
      "オープニングテーマ",
      "主題歌",
      "主題歌op",
      "ノンクレジットop",
      "ノンテロップop",
      "ノンクレop",
      "前期op",
      "後期op",
      "冒頭",
    )

  private val outroKeywordPatterns =
    listOf(
      // English/general
      "outro",
      "ending",
      "ending theme",
      "end credits",
      "credits",
      "credit roll",
      "epilogue",
      "postlude",
      "ed",
      "ed1",
      "ed2",
      "ed3",
      "nced",
      "nced1",
      "nced2",
      "nce",
      "preview",
      "next episode",
      // Japanese
      "エンディング",
      "エンディングテーマ",
      "エンドロール",
      "次回予告",
      "ノンクレジットed",
      "ノンテロップed",
      "ノンクレed",
      "前期ed",
      "後期ed",
      "予告",
      "終幕",
    )

  private val recapKeywordPatterns =
    listOf(
      "recap",
      "summary",
      "story so far",
      "previously on",
      "last time",
      "digest",
      "catch up",
      "振り返り",
      "前回まで",
      "これまで",
      "総集編",
      "おさらい",
    )

  private val creditsKeywordPatterns =
    listOf(
      "credits",
      "end credits",
      "credit roll",
      "rolling credits",
      "staff roll",
      "エンドロール",
      "クレジット",
    )

  private val previewKeywordPatterns =
    listOf(
      "preview",
      "next episode",
      "next week on",
      "up next",
      "teaser",
      "次回予告",
      "予告",
      "次回",
    )

  private val _skipSegments = MutableStateFlow<List<SkipSegment>>(emptyList())
  val skipSegments: StateFlow<List<SkipSegment>> = _skipSegments.asStateFlow()

  @Volatile private var skipSegmentsSnapshot: List<SkipSegment> = emptyList()

  private val _currentSkippableSegment = MutableStateFlow<SkipSegment?>(null)
  val currentSkippableSegment: StateFlow<SkipSegment?> = _currentSkippableSegment.asStateFlow()
  private val _showSkipChipAuto = MutableStateFlow(false)
  val showSkipChipAuto: StateFlow<Boolean> = _showSkipChipAuto.asStateFlow()
  private var pendingIntroLookupTitle: String? = null

  private val _introDbStatus =
    MutableStateFlow(
      if (playerPreferences.enableIntroDb.get()) {
        IntroDbStatus()
      } else {
        IntroDbStatus(
          state = IntroDbStatusState.DISABLED,
          message = "Online skip markers are disabled",
        )
      },
    )
  val introDbStatus: StateFlow<IntroDbStatus> = _introDbStatus.asStateFlow()

  // Media Search / Autocomplete
  private val _mediaSearchResults =
    MutableStateFlow<List<app.gyrolet.mpvrx.repository.wyzie.WyzieTmdbResult>>(emptyList())
  val mediaSearchResults: StateFlow<List<app.gyrolet.mpvrx.repository.wyzie.WyzieTmdbResult>> =
    _mediaSearchResults
      .asStateFlow()

  private val _isSearchingMedia = MutableStateFlow(false)
  val isSearchingMedia: StateFlow<Boolean> = _isSearchingMedia.asStateFlow()

  // TV Show Details
  private val _selectedTvShow = MutableStateFlow<app.gyrolet.mpvrx.repository.wyzie.WyzieTvShowDetails?>(null)
  val selectedTvShow: StateFlow<app.gyrolet.mpvrx.repository.wyzie.WyzieTvShowDetails?> = _selectedTvShow.asStateFlow()

  private val _isFetchingTvDetails = MutableStateFlow(false)
  val isFetchingTvDetails: StateFlow<Boolean> = _isFetchingTvDetails.asStateFlow()

  // Season / Episode
  private val _selectedSeason = MutableStateFlow<app.gyrolet.mpvrx.repository.wyzie.WyzieSeason?>(null)
  val selectedSeason: StateFlow<app.gyrolet.mpvrx.repository.wyzie.WyzieSeason?> = _selectedSeason.asStateFlow()

  private val _seasonEpisodes = MutableStateFlow<List<app.gyrolet.mpvrx.repository.wyzie.WyzieEpisode>>(emptyList())
  val seasonEpisodes: StateFlow<List<app.gyrolet.mpvrx.repository.wyzie.WyzieEpisode>> = _seasonEpisodes.asStateFlow()

  private val _isFetchingEpisodes = MutableStateFlow(false)
  val isFetchingEpisodes: StateFlow<Boolean> = _isFetchingEpisodes.asStateFlow()

  private val _selectedEpisode = MutableStateFlow<app.gyrolet.mpvrx.repository.wyzie.WyzieEpisode?>(null)
  val selectedEpisode: StateFlow<app.gyrolet.mpvrx.repository.wyzie.WyzieEpisode?> = _selectedEpisode.asStateFlow()

  fun toggleOnlineSection() {
    _isOnlineSectionExpanded.value = !_isOnlineSectionExpanded.value
  }

  // Cache for video metadata to avoid re-extracting — LruCache handles bounds + thread-safety
  private val metadataCache = object : android.util.LruCache<String, Pair<String, String>>(100) {}
  private val playbackStateDispatcher = Dispatchers.Default.limitedParallelism(1)
  private val renderPrepDispatcher = Dispatchers.Default.limitedParallelism(1)
  private var autoCropJob: Job? = null
  private var autoCropReadinessJob: Job? = null
  private var autoCropAnalyzedGeneration = -1L
  private var autoCropApplied = false
  // Memory-only analysis cache. This is not playback history and does not enable auto-crop.
  private val autoCropResultCache = LruCache<String, AutoCropEdges>(AUTO_CROP_CACHE_CAPACITY)
  private val _autoCropState = MutableStateFlow(AutoCropState.IDLE)
  val autoCropState: StateFlow<AutoCropState> = _autoCropState.asStateFlow()

  private data class AutoCropSamples(
    val edges: List<AutoCropEdges>,
    val framesWereRotated: Boolean,
  )

  private sealed interface AutoCropAnalysisResult {
    data class Detected(
      val edges: AutoCropEdges,
    ) : AutoCropAnalysisResult

    data object NoBars : AutoCropAnalysisResult

    data object Unavailable : AutoCropAnalysisResult
  }

  private data class AutoCropMetadata(
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,
  )

  private fun updateMetadataCache(
    key: String,
    value: Pair<String, String>,
  ) {
    metadataCache.put(key, value)
  }

  // MPV-backed scalar state. Keep these initialized before any coroutine can read them.
  private val _paused = MutableStateFlow<Boolean?>(null)
  val paused: Boolean? get() = _paused.value

  private val _pos = MutableStateFlow<Int?>(null)
  val pos: Int? get() = _pos.value

  private val _duration = MutableStateFlow<Int?>(null)
  val duration: Int? get() = _duration.value

  private val _volumeBoostCap = MutableStateFlow<Int?>(null)
  private val volumeBoostCap: Int? get() = _volumeBoostCap.value

  private val _isMpvCoreReady = MutableStateFlow(false)
  private var mpvStateCollectorsJob: Job? = null

  // High-precision position and duration for smooth seekbar
  private val _precisePosition = MutableStateFlow(0f)
  val precisePosition = _precisePosition.asStateFlow()

  private val _preciseDuration = MutableStateFlow(0f)
  val preciseDuration = _preciseDuration.asStateFlow()

  private fun parseTracks(node: MPVNode?): List<TrackNode> {
    val fromNode = runCatching { node?.toObject<List<TrackNode>>(json) }.getOrNull()
    if (!fromNode.isNullOrEmpty()) {
      return fromNode
    }
    val trackCount = PlaybackSession.getPropertyInt("track-list/count") ?: 0
    if (trackCount <= 0) return emptyList()
    val fallbackList = mutableListOf<TrackNode>()
    for (i in 0 until trackCount) {
      val id = PlaybackSession.getPropertyInt("track-list/$i/id") ?: continue
      val type = PlaybackSession.getPropertyString("track-list/$i/type") ?: continue
      val title =
        PlaybackSession.getPropertyString("track-list/$i/title")
          ?: PlaybackSession.getPropertyString("track-list/$i/metadata/by-key/title")
          ?: PlaybackSession.getPropertyString("track-list/$i/metadata/by-key/TITLE")
      val lang =
        PlaybackSession.getPropertyString("track-list/$i/lang")
          ?: PlaybackSession.getPropertyString("track-list/$i/metadata/by-key/language")
          ?: PlaybackSession.getPropertyString("track-list/$i/metadata/by-key/lang")
      val selected = PlaybackSession.getPropertyBoolean("track-list/$i/selected")
      val external = PlaybackSession.getPropertyBoolean("track-list/$i/external")
      val default = PlaybackSession.getPropertyBoolean("track-list/$i/default")
      val forced = PlaybackSession.getPropertyBoolean("track-list/$i/forced")
      val codec = PlaybackSession.getPropertyString("track-list/$i/codec")
      val codecDesc = PlaybackSession.getPropertyString("track-list/$i/codec-desc")
      val hlsBitrate = PlaybackSession.getPropertyInt("track-list/$i/hls-bitrate")?.toLong()
      val programId = PlaybackSession.getPropertyInt("track-list/$i/program-id")?.toLong()
      val programIds =
        runCatching {
          PlaybackSession.getPropertyNode("track-list/$i/program-ids")?.toObject<List<Long>>(json)
        }.getOrNull()
      val demuxW = PlaybackSession.getPropertyInt("track-list/$i/demux-w")?.toLong()
      val demuxH = PlaybackSession.getPropertyInt("track-list/$i/demux-h")?.toLong()
      val demuxFps = PlaybackSession.getPropertyDouble("track-list/$i/demux-fps")
      val demuxBitrate = PlaybackSession.getPropertyInt("track-list/$i/demux-bitrate")?.toLong()
      val externalFilename = PlaybackSession.getPropertyString("track-list/$i/external-filename")
      val image = PlaybackSession.getPropertyBoolean("track-list/$i/image")
      val albumArt = PlaybackSession.getPropertyBoolean("track-list/$i/albumart")
      fallbackList.add(
        TrackNode(
          id = id,
          type = type,
          title = title,
          lang = lang,
          selected = selected,
          external = external,
          default = default,
          forced = forced,
          codec = codec,
          codecDesc = codecDesc,
          hlsBitrate = hlsBitrate,
          programId = programId,
          programIds = programIds,
          demuxW = demuxW,
          demuxH = demuxH,
          demuxFps = demuxFps,
          demuxBitrate = demuxBitrate,
          externalFilename = externalFilename,
          image = image,
          albumArt = albumArt,
        ),
      )
    }
    return fallbackList
  }

  // These MPV-backed state flows must be initialized before any init block collects them.
  private val allTracks: StateFlow<List<TrackNode>> =
    PlaybackSession.propNode["track-list"]
      .map { node -> parseTracks(node).toImmutableList() }
      .stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

  val subtitleTracks: StateFlow<List<TrackNode>> =
    allTracks
      .map { tracks -> tracks.filter { it.isSubtitle }.toImmutableList() }
      .stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

  val audioTracks: StateFlow<List<TrackNode>> =
    allTracks
      .map { tracks -> tracks.filter { it.isAudio }.toImmutableList() }
      .stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

  val videoQualityTracks: StateFlow<List<TrackNode>> =
    combine(allTracks, PlaybackSession.state) { tracks, session ->
      if (session.phase != PlaybackPhase.READY && session.phase != PlaybackPhase.BACKGROUND) {
        return@combine persistentListOf()
      }

      val qualityTracks = tracks
        .asSequence()
        .filter { track -> track.isVideo && !track.isAlbumArtwork }
        .distinctBy(TrackNode::id)
        .sortedWith(
          compareByDescending<TrackNode>(::videoQualityDimension)
            .thenByDescending(::videoPixelCount)
            .thenByDescending { track -> track.demuxFps ?: 0.0 }
            .thenByDescending { track -> track.effectiveBitrate ?: 0L },
        ).toList()
      if (qualityTracks.size <= 1 || !supportsOnlineVideoQualitySelection(session.currentItem)) {
        persistentListOf()
      } else {
        qualityTracks.toImmutableList()
      }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

  val showVideoQualitySelector: StateFlow<Boolean> =
    videoQualityTracks
      .map { tracks -> tracks.isNotEmpty() }
      .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  data class QualityDownloadRequest(
    val sourceUrl: String,
    val title: String,
    val formatSelector: String? = null,
    val mergeSeparateStreams: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val jellyfinItemId: String? = null,
    val fileExtension: String = "mkv",
  )

  fun canDownloadCurrentVideoQuality(): Boolean =
    currentYouTubeSource() != null || currentJellyfinVideoSource() != null

  fun qualityDownloadRequest(track: TrackNode): QualityDownloadRequest? {
    val itemTitle = PlaybackSession.state.value.currentItem?.title?.trim()?.takeIf(String::isNotBlank)
    val jellyfinSource = currentJellyfinVideoSource()
    if (jellyfinSource != null) {
      val qualityLabel = videoQualityLabel(track)
      val baseTitle = itemTitle ?: "Jellyfin"
      return QualityDownloadRequest(
        sourceUrl = jellyfinSource.sourceUrl,
        title = if (qualityLabel.isBlank()) baseTitle else "$qualityLabel - $baseTitle",
        headers = jellyfinSource.headers,
        jellyfinItemId = jellyfinSource.itemId,
        fileExtension = currentVideoContainerExtension(),
      )
    }

    val sourceUrl = currentYouTubeSource() ?: return null
    val selectedAudio =
      pairedYtdlTrack(track, TrackNode::isAudio)
        ?: allTracks.value.firstOrNull { candidate -> candidate.isAudio && candidate.isSelected }
    val downloadSelection = buildYtdlDownloadSelection(videoTrack = track, audioTrack = selectedAudio) ?: return null
    val fallbackTitle =
      runCatching { HttpUtils.extractYouTubeVideoId(Uri.parse(sourceUrl)) }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: "YouTube"
    val qualityLabel = videoQualityLabel(track)
    val baseTitle = itemTitle ?: fallbackTitle
    return QualityDownloadRequest(
      sourceUrl = sourceUrl,
      title = if (qualityLabel.isBlank()) baseTitle else "$qualityLabel - $baseTitle",
      formatSelector = downloadSelection.formatSelector,
      mergeSeparateStreams = downloadSelection.mergeSeparateStreams,
    )
  }

  private fun videoQualityLabel(track: TrackNode): String =
    buildList {
      videoQualityDimension(track).takeIf { it > 0L }?.let { dimension -> add("${dimension}p") }
      track.demuxFps?.takeIf { it > 0.0 }?.toInt()?.let { fps -> add("${fps}fps") }
      track.codec?.trim()?.takeIf(String::isNotBlank)?.uppercase(Locale.ROOT)?.let(::add)
    }.joinToString(" ")

  private data class JellyfinVideoSource(
    val sourceUrl: String,
    val headers: Map<String, String>,
    val itemId: String,
  )

  private fun currentJellyfinVideoSource(): JellyfinVideoSource? {
    val item = PlaybackSession.state.value.currentItem ?: return null
    val sourceUrl = item.playableUri.trim().takeIf(String::isNotBlank) ?: return null
    val uri = runCatching { Uri.parse(sourceUrl) }.getOrNull() ?: return null
    val videoSegmentIndex = uri.pathSegments.indexOfFirst { it.equals("Videos", ignoreCase = true) }
    val itemId = uri.pathSegments.getOrNull(videoSegmentIndex + 1)?.takeIf(String::isNotBlank) ?: return null
    val isStaticStream = runCatching { uri.getQueryParameter("static")?.equals("true", ignoreCase = true) }.getOrNull() == true
    val hasAuthentication =
      item.headers.any { (name, value) -> name.equals("X-Emby-Token", ignoreCase = true) && value.isNotBlank() } ||
        runCatching { !uri.getQueryParameter("api_key").isNullOrBlank() }.getOrDefault(false)
    return if (videoSegmentIndex >= 0 && isStaticStream && hasAuthentication) {
      JellyfinVideoSource(sourceUrl = sourceUrl, headers = item.headers, itemId = itemId)
    } else {
      null
    }
  }

  private fun currentVideoContainerExtension(): String {
    val format = PlaybackSession.getPropertyString("file-format")?.lowercase(Locale.ROOT).orEmpty()
    return when {
      "matroska" in format -> "mkv"
      "webm" in format -> "webm"
      "mp4" in format || "mov" in format -> "mp4"
      "mpegts" in format -> "ts"
      "avi" in format -> "avi"
      else -> "mkv"
    }
  }

  fun selectVideoQuality(track: TrackNode, expectedGeneration: Long) {
    val session = PlaybackSession.state.value
    if (session.generation != expectedGeneration ||
      session.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND) ||
      videoQualityTracks.value.none { candidate -> candidate == track }
    ) return

    if (currentItemRequiresYtdlp() && !MpvConfigOverridePolicy.isOwnedByMpvConf("ytdl-format")) {
      val selectedAudio = pairedYtdlTrack(track, TrackNode::isAudio)
        ?: allTracks.value.firstOrNull { candidate -> candidate.isAudio && candidate.isSelected }
      val selector = buildYtdlFormatSelector(videoTrack = track, audioTrack = selectedAudio)
      if (selector != null && host?.reloadCurrentYtdlFormat(selector) == true) return
    }

    val selectedProgramIds = track.effectiveProgramIds.toSet()
    val currentAudio = allTracks.value.firstOrNull { candidate -> candidate.isAudio && candidate.isSelected }
    val matchingAudio =
      allTracks.value
        .asSequence()
        .filter(TrackNode::isAudio)
        .filter { audioTrack -> audioTrack.effectiveProgramIds.any(selectedProgramIds::contains) }
        .sortedWith(
          compareByDescending<TrackNode> { candidate -> candidate.id == currentAudio?.id }
            .thenByDescending { candidate ->
              currentAudio?.effectiveLang?.equals(candidate.effectiveLang, ignoreCase = true) == true
            }.thenByDescending { candidate -> candidate.default == true },
        ).firstOrNull()
    PlaybackSession.selectVideoTracks(expectedGeneration, track.id, matchingAudio?.id)
  }

  fun selectAudioTrack(track: TrackNode) {
    if (getTrackSelectionId("aid") == track.id) {
      setTrackSelectionId("aid", null)
      return
    }

    if (currentItemRequiresYtdlp() &&
      !MpvConfigOverridePolicy.isOwnedByMpvConf("ytdl-format") &&
      ytdlFormatId(track) != null
    ) {
      val selectedVideo = pairedYtdlTrack(track, TrackNode::isVideo)
        ?: allTracks.value.firstOrNull { candidate -> candidate.isVideo && candidate.isSelected }
      val selector = buildYtdlFormatSelector(videoTrack = selectedVideo, audioTrack = track)
      if (selector != null && host?.reloadCurrentYtdlFormat(selector) == true) return
    }

    setTrackSelectionId("aid", track.id)
  }

  private fun currentItemRequiresYtdlp(): Boolean {
    val item = PlaybackSession.state.value.currentItem
    return sequenceOf(item?.originalUri, item?.playableUri)
      .filterNotNull()
      .any(YtdlpManager::requiresYtdlp)
  }

  private fun supportsOnlineVideoQualitySelection(item: PlaybackItem?): Boolean {
    item ?: return false
    val sources = sequenceOf(item.originalUri, item.playableUri)
      .map(String::trim)
      .filter(String::isNotBlank)
      .toList()
    if (sources.any(YtdlpManager::requiresYtdlp)) return true
    if (M3uPlaybackPolicy.looksLikeM3uForPlayback(item.playableUri, item.originalUri, item.title.orEmpty(), item.mimeType)) return true
    if (item.networkSource != null || sources.any { NetworkPlaybackUri.parse(it) != null || XtreamPlaybackUri.parse(it) != null }) return true
    return sources.any { source ->
      runCatching {
        val scheme = Uri.parse(source).scheme?.lowercase(Locale.ROOT)
        scheme != null && scheme in VIDEO_QUALITY_STREAM_SCHEMES
      }.getOrDefault(false)
    }
  }

  private fun currentYouTubeSource(): String? {
    if (!app.gyrolet.mpvrx.BuildConfig.ENABLE_YTDLP) return null
    val item = PlaybackSession.state.value.currentItem ?: return null
    return sequenceOf(item.originalUri, item.playableUri)
      .map(String::trim)
      .filter(String::isNotBlank)
      .firstOrNull { source ->
        runCatching { HttpUtils.isYouTubeUrl(Uri.parse(source)) }.getOrDefault(false)
      }
  }

  private fun pairedYtdlTrack(
    track: TrackNode,
    matchesType: (TrackNode) -> Boolean,
  ): TrackNode? {
    val programIds = track.effectiveProgramIds.toSet()
    val formatId = track.ytdlFormatId ?: return null
    if (programIds.isEmpty()) return null
    return allTracks.value.firstOrNull { candidate ->
      matchesType(candidate) &&
        candidate.ytdlFormatId == formatId &&
        candidate.effectiveProgramIds.any(programIds::contains)
    }
  }

  private fun buildYtdlFormatSelector(
    videoTrack: TrackNode?,
    audioTrack: TrackNode?,
  ): String? {
    val videoFormatId = videoTrack?.let(::ytdlFormatId)
    val audioFormatId = audioTrack?.let(::ytdlFormatId)
    val videoSelector =
      when {
        videoFormatId != null -> videoFormatId
        videoTrack != null && videoQualityDimension(videoTrack) > 0L ->
          "bestvideo[height<=?${videoQualityDimension(videoTrack)}]"
        videoTrack != null -> "bestvideo"
        else -> null
      }

    return when {
      videoSelector != null && videoFormatId != null && videoFormatId == audioFormatId -> "$videoSelector/best"
      videoSelector != null -> "$videoSelector+${audioFormatId ?: "bestaudio"}/best"
      audioFormatId != null -> "$audioFormatId/best"
      else -> null
    }
  }

  private fun buildYtdlDownloadSelection(
    videoTrack: TrackNode,
    audioTrack: TrackNode?,
  ): YtdlDownloadSelection? {
    val videoFormatId = ytdlFormatId(videoTrack)
    val audioFormatId = audioTrack?.let(::ytdlFormatId)
    val videoSelector =
      videoFormatId
        ?: videoQualityDimension(videoTrack)
          .takeIf { it > 0L }
          ?.let { dimension -> "bestvideo[height<=?$dimension]" }
        ?: return null
    val isMuxedFormat = videoFormatId != null && videoFormatId == audioFormatId
    return if (isMuxedFormat) {
      YtdlDownloadSelection(formatSelector = "$videoSelector/best", mergeSeparateStreams = false)
    } else {
      YtdlDownloadSelection(
        formatSelector = "$videoSelector,${audioFormatId ?: "bestaudio"}",
        mergeSeparateStreams = true,
      )
    }
  }

  private fun ytdlFormatId(track: TrackNode): String? = track.ytdlFormatId

  private fun videoQualityDimension(track: TrackNode): Long {
    val width = track.demuxW?.takeIf { it > 0L }
    val height = track.demuxH?.takeIf { it > 0L }
    return when {
      width != null && height != null -> minOf(width, height)
      height != null -> height
      width != null -> width
      else -> QUALITY_HEIGHT_REGEX.find(track.effectiveTitle.orEmpty())?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
    }
  }

  private fun videoPixelCount(track: TrackNode): Long =
    (track.demuxW ?: 0L).coerceAtLeast(0L) * (track.demuxH ?: 0L).coerceAtLeast(0L)

  private data class YtdlDownloadSelection(
    val formatSelector: String,
    val mergeSeparateStreams: Boolean,
  )

  val isAudioOnly: StateFlow<Boolean> =
    combine(
      allTracks,
      PlaybackSession.propString["path"],
      PlaybackSession.propString["stream-open-filename"],
      PlaybackSession.state,
    ) { tracks, path, streamPath, session ->
      val currentPath = path?.takeIf { it.isNotBlank() } ?: streamPath
      val queuedItem = session.currentItem ?: return@combine false
      when (queuedItem.declaredMediaKind()) {
        DeclaredPlaybackMediaKind.VIDEO -> false
        DeclaredPlaybackMediaKind.AUDIO -> true
        DeclaredPlaybackMediaKind.UNKNOWN -> {
          if (session.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND) ||
            currentPath == null || currentPath !in listOf(queuedItem.originalUri, queuedItem.playableUri)
          ) {
            return@combine false
          }
          when {
            tracks.any { it.isVideo && !it.isAlbumArtwork } -> false
            currentPath.fileExtension() in FileTypeUtils.VIDEO_EXTENSIONS -> false
            currentPath.fileExtension() in FileTypeUtils.AUDIO_EXTENSIONS -> true
            else -> tracks.any { it.isAudio }
          }
        }
      }
    }.distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val hasAlbumArt: StateFlow<Boolean> =
    allTracks
      .map { tracks -> tracks.any { it.isAlbumArtwork } }
      .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val chapters: StateFlow<List<dev.vivvvek.seeker.Segment>> =
    PlaybackSession.propNode["chapter-list"]
      .map { node ->
        runCatching { node?.toObject<List<ChapterNode>>(json) }.getOrNull()?.map { it.toSegment() }?.toImmutableList()
          ?: persistentListOf()
      }.stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

  private fun bookmarkMediaId(item: PlaybackItem?): String? = item?.audiobook?.let {
    app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity.audiobookMediaId(it.bookId)
  } ?: item?.stableId

  val bookmarkMediaId: StateFlow<String?> = PlaybackSession.state.map { bookmarkMediaId(it.currentItem) }
    .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val playbackBookmarks: StateFlow<List<app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity>> =
    bookmarkMediaId.flatMapLatest { mediaId ->
      if (mediaId == null) flowOf(emptyList()) else playbackBookmarkDao.observe(mediaId).onStart { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  /**
   * Chapters that come from the media itself: mpv's chapter list, or the book's own track
   * chapters for audiobooks. Saved bookmarks are deliberately excluded, so anything deriving a
   * chapter name from this can't feed a bookmark's own title back into the next one.
   */
  private val sourceChapters: StateFlow<List<dev.vivvvek.seeker.Segment>> =
    combine(chapters, AudiobookPlayback.book, AudiobookPlayback.chapters, PlaybackSession.state) { native, book, bookChapters, state ->
      val item = state.currentItem
      if (item?.audiobook == null) native
      else if (book?.book?.id != item.audiobook.bookId) emptyList()
      else bookChapters.map { dev.vivvvek.seeker.Segment(it.title, it.bookStartMs / 1000f) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

  val playbackChapters: StateFlow<List<dev.vivvvek.seeker.Segment>> =
    combine(sourceChapters, playbackBookmarks, AudiobookPlayback.book, PlaybackSession.state) { sourceChapters, bookmarks, book, state ->
      val item = state.currentItem
      val mediaId = bookmarkMediaId(item)
      val currentBook = book?.takeIf { it.book.id == item?.audiobook?.bookId }
      val customChapters = bookmarks.filter { it.mediaId == mediaId }.mapNotNull { bookmark ->
        val positionMs = if (bookmark.bookTrackId == null) bookmark.positionMs else {
          currentBook?.takeIf { book -> book.tracks.any { it.id == bookmark.bookTrackId } }
            ?.positionInBook(bookmark.bookTrackId, bookmark.positionMs) ?: return@mapNotNull null
        }
        dev.vivvvek.seeker.Segment(bookmark.title, positionMs / 1000f)
      }
      val customStarts = customChapters.mapTo(hashSetOf()) { (it.start * 1000).toLong() }
      (customChapters + sourceChapters.distinctBy { (it.start * 1000).toLong() }.filter { (it.start * 1000).toLong() !in customStarts })
        .filter { it.start.isFinite() && it.start >= 0 }.sortedBy { it.start }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  fun seekAudioTo(positionSeconds: Float) {
    if (!positionSeconds.isFinite()) return
    if (PlaybackSession.state.value.currentItem?.audiobook != null) AudiobookPlayback.seekInBook((positionSeconds * 1000).toLong())
    else seekFromScrub(positionSeconds)
  }

  // Audio player UI state
  val albumArtBounds = MutableStateFlow<android.graphics.Rect?>(null)
  // The style and artwork/visualizer display choice are persisted via audioPreferences.
  val showVisualizerInAudioPlayer = MutableStateFlow(audioPreferences.showAudioVisualizer.get())
  private val restoredEqualizerBands = audioPreferences.equalizerBands.get()
  val equalizerState =
    MutableStateFlow(
      EqualizerState(
        isEnabled = audioPreferences.equalizerEnabled.get(),
        mode =
          runCatching { EqualizerMode.valueOf(audioPreferences.equalizerMode.get()) }
            .getOrDefault(EqualizerMode.DYNAMIC),
        currentPreset = EqualizerPreset.matching(restoredEqualizerBands),
        bandGains = restoredEqualizerBands,
        toneX = audioPreferences.equalizerToneX.get().coerceIn(-EQ_TONE_STEPS, EQ_TONE_STEPS),
        toneY = audioPreferences.equalizerToneY.get().coerceIn(-EQ_TONE_STEPS, EQ_TONE_STEPS),
        toneFocused = audioPreferences.equalizerFocused.get(),
        volumeBoostDb = audioPreferences.equalizerVolumeBoost.get().coerceIn(0, 10),
      ),
    )
  private var equalizerMpvDebounceJob: Job? = null

  data class LyricsUiState(
    val isLoading: Boolean = false,
    val isTranslating: Boolean = false,
    val isTranslationActive: Boolean = false,
    val targetLanguage: String = "en",
    val lyrics: app.gyrolet.mpvrx.domain.lyrics.Lyrics? = null,
    val originalLyrics: app.gyrolet.mpvrx.domain.lyrics.Lyrics? = null,
    val embeddedLyrics: app.gyrolet.mpvrx.domain.lyrics.Lyrics? = null,
    val onlineLyrics: app.gyrolet.mpvrx.domain.lyrics.Lyrics? = null,
    val activeLineIndex: Int = -1,
    val selectedSource: app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType = app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType.EMBEDDED,
    val availableSources: List<app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType> = emptyList(),
    /** Which provider the current online lyrics actually came from. */
    val onlineProvider: app.gyrolet.mpvrx.domain.lyrics.LyricsProvider? = null,
    /** What the picker is set to, or null for the automatic lookup. */
    val preferredProvider: app.gyrolet.mpvrx.domain.lyrics.LyricsProvider? = null,
    /** Every provider that has answered for this track, for the source sheet. */
    val fetchedProviders: Set<app.gyrolet.mpvrx.domain.lyrics.LyricsProvider> = emptySet(),
    /** Providers asked for this track that came back empty. */
    val missingProviders: Set<app.gyrolet.mpvrx.domain.lyrics.LyricsProvider> = emptySet(),
    /** Providers asked for this track that errored out or timed out. */
    val failedProviders: Set<app.gyrolet.mpvrx.domain.lyrics.LyricsProvider> = emptySet(),
    /** Whether this item may be looked up online at all (false for audiobooks). */
    val onlineEnabled: Boolean = false,
    val syncOffsetMs: Int = 0,
    val errorMessage: String? = null,
  )

  val lyricsUiState = MutableStateFlow(LyricsUiState())

  private val _mediaScopesUiState =
    MutableStateFlow(
      app.gyrolet.mpvrx.ui.player.scopes.MediaScopesUiState(
        analysisResolution = playerPreferences.mediaScopeAnalysisResolution.get().coerceIn(256, 720),
        frameRate = playerPreferences.mediaScopeFrameRate.get().coerceIn(5, 30),
      ),
    )
  val mediaScopesUiState = _mediaScopesUiState.asStateFlow()

  fun setMediaScopesOverlayVisible(visible: Boolean) {
    _mediaScopesUiState.update { state ->
      state.copy(overlayVisible = visible, expanded = state.expanded && visible)
    }
  }

  fun setMediaScopeTab(tab: app.gyrolet.mpvrx.ui.player.scopes.MediaScopeTab) {
    _mediaScopesUiState.update { it.copy(tab = tab) }
  }

  fun toggleMediaScopes(tab: app.gyrolet.mpvrx.ui.player.scopes.MediaScopeTab) {
    _mediaScopesUiState.update { state ->
      val closing = state.overlayVisible && state.tab == tab
      state.copy(
        overlayVisible = !closing,
        tab = tab,
        expanded = state.expanded && !closing,
      )
    }
  }

  fun setVideoScopeMode(mode: app.gyrolet.mpvrx.ui.player.scopes.VideoScopeMode) {
    _mediaScopesUiState.update { it.copy(videoMode = mode) }
  }

  fun toggleMediaScopesExpanded() {
    _mediaScopesUiState.update { it.copy(expanded = !it.expanded, overlayVisible = true) }
  }

  fun setMediaScopeAnalysisResolution(resolution: Int) {
    val bounded = resolution.coerceIn(256, 720)
    playerPreferences.mediaScopeAnalysisResolution.set(bounded)
    _mediaScopesUiState.update { it.copy(analysisResolution = bounded) }
  }

  fun setMediaScopeFrameRate(frameRate: Int) {
    val bounded = frameRate.coerceIn(5, 30)
    playerPreferences.mediaScopeFrameRate.set(bounded)
    _mediaScopesUiState.update { it.copy(frameRate = bounded) }
  }

  private data class LyricsLoadRequest(
    val path: String,
    val title: String,
    val artist: String,
    val durationSeconds: Int,
    val allowOnline: Boolean,
    val autoFetchOnline: Boolean,
    val album: String?,
    val isrc: String?,
  )

  private var lyricsLoadJob: Job? = null
  private var lyricsTranslateJob: Job? = null
  private var lastLyricsLoadRequest: LyricsLoadRequest? = null

  /** The file the lyrics state on screen belongs to, so a track change nobody saw is noticed. */
  private var lyricsStatePath: String? = null

  /**
   * Brings the lyrics back in line with what is actually playing.
   *
   * Lyrics are normally reloaded by [onVideoLoadCompleted], which only runs while the player
   * screen is alive. With the app in the background the queue keeps advancing, so on return
   * the panel still held the previous song's lyrics. This is called when the track changes
   * and when the screen resumes, and does nothing if the lyrics already belong to this file.
   */
  fun syncLyricsWithPlayback() {
    // A collapsed in-app sheet must not fetch lyrics for playback it is not showing.
    if (hostedInBrowser && !_isMpvCoreReady.value) return
    if (!isAudioOnly.value) return
    val path = currentLyricsPath() ?: return
    if (path == lyricsStatePath) return
    lyricsLoadJob?.cancel()
    lyricsTranslateJob?.cancel()
    lastLyricsLoadRequest = null
    lyricsUiState.value = LyricsUiState()
    loadLyricsForCurrentTrack()
  }

  fun setEqualizerEnabled(enabled: Boolean) {
    equalizerState.value = equalizerState.value.copy(isEnabled = enabled)
    audioPreferences.equalizerEnabled.set(enabled)
    applyEqualizerMpvFilters(immediate = true)
  }

  fun setEqualizerMode(mode: EqualizerMode) {
    if (equalizerState.value.mode == mode) return
    equalizerState.value = equalizerState.value.copy(mode = mode)
    audioPreferences.equalizerMode.set(mode.name)
    applyEqualizerMpvFilters(immediate = true)
  }

  fun applyEqualizerPreset(preset: EqualizerPreset) {
    if (preset == EqualizerPreset.CUSTOM) return
    equalizerState.value =
      equalizerState.value.copy(
        currentPreset = preset,
        bandGains = preset.gains,
      )
    audioPreferences.equalizerBands.set(preset.gains)
    applyEqualizerMpvFilters(immediate = true)
  }

  fun setEqualizerBandGain(
    index: Int,
    gainDb: Float,
  ) {
    val currentGains = equalizerState.value.bandGains.toMutableList()
    if (index in currentGains.indices && currentGains[index] != gainDb) {
      currentGains[index] = gainDb.coerceIn(EQ_MIN_DB, EQ_MAX_DB)
      equalizerState.value =
        equalizerState.value.copy(
          currentPreset = EqualizerPreset.matching(currentGains),
          bandGains = currentGains,
        )
      audioPreferences.equalizerBands.set(currentGains)
      applyEqualizerMpvFilters(immediate = false)
    }
  }

  fun setEqualizerTone(
    x: Int,
    y: Int,
  ) {
    val boundedX = x.coerceIn(-EQ_TONE_STEPS, EQ_TONE_STEPS)
    val boundedY = y.coerceIn(-EQ_TONE_STEPS, EQ_TONE_STEPS)
    val current = equalizerState.value
    if (current.toneX == boundedX && current.toneY == boundedY) return
    equalizerState.value = current.copy(toneX = boundedX, toneY = boundedY)
    audioPreferences.equalizerToneX.set(boundedX)
    audioPreferences.equalizerToneY.set(boundedY)
    applyEqualizerMpvFilters(immediate = false)
  }

  fun setEqualizerToneFocused(focused: Boolean) {
    if (equalizerState.value.toneFocused == focused) return
    equalizerState.value = equalizerState.value.copy(toneFocused = focused)
    audioPreferences.equalizerFocused.set(focused)
    applyEqualizerMpvFilters(immediate = true)
  }

  private fun currentLyricsPath(): String? = PlaybackSession.state.value.currentItem?.originalUri?.takeIf(String::isNotBlank)
    ?: PlaybackSession.getPropertyString("path") ?: PlaybackSession.getPropertyString("stream-open-filename")

  /** Queue song metadata is stable across track changes; native/display tags can lag behind. */
  private fun currentLyricsTitle(): String =
    PlaybackSession.state.value.currentItem?.title?.takeIf { it.isNotBlank() }
      ?: PlaybackSession.getPropertyString("metadata/by-key/Title")?.takeIf { it.isNotBlank() }
      ?: currentMediaTitle.takeIf { it.isNotBlank() }
      ?: PlaybackSession.getPropertyString("media-title").orEmpty()

  private fun currentLyricsArtist(): String =
    PlaybackSession.state.value.currentItem?.artist?.takeIf { it.isNotBlank() }
      ?: PlaybackSession.getPropertyString("metadata/by-key/Artist")
      ?: PlaybackSession.getPropertyString("metadata/by-key/ARTIST")
      ?: PlaybackSession.getPropertyString("metadata/by-key/album_artist")
      ?: ""

  fun loadLyricsForCurrentTrack(
    forceRefresh: Boolean = false,
    titleOverride: String? = null,
    forceOnline: Boolean = false,
  ) {
    val session = PlaybackSession.state.value
    // Do not send the previous song's native tags to online providers during file loading.
    if (session.phase != PlaybackPhase.READY && session.phase != PlaybackPhase.BACKGROUND) return
    val allowOnline = session.currentItem?.audiobook == null
    val path = currentLyricsPath() ?: return
    if (path.isBlank()) return
    if (forceRefresh) lyricsRepository.invalidate(path, allowOnline)
    val generation = session.generation

    val title = titleOverride ?: currentLyricsTitle()
    val artist = currentLyricsArtist()
    val duration = session.currentItem?.durationSeconds?.takeIf { it > 0 }
      ?: PlaybackSession.getPropertyDouble("duration")?.toInt() ?: 0
    val album = PlaybackSession.getPropertyString("metadata/by-key/Album")
      ?: PlaybackSession.getPropertyString("metadata/by-key/album")
    val isrc = PlaybackSession.getPropertyString("metadata/by-key/isrc")
    val request = LyricsLoadRequest(
      path, title, artist, duration, allowOnline, audioPreferences.lyricsAutoFetchOnline.get(), album, isrc,
    )
    if (!forceRefresh && !forceOnline && request == lastLyricsLoadRequest) return
    lastLyricsLoadRequest = request
    val isNewTrack = lyricsStatePath != path
    lyricsStatePath = path

    lyricsUiState.value = if (isNewTrack) {
      LyricsUiState(isLoading = true, onlineEnabled = allowOnline)
    } else {
      lyricsUiState.value.copy(
        isLoading = true,
        errorMessage = null,
        syncOffsetMs = 0,
        onlineEnabled = allowOnline,
      )
    }

    // Cancel any in-flight lyrics load/translate for the previous track, otherwise a slow
    // fetch for the old song can resolve after the new song's fetch and overwrite it with
    // stale (previous track's) lyrics.
    lyricsLoadJob?.cancel()
    lyricsTranslateJob?.cancel()

    lyricsLoadJob = viewModelScope.launch(Dispatchers.IO) {
      val result = lyricsRepository.loadLyricsForTrack(
        mediaPath = path,
        title = title,
        artist = artist,
        durationSeconds = duration,
        forceRefresh = forceRefresh,
        allowOnline = allowOnline,
        forceOnline = forceOnline,
        album = album,
        isrc = isrc,
      )

      ensureActive()
      // The track may have changed again while this fetch was in-flight; only apply the
      // result if we're still on the same track (extra guard on top of job cancellation).
      val stillCurrentPath = currentLyricsPath()
      if (!PlaybackSession.isCurrentGeneration(generation) || stillCurrentPath != path) return@launch

      val activeIndex = app.gyrolet.mpvrx.utils.media.LyricsUtils.getActiveLineIndex(
        syncedLines = result.activeLyrics?.synced,
        positionMs = (precisePosition.value * 1000).toLong(),
        offsetMs = 0,
      )
      val autoTranslate = allowOnline && audioPreferences.lyricsAutoTranslate.get()
      val defaultTargetLang = audioPreferences.lyricsTargetLanguage.get().ifBlank { "en" }

      lyricsUiState.value = lyricsUiState.value.copy(
        isLoading = false,
        isTranslationActive = false,
        targetLanguage = defaultTargetLang,
        lyrics = result.activeLyrics,
        originalLyrics = result.activeLyrics,
        embeddedLyrics = result.embeddedLyrics,
        onlineLyrics = result.onlineLyrics,
        activeLineIndex = activeIndex,
        selectedSource = result.selectedSource,
        availableSources = result.availableSources,
        onlineProvider = result.onlineProvider,
        preferredProvider = result.preferredOnlineProvider,
        fetchedProviders = result.onlineByProvider.keys,
        missingProviders = result.missingProviders,
        failedProviders = result.failedProviders,
        onlineEnabled = allowOnline,
        syncOffsetMs = 0,
      )

      val hasSynced = result.activeLyrics?.synced?.isNotEmpty() == true

      if (autoTranslate && hasSynced) {
        translateLyrics(defaultTargetLang)
      }
    }
  }

  /**
   * The "Search Online" action behind an empty panel.
   *
   * The automatic load can come back empty because the query mpv publishes for
   * the track had not landed yet, and that empty answer sits in the cache. This
   * drops the cached answer and searches again, using the file name when the
   * metadata is still missing, so the first tap always asks the providers.
   */
  fun searchLyricsOnline() {
    val path = currentLyricsPath() ?: return
    loadLyricsForCurrentTrack(forceRefresh = true, titleOverride = trackSearchTitle(path), forceOnline = true)
  }

  /** What to search for when mpv has not published a title for [mediaPath] yet. */
  private fun trackSearchTitle(mediaPath: String): String {
    val fromMetadata = currentLyricsTitle().takeIf { it.isNotBlank() }
    if (!fromMetadata.isNullOrBlank()) return fromMetadata
    return mediaPath
      .substringAfterLast('/')
      .substringBefore('?')
      .substringBeforeLast('.')
      .replace(Regex("""[._]+"""), " ")
      .trim()
  }

  fun switchLyricsSource(sourceType: app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType) {
    val allowOnline = PlaybackSession.state.value.currentItem?.audiobook == null
    if (!allowOnline && sourceType == app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType.ONLINE) return
    val path = currentLyricsPath() ?: return
    if (path.isBlank()) return

    if (lyricsStatePath != path) return
    val current = lyricsUiState.value
    val autoTranslate = allowOnline && audioPreferences.lyricsAutoTranslate.get()
    val defaultTargetLang = audioPreferences.lyricsTargetLanguage.get().ifBlank { "en" }

    if (sourceType == app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType.ONLINE && current.onlineLyrics == null) {
      lyricsUiState.value = current.copy(isLoading = true)
      lyricsLoadJob?.cancel()
      lyricsTranslateJob?.cancel()
      val generation = PlaybackSession.state.value.generation
      lyricsLoadJob = viewModelScope.launch(Dispatchers.IO) {
        val title = currentLyricsTitle()
        val artist = currentLyricsArtist()
        val duration = PlaybackSession.state.value.currentItem?.durationSeconds?.takeIf { it > 0 }
          ?: PlaybackSession.getPropertyDouble("duration")?.toInt() ?: 0
        val album = PlaybackSession.getPropertyString("metadata/by-key/Album")
          ?: PlaybackSession.getPropertyString("metadata/by-key/album")

        val fetched = lyricsRepository.fetchOnlineLyrics(
          rawTitle = title,
          rawArtist = artist,
          durationSeconds = duration,
          provider = lyricsRepository.preferredProvider,
          album = album,
          mediaPath = path,
        )

        ensureActive()
        if (!PlaybackSession.isCurrentGeneration(generation) || currentLyricsPath() != path ||
          lyricsStatePath != path) return@launch

        val updated = lyricsRepository.mergeOnline(path, fetched, allowOnline)
        val online = updated?.onlineLyrics ?: fetched.lyrics
        val updatedSources = if (online != null) {
          (current.availableSources + app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType.ONLINE).distinct()
        } else {
          current.availableSources
        }
        val activeLyrics = online ?: current.embeddedLyrics
        val activeIndex = app.gyrolet.mpvrx.utils.media.LyricsUtils.getActiveLineIndex(
          syncedLines = activeLyrics?.synced,
          positionMs = (precisePosition.value * 1000).toLong(),
          offsetMs = current.syncOffsetMs,
        )
        val hasSynced = activeLyrics?.synced?.isNotEmpty() == true

        lyricsUiState.value = current.copy(
          isLoading = false,
          isTranslationActive = false,
          onlineLyrics = online,
          lyrics = activeLyrics,
          originalLyrics = activeLyrics,
          selectedSource = if (online != null) app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType.ONLINE else current.selectedSource,
          availableSources = updatedSources,
          onlineProvider = updated?.onlineProvider ?: fetched.provider,
          preferredProvider = updated?.preferredOnlineProvider ?: if (fetched.raced) null else fetched.provider,
          fetchedProviders = (updated?.onlineByProvider ?: fetched.byProvider).keys,
          missingProviders = updated?.missingProviders ?: fetched.missing,
          failedProviders = updated?.failedProviders ?: fetched.failed,
          activeLineIndex = activeIndex,
        )

        if (autoTranslate && hasSynced) {
          translateLyrics(defaultTargetLang)
        }
      }
      return
    }

    val updatedResult = lyricsRepository.switchSource(path, sourceType, allowOnline)
    if (updatedResult != null) {
      val activeLyrics = updatedResult.activeLyrics
      val activeIndex = app.gyrolet.mpvrx.utils.media.LyricsUtils.getActiveLineIndex(
        syncedLines = activeLyrics?.synced,
        positionMs = (precisePosition.value * 1000).toLong(),
        offsetMs = current.syncOffsetMs,
      )
      val hasSynced = activeLyrics?.synced?.isNotEmpty() == true

      lyricsUiState.value = current.copy(
        lyrics = activeLyrics,
        originalLyrics = activeLyrics,
        isTranslationActive = false,
        selectedSource = updatedResult.selectedSource,
        activeLineIndex = activeIndex,
        fetchedProviders = updatedResult.onlineByProvider.keys,
        missingProviders = updatedResult.missingProviders,
        failedProviders = updatedResult.failedProviders,
      )

      if (autoTranslate && hasSynced) {
        translateLyrics(defaultTargetLang)
      }
    }
  }

  /**
   * Moves the online half of the panel onto [provider], or back onto the
   * automatic lookup when it is null.
   *
   * An answer already fetched for this track is reused; only a source the
   * track has not been asked yet costs a request, and a source that misses
   * falls through to the automatic lookup rather than blanking the panel.
   */
  fun switchLyricsProvider(provider: app.gyrolet.mpvrx.domain.lyrics.LyricsProvider?) {
    val allowOnline = PlaybackSession.state.value.currentItem?.audiobook == null
    if (!allowOnline) return
    val path = currentLyricsPath() ?: return
    if (path.isBlank() || lyricsStatePath != path) return
    val generation = PlaybackSession.state.value.generation

    val current = lyricsUiState.value
    val autoTranslate = audioPreferences.lyricsAutoTranslate.get()
    val defaultTargetLang = audioPreferences.lyricsTargetLanguage.get().ifBlank { "en" }

    lyricsLoadJob?.cancel()
    lyricsTranslateJob?.cancel()
    val cached = lyricsRepository.switchProvider(path, provider, allowOnline)
    if (cached != null && cached.onlineLyrics != null) {
      val activeLyrics = cached.onlineLyrics
      lyricsUiState.value = current.copy(
        isLoading = false,
        lyrics = activeLyrics,
        originalLyrics = activeLyrics,
        onlineLyrics = activeLyrics,
        isTranslationActive = false,
        selectedSource = app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType.ONLINE,
        onlineProvider = cached.onlineProvider,
        preferredProvider = cached.preferredOnlineProvider,
        fetchedProviders = cached.onlineByProvider.keys,
        missingProviders = cached.missingProviders,
        failedProviders = cached.failedProviders,
        activeLineIndex = currentActiveLineIndex(activeLyrics, current.syncOffsetMs),
        availableSources = (current.availableSources + app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType.ONLINE)
          .distinct(),
        errorMessage = null,
      )
      if (autoTranslate && activeLyrics.synced?.isNotEmpty() == true) translateLyrics(defaultTargetLang)
      return
    }

    lyricsUiState.value = current.copy(isLoading = true, errorMessage = null, preferredProvider = provider)
    lyricsLoadJob = viewModelScope.launch(Dispatchers.IO) {
      val title = currentLyricsTitle()
      val artist = currentLyricsArtist()
      val duration = PlaybackSession.state.value.currentItem?.durationSeconds?.takeIf { it > 0 }
        ?: PlaybackSession.getPropertyDouble("duration")?.toInt() ?: 0
      val album = PlaybackSession.getPropertyString("metadata/by-key/Album")
        ?: PlaybackSession.getPropertyString("metadata/by-key/album")

      val fetched = lyricsRepository.fetchOnlineLyrics(
        rawTitle = title,
        rawArtist = artist,
        durationSeconds = duration,
        provider = provider,
        album = album,
        mediaPath = path,
      )

      ensureActive()
      if (!PlaybackSession.isCurrentGeneration(generation) || currentLyricsPath() != path) return@launch

      val updated = lyricsRepository.mergeOnline(path, fetched, allowOnline)
      val online = updated?.onlineLyrics ?: fetched.lyrics
      val activeLyrics = online ?: current.embeddedLyrics
      val hasSynced = activeLyrics?.synced?.isNotEmpty() == true
      val answered = (updated?.onlineByProvider ?: fetched.byProvider).keys
      val failed = updated?.failedProviders ?: ((current.failedProviders - answered - fetched.missing) + fetched.failed)
      val missing = updated?.missingProviders ?: ((current.missingProviders - answered - fetched.failed) + fetched.missing)

      lyricsUiState.value = current.copy(
        isLoading = false,
        isTranslationActive = false,
        onlineLyrics = online,
        lyrics = activeLyrics,
        originalLyrics = activeLyrics,
        selectedSource = if (online == null) {
          current.selectedSource
        } else {
          app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType.ONLINE
        },
        availableSources = if (online == null) {
          current.availableSources
        } else {
          (current.availableSources + app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType.ONLINE).distinct()
        },
        onlineProvider = updated?.onlineProvider ?: fetched.provider,
        preferredProvider = updated?.preferredOnlineProvider ?: if (fetched.raced) null else fetched.provider,
        fetchedProviders = answered,
        missingProviders = missing,
        failedProviders = failed,
        activeLineIndex = currentActiveLineIndex(activeLyrics, current.syncOffsetMs),
        errorMessage = if (online == null && provider != null) {
          appContext.getString(R.string.lyrics_provider_not_found, provider.label)
        } else {
          null
        },
      )

      if (autoTranslate && hasSynced) translateLyrics(defaultTargetLang)
    }
  }

  private fun currentActiveLineIndex(
    lyrics: app.gyrolet.mpvrx.domain.lyrics.Lyrics?,
    offsetMs: Int,
  ): Int = app.gyrolet.mpvrx.utils.media.LyricsUtils.getActiveLineIndex(
    syncedLines = lyrics?.synced,
    positionMs = (precisePosition.value * 1000).toLong(),
    offsetMs = offsetMs,
  )

  fun adjustLyricsSyncOffset(deltaMs: Int) {
    val newOffset = lyricsUiState.value.syncOffsetMs + deltaMs
    lyricsUiState.value = lyricsUiState.value.copy(syncOffsetMs = newOffset)
    updateLyricsActiveLine()
  }

  fun resetLyricsSyncOffset() {
    lyricsUiState.value = lyricsUiState.value.copy(syncOffsetMs = 0)
    updateLyricsActiveLine()
  }

  fun updateLyricsActiveLine() {
    val state = lyricsUiState.value
    val synced = state.lyrics?.synced ?: return
    val posMs = (precisePosition.value * 1000).toLong()
    val index = app.gyrolet.mpvrx.utils.media.LyricsUtils.getActiveLineIndex(synced, posMs, state.syncOffsetMs)
    if (index != state.activeLineIndex) {
      lyricsUiState.value = state.copy(activeLineIndex = index)
    }
  }

  fun translateLyrics(targetLang: String? = null) {
    // Play builds omit translation: it relies on Google Translate's unofficial web endpoint.
    if (app.gyrolet.mpvrx.BuildConfig.IS_PLAY_STORE_BUILD) return
    val current = lyricsUiState.value
    val baseLyrics = current.originalLyrics ?: current.lyrics ?: return
    val lang = targetLang ?: audioPreferences.lyricsTargetLanguage.get().ifBlank { "en" }
    val path = currentLyricsPath() ?: return
    if (lyricsStatePath != path) return
    val generation = PlaybackSession.state.value.generation

    audioPreferences.lyricsAutoTranslate.set(true)
    audioPreferences.lyricsTargetLanguage.set(lang)

    lyricsUiState.value = current.copy(
      isTranslating = true,
      targetLanguage = lang,
      originalLyrics = current.originalLyrics ?: current.lyrics,
      errorMessage = null,
    )

    lyricsTranslateJob?.cancel()
    lyricsTranslateJob = viewModelScope.launch(Dispatchers.IO) {
      val outcome = lyricsTranslationService.translateLyrics(
        lyrics = baseLyrics,
        targetLanguage = lang,
        cacheKey = path,
      )

      // Bail out if the track changed while translation was in-flight, so a slow
      // translation for the previous song can't overwrite the new song's lyrics.
      ensureActive()
      if (!PlaybackSession.isCurrentGeneration(generation) || currentLyricsPath() != path ||
        lyricsStatePath != path) return@launch

      if (!outcome.isSuccessful) {
        lyricsUiState.value = lyricsUiState.value.copy(
          isTranslating = false,
          isTranslationActive = false,
          lyrics = baseLyrics,
          errorMessage = appContext.getString(R.string.lyrics_translation_failed),
        )
        return@launch
      }

      val translated = outcome.lyrics
      val activeIndex = app.gyrolet.mpvrx.utils.media.LyricsUtils.getActiveLineIndex(
        syncedLines = translated.synced,
        positionMs = (precisePosition.value * 1000).toLong(),
        offsetMs = current.syncOffsetMs,
      )
      lyricsUiState.value = lyricsUiState.value.copy(
        isTranslating = false,
        isTranslationActive = true,
        lyrics = translated,
        activeLineIndex = activeIndex,
        errorMessage =
          if (outcome.isComplete) null else appContext.getString(R.string.lyrics_translation_partial),
      )
    }
  }

  fun showOriginalLyrics() {
    audioPreferences.lyricsAutoTranslate.set(false)
    lyricsTranslateJob?.cancel()
    val current = lyricsUiState.value
    val original = current.originalLyrics ?: current.lyrics ?: return
    val activeIndex = app.gyrolet.mpvrx.utils.media.LyricsUtils.getActiveLineIndex(
      syncedLines = original.synced,
      positionMs = (precisePosition.value * 1000).toLong(),
      offsetMs = current.syncOffsetMs,
    )
    lyricsUiState.value = current.copy(
      isTranslating = false,
      isTranslationActive = false,
      lyrics = original,
      activeLineIndex = activeIndex,
      errorMessage = null,
    )
  }

  fun setEqualizerVolumeBoost(db: Int) {
    if (equalizerState.value.volumeBoostDb != db) {
      val bounded = db.coerceIn(0, 10)
      equalizerState.value = equalizerState.value.copy(volumeBoostDb = bounded)
      audioPreferences.equalizerVolumeBoost.set(bounded)
      applyEqualizerMpvFilters(immediate = false)
    }
  }

  fun applyEqualizerMpvFilters(immediate: Boolean = false) {
    val state = equalizerState.value
    // Changing MPV "af" filter property during playback causes MPV to recreate audio filter graph.
    // Debouncing while dragging prevents audio stutter/breaking.
    equalizerMpvDebounceJob?.cancel()
    if (immediate) {
      updateMpvAfProperty(state)
    } else {
      equalizerMpvDebounceJob =
        viewModelScope.launch(Dispatchers.Default) {
          delay(150)
          updateMpvAfProperty(state)
        }
    }
  }

  private fun getCustomMpvAf(): String {
    val confFile = File(appContext.filesDir, "mpv.conf")
    val text = if (confFile.exists()) {
      runCatching { confFile.readText() }.getOrDefault("")
    } else {
      advancedPreferences.mpvConf.get()
    }
    if (text.isBlank()) return ""
    val afFilters = text.lines()
      .map { it.trim() }
      .filter { !it.startsWith("#") && (it.startsWith("af=") || it.startsWith("af =") || it.startsWith("af-add=") || it.startsWith("af-add =") || it.startsWith("af-append=") || it.startsWith("af-append =")) }
      .map { line -> line.substringAfter("=").trim() }
      .filter { it.isNotBlank() }
    return afFilters.joinToString(",")
  }

  private fun updateMpvAfProperty(state: EqualizerState) {
    val filterList = mutableListOf<String>()

    // 1. Preserve custom af filters from mpv.conf
    val customAf = getCustomMpvAf()
    if (customAf.isNotBlank()) {
      filterList.add(customAf)
    }

    // 2. Dynamic Range Compression (DRC) / Night Mode
    if (audioPreferences.drcEnabled.get()) {
      filterList.add("lavfi=[acompressor=threshold=-20dB:ratio=4:attack=5:release=50:makeup=2]")
    }

    // 3. Volume Normalization (dynaudnorm)
    if (audioPreferences.volumeNormalization.get()) {
      filterList.add("dynaudnorm")
    }

    // 4. Reverse Stereo filter
    if (audioPreferences.audioChannels.get() == AudioChannels.ReverseStereo) {
      filterList.add("pan=[stereo|c0=c1|c1=c0]")
    }

    // 5. Equalizer filters (if enabled). One lavfi graph owns the whole curve so Android AudioFx
    // cannot apply a second, device-dependent EQ on top of it.
    if (state.isEnabled) {
      val filters = state.activeFilters()
      val outputGain = state.recommendedPreampDb() + state.volumeBoostDb
      if (kotlin.math.abs(outputGain) >= 0.01f) {
        filterList.add("volume=volume=${formatEqualizerNumber(outputGain)}dB")
      }
      if (filters.isNotEmpty()) {
        val graph =
          filters.joinToString(",") { filter ->
            val gain = formatEqualizerNumber(filter.gainDb)
            val q = formatEqualizerNumber(filter.q)
            when (filter.kind) {
              EqualizerFilterKind.BELL -> "equalizer=f=${filter.frequencyHz}:t=q:w=$q:g=$gain"
              EqualizerFilterKind.LOW_SHELF -> "bass=f=${filter.frequencyHz}:t=q:w=$q:g=$gain"
              EqualizerFilterKind.HIGH_SHELF -> "treble=f=${filter.frequencyHz}:t=q:w=$q:g=$gain"
            }
          }
        filterList.add("lavfi=[$graph]")
      }
    }

    val afString = filterList.joinToString(",")
    PlaybackSession.setPropertyString("af", afString)
  }

  private fun formatEqualizerNumber(value: Float): String =
    String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

  private val audioVisualizerToggleDebouncer =
    app.gyrolet.mpvrx.ui.player
      .ToggleDebouncer()

  fun toggleAudioVisualizer(): Boolean {
    if (!audioVisualizerToggleDebouncer.tryConsume()) return false
    val newValue = !showVisualizerInAudioPlayer.value
    showVisualizerInAudioPlayer.value = newValue
    audioPreferences.showAudioVisualizer.set(newValue)
    return true
  }

  fun getAudioPropertiesData(): List<app.gyrolet.mpvrx.ui.player.controls.components.sheets.AudioPropertyItem> {
    val title =
      currentMediaTitle.takeIf { it.isNotBlank() }
        ?: PlaybackSession.getPropertyString("metadata/by-key/Title")
        ?: PlaybackSession.getPropertyString("media-title")
        ?: "Unknown Title"

    val artist =
      PlaybackSession.getPropertyString("metadata/by-key/Artist")
        ?: PlaybackSession.getPropertyString("metadata/by-key/ARTIST")
        ?: PlaybackSession.getPropertyString("metadata/by-key/album_artist")
        ?: "Unknown Artist"

    val album =
      PlaybackSession.getPropertyString("metadata/by-key/Album")
        ?: PlaybackSession.getPropertyString("metadata/by-key/ALBUM")
        ?: "Unknown Album"

    val codec = PlaybackSession.getPropertyString("current-tracks/audio/codec")?.uppercase() ?: "Unknown"
    val samplerateInt = PlaybackSession.getPropertyInt("audio-params/samplerate") ?: 0
    val sampleRateStr =
      if (samplerateInt >
        0
      ) {
        String.format(java.util.Locale.US, "%.1f kHz", samplerateInt / 1000f)
      } else {
        "Unknown"
      }

    val channelsInt = PlaybackSession.getPropertyInt("audio-params/channel-count") ?: 0
    val channelsStr =
      when (channelsInt) {
        1 -> "Mono (1.0)"
        2 -> "Stereo (2.0)"
        6 -> "5.1 Surround"
        8 -> "7.1 Surround"
        else -> if (channelsInt > 0) "$channelsInt Channels" else "Unknown"
      }

    // Prefer the selected track's demux metadata. mpv's top-level audio-bitrate is optional
    // and can be unavailable during seeks/reconfigures, which needlessly logged E/mpv entries.
    val bitrateBitsPerSecond =
      audioTracks.value
        .firstOrNull(TrackNode::isSelected)
        ?.demuxBitrate
        ?.takeIf { it > 0L }
        ?: 0L
    val bitrateStr =
      if (bitrateBitsPerSecond > 0L) "${bitrateBitsPerSecond / 1000} kbps" else "Variable / Unknown"

    val path = PlaybackSession.getPropertyString("path") ?: PlaybackSession.getPropertyString("stream-open-filename") ?: ""
    val fileSizeStr =
      if (path.isNotBlank() && !path.startsWith("content://") && !path.startsWith("http")) {
        runCatching {
          val bytes = java.io.File(path.removePrefix("file://")).length()
          if (bytes > 0) String.format(java.util.Locale.US, "%.2f MB", bytes / (1024f * 1024f)) else ""
        }.getOrDefault("")
      } else {
        ""
      }

    val formatExt =
      path
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', "")
        .uppercase()

    return buildList {
      add(
        app.gyrolet.mpvrx.ui.player.controls.components.sheets
          .AudioPropertyItem("Title", title),
      )
      add(
        app.gyrolet.mpvrx.ui.player.controls.components.sheets
          .AudioPropertyItem("Artist", artist),
      )
      add(
        app.gyrolet.mpvrx.ui.player.controls.components.sheets
          .AudioPropertyItem("Album", album),
      )
      add(
        app.gyrolet.mpvrx.ui.player.controls.components.sheets.AudioPropertyItem(
          "Format / Codec",
          if (formatExt.isNotBlank()) "$formatExt ($codec)" else codec,
        ),
      )
      add(
        app.gyrolet.mpvrx.ui.player.controls.components.sheets
          .AudioPropertyItem("Sample Rate", sampleRateStr),
      )
      add(
        app.gyrolet.mpvrx.ui.player.controls.components.sheets
          .AudioPropertyItem("Bitrate", bitrateStr),
      )
      add(
        app.gyrolet.mpvrx.ui.player.controls.components.sheets
          .AudioPropertyItem("Channels", channelsStr),
      )
      if (fileSizeStr.isNotBlank()) {
        add(
          app.gyrolet.mpvrx.ui.player.controls.components.sheets
            .AudioPropertyItem("File Size", fileSizeStr),
        )
      }
      if (path.isNotBlank()) {
        add(
          app.gyrolet.mpvrx.ui.player.controls.components.sheets
            .AudioPropertyItem("File Location", path),
        )
      }
    }
  }

  // Audio state
  val maxVolume = (appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamMaxVolume(AudioManager.STREAM_MUSIC)
  val currentVolume = MutableStateFlow((appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC))
  val currentVolumePercent = MutableStateFlow(systemVolumeToPercent(currentVolume.value))

  // UI state
  private val _controlsShown = MutableStateFlow(false)
  val controlsShown: StateFlow<Boolean> = _controlsShown.asStateFlow()

  private val _controlsInteractionEpoch = MutableStateFlow(0L)
  val controlsInteractionEpoch: StateFlow<Long> = _controlsInteractionEpoch.asStateFlow()

  private val _seekBarShown = MutableStateFlow(false)
  val seekBarShown: StateFlow<Boolean> = _seekBarShown.asStateFlow()

  private val _areControlsLocked = MutableStateFlow(false)
  val areControlsLocked: StateFlow<Boolean> = _areControlsLocked.asStateFlow()

  val playerUpdate = MutableStateFlow<PlayerUpdates>(PlayerUpdates.None)
fun restartFromBeginning() {
  seekTo(0)
  runCatching { PlaybackSession.setPropertyBoolean("pause", false) }
}

  val isBrightnessSliderShown = MutableStateFlow(false)
  val isVolumeSliderShown = MutableStateFlow(false)
  val volumeSliderTimestamp = MutableStateFlow(0L)
  val brightnessSliderTimestamp = MutableStateFlow(0L)

  /** Dismiss gesture feedback without changing playback, volume, brightness or the control lock. */
  fun dismissAdjustmentOverlays(): Boolean {
    if (!isVolumeSliderShown.value && !isBrightnessSliderShown.value) return false
    isVolumeSliderShown.value = false
    isBrightnessSliderShown.value = false
    volumeSliderTimestamp.value = 0L
    brightnessSliderTimestamp.value = 0L
    return true
  }

  val currentBrightness =
    MutableStateFlow(
      runCatching {
        Settings.System
          .getFloat(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
          .normalize(0f, 255f, 0f, 1f)
      }.getOrElse { 0f },
    )

  val sheetShown = MutableStateFlow(Sheets.None)

  /**
   * Which sheet opened [Sheets.LyricsProvider], so dismissing it can go back to
   * that sheet instead of closing the panel outright. [Sheets.None] means the
   * picker was opened from the in-place lyrics, where there is nothing to go back to.
   */
  var lyricsProviderSheetOrigin = Sheets.None
  private val _bookmarkDraft = MutableStateFlow<app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity?>(null)
  val bookmarkDraft = _bookmarkDraft.asStateFlow()

  fun preparePlaybackBookmark(existing: app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity? = null): Boolean {
    val mediaId = bookmarkMediaId(PlaybackSession.state.value.currentItem) ?: return false
    if (existing != null) {
      if (existing.mediaId != mediaId) return false
      _bookmarkDraft.value = existing
    } else {
      val (item, positionMs) = PlaybackSession.bookmarkSnapshot() ?: return false
      if (bookmarkMediaId(item) != mediaId) return false
      val timelinePosition = item.audiobook?.let { info ->
        AudiobookPlayback.book.value?.takeIf { it.book.id == info.bookId }?.positionInBook(info.trackId, positionMs)
      } ?: positionMs
      val chapter = sourceChapters.value.lastOrNull { it.start * 1000 <= timelinePosition }?.name ?: currentMediaTitle
      val time = android.text.format.DateUtils.formatElapsedTime(timelinePosition / 1000)
      _bookmarkDraft.value = app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity(
        mediaId = mediaId, bookTrackId = item.audiobook?.trackId, positionMs = positionMs, title = "$chapter $time".trim(),
      )
    }
    return true
  }

  fun bookmarkPositionMs(bookmark: app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity): Long {
    val trackId = bookmark.bookTrackId ?: return bookmark.positionMs
    return AudiobookPlayback.book.value?.positionInBook(trackId, bookmark.positionMs) ?: bookmark.positionMs
  }

  suspend fun savePlaybackBookmark(bookmark: app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity, title: String) {
    require(title.isNotBlank())
    if (bookmark.id == 0L) playbackBookmarkDao.add(bookmark.copy(title = title.trim())) else playbackBookmarkDao.rename(bookmark.id, title.trim())
  }

  suspend fun deletePlaybackBookmark(bookmark: app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity) {
    playbackBookmarkDao.delete(bookmark.id)
  }

  fun seekToBookmark(bookmark: app.gyrolet.mpvrx.database.entities.PlaybackBookmarkEntity) {
    if (bookmark.mediaId != bookmarkMediaId(PlaybackSession.state.value.currentItem)) return
    if (bookmark.bookTrackId != null) AudiobookPlayback.seekToBookmark(bookmark.bookTrackId, bookmark.positionMs)
    else PlaybackSession.command("seek", (bookmark.positionMs / 1000.0).toString(), "absolute+exact")
  }

  fun seekToPlaybackChapter(chapter: dev.vivvvek.seeker.Segment) {
    if (!chapter.start.isFinite() || chapter.start < 0) return
    if (PlaybackSession.state.value.currentItem?.audiobook != null) AudiobookPlayback.seekInBook((chapter.start * 1000).toLong())
    else PlaybackSession.command("seek", chapter.start.toString(), "absolute+exact")
  }

  fun stepPlaybackChapter(direction: Int) {
    if (direction !in setOf(-1, 1)) return
    val (item, positionMs) = PlaybackSession.bookmarkSnapshot() ?: return
    val position = item.audiobook?.let { info ->
      AudiobookPlayback.book.value?.takeIf { it.book.id == info.bookId }?.positionInBook(info.trackId, positionMs)
    } ?: positionMs
    val chapters = playbackChapters.value
    val index = chapters.indexOfLast { it.start * 1000 <= position }
    val current = chapters.getOrNull(index)
    val target = if (direction < 0 && current != null && position - current.start * 1000 > 3000) current else chapters.getOrNull(index + direction)
    target?.let(::seekToPlaybackChapter)
  }

  fun sleepAtCurrentChapterEnd() {
    val progress = PlaybackSession.audiobookProgress() ?: return
    val book = AudiobookPlayback.book.value?.takeIf { it.book.id == progress.item.bookId } ?: return
    val track = book.tracks.firstOrNull { it.id == progress.item.trackId } ?: return
    val offset = book.positionInBook(track.id, 0)
    val position = offset + progress.positionMs
    val nextStart = playbackChapters.value.firstOrNull { it.start * 1000 > position }?.start?.times(1000)?.toLong()
    val localEnd = nextStart?.minus(offset)?.coerceAtMost(track.durationMs) ?: track.durationMs
    AudiobookPlayback.setTimer(null, localEnd)
  }
  val isPlaylistSwipeActive = MutableStateFlow(false)
  val playlistSwipeOffset = MutableStateFlow(0f)
  val panelShown = MutableStateFlow(Panels.None)
  private val _videoOpenAnimationState = MutableStateFlow(VideoOpenAnimationState())
  val videoOpenAnimationState: StateFlow<VideoOpenAnimationState> = _videoOpenAnimationState.asStateFlow()

  // Seek state — combined to allow atomic updates and reduce flow count
  data class SeekState(
    val text: String? = null,
    val amount: Int = 0,
    val isForwards: Boolean = false,
  )

  private val _seekState = MutableStateFlow(SeekState())
  val seekState: StateFlow<SeekState> = _seekState.asStateFlow()
  private var tvSeekFeedbackJob: Job? = null

  // Frame navigation
  private val _currentFrame = MutableStateFlow(0)
  val currentFrame: StateFlow<Int> = _currentFrame.asStateFlow()

  private val _totalFrames = MutableStateFlow(0)
  val totalFrames: StateFlow<Int> = _totalFrames.asStateFlow()

  private val _isFrameNavigationExpanded = MutableStateFlow(false)
  val isFrameNavigationExpanded: StateFlow<Boolean> = _isFrameNavigationExpanded.asStateFlow()

  private val _isSnapshotLoading = MutableStateFlow(false)
  val isSnapshotLoading: StateFlow<Boolean> = _isSnapshotLoading.asStateFlow()

  // Video zoom
  val videoZoom: StateFlow<Float> = PlaybackSession.videoZoom

  // Video aspect ratio (persisted in player preferences)
  private val _videoAspect = MutableStateFlow(VideoAspect.Fit)
  val videoAspect: StateFlow<VideoAspect> = _videoAspect.asStateFlow()

  // Current aspect ratio value (for custom ratios and tracking)
  private val _currentAspectRatio = MutableStateFlow(-1.0)
  val currentAspectRatio: StateFlow<Double> = _currentAspectRatio.asStateFlow()

  // Timer
  private var timerJob: Job? = null
  private val _remainingTime = MutableStateFlow(0)
  val remainingTime: StateFlow<Int> = _remainingTime.asStateFlow()

  // Media title for subtitle association
  var currentMediaTitle: String = ""
  private var lastAutoSelectedMediaTitle: String? = null
  private val _videoHash = MutableStateFlow<String?>(null)
  val videoHash: StateFlow<String?> = _videoHash.asStateFlow()
  private var videoHashJob: Job? = null

  @Volatile
  private var videoHashGeneration = 0

  // External subtitle tracking
  private val _externalSubtitles = mutableListOf<String>()
  val externalSubtitles: List<String> get() = _externalSubtitles.toList()

  // Mutex to prevent race-condition duplicates when scan adds multiple subtitle URIs concurrently
  private val subtitleAddMutex = Mutex()

  // Mapping from mpv internal path/URI to the original source URI (resolves deletion issues)
  private val mpvPathToUriMap = mutableMapOf<String, String>()

  fun calculateVideoHash(uri: Uri) {
    _videoHash.value = null
    val generation = ++videoHashGeneration
    videoHashJob?.cancel()
    videoHashJob =
      viewModelScope.launch(Dispatchers.IO) {
        val hash = SubtitleHashUtils.computeHash(appContext, uri)
        if (videoHashGeneration == generation) {
          _videoHash.value = hash
        }
        Log.d(TAG, "Computed video hash for $uri: ${hash ?: "unavailable"}")
      }
  }

  // Repeat and Shuffle state
  private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
  val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

  private val _shuffleEnabled = MutableStateFlow(false)
  val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

  // A-B Loop state — combined for atomic updates
  data class ABLoopState(
    val a: Double? = null,
    val b: Double? = null,
    val isExpanded: Boolean = false,
  )

  private val _abLoopState = MutableStateFlow(ABLoopState())
  val abLoopState: StateFlow<ABLoopState> = _abLoopState.asStateFlow()

  // Transform state (mirror + flip) — combined, saves 1 StateFlow object
  data class TransformState(
    val isMirrored: Boolean = false,
    val isVerticalFlipped: Boolean = false,
  )

  private val _transformState = MutableStateFlow(TransformState())
  val transformState: StateFlow<TransformState> = _transformState.asStateFlow()

  private val _hdrScreenMode = MutableStateFlow(initialHdrScreenMode())
  val hdrScreenMode: StateFlow<HdrScreenMode> = _hdrScreenMode.asStateFlow()

  private val _isGpuNextEnabled = MutableStateFlow(decoderPreferences.gpuNext.get())
  private val _isVulkanEnabled = MutableStateFlow(decoderPreferences.useVulkan.get())
  val isLinearHdrAvailable: StateFlow<Boolean> =
    combine(_isGpuNextEnabled, _isVulkanEnabled) { gpuNext, vulkan -> gpuNext && vulkan }
      .stateIn(viewModelScope, SharingStarted.Eagerly, _isGpuNextEnabled.value && _isVulkanEnabled.value)

  private val _isHdrScreenOutputPipelineReady = MutableStateFlow(isHdrScreenOutputAvailable())
  val isHdrScreenOutputPipelineReady: StateFlow<Boolean> = _isHdrScreenOutputPipelineReady.asStateFlow()

  private val _isHdrScreenOutputEnabled =
    MutableStateFlow(_isHdrScreenOutputPipelineReady.value && _hdrScreenMode.value != HdrScreenMode.OFF)
  val isHdrScreenOutputEnabled: StateFlow<Boolean> = _isHdrScreenOutputEnabled.asStateFlow()

  // ==================== Ambience Mode ======================================
  private val _isAmbientEnabled = MutableStateFlow(playerPreferences.isAmbientEnabled.get())
  val isAmbientEnabled: StateFlow<Boolean> = _isAmbientEnabled.asStateFlow()

  private val _ambientStyle = MutableStateFlow(playerPreferences.ambientStyle.get())
  val ambientStyle: StateFlow<AmbientStyle> = _ambientStyle.asStateFlow()

  private val _ambientBlurSamples = MutableStateFlow(playerPreferences.ambientBlurSamples.get())
  val ambientBlurSamples: StateFlow<Int> = _ambientBlurSamples.asStateFlow()

  private val _ambientMaxRadius = MutableStateFlow(playerPreferences.ambientMaxRadius.get())
  val ambientMaxRadius: StateFlow<Float> = _ambientMaxRadius.asStateFlow()

  private val _ambientGlowIntensity = MutableStateFlow(playerPreferences.ambientGlowIntensity.get())
  val ambientGlowIntensity: StateFlow<Float> = _ambientGlowIntensity.asStateFlow()

  private val _ambientSatBoost = MutableStateFlow(playerPreferences.ambientSatBoost.get())
  val ambientSatBoost: StateFlow<Float> = _ambientSatBoost.asStateFlow()

  private val _ambientVignetteStrength = MutableStateFlow(playerPreferences.ambientVignetteStrength.get())
  val ambientVignetteStrength: StateFlow<Float> = _ambientVignetteStrength.asStateFlow()

  private val _ambientWarmth = MutableStateFlow(playerPreferences.ambientWarmth.get())
  val ambientWarmth: StateFlow<Float> = _ambientWarmth.asStateFlow()

  private val _ambientFadeCurve = MutableStateFlow(playerPreferences.ambientFadeCurve.get())
  val ambientFadeCurve: StateFlow<Float> = _ambientFadeCurve.asStateFlow()

  private val _ambientOpacity = MutableStateFlow(playerPreferences.ambientOpacity.get())
  val ambientOpacity: StateFlow<Float> = _ambientOpacity.asStateFlow()

  private val _ambientGlowEdgeBlend =
    MutableStateFlow(playerPreferences.ambientGlowEdgeBlend.get().takeIf { it.isFinite() }?.coerceIn(0f, 0.1f) ?: 0f)
  val ambientGlowEdgeBlend: StateFlow<Float> = _ambientGlowEdgeBlend.asStateFlow()

  private val _ambientYouTubeEdgeBlend =
    MutableStateFlow(playerPreferences.ambientYouTubeEdgeBlend.get().takeIf { it.isFinite() }?.coerceIn(0f, 0.1f) ?: 0f)
  val ambientYouTubeEdgeBlend: StateFlow<Float> = _ambientYouTubeEdgeBlend.asStateFlow()

  @Volatile private var lastAmbientGeometry: AmbientGeometry? = null
  @Volatile private var isAmbientPipActive = false
  private var ambientDebounceJob: kotlinx.coroutines.Job? = null
  private val ambientScheduleLock = Any()
  private val ambientRenderLock = Any()
  private val ambientUpdateGeneration = AtomicLong()
  private val _isAmbientLifecycleActive = MutableStateFlow(false)
  val isAmbientLifecycleActive: StateFlow<Boolean> = _isAmbientLifecycleActive.asStateFlow()
  @Volatile private var ambientShaderFile: java.io.File? = null

  /**
  * Caches the [AmbientGlowShaderSpec] that was last compiled into a GLSL file.
   * When [updateAmbientStretch] is called but every parameter is identical to
   * the previously compiled spec, the expensive string-build + file-write +
   * MPV shader-reload cycle is skipped entirely.
   *
   * Using the spec data class (instead of the raw GLSL String) as the cache
   * key avoids allocating the multi-KB shader string and running
   * buildSpiralTapTable trig math before the early-return guard fires.
   *
   * @Volatile: written on renderPrepDispatcher (background), read and nulled on
   * the main thread in disableAmbientShader() / restartAmbientIfActive().
   */
  @Volatile private var lastCompiledSpec: AmbientGlowShaderSpec? = null

  /**
   * Tracks which fx-ambient spec (Cinema/Echo/Mirror + parameters) was last compiled so
   * we can skip recompilation when the parameters haven't changed.
   * @Volatile: written on renderPrepDispatcher, read/nulled on main thread.
   */
  @Volatile private var lastCompiledFxSpec: FxAmbientShaderSpec? = null
  @Volatile private var lastCompiledStyle: AmbientStyle? = null

  /**
   * Latest device thermal headroom reading ([0f] = at thermal limit, [1f] = cool).
   * Sampled every 10 s by the thermal-monitor coroutine and used to cap the ambient
   * shader sample budget before the SoC enters hard throttling.
   */
  @Volatile private var thermalHeadroom: Float = 1.0f

  private val _isAmbientBatterySaver = MutableStateFlow(playerPreferences.ambientBatterySaver.get())
  val isAmbientBatterySaver: StateFlow<Boolean> = _isAmbientBatterySaver.asStateFlow()
  private var ambientWasOnBattery = false
  private var ambientPreBatterySaverSamples: Int = 18
  private var ambientPreBatterySaverRadius: Float = 0.18f
  private var ambientPreBatterySaverIntensity: Float = 1.4f
  private var ambientPreBatterySaverSatBoost: Float = 1.2f
  private var ambientPreBatterySaverVignette: Float = 0.5f
  private var ambientPreBatterySaverWarmth: Float = 0.0f
  private var ambientPreBatterySaverFadeCurve: Float = 1.5f
  private var ambientPreBatterySaverOpacity: Float = 1.0f
  private var androidSystemInfoBridgeJob: Job? = null

  // ==================== Post-Processing ===================================
  private val _isPostProcessingEnabled = MutableStateFlow(playerPreferences.isPostProcessingEnabled.get())
  val isPostProcessingEnabled: StateFlow<Boolean> = _isPostProcessingEnabled.asStateFlow()

  private val _postProcessingPreset = MutableStateFlow(playerPreferences.postProcessingPreset.get())
  val postProcessingPreset: StateFlow<PostProcessingPreset> = _postProcessingPreset.asStateFlow()

  private val _postProcessingParams = MutableStateFlow(loadPostProcessingParamsFromPrefs())
  val postProcessingParams: StateFlow<PostProcessingParams> = _postProcessingParams.asStateFlow()

  @Volatile private var ppShaderFiles: List<java.io.File> = emptyList()
  private val ppShaderSeq = AtomicLong()
  private val ppScheduleLock = Any()
  private val ppRenderLock = Any()
  private val ppUpdateGeneration = AtomicLong()
  private var ppDebounceJob: Job? = null
  @Volatile private var lastCompiledPpSpec: Pair<PostProcessingPreset, PostProcessingParams>? = null

  // ==================== Custom Buttons ====================

  data class CustomButtonState(
    val id: String,
    val label: String,
    val isLeft: Boolean,
  )

  private val _customButtons = MutableStateFlow<List<CustomButtonState>>(emptyList())
  val customButtons: StateFlow<List<CustomButtonState>> = _customButtons.asStateFlow()
  private var customButtonsSetupJob: Job? = null
  private val customButtonsLoadMutex = Mutex()

  @Volatile
  private var isMpvReadyForCustomButtons = false

  @Volatile
  private var customButtonsScriptPaths: Map<CustomButtonScriptLanguage, String> = emptyMap()
  private val legacyCustomButtonsLoadedFlagProperty = "user-data/mpvrx/custombuttons_loaded"
  private val legacyCustomButtonsVersionProperty = "user-data/mpvrx/custombuttons_version"
  private val customButtonScriptTargets =
    listOf(
      CustomButtonScriptTarget(
        language = CustomButtonScriptLanguage.LUA,
        fileName = "custombuttons.lua",
        loadedFlagProperty = "user-data/mpvrx/custombuttons_lua_loaded",
        versionProperty = "user-data/mpvrx/custombuttons_lua_version",
      ),
      CustomButtonScriptTarget(
        language = CustomButtonScriptLanguage.JS,
        fileName = "custombuttons.js",
        loadedFlagProperty = "user-data/mpvrx/custombuttons_js_loaded",
        versionProperty = "user-data/mpvrx/custombuttons_js_version",
      ),
    )
  private val customButtonScriptTargetsByLanguage =
    customButtonScriptTargets.associateBy { it.language }

  private data class CustomButtonScriptTarget(
    val language: CustomButtonScriptLanguage,
    val fileName: String,
    val loadedFlagProperty: String,
    val versionProperty: String,
  )

  init {
    syncAmbientParamsForStyle(_ambientStyle.value)
    // Clean up any stale ambient shader files left in cacheDir from previous abnormal exits
    appContext.cacheDir.listFiles { _, name -> name.startsWith("ambient_") && name.endsWith(".glsl") }
      ?.forEach { runCatching { it.delete() } }
    // Immediately clear old lyrics when a track changes, even without a foreground Activity.
    // Allow mpv to finish publishing the next song's tags before the fallback lookup.
    viewModelScope.launch {
      PlaybackSession.state
        .map { it.currentItem?.stableId to it.generation }
        .distinctUntilChanged()
        .drop(1)
        .collectLatest {
          lyricsLoadJob?.cancel()
          lyricsTranslateJob?.cancel()
          lastLyricsLoadRequest = null
          lyricsStatePath = null
          lyricsUiState.value = LyricsUiState()
          delay(1_000)
          syncLyricsWithPlayback()
        }
    }
    viewModelScope.launch {
      decoderPreferences.gpuNext.changes().collect { enabled ->
        _isGpuNextEnabled.value = enabled
        reconcileHdrModeWithRenderer()
      }
    }
    viewModelScope.launch {
      decoderPreferences.useVulkan.changes().collect { enabled ->
        _isVulkanEnabled.value = enabled
        reconcileHdrModeWithRenderer()
      }
    }
    // Syncplay keeps one set of player bindings; the in-app sheet must not steal the player's.
    if (!hostedInBrowser) {
      syncplayManager.playbackStateProvider = { currentSyncplayPlaybackState() }
      syncplayManager.fileInfoProvider = { currentSyncplayFileInfo() }
      syncplayManager.onRemotePause = { shouldPause ->
        viewModelScope.launch(playbackStateDispatcher) {
          val currentlyPaused = PlaybackSession.getPropertyBoolean("pause") ?: false
          if (currentlyPaused != shouldPause) {
            if (!shouldPause) {
              val focusGranted = withContext(Dispatchers.Main) { host?.requestAudioFocus() ?: false }
              if (!focusGranted) return@launch
            }
            PlaybackSession.setPropertyBoolean("pause", shouldPause)
            if (shouldPause) {
              withContext(Dispatchers.Main) { host?.abandonAudioFocus() }
            }
          }
        }
      }
      syncplayManager.onRemoteSeek = { pos ->
        viewModelScope.launch(Dispatchers.IO) {
          val currentPos = PlaybackSession.getPropertyDouble("time-pos") ?: 0.0
          if (kotlin.math.abs(currentPos - pos) > 0.75) {
            PlaybackSession.command("seek", pos.toString(), "absolute+exact")
          }
        }
      }
    }

    viewModelScope.launch(playbackStateDispatcher) {
      combine(
        playerPreferences.autoSkipIntro.changes(),
        playerPreferences.autoSkipOutro.changes(),
      ) { intro, outro -> intro to outro }
        .distinctUntilChanged()
        .collect {
          val phase = PlaybackSession.state.value.phase
          if (!_isMpvCoreReady.value || (phase != PlaybackPhase.READY && phase != PlaybackPhase.BACKGROUND)) return@collect
          val position = PlaybackSession.getPropertyDouble("time-pos") ?: return@collect
          maybeAutoSkipIntro(position)
        }
    }

    // Single adaptive polling loop for playback position.
    //  1. An event-driven collect on PlaybackSession.propInt["time-pos"]
    //  2. This polling loop via PlaybackSession.getPropertyDouble("time-pos")
    // Having both caused redundant StateFlow emissions and double recompositions of the
    // seek bar on every MPV property event.  The polling loop alone is sufficient:
    //  - It provides Double precision (vs integer from the observer)
    //  - It drives maybeAutoSkipIntro() which needs sub-second accuracy
    //  - The adaptive interval keeps CPU cost proportional to actual UI demand
    //
    // Intervals:
    //   50 ms  – seek bar / controls visible (smooth scrubbing)
    //   500 ms – uninterrupted playback (halved from original 250 ms to cut idle overhead)
    //   500 ms – paused
    viewModelScope.launch(playbackStateDispatcher) {
      while (isActive) {
        val playbackPhase = PlaybackSession.state.value.phase
        val hasActiveTimeline = playbackPhase == PlaybackPhase.READY || playbackPhase == PlaybackPhase.BACKGROUND
        if (!_isMpvCoreReady.value || !hasActiveTimeline) {
          // A collapsed in-app sheet keeps its state for the session; it must not keep waking up.
          if (hostedInBrowser && !_isMpvCoreReady.value) _isMpvCoreReady.first { it } else delay(250L)
          continue
        }
        runCatching {
          val time = PlaybackSession.getPropertyDouble("time-pos")
          if (time != null) {
            val posFloat = time.toFloat()
            // While a seek is in flight, the player's reported time-pos is still the pre-seek
            // position; overwriting it would snap the thumb backwards mid-gesture.
            if (_precisePosition.value != posFloat && !shouldHoldOptimisticPosition(posFloat)) {
              _precisePosition.value = posFloat
              updateLyricsActiveLine()
            }
            maybeAutoSkipIntro(time)
          }
        }.onFailure { error ->
          if (isActive) {
            Log.w(TAG, "Playback position polling failed", error)
          }
        }
        val intervalMs =
          when {
            paused == true -> 1000L // Reduce polling frequency when paused to conserve CPU/battery
            // 100 ms is below the threshold where seek-bar motion reads as stepped, while halving
            // the JNI reads and state emissions a 50 ms loop caused while controls are visible.
            seekBarVisibleForPolling || controlsVisibleForPolling -> 100L
            else -> 500L
          }
        delay(intervalMs)
      }
    }

    // ── Thermal monitor ────────────────────────────────────────────────────────
    // Sample Android's PowerManager.getThermalHeadroom() every 10 s during active
    // playback.  When thermal margin shrinks the ambient shader sample budget is
    // capped automatically, preventing the device from entering hard CPU/GPU throttling
    // which would otherwise manifest as dropped frames and accelerated battery drain.
    viewModelScope.launch(playbackStateDispatcher) {
      while (isActive) {
        if (_isMpvCoreReady.value && paused == false) {
          val newHeadroom = ThermalMonitor.getHeadroom(appContext)
          if (kotlin.math.abs(newHeadroom - thermalHeadroom) > 0.08f) {
            thermalHeadroom = newHeadroom
            if (_isAmbientEnabled.value) {
              // Invalidate both spec caches so the new budget cap is applied on the
              // next scheduled ambient update.
              lastCompiledSpec = null
              lastCompiledFxSpec = null
        lastCompiledStyle = null
              scheduleAmbientUpdate()
            }
            Log.d(TAG, "Thermal headroom updated: %.2f".format(newHeadroom))
          }
        }
        delay(10_000L)
      }
    }

    // Update precise duration when the integer duration changes (avoid polling)
    viewModelScope.launch(playbackStateDispatcher) {
      _duration.collect { observedDuration ->
        if (!_isMpvCoreReady.value) return@collect
        if (observedDuration == null || observedDuration <= 0) {
          _preciseDuration.value = 0f
          return@collect
        }
        val dur = PlaybackSession.getPropertyDouble("duration")
        if (dur != null && dur > 0) {
          _preciseDuration.value = dur.toFloat()
          // chapter-list and duration are independent mpv properties. A chapter list can arrive
          // first (or be identical to the previous file), so always derive its markers again once
          // the new duration is known.
          refreshChapterDerivedSegments(chapters.value)
          checkPendingIntroLookup()
          syncplayManager.updateFileInfo(currentSyncplayFileInfo())

          // --- AMBIENT FIX: Adapt shader to new file dimensions by @Chinna95P ---
          if (_isAmbientEnabled.value) {
            lastAmbientGeometry = null
            // Slight delay ensures MPV's video-params (w/h/crop) are fully populated.
            scheduleAmbientUpdate(250)
          }
          // --------------------------------------------------------
        }
      }
    }

    // Crop, aspect and ambient work belongs to the video player's surface, never to the in-app sheet.
    if (!hostedInBrowser) viewModelScope.launch {
      val activeGeneration =
        PlaybackSession.state
          .map { state ->
            state.generation.takeIf {
              state.surfaceAttached &&
                (state.phase == PlaybackPhase.READY || state.phase == PlaybackPhase.BACKGROUND)
            }
          }.distinctUntilChanged()

      combine(
        PlaybackSession.propString["video-crop"],
        activeGeneration,
        _isMpvCoreReady,
      ) { crop, generation, coreReady -> Triple(crop, generation, coreReady) }
        .distinctUntilChanged()
        .collect { (_, generation, coreReady) ->
          if (!coreReady || generation == null) {
            disableAmbientShader()
            return@collect
          }
          refreshStretchAspectAfterCropChange()
          restartAmbientIfActive()
        }
    }

    if (!hostedInBrowser) viewModelScope.launch {
      combine(
        PlaybackSession.propInt["osd-width"],
        PlaybackSession.propInt["osd-height"],
        PlaybackSession.propInt["video-out-params/dw"],
        PlaybackSession.propInt["video-out-params/dh"],
        PlaybackSession.propInt["video-out-params/rotate"],
      ) { _, _, _, _, _ -> Unit }.collect { onAmbientGeometryChanged() }
    }

    viewModelScope.launch {
      combine(
        playerPreferences.enableIntroDb.changes(),
        playerPreferences.introSegmentProvider.changes(),
      ) { enabled, provider -> enabled to provider }
        .distinctUntilChanged()
        .drop(1)
        .collect {
          currentMediaTitle.takeIf { it.isNotBlank() }?.let(::lookupIntroSegments)
        }
    }

    viewModelScope.launch(playbackStateDispatcher) {
      chapters
        .collect { chapterList ->
          refreshChapterDerivedSegments(chapterList)
        }
    }

    viewModelScope.launch(playbackStateDispatcher) {
      combine(
        playerPreferences.customIntroKeywordsEnabled.changes(),
        playerPreferences.customIntroKeywords.changes(),
        playerPreferences.customOutroKeywordsEnabled.changes(),
        playerPreferences.customOutroKeywords.changes(),
      ) { _, _, _, _ -> }.collect {
        refreshChapterDerivedSegments(chapters.value)
      }
    }

    // Track selection is now handled by TrackSelector in PlayerActivity

    // Restore repeat mode and shuffle state from preferences
    if (hostedInBrowser) {
      // The in-app sheet adopts the running queue instead of re-imposing the saved modes on it.
      val queue = PlaybackSession.queue.value
      _repeatMode.value = queue.repeatMode
      _shuffleEnabled.value = queue.shuffleEnabled
    } else {
      _repeatMode.value = playerPreferences.repeatMode.get()
      _shuffleEnabled.value = playerPreferences.shuffleEnabled.get()
      PlaybackSession.setRepeatMode(_repeatMode.value)
      PlaybackSession.setShuffleEnabled(_shuffleEnabled.value)
    }

    // Observe volume boost cap changes to enforce limits dynamically (in PiP)
    viewModelScope.launch(playbackStateDispatcher) {
      audioPreferences.volumeBoostCap.changes().collect { cap ->
        val maxVol = 100 + cap
        runCatching {
          PlaybackSession.setPropertyString("volume-max", maxVol.toString())

          // Clamp current volume if it exceeds the new limit
          val currentMpvVol = PlaybackSession.getPropertyInt("volume") ?: 100
          if (currentMpvVol > maxVol) {
            PlaybackSession.setPropertyInt("volume", maxVol)
          }
        }.onFailure { e ->
          Log.e(TAG, "Error setting volume-max: $maxVol", e)
        }
      }
    }
    // Observe audio effect changes to update mpv af filters
    viewModelScope.launch(playbackStateDispatcher) {
      combine(
        audioPreferences.volumeNormalization.changes(),
        audioPreferences.drcEnabled.changes(),
        audioPreferences.audioChannels.changes(),
      ) { _, _, _ -> }
        // Re-sending the unchanged filter chain to a playing core rebuilds it and audibly hiccups.
        .drop(if (hostedInBrowser) 1 else 0)
        .collect {
          if (!_isMpvCoreReady.value) return@collect
          applyEqualizerMpvFilters(immediate = true)
        }
    }

    // Embedded settings do not leave/resume the Activity. Apply these preference changes to
    // the active core as they happen, preserving the current session on the initial emission.
    viewModelScope.launch(playbackStateDispatcher) {
      audioPreferences.audioPitchCorrection.changes().distinctUntilChanged().drop(1).collect { enabled ->
        if (_isMpvCoreReady.value) PlaybackSession.setPropertyBoolean("audio-pitch-correction", enabled)
      }
    }
    viewModelScope.launch(playbackStateDispatcher) {
      subtitlesPreferences.overrideAssSubs.changes().distinctUntilChanged().drop(1).collect { force ->
        if (_isMpvCoreReady.value) {
          applySubtitleLayout(subtitlesPreferences.subPos.get(), force, subtitlesPreferences.secondarySubPos.get())
        }
      }
    }
    viewModelScope.launch(playbackStateDispatcher) {
      subtitlesPreferences.scaleByWindow.changes().distinctUntilChanged().drop(1).collect { enabled ->
        if (_isMpvCoreReady.value) {
          val value = if (enabled) "yes" else "no"
          PlaybackSession.setPropertyString("sub-scale-by-window", value)
          PlaybackSession.setPropertyString("sub-use-margins", value)
        }
      }
    }

    // Monitor duration and AB loop changes to automatically enable precise seeking
    viewModelScope.launch(playbackStateDispatcher) {
      combine(_duration, abLoopState) { duration, abLoop ->
        Pair(duration, abLoop)
      }.collect { (duration, abLoop) ->
        if (!_isMpvCoreReady.value) return@collect
        val videoDuration = duration ?: 0
        if (videoDuration > 0) {
          val isLoopActive = abLoop.a != null || abLoop.b != null
          val shouldUsePreciseSeeking = playerPreferences.usePreciseSeeking.get() || videoDuration < 120 || isLoopActive
          PlaybackSession.setPropertyString("hr-seek", if (shouldUsePreciseSeeking) "yes" else "no")
          PlaybackSession.setPropertyString("hr-seek-framedrop", if (shouldUsePreciseSeeking) "no" else "yes")
        }
      }
    }

    // Monitor precise seeking preference toggle dynamically
    viewModelScope.launch {
      playerPreferences.usePreciseSeeking.changes().collect { usePrecise ->
        if (!_isMpvCoreReady.value) return@collect
        val videoDuration = _duration.value ?: 0
        if (videoDuration > 0) {
          val isLoopActive = _abLoopState.value.a != null || _abLoopState.value.b != null
          val shouldUsePreciseSeeking = usePrecise || videoDuration < 120 || isLoopActive
          PlaybackSession.setPropertyString("hr-seek", if (shouldUsePreciseSeeking) "yes" else "no")
          PlaybackSession.setPropertyString("hr-seek-framedrop", if (shouldUsePreciseSeeking) "no" else "yes")
        }
      }
    }

    // Refresh custom buttons whenever their configuration changes. Their scripts live in the
    // shared core, so only the player screen that loaded them may rebuild them.
    if (!hostedInBrowser) viewModelScope.launch {
      playerPreferences.customButtons.changes().drop(1).collect {
        setupCustomButtons()
      }
    }

    // Observe ambient battery saver preference
    viewModelScope.launch {
      playerPreferences.ambientBatterySaver.changes().collect { enabled ->
        _isAmbientBatterySaver.value = enabled
        if (enabled && _isAmbientEnabled.value) {
          applyBatterySaverPolicy()
        } else if (!enabled && ambientWasOnBattery && _isAmbientEnabled.value) {
          restoreFromBatterySaver()
        }
      }
    }

    viewModelScope.launch {
      sheetShown.collect { shownSheet ->
        if (shownSheet == Sheets.Playlist) {
          refreshPlaylistItems(forceMetadata = true)
        } else {
          playlistMetadataJob?.cancel()
        }
        if (_isAmbientEnabled.value && _ambientStyle.value != AmbientStyle.YouTube) {
          scheduleAmbientUpdate(100)
        }
      }
    }
  }

  /**
   * Builds and loads the custom-button scripts, then publishes the Android system state to mpv.
   *
   * Called once the core exists rather than from `init`, because generating the scripts needs a
   * live mpv to activate them and [reloadCustomButtonsScript] needs the paths
   * [setupCustomButtons] produces. `PlayerActivity` calls this immediately after construction, so
   * running it here instead of at `init` removes one redundant pass — a JSON decode, an MD5 per
   * script, a `filesDir/scripts` write and a Main-thread hop — from Activity construction.
   */
  fun onMpvCoreInitialized() {
    _isMpvCoreReady.value = true
    scheduleAmbientUpdate(0)
    startMpvStateCollectors()
    isMpvReadyForCustomButtons = true
    setupCustomButtons()
    reloadCustomButtonsScript("mpv_core_initialized")
    startAndroidSystemInfoBridge()
  }

  /** Stops every ViewModel path that can read or write libmpv during native teardown. */
  fun onMpvCoreStopping() {
    stopRealtimeSubtitles(showToastMessage = false)
    cancelAutoCropAnalysis()
    disableAmbientShader()
    _isMpvCoreReady.value = false
    isMpvReadyForCustomButtons = false
    runCatching { syncplayManager.clearPlayerBindings() }
    mpvStateCollectorsJob?.cancel()
    mpvStateCollectorsJob = null
    androidSystemInfoBridgeJob?.cancel()
    androidSystemInfoBridgeJob = null
    customButtonsSetupJob?.cancel()
    _paused.value = null
    _pos.value = null
    _duration.value = null
    _volumeBoostCap.value = null
    _precisePosition.value = 0f
    _preciseDuration.value = 0f
  }

  @Volatile
  private var detachedPlaybackJob: Job? = null

  /**
   * Adopts the core [MediaPlaybackService] is playing, for the in-app music sheet. Unlike
   * [onMpvCoreInitialized] it loads no scripts and writes nothing to mpv, so opening the sheet can
   * never interrupt the audio that is already playing. The service keeps owning the session and
   * advances the queue; this only mirrors it, the way PlayerActivity's load callbacks would.
   */
  fun attachToDetachedPlayback() {
    if (!hostedInBrowser || detachedPlaybackJob?.isActive == true) return
    detachedPlaybackJob =
      viewModelScope.launch(playbackStateDispatcher) {
        // Seed the timeline before the polling loop wakes, so the sheet never opens at 0:00.
        PlaybackSession.getPropertyDouble("time-pos")?.let { _precisePosition.value = it.toFloat() }
        PlaybackSession.getPropertyDouble("duration")?.takeIf { it > 0.0 }?.let { _preciseDuration.value = it.toFloat() }
        _isMpvCoreReady.value = true
        startMpvStateCollectors()
        refreshPlaylistItems()
        launch {
          // The audio-only verdict needs the track list, which can land just after attaching.
          withTimeoutOrNull(2_000L) { isAudioOnly.first { it } }
          withContext(Dispatchers.Main) { syncLyricsWithPlayback() }
        }
        launch { PlaybackSession.queue.drop(1).collect { refreshPlaylistItems() } }
        launch {
          // Notification and widget shuffle toggles change the queue directly.
          PlaybackSession.queue
            .map { it.shuffleEnabled }
            .distinctUntilChanged()
            .collect { _shuffleEnabled.value = it }
        }
        val attachedState = PlaybackSession.state.value
        var startedGeneration = attachedState.generation
        var loadedGeneration =
          attachedState.generation.takeIf {
            attachedState.phase == PlaybackPhase.READY || attachedState.phase == PlaybackPhase.BACKGROUND
          }
        PlaybackSession.state
          .map { it.generation to it.phase }
          .distinctUntilChanged()
          .collect { (generation, phase) ->
            when (phase) {
              PlaybackPhase.LOADING ->
                if (generation != startedGeneration) {
                  startedGeneration = generation
                  withContext(Dispatchers.Main) { onVideoLoadStarted() }
                }
              PlaybackPhase.READY, PlaybackPhase.BACKGROUND ->
                if (generation != loadedGeneration) {
                  startedGeneration = generation
                  loadedGeneration = generation
                  refreshPlaylistItems()
                  withContext(Dispatchers.Main) { syncLyricsWithPlayback() }
                }
              else -> Unit
            }
          }
      }
  }

  /** Stops reading the core when the in-app sheet leaves; its UI state stays for the next open. */
  fun detachFromDetachedPlayback() {
    if (!hostedInBrowser) return
    detachedPlaybackJob?.cancel()
    detachedPlaybackJob = null
    // Queued behind any attach work on the same single-threaded dispatcher, so it always wins.
    viewModelScope.launch(playbackStateDispatcher) {
      if (detachedPlaybackJob != null) return@launch
      _isMpvCoreReady.value = false
      mpvStateCollectorsJob?.cancel()
      mpvStateCollectorsJob = null
    }
  }

  private fun startMpvStateCollectors() {
    if (mpvStateCollectorsJob?.isActive == true) return
    mpvStateCollectorsJob =
      viewModelScope.launch(playbackStateDispatcher) {
        launch { PlaybackSession.propBoolean["pause"].collect { _paused.value = it } }
        launch { PlaybackSession.propInt["time-pos"].collect { _pos.value = it } }
        launch { PlaybackSession.propInt["duration"].collect { _duration.value = it } }
        launch { PlaybackSession.propInt["volume-max"].collect { _volumeBoostCap.value = it } }
        launch {
          var wasSeeking = false
          PlaybackSession.propBoolean["seeking"].collect { seeking ->
            when {
              seeking == true -> {
                wasSeeking = true
                if (_isRealtimeSubsActive.value) realtimeSubtitleService.onSeekStarted()
              }
              wasSeeking -> {
                wasSeeking = false
                if (_isRealtimeSubsActive.value) {
                  val positionMs = ((PlaybackSession.getPropertyDouble("time-pos") ?: _precisePosition.value.toDouble()) * 1000).toLong()
                  realtimeSubtitleService.seekTo(positionMs)
                }
              }
            }
          }
        }
        launch {
          audioTracks
            .map { tracks -> tracks.firstOrNull(TrackNode::isSelected)?.id }
            .distinctUntilChanged()
            .drop(1)
            .collect { selectedId ->
              val targetLanguage = realtimeTargetLanguage
              if (_isRealtimeSubsActive.value && selectedId != null) {
                withContext(Dispatchers.Main) {
                  startRealtimeSubtitles(targetLanguage.orEmpty())
                }
              }
            }
        }
        launch {
          PlaybackSession.queue
            .map { it.repeatMode }
            .distinctUntilChanged()
            .collect { _repeatMode.value = it }
        }
      }
  }

  private fun startAndroidSystemInfoBridge() {
    if (androidSystemInfoBridgeJob?.isActive == true) return

    val appContext = appContext.applicationContext
    androidSystemInfoBridgeJob =
      viewModelScope.launch(playbackStateDispatcher) {
        while (isActive) {
          publishAndroidBatteryState(appContext)
          delay(30_000L)
        }
      }
  }

  private fun publishAndroidBatteryState(context: Context) {
    runCatching {
      val state = readAndroidBatteryState(context)
      PlaybackSession.setPropertyInt("user-data/android/battery-level", state.level)
      PlaybackSession.setPropertyBoolean("user-data/android/battery-charging", state.charging)
      PlaybackSession.setPropertyBoolean("user-data/android/battery-plugged", state.plugged)
      onBatteryStateChanged(
        isCharging = state.charging,
        level = state.level,
        isPowerSaveMode = state.isPowerSaveMode,
      )
    }.onFailure { error ->
      Log.w(TAG, "Failed to publish Android battery properties", error)
    }
  }

  private data class AndroidBatteryState(
    val level: Int,
    val charging: Boolean,
    val plugged: Boolean,
    val isPowerSaveMode: Boolean,
  )

  private fun readAndroidBatteryState(context: Context): AndroidBatteryState {
    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val isPowerSaveMode = powerManager?.isPowerSaveMode ?: false
    val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val intentLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val propertyLevel =
      batteryManager
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ?.takeIf { it in 0..100 }
    val fallbackLevel =
      if (intentLevel >= 0 && scale > 0) {
        ((intentLevel * 100f) / scale).roundToInt().coerceIn(0, 100)
      } else {
        -1
      }
    val status =
      batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        ?: BatteryManager.BATTERY_STATUS_UNKNOWN
    val pluggedExtra = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return AndroidBatteryState(
      level = propertyLevel ?: fallbackLevel,
      charging = charging,
      plugged = pluggedExtra != 0,
      isPowerSaveMode = isPowerSaveMode,
    )
  }

  fun onVideoLoadStarted() {
    if (tvSeekFeedbackJob != null) {
      tvSeekFeedbackJob?.cancel()
      tvSeekFeedbackJob = null
      _seekState.value = SeekState()
    }
    cancelSeekPreview()
    cancelFrameSeek()
    seekCommitJob?.cancel()
    seekCommitJob = null
    cancelPendingRelativeSeek()
    lyricsLoadJob?.cancel()
    lyricsLoadJob = null
    lyricsTranslateJob?.cancel()
    lyricsTranslateJob = null
    lastLyricsLoadRequest = null
    lyricsStatePath = null
    lyricsUiState.value = LyricsUiState()
    stopRealtimeSubtitles(showToastMessage = false)
    introLookupJob?.cancel()
    cancelAutoCropAnalysis()
    autoCropAnalyzedGeneration = -1L
    if (autoCropApplied || playerPreferences.autoCropBlackBars.get()) {
      clearAutoCropProperty()
    }
    _autoCropState.value = AutoCropState.IDLE
    // PlaybackSession is process-wide, while this ViewModel can be recreated. Clear both halves of
    // the timeline so a stopped file's last position cannot be rendered beside the incoming file's
    // reset 00:00 duration while mpv is still opening it.
    _pos.value = null
    _duration.value = null
    _precisePosition.value = 0f
    _preciseDuration.value = 0f
    chapterDerivedSegments = emptyList()
    introDbSegments = emptyList()
    skipSegmentsSnapshot = emptyList()
    _skipSegments.value = emptyList()
    _currentSkippableSegment.value = null
    _showSkipChipAuto.value = false
    skippedSegments.clear()
    pendingIntroLookupTitle = currentMediaTitle.takeIf { it.isNotBlank() }
    _videoOpenAnimationState.update {
      it.copy(
        loadToken = it.loadToken + 1,
        isWaitingForVideo = true,
      )
    }
  }

  fun onVideoLoadCompleted() {
    _videoOpenAnimationState.update { current ->
      if (current.isWaitingForVideo) {
        current.copy(isWaitingForVideo = false)
      } else {
        current
      }
    }
    syncplayManager.updateFileInfo(currentSyncplayFileInfo())
    applyEqualizerMpvFilters()
    if (isAudioOnly.value) loadLyricsForCurrentTrack()
    scheduleAutoCropAnalysis()
  }

  fun updateTorrentState(state: TorrentStreamingState) {
    _torrentState.value = state
  }

  private fun currentSyncplayPlaybackState(): SyncplayPlaybackState =
    SyncplayPlaybackState(
      position =
        runCatching { PlaybackSession.getPropertyDouble("time-pos") }.getOrNull()
          ?: precisePosition.value.toDouble(),
      paused =
        runCatching { PlaybackSession.getPropertyBoolean("pause") }.getOrNull()
          ?: (paused ?: true),
    )

  private fun currentSyncplayFileInfo(): SyncplayFile? {
    val name =
      listOfNotNull(
        runCatching { PlaybackSession.getPropertyString("filename") }.getOrNull(),
        currentMediaTitle,
        runCatching { PlaybackSession.getPropertyString("media-title") }.getOrNull(),
        runCatching { PlaybackSession.getPropertyString("stream-open-filename") }
          .getOrNull()
          ?.substringAfterLast('/'),
        runCatching { PlaybackSession.getPropertyString("path") }
          .getOrNull()
          ?.substringAfterLast('/'),
      ).map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        ?: return null

    val durationSeconds =
      runCatching { PlaybackSession.getPropertyDouble("duration") }.getOrNull()?.takeIf { it > 0.0 }
        ?: _preciseDuration.value.toDouble().takeIf { it > 0.0 }
        ?: 0.0
    val sizeBytes =
      listOfNotNull(
        runCatching { PlaybackSession.getPropertyDouble("file-size")?.toLong() }.getOrNull(),
        runCatching { PlaybackSession.getPropertyDouble("stream-end")?.toLong() }.getOrNull(),
      ).firstOrNull { it > 0L } ?: 0L

    return SyncplayFile(
      duration = durationSeconds,
      name = name,
      size = JsonPrimitive(sizeBytes),
    )
  }

  private fun setupCustomButtons() {
    customButtonsSetupJob?.cancel()
    customButtonsSetupJob =
      viewModelScope.launch(Dispatchers.IO) {
        try {
          val buttons = mutableListOf<CustomButtonState>()
          val scriptBodies = linkedMapOf<CustomButtonScriptLanguage, StringBuilder>()
          val jsonString = playerPreferences.customButtons.get()
          if (jsonString.isNotBlank()) {
            try {
              // Try new slot-based format first
              val slotsData = json.decodeFromString<app.gyrolet.mpvrx.ui.preferences.CustomButtonSlots>(jsonString)
              slotsData.slots.forEachIndexed { index, btn ->
                if (btn != null && btn.enabled) {
                  val safeId = btn.id.replace("-", "_")
                  val isLeft = index < 4 // Slots 0-3 are left, 4-7 are right
                  processButton(
                    originalId = btn.id,
                    safeId = safeId,
                    label = btn.title,
                    command = btn.content,
                    longPressCommand = btn.longPressContent,
                    onStartup = btn.onStartup,
                    language = btn.scriptLanguage,
                    scriptBuilder = scriptBodies.getOrPut(btn.scriptLanguage) { StringBuilder() },
                    isLeft = isLeft,
                    uiList = buttons,
                  )
                }
              }
            } catch (e: Exception) {
              // Fallback to old format for backward compatibility
              try {
                val customButtonsList =
                  json.decodeFromString<List<app.gyrolet.mpvrx.ui.preferences.CustomButton>>(
                    jsonString,
                  )
                customButtonsList.forEachIndexed { index, btn ->
                  val safeId = btn.id.replace("-", "_")
                  val isLeft = index < 4 // First 4 are left buttons, rest are right
                  processButton(
                    originalId = btn.id,
                    safeId = safeId,
                    label = btn.title,
                    command = btn.content,
                    longPressCommand = btn.longPressContent,
                    onStartup = btn.onStartup,
                    language = btn.scriptLanguage,
                    scriptBuilder = scriptBodies.getOrPut(btn.scriptLanguage) { StringBuilder() },
                    isLeft = isLeft,
                    uiList = buttons,
                  )
                }
              } catch (e2: Exception) {
                e2.printStackTrace()
              }
            }
          }

          _customButtons.value = buttons

          val generatedPaths = mutableMapOf<CustomButtonScriptLanguage, String>()
          val scriptsDir = File(appContext.filesDir, "scripts")
          if (!scriptsDir.exists()) scriptsDir.mkdirs()

          scriptBodies.forEach { (language, bodyBuilder) ->
            val rawScriptContent = bodyBuilder.toString()
            if (rawScriptContent.isBlank()) return@forEach

            val target = customButtonScriptTargetsByLanguage[language] ?: return@forEach
            val scriptVersion = rawScriptContent.md5()
            val scriptContent = buildCustomButtonsScript(rawScriptContent, scriptVersion, target)

            val file = File(scriptsDir, target.fileName)
            file.writeText(scriptContent)
            generatedPaths[language] = file.absolutePath
          }

          customButtonsScriptPaths = generatedPaths.toMap()
          deleteCustomButtonsScriptFiles(activePaths = generatedPaths.values.toSet())
          withContext(Dispatchers.Main) {
            customButtonScriptTargets
              .filter { it.language !in generatedPaths.keys }
              .forEach(::deactivateCustomButtonsScript)
          }

          if (generatedPaths.isNotEmpty()) {
            if (isMpvReadyForCustomButtons) {
              customButtonsLoadMutex.withLock {
                withContext(Dispatchers.Main) {
                  deactivateLegacyCustomButtonsScript()
                }
                generatedPaths.forEach { (language, path) ->
                  val target = customButtonScriptTargetsByLanguage[language] ?: return@forEach
                  val loaded =
                    withContext(Dispatchers.Main) {
                      loadCustomButtonsScript(File(path), target)
                    }
                  if (!loaded) {
                    android.util.Log.w("PlayerViewModel", "Failed to load ${target.fileName}")
                  }
                }
              }
            } else {
              android.util.Log.d("PlayerViewModel", "Deferring custom button scripts until MPV is ready")
            }
          } else {
            customButtonsScriptPaths = emptyMap()
            deleteCustomButtonsScriptFiles()
            withContext(Dispatchers.Main) {
              customButtonScriptTargets.forEach(::deactivateCustomButtonsScript)
              deactivateLegacyCustomButtonsScript()
            }
          }
        } catch (e: Exception) {
          android.util.Log.e("PlayerViewModel", "Error setting up custom buttons", e)
        }
      }
  }

  private fun reloadCustomButtonsScript(reason: String) {
    if (!isMpvReadyForCustomButtons) return

    viewModelScope.launch(Dispatchers.IO) {
      var rebuildNeeded = false
      customButtonsLoadMutex.withLock {
        val scriptPaths = customButtonsScriptPaths
        if (scriptPaths.isEmpty()) return@withLock

        withContext(Dispatchers.Main) {
          deactivateLegacyCustomButtonsScript()
        }

        for ((language, scriptPath) in scriptPaths) {
          val target = customButtonScriptTargetsByLanguage[language] ?: continue
          val isLoaded =
            withContext(Dispatchers.Main) {
              isCustomButtonsScriptLoaded(target)
            }
          if (isLoaded) continue

          val file = File(scriptPath)
          if (!file.exists()) {
            android.util.Log.w("PlayerViewModel", "${target.fileName} missing during $reason, rebuilding")
            rebuildNeeded = true
            break
          }

          val loaded =
            withContext(Dispatchers.Main) {
              loadCustomButtonsScript(file, target)
            }
          if (!loaded) {
            android.util.Log.w("PlayerViewModel", "${target.fileName} load failed during $reason")
          }
        }
      }
      if (rebuildNeeded) {
        setupCustomButtons()
      }
    }
  }

  private fun isCustomButtonsScriptLoaded(target: CustomButtonScriptTarget): Boolean =
    runCatching { PlaybackSession.getPropertyString(target.loadedFlagProperty) == "1" }
      .getOrDefault(false)

  private fun loadCustomButtonsScript(
    file: File,
    target: CustomButtonScriptTarget,
  ): Boolean {
    runCatching { PlaybackSession.setPropertyString(target.loadedFlagProperty, "0") }

    return runCatching {
      PlaybackSession.command("load-script", file.absolutePath)
      true
    }.getOrElse {
      android.util.Log.w("PlayerViewModel", "load-script failed for ${target.fileName}: ${it.message}")
      false
    }
  }

  private fun deactivateCustomButtonsScript(target: CustomButtonScriptTarget) {
    runCatching {
      PlaybackSession.setPropertyString(target.loadedFlagProperty, "0")
      PlaybackSession.setPropertyString(target.versionProperty, "")
    }
  }

  private fun deactivateLegacyCustomButtonsScript() {
    runCatching {
      PlaybackSession.setPropertyString(legacyCustomButtonsLoadedFlagProperty, "0")
      PlaybackSession.setPropertyString(legacyCustomButtonsVersionProperty, "")
    }
  }

  private fun deleteCustomButtonsScriptFiles(activePaths: Set<String> = emptySet()) {
    runCatching {
      val activeNames = activePaths.map { File(it).name }.toSet()
      val scriptsDir = File(appContext.filesDir, "scripts")
      customButtonScriptTargets.forEach { target ->
        val file = File(scriptsDir, target.fileName)
        if (file.exists() && file.name !in activeNames) {
          file.delete()
        }
      }
    }
  }

  private fun buildCustomButtonsScript(
    body: String,
    version: String,
    target: CustomButtonScriptTarget,
  ): String =
    when (target.language) {
      CustomButtonScriptLanguage.LUA ->
        buildString {
          appendLine("local loaded_flag_property = '${target.loadedFlagProperty.toScriptLiteral()}'")
          appendLine("local version_property = '${target.versionProperty.toScriptLiteral()}'")
          appendLine("local instance_version = '${version.toScriptLiteral()}'")
          appendLine("if mp.get_property_native(version_property) == instance_version then")
          appendLine("    mp.set_property_native(loaded_flag_property, '1')")
          appendLine("    return")
          appendLine("end")
          appendLine("mp.set_property_native(version_property, instance_version)")
          appendLine("mp.set_property_native(loaded_flag_property, '1')")
          appendLine("local function is_active_instance()")
          appendLine("    return mp.get_property_native(version_property) == instance_version")
          appendLine("end")
          appendLine()
          append(body)
        }

      CustomButtonScriptLanguage.JS ->
        buildString {
          appendLine("var loadedFlagProperty = '${target.loadedFlagProperty.toScriptLiteral()}';")
          appendLine("var versionProperty = '${target.versionProperty.toScriptLiteral()}';")
          appendLine("var instanceVersion = '${version.toScriptLiteral()}';")
          appendLine("if (mp.get_property_native(versionProperty) === instanceVersion) {")
          appendLine("    mp.set_property_native(loadedFlagProperty, '1');")
          appendLine("} else {")
          appendLine("    mp.set_property_native(versionProperty, instanceVersion);")
          appendLine("    mp.set_property_native(loadedFlagProperty, '1');")
          appendLine("    var isActiveInstance = function() {")
          appendLine("        return mp.get_property_native(versionProperty) === instanceVersion;")
          appendLine("    };")
          appendLine()
          append(body.prependIndent("    "))
          appendLine()
          appendLine("}")
        }
    }

  fun callCustomButton(id: String) {
    val safeId = id.replace("-", "_")
    PlaybackSession.command("script-message", "call_button_$safeId")
  }

  fun callCustomButtonLongPress(id: String) {
    val safeId = id.replace("-", "_")
    PlaybackSession.command("script-message", "call_button_long_$safeId")
  }

  private fun processButton(
    originalId: String,
    safeId: String,
    label: String,
    command: String,
    longPressCommand: String,
    onStartup: String,
    language: CustomButtonScriptLanguage,
    scriptBuilder: StringBuilder,
    isLeft: Boolean,
    uiList: MutableList<CustomButtonState>,
  ) {
    if (label.isNotBlank()) {
      uiList.add(CustomButtonState(originalId, label, isLeft))

      // On Startup Code
      if (onStartup.isNotBlank()) {
        scriptBuilder.append(onStartup)
        scriptBuilder.append("\n")
      }

      // Click Handler
      if (command.isNotBlank()) {
        scriptBuilder.appendButtonHandler(
          functionName = "button_$safeId",
          messageName = "call_button_$safeId",
          command = command,
          language = language,
        )
      }

      // Long Press Handler
      if (longPressCommand.isNotBlank()) {
        scriptBuilder.appendButtonHandler(
          functionName = "button_long_$safeId",
          messageName = "call_button_long_$safeId",
          command = longPressCommand,
          language = language,
        )
      }
    }
  }

  private fun StringBuilder.appendButtonHandler(
    functionName: String,
    messageName: String,
    command: String,
    language: CustomButtonScriptLanguage,
  ) {
    when (language) {
      CustomButtonScriptLanguage.LUA -> {
        append(
          """
          function $functionName()
              if not is_active_instance() then return end
              $command
          end
          mp.register_script_message('$messageName', $functionName)
          """.trimIndent(),
        )
      }
      CustomButtonScriptLanguage.JS -> {
        append(
          """
          var $functionName = function() {
              if (!isActiveInstance()) return;
              $command
          };
          mp.register_script_message('$messageName', $functionName);
          """.trimIndent(),
        )
      }
    }
    append("\n")
  }

  private fun String.toScriptLiteral(): String = replace("\\", "\\\\").replace("'", "\\'")

  private val doubleTapToSeekDuration: Int
    get() = gesturePreferences.doubleTapToSeekDuration.get().coerceIn(1, 120)

  // Cached values
  private val inputMethodManager by lazy {
    appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
  }

  // Seek coalescing for smooth performance
  private val seekCoalesceLock = Any()
  private var pendingSeekOffset: Int = 0
  private var pendingSeekGeneration: Long? = null
  private var seekCoalesceRevision = 0L
  private var seekCoalesceJob: Job? = null
  private val seekPreviewLock = Any()
  private var pendingSeekPreviewPosition: Float? = null
  private var seekPreviewIsFrame = false

  // Move the thumb immediately while a native seek runs. The poller resumes when an exact
  // target lands or the bounded hold expires, allowing fast keyframe seeks to settle too.
  private var seekFeedbackHoldUntilElapsed = 0L
  private var seekFeedbackTargetPosition = 0f

  /** How close the player's reported position must be to count as "the seek landed". */
  private fun seekFeedbackTolerance(): Double = SEEK_FEEDBACK_TOLERANCE_SECONDS

  private fun beginSeekFeedbackHold(targetPosition: Float, holdMillis: Long) {
    seekFeedbackTargetPosition = targetPosition
    seekFeedbackHoldUntilElapsed = android.os.SystemClock.elapsedRealtime() + holdMillis
  }

  private fun shouldHoldOptimisticPosition(reportedPosition: Float): Boolean {
    val now = android.os.SystemClock.elapsedRealtime()
    if (now >= seekFeedbackHoldUntilElapsed) return false
    if (kotlin.math.abs(reportedPosition - seekFeedbackTargetPosition) <= seekFeedbackTolerance()) {
      // The player caught up with the requested position: resume normal polling.
      seekFeedbackHoldUntilElapsed = 0L
      return false
    }
    return true
  }

  // Runs on the UI thread during a double-tap, so it clamps against the already-polled duration and
  // position rather than reading mpv here.
  private fun previewSeekPositionOptimistically(offsetSeconds: Int) {
    val maxDuration = duration ?: _preciseDuration.value.toInt()
    val base = _precisePosition.value.toDouble()
    val clamped =
      if (maxDuration > 0) (base + offsetSeconds).coerceIn(0.0, maxDuration.toDouble())
      else (base + offsetSeconds).coerceAtLeast(0.0)
    _precisePosition.value = clamped.toFloat()
    beginSeekFeedbackHold(clamped.toFloat(), RELATIVE_SEEK_FEEDBACK_HOLD_MS)
  }

  // Called from both the drag handler and the seek path, so it clamps against the polled duration
  // rather than reading mpv on the UI thread.
  private fun previewAbsoluteSeekPositionOptimistically(positionSeconds: Double) {
    val maxDuration = duration ?: _preciseDuration.value.toInt()
    val clamped =
      if (maxDuration > 0) positionSeconds.coerceIn(0.0, maxDuration.toDouble())
      else positionSeconds.coerceAtLeast(0.0)
    _precisePosition.value = clamped.toFloat()
    beginSeekFeedbackHold(clamped.toFloat(), ABSOLUTE_SEEK_FEEDBACK_HOLD_MS)
  }

  private var seekPreviewJob: Job? = null
  private var seekCommitJob: Job? = null
  private var frameSeekJob: Job? = null

  private companion object {
    const val TAG = "PlayerViewModel"
    const val AUTO_CROP_SAMPLE_COUNT = 7
    const val AUTO_CROP_MIN_VALID_SAMPLES = 3
    const val AUTO_CROP_THUMBNAIL_SIZE = 640
    const val AUTO_CROP_READY_TIMEOUT_MS = 15_000L
    const val AUTO_CROP_CACHE_CAPACITY = 20
    const val AUTO_CROP_FILTER_LABEL = "mpvrx_autocrop_detect"
    const val AUTO_CROP_DETECT_LIMIT = "24/255"
    const val AUTO_CROP_DETECT_ROUND = 2
    const val AUTO_CROP_ACTIVE_DETECT_TIMEOUT_MS = 1_600L
    const val AUTO_CROP_ACTIVE_SETTLE_MS = 1_000L
    const val AUTO_CROP_METADATA_POLL_MS = 100L
    const val AUTO_CROP_HWDEC_TIMEOUT_MS = 1_500L
    const val AUTO_CROP_ACTIVE_FRAME_TIMEOUT_MS = 2_500L
    const val AUTO_CROP_ACTIVE_FRAME_INTERVAL_MS = 300L
    const val AUTO_SHOW_SKIP_CHIP_DURATION = 10.0
    const val SEEK_COALESCE_DELAY_MS = 60L
    // Reported positions within this distance of the requested target count as "landed".
    const val SEEK_FEEDBACK_TOLERANCE_SECONDS = 0.35
    // Upper bound on how long the optimistic thumb may outrun the player.
    const val RELATIVE_SEEK_FEEDBACK_HOLD_MS = 1500L
    const val ABSOLUTE_SEEK_FEEDBACK_HOLD_MS = 3000L
    // The thumb updates immediately; decoder previews only need a modest cadence.
    const val PREVIEW_SEEK_INTERVAL_MS = 200L
    const val NETWORK_FRAME_PREVIEW_SEEK_INTERVAL_MS = 800L
    const val FRAME_SEEK_POLL_INTERVAL_MS = 10L
    const val FRAME_SEEK_MIN_SETTLE_POLLS = 8
    const val FRAME_SEEK_MAX_SETTLE_POLLS = 75
    const val FRAME_SEEK_EXACT_PASSES = 3
    const val FRAME_SEEK_MAX_CORRECTION_STEPS = 12
    const val FRAME_SEEK_CORRECTION_INTERVAL_MS = 24L
    const val FRAME_SEEK_CORRECTION_SETTLE_MS = 40L
    val QUALITY_HEIGHT_REGEX = Regex("""(?i)(\d{3,4})p""")
    val VIDEO_QUALITY_STREAM_SCHEMES = setOf(
      "http", "https", "ftp", "ftps", "rtmp", "rtmps", "rtsp", "rtsps", "mms", "mmsh",
      "srt", "rist", "udp", "tcp", NetworkPlaybackUri.SCHEME, XtreamPlaybackUri.SCHEME,
    )
    val MPV_ONLY_PSEUDO_PROTOCOLS =
      setOf("fd", "fdclose", "edl", "memory", "null", "av", "lavf", "archive", "slice", "mf", "hex", "bd", "dvd", "dvb")
    const val PLAYLIST_METADATA_PREFETCH_RADIUS = 40
    const val PLAYLIST_METADATA_PREFETCH_LIMIT = 120
    const val PLAYLIST_NETWORK_METADATA_PREFETCH_RADIUS = 3
    const val PLAYLIST_NETWORK_METADATA_TIMEOUT_MS = 8_000L
    const val INTRO_MARKER_CACHE_PREFS = "intro_marker_cache"
    const val INTRO_MARKER_CACHE_PREFIX = "intro_marker:v3:"
    const val INTRO_MARKER_CACHE_MAX_ENTRIES = 200
    const val INTRO_MARKER_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
    const val INTRO_MARKER_EMPTY_CACHE_TTL_MS = 60L * 60L * 1000L
    const val INTRO_MARKER_CACHE_LOADED = "loaded"
    const val INTRO_MARKER_CACHE_NO_SEGMENTS = "no_segments"
    const val INTRO_MARKER_CACHE_UNRESOLVED = "unresolved"
    val VALID_SUBTITLE_EXTENSIONS =
      setOf(
        // Common & modern
        "srt",
        "vtt",
        "ass",
        "ssa",
        // DVD / Blu-ray
        "sub",
        "idx",
        "sup",
        // Streaming / XML / Professional
        "xml",
        "ttml",
        "dfxp",
        "itt",
        "ebu",
        "imsc",
        "usf",
        // Online platforms
        "sbv",
        "srv1",
        "srv2",
        "srv3",
        "json",
        // Legacy & niche
        "sami",
        "smi",
        "mpl",
        "pjs",
        "stl",
        "rt",
        "psb",
        "cap",
        // Broadcast captions
        "scc",
        "vttx",
        // Karaoke / lyrics
        "lrc",
        "krc",
        // Fallback / raw text
        "txt",
        "pgs",
      )
  }

  // ==================== Timer ====================

  fun startTimer(seconds: Int) {
    timerJob?.cancel()
    _remainingTime.value = seconds
    if (seconds < 1) return

    timerJob =
      viewModelScope.launch {
        for (time in seconds downTo 0) {
          _remainingTime.value = time
          delay(1000)
        }
        PlaybackSession.setPropertyBoolean("pause", true)
        showToast(appContext.getString(R.string.toast_sleep_timer_ended))
      }
  }

  // ==================== Decoder ====================

  // ==================== Audio/Subtitle Management ====================

  fun addAudio(uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
      runCatching {
        if (uri.scheme == "content") {
          try {
            appContext.contentResolver.takePersistableUriPermission(
              uri,
              Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
          } catch (e: SecurityException) {
            android.util.Log.i(
              "PlayerViewModel",
              "Persistent permission not taken for audio $uri",
            )
          }
        }

        val path =
          uri.resolveUri(appContext)
            ?: return@launch withContext(Dispatchers.Main) {
              showToast("Failed to load audio file: Invalid URI")
            }

        val title = getFileNameFromUri(uri)?.substringBeforeLast(".")?.ifBlank { null }

        withContext(Dispatchers.Main) {
          if (title != null) {
            PlaybackSession.command("audio-add", path, "cached", title)
          } else {
            PlaybackSession.command("audio-add", path, "cached")
          }
          showToast("Audio track added")
        }
      }.onFailure { e ->
        withContext(Dispatchers.Main) {
          showToast("Failed to load audio: ${e.message}")
        }
        android.util.Log.e("PlayerViewModel", "Error adding audio", e)
      }
    }
  }

  fun addSubtitle(
    uri: Uri,
    select: Boolean = true,
    silent: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      addSubtitleSuspend(uri, select, silent)
    }
  }

  suspend fun addSubtitleSuspend(
    uri: Uri,
    select: Boolean = true,
    silent: Boolean = false,
  ) {
    subtitleAddMutex.withLock {
      val uriString = uri.toString()
      if (_externalSubtitles.contains(uriString)) {
        android.util.Log.d("PlayerViewModel", "Subtitle already tracked, skipping: $uriString")
        return@withLock
      }

      runCatching {
        val fileName = getFileNameFromUri(uri) ?: "subtitle.srt"

        if (!isValidSubtitleFile(fileName)) {
          return@withLock withContext(Dispatchers.Main) {
            showToast("Invalid subtitle file format")
          }
        }

        // Take persistent URI permission for content:// URIs
        if (uri.scheme == "content") {
          try {
            appContext.contentResolver.takePersistableUriPermission(
              uri,
              Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
          } catch (e: SecurityException) {
            // Permission already granted, not available, or not needed (e.g. from tree).
            android.util.Log.i(
              "PlayerViewModel",
              "Persistent permission not taken for $uri (may already have it via tree)",
            )
          }
        }

        val mpvPath = uri.resolveUri(appContext) ?: uri.toString()
        val mode = if (select) "select" else "auto"

        // Keep the original URI even when mpv already auto-loaded this file.
        mpvPathToUriMap[mpvPath] = uri.toString()

        // Check if MPV already auto-loaded this subtitle (prevents duplication)
        val existingTrack = subtitleTracks.value.find { it.externalFilename == mpvPath }
        if (existingTrack != null) {
          android.util.Log.d("PlayerViewModel", "Subtitle already loaded by MPV, skipping sub-add: $mpvPath")
          if (select) {
            withContext(Dispatchers.Main) {
              runCatching { PlaybackSession.setPropertyInt("sid", existingTrack.id) }
            }
          }
          // Still track it in _externalSubtitles if it's not there
          if (!_externalSubtitles.contains(uriString)) {
            _externalSubtitles.add(uriString)
          }
          return@withLock
        }

        withContext(Dispatchers.Main) {
          PlaybackSession.command("sub-add", mpvPath, mode, fileName)
        }

        // Track external subtitle URI for persistence
        if (!_externalSubtitles.contains(uriString)) {
          _externalSubtitles.add(uriString)
        }

        val displayName = fileName.take(30).let { if (fileName.length > 30) "$it..." else it }
        if (!silent) {
          withContext(Dispatchers.Main) {
            showToast("$displayName added")
          }
        }
      }.onFailure {
        if (!silent) {
          withContext(Dispatchers.Main) {
            showToast("Failed to load subtitle")
          }
        }
      }
    }
  }

  private var translationJob: Job? = null

  /** Reopen a picked subtitle using its original content URI instead of mpv's private fd path. */
  internal fun clipSubtitleSource(externalPath: String): String =
    mpvPathToUriMap[externalPath] ?: externalPath

  fun translateSubtitle(
    track: TrackNode,
    targetLanguage: String,
  ) {
    val externalPath = track.externalFilename ?: return
    val uriString = mpvPathToUriMap[externalPath] ?: externalPath

    // Convert file path to proper URI if needed
    val uri =
      if (uriString.startsWith("/")) {
        File(uriString).toUri()
      } else {
        Uri.parse(uriString)
      }

    translationJob?.cancel()
    translationJob =
      viewModelScope.launch(Dispatchers.IO) {
        _isTranslatingSub.value = true
        _translatingTrackId.value = track.id
        _translatingTrackName.value =
          getFileNameFromUri(uri)?.let { it.substringBeforeLast(".") }?.lowercase() ?: "subtitle"
        _translationProgress.value = 0f
        _translationStatus.value = "Preparing translation"

        try {
          val content =
            appContext.contentResolver.openInputStream(uri)?.use {
              it.readBytes().decodeToString()
            } ?: throw Exception("Could not read subtitle file")

          val originalFileName = getFileNameFromUri(uri) ?: "subtitle.srt"
          val extension = originalFileName.substringAfterLast('.', "srt")

          val result =
            aiService.translateSubtitle(content, targetLanguage, extension) { progress ->
              _translationProgress.value = progress.progress
              _translationStatus.value =
                buildString {
                  append(if (progress.isResuming) "Resuming" else "Translating")
                  append(" ${progress.completedChunks}/${progress.totalChunks}")
                }
            }

          result
            .onSuccess { translatedContent ->
              val baseName = originalFileName.substringBeforeLast(".").ifBlank { "subtitle" }
              val sanitizedLang = targetLanguage.replace(" ", "_").ifBlank { "translated" }
              val newFileName = "$baseName.$sanitizedLang.AI.$extension"

              val savedUri =
                saveTranslatedSubtitle(uri, newFileName, extension, targetLanguage, translatedContent)
                  ?: throw Exception("Could not save translated subtitle")

              withContext(Dispatchers.Main) {
                addSubtitle(savedUri, select = true)
                showToast("Translation complete: $newFileName")
              }
            }.onFailure { error ->
              withContext(Dispatchers.Main) {
                showToast("Translation failed: ${error.message}")
              }
            }
        } catch (e: Exception) {
          withContext(Dispatchers.Main) {
            showToast("Error: ${e.message}")
          }
        } finally {
          _isTranslatingSub.value = false
          _translatingTrackId.value = null
          _translatingTrackName.value = ""
          _translationProgress.value = 0f
          _translationStatus.value = ""
          translationJob = null
        }
      }
  }

  fun cancelTranslation() {
    translationJob?.cancel()
    translationJob = null
    _isTranslatingSub.value = false
    _translatingTrackId.value = null
    _translatingTrackName.value = ""
    _translationProgress.value = 0f
    _translationStatus.value = ""
    val cacheDir = java.io.File(appContext.filesDir, "ai_translation_cache")
    if (cacheDir.exists()) {
      cacheDir.listFiles()?.forEach { it.delete() }
      cacheDir.delete()
    }
    showToast("Translation cancelled")
  }

  fun generateSubtitles(
    language: String,
    outputFormat: String = "srt",
  ) {
    val videoUri = currentVideoUriForSubtitleGeneration()
    if (videoUri == null) {
      showToast("Could not find current video path")
      return
    }
    val mediaInput = currentRealtimeMediaInput()
    if (mediaInput == null) {
      showToast(appContext.getString(R.string.subtitle_generation_audio_unavailable))
      return
    }
    val videoDurationMs = (_preciseDuration.value * 1000f).toLong()
    if (videoDurationMs <= 0L) {
      showToast(appContext.getString(R.string.subtitle_generation_duration_unknown))
      return
    }

    val actualLanguage = if (language.isBlank()) aiPreferences.sttLanguage.get().ifBlank { "en" } else language
    val actualFormat = if (outputFormat.isBlank()) aiPreferences.subtitleGenerationOutputFormat.get() else outputFormat

    viewModelScope.launch(Dispatchers.IO) {
      _isGeneratingSubtitles.value = true
      _subtitleGenerationProgress.value = 0f
      _subtitleGenerationStatus.value = "Preparing audio"

      try {
        val result =
          subtitleGenerationService.generateSubtitles(
            mediaInput = mediaInput,
            videoDurationMs = videoDurationMs,
            language = actualLanguage,
            outputFormat = actualFormat,
          ) { progress ->
            _subtitleGenerationProgress.value = progress.progress
            _subtitleGenerationStatus.value = progress.stage
          }

        result
          .onSuccess { generated ->
            val baseName = currentMediaTitle.substringBeforeLast(".").ifBlank { "video" }
            val sanitizedLang = actualLanguage.replace(" ", "_")
            val newFileName = "$baseName.$sanitizedLang.AI.${generated.extension}"
            val savedUri =
              saveTranslatedSubtitle(videoUri, newFileName, generated.extension, sanitizedLang, generated.content)
                ?: throw Exception("Could not save generated subtitles")
            withContext(Dispatchers.Main) {
              addSubtitle(savedUri, select = true)
              showToast("Generated subtitles: $newFileName")
            }
          }.onFailure { error ->
            withContext(Dispatchers.Main) {
              showToast("Subtitle generation failed: ${error.message}")
            }
          }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          showToast("Subtitle generation error: ${e.message}")
        }
      } finally {
        _isGeneratingSubtitles.value = false
        _subtitleGenerationProgress.value = 0f
        _subtitleGenerationStatus.value = ""
      }
    }
  }

  private fun currentVideoUriForSubtitleGeneration(): Uri? {
    val media = host?.currentMediaLookupHint()?.takeIf { it.isNotBlank() } ?: return null
    return if (media.startsWith("/")) File(media).toUri() else Uri.parse(media)
  }

  private fun currentRealtimeMediaInput(): RealtimeMediaInput? {
    val item = PlaybackSession.state.value.currentItem
    val tracks = audioTracks.value
    val selectedAudio = tracks.firstOrNull(TrackNode::isSelected) ?: tracks.firstOrNull()
    val selectedAudioSource =
      selectedAudio
        ?.externalFilename
        ?.takeIf(String::isNotBlank)
        ?.let { path -> mpvPathToUriMap[path] ?: path }
    val runtimeSource =
      PlaybackSession.getPropertyString("stream-open-filename")
        ?.takeIf(String::isNotBlank)
        ?: PlaybackSession.getPropertyString("path")?.takeIf(String::isNotBlank)
    val source =
      selectedAudioSource
        ?: runtimeSource?.takeUnless { it.startsWith("fd://") || it.startsWith("memory://") }
        ?: item?.originalUri?.takeIf(String::isNotBlank)
        ?: item?.playableUri?.takeIf(String::isNotBlank)
        ?: host?.currentMediaLookupHint()?.takeIf(String::isNotBlank)
        ?: return null
    val audioTrackOrdinal =
      selectedAudio
        ?.let { selected -> tracks.indexOfFirst { it.id == selected.id } }
        ?.coerceAtLeast(0)
        ?: 0
    return RealtimeMediaInput(
      source = source,
      headers = item?.headers.orEmpty(),
      audioTrackIndex = selectedAudio?.ffIndex?.toInt(),
      audioTrackOrdinal = audioTrackOrdinal,
    )
  }

  fun startRealtimeSubtitles(targetLanguage: String = "") {
    val mediaInput = currentRealtimeMediaInput()
    if (mediaInput == null) {
      showToast(appContext.getString(R.string.realtime_subtitles_media_unavailable))
      return
    }
    val videoDurationMs = (_preciseDuration.value * 1000f).toLong()
    if (videoDurationMs <= 0) {
      showToast(appContext.getString(R.string.subtitle_generation_duration_unknown))
      return
    }

    stopRealtimeSubtitles(showToastMessage = false)
    val sessionId = ++realtimeSubtitleSessionId
    val sourceLanguage = aiPreferences.sttLanguage.get().trim().takeIf(String::isNotBlank)
    val resolvedTargetLanguage = targetLanguage.trim().takeIf(String::isNotBlank)
    val startPositionMs = (_precisePosition.value * 1000f).toLong().coerceIn(0L, videoDurationMs)
    val playbackGeneration = PlaybackSession.state.value.generation
    realtimeSrtFile = java.io.File.createTempFile("realtime_subs_", ".srt", appContext.cacheDir)
    realtimeSubtitleTrackId = null
    realtimeTargetLanguage = resolvedTargetLanguage
    realtimePlaybackGeneration = playbackGeneration

    _isRealtimeSubsActive.value = true
    _realtimeSubsLanguage.value = resolvedTargetLanguage ?: sourceLanguage ?: "Auto"
    _realtimeSubsProgress.value = 0f

    realtimeSubtitleService.start(
      request =
        RealtimeSubtitleRequest(
          mediaInput = mediaInput,
          videoDurationMs = videoDurationMs,
          startPositionMs = startPositionMs,
          sourceLanguage = sourceLanguage,
          targetLanguage = resolvedTargetLanguage,
        ),
      scope = viewModelScope,
      positionProvider = { (_precisePosition.value * 1000f).toLong() },
      onProgress = { progress ->
        if (sessionId != realtimeSubtitleSessionId || playbackGeneration != PlaybackSession.state.value.generation) return@start
        _realtimeSubsProgress.value = progress.chunkIndex.toFloat() / progress.totalChunks.coerceAtLeast(1)
        _realtimeSubsStatus.value = progress.stage
      },
      onNewContent = { srtContent ->
        updateRealtimeSubtitleContent(sessionId, playbackGeneration, srtContent)
      },
      onComplete = {
        if (sessionId != realtimeSubtitleSessionId || playbackGeneration != PlaybackSession.state.value.generation) return@start
        _isRealtimeSubsActive.value = false
        _realtimeSubsProgress.value = 0f
        _realtimeSubsStatus.value = ""
        realtimeTargetLanguage = null
        showToast(appContext.getString(R.string.realtime_subtitles_complete))
      },
      onError = { error ->
        if (sessionId != realtimeSubtitleSessionId || playbackGeneration != PlaybackSession.state.value.generation) return@start
        _isRealtimeSubsActive.value = false
        _realtimeSubsProgress.value = 0f
        _realtimeSubsStatus.value = ""
        realtimeTargetLanguage = null
        Log.e(TAG, "Real-time subtitles failed: $error")
        showToast(appContext.getString(R.string.realtime_subtitles_failed))
      },
    )
  }

  private fun updateRealtimeSubtitleContent(
    sessionId: Long,
    playbackGeneration: Long,
    content: String,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        realtimeSubtitleUpdateMutex.withLock {
          if (sessionId != realtimeSubtitleSessionId || playbackGeneration != PlaybackSession.state.value.generation) return@withLock
          val file = realtimeSrtFile ?: return@withLock
          FileOutputStream(file, false).use { output ->
            output.write(content.toByteArray())
            output.fd.sync()
          }
          if (sessionId != realtimeSubtitleSessionId || playbackGeneration != PlaybackSession.state.value.generation) return@withLock

          val path = file.absolutePath
          var trackId = realtimeSubtitleTrackId ?: findRealtimeSubtitleTrackId(path)
          if (trackId == null) {
            withContext(Dispatchers.Main) {
              PlaybackSession.command("sub-add", path, "select")
            }
            trackId = awaitRealtimeSubtitleTrackId(path, sessionId, playbackGeneration)
            if (sessionId != realtimeSubtitleSessionId || playbackGeneration != PlaybackSession.state.value.generation) {
              trackId?.let { staleTrackId ->
                withContext(Dispatchers.Main) {
                  PlaybackSession.command("sub-remove", staleTrackId.toString())
                }
              }
              return@withLock
            }
            if (trackId == null) throw IllegalStateException("mpv did not expose the live subtitle track")
            realtimeSubtitleTrackId = trackId
          } else {
            withContext(Dispatchers.Main) {
              PlaybackSession.command("sub-reload", trackId.toString())
              PlaybackSession.setPropertyInt("sid", trackId)
            }
          }
        }
      } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        withContext(Dispatchers.Main) {
          if (sessionId == realtimeSubtitleSessionId && playbackGeneration == PlaybackSession.state.value.generation) {
            realtimeSubtitleService.stop()
            _isRealtimeSubsActive.value = false
            _realtimeSubsProgress.value = 0f
            _realtimeSubsStatus.value = ""
            realtimeTargetLanguage = null
            Log.e(TAG, "Could not update live subtitles", error)
            showToast(appContext.getString(R.string.realtime_subtitles_update_failed))
          }
        }
      }
    }
  }

  private suspend fun awaitRealtimeSubtitleTrackId(
    path: String,
    sessionId: Long,
    playbackGeneration: Long,
  ): Int? =
    withTimeoutOrNull(5_000L) {
      var pollDelayMs = 50L
      while (
        currentCoroutineContext().isActive &&
        sessionId == realtimeSubtitleSessionId &&
        playbackGeneration == PlaybackSession.state.value.generation
      ) {
        findRealtimeSubtitleTrackId(path)?.let { return@withTimeoutOrNull it }
        delay(pollDelayMs)
        pollDelayMs = (pollDelayMs * 2).coerceAtMost(200L)
      }
      null
    }

  private fun findRealtimeSubtitleTrackId(path: String): Int? {
    val count = PlaybackSession.getPropertyInt("track-list/count")
    if (count != null) {
      for (index in 0 until count) {
        if (PlaybackSession.getPropertyString("track-list/$index/type") != "sub") continue
        if (PlaybackSession.getPropertyString("track-list/$index/external-filename") != path) continue
        return PlaybackSession.getPropertyInt("track-list/$index/id")
      }
    }
    return subtitleTracks.value.firstOrNull { it.externalFilename == path }?.id
  }

  fun stopRealtimeSubtitles(showToastMessage: Boolean = true) {
    val wasActive = _isRealtimeSubsActive.value
    val subtitlePath = realtimeSrtFile?.absolutePath
    realtimeSubtitleSessionId++
    realtimeSubtitleService.stop()
    if (realtimePlaybackGeneration == PlaybackSession.state.value.generation) {
      (realtimeSubtitleTrackId ?: subtitlePath?.let(::findRealtimeSubtitleTrackId))?.let { trackId ->
        runCatching { PlaybackSession.command("sub-remove", trackId.toString()) }
      }
    }
    _isRealtimeSubsActive.value = false
    _realtimeSubsLanguage.value = ""
    _realtimeSubsProgress.value = 0f
    _realtimeSubsStatus.value = ""
    realtimeTargetLanguage = null
    realtimePlaybackGeneration = -1L
    realtimeSrtFile?.delete()
    realtimeSrtFile = null
    realtimeSubtitleTrackId = null
    if (showToastMessage && wasActive) {
      showToast(appContext.getString(R.string.realtime_subtitles_stopped))
    }
  }

  private fun saveTranslatedSubtitle(
    originalUri: Uri,
    newFileName: String,
    extension: String,
    targetLanguage: String,
    translatedContent: String,
  ): Uri? {
    if (originalUri.scheme == "file") {
      val parent = originalUri.path?.let { File(it).parentFile }
      if (parent?.exists() == true) {
        val saved = File(parent, newFileName).also { it.writeText(translatedContent) }.toUri()
        // Clean up common buggy patterns: .AI.ext, lang.AI.ext, ..AI.ext
        listOf(
          ".AI.$extension",
          "$targetLanguage.AI.$extension",
          "..AI.$extension",
        ).filter { it.isNotBlank() }.forEach { pattern ->
          val buggy = File(parent, pattern)
          if (buggy.exists() && buggy.name != newFileName) buggy.delete()
        }
        return saved
      }
    }

    if (originalUri.scheme == "content") {
      val sourceDocument = DocumentFile.fromSingleUri(appContext, originalUri)
      val parentDocument = sourceDocument?.parentFile
      if (parentDocument?.canWrite() == true) {
        val mimeType =
          MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "text/plain"
        val targetDocument =
          parentDocument.findFile(newFileName)
            ?: parentDocument.createFile(mimeType, newFileName)

        if (targetDocument != null) {
          appContext.contentResolver.openOutputStream(targetDocument.uri)?.use { output ->
            output.write(translatedContent.toByteArray())
          }
          // Clean up common buggy patterns
          listOf(
            ".AI.$extension",
            "$targetLanguage.AI.$extension",
            "..AI.$extension",
          ).filter { it.isNotBlank() }.forEach { pattern ->
            parentDocument.findFile(pattern)?.let { buggy ->
              if (buggy.uri != targetDocument.uri) buggy.delete()
            }
          }
          return targetDocument.uri
        }
      }
    }

    val fallbackDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
    val backupFile =
      File(File(fallbackDir, "Subtitles"), newFileName).apply {
        parentFile?.mkdirs()
      }
    backupFile.writeText(translatedContent)
    // Clean up common buggy patterns in fallback dir
    val backupParent = backupFile.parentFile
    if (backupParent != null) {
      listOf(".AI.$extension", "$targetLanguage.AI.$extension", "..AI.$extension").filter { it.isNotBlank() }.forEach { pattern ->
        val buggy = File(backupParent, pattern)
        if (buggy.exists() && buggy.name != newFileName) buggy.delete()
      }
    }
    return backupFile.toUri()
  }

  private fun scanLocalSubtitles(mediaTitle: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val saveFolderUri = subtitlesPreferences.subtitleSaveFolder.get()
      if (saveFolderUri.isBlank()) return@launch

      var addedCount = 0
      try {
        val sanitizedTitle = MediaInfoParser.parse(mediaTitle).title
        val fullTitle = mediaTitle.substringBeforeLast(".")
        val checksumTitle = ChecksumUtils.getCRC32(mediaTitle)
        val parentDirs = resolveSubtitleLookupDirectories(appContext, saveFolderUri)
        if (parentDirs.isEmpty()) return@launch

        // Scan potential folder names for compatibility: checksum, full, and sanitized
        // Use seenUris so the same file found via multiple folder name variants isn't double-added
        val seenUris = mutableSetOf<String>()
        parentDirs.forEach { parentDir ->
          listOf(checksumTitle, fullTitle, sanitizedTitle).distinct().forEach { folderName ->
            val movieDir = parentDir.findFile(folderName) ?: return@forEach
            if (movieDir.isDirectory) {
              movieDir.listFiles().forEach { file ->
                val uriStr = file.uri.toString()
                if (file.isFile && isValidSubtitleFile(file.name ?: "") && seenUris.add(uriStr)) {
                  // Don't auto-select during scan, just make available.
                  addSubtitle(file.uri, select = false, silent = true)
                  addedCount++
                }
              }
            }
          }
        }
      } catch (e: Exception) {
        android.util.Log.e("PlayerViewModel", "Error scanning local subtitles: ${e.message}", e)
      }

      // Auto-select the first external subtitle if the video has no embedded subtitle track active
      if (addedCount > 0) {
        // Give MPV time to register the sub-add commands
        kotlinx.coroutines.delay(300)
        withContext(Dispatchers.Main) {
          val activeSid = getTrackSelectionId("sid")
          if (activeSid == 0) {
            val firstExternal = subtitleTracks.value.firstOrNull { it.external == true }
            if (firstExternal != null) {
              runCatching { setTrackSelectionId("sid", firstExternal.id) }
            }
          }
        }
      }
    }
  }

  fun setMediaTitle(mediaTitle: String) {
    if (currentMediaTitle != mediaTitle) {
      currentMediaTitle = mediaTitle
      lastAutoSelectedMediaTitle = null
      introLookupJob?.cancel()
      pendingIntroLookupTitle = null
      videoHashJob?.cancel()
      // Clear external subtitles when media changes
      _externalSubtitles.clear()
      // Reset subtitle hash when media changes.
      _videoHash.value = null
      scanLocalSubtitles(mediaTitle)
      syncplayManager.updateFileInfo(currentSyncplayFileInfo())

      restoreSavedVideoAspect(showUpdate = false)
      skippedSegments.clear()
      chapterDerivedSegments = emptyList()
      introDbSegments = emptyList()
      _skipSegments.value = emptyList()
      skipSegmentsSnapshot = emptyList()
      _currentSkippableSegment.value = null
      _showSkipChipAuto.value = false
      _introDbStatus.value =
        if (playerPreferences.enableIntroDb.get()) {
          IntroDbStatus()
        } else {
          IntroDbStatus(
            state = IntroDbStatusState.DISABLED,
            message = "Online skip markers are disabled",
          )
        }
      lookupIntroSegments(mediaTitle)
      refreshChapterDerivedSegments(chapters.value)

      // Reset Video Pan
      if (videoPanX.value != 0f || videoPanY.value != 0f) {
        resetVideoPan()
      }
      // ---------------------------------------------------
    }
  }

  private fun maybeAutoSkipIntro(positionSeconds: Double) {
    val activeSegment =
      skipSegmentsSnapshot.firstOrNull { segment ->
        positionSeconds >= segment.startSeconds &&
          positionSeconds < segment.endSeconds &&
          segment !in skippedSegments
      }

    val autoSkipEnabled =
      when (activeSegment?.type) {
        SkipSegmentType.INTRO, SkipSegmentType.RECAP -> playerPreferences.autoSkipIntro.get()
        SkipSegmentType.OUTRO, SkipSegmentType.CREDITS, SkipSegmentType.PREVIEW -> playerPreferences.autoSkipOutro.get()
        null -> false
      }
    val manualSegment = activeSegment?.takeUnless { autoSkipEnabled }
    val showChip =
      manualSegment != null &&
        (positionSeconds - manualSegment.startSeconds) < AUTO_SHOW_SKIP_CHIP_DURATION
    if (_currentSkippableSegment.value != manualSegment) {
      _currentSkippableSegment.value = manualSegment
    }
    if (_showSkipChipAuto.value != showChip) {
      _showSkipChipAuto.value = showChip
    }

    if (paused == true || activeSegment == null || !autoSkipEnabled) return

    skippedSegments += activeSegment
    seekPastSkipSegment(activeSegment, auto = true)
  }

  fun skipActiveSegment() {
    val segment = _currentSkippableSegment.value ?: return
    skippedSegments += segment
    _showSkipChipAuto.value = false
    seekPastSkipSegment(segment, auto = false)
  }

  private fun seekPastSkipSegment(
    segment: SkipSegment,
    auto: Boolean,
  ) {
    val seekTarget = SkipMarkerResolver.seekTarget(segment, currentDurationSeconds())
    PlaybackSession.setPropertyDouble("time-pos", seekTarget)
    syncplayManager.updatePlayerState(
      seekTarget,
      PlaybackSession.getPropertyBoolean("pause") ?: false,
      doSeek = true,
    )
    showToast(if (auto) "${segment.label} (auto)" else segment.label)
  }

  private fun mergeSkipSegments() {
    val providerSegments = resolveIntroDbSegments()
    val endingTypes = setOf(SkipSegmentType.OUTRO, SkipSegmentType.CREDITS)
    val chapterFallbacks =
      chapterDerivedSegments.filter { chapter ->
        providerSegments.none { provider ->
          provider.type == chapter.type ||
            (provider.type in endingTypes && chapter.type in endingTypes) ||
            (provider.startSeconds < chapter.endSeconds && chapter.startSeconds < provider.endSeconds)
        }
      }
    val merged = SkipMarkerResolver.merge(providerSegments + chapterFallbacks)
    skipSegmentsSnapshot = merged
    _skipSegments.value = merged
  }

  private fun currentDurationSeconds(): Double =
    (_preciseDuration.value.takeIf { it > 0f } ?: (duration ?: 0).toFloat()).toDouble()

  private fun resolveIntroDbSegments(): List<SkipSegment> {
    val durationSec = currentDurationSeconds()
    return SkipMarkerResolver.resolveProviderSegments(
      markers =
        introDbSegments.map { segment ->
          ProviderSkipMarker(
            type = segment.segmentType,
            startSeconds = segment.startSecondsOrNull,
            endSeconds = segment.endSecondsOrNull,
          )
        },
      durationSeconds = durationSec,
      source = introDbSourceKey,
    )
  }

  private fun lookupIntroSegments(mediaTitle: String) {
    val generation = ++introLookupGeneration
    introLookupJob?.cancel()
    introLookupJob = null
    introDbSegments = emptyList()
    _currentSkippableSegment.value = null
    _showSkipChipAuto.value = false
    mergeSkipSegments()
    if (!playerPreferences.enableIntroDb.get()) {
      pendingIntroLookupTitle = null
      _introDbStatus.value =
        IntroDbStatus(
          state = IntroDbStatusState.DISABLED,
          message = "Online skip markers are disabled",
        )
      return
    }

    val durationSec = currentDurationSeconds()
    if (durationSec <= 0) {
      pendingIntroLookupTitle = mediaTitle
      return
    }

    pendingIntroLookupTitle = null

    val lookupKey = mediaTitle
    val provider = playerPreferences.introSegmentProvider.get()
    val lookupHints = host?.currentPlayerLookupHints() ?: PlayerLookupHints()
    val lookupRequest =
      IntroDbLookupRequest(
        mediaTitle = mediaTitle,
        canonicalTitle = lookupHints.canonicalTitle,
        lookupHint = host?.currentMediaLookupHint(),
        imdbId = lookupHints.imdbId,
        tmdbId = lookupHints.tmdbId,
        mediaType = lookupHints.mediaType,
        season = lookupHints.season,
        episode = lookupHints.episode,
        provider = provider,
        durationSeconds = durationSec,
      )
    val cacheKey = buildIntroMarkerCacheKey(lookupRequest)

    introDbSourceKey = provider.sourceKey
    readIntroMarkerCacheEntry(cacheKey)?.let { cachedEntry ->
      applyIntroMarkerCacheEntry(provider, cachedEntry)
      mergeSkipSegments()
      showProviderStatusFeedback(_introDbStatus.value.message)
      return
    }
    _introDbStatus.value =
      IntroDbStatus(
        state = IntroDbStatusState.LOOKING_UP,
        message = "${provider.displayName}: matching title",
      )

    introLookupJob =
      viewModelScope.launch {
        val outcome = introDbRepository.lookupSegments(lookupRequest)
        if (
          generation != introLookupGeneration || currentMediaTitle != lookupKey ||
          !playerPreferences.enableIntroDb.get() || playerPreferences.introSegmentProvider.get() != provider
        ) {
          return@launch
        }

        applyIntroDbOutcome(outcome)
        cacheIntroDbOutcome(cacheKey, outcome)
        mergeSkipSegments()
        showProviderStatusFeedback(_introDbStatus.value.message)
      }
  }

  private fun showProviderStatusFeedback(message: String) {
    if (message.isBlank() || !playerPreferences.showProviderStatusOverlay.get()) return
    playerUpdate.value = PlayerUpdates.ProviderStatusText(message)
  }

  private fun showProviderStatusToast(message: String) {
    if (message.isBlank() || !playerPreferences.showProviderStatusOverlay.get()) return
    showToast(message)
  }

  private fun checkPendingIntroLookup() {
    val pendingTitle = pendingIntroLookupTitle ?: return
    val durationSec = currentDurationSeconds()
    if (durationSec <= 0) return
    pendingIntroLookupTitle = null
    lookupIntroSegments(pendingTitle)
  }

  private fun buildIntroMarkerCacheKey(request: IntroDbLookupRequest): String =
    buildString {
      append(request.provider.sourceKey)
      append('|')
      append(request.lookupHint.orEmpty())
      append('|')
      append(request.mediaTitle)
      append('|')
      append(request.canonicalTitle.orEmpty())
      append('|')
      append(request.imdbId.orEmpty())
      append('|')
      append(request.tmdbId?.toString().orEmpty())
      append('|')
      append(request.mediaType.orEmpty())
      append('|')
      append(request.season?.toString().orEmpty())
      append('|')
      append(request.episode?.toString().orEmpty())
      append('|')
      append(request.durationSeconds?.toString().orEmpty())
    }.md5()

  private fun readIntroMarkerCacheEntry(cacheKey: String): IntroMarkerCacheEntry? {
    val prefKey = INTRO_MARKER_CACHE_PREFIX + cacheKey
    val rawValue = introMarkerCachePrefs.getString(prefKey, null) ?: return null
    val entry =
      runCatching { json.decodeFromString<IntroMarkerCacheEntry>(rawValue) }
        .getOrElse {
          introMarkerCachePrefs.edit().remove(prefKey).apply()
          return null
        }

    val ttl =
      if (entry.outcomeType == INTRO_MARKER_CACHE_LOADED) INTRO_MARKER_CACHE_TTL_MS else INTRO_MARKER_EMPTY_CACHE_TTL_MS
    if ((System.currentTimeMillis() - entry.cachedAtMs) > ttl) {
      introMarkerCachePrefs.edit().remove(prefKey).apply()
      return null
    }

    return entry
  }

  private fun cacheIntroDbOutcome(
    cacheKey: String,
    outcome: IntroDbLookupOutcome,
  ) {
    val cacheEntry =
      when (outcome) {
        is IntroDbLookupOutcome.Loaded ->
          IntroMarkerCacheEntry(
            providerSourceKey = outcome.provider.sourceKey,
            outcomeType = INTRO_MARKER_CACHE_LOADED,
            imdbId = outcome.imdbId,
            message = outcome.message,
            segments = outcome.segments,
          )

        is IntroDbLookupOutcome.NoSegments ->
          IntroMarkerCacheEntry(
            providerSourceKey = outcome.provider.sourceKey,
            outcomeType = INTRO_MARKER_CACHE_NO_SEGMENTS,
            imdbId = outcome.imdbId,
            message = outcome.message,
          )

        is IntroDbLookupOutcome.Unresolved ->
          IntroMarkerCacheEntry(
            providerSourceKey = outcome.provider.sourceKey,
            outcomeType = INTRO_MARKER_CACHE_UNRESOLVED,
            message = outcome.message,
          )

        is IntroDbLookupOutcome.Error -> null
      } ?: return

    introMarkerCachePrefs
      .edit()
      .putString(
        INTRO_MARKER_CACHE_PREFIX + cacheKey,
        json.encodeToString(IntroMarkerCacheEntry.serializer(), cacheEntry),
      ).apply()
    trimIntroMarkerCache()
  }

  private fun trimIntroMarkerCache() {
    val cacheEntries =
      introMarkerCachePrefs.all
        .mapNotNull { (key, value) ->
          if (!key.startsWith(INTRO_MARKER_CACHE_PREFIX) || value !is String) {
            return@mapNotNull null
          }
          val entry =
            runCatching { json.decodeFromString<IntroMarkerCacheEntry>(value) }.getOrNull()
              ?: return@mapNotNull key to null
          key to entry
        }

    if (cacheEntries.size <= INTRO_MARKER_CACHE_MAX_ENTRIES) return

    val keysToRemove =
      cacheEntries
        .sortedBy { (_, entry) -> entry?.cachedAtMs ?: Long.MIN_VALUE }
        .take(cacheEntries.size - INTRO_MARKER_CACHE_MAX_ENTRIES)
        .map { it.first }

    introMarkerCachePrefs
      .edit()
      .apply {
        keysToRemove.forEach(::remove)
      }.apply()
  }

  private fun applyIntroMarkerCacheEntry(
    provider: IntroSegmentProvider,
    entry: IntroMarkerCacheEntry,
  ) {
    introDbSourceKey = entry.providerSourceKey
    when (entry.outcomeType) {
      INTRO_MARKER_CACHE_LOADED -> {
        introDbSegments = entry.segments
        _introDbStatus.value =
          IntroDbStatus(
            state = IntroDbStatusState.LOADED,
            message =
              cacheStatusMessage(
                provider,
                entry.message,
                "loaded ${entry.segments.size} marker${if (entry.segments.size == 1) "" else "s"}",
              ),
            imdbId = entry.imdbId,
            segmentCount = entry.segments.size,
          )
      }

      INTRO_MARKER_CACHE_NO_SEGMENTS -> {
        introDbSegments = emptyList()
        _introDbStatus.value =
          IntroDbStatus(
            state = IntroDbStatusState.NO_SEGMENTS,
            message = cacheStatusMessage(provider, entry.message, "no markers cached"),
            imdbId = entry.imdbId,
          )
      }

      else -> {
        introDbSegments = emptyList()
        _introDbStatus.value =
          IntroDbStatus(
            state = IntroDbStatusState.UNRESOLVED,
            message = cacheStatusMessage(provider, entry.message, "cached title match failed"),
          )
      }
    }
  }

  private fun applyIntroDbOutcome(outcome: IntroDbLookupOutcome) {
    when (outcome) {
      is IntroDbLookupOutcome.Loaded -> {
        introDbSegments = outcome.segments
        introDbSourceKey = outcome.provider.sourceKey
        _introDbStatus.value =
          IntroDbStatus(
            state = IntroDbStatusState.LOADED,
            message = outcome.message,
            imdbId = outcome.imdbId,
            segmentCount = outcome.segments.size,
          )
      }

      is IntroDbLookupOutcome.NoSegments -> {
        introDbSegments = emptyList()
        introDbSourceKey = outcome.provider.sourceKey
        _introDbStatus.value =
          IntroDbStatus(
            state = IntroDbStatusState.NO_SEGMENTS,
            message = outcome.message,
            imdbId = outcome.imdbId,
          )
      }

      is IntroDbLookupOutcome.Unresolved -> {
        introDbSegments = emptyList()
        introDbSourceKey = outcome.provider.sourceKey
        _introDbStatus.value =
          IntroDbStatus(
            state = IntroDbStatusState.UNRESOLVED,
            message = outcome.message,
          )
      }

      is IntroDbLookupOutcome.Error -> {
        introDbSegments = emptyList()
        introDbSourceKey = outcome.provider.sourceKey
        _introDbStatus.value =
          IntroDbStatus(
            state = IntroDbStatusState.ERROR,
            message = outcome.message,
          )
      }
    }
  }

  private fun cacheStatusMessage(
    provider: IntroSegmentProvider,
    message: String,
    fallback: String,
  ): String {
    val sourceMessage = message.ifBlank { "${provider.displayName}: $fallback" }
    return if (sourceMessage.contains("(cached)")) sourceMessage else "$sourceMessage (cached)"
  }

  private fun refreshChapterDerivedSegments(chapters: List<dev.vivvvek.seeker.Segment>) {
    if (!playerPreferences.detectIntroOutroFromChapters.get()) {
      chapterDerivedSegments = emptyList()
      mergeSkipSegments()
      return
    }
    val durationSec = currentDurationSeconds()
    if (durationSec <= 0.0 || chapters.isEmpty()) {
      chapterDerivedSegments = emptyList()
      mergeSkipSegments()
      return
    }

    val derived =
      SkipMarkerResolver.resolveChapters(
        chapters = chapters.map { ChapterSkipMarker(it.name, it.start.toDouble()) },
        durationSeconds = durationSec,
        classify = ::chapterTitleToType,
      )

    chapterDerivedSegments = derived
    mergeSkipSegments()
  }

  private fun chapterTitleToType(title: String?): SkipSegmentType? {
    val introKeywords =
      if (playerPreferences.customIntroKeywordsEnabled.get()) {
        SkipMarkerResolver.parseCustomKeywords(playerPreferences.customIntroKeywords.get())
      } else {
        introKeywordPatterns
      }

    val outroKeywords =
      if (playerPreferences.customOutroKeywordsEnabled.get()) {
        SkipMarkerResolver.parseCustomKeywords(playerPreferences.customOutroKeywords.get())
      } else {
        outroKeywordPatterns
      }

    return SkipMarkerResolver.classifyTitle(
      title = title,
      introKeywords = introKeywords,
      outroKeywords = outroKeywords,
      recapKeywords = recapKeywordPatterns,
      creditsKeywords = creditsKeywordPatterns,
      previewKeywords = previewKeywordPatterns,
    )
  }

  fun removeSubtitle(id: Int) {
    viewModelScope.launch(Dispatchers.IO) {
      // Find the subtitle track info before removing
      val tracks = subtitleTracks.value
      val trackToRemove = tracks.firstOrNull { it.id == id }

      // If it's external, physically delete the file if we can find its URI
      if (trackToRemove?.external == true && trackToRemove.externalFilename != null) {
        val mpvPath = trackToRemove.externalFilename
        val originalUriString = mpvPathToUriMap[mpvPath] ?: mpvPath
        val uri = Uri.parse(originalUriString)

        val deleted = wyzieRepository.deleteSubtitleFile(uri)

        if (deleted) {
          _externalSubtitles.remove(originalUriString)
          mpvPathToUriMap.remove(mpvPath)
          withContext(Dispatchers.Main) {
            showToast("Subtitle deleted")
          }
        }
      }

      PlaybackSession.command("sub-remove", id.toString())
    }
  }

  // --- Media Search and Series Management ---

  private var mediaSearchJob: Job? = null

  private data class WyzieSearchPlan(
    val request: OnlineSubtitleSearchRequest?,
    val missingSelectionMessage: String? = null,
  )

  fun searchMedia(query: String) {
    mediaSearchJob?.cancel()
    if (query.isBlank()) {
      _mediaSearchResults.value = emptyList()
      return
    }

    mediaSearchJob =
      viewModelScope.launch {
        delay(300) // Debounce
        _isSearchingMedia.value = true
        wyzieRepository
          .searchMedia(query)
          .onSuccess { results ->
            _mediaSearchResults.value = results
          }.onFailure {
            // Silent failure for autocomplete, or optionally show toast(if someone is reading this if u need u can impelmen this in future )
          }
        _isSearchingMedia.value = false
      }
  }

  fun selectMedia(result: app.gyrolet.mpvrx.repository.wyzie.WyzieTmdbResult) {
    _mediaSearchResults.value = emptyList() // Clear results after selection
    _onlineSubtitleSearchResults.value = emptyList() // Clear old subtitle results
    val fileInfo = MediaInfoParser.parse(currentMediaTitle)

    if (result.mediaType == "tv") {
      fetchTvShowDetails(
        id = result.id,
        preferredSeason = fileInfo.season,
        preferredEpisode = fileInfo.episode,
      )
    } else {
      searchSubtitles(result.title, year = result.releaseYear ?: fileInfo.year, tmdbId = result.id)
    }
  }

  private fun fetchTvShowDetails(
    id: Int,
    preferredSeason: Int? = null,
    preferredEpisode: Int? = null,
  ) {
    viewModelScope.launch {
      _isFetchingTvDetails.value = true
      wyzieRepository
        .getTvShowDetails(id)
        .onSuccess { details ->
          val validSeasons = details.seasons.filter { it.season_number > 0 }.sortedBy { it.season_number }
          _selectedTvShow.value = details.copy(seasons = validSeasons)
          _selectedSeason.value = null
          _seasonEpisodes.value = emptyList()
          val matchingSeason =
            preferredSeason?.let { wanted ->
              validSeasons.firstOrNull { it.season_number == wanted }
            }
          if (matchingSeason != null) {
            selectSeason(matchingSeason, preferredEpisode)
          }
        }.onFailure {
          showProviderStatusToast("Failed to load series details: ${it.message}")
        }
      _isFetchingTvDetails.value = false
    }
  }

  fun selectSeason(
    season: app.gyrolet.mpvrx.repository.wyzie.WyzieSeason,
    preferredEpisode: Int? = null,
  ) {
    val tvShowId = _selectedTvShow.value?.id ?: return
    _selectedSeason.value = season

    viewModelScope.launch {
      _isFetchingEpisodes.value = true
      wyzieRepository
        .getSeasonEpisodes(tvShowId, season.season_number)
        .onSuccess { episodes ->
          val validEpisodes = episodes.filter { it.episode_number > 0 }.sortedBy { it.episode_number }
          _seasonEpisodes.value = validEpisodes
          val matchingEpisode =
            preferredEpisode?.let { wanted ->
              validEpisodes.firstOrNull { it.episode_number == wanted }
            }
          _selectedEpisode.value = matchingEpisode
          matchingEpisode?.let { episode ->
            val tvShowName = _selectedTvShow.value?.name ?: currentMediaTitle
            searchSubtitles(
              query = tvShowName,
              season = season.season_number,
              episode = episode.episode_number,
              tmdbId = tvShowId,
            )
          }
        }.onFailure {
          showProviderStatusToast("Failed to load series details: ${it.message}")
        }
      _isFetchingEpisodes.value = false
    }
  }

  fun selectEpisode(episode: app.gyrolet.mpvrx.repository.wyzie.WyzieEpisode) {
    _selectedEpisode.value = episode
    val tvShowName = _selectedTvShow.value?.name ?: currentMediaTitle
    searchSubtitles(tvShowName, episode.season_number, episode.episode_number, tmdbId = _selectedTvShow.value?.id)
  }

  fun clearMediaSelection() {
    _selectedTvShow.value = null
    _selectedSeason.value = null
    _seasonEpisodes.value = emptyList()
    _selectedEpisode.value = null
    _mediaSearchResults.value = emptyList()
  }

  // --- Subtitle Search ---
  private var subtitleSearchJob: Job? = null

  fun searchOnlineSubtitles(query: String) {
    val queryInfo = MediaInfoParser.parse(query)
    val fileInfo = MediaInfoParser.parse(currentMediaTitle)
    val searchTitle = queryInfo.title.ifBlank { query.trim() }.ifBlank { fileInfo.title }
    if (searchTitle.isBlank()) return

    val mode = subtitlesPreferences.onlineSubtitleSearchMode.get()
    if (mode != OnlineSubtitleSearchMode.SUBHUB) {
      searchMedia(searchTitle)
    } else {
      _mediaSearchResults.value = emptyList()
    }

    val year = queryInfo.year ?: fileInfo.year
    val wyziePlan = buildWyzieSearchPlan(searchTitle, year, queryInfo, fileInfo)
    val includeWyzie = mode != OnlineSubtitleSearchMode.SUBHUB && wyziePlan.request != null
    val includeSubtitleHub = mode != OnlineSubtitleSearchMode.WYZIE

    if (mode == OnlineSubtitleSearchMode.WYZIE && wyziePlan.request == null) {
      wyziePlan.missingSelectionMessage?.let(::showToast)
      _onlineSubtitleSearchResults.value = emptyList()
      return
    }

    val wyzieRequest = wyziePlan.request ?: OnlineSubtitleSearchRequest(query = searchTitle, year = year)
    searchSubtitles(
      query = wyzieRequest.query,
      season = wyzieRequest.season,
      episode = wyzieRequest.episode,
      year = wyzieRequest.year,
      tmdbId = wyzieRequest.tmdbId,
      includeWyzie = includeWyzie,
      includeSubtitleHub = includeSubtitleHub,
    )
  }

  fun searchSubtitles(
    query: String,
    season: Int? = null,
    episode: Int? = null,
    year: String? = null,
    tmdbId: Int? = null,
    includeWyzie: Boolean = true,
    includeSubtitleHub: Boolean = true,
  ) {
    subtitleSearchJob?.cancel()
    _onlineSubtitleSearchResults.value = emptyList()
    subtitleSearchJob =
      viewModelScope.launch {
        _isSearchingSub.value = true
        val cleanSubHubTitle = MediaInfoParser.parse(query).title.ifBlank { query.trim() }
        val lookupHints = host?.currentPlayerLookupHints() ?: PlayerLookupHints()
        val lookupTitle = lookupHints.canonicalTitle ?: currentMediaTitle
        val cleanLookupTitle = MediaInfoParser.parse(lookupTitle).title.ifBlank { lookupTitle.trim() }
        val matchesCurrentLookup =
          cleanLookupTitle.equals(cleanSubHubTitle, ignoreCase = true) ||
            (tmdbId != null && tmdbId == lookupHints.tmdbId)
        val wyzieRequest =
          OnlineSubtitleSearchRequest(
            query = query,
            tmdbId = tmdbId,
            season = season,
            episode = episode,
            year = year,
            movieHash = _videoHash.value,
          )
        val subtitleHubRequest =
          OnlineSubtitleSearchRequest(
            query = cleanSubHubTitle,
            tmdbId = tmdbId ?: lookupHints.tmdbId.takeIf { matchesCurrentLookup },
            imdbId = lookupHints.imdbId.takeIf { matchesCurrentLookup },
            season = season,
            episode = episode,
            year = year,
          )
        onlineSubtitleOrchestrator
          .search(
            wyzieRequest,
            subtitlesPreferences.onlineSubtitleSearchMode.get(),
            subtitleHubRequest = subtitleHubRequest,
            includeWyzie = includeWyzie,
            includeSubtitleHub = includeSubtitleHub,
            onResults = { results ->
              _onlineSubtitleSearchResults.value = results
            },
          ).onSuccess { results ->
            _onlineSubtitleSearchResults.value = results
          }.onFailure {
            showProviderStatusToast("Search failed: ${it.message}")
          }
        _isSearchingSub.value = false
      }
  }

  private fun buildWyzieSearchPlan(
    searchTitle: String,
    year: String?,
    queryInfo: ParsedMediaInfo,
    fileInfo: ParsedMediaInfo,
  ): WyzieSearchPlan {
    val selectedShow = _selectedTvShow.value
    val selectedSeason = _selectedSeason.value?.season_number
    val selectedEpisode = _selectedEpisode.value?.episode_number

    if (selectedShow != null) {
      if (selectedSeason == null || selectedEpisode == null) {
        return WyzieSearchPlan(
          request = null,
          missingSelectionMessage = "Select season and episode for Wyzie.",
        )
      }
      return WyzieSearchPlan(
        request =
          OnlineSubtitleSearchRequest(
            query = selectedShow.name,
            tmdbId = selectedShow.id,
            season = selectedSeason,
            episode = selectedEpisode,
            year = year,
            movieHash = _videoHash.value,
          ),
      )
    }

    val detectedSeason = queryInfo.season ?: fileInfo.season
    val detectedEpisode = queryInfo.episode ?: fileInfo.episode
    if (detectedSeason != null || detectedEpisode != null) {
      return WyzieSearchPlan(
        request = null,
        missingSelectionMessage = "Select the show, season, and episode for Wyzie.",
      )
    }

    return WyzieSearchPlan(
      request =
        OnlineSubtitleSearchRequest(
          query = searchTitle,
          year = year,
          movieHash = _videoHash.value,
        ),
    )
  }

  fun downloadSubtitle(subtitle: OnlineSubtitle) {
    viewModelScope.launch {
      _isDownloadingSub.value = true
      onlineSubtitleOrchestrator
        .download(subtitle, currentMediaTitle)
        .onSuccess { uri ->
          if (subtitle.isHashMatch) {
            PlaybackSession.setPropertyDouble("sub-delay", 0.0)
            PlaybackSession.setPropertyDouble("secondary-sub-delay", 0.0)
            Log.d(TAG, "Applied perfect-sync subtitle match for ${subtitle.displayName}")
          }
          addSubtitle(uri)
        }.onFailure {
          showToast("Download failed: ${it.message}")
        }
      _isDownloadingSub.value = false
    }
  }

  fun toggleSubtitle(id: Int) {
    val primarySid = getTrackSelectionId("sid")
    val secondarySid = getTrackSelectionId("secondary-sid")

    val wasOff = primarySid <= 0 && secondarySid <= 0

    when {
      id == primarySid -> {
        setTrackSelectionId("secondary-sid", null)
        setTrackSelectionId("sid", secondarySid.takeIf { it > 0 })
      }
      id == secondarySid -> setTrackSelectionId("secondary-sid", null)
      primarySid <= 0 -> setTrackSelectionId("sid", id)
      secondarySid <= 0 -> setTrackSelectionId("secondary-sid", id)
      else -> setTrackSelectionId("sid", id)
    }

    if (wasOff && !subtitlesPreferences.autoEnableSubtitles.get()) {
      subtitlesPreferences.autoEnableSubtitles.set(true)
    }

    syncSubtitleLayout()
  }

  fun selectPrimarySubtitle(id: Int) {
    setTrackSelectionId("sid", id)
    setTrackSelectionId("secondary-sid", null)
    if (!subtitlesPreferences.autoEnableSubtitles.get()) {
      subtitlesPreferences.autoEnableSubtitles.set(true)
    }
    syncSubtitleLayout()
  }

  fun isSubtitleSelected(id: Int): Boolean {
    val primarySid = getTrackSelectionId("sid")
    val secondarySid = getTrackSelectionId("secondary-sid")
    return (id == primarySid && primarySid > 0) || (id == secondarySid && secondarySid > 0)
  }

  fun subtitleSelectionIndicator(id: Int): String? {
    val primarySid = getTrackSelectionId("sid")
    val secondarySid = getTrackSelectionId("secondary-sid")
    return when {
      primarySid > 0 && id == primarySid -> "P"
      secondarySid > 0 && id == secondarySid -> "S"
      else -> null
    }
  }

  private fun getFileNameFromUri(uri: Uri): String? =
    when (uri.scheme) {
      "content" ->
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
          val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }

      "file" -> uri.lastPathSegment
      else -> uri.lastPathSegment
    }

  private fun isValidSubtitleFile(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").lowercase() in VALID_SUBTITLE_EXTENSIONS

  // ==================== Playback Control ====================

  fun pauseUnpause() {
    viewModelScope.launch(playbackStateDispatcher) {
      val wasPaused = PlaybackSession.getPropertyBoolean("pause") ?: PlaybackSession.state.value.paused
      if (wasPaused) {
        val focusGranted = withContext(Dispatchers.Main) { host?.requestAudioFocus() ?: false }
        if (!focusGranted) return@launch
        if (PlaybackSession.state.value.currentItem?.audiobook != null && PlaybackSession.getPropertyBoolean("eof-reached") == true) {
          AudiobookPlayback.resumeAtEnd()
          return@launch
        }
        PlaybackSession.setPropertyBoolean("pause", false)
        syncplayManager.updatePlayerState(precisePosition.value.toDouble(), false, doSeek = false)
      } else {
        PlaybackSession.setPropertyBoolean("pause", true)
        syncplayManager.updatePlayerState(precisePosition.value.toDouble(), true, doSeek = false)
        withContext(Dispatchers.Main) { host?.abandonAudioFocus() }
      }
    }
  }

  fun pause() {
    viewModelScope.launch(playbackStateDispatcher) {
      PlaybackSession.setPropertyBoolean("pause", true)
      syncplayManager.updatePlayerState(precisePosition.value.toDouble(), true, doSeek = false)
      withContext(Dispatchers.Main) { host?.abandonAudioFocus() }
    }
  }

  fun unpause() {
    viewModelScope.launch(playbackStateDispatcher) {
      val focusGranted = withContext(Dispatchers.Main) { host?.requestAudioFocus() ?: false }
      if (!focusGranted) return@launch
      PlaybackSession.setPropertyBoolean("pause", false)
      syncplayManager.updatePlayerState(precisePosition.value.toDouble(), false, doSeek = false)
    }
  }

  // ==================== UI Control ====================

  // PlayerActivity is the sole owner of system bar visibility. Updating the control state
  // here lets it apply both user preferences consistently on all supported Android versions.
  fun showControls() {
    if (sheetShown.value != Sheets.None || panelShown.value != Panels.None) return
    _controlsShown.value = true
    _controlsInteractionEpoch.value++
    controlsVisibleForPolling = true
  }

  fun hideControls() {
    _controlsShown.value = false
    _seekBarShown.value = false
    controlsVisibleForPolling = false
    seekBarVisibleForPolling = false
  }

  fun autoHideControls() {
    _controlsShown.value = false
    _seekBarShown.value = true
    controlsVisibleForPolling = false
    seekBarVisibleForPolling = true
  }

  fun hideSeekBar() {
    _seekBarShown.value = false
    seekBarVisibleForPolling = false
  }


  /**
   * Cancels UI-originated native work before PlayerActivity releases its Surface.
   */
  fun prepareForPlayerTeardown() {
    cancelSeekPreview()
    cancelFrameSeek()
    seekCommitJob?.cancel()
    seekCommitJob = null
    cancelPendingRelativeSeek()

    synchronized(ambientScheduleLock) {
      ambientUpdateGeneration.incrementAndGet()
      ambientDebounceJob?.cancel()
      ambientDebounceJob = null
    }
    synchronized(ppScheduleLock) {
      ppUpdateGeneration.incrementAndGet()
      ppDebounceJob?.cancel()
      ppDebounceJob = null
    }
  }

  fun lockControls() {
    _areControlsLocked.value = true
  }

  fun unlockControls() {
    _areControlsLocked.value = false
  }

  // ==================== Seeking ====================

  fun seekBy(offset: Int) {
    cancelFrameSeek()
    coalesceSeek(offset)
    previewSeekPositionOptimistically(offset)
  }

  /** TV scrubbing never consults the phone's touch/double-tap mappings or saved duration. */
  fun seekFromTvRemote(offset: Int) {
    tvSeekFeedbackJob?.cancel()
    _seekState.update { state ->
      val sameDirection = state.isForwards == (offset > 0)
      state.copy(amount = if (sameDirection) state.amount + offset else offset, isForwards = offset > 0, text = null)
    }
    // TV does not compose GestureHandler, which owns the phone's feedback reset. Restart on
    // every remote repeat, including identical steps at a media boundary, then fade out at rest.
    tvSeekFeedbackJob = viewModelScope.launch {
      delay(800L)
      _seekState.value = SeekState()
    }
    seekBy(offset)
  }

  /**
   * Conflated live preview used by the legacy/full-screen seek mode and the audio seekbar.
   * Pointer events can arrive much faster than a decoder can seek, so only the newest target is
   * applied at a bounded rate. Local preview seeks are keyframe-only and never spam Syncplay peers.
   * Network targets update the thumb/time only until release, preserving the demuxer's read-ahead.
   */
  fun seekPreviewTo(position: Float) = scheduleSeekPreview(position, framePreview = false)

  private fun scheduleSeekPreview(position: Float, framePreview: Boolean) {
    cancelFrameSeek()
    val generation = PlaybackSession.state.value.generation
    previewAbsoluteSeekPositionOptimistically(position.coerceAtLeast(0f).toDouble())
    synchronized(seekPreviewLock) {
      pendingSeekPreviewPosition = position.coerceAtLeast(0f)
      if (seekPreviewJob?.isActive == true && seekPreviewIsFrame == framePreview) return
      seekPreviewJob?.cancel()
      seekPreviewIsFrame = framePreview
      seekPreviewJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
        runSeekPreviewLoop(generation, framePreview)
      }
      seekPreviewJob?.start()
    }
  }

  private suspend fun runSeekPreviewLoop(generation: Long, framePreview: Boolean) {
    var lastSentPosition: Float? = null
    val owner = currentCoroutineContext()[Job]
    val networkFrame = framePreview && PlaybackSession.state.value.currentItem?.let {
      it.networkSource != null || it.originalUri.startsWith("mpvrx-network://")
    } == true
    val intervalMs = if (networkFrame) NETWORK_FRAME_PREVIEW_SEEK_INTERVAL_MS else PREVIEW_SEEK_INTERVAL_MS
    try {
      while (currentCoroutineContext().isActive) {
        val target = synchronized(seekPreviewLock) {
          if (seekPreviewJob !== owner) return
          pendingSeekPreviewPosition?.also { pendingSeekPreviewPosition = null } ?: return
        }
        val previous = lastSentPosition
        if (previous != null && kotlin.math.abs(target - previous) < 0.5f) continue
        val issued = synchronized(seekPreviewLock) {
          // Cancellation and native issuance share this lock, so a late preview cannot overtake release.
          if (seekPreviewJob !== owner || owner?.isActive != true) return
          if (framePreview) {
            PlaybackSession.commandForGeneration(generation, "seek", target.toString(), "absolute+keyframes")
          } else {
            PlaybackSession.previewSeekForGeneration(generation, target)
          }
        }
        if (!issued) return
        lastSentPosition = target
        delay(intervalMs)
      }
    } finally {
      synchronized(seekPreviewLock) {
        if (seekPreviewJob === owner) {
          seekPreviewJob = null
          pendingSeekPreviewPosition = null
        }
      }
    }
  }

  private fun cancelSeekPreview() {
    synchronized(seekPreviewLock) {
      seekPreviewJob?.cancel()
      seekPreviewJob = null
      pendingSeekPreviewPosition = null
    }
  }

  fun seekTo(
    position: Int,
    fast: Boolean = false,
  ) {
    commitSeek(position, if (fast) "absolute+keyframes" else "absolute+exact")
  }

  /** Like relative skips, a user scrub honors hr-seek and mpv.conf instead of forcing exact decoding. */
  fun seekFromScrub(position: Float, resumePlayback: Boolean = false) {
    if (!position.isFinite()) {
      Log.w(TAG, "Ignoring a non-finite scrub target")
      return
    }
    commitSeek(position.toInt(), "absolute", resumePlayback)
  }

  private fun commitSeek(position: Int, seekMode: String, resumePlayback: Boolean = false) {
    cancelFrameSeek()
    cancelSeekPreview()
    val generation = PlaybackSession.state.value.generation
    seekCommitJob?.cancel()
    seekCommitJob = viewModelScope.launch(playbackStateDispatcher) {
      if (!PlaybackSession.isCurrentGeneration(generation)) return@launch
      val maxDuration =
        (PlaybackSession.getPropertyDouble("duration")?.toInt() ?: duration ?: _preciseDuration.value.toInt())
          .coerceAtLeast(0)
      var clampedPosition =
        if (maxDuration > 0) position.coerceIn(0, maxDuration) else position.coerceAtLeast(0)

      // Clamp within AB loop if active
      val loopA = _abLoopState.value.a
      val loopB = _abLoopState.value.b
      if (loopA != null && loopB != null) {
        val min = minOf(loopA.toInt(), loopB.toInt())
        val max = maxOf(loopA.toInt(), loopB.toInt())
        clampedPosition = clampedPosition.coerceIn(min, max)
      }

      if (maxDuration > 0 && clampedPosition !in 0..maxDuration) return@launch

      previewAbsoluteSeekPositionOptimistically(clampedPosition.toDouble())

      // Cancel pending relative seek before absolute seek
      cancelPendingRelativeSeek()

      currentCoroutineContext().ensureActive()
      if (!PlaybackSession.commandForGeneration(generation, "seek", clampedPosition.toString(), seekMode)) return@launch
      if (resumePlayback) {
        val focusGranted = withContext(Dispatchers.Main) { host?.requestAudioFocus() ?: false }
        currentCoroutineContext().ensureActive()
        if (!PlaybackSession.isCurrentGeneration(generation)) return@launch
        if (focusGranted && !PlaybackSession.setPausedForGeneration(generation, false)) return@launch
      }
      syncplayManager.updatePlayerState(
        clampedPosition.toDouble(),
        PlaybackSession.getPropertyBoolean("pause") ?: false,
        doSeek = true,
      )
    }
  }

  private fun cancelPendingRelativeSeek() {
    synchronized(seekCoalesceLock) {
      seekCoalesceRevision++
      seekCoalesceJob?.cancel()
      seekCoalesceJob = null
      pendingSeekOffset = 0
      pendingSeekGeneration = null
    }
  }

  private fun coalesceSeek(offset: Int) {
    val generation = PlaybackSession.state.value.generation
    synchronized(seekCoalesceLock) {
      if (pendingSeekGeneration != generation) pendingSeekOffset = 0
      pendingSeekGeneration = generation
      pendingSeekOffset += offset
      val revision = ++seekCoalesceRevision
      seekCoalesceJob?.cancel()
      seekCoalesceJob = viewModelScope.launch(Dispatchers.IO) {
        delay(SEEK_COALESCE_DELAY_MS)
        // UI taps and IO workers share this total. An older cancelled worker must never
        // consume or clear an offset already reserved for a newer batch.
        val toApply = synchronized(seekCoalesceLock) {
          if (revision != seekCoalesceRevision) return@launch
          pendingSeekOffset.also { pendingSeekOffset = 0 }
        }
        if (toApply == 0 || !PlaybackSession.isCurrentGeneration(generation)) return@launch
        if (PlaybackSession.state.value.currentItem?.audiobook != null) {
          AudiobookPlayback.seekBy(toApply)
          return@launch
        }
        val duration = PlaybackSession.getPropertyDouble("duration")?.toInt()
          ?: duration ?: _preciseDuration.value.toInt()
        val currentPos = PlaybackSession.getPropertyDouble("time-pos") ?: (pos ?: 0).toDouble()
        val reachesEnd = duration > 0 && currentPos + toApply >= duration
        val issued = if (reachesEnd) {
          // Keep the existing explicit EOF transition when skipping past the final frame.
          PlaybackSession.commandForGeneration(generation, "seek", "100", "absolute-percent+exact")
        } else {
          // MPSEEK_DEFAULT inherits hr-seek, including Fast/Precise, short-video/AB-loop
          // policy and user-owned mpv.conf. An explicit +exact overrides all those choices.
          PlaybackSession.commandForGeneration(generation, "seek", toApply.toString(), "relative")
        }
        if (!issued) return@launch
        // Use the pre-seek snapshot; reading an already-updated position would add the skip twice.
        val synchronizedPosition = if (reachesEnd) duration.toDouble() else (currentPos + toApply).coerceAtLeast(0.0)
        syncplayManager.updatePlayerState(
          synchronizedPosition,
          PlaybackSession.getPropertyBoolean("pause") ?: false,
          doSeek = true,
        )
      }
    }
  }

  fun leftSeek() {
    val seconds = doubleTapToSeekDuration
    if ((pos ?: 0) > 0) {
      _seekState.update { state ->
        state.copy(amount = state.amount - seconds, isForwards = false)
      }
    } else {
      _seekState.update { state ->
        state.copy(isForwards = false)
      }
    }
    seekBy(-seconds)
  }

  fun rightSeek() {
    val seconds = doubleTapToSeekDuration
    if ((pos ?: 0) < (duration ?: 0)) {
      _seekState.update { state ->
        state.copy(amount = state.amount + seconds, isForwards = true)
      }
    } else {
      _seekState.update { state ->
        state.copy(isForwards = true)
      }
    }
    seekBy(seconds)
  }

  fun updateSeekAmount(amount: Int) {
    _seekState.update { it.copy(amount = amount) }
  }

  fun updateSeekText(text: String?) {
    _seekState.update { it.copy(text = text) }
  }

  private fun seekToWithText(
    seekValue: Int,
    text: String?,
  ) {
    val currentPos = pos ?: return
    _seekState.value = SeekState(text = text, amount = seekValue - currentPos, isForwards = seekValue > currentPos)
    seekTo(seekValue)
  }

  private fun seekByWithText(
    value: Int,
    text: String?,
  ) {
    val currentPos = pos ?: return
    val maxDuration = duration ?: return

    _seekState.update { s ->
      val newAmount = if ((value < 0 && s.amount < 0) || currentPos + value > maxDuration) 0 else s.amount + value
      SeekState(text = text, amount = newAmount, isForwards = value > 0)
    }
    seekBy(value)
  }

  // ==================== Brightness & Volume ====================

  fun changeBrightnessTo(brightness: Float) {
    val currentHost = host ?: return
    val isAudio = currentHost.isCurrentMediaKnownAudio() || isAudioOnly.value
    val minBrightness = if (isAudio) 0f else -0.75f
    val coercedBrightness = brightness.coerceIn(minBrightness, 1f)
    currentHost.hostWindow.attributes =
      currentHost.hostWindow.attributes.apply {
        screenBrightness = coercedBrightness.coerceIn(0f, 1f)
      }
    currentBrightness.value = coercedBrightness

    // Save brightness to preferences if enabled
    if (playerPreferences.rememberBrightness.get()) {
      playerPreferences.defaultBrightness.set(coercedBrightness)
    }
  }

  fun displayBrightnessSlider() {
    isBrightnessSliderShown.value = true
    brightnessSliderTimestamp.value = System.currentTimeMillis()
  }

  /**
   * Resets the window brightness to follow the system. Used when "remember brightness"
   * is disabled so playback adheres to the device's current brightness (including
   * auto-brightness) instead of being forced to the manual SCREEN_BRIGHTNESS value,
   * which is wrong/dimmer when auto-brightness is active.
   */
  fun resetBrightnessToSystem() {
    val systemBrightness =
      runCatching {
        Settings.System
          .getFloat(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
          .coerceIn(0f, 255f) / 255f
      }.getOrNull() ?: 0f
    currentBrightness.value = systemBrightness
    val currentHost = host ?: return
    runCatching {
      currentHost.hostWindow.attributes =
        currentHost.hostWindow.attributes.apply {
          screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
  }

  fun changeVolumeBy(
    change: Int,
    showUi: Boolean = false,
  ) {
    val isAudio = host?.isCurrentMediaKnownAudio() == true || isAudioOnly.value
    val currentSystemVolume = syncCurrentSystemVolume()
    val mpvVolume = PlaybackSession.getPropertyInt("volume") ?: 100
    // Audio playback must not apply gain boost (>100%). Boost is a video-only feature,
    // so cap the maximum at 100 for audio.
    val absoluteMaxVolume =
      if (isAudio) {
        100
      } else {
        volumeBoostCap ?: (audioPreferences.volumeBoostCap.get() + 100)
      }

    if (currentSystemVolume < maxVolume && mpvVolume > 100) {
      changeMPVVolumeTo(100)
    }

    if (absoluteMaxVolume > 100 && currentSystemVolume == maxVolume) {
      if (mpvVolume == 100 && change < 0) {
        changeVolumeTo(currentSystemVolume + change, showUi)
      }
      val finalMPVVolume = (mpvVolume + change).coerceAtLeast(100)
      if (finalMPVVolume in 100..absoluteMaxVolume) {
        return changeMPVVolumeTo(finalMPVVolume)
      }
    }

    changeVolumeTo(currentSystemVolume + change, showUi)
  }

  fun changeVolumePercentTo(volumePercent: Int) {
    val newPercent = volumePercent.coerceIn(0, 100)
    val newVolume = percentToSystemVolume(newPercent)
    val flags = if (isAudioOnly.value) AudioManager.FLAG_SHOW_UI else 0
    (appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, flags)
    currentVolume.value = syncCurrentSystemVolume()
    currentVolumePercent.value = newPercent

    if (currentVolume.value < maxVolume) {
      val currentMpvVolume = PlaybackSession.getPropertyInt("volume") ?: 100
      if (currentMpvVolume > 100) {
        changeMPVVolumeTo(100)
      }
    }
  }

  fun changeVolumeTo(
    volume: Int,
    showUi: Boolean = false,
  ) {
    val newVolume = volume.coerceIn(0..maxVolume)
    val flags = if (showUi || isAudioOnly.value) AudioManager.FLAG_SHOW_UI else 0
    (appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, flags)
    currentVolume.value = syncCurrentSystemVolume()

    if (currentVolume.value < maxVolume) {
      val currentMpvVolume = PlaybackSession.getPropertyInt("volume") ?: 100
      if (currentMpvVolume > 100) {
        changeMPVVolumeTo(100)
      }
    }
  }

  fun changeMPVVolumeTo(volume: Int) {
    PlaybackSession.setPropertyInt("volume", volume)
  }

  fun displayVolumeSlider() {
    isVolumeSliderShown.value = true
    volumeSliderTimestamp.value = System.currentTimeMillis()
  }

  // mpv property writes block until the core handles them; gestures must never wait on that.
  private val subtitleGestureDispatcher = Dispatchers.IO.limitedParallelism(1)
  private val pendingSubtitleProperties = ConcurrentHashMap<String, Float>()
  private val subtitleFlushQueued = AtomicBoolean(false)
  @Volatile private var savedSubtitleBoxStyle: SubtitleGestureBoxStyle? = null
  @Volatile private var hiddenSubtitleVisibility: String? = null
  private var lastSubtitleGestureText: String? = null

  /** Only the newest value per property reaches mpv, so fast drags never queue up stale frames. */
  private fun queueSubtitleProperty(property: String, value: Float) {
    if (MpvConfigOverridePolicy.isOwnedByMpvConf(property)) return
    pendingSubtitleProperties[property] = value
    if (!subtitleFlushQueued.compareAndSet(false, true)) return
    viewModelScope.launch(subtitleGestureDispatcher) {
      subtitleFlushQueued.set(false)
      pendingSubtitleProperties.keys.toList().forEach { key ->
        pendingSubtitleProperties.remove(key)?.let { PlaybackSession.setPropertyFloat(key, it) }
      }
    }
  }

  fun previewSubtitlePosition(position: Float, target: SubtitleGestureTarget) {
    val newPosition = position.coerceIn(0f, 150f)
    queueSubtitleProperty("${target.prefix}-pos", newPosition)
    val label = appContext.getString(
      if (target == SubtitleGestureTarget.Secondary) R.string.player_sheets_secondary_sub_position
      else R.string.player_sheets_sub_primary_position,
    )
    val text = "$label: ${newPosition.roundToInt()}%"
    if (text != lastSubtitleGestureText) {
      lastSubtitleGestureText = text
      playerUpdate.value = PlayerUpdates.ShowText(text)
    }
  }

  fun previewSubtitleScale(scale: Float, target: SubtitleGestureTarget) {
    queueSubtitleProperty("${target.prefix}-scale", scale)
  }

  fun persistSubtitleGesturePosition(target: SubtitleGestureTarget, position: Float) {
    lastSubtitleGestureText = null
    if (MpvConfigOverridePolicy.isOwnedByMpvConf("${target.prefix}-pos")) return
    val preference = if (target == SubtitleGestureTarget.Secondary) subtitlesPreferences.secondarySubPos else subtitlesPreferences.subPos
    val rounded = clampSubtitlePosition(position.roundToInt())
    if (preference.get() != rounded) preference.set(rounded)
  }

  /**
   * mpv's own background box outlines the real rendered glyphs while a subtitle is dragged or pinched.
   * The style is shared by both tracks, so the other track is hidden until release when [target] is set.
   */
  fun setSubtitleGestureHighlight(active: Boolean, target: SubtitleGestureTarget? = null) {
    viewModelScope.launch(subtitleGestureDispatcher) {
      if (active) {
        if (savedSubtitleBoxStyle != null) return@launch
        val style = SubtitleGestureBoxStyle(
          PlaybackSession.getPropertyString("sub-border-style"),
          PlaybackSession.getPropertyString("sub-back-color"),
          PlaybackSession.getPropertyString("sub-shadow-offset"),
        )
        savedSubtitleBoxStyle = style
        // For background-box, mpv uses sub-shadow-offset as padding around all text lines.
        // Keep larger user margins; restore the exact original value when the gesture ends.
        val padding = maxOf(
          style.shadowOffset?.toFloatOrNull() ?: subtitlesPreferences.shadowOffset.get().toFloat(),
          SUBTITLE_GESTURE_HIGHLIGHT_PADDING,
        )
        PlaybackSession.setPropertyFloat("sub-shadow-offset", padding)
        PlaybackSession.setPropertyString("sub-border-style", "background-box")
        PlaybackSession.setPropertyString("sub-back-color", SUBTITLE_GESTURE_HIGHLIGHT_COLOR)
        val other = SubtitleGestureTarget.entries.firstOrNull { target != null && it != target }
        val visibility = other?.let { "${it.prefix}-visibility" }
        if (visibility != null && PlaybackSession.getPropertyBoolean(visibility) == true) {
          PlaybackSession.setPropertyBoolean(visibility, false)
          hiddenSubtitleVisibility = visibility
        }
      } else {
        restoreSubtitleGestureHighlight()
      }
    }
  }

  private fun restoreSubtitleGestureHighlight() {
    hiddenSubtitleVisibility?.let { PlaybackSession.setPropertyBoolean(it, true) }
    hiddenSubtitleVisibility = null
    val style = savedSubtitleBoxStyle ?: return
    savedSubtitleBoxStyle = null
    // mpv may not have reported a value at gesture start; fall back to the user's saved choice.
    PlaybackSession.setPropertyString("sub-border-style", style.borderStyle ?: subtitlesPreferences.borderStyle.get().value)
    PlaybackSession.setPropertyString(
      "sub-back-color",
      style.backgroundColor ?: subtitlesPreferences.backgroundColor.get().toColorHexString(),
    )
    PlaybackSession.setPropertyString(
      "sub-shadow-offset",
      style.shadowOffset ?: subtitlesPreferences.shadowOffset.get().toString(),
    )
  }

  private fun syncSubtitleLayout(
    primaryPosition: Int = subtitlesPreferences.subPos.get(),
    secondaryPosition: Int = subtitlesPreferences.secondarySubPos.get(),
  ) {
    applySubtitleLayout(primaryPosition, subtitlesPreferences.overrideAssSubs.get(), secondaryPosition)
  }

  private fun syncCurrentSystemVolume(): Int {
    val systemVolume = (appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC)
    currentVolume.value = systemVolume
    currentVolumePercent.value = systemVolumeToPercent(systemVolume)
    return systemVolume
  }

  fun syncCurrentVolumeState() {
    syncCurrentSystemVolume()
  }

  private fun systemVolumeToPercent(systemVolume: Int): Int {
    if (maxVolume <= 0) return 0
    return ((systemVolume.toFloat() / maxVolume.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
  }

  private fun percentToSystemVolume(volumePercent: Int): Int {
    if (maxVolume <= 0) return 0
    return ((volumePercent / 100f) * maxVolume.toFloat()).roundToInt().coerceIn(0, maxVolume)
  }

  // ==================== Video Aspect ====================

  fun changeVideoAspect(
    aspect: VideoAspect,
    showUpdate: Boolean = true,
    resetZoom: Boolean = true,
  ) {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.VIDEO_ASPECT)) return
    when (aspect) {
      VideoAspect.Fit -> {
        // To FIT: Reset both properties to their defaults.
        PlaybackSession.setPropertyDouble("panscan", 0.0)
        PlaybackSession.setPropertyString("video-aspect-override", "no")
      }
      VideoAspect.Crop -> {
        // To CROP: Reset aspect override first, then set panscan
        PlaybackSession.setPropertyString("video-aspect-override", "no")
        PlaybackSession.setPropertyDouble("panscan", 1.0)
      }
      VideoAspect.Stretch -> {
        // To STRETCH: Calculate screen ratio accounting for video rotation
        val currentHost = host ?: return
        @Suppress("DEPRECATION")
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        currentHost.hostWindowManager.defaultDisplay.getRealMetrics(dm)

        // Get video rotation from metadata
        val rotate = PlaybackSession.getPropertyInt("video-params/rotate") ?: 0
        val isVideoRotated = (rotate % 180 == 90) // 90° or 270° rotation

        // Calculate screen ratio, inverting if video is rotated
        val screenRatio =
          if (isVideoRotated) {
            // Video is rotated, so invert the screen ratio
            dm.heightPixels.toDouble() / dm.widthPixels.toDouble()
          } else {
            // Video is not rotated, use normal screen ratio
            dm.widthPixels.toDouble() / dm.heightPixels.toDouble()
          }

        // Set aspect override first, then reset panscan
        // This prevents the brief flash of Fit mode
        PlaybackSession.setPropertyDouble(
          "video-aspect-override",
          VideoAspectGeometry.stretchAspectOverride(screenRatio),
        )
        PlaybackSession.setPropertyDouble("panscan", 0.0)
      }
    }

    // A selected preset replaces manual zoom and pan; internal reapplication preserves them.
    if (resetZoom) resetVideoZoom()

    // Update the state
    playerPreferences.lastVideoAspect.set(aspect)
    playerPreferences.lastCustomAspectRatio.set(-1f)
    _videoAspect.value = aspect
    _currentAspectRatio.value = -1.0 // Reset custom ratio when using standard modes

    onAmbientGeometryChanged()
    // Notify the UI
    if (showUpdate) {
      playerUpdate.value = PlayerUpdates.AspectRatio
    }
  }

  fun setCustomAspectRatio(
    ratio: Double,
    showUpdate: Boolean = true,
    resetZoom: Boolean = true,
  ) {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.VIDEO_ASPECT)) return
    PlaybackSession.setPropertyDouble("panscan", 0.0)
    PlaybackSession.setPropertyDouble("video-aspect-override", ratio)
    if (resetZoom) resetVideoZoom()
    playerPreferences.lastCustomAspectRatio.set(ratio.toFloat())
    _currentAspectRatio.value = ratio
    onAmbientGeometryChanged()
    if (showUpdate) {
      playerUpdate.value = PlayerUpdates.AspectRatio
    }
  }

  fun restoreSavedVideoAspect(showUpdate: Boolean = false) {
    val customAspectRatio = playerPreferences.lastCustomAspectRatio.get()
    if (customAspectRatio > 0f) {
      setCustomAspectRatio(customAspectRatio.toDouble(), showUpdate, resetZoom = false)
      return
    }

    changeVideoAspect(playerPreferences.lastVideoAspect.get(), showUpdate, resetZoom = false)
  }

  fun setAutoCropBlackBars(enabled: Boolean) {
    playerPreferences.autoCropBlackBars.set(enabled)
    cancelAutoCropAnalysis()
    autoCropAnalyzedGeneration = -1L
    if (!enabled) {
      clearAutoCropProperty()
      _autoCropState.value = AutoCropState.IDLE
      return
    }
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AUTO_CROP)) {
      _autoCropState.value = AutoCropState.UNSUPPORTED
      return
    }
    clearAutoCropProperty()
    scheduleAutoCropAnalysis(force = true)
  }

  private fun scheduleAutoCropAnalysis(force: Boolean = false) {
    if (!playerPreferences.autoCropBlackBars.get()) return
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AUTO_CROP)) {
      _autoCropState.value = AutoCropState.UNSUPPORTED
      return
    }

    val session = PlaybackSession.state.value
    if (session.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)) {
      if (session.phase == PlaybackPhase.LOADING && autoCropReadinessJob?.isActive != true) {
        val expectedGeneration = session.generation
        _autoCropState.value = AutoCropState.ANALYZING
        autoCropReadinessJob =
          viewModelScope.launch {
            val readyState =
              withTimeoutOrNull(AUTO_CROP_READY_TIMEOUT_MS) {
                PlaybackSession.state.first { state ->
                  state.generation != expectedGeneration ||
                    state.phase in
                    setOf(
                      PlaybackPhase.READY,
                      PlaybackPhase.BACKGROUND,
                      PlaybackPhase.ERROR,
                      PlaybackPhase.IDLE,
                    )
                }
              }
            autoCropReadinessJob = null
            if (
              readyState?.generation == expectedGeneration &&
              readyState.phase in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
            ) {
              scheduleAutoCropAnalysis(force)
            } else if (PlaybackSession.isCurrentGeneration(expectedGeneration)) {
              _autoCropState.value = AutoCropState.ERROR
            }
          }
      }
      return
    }
    val generation = session.generation
    if (!force && autoCropAnalyzedGeneration == generation) return
    val source = host?.currentThumbnailSource()?.takeIf(String::isNotBlank)
    val durationSeconds =
      sequenceOf(
        PlaybackSession.getPropertyDouble("duration"),
        preciseDuration.value.toDouble(),
      ).filterNotNull().firstOrNull { it.isFinite() && it > 0.0 }
    val sourceWidth = PlaybackSession.getPropertyInt("video-params/w") ?: 0
    val sourceHeight = PlaybackSession.getPropertyInt("video-params/h") ?: 0
    val rotation = (PlaybackSession.getPropertyInt("video-params/rotate") ?: 0).mod(360)
    if (sourceWidth <= 0 || sourceHeight <= 0) {
      if (autoCropReadinessJob?.isActive != true) {
        _autoCropState.value = AutoCropState.ANALYZING
        autoCropReadinessJob =
          viewModelScope.launch {
            val dimensionsReady =
              withTimeoutOrNull(AUTO_CROP_READY_TIMEOUT_MS) {
                while (currentCoroutineContext().isActive && PlaybackSession.isCurrentGeneration(generation)) {
                  val width = PlaybackSession.getPropertyInt("video-params/w") ?: 0
                  val height = PlaybackSession.getPropertyInt("video-params/h") ?: 0
                  if (width > 0 && height > 0) return@withTimeoutOrNull true
                  delay(AUTO_CROP_METADATA_POLL_MS)
                }
                false
              } == true
            autoCropReadinessJob = null
            if (!PlaybackSession.isCurrentGeneration(generation) || !playerPreferences.autoCropBlackBars.get()) {
              return@launch
            }
            if (dimensionsReady) {
              scheduleAutoCropAnalysis(force)
            } else {
              autoCropAnalyzedGeneration = generation
              _autoCropState.value = AutoCropState.UNSUPPORTED
            }
          }
      }
      return
    }

    autoCropAnalyzedGeneration = generation
    autoCropJob?.cancel()
    _autoCropState.value = AutoCropState.ANALYZING
    val sourceIdentity = source ?: session.currentItem?.stableId ?: "generation:$generation"
    val androidReadableSource = source?.let(::isAndroidReadableMediaSource) == true
    // A URL can identify a changing live channel, so only cache results for local/Android media.
    val cacheKey =
      durationSeconds?.takeIf { androidReadableSource }?.let { duration ->
        "$sourceIdentity|$sourceWidth|$sourceHeight|${duration.toLong()}"
      }
    autoCropJob =
      viewModelScope.launch(Dispatchers.IO) {
        Log.i(TAG, "Auto-crop analyzing generation=$generation source=$sourceIdentity")
        val result =
          try {
            val cached = cacheKey?.let(autoCropResultCache::get)
            if (cached != null) {
              AutoCropAnalysisResult.Detected(cached)
            } else if (source != null && durationSeconds != null && androidReadableSource) {
              val positions = autoCropSamplePositions(source, durationSeconds)
              val combined = extractAutoCropSamples(source, positions)
              when {
                combined == null -> detectAutoCropFromActivePlayback(generation, sourceWidth, sourceHeight)
                else -> {
                  val detected = AutoCropAnalyzer.combine(combined.edges)
                  if (detected == null) {
                    AutoCropAnalysisResult.NoBars
                  } else {
                    val sourceEdges =
                      if (combined.framesWereRotated) mapRotatedEdgesToSource(detected, rotation) else detected
                    AutoCropAnalysisResult.Detected(sourceEdges)
                  }
                }
              }
            } else {
              detectAutoCropFromActivePlayback(generation, sourceWidth, sourceHeight)
            }
          } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
          } catch (error: Exception) {
            Log.w(TAG, "Auto-crop analysis failed for generation=$generation", error)
            AutoCropAnalysisResult.Unavailable
          }

        withContext(Dispatchers.Main) {
          if (!PlaybackSession.isCurrentGeneration(generation) || !playerPreferences.autoCropBlackBars.get()) {
            return@withContext
          }
          if (result == AutoCropAnalysisResult.Unavailable) {
            Log.w(TAG, "Auto-crop could not inspect active video frames for generation=$generation")
            clearAutoCropProperty()
            _autoCropState.value = AutoCropState.ERROR
            return@withContext
          }
          if (result == AutoCropAnalysisResult.NoBars) {
            Log.i(TAG, "Auto-crop found no persistent black bars for generation=$generation")
            clearAutoCropProperty()
            _autoCropState.value = AutoCropState.NO_BARS
            return@withContext
          }

          val sourceEdges = (result as AutoCropAnalysisResult.Detected).edges
          val cropValue = buildAutoCropValue(sourceEdges, sourceWidth, sourceHeight)
          if (cropValue == null) {
            Log.i(TAG, "Auto-crop result was too small or unsafe for generation=$generation edges=$sourceEdges")
            clearAutoCropProperty()
            _autoCropState.value = AutoCropState.NO_BARS
            return@withContext
          }

          cacheKey?.let { autoCropResultCache.put(it, sourceEdges) }
          PlaybackSession.setPropertyString("video-crop", cropValue)
          Log.i(TAG, "Auto-crop applied generation=$generation crop=$cropValue edges=$sourceEdges")
          autoCropApplied = true
          _autoCropState.value = AutoCropState.APPLIED
        }
      }
  }

  private suspend fun detectAutoCropFromActivePlayback(
    generation: Long,
    sourceWidth: Int,
    sourceHeight: Int,
  ): AutoCropAnalysisResult {
    if (MpvConfigOverridePolicy.isOwnedByMpvConf("vf")) return detectAutoCropFromCurrentFrames(generation)
    if (PlaybackSession.getPropertyBoolean("current-tracks/video/image") == true) {
      return AutoCropAnalysisResult.Unavailable
    }

    var hwdecBackup: String? = null
    try {
      PlaybackSession.removeVideoFilter(AUTO_CROP_FILTER_LABEL)
      val activeHwdec = PlaybackSession.getPropertyString("hwdec-current").orEmpty()
      val needsSoftwareFrames =
        activeHwdec.isNotBlank() &&
          activeHwdec != "no" &&
          !activeHwdec.endsWith("-copy") &&
          activeHwdec !in setOf("crystalhd", "rkmpp")
      if (needsSoftwareFrames) {
        if (MpvConfigOverridePolicy.isOwnedByMpvConf("hwdec")) {
          return detectAutoCropFromCurrentFrames(generation)
        }
        hwdecBackup = PlaybackSession.getPropertyString("hwdec")?.takeIf(String::isNotBlank)
        PlaybackSession.setPropertyString("hwdec", "no")
        withTimeoutOrNull(AUTO_CROP_HWDEC_TIMEOUT_MS) {
          while (currentCoroutineContext().isActive && PlaybackSession.isCurrentGeneration(generation)) {
            if (PlaybackSession.getPropertyString("hwdec-current").orEmpty() in setOf("", "no")) return@withTimeoutOrNull
            delay(AUTO_CROP_METADATA_POLL_MS)
          }
        }
      }

      if (!PlaybackSession.isCurrentGeneration(generation)) return AutoCropAnalysisResult.Unavailable
      PlaybackSession.command(
        "vf",
        "pre",
        "@$AUTO_CROP_FILTER_LABEL:cropdetect=limit=$AUTO_CROP_DETECT_LIMIT:round=$AUTO_CROP_DETECT_ROUND:reset=0",
      )

      val startedAt = SystemClock.elapsedRealtime()
      val deadline = startedAt + AUTO_CROP_ACTIVE_DETECT_TIMEOUT_MS
      var metadata: AutoCropMetadata? = null
      while (
        currentCoroutineContext().isActive &&
        PlaybackSession.isCurrentGeneration(generation) &&
        SystemClock.elapsedRealtime() < deadline
      ) {
        delay(AUTO_CROP_METADATA_POLL_MS)
        metadata = readAutoCropMetadata()
        if (metadata != null && SystemClock.elapsedRealtime() - startedAt >= AUTO_CROP_ACTIVE_SETTLE_MS) break
      }

      if (!PlaybackSession.isCurrentGeneration(generation)) return AutoCropAnalysisResult.Unavailable
      return metadata?.toAutoCropResult(sourceWidth, sourceHeight) ?: detectAutoCropFromCurrentFrames(generation)
    } finally {
      PlaybackSession.removeVideoFilter(AUTO_CROP_FILTER_LABEL)
      val backup = hwdecBackup
      if (backup != null && PlaybackSession.getPropertyString("hwdec") == "no") {
        PlaybackSession.setPropertyString("hwdec", backup)
      }
    }
  }

  private suspend fun detectAutoCropFromCurrentFrames(generation: Long): AutoCropAnalysisResult {
    val samples = mutableListOf<AutoCropEdges>()
    val deadline = SystemClock.elapsedRealtime() + AUTO_CROP_ACTIVE_FRAME_TIMEOUT_MS
    while (
      samples.size < AUTO_CROP_MIN_VALID_SAMPLES &&
      currentCoroutineContext().isActive &&
      PlaybackSession.isCurrentGeneration(generation) &&
      SystemClock.elapsedRealtime() < deadline
    ) {
      PlaybackSession.grabThumbnail(AUTO_CROP_THUMBNAIL_SIZE)?.analyzeAndRecycle()?.let(samples::add)
      if (samples.size < AUTO_CROP_MIN_VALID_SAMPLES) delay(AUTO_CROP_ACTIVE_FRAME_INTERVAL_MS)
    }
    if (!PlaybackSession.isCurrentGeneration(generation)) return AutoCropAnalysisResult.Unavailable
    if (samples.size < AUTO_CROP_MIN_VALID_SAMPLES) return AutoCropAnalysisResult.Unavailable
    return AutoCropAnalyzer.combine(samples)
      ?.let(AutoCropAnalysisResult::Detected)
      ?: AutoCropAnalysisResult.NoBars
  }

  private fun readAutoCropMetadata(): AutoCropMetadata? {
    fun value(key: String): Int? =
      PlaybackSession
        .getPropertyString("vf-metadata/$AUTO_CROP_FILTER_LABEL/lavfi.cropdetect.$key")
        ?.toIntOrNull()

    return AutoCropMetadata(
      width = value("w") ?: return null,
      height = value("h") ?: return null,
      x = value("x") ?: return null,
      y = value("y") ?: return null,
    )
  }

  private fun AutoCropMetadata.toAutoCropResult(
    sourceWidth: Int,
    sourceHeight: Int,
  ): AutoCropAnalysisResult {
    if (width <= 0 || height <= 0 || x < 0 || y < 0) return AutoCropAnalysisResult.Unavailable
    if (x + width > sourceWidth + AUTO_CROP_DETECT_ROUND || y + height > sourceHeight + AUTO_CROP_DETECT_ROUND) {
      return AutoCropAnalysisResult.Unavailable
    }
    if (width < sourceWidth / 2 || height < sourceHeight / 2) return AutoCropAnalysisResult.NoBars

    val right = (sourceWidth - width - x).coerceAtLeast(0)
    val bottom = (sourceHeight - height - y).coerceAtLeast(0)
    if (x < 2 && y < 2 && right < 2 && bottom < 2) return AutoCropAnalysisResult.NoBars
    return AutoCropAnalysisResult.Detected(
      AutoCropEdges(
        left = x.toFloat() / sourceWidth,
        top = y.toFloat() / sourceHeight,
        right = right.toFloat() / sourceWidth,
        bottom = bottom.toFloat() / sourceHeight,
      ),
    )
  }

  private fun autoCropSamplePositions(
    source: String,
    durationSeconds: Double,
  ): List<Double> {
    val random = Random(source.hashCode())
    val start = durationSeconds * 0.05
    val span = durationSeconds * 0.90
    val segment = span / AUTO_CROP_SAMPLE_COUNT
    return List(AUTO_CROP_SAMPLE_COUNT) { index ->
      start + segment * (index + random.nextDouble(0.2, 0.8))
    }
  }

  private suspend fun extractAutoCropSamples(
    source: String,
    positionsSeconds: List<Double>,
  ): AutoCropSamples? {
    if (isAndroidReadableMediaSource(source)) {
      extractAutoCropWithRetriever(source, positionsSeconds)?.let { samples ->
        if (samples.size >= AUTO_CROP_MIN_VALID_SAMPLES) {
          return AutoCropSamples(samples, framesWereRotated = false)
        }
      }
    }

    val samples =
      positionsSeconds.mapNotNull { position ->
        currentCoroutineContext().ensureActive()
        val bitmap = PlaybackSession.grabThumbnailFast(source, position, AUTO_CROP_THUMBNAIL_SIZE, true)
        bitmap?.analyzeAndRecycle()
      }
    return samples.takeIf { it.size >= AUTO_CROP_MIN_VALID_SAMPLES }?.let {
      AutoCropSamples(it, framesWereRotated = true)
    }
  }

  private suspend fun extractAutoCropWithRetriever(
    source: String,
    positionsSeconds: List<Double>,
  ): List<AutoCropEdges>? =
    runCatching {
      val retriever = MediaMetadataRetriever()
      try {
        val uri = Uri.parse(source)
        when (uri.scheme?.lowercase()) {
          null, "" -> retriever.setDataSource(source)
          "file" -> retriever.setDataSource(uri.path ?: source)
          "content", "android.resource" -> retriever.setDataSource(appContext, uri)
          else -> return@runCatching null
        }
        val sourceWidth =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            ?: AUTO_CROP_THUMBNAIL_SIZE
        val sourceHeight =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            ?: AUTO_CROP_THUMBNAIL_SIZE
        val scale = AUTO_CROP_THUMBNAIL_SIZE.toDouble() / maxOf(sourceWidth, sourceHeight).coerceAtLeast(1)
        val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(2)
        val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(2)
        positionsSeconds.mapNotNull { position ->
          currentCoroutineContext().ensureActive()
          val timeUs = (position * 1_000_000.0).toLong()
          val bitmap =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
              retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetWidth,
                targetHeight,
              )
            } else {
              retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
          bitmap?.analyzeAndRecycle()
        }
      } finally {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) retriever.close() else retriever.release()
      }
    }.getOrNull()

  private fun Bitmap.analyzeAndRecycle(): AutoCropEdges? =
    try {
      AutoCropAnalyzer.analyzeFrame(this)
    } finally {
      recycle()
    }

  private fun isAndroidReadableMediaSource(source: String): Boolean {
    val scheme = Uri.parse(source).scheme?.lowercase()
    return scheme.isNullOrBlank() || scheme in setOf("file", "content", "android.resource")
  }

  private fun mapRotatedEdgesToSource(
    edges: AutoCropEdges,
    rotation: Int,
  ): AutoCropEdges =
    when (rotation) {
      90 -> AutoCropEdges(left = edges.top, top = edges.right, right = edges.bottom, bottom = edges.left)
      180 -> AutoCropEdges(left = edges.right, top = edges.bottom, right = edges.left, bottom = edges.top)
      270 -> AutoCropEdges(left = edges.bottom, top = edges.left, right = edges.top, bottom = edges.right)
      else -> edges
    }

  private fun buildAutoCropValue(
    edges: AutoCropEdges,
    sourceWidth: Int,
    sourceHeight: Int,
  ): String? {
    fun evenFloor(value: Float): Int = (value.toInt().coerceAtLeast(0) / 2) * 2

    val left = evenFloor(edges.left * sourceWidth)
    val top = evenFloor(edges.top * sourceHeight)
    val right = evenFloor(edges.right * sourceWidth)
    val bottom = evenFloor(edges.bottom * sourceHeight)
    val width = evenFloor((sourceWidth - left - right).toFloat())
    val height = evenFloor((sourceHeight - top - bottom).toFloat())
    if (width < sourceWidth / 2 || height < sourceHeight / 2) return null
    if (sourceWidth - width < 4 && sourceHeight - height < 4) return null
    return "${width}x$height+$left+$top"
  }

  private fun clearAutoCropProperty() {
    PlaybackSession.removeVideoFilter(AUTO_CROP_FILTER_LABEL)
    PlaybackSession.setPropertyString("video-crop", "")
    autoCropApplied = false
  }

  private fun cancelAutoCropAnalysis() {
    autoCropJob?.cancel()
    autoCropJob = null
    autoCropReadinessJob?.cancel()
    autoCropReadinessJob = null
    PlaybackSession.removeVideoFilter(AUTO_CROP_FILTER_LABEL)
  }

  private fun refreshStretchAspectAfterCropChange() {
    if (playerPreferences.lastCustomAspectRatio.get() > 0f) return
    if (playerPreferences.lastVideoAspect.get() != VideoAspect.Stretch) return
    changeVideoAspect(VideoAspect.Stretch, showUpdate = false, resetZoom = false)
  }

  // ==================== Screen Rotation ====================

  fun cycleScreenRotations() {
    val currentHost = host ?: return
    if (isAudioOnly.value) {
      currentHost.hostRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
      return
    }
    // Temporarily cycle orientation WITHOUT modifying preferences
    // Preferences remain the single source of truth and will be reapplied on next video
    currentHost.hostRequestedOrientation =
      when (currentHost.hostRequestedOrientation) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        -> {
          ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        else -> {
          ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
      }
  }

  // ==================== Lua Invocation Handling ====================

  fun handleLuaInvocation(
    property: String,
    value: String,
  ) {
    val data = value.removeSurrounding("\"").ifEmpty { return }

    when (property.substringAfterLast("/")) {
      "show_text" -> playerUpdate.value = PlayerUpdates.ShowText(data)
      "toggle_ui" -> handleToggleUI(data)
      "show_panel" -> handleShowPanel(data)
      "seek_to_with_text" -> {
        val (seekValue, text) = data.split("|", limit = 2)
        seekToWithText(seekValue.toInt(), text)
      }
      "seek_by_with_text" -> {
        val (seekValue, text) = data.split("|", limit = 2)
        seekByWithText(seekValue.toInt(), text)
      }
      "seek_by" -> seekByWithText(data.toInt(), null)
      "seek_to" -> seekToWithText(data.toInt(), null)
      "software_keyboard" -> handleSoftwareKeyboard(data)
      // Curl bridge: dispatch the HTTP request asynchronously; response is written
      // back to user-data/mpvrx/curl_response for the script to observe.
      "curl_request" -> scriptCurlBridge.handleRequest(data)
    }

    // Do not clear curl_request or curl_response here:
    //  - curl_request is consumed asynchronously by the bridge.
    //  - curl_response is written by the bridge and must remain readable
    //    until the script's observe_property callback fires.
    if (property.substringAfterLast("/") !in listOf("curl_request", "curl_response")) {
      PlaybackSession.setPropertyString(property, "")
    }
  }

  private fun handleToggleUI(data: String) {
    when (data) {
      "show" -> showControls()
      "toggle" -> if (controlsShown.value) hideControls() else showControls()
      "hide" -> {
        sheetShown.value = Sheets.None
        panelShown.value = Panels.None
        hideControls()
      }
    }
  }

  private fun handleShowPanel(data: String) {
    when (data) {
      "frame_navigation" -> {
        sheetShown.value = Sheets.FrameNavigation
      }
      else -> {
        panelShown.value =
          when (data) {
            "subtitle_settings" -> Panels.SubtitleSettings
            "subtitle_delay" -> Panels.SubtitleDelay
            "audio_delay" -> Panels.AudioDelay
            "video_filters" -> Panels.VideoFilters
            "lua_scripts" -> Panels.LuaScripts
            "hdr_screen_output" -> Panels.HdrScreenOutput
            else -> Panels.None
          }
      }
    }
  }

  private fun handleSoftwareKeyboard(data: String) {
    when (data) {
      "show" -> forceShowSoftwareKeyboard()
      "hide" -> forceHideSoftwareKeyboard()
      "toggle" ->
        if (!inputMethodManager.isActive) {
          forceShowSoftwareKeyboard()
        } else {
          forceHideSoftwareKeyboard()
        }
    }
  }

  @Suppress("DEPRECATION")
  private fun forceShowSoftwareKeyboard() {
    inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
  }

  @Suppress("DEPRECATION")
  private fun forceHideSoftwareKeyboard() {
    inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
  }

  // ==================== Gesture Handling ====================

  fun handleLeftTap() {
    when (gesturePreferences.leftSingleActionGesture.get()) {
      SingleActionGesture.Seek -> leftSeek()
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom ->
        viewModelScope.launch(Dispatchers.IO) {
          PlaybackSession.command("keypress", CustomKeyCodes.DoubleTapLeft.keyCode)
        }
      SingleActionGesture.None -> {}
    }
  }

  fun handleCenterTap() {
    when (gesturePreferences.centerSingleActionGesture.get()) {
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom ->
        viewModelScope.launch(Dispatchers.IO) {
          PlaybackSession.command("keypress", CustomKeyCodes.DoubleTapCenter.keyCode)
        }
      SingleActionGesture.Seek, SingleActionGesture.None -> {}
    }
  }

  fun handleRightTap() {
    when (gesturePreferences.rightSingleActionGesture.get()) {
      SingleActionGesture.Seek -> rightSeek()
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom ->
        viewModelScope.launch(Dispatchers.IO) {
          PlaybackSession.command("keypress", CustomKeyCodes.DoubleTapRight.keyCode)
        }
      SingleActionGesture.None -> {}
    }
  }

  fun handleMediaPrevious() {
    when (gesturePreferences.mediaPreviousGesture.get()) {
      SingleActionGesture.Seek -> leftSeek()
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom ->
        viewModelScope.launch(Dispatchers.IO) {
          PlaybackSession.command("keypress", CustomKeyCodes.MediaPrevious.keyCode)
        }
      SingleActionGesture.None -> {}
    }
  }

  fun handleMediaPlayPause() {
    when (gesturePreferences.mediaPlayGesture.get()) {
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom ->
        viewModelScope.launch(Dispatchers.IO) {
          PlaybackSession.command("keypress", CustomKeyCodes.MediaPlay.keyCode)
        }
      SingleActionGesture.Seek,
      SingleActionGesture.None,
      -> {}
    }
  }

  fun handleMediaNext() {
    when (gesturePreferences.mediaNextGesture.get()) {
      SingleActionGesture.Seek -> rightSeek()
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom ->
        viewModelScope.launch(Dispatchers.IO) {
          PlaybackSession.command("keypress", CustomKeyCodes.MediaNext.keyCode)
        }
      SingleActionGesture.None -> {}
    }
  }

  // ==================== Video Zoom ====================

  fun setVideoZoom(zoom: Float) {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.VIDEO_ZOOM)) {
      PlaybackSession.setVideoTransformZoom(0f)
      return
    }
    PlaybackSession.setVideoTransformZoom(zoom)
  }

  // Video pan (for pan & zoom feature)
  val videoPanX: StateFlow<Float> = PlaybackSession.videoPanX
  val videoPanY: StateFlow<Float> = PlaybackSession.videoPanY

  fun setVideoPan(
    x: Float,
    y: Float,
  ) {
    PlaybackSession.setVideoTransformPan(
      x = if (MpvConfigOverridePolicy.isOwnedByMpvConf("video-pan-x")) 0f else x,
      y = if (MpvConfigOverridePolicy.isOwnedByMpvConf("video-pan-y")) 0f else y,
    )
  }

  fun resetVideoPan() {
    setVideoPan(0f, 0f)
  }

  fun resetVideoZoom() {
    setVideoZoom(0f)
    resetVideoPan()
  }

  // ==================== Frame Navigation ====================

  fun updateFrameInfo() {
    _currentFrame.value = PlaybackSession.getPropertyInt("estimated-frame-number") ?: 0

    val estimatedFrameCount =
      PlaybackSession
        .getPropertyInt("estimated-frame-count")
        ?.takeIf { it > 0 }
    val durationValue = PlaybackSession.getPropertyDouble("duration") ?: 0.0
    val fps =
      PlaybackSession.getPropertyDouble("container-fps")
        ?: PlaybackSession.getPropertyDouble("estimated-vf-fps")
        ?: 0.0

    _totalFrames.value =
      estimatedFrameCount
        ?: if (durationValue > 0 && fps > 0) {
          (durationValue * fps).toInt()
        } else {
          0
        }
  }

  fun seekToFrame(
    targetFrame: Int,
    totalFrames: Int,
    finished: Boolean,
  ) {
    val generation = PlaybackSession.state.value.generation
    val durationSeconds =
      PlaybackSession
        .getPropertyDouble("duration")
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?: currentDurationSeconds().takeIf { it.isFinite() && it > 0.0 }
        ?: return
    val frameCount =
      PlaybackSession
        .getPropertyInt("estimated-frame-count")
        ?.takeIf { it > 0 }
        ?: totalFrames.takeIf { it > 0 }
        ?: return
    val lastFrame = (frameCount - 1).coerceAtLeast(0)
    val clampedFrame = targetFrame.coerceIn(0, lastFrame)
    val requestedPosition = clampedFrame.toDouble() * durationSeconds / frameCount
    val loopA = _abLoopState.value.a
    val loopB = _abLoopState.value.b
    val targetPosition =
      if (loopA != null && loopB != null) {
        requestedPosition.coerceIn(minOf(loopA, loopB), maxOf(loopA, loopB))
      } else {
        requestedPosition
      }
    val effectiveTargetFrame =
      if (targetPosition == requestedPosition) {
        clampedFrame
      } else {
        kotlin.math
          .round(targetPosition * frameCount / durationSeconds)
          .toInt()
          .coerceIn(0, lastFrame)
      }

    if (!finished) {
      scheduleSeekPreview(targetPosition.toFloat(), framePreview = true)
      return
    }

    cancelSeekPreview()
    cancelFrameSeek()
    frameSeekJob =
      viewModelScope.launch(Dispatchers.IO) {
        if (!PlaybackSession.isCurrentGeneration(generation)) return@launch
        cancelPendingRelativeSeek()
        if (PlaybackSession.getPropertyBoolean("pause") != true) {
          PlaybackSession.setPropertyBoolean("pause", true)
          withContext(Dispatchers.Main) { host?.abandonAudioFocus() }
        }

        var refinedPosition = targetPosition
        var remainingFrameDelta: Int? = null
        var exactPassCount = 0
        while (exactPassCount < FRAME_SEEK_EXACT_PASSES) {
          exactPassCount += 1
          if (!PlaybackSession.commandForGeneration(generation, "seek", refinedPosition.toString(), "absolute+exact")) return@launch
          awaitFrameSeekSettled()
          if (!PlaybackSession.isCurrentGeneration(generation)) return@launch
          val observedFrame = PlaybackSession.getPropertyInt("estimated-frame-number") ?: break
          remainingFrameDelta = effectiveTargetFrame - observedFrame
          if (kotlin.math.abs(remainingFrameDelta) <= FRAME_SEEK_MAX_CORRECTION_STEPS) break
          refinedPosition =
            (refinedPosition + remainingFrameDelta.toDouble() * durationSeconds / frameCount)
              .coerceIn(0.0, durationSeconds)
        }

        val correctedFrames =
          remainingFrameDelta
            ?.takeIf { kotlin.math.abs(it) <= FRAME_SEEK_MAX_CORRECTION_STEPS }
            ?.let { frameDelta ->
              repeat(kotlin.math.abs(frameDelta)) {
                if (!PlaybackSession.commandForGeneration(
                    generation,
                    "no-osd",
                    if (frameDelta > 0) "frame-step" else "frame-back-step",
                  )
                ) return@launch
                delay(FRAME_SEEK_CORRECTION_INTERVAL_MS)
              }
              kotlin.math.abs(frameDelta)
            }
            ?: 0
        if (correctedFrames > 0) delay(FRAME_SEEK_CORRECTION_SETTLE_MS)

        if (!PlaybackSession.isCurrentGeneration(generation)) return@launch
        updateFrameInfo()
        val actualPosition = PlaybackSession.getPropertyDouble("time-pos") ?: refinedPosition
        syncplayManager.updatePlayerState(actualPosition, true, doSeek = true)
      }
  }

  fun cancelFrameSeek() {
    frameSeekJob?.cancel()
    frameSeekJob = null
  }

  private suspend fun awaitFrameSeekSettled() {
    var observedSeeking = false
    for (poll in 0 until FRAME_SEEK_MAX_SETTLE_POLLS) {
      val seeking = PlaybackSession.getPropertyBoolean("seeking") == true
      observedSeeking = observedSeeking || seeking
      if (!seeking && (observedSeeking || poll >= FRAME_SEEK_MIN_SETTLE_POLLS)) return
      delay(FRAME_SEEK_POLL_INTERVAL_MS)
    }
  }

  fun toggleFrameNavigationExpanded() {
    val wasExpanded = _isFrameNavigationExpanded.value
    _isFrameNavigationExpanded.update { !it }
    // Update frame info and pause when expanding (going from false to true)
    if (!wasExpanded) {
      // Pause the video if it's playing
      if (paused != true) {
        pauseUnpause()
      }
      updateFrameInfo()
      showFrameInfoOverlay()
      resetFrameNavigationTimer()
    } else {
      // Cancel timer when manually collapsing
      frameNavigationCollapseJob?.cancel()
    }
  }

  private fun showFrameInfoOverlay() {
    playerUpdate.value = PlayerUpdates.FrameInfo(_currentFrame.value, _totalFrames.value)
  }

  fun frameStepForward() {
    viewModelScope.launch(Dispatchers.IO) {
      if (paused != true) {
        pauseUnpause()
        delay(50)
      }
      PlaybackSession.command("no-osd", "frame-step")
      delay(100)
      updateFrameInfo()
      withContext(Dispatchers.Main) {
        showFrameInfoOverlay()
        // Reset the inactivity timer
        resetFrameNavigationTimer()
      }
    }
  }

  fun frameStepBackward() {
    viewModelScope.launch(Dispatchers.IO) {
      if (paused != true) {
        pauseUnpause()
        delay(50)
      }
      PlaybackSession.command("no-osd", "frame-back-step")
      delay(100)
      updateFrameInfo()
      withContext(Dispatchers.Main) {
        showFrameInfoOverlay()
        // Reset the inactivity timer
        resetFrameNavigationTimer()
      }
    }
  }

  private var frameNavigationCollapseJob: Job? = null

  fun resetFrameNavigationTimer() {
    frameNavigationCollapseJob?.cancel()
    frameNavigationCollapseJob =
      viewModelScope.launch {
        delay(10000) // 10 seconds
        if (_isFrameNavigationExpanded.value) {
          _isFrameNavigationExpanded.value = false
        }
      }
  }

  fun takeSnapshot(context: Context) {
    viewModelScope.launch(Dispatchers.IO) {
      _isSnapshotLoading.value = true
      try {
        val includeSubtitles = playerPreferences.includeSubtitlesInSnapshot.get()
        ScreenshotSaver
          .save(
            context = context,
            settings = ScreenshotSettings.fromPreferences(playerPreferences),
            includeSubtitles = includeSubtitles,
          ).getOrThrow()
        withContext(Dispatchers.Main) {
          Toast
            .makeText(
              context,
              context.getString(R.string.player_sheets_frame_navigation_snapshot_saved),
              Toast.LENGTH_SHORT,
            ).show()
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          Toast
            .makeText(
              context,
              context.getString(
                R.string.toast_failed_to_save_snapshot,
                e.message ?: context.getString(R.string.generic_unknown_error),
              ),
              Toast.LENGTH_LONG,
            ).show()
        }
      } finally {
        _isSnapshotLoading.value = false
      }
    }
  }

  // ==================== Playlist Management ====================

  fun hasPlaylistSupport(): Boolean = PlaybackSession.queue.value.isExplicitQueue

  fun getPlaylistInfo(): String? {
    val queue = PlaybackSession.queue.value
    if (!queue.isExplicitQueue || queue.currentIndex !in queue.items.indices) return null
    return "${queue.currentIndex + 1}/${queue.items.size}"
  }

  fun isPlaylistM3U(): Boolean = PlaybackSession.queue.value.isM3u

  fun getPlaylistTotalCount(): Int = PlaybackSession.queue.value.items.size

  fun getPlaylistData(): List<app.gyrolet.mpvrx.ui.player.controls.components.sheets.PlaylistItem>? {
    val queue = PlaybackSession.queue.value
    if (!queue.isExplicitQueue || queue.items.isEmpty()) return null

    // Get current video progress
    val currentPos = pos ?: 0
    val currentDuration = duration ?: 0
    val currentProgress =
      if (currentDuration > 0) {
        ((currentPos.toFloat() / currentDuration.toFloat()) * 100f).coerceIn(0f, 100f)
      } else {
        0f
      }

    return queue.items.mapIndexed { index, item ->
      val uri = Uri.parse(item.originalUri)
      val book = AudiobookPlayback.book.value?.takeIf { it.book.id == item.audiobook?.bookId }
      val bookTrack = book?.tracks?.firstOrNull { it.id == item.audiobook?.trackId }
      val title = bookTrack?.title?.takeIf(String::isNotBlank) ?: item.title?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
      val resolvedUri =
        if (uri.scheme == "content") {
          uri.extractLocalPath()?.let { Uri.fromFile(File(it)) } ?: uri
        } else {
          uri
        }
      val localPath = uri.extractLocalPath() ?: if (uri.scheme == "file") uri.path else null
      val path = localPath ?: resolvedUri.path?.takeIf { File(it).exists() } ?: resolvedUri.toString()
      val isAudio =
        isAudioOnly.value ||
        item.mimeType?.startsWith("audio/", ignoreCase = true) == true ||
        path
          .substringBefore('?')
          .substringBefore('#')
          .substringAfterLast('.', "")
          .lowercase() in FileTypeUtils.AUDIO_EXTENSIONS ||
        resolvedUri.toString().lowercase().contains("audio") ||
        uri.toString().lowercase().contains("audio") ||
        resolvedUri.toString().lowercase().contains("stream.view") ||
        uri.toString().lowercase().contains("stream.view") ||
        resolvedUri.toString().lowercase().contains("/rest/stream") ||
        uri.toString().lowercase().contains("/rest/stream") ||
        (item.artist?.isNotBlank() == true && item.durationSeconds != null)
      val isCurrentlyPlaying = index == queue.currentIndex

      // Try to get from cache first (synchronized access)
      val cacheKey = resolvedUri.toString()
      val extractedDuration =
        item.durationSeconds
          ?.takeIf { seconds -> seconds > 0 }
          ?.let { seconds -> formatDuration(seconds * 1000L) }
          .orEmpty()
      val knownResolution =
        when {
          isAudio -> ""
          isCurrentlyPlaying -> {
            val width = PlaybackSession.getPropertyInt("video-params/w") ?: item.videoWidth
            val height = PlaybackSession.getPropertyInt("video-params/h") ?: item.videoHeight
            if (width > 0 && height > 0) "${width}x$height" else ""
          }
          item.videoWidth > 0 && item.videoHeight > 0 -> "${item.videoWidth}x${item.videoHeight}"
          else -> ""
        }
      val cachedMetadata = synchronized(metadataCache) { metadataCache[cacheKey] }
      val durationStr = cachedMetadata?.first?.takeIf(String::isNotBlank) ?: extractedDuration
      val resolutionStr = cachedMetadata?.second?.takeIf(String::isNotBlank) ?: knownResolution

      app.gyrolet.mpvrx.ui.player.controls.components.sheets.PlaylistItem(
        uri = resolvedUri,
        title = title,
        artist = item.artist.orEmpty(),
        index = index,
        isPlaying = isCurrentlyPlaying,
        path = path,
        progressPercent = if (isCurrentlyPlaying) currentProgress else 0f,
        isWatched = isCurrentlyPlaying && currentProgress >= 95f,
        duration = durationStr,
        resolution = resolutionStr,
        isAudio = isAudio,
        tvgLogo = book?.book?.coverUri ?: item.artworkUri.orEmpty(),
        networkConnectionId = item.networkSource?.connectionId,
        networkPath = item.networkSource?.relativePath.orEmpty(),
        queueEntryId = item.queueEntryId,
      )
    }
  }

  private fun getVideoMetadata(uri: Uri): Pair<String, String> {
    if (app.gyrolet.mpvrx.domain.archive.ZipArchiveMedia.isPlaybackUri(uri.toString())) return "" to ""
    val resolvedUri =
      if (uri.scheme == "content") {
        uri.extractLocalPath()?.let { Uri.fromFile(File(it)) } ?: uri
      } else {
        uri
      }

    // Remote queue items use their authenticated thumbnail path instead of local metadata APIs.
    val scheme = resolvedUri.scheme?.lowercase()
    if (scheme == "http" ||
      scheme == "https" ||
      scheme == "rtmp" ||
      scheme == "rtsp" ||
      scheme == "ftp" ||
      scheme == "sftp" ||
      scheme == "smb" ||
      scheme == "davs" ||
      scheme == "mms" ||
      scheme == NetworkPlaybackUri.SCHEME
    ) {
      return "" to ""
    }

    // Skip M3U/M3U8 files
    val uriString = resolvedUri.toString().lowercase()
    if (uriString.contains(".m3u8") || uriString.contains(".m3u")) {
      return "" to ""
    }

    // Try MediaStore first (much faster - uses cached values)
    val mediaStoreMetadata = getVideoMetadataFromMediaStore(resolvedUri)
    if (mediaStoreMetadata != null) {
      return mediaStoreMetadata
    }

    // Fallback to MediaMetadataRetriever only if MediaStore fails
    val retriever = android.media.MediaMetadataRetriever()
    return try {
      // For file:// URIs, use the path directly (faster)
      if (resolvedUri.scheme == "file") {
        retriever.setDataSource(resolvedUri.path)
      } else {
        // For content:// URIs, use context
        retriever.setDataSource(appContext, resolvedUri)
      }

      retriever.playlistMetadata()
    } catch (e: Exception) {
      android.util.Log.e("PlayerViewModel", "Failed to get video metadata for $resolvedUri", e)
      "" to ""
    } finally {
      try {
        retriever.release()
      } catch (e: Exception) {
        // Ignore release errors
      }
    }
  }

  private suspend fun getVideoMetadata(item: app.gyrolet.mpvrx.ui.player.controls.components.sheets.PlaylistItem): Pair<String, String> {
    val connectionId = item.networkConnectionId
    if (!item.isAudio && connectionId != null && item.networkPath.isNotBlank()) {
      return getNetworkVideoMetadata(connectionId, item.networkPath)
    }
    return getVideoMetadata(item.uri)
  }

  private suspend fun getNetworkVideoMetadata(
    connectionId: Long,
    path: String,
  ): Pair<String, String> =
    withTimeoutOrNull(PLAYLIST_NETWORK_METADATA_TIMEOUT_MS) {
      runInterruptible(Dispatchers.IO) {
        val proxy = NetworkStreamingProxy.getInstance()
        val streamId = "playlist_metadata_${connectionId}_${path.hashCode()}_${System.nanoTime()}"
        val retriever = android.media.MediaMetadataRetriever()
        try {
          val localUrl =
            proxy.registerStream(
              streamId = streamId,
              connectionId = connectionId,
              filePath = path,
              mimeType = NetworkMimeTypes.forFileName(path) ?: "application/octet-stream",
            )
          retriever.setDataSource(
            localUrl,
            mapOf(
              "User-Agent" to "Mozilla/5.0 (Android) mpvRx",
              "Accept" to "*/*",
            ),
          )
          retriever.playlistMetadata()
        } catch (e: Exception) {
          android.util.Log.w("PlayerViewModel", "Failed to get network video metadata", e)
          "" to ""
        } finally {
          runCatching { retriever.release() }
          proxy.unregisterStream(streamId)
        }
      }
    } ?: ("" to "")

  private fun android.media.MediaMetadataRetriever.playlistMetadata(): Pair<String, String> {
    val durationStr =
      extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
        ?.toLongOrNull()
        ?.let(::formatDuration)
        .orEmpty()
    val width = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
    val height = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
    return durationStr to if (width > 0 && height > 0) "${width}x$height" else ""
  }

  /**
   * Get video metadata from MediaStore (fast - uses cached system values).
   * Returns null if the video is not found in MediaStore.
   */
  private fun getVideoMetadataFromMediaStore(uri: Uri): Pair<String, String>? {
    return try {
      val projection =
        arrayOf(
          android.provider.MediaStore.Video.Media.DURATION,
          android.provider.MediaStore.Video.Media.WIDTH,
          android.provider.MediaStore.Video.Media.HEIGHT,
          android.provider.MediaStore.Video.Media.DATA,
        )

      // Determine the query URI based on the input URI scheme
      val queryUri =
        when (uri.scheme) {
          "content" -> {
            // If it's already a content URI, use it directly
            if (uri.toString().startsWith(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                  .toString(),
              )
            ) {
              uri
            } else {
              // Try to find by path if available
              null
            }
          }
          "file" -> {
            // For file:// URIs, query by path
            null
          }
          else -> null
        }

      // Query by URI if we have a content URI
      if (queryUri != null) {
        appContext.contentResolver
          .query(
            queryUri,
            projection,
            null,
            null,
            null,
          )?.use { cursor ->
            if (cursor.moveToFirst()) {
              val durationColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DURATION)
              val widthColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.WIDTH)
              val heightColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.HEIGHT)

              val durationMs = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L
              val width = if (widthColumn >= 0) cursor.getInt(widthColumn) else 0
              val height = if (heightColumn >= 0) cursor.getInt(heightColumn) else 0

              val durationStr = formatDuration(durationMs)

              val resolutionStr =
                if (width > 0 && height > 0) {
                  "${width}x$height"
                } else {
                  ""
                }

              return durationStr to resolutionStr
            }
          }
      }

      // Query by file path if we have a file:// URI or content URI without direct match
      val filePath =
        when (uri.scheme) {
          "file" -> uri.path
          "content" -> {
            // Try to get the file path from content URI
            appContext.contentResolver
              .query(
                uri,
                arrayOf(android.provider.MediaStore.Video.Media.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val dataColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DATA)
                  if (dataColumn >= 0) cursor.getString(dataColumn) else null
                } else {
                  null
                }
              }
          }
          else -> null
        }

      if (filePath != null) {
        val selection = "${android.provider.MediaStore.Video.Media.DATA} = ?"
        val selectionArgs = arrayOf(filePath)

        appContext.contentResolver
          .query(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null,
          )?.use { cursor ->
            if (cursor.moveToFirst()) {
              val durationColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DURATION)
              val widthColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.WIDTH)
              val heightColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.HEIGHT)

              val durationMs = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L
              val width = if (widthColumn >= 0) cursor.getInt(widthColumn) else 0
              val height = if (heightColumn >= 0) cursor.getInt(heightColumn) else 0

              val durationStr = formatDuration(durationMs)

              val resolutionStr =
                if (width > 0 && height > 0) {
                  "${width}x$height"
                } else {
                  ""
                }

              return durationStr to resolutionStr
            }
          }
      }

      null
    } catch (e: Exception) {
      android.util.Log.w(
        "PlayerViewModel",
        "Failed to get metadata from MediaStore for $uri, will try MediaMetadataRetriever",
        e,
      )
      null
    }
  }

  /**
   * Format duration in milliseconds to hh:mm:ss or mm:ss format
   */
  private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return ""

    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
      String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
      String.format("%d:%02d", minutes, seconds)
    }
  }

  fun playPlaylistItem(index: Int) {
    host?.playQueueItem(index)
  }

  fun reorderPlaylistItem(
    from: Int,
    to: Int,
  ) {
    host?.reorderQueueItem(from, to)
  }

  /**
   * Refreshes the playlist items to update the currently playing indicator.
   * Called when a new video starts playing to update the playlist UI.
   */
  fun refreshPlaylistItems(forceMetadata: Boolean = sheetShown.value == Sheets.Playlist) {
    viewModelScope.launch(Dispatchers.IO) {
      val updatedItems = getPlaylistData()
      if (updatedItems != null) {
        // Clear cache if playlist size changed
        if (_playlistItems.value.size != updatedItems.size) {
          metadataCache.evictAll()
        }

        _playlistItems.value = updatedItems

        if (forceMetadata && PlaybackSession.state.value.currentItem?.audiobook == null) {
          // Load metadata only when the playlist sheet is actually in use.
          loadPlaylistMetadataAsync(updatedItems)
        }
      }
    }
  }

  /**
   * Loads metadata for all playlist items asynchronously in the background.
   * Updates the playlist items as metadata becomes available.
   * Uses batched updates to avoid O(n²) complexity with large playlists.
   * Skips metadata extraction for M3U playlists (network streams).
   */
  private fun loadPlaylistMetadataAsync(
    items: List<app.gyrolet.mpvrx.ui.player.controls.components.sheets.PlaylistItem>,
  ) {
    playlistMetadataJob?.cancel()
    playlistMetadataJob =
      viewModelScope.launch(Dispatchers.IO) {
        // Skip metadata extraction for M3U playlists only if they contain network streams
        val queue = PlaybackSession.queue.value
        if (queue.isM3u) {
          val hasNetworkStreams =
            queue.items.any { item ->
              val uri = Uri.parse(item.originalUri)
              val scheme = uri.scheme?.lowercase()
              scheme == "http" ||
                scheme == "https" ||
                scheme == "davs" ||
                scheme == "smb" ||
                scheme == "ftp" ||
                scheme == "sftp"
            }
          if (hasNetworkStreams) {
            Log.d(TAG, "Skipping metadata extraction for M3U playlist with network streams")
            return@launch
          }
        }

        val currentIndex = queue.currentIndex.coerceIn(0, items.lastIndex)
        val metadataItems =
          if (items.size <= PLAYLIST_METADATA_PREFETCH_LIMIT) {
            items
          } else {
            val startIndex = maxOf(0, currentIndex - PLAYLIST_METADATA_PREFETCH_RADIUS)
            val endIndex = minOf(items.lastIndex, currentIndex + PLAYLIST_METADATA_PREFETCH_RADIUS)
            items.subList(startIndex, endIndex + 1)
          }.filter { item ->
            item.duration.isBlank() || (!item.isAudio && item.resolution.isBlank())
          }.filter { item ->
            item.networkConnectionId == null || abs(item.index - currentIndex) <= PLAYLIST_NETWORK_METADATA_PREFETCH_RADIUS
          }

        // Limit concurrent metadata extraction to avoid overwhelming resources
        val batchSize = 5
        metadataItems.chunked(batchSize).forEach { batch ->
          val updates = mutableMapOf<String, Pair<String, String>>()

          // Extract metadata for the batch
          batch.forEach { item ->
            val cacheKey = item.uri.toString()

            val cached = metadataCache.get(cacheKey)
            val needsDuration = item.duration.isBlank() && cached?.first.isNullOrBlank()
            val needsResolution = !item.isAudio && item.resolution.isBlank() && cached?.second.isNullOrBlank()
            if (cached == null && (needsDuration || needsResolution)) {
              // Extract metadata
              val (durationStr, resolutionStr) = getVideoMetadata(item)

              // Update cache and track update
              val merged =
                (durationStr.takeIf(String::isNotBlank) ?: cached?.first.orEmpty()) to
                  (resolutionStr.takeIf(String::isNotBlank) ?: cached?.second.orEmpty())
              updateMetadataCache(cacheKey, merged)
              updates[cacheKey] = merged
            }
          }

          // Apply all batched updates at once (single playlist update)
          if (updates.isNotEmpty()) {
            _playlistItems.value =
              _playlistItems.value.map { currentItem ->
                val cacheKey = currentItem.uri.toString()
                val (durationStr, resolutionStr) = updates[cacheKey] ?: return@map currentItem
                currentItem.copy(
                  duration = durationStr.ifBlank { currentItem.duration },
                  resolution = resolutionStr.ifBlank { currentItem.resolution },
                )
              }
          }
        }
      }
  }

  fun hasNext(): Boolean = PlaybackSession.hasNext()

  fun hasPrevious(): Boolean = PlaybackSession.hasPrevious()

  fun playNext() {
    host?.playNextQueueItem()
  }

  fun playPrevious() {
    host?.playPreviousQueueItem()
  }

  // ==================== Repeat and Shuffle ====================

  fun applyPersistedShuffleState() {
    PlaybackSession.setShuffleEnabled(_shuffleEnabled.value)
    if (_shuffleEnabled.value) {
      host?.onQueueShuffleChanged(true)
    }
  }

  fun cycleRepeatMode() {
    val hasPlaylist = PlaybackSession.queue.value.isExplicitQueue

    _repeatMode.value =
      when (_repeatMode.value) {
        RepeatMode.OFF -> RepeatMode.ONE
        RepeatMode.ONE -> if (hasPlaylist) RepeatMode.ALL else RepeatMode.OFF
        RepeatMode.ALL -> RepeatMode.OFF
      }

    // Persist the repeat mode
    playerPreferences.repeatMode.set(_repeatMode.value)
    PlaybackSession.setRepeatMode(_repeatMode.value)

    // Show overlay update instead of toast
    playerUpdate.value = PlayerUpdates.RepeatMode(_repeatMode.value)
  }

  fun toggleShuffle() {
    _shuffleEnabled.value = !_shuffleEnabled.value

    // Persist the shuffle state
    playerPreferences.shuffleEnabled.set(_shuffleEnabled.value)
    PlaybackSession.setShuffleEnabled(_shuffleEnabled.value)

    // Notify activity to handle shuffle state change
    host?.onQueueShuffleChanged(_shuffleEnabled.value)

    // Show overlay update instead of toast
    playerUpdate.value = PlayerUpdates.Shuffle(_shuffleEnabled.value)
  }

  // ==================== A-B Loop ====================

  fun toggleABLoopExpanded() {
    _abLoopState.update { it.copy(isExpanded = !it.isExpanded) }
  }

  fun setLoopA() {
    if (_abLoopState.value.a != null) {
      _abLoopState.update { it.copy(a = null) }
      PlaybackSession.setPropertyString("ab-loop-a", "no")
      return
    }
    val currentPos = PlaybackSession.getPropertyDouble("time-pos") ?: return
    _abLoopState.update { it.copy(a = currentPos) }
    PlaybackSession.setPropertyDouble("ab-loop-a", currentPos)
  }

  fun setLoopB() {
    if (_abLoopState.value.b != null) {
      _abLoopState.update { it.copy(b = null) }
      PlaybackSession.setPropertyString("ab-loop-b", "no")
      return
    }
    val currentPos = PlaybackSession.getPropertyDouble("time-pos") ?: return
    _abLoopState.update { it.copy(b = currentPos) }
    PlaybackSession.setPropertyDouble("ab-loop-b", currentPos)
  }

  fun clearABLoop() {
    _abLoopState.update { it.copy(a = null, b = null) }
    PlaybackSession.setPropertyString("ab-loop-a", "no")
    PlaybackSession.setPropertyString("ab-loop-b", "no")
  }

  fun formatTimestamp(seconds: Double): String {
    val totalSec = seconds.toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
  }

  // ==================== Mirroring ====================

  fun toggleMirroring() {
    val newMirrorState = !_transformState.value.isMirrored
    _transformState.update { it.copy(isMirrored = newMirrorState) }

    // Use labeled video filter for mirroring to avoid state desync
    if (newMirrorState) {
      PlaybackSession.command("vf", "add", "@mpvrx_hflip:hflip")
    } else {
      PlaybackSession.command("vf", "remove", "@mpvrx_hflip")
    }
    playerUpdate.value =
      PlayerUpdates.ShowText(
        appContext.getString(
          if (newMirrorState) R.string.player_horizontal_flip_on else R.string.player_horizontal_flip_off,
        ),
      )
  }

  fun toggleVerticalFlip() {
    val newState = !_transformState.value.isVerticalFlipped
    _transformState.update { it.copy(isVerticalFlipped = newState) }

    // Use labeled video filter for vflip to avoid state desync
    if (newState) {
      PlaybackSession.command("vf", "add", "@mpvrx_vflip:vflip")
    } else {
      PlaybackSession.command("vf", "remove", "@mpvrx_vflip")
    }

    playerUpdate.value =
      PlayerUpdates.ShowText(
        appContext.getString(if (newState) R.string.player_vertical_flip_on else R.string.player_vertical_flip_off),
      )
  }

  fun toggleHdrScreenOutput() {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)) return
    val nextMode =
      if (_hdrScreenMode.value == HdrScreenMode.OFF) {
        val lastMode = decoderPreferences.lastHdrMode.get()
        val targetMode = if (lastMode == HdrScreenMode.OFF) HdrScreenMode.defaultEnabledMode else lastMode
        if (targetMode == HdrScreenMode.LINEAR && !isLinearHdrAvailable.value) {
          HdrScreenMode.defaultEnabledMode
        } else {
          targetMode
        }
      } else {
        HdrScreenMode.OFF
      }
    setHdrScreenMode(nextMode)
  }

  fun setHdrScreenMode(mode: HdrScreenMode) {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)) return
    val resolvedMode =
      if (mode == HdrScreenMode.LINEAR && !isLinearHdrAvailable.value) {
        HdrScreenMode.defaultEnabledMode
      } else {
        mode
      }
    val pipelineReady = isHdrScreenOutputAvailable(resolvedMode)

    _hdrScreenMode.value = resolvedMode
    _isHdrScreenOutputEnabled.value = pipelineReady && resolvedMode != HdrScreenMode.OFF
    decoderPreferences.hdrScreenMode.set(resolvedMode)
    decoderPreferences.hdrScreenOutput.set(resolvedMode != HdrScreenMode.OFF)
    if (resolvedMode != HdrScreenMode.OFF) {
      decoderPreferences.lastHdrMode.set(resolvedMode)
    }
    applyHdrScreenOutput(resolvedMode)
    restartAmbientIfActive()
    playerUpdate.value =
      PlayerUpdates.ShowText(
        appContext.getString(R.string.hdr_screen_output_update, appContext.getString(resolvedMode.shortTitleRes)),
      )
  }

  private fun isHdrScreenOutputAvailable(mode: HdrScreenMode = _hdrScreenMode.value): Boolean =
    mode != HdrScreenMode.LINEAR || isLinearHdrAvailable.value

  private fun initialHdrScreenMode(): HdrScreenMode {
    if (!decoderPreferences.hdrScreenOutput.get()) {
      return HdrScreenMode.OFF
    }
    val savedMode = decoderPreferences.hdrScreenMode.get()
    if (savedMode == HdrScreenMode.OFF) {
      return HdrScreenMode.OFF
    }
    return if (savedMode == HdrScreenMode.LINEAR &&
      !(decoderPreferences.gpuNext.get() && decoderPreferences.useVulkan.get())
    ) HdrScreenMode.defaultEnabledMode else savedMode
  }

  private fun reconcileHdrModeWithRenderer() {
    if (_hdrScreenMode.value == HdrScreenMode.LINEAR && !isLinearHdrAvailable.value) {
      setHdrScreenMode(HdrScreenMode.defaultEnabledMode)
    } else {
      refreshHdrScreenOutputPipelineState()
    }
  }

  private fun refreshHdrScreenOutputPipelineState(): Boolean {
    val pipelineReady = isHdrScreenOutputAvailable()
    _isHdrScreenOutputPipelineReady.value = pipelineReady
    _isHdrScreenOutputEnabled.value = pipelineReady && _hdrScreenMode.value != HdrScreenMode.OFF
    return pipelineReady
  }

  private fun applyHdrScreenOutput(mode: HdrScreenMode) {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)) {
      _isHdrScreenOutputPipelineReady.value = false
      _isHdrScreenOutputEnabled.value = false
      return
    }
    val pipelineReady = refreshHdrScreenOutputPipelineState()
    runCatching {
      val boostSdr = decoderPreferences.boostSdrToHdr.get()
      // Init-time options are already applied by MPVView. Runtime transitions only need properties.
      applyHdrScreenOutputProperties(mode, pipelineReady, boostSdr)
      applyHdrToysMode(mode, pipelineReady)
    }.onFailure { e ->
      Log.e(TAG, "Error applying HDR screen output: mode=$mode, pipelineReady=$pipelineReady", e)
    }
  }

  /** Re-applies the current HDR mode to a newly loaded video. */
  fun refreshHdrScreenOutputForCurrentVideo() {
    applyHdrScreenOutput(_hdrScreenMode.value)
  }

  fun selectAnime4KMode(mode: Anime4KManager.Mode) {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.ANIME4K)) return
    decoderPreferences.anime4kMode.set(mode.name)
    viewModelScope.launch(Dispatchers.Default) {
      runCatching {
        val shouldRefreshShaderStack =
          if (mode == Anime4KManager.Mode.OFF) {
            clearAnime4KShaders()
            true
          } else {
            val selection =
              selectRuntimeStableAnime4K(
                mode = mode,
                quality = decoderPreferences.anime4kQuality.get(),
                context = appContext,
                enableIn4k = decoderPreferences.anime4kIn4k.get(),
              )
            if (selection.mode == Anime4KManager.Mode.OFF) {
              clearAnime4KShaders()
              true
            } else {
              anime4kManager.setPostFilters(
                darken = decoderPreferences.anime4kDarken.get(),
                thin = decoderPreferences.anime4kThin.get(),
                deblur = decoderPreferences.anime4kDeblur.get(),
              )
              applyAnime4KShaderChain(anime4kManager, selection.mode, selection.quality).also { applied ->
                if (applied) {
                  applyAnime4KStabilityOptions(
                    useVulkan = PlaybackSession.getPropertyString("gpu-api") == "vulkan",
                  )
                }
              }
            }
          }

        if (shouldRefreshShaderStack) {
          restartHdrScreenOutputAndAmbientIfActive()
        }
      }.onFailure { error ->
        Log.e(TAG, "Failed to apply Anime4K mode ${mode.name}", error)
      }
    }
  }

  /**
   * Called after Anime4K or file changes so HDR remains layered with the rest of
   * the shader stack, then the ambient shader is moved back to the final pass.
   */
  fun restartHdrScreenOutputAndAmbientIfActive() {
    refreshHdrScreenOutputForCurrentVideo()
    restartPostProcessingIfActive()
    restartAmbientIfActive()
  }

  private fun applyHdrToysMode(
    mode: HdrScreenMode,
    pipelineReady: Boolean,
  ) {
    val profile = mode.hdrToysProfile
    if (!pipelineReady || profile == null) {
      hdrToysManager.clear()
      return
    }
    if (!hdrToysManager.apply(profile)) {
      playerUpdate.value = PlayerUpdates.ShowText(appContext.getString(R.string.player_hdr_shaders_unavailable))
    }
  }

  // ==================== Ambient Mode Integration ====================

  fun setAmbientLifecycleActive(active: Boolean) {
    if (_isAmbientLifecycleActive.value == active) return
    _isAmbientLifecycleActive.value = active
    if (active) {
      scheduleAmbientUpdate(0)
    } else {
      disableAmbientShader()
    }
  }

  private fun isAmbientRuntimeActive(): Boolean =
    _isAmbientLifecycleActive.value &&
      _isMpvCoreReady.value &&
      _isAmbientEnabled.value &&
      !isAmbientPipActive &&
      !isAudioOnly.value &&
      PlaybackSession.state.value.surfaceAttached &&
      PlaybackSession.state.value.phase in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND) &&
      !MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AMBIENT)

  /** All shader styles share the same renderer and lifecycle. YouTube retains its capture renderer. */
  private fun isAmbientShaderRuntimeActive(): Boolean =
    isAmbientRuntimeActive() && _ambientStyle.value != AmbientStyle.YouTube

  fun setAmbientPipActive(active: Boolean) {
    isAmbientPipActive = active
    if (active) disableAmbientShader() else scheduleAmbientUpdate(0)
  }

  fun onAmbientGeometryChanged() {
    if (isAmbientShaderRuntimeActive()) scheduleAmbientUpdate(0)
  }

  fun toggleAmbientMode() {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AMBIENT)) return
    _isAmbientEnabled.value = !_isAmbientEnabled.value
    playerPreferences.isAmbientEnabled.set(_isAmbientEnabled.value)
    if (_isAmbientEnabled.value) {
      if (_ambientStyle.value != AmbientStyle.YouTube) {
        lastAmbientGeometry = null
        scheduleAmbientUpdate(0)
      }
      playerUpdate.value = PlayerUpdates.ShowText(appContext.getString(R.string.player_ambience_on))
    } else {
      disableAmbientShader()
      playerUpdate.value = PlayerUpdates.ShowText(appContext.getString(R.string.player_ambience_off))
    }
  }

  private fun syncAmbientParamsForStyle(style: AmbientStyle) {
    when (style) {
      AmbientStyle.Glow -> {
        _ambientBlurSamples.value = playerPreferences.ambientBlurSamples.get()
        _ambientMaxRadius.value = playerPreferences.ambientMaxRadius.get()
        _ambientGlowIntensity.value = playerPreferences.ambientGlowIntensity.get()
        _ambientSatBoost.value = playerPreferences.ambientSatBoost.get()
        _ambientVignetteStrength.value = playerPreferences.ambientVignetteStrength.get()
        _ambientWarmth.value = playerPreferences.ambientWarmth.get()
        _ambientFadeCurve.value = playerPreferences.ambientFadeCurve.get()
        _ambientOpacity.value = playerPreferences.ambientOpacity.get()
      }
      AmbientStyle.Cinema -> {
        _ambientBlurSamples.value = playerPreferences.ambientCinemaBlurSamples.get()
        _ambientMaxRadius.value = playerPreferences.ambientCinemaSpread.get()
        _ambientGlowIntensity.value = playerPreferences.ambientCinemaIntensity.get()
        _ambientSatBoost.value = playerPreferences.ambientCinemaSatBoost.get()
        _ambientVignetteStrength.value = playerPreferences.ambientCinemaVignette.get()
        _ambientWarmth.value = playerPreferences.ambientCinemaWarmth.get()
        _ambientFadeCurve.value = playerPreferences.ambientCinemaFadeCurve.get()
        _ambientOpacity.value = playerPreferences.ambientCinemaOpacity.get()
      }
      AmbientStyle.Ambilight -> {
        _ambientBlurSamples.value = playerPreferences.ambientAmbilightBlurSamples.get()
        _ambientMaxRadius.value = playerPreferences.ambientAmbilightSpread.get()
        _ambientGlowIntensity.value = playerPreferences.ambientAmbilightIntensity.get()
        _ambientSatBoost.value = playerPreferences.ambientAmbilightSatBoost.get()
        _ambientVignetteStrength.value = playerPreferences.ambientAmbilightVignette.get()
        _ambientWarmth.value = playerPreferences.ambientAmbilightWarmth.get()
        _ambientFadeCurve.value = playerPreferences.ambientAmbilightFadeCurve.get()
        _ambientOpacity.value = playerPreferences.ambientAmbilightOpacity.get()
      }
      AmbientStyle.Echo -> {
        _ambientBlurSamples.value = playerPreferences.ambientEchoBlurSamples.get()
        _ambientMaxRadius.value = playerPreferences.ambientEchoSpread.get()
        _ambientGlowIntensity.value = playerPreferences.ambientEchoIntensity.get()
        _ambientSatBoost.value = playerPreferences.ambientEchoSatBoost.get()
        _ambientVignetteStrength.value = playerPreferences.ambientEchoVignette.get()
        _ambientWarmth.value = playerPreferences.ambientEchoWarmth.get()
        _ambientFadeCurve.value = playerPreferences.ambientEchoFadeCurve.get()
        _ambientOpacity.value = playerPreferences.ambientEchoOpacity.get()
      }
      AmbientStyle.Mirror -> {
        _ambientBlurSamples.value = playerPreferences.ambientMirrorBlurSamples.get()
        _ambientMaxRadius.value = playerPreferences.ambientMirrorSpread.get()
        _ambientGlowIntensity.value = playerPreferences.ambientMirrorIntensity.get()
        _ambientSatBoost.value = playerPreferences.ambientMirrorSatBoost.get()
        _ambientVignetteStrength.value = playerPreferences.ambientMirrorVignette.get()
        _ambientWarmth.value = playerPreferences.ambientMirrorWarmth.get()
        _ambientFadeCurve.value = playerPreferences.ambientMirrorFadeCurve.get()
        _ambientOpacity.value = playerPreferences.ambientMirrorOpacity.get()
      }
      AmbientStyle.YouTube, AmbientStyle.YouTube2 -> Unit
    }
  }

  /** Switches between Glow, YouTube, Cinema, Echo, and Mirror styles. */
  fun setAmbientStyle(style: AmbientStyle) {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AMBIENT)) return
    if (_ambientStyle.value == style) return
    _ambientStyle.value = style
    playerPreferences.ambientStyle.set(style)
    syncAmbientParamsForStyle(style)
    playerUpdate.value =
      PlayerUpdates.ShowText(
        appContext.getString(R.string.ambient_style_update, appContext.getString(style.titleRes)),
      )
    when (style) {
      AmbientStyle.Glow, AmbientStyle.Ambilight, AmbientStyle.Cinema, AmbientStyle.Echo, AmbientStyle.Mirror, AmbientStyle.YouTube2 -> {
        // Any shader-based mode: invalidate scale and spec caches so a fresh shader is compiled
        // for the new mode. Both lastCompiledSpec and lastCompiledFxSpec are cleared so
        // switching between Glow and Fx modes (or between Fx modes) never hits a stale shader cache.
        lastAmbientGeometry = null
        lastCompiledSpec = null
        lastCompiledFxSpec = null
        lastCompiledStyle = null
        scheduleAmbientUpdate(0)
      }
      AmbientStyle.YouTube -> disableAmbientShader()
    }
  }

  /** Disables the ambient shader and resets video scale. Safe to call from any state. */
  private fun disableAmbientShader() {
    synchronized(ambientScheduleLock) {
      ambientUpdateGeneration.incrementAndGet()
      ambientDebounceJob?.cancel()
      ambientDebounceJob = null
    }
    clearAmbientRenderState()
  }

  private fun clearAmbientRenderState() {
    synchronized(ambientRenderLock) {
      if (!PlaybackSession.clearAmbientShader()) return@synchronized
      ambientShaderFile?.delete()
      ambientShaderFile = null
      lastCompiledSpec = null
      lastCompiledFxSpec = null
        lastCompiledStyle = null
      lastAmbientGeometry = null
    }
  }

  /** Called when the device orientation changes. Refreshes all shader-based modes for new output dimensions. */
  fun onOrientationChanged() {
    if (!isAmbientShaderRuntimeActive()) return

    // The output dimensions are part of the compiled spec. Keep the cache intact here so a
    // redundant orientation callback cannot recompile the same Vulkan shader.
    scheduleAmbientUpdate(200)
  }

  /** Removes the old file-specific ambient shader while preserving the user's selected ambient mode. */
  fun prepareAmbientForNewVideo() {
    if (!_isAmbientEnabled.value || MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AMBIENT)) return
    disableAmbientShader()
    lastAmbientGeometry = null
  }

  /**
   * Re-injects the ambient shader if ambient mode is currently ON.
   * Called after shader-stack changes so ambient stays as the last OUTPUT pass.
   */
  fun restartAmbientIfActive() {
    if (!isAmbientShaderRuntimeActive()) return

    // Move the existing Ambient program back to the final OUTPUT pass without invalidating the
    // compiled-spec cache. Rebuilding an identical file was forcing avoidable SPIR-V/pipeline work.
    synchronized(ambientRenderLock) {
      ambientShaderFile
        ?.takeIf { it.exists() }
        ?.let { file ->
          PlaybackSession.command("change-list", "glsl-shaders", "append", file.absolutePath)
        }
    }
    scheduleAmbientUpdate(200)
  }

  fun setAmbientEdgeBlend(style: AmbientStyle, edgeBlend: Float) {
    val clamped = edgeBlend.takeIf { it.isFinite() }?.coerceIn(0f, 0.1f) ?: 0f
    when (style) {
      AmbientStyle.Glow -> {
        _ambientGlowEdgeBlend.value = clamped
        playerPreferences.ambientGlowEdgeBlend.set(clamped)
        scheduleAmbientUpdate()
      }
      AmbientStyle.YouTube -> {
        _ambientYouTubeEdgeBlend.value = clamped
        playerPreferences.ambientYouTubeEdgeBlend.set(clamped)
      }
      // Fx modes have fixed internal blending — edge-blend slider is hidden in the UI.
      AmbientStyle.Ambilight, AmbientStyle.Cinema, AmbientStyle.Echo, AmbientStyle.Mirror, AmbientStyle.YouTube2 -> Unit
    }
  }

  fun updateAmbientParams(
    blurSamples: Int = _ambientBlurSamples.value,
    maxRadius: Float = _ambientMaxRadius.value,
    glowIntensity: Float = _ambientGlowIntensity.value,
    satBoost: Float = _ambientSatBoost.value,
    vignetteStrength: Float = _ambientVignetteStrength.value,
    warmth: Float = _ambientWarmth.value,
    fadeCurve: Float = _ambientFadeCurve.value,
    opacity: Float = _ambientOpacity.value,
  ) {
    // Slider updates are frequent. Persist only actual changes: writing eight preferences for
    // a single moving slider forced unnecessary flows/disk work alongside shader compilation.
    var changed = false
    val style = _ambientStyle.value
    if (_ambientBlurSamples.value != blurSamples) {
      _ambientBlurSamples.value = blurSamples
      when (style) {
        AmbientStyle.Glow -> playerPreferences.ambientBlurSamples.set(blurSamples)
        AmbientStyle.Ambilight -> playerPreferences.ambientAmbilightBlurSamples.set(blurSamples)
        AmbientStyle.Cinema -> playerPreferences.ambientCinemaBlurSamples.set(blurSamples)
        AmbientStyle.Echo -> playerPreferences.ambientEchoBlurSamples.set(blurSamples)
        AmbientStyle.Mirror -> playerPreferences.ambientMirrorBlurSamples.set(blurSamples)
        AmbientStyle.YouTube, AmbientStyle.YouTube2 -> Unit
      }
      changed = true
    }
    if (_ambientMaxRadius.value != maxRadius) {
      _ambientMaxRadius.value = maxRadius
      when (style) {
        AmbientStyle.Glow -> playerPreferences.ambientMaxRadius.set(maxRadius)
        AmbientStyle.Ambilight -> playerPreferences.ambientAmbilightSpread.set(maxRadius)
        AmbientStyle.Cinema -> playerPreferences.ambientCinemaSpread.set(maxRadius)
        AmbientStyle.Echo -> playerPreferences.ambientEchoSpread.set(maxRadius)
        AmbientStyle.Mirror -> playerPreferences.ambientMirrorSpread.set(maxRadius)
        AmbientStyle.YouTube, AmbientStyle.YouTube2 -> Unit
      }
      changed = true
    }
    if (_ambientGlowIntensity.value != glowIntensity) {
      _ambientGlowIntensity.value = glowIntensity
      when (style) {
        AmbientStyle.Glow -> playerPreferences.ambientGlowIntensity.set(glowIntensity)
        AmbientStyle.Ambilight -> playerPreferences.ambientAmbilightIntensity.set(glowIntensity)
        AmbientStyle.Cinema -> playerPreferences.ambientCinemaIntensity.set(glowIntensity)
        AmbientStyle.Echo -> playerPreferences.ambientEchoIntensity.set(glowIntensity)
        AmbientStyle.Mirror -> playerPreferences.ambientMirrorIntensity.set(glowIntensity)
        AmbientStyle.YouTube, AmbientStyle.YouTube2 -> Unit
      }
      changed = true
    }
    if (_ambientSatBoost.value != satBoost) {
      _ambientSatBoost.value = satBoost
      when (style) {
        AmbientStyle.Glow -> playerPreferences.ambientSatBoost.set(satBoost)
        AmbientStyle.Ambilight -> playerPreferences.ambientAmbilightSatBoost.set(satBoost)
        AmbientStyle.Cinema -> playerPreferences.ambientCinemaSatBoost.set(satBoost)
        AmbientStyle.Echo -> playerPreferences.ambientEchoSatBoost.set(satBoost)
        AmbientStyle.Mirror -> playerPreferences.ambientMirrorSatBoost.set(satBoost)
        AmbientStyle.YouTube, AmbientStyle.YouTube2 -> Unit
      }
      changed = true
    }
    if (_ambientVignetteStrength.value != vignetteStrength) {
      _ambientVignetteStrength.value = vignetteStrength
      when (style) {
        AmbientStyle.Glow -> playerPreferences.ambientVignetteStrength.set(vignetteStrength)
        AmbientStyle.Ambilight -> playerPreferences.ambientAmbilightVignette.set(vignetteStrength)
        AmbientStyle.Cinema -> playerPreferences.ambientCinemaVignette.set(vignetteStrength)
        AmbientStyle.Echo -> playerPreferences.ambientEchoVignette.set(vignetteStrength)
        AmbientStyle.Mirror -> playerPreferences.ambientMirrorVignette.set(vignetteStrength)
        AmbientStyle.YouTube, AmbientStyle.YouTube2 -> Unit
      }
      changed = true
    }
    if (_ambientWarmth.value != warmth) {
      _ambientWarmth.value = warmth
      when (style) {
        AmbientStyle.Glow -> playerPreferences.ambientWarmth.set(warmth)
        AmbientStyle.Ambilight -> playerPreferences.ambientAmbilightWarmth.set(warmth)
        AmbientStyle.Cinema -> playerPreferences.ambientCinemaWarmth.set(warmth)
        AmbientStyle.Echo -> playerPreferences.ambientEchoWarmth.set(warmth)
        AmbientStyle.Mirror -> playerPreferences.ambientMirrorWarmth.set(warmth)
        AmbientStyle.YouTube, AmbientStyle.YouTube2 -> Unit
      }
      changed = true
    }
    if (_ambientFadeCurve.value != fadeCurve) {
      _ambientFadeCurve.value = fadeCurve
      when (style) {
        AmbientStyle.Glow -> playerPreferences.ambientFadeCurve.set(fadeCurve)
        AmbientStyle.Ambilight -> playerPreferences.ambientAmbilightFadeCurve.set(fadeCurve)
        AmbientStyle.Cinema -> playerPreferences.ambientCinemaFadeCurve.set(fadeCurve)
        AmbientStyle.Echo -> playerPreferences.ambientEchoFadeCurve.set(fadeCurve)
        AmbientStyle.Mirror -> playerPreferences.ambientMirrorFadeCurve.set(fadeCurve)
        AmbientStyle.YouTube, AmbientStyle.YouTube2 -> Unit
      }
      changed = true
    }
    if (_ambientOpacity.value != opacity) {
      _ambientOpacity.value = opacity
      when (style) {
        AmbientStyle.Glow -> playerPreferences.ambientOpacity.set(opacity)
        AmbientStyle.Ambilight -> playerPreferences.ambientAmbilightOpacity.set(opacity)
        AmbientStyle.Cinema -> playerPreferences.ambientCinemaOpacity.set(opacity)
        AmbientStyle.Echo -> playerPreferences.ambientEchoOpacity.set(opacity)
        AmbientStyle.Mirror -> playerPreferences.ambientMirrorOpacity.set(opacity)
        AmbientStyle.YouTube, AmbientStyle.YouTube2 -> Unit
      }
      changed = true
    }

    if (changed) scheduleAmbientUpdate()
  }

  private fun scheduleAmbientUpdate(delayMs: Long = 275L) {
    synchronized(ambientScheduleLock) {
      if (!isAmbientShaderRuntimeActive()) return

      val generation = ambientUpdateGeneration.incrementAndGet()
      ambientDebounceJob?.cancel()
      ambientDebounceJob =
        viewModelScope.launch(renderPrepDispatcher) {
          delay(delayMs)
          updateAmbientStretch(generation)
        }
    }
  }

  fun applyAmbientProfileFast() {
    when (_ambientStyle.value) {
      AmbientStyle.Glow -> {
        val preset = AmbientShaderPresets.glowFast
        updateAmbientParams(
          blurSamples = preset.blurSamples,
          maxRadius = preset.maxRadius,
          glowIntensity = preset.glowIntensity,
          satBoost = preset.satBoost,
          vignetteStrength = preset.vignetteStrength,
          warmth = preset.warmth,
          fadeCurve = preset.fadeCurve,
          opacity = preset.opacity,
        )
      }
      AmbientStyle.Cinema -> {
        val preset = FxAmbientPresets.cinemaFast
        updateAmbientParams(
          blurSamples = preset.blurSamples,
          maxRadius = preset.spread,
          glowIntensity = preset.intensity,
          satBoost = preset.satBoost,
          vignetteStrength = preset.vignetteStrength,
          warmth = preset.warmth,
          fadeCurve = preset.fadeCurve,
          opacity = preset.opacity,
        )
      }
      AmbientStyle.Ambilight -> {
        val preset = FxAmbientPresets.ambilightFast
        updateAmbientParams(
          blurSamples = preset.blurSamples,
          maxRadius = preset.spread,
          glowIntensity = preset.intensity,
          satBoost = preset.satBoost,
          vignetteStrength = preset.vignetteStrength,
          warmth = preset.warmth,
          fadeCurve = preset.fadeCurve,
          opacity = preset.opacity,
        )
      }
      AmbientStyle.Echo -> {
        val preset = FxAmbientPresets.echoFast
        updateAmbientParams(
          blurSamples = preset.blurSamples,
          maxRadius = preset.spread,
          glowIntensity = preset.intensity,
          satBoost = preset.satBoost,
          vignetteStrength = preset.vignetteStrength,
          warmth = preset.warmth,
          fadeCurve = preset.fadeCurve,
          opacity = preset.opacity,
        )
      }
      AmbientStyle.Mirror -> {
        val preset = FxAmbientPresets.mirrorFast
        updateAmbientParams(
          blurSamples = preset.blurSamples,
          maxRadius = preset.spread,
          glowIntensity = preset.intensity,
          satBoost = preset.satBoost,
          vignetteStrength = preset.vignetteStrength,
          warmth = preset.warmth,
          fadeCurve = preset.fadeCurve,
          opacity = preset.opacity,
        )
      }
      AmbientStyle.YouTube, AmbientStyle.YouTube2 -> Unit
    }
  }

  /** Balanced profile — good quality/performance trade-off for most devices. */
  fun applyAmbientProfileBalanced() {
    when (_ambientStyle.value) {
      AmbientStyle.Glow -> {
        val preset = AmbientShaderPresets.glowBalanced
        updateAmbientParams(
          blurSamples = preset.blurSamples,
          maxRadius = preset.maxRadius,
          glowIntensity = preset.glowIntensity,
          satBoost = preset.satBoost,
          vignetteStrength = preset.vignetteStrength,
          warmth = preset.warmth,
          fadeCurve = preset.fadeCurve,
          opacity = preset.opacity,
        )
      }
      AmbientStyle.Cinema -> {
        val preset = FxAmbientPresets.cinemaBalanced
        updateAmbientParams(
          blurSamples = preset.blurSamples,
          maxRadius = preset.spread,
          glowIntensity = preset.intensity,
          satBoost = preset.satBoost,
          vignetteStrength = preset.vignetteStrength,
          warmth = preset.warmth,
          fadeCurve = preset.fadeCurve,
          opacity = preset.opacity,
        )
      }
      AmbientStyle.Ambilight -> {
        val preset = FxAmbientPresets.ambilightBalanced
        updateAmbientParams(
          blurSamples = preset.blurSamples,
          maxRadius = preset.spread,
          glowIntensity = preset.intensity,
          satBoost = preset.satBoost,
          vignetteStrength = preset.vignetteStrength,
          warmth = preset.warmth,
          fadeCurve = preset.fadeCurve,
          opacity = preset.opacity,
        )
      }
      AmbientStyle.Echo -> {
        val preset = FxAmbientPresets.echoBalanced
        updateAmbientParams(
          blurSamples = preset.blurSamples,
          maxRadius = preset.spread,
          glowIntensity = preset.intensity,
          satBoost = preset.satBoost,
          vignetteStrength = preset.vignetteStrength,
          warmth = preset.warmth,
          fadeCurve = preset.fadeCurve,
          opacity = preset.opacity,
        )
      }
      AmbientStyle.Mirror -> {
        val preset = FxAmbientPresets.mirrorBalanced
        updateAmbientParams(
          blurSamples = preset.blurSamples,
          maxRadius = preset.spread,
          glowIntensity = preset.intensity,
          satBoost = preset.satBoost,
          vignetteStrength = preset.vignetteStrength,
          warmth = preset.warmth,
          fadeCurve = preset.fadeCurve,
          opacity = preset.opacity,
        )
      }
      AmbientStyle.YouTube, AmbientStyle.YouTube2 -> Unit
    }
  }

  /** High Quality profile — maximum visual fidelity for high-end devices. */
  fun applyAmbientProfileHighQuality() {
    when (_ambientStyle.value) {
      AmbientStyle.Glow -> {
        val preset = AmbientShaderPresets.glowHighQuality
        updateAmbientParams(
          blurSamples = preset.blurSamples,
          maxRadius = preset.maxRadius,
          glowIntensity = preset.glowIntensity,
          satBoost = preset.satBoost,
          vignetteStrength = preset.vignetteStrength,
          warmth = preset.warmth,
          fadeCurve = preset.fadeCurve,
          opacity = preset.opacity,
        )
      }
      AmbientStyle.Cinema -> {
        val preset = FxAmbientPresets.cinemaHighQuality
        updateAmbientParams(
          blurSamples = preset.blurSamples,
          maxRadius = preset.spread,
          glowIntensity = preset.intensity,
          satBoost = preset.satBoost,
          vignetteStrength = preset.vignetteStrength,
          warmth = preset.warmth,
          fadeCurve = preset.fadeCurve,
          opacity = preset.opacity,
        )
      }
      AmbientStyle.Ambilight -> {
        val preset = FxAmbientPresets.ambilightHighQuality
        updateAmbientParams(
          blurSamples = preset.blurSamples,
          maxRadius = preset.spread,
          glowIntensity = preset.intensity,
          satBoost = preset.satBoost,
          vignetteStrength = preset.vignetteStrength,
          warmth = preset.warmth,
          fadeCurve = preset.fadeCurve,
          opacity = preset.opacity,
        )
      }
      AmbientStyle.Echo -> {
        val preset = FxAmbientPresets.echoHighQuality
        updateAmbientParams(
          blurSamples = preset.blurSamples,
          maxRadius = preset.spread,
          glowIntensity = preset.intensity,
          satBoost = preset.satBoost,
          vignetteStrength = preset.vignetteStrength,
          warmth = preset.warmth,
          fadeCurve = preset.fadeCurve,
          opacity = preset.opacity,
        )
      }
      AmbientStyle.Mirror -> {
        val preset = FxAmbientPresets.mirrorHighQuality
        updateAmbientParams(
          blurSamples = preset.blurSamples,
          maxRadius = preset.spread,
          glowIntensity = preset.intensity,
          satBoost = preset.satBoost,
          vignetteStrength = preset.vignetteStrength,
          warmth = preset.warmth,
          fadeCurve = preset.fadeCurve,
          opacity = preset.opacity,
        )
      }
      AmbientStyle.YouTube, AmbientStyle.YouTube2 -> Unit
    }
  }

  private fun applyBatterySaverPolicy() {
    if (_ambientBlurSamples.value <= 4) return
    ambientPreBatterySaverSamples = _ambientBlurSamples.value
    ambientPreBatterySaverRadius = _ambientMaxRadius.value
    ambientPreBatterySaverIntensity = _ambientGlowIntensity.value
    ambientPreBatterySaverSatBoost = _ambientSatBoost.value
    ambientPreBatterySaverVignette = _ambientVignetteStrength.value
    ambientPreBatterySaverWarmth = _ambientWarmth.value
    ambientPreBatterySaverFadeCurve = _ambientFadeCurve.value
    ambientPreBatterySaverOpacity = _ambientOpacity.value
    ambientWasOnBattery = true
    applyAmbientProfileFast()
    playerUpdate.value = PlayerUpdates.ShowText(appContext.getString(R.string.player_ambient_battery_saver_on))
  }

  private fun restoreFromBatterySaver() {
    if (!ambientWasOnBattery) return
    ambientWasOnBattery = false
    updateAmbientParams(
      blurSamples = ambientPreBatterySaverSamples,
      maxRadius = ambientPreBatterySaverRadius,
      glowIntensity = ambientPreBatterySaverIntensity,
      satBoost = ambientPreBatterySaverSatBoost,
      vignetteStrength = ambientPreBatterySaverVignette,
      warmth = ambientPreBatterySaverWarmth,
      fadeCurve = ambientPreBatterySaverFadeCurve,
      opacity = ambientPreBatterySaverOpacity,
    )
    playerUpdate.value = PlayerUpdates.ShowText(appContext.getString(R.string.player_ambient_battery_saver_off))
  }

  fun onBatteryStateChanged(
    isCharging: Boolean,
    level: Int = -1,
    isPowerSaveMode: Boolean = false,
  ) {
    if (!_isAmbientEnabled.value) return
    val shouldThrottle = _isAmbientBatterySaver.value &&
      (!isCharging || isPowerSaveMode || (level in 0..19))

    if (shouldThrottle) {
      applyBatterySaverPolicy()
    } else if (ambientWasOnBattery && (isCharging || (!isPowerSaveMode && (level < 0 || level >= 20)))) {
      restoreFromBatterySaver()
    }
  }

  private suspend fun updateAmbientStretch(generation: Long) {
    if (!isAmbientShaderRuntimeActive() || generation != ambientUpdateGeneration.get()) return
    try {
      val playbackGeneration = PlaybackSession.state.value.generation
      val geometry = PlaybackSession.ambientGeometry()
      if (geometry == null || !geometry.hasBars) {
        synchronized(ambientRenderLock) {
          if (generation == ambientUpdateGeneration.get()) clearAmbientRenderState()
        }
        return
      }
      val currentStyle = _ambientStyle.value
      val samples = ThermalMonitor.clampAmbientSampleBudget(_ambientBlurSamples.value, thermalHeadroom)
      val glowSpec = if (currentStyle == AmbientStyle.Glow) {
        buildAmbientSpec(
          context = geometry.picture,
          blurSamples = samples,
          maxRadius = _ambientMaxRadius.value,
          glowIntensity = _ambientGlowIntensity.value,
          satBoost = _ambientSatBoost.value,
          vignetteStrength = _ambientVignetteStrength.value,
          warmth = _ambientWarmth.value,
          fadeCurve = _ambientFadeCurve.value,
          opacity = _ambientOpacity.value,
          edgeBlend = _ambientGlowEdgeBlend.value,
        )
      } else null
      val fxSpec = if (currentStyle != AmbientStyle.Glow && currentStyle != AmbientStyle.YouTube && currentStyle != AmbientStyle.YouTube2) {
        FxAmbientShaderSpec(
          style = currentStyle,
          scaleX = geometry.picture.scaleX,
          scaleY = geometry.picture.scaleY,
          centerX = geometry.picture.centerX,
          centerY = geometry.picture.centerY,
          blurSamples = samples,
          spread = _ambientMaxRadius.value,
          intensity = _ambientGlowIntensity.value,
          satBoost = _ambientSatBoost.value,
          vignetteStrength = _ambientVignetteStrength.value,
          warmth = _ambientWarmth.value,
          fadeCurve = _ambientFadeCurve.value,
          opacity = _ambientOpacity.value,
        )
      } else null
      if (glowSpec == null && fxSpec == null && currentStyle != AmbientStyle.YouTube2) return
      val shaderIsCurrent = synchronized(ambientRenderLock) {
        geometry == lastAmbientGeometry && glowSpec == lastCompiledSpec && fxSpec == lastCompiledFxSpec &&
          (currentStyle != AmbientStyle.YouTube2 || lastCompiledStyle == currentStyle) &&
          ambientShaderFile?.exists() == true && PlaybackSession.isAmbientShaderActive
      }
      if (shaderIsCurrent) return
      val shaderCode = when (currentStyle) {
        AmbientStyle.Glow -> AmbientShaderBuilder.build(appContext, checkNotNull(glowSpec))
        AmbientStyle.Ambilight -> FxAmbientShaderBuilder.buildAmbilight(appContext, checkNotNull(fxSpec))
        AmbientStyle.Cinema -> FxAmbientShaderBuilder.buildCinema(appContext, checkNotNull(fxSpec))
        AmbientStyle.Echo -> FxAmbientShaderBuilder.buildEcho(appContext, checkNotNull(fxSpec))
        AmbientStyle.Mirror -> FxAmbientShaderBuilder.buildMirror(appContext, checkNotNull(fxSpec))
        AmbientStyle.YouTube2 -> AmbientModeManager.buildAmbientShader(
          sx = geometry.picture.scaleX,
          sy = geometry.picture.scaleY,
          cx = geometry.picture.centerX,
          cy = geometry.picture.centerY,
          samples = 16,
        )
        AmbientStyle.YouTube -> return
      }
      val newFile = File.createTempFile("ambient_", ".glsl", appContext.cacheDir)
      var installed = false
      try {
        withContext(kotlinx.coroutines.Dispatchers.IO) { newFile.writeText(shaderCode) }
        currentCoroutineContext().ensureActive()
        synchronized(ambientRenderLock) {
          if (generation != ambientUpdateGeneration.get() || !isAmbientShaderRuntimeActive() ||
            currentStyle != _ambientStyle.value
          ) return@synchronized
          installed = PlaybackSession.installAmbientShader(newFile.absolutePath, geometry, playbackGeneration)
          if (installed) {
            val previous = ambientShaderFile
            ambientShaderFile = newFile
            lastAmbientGeometry = geometry
            lastCompiledSpec = glowSpec
            lastCompiledFxSpec = fxSpec
            lastCompiledStyle = currentStyle
            previous?.delete()
          }
        }
      } finally {
        // Includes cancellation during writeText/withContext, not only stale installs under the lock.
        if (!installed) newFile.delete()
      }
    } catch (error: kotlinx.coroutines.CancellationException) {
      throw error
    } catch (error: Exception) {
      Log.e(TAG, "Failed to update Ambient renderer", error)
    }
  }

  /**
  * Builds an [AmbientGlowShaderSpec] from the current ambient parameter values.
   * The spec is a lightweight data class that captures all shader inputs;
   * [AmbientShaderBuilder.build] converts it to a GLSL string only when the
   * spec has actually changed from the last compiled version.
   */
  private fun buildAmbientSpec(
    context: AmbientRenderContext,
    blurSamples: Int,
    maxRadius: Float,
    glowIntensity: Float,
    satBoost: Float,
    vignetteStrength: Float,
    warmth: Float,
    fadeCurve: Float,
    opacity: Float,
    edgeBlend: Float,
  ): AmbientGlowShaderSpec {
    val shared =
      AmbientSharedShaderConfig(
        bezelDepth = 0f,
        vignetteStrength = vignetteStrength,
        opacity = opacity,
        edgeBlend = edgeBlend,
      )

    return AmbientGlowShaderSpec(
      context = context,
      shared = shared,
      blurSamples = blurSamples,
      maxRadius = maxRadius,
      glowIntensity = glowIntensity,
      satBoost = satBoost,
      warmth = warmth,
      fadeCurve = fadeCurve,
    )
  }

  // ==================== Post-Processing Implementation ====================

  fun togglePostProcessing() {
    _isPostProcessingEnabled.value = !_isPostProcessingEnabled.value
    playerPreferences.isPostProcessingEnabled.set(_isPostProcessingEnabled.value)
    if (_isPostProcessingEnabled.value) {
      if (_postProcessingPreset.value == PostProcessingPreset.None) {
        setPostProcessingPreset(PostProcessingPreset.Natural)
      } else {
        schedulePostProcessingUpdate(0)
      }
      playerUpdate.value = PlayerUpdates.ShowText(appContext.getString(R.string.pp_on))
    } else {
      clearPostProcessingShaders()
      playerUpdate.value = PlayerUpdates.ShowText(appContext.getString(R.string.pp_off))
    }
  }

  fun setPostProcessingPreset(preset: PostProcessingPreset) {
    if (_postProcessingPreset.value == preset) return
    _postProcessingPreset.value = preset
    playerPreferences.postProcessingPreset.set(preset)
    val name = appContext.getString(preset.displayNameRes)
    playerUpdate.value = PlayerUpdates.ShowText(name)
    if (_isPostProcessingEnabled.value) {
      if (preset == PostProcessingPreset.None) {
        clearPostProcessingShaders()
      } else {
        schedulePostProcessingUpdate(0)
      }
    }
  }

  fun updatePostProcessingParams(params: PostProcessingParams) {
    _postProcessingParams.value = params
    savePostProcessingParamsToPrefs(params)
    if (_isPostProcessingEnabled.value && _postProcessingPreset.value != PostProcessingPreset.None) {
      schedulePostProcessingUpdate(150L)
    }
  }

  fun resetPostProcessingParams() {
    updatePostProcessingParams(PostProcessingParams())
  }

  fun clearPostProcessingShaders() {
    synchronized(ppScheduleLock) {
      ppUpdateGeneration.incrementAndGet()
      ppDebounceJob?.cancel()
      ppDebounceJob = null
    }
    synchronized(ppRenderLock) {
      ppShaderFiles.forEach { file ->
        runCatching { PlaybackSession.command("change-list", "glsl-shaders", "remove", file.absolutePath) }
        file.delete()
      }
      ppShaderFiles = emptyList()
      lastCompiledPpSpec = null
    }
  }

  fun preparePostProcessingForNewVideo() {
    if (!_isPostProcessingEnabled.value) return
    clearPostProcessingShaders()
  }

  fun restartPostProcessingIfActive() {
    if (!_isPostProcessingEnabled.value || _postProcessingPreset.value == PostProcessingPreset.None) return
    clearPostProcessingShaders()
    schedulePostProcessingUpdate(200)
  }

  private fun schedulePostProcessingUpdate(delayMs: Long = 150L) {
    synchronized(ppScheduleLock) {
      if (!_isPostProcessingEnabled.value || _postProcessingPreset.value == PostProcessingPreset.None) return

      val generation = ppUpdateGeneration.incrementAndGet()
      ppDebounceJob?.cancel()
      ppDebounceJob =
        viewModelScope.launch(renderPrepDispatcher) {
          delay(delayMs)
          updatePostProcessingShaders(generation)
        }
    }
  }

  private suspend fun updatePostProcessingShaders(generation: Long) {
    if (!_isPostProcessingEnabled.value || generation != ppUpdateGeneration.get()) return

    runCatching {
      val preset = _postProcessingPreset.value
      if (preset == PostProcessingPreset.None) {
        clearPostProcessingShaders()
        return
      }
      val params = _postProcessingParams.value
      val spec = Pair(preset, params)

      val isCurrent =
        synchronized(ppRenderLock) {
          spec == lastCompiledPpSpec && ppShaderFiles.isNotEmpty() && ppShaderFiles.all { it.exists() }
        }
      if (isCurrent) return

      val shaderSources = preset.buildShaders(params)
      if (shaderSources.isEmpty()) {
        clearPostProcessingShaders()
        return
      }

      val baseSeq = ppShaderSeq.incrementAndGet()
      val writtenFiles = mutableListOf<java.io.File>()
      try {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
          shaderSources.forEachIndexed { index, source ->
            val file = java.io.File(appContext.cacheDir, "pp_${baseSeq}_${index}.glsl")
            file.writeText(source)
            writtenFiles.add(file)
          }
        }
      } catch (error: Throwable) {
        writtenFiles.forEach { it.delete() }
        throw error
      }
      currentCoroutineContext().ensureActive()

      synchronized(ppRenderLock) {
        if (!_isPostProcessingEnabled.value || generation != ppUpdateGeneration.get()) {
          writtenFiles.forEach { it.delete() }
          return@synchronized
        }
        ppShaderFiles.forEach { oldFile ->
          runCatching { PlaybackSession.command("change-list", "glsl-shaders", "remove", oldFile.absolutePath) }
          oldFile.delete()
        }
        writtenFiles.forEach { newFile ->
          PlaybackSession.command("change-list", "glsl-shaders", "append", newFile.absolutePath)
        }
        ppShaderFiles = writtenFiles
        lastCompiledPpSpec = spec
      }
      restartAmbientIfActive()
    }.onFailure { e ->
      if (e is kotlinx.coroutines.CancellationException) throw e
      Log.e(TAG, "Failed to update post-processing shaders", e)
    }
  }

  private fun loadPostProcessingParamsFromPrefs(): PostProcessingParams =
    PostProcessingParams(
      naturalLuma = playerPreferences.ppNaturalLuma.get(),
      naturalChroma = playerPreferences.ppNaturalChroma.get(),
      levelsInputBlack = playerPreferences.ppLevelsInputBlack.get(),
      levelsInputWhite = playerPreferences.ppLevelsInputWhite.get(),
      levelsGamma = playerPreferences.ppLevelsGamma.get(),
      levelsOutputBlack = playerPreferences.ppLevelsOutputBlack.get(),
      levelsOutputWhite = playerPreferences.ppLevelsOutputWhite.get(),
      sharpenAmount = playerPreferences.ppSharpenAmount.get(),
      bloomRadius = playerPreferences.ppBloomRadius.get(),
      bloomAmount = playerPreferences.ppBloomAmount.get(),
      cgSaturation = playerPreferences.ppCgSaturation.get(),
      cgBrightness = playerPreferences.ppCgBrightness.get(),
      cgContrast = playerPreferences.ppCgContrast.get(),
      cgGamma = playerPreferences.ppCgGamma.get(),
      denoiseStrength = playerPreferences.ppDenoiseStrength.get(),
      denoiseRadius = playerPreferences.ppDenoiseRadius.get(),
      denoiseCurve = playerPreferences.ppDenoiseCurve.get(),
      celBands = playerPreferences.ppCelBands.get(),
      celBandContrast = playerPreferences.ppCelBandContrast.get(),
      celBandEdge = playerPreferences.ppCelBandEdge.get(),
      celDetail = playerPreferences.ppCelDetail.get(),
      celOutlineStrength = playerPreferences.ppCelOutlineStrength.get(),
      celOutlineThreshold = playerPreferences.ppCelOutlineThreshold.get(),
      celSaturation = playerPreferences.ppCelSaturation.get(),
      filmicExposure = playerPreferences.ppFilmicExposure.get(),
      filmicToe = playerPreferences.ppFilmicToe.get(),
      filmicShoulder = playerPreferences.ppFilmicShoulder.get(),
      filmicAmount = playerPreferences.ppFilmicAmount.get(),
      blurRadius = playerPreferences.ppBlurRadius.get(),
      blurStrength = playerPreferences.ppBlurStrength.get(),
      blurFocusSize = playerPreferences.ppBlurFocusSize.get(),
      blurFocusSoftness = playerPreferences.ppBlurFocusSoftness.get(),
      cartoonEdgeStrength = playerPreferences.ppCartoonEdgeStrength.get(),
      cartoonLevels = playerPreferences.ppCartoonLevels.get(),
      cartoonSoftEdgeStrength = playerPreferences.ppCartoonSoftEdgeStrength.get(),
      cartoonSoftShadowGuard = playerPreferences.ppCartoonSoftShadowGuard.get(),
      cartoonSoftLevels = playerPreferences.ppCartoonSoftLevels.get(),
      cartoonSoftSmoothing = playerPreferences.ppCartoonSoftSmoothing.get(),
      cartoonSoftSaturation = playerPreferences.ppCartoonSoftSaturation.get(),
      caStrength = playerPreferences.ppCaStrength.get(),
      caFalloff = playerPreferences.ppCaFalloff.get(),
      debandThreshold = playerPreferences.ppDebandThreshold.get(),
      debandRadius = playerPreferences.ppDebandRadius.get(),
      debandGrain = playerPreferences.ppDebandGrain.get(),
      grainIntensity = playerPreferences.ppGrainIntensity.get(),
      grainSize = playerPreferences.ppGrainSize.get(),
      grainColored = playerPreferences.ppGrainColored.get(),
      lensDistortion = playerPreferences.ppLensDistortion.get(),
      lensZoom = playerPreferences.ppLensZoom.get(),
      motionLength = playerPreferences.ppMotionLength.get(),
      motionZoom = playerPreferences.ppMotionZoom.get(),
      motionPan = playerPreferences.ppMotionPan.get(),
      motionAngle = playerPreferences.ppMotionAngle.get(),
      motionSpin = playerPreferences.ppMotionSpin.get(),
      reflHorizon = playerPreferences.ppReflHorizon.get(),
      reflAmount = playerPreferences.ppReflAmount.get(),
      reflFalloff = playerPreferences.ppReflFalloff.get(),
      reflPerspective = playerPreferences.ppReflPerspective.get(),
      reflRipple = playerPreferences.ppReflRipple.get(),
      reflRippleSpeed = playerPreferences.ppReflRippleSpeed.get(),
      scanlinesDensity = playerPreferences.ppScanlinesDensity.get(),
      scanlinesIntensity = playerPreferences.ppScanlinesIntensity.get(),
      scanlinesTint = playerPreferences.ppScanlinesTint.get(),
      splitShadowHue = playerPreferences.ppSplitShadowHue.get(),
      splitShadowStrength = playerPreferences.ppSplitShadowStrength.get(),
      splitHighlightHue = playerPreferences.ppSplitHighlightHue.get(),
      splitHighlightStrength = playerPreferences.ppSplitHighlightStrength.get(),
      splitBalance = playerPreferences.ppSplitBalance.get(),
      vignetteStrength = playerPreferences.ppVignetteStrength.get(),
      vignetteAspect = playerPreferences.ppVignetteAspect.get(),
      wbTemperature = playerPreferences.ppWbTemperature.get(),
      wbTint = playerPreferences.ppWbTint.get(),
      crtDensity = playerPreferences.ppCrtDensity.get(),
      crtRollSpeed = playerPreferences.ppCrtRollSpeed.get(),
      crtBleed = playerPreferences.ppCrtBleed.get(),
    )

  private fun savePostProcessingParamsToPrefs(p: PostProcessingParams) {
    playerPreferences.ppNaturalLuma.set(p.naturalLuma)
    playerPreferences.ppNaturalChroma.set(p.naturalChroma)
    playerPreferences.ppLevelsInputBlack.set(p.levelsInputBlack)
    playerPreferences.ppLevelsInputWhite.set(p.levelsInputWhite)
    playerPreferences.ppLevelsGamma.set(p.levelsGamma)
    playerPreferences.ppLevelsOutputBlack.set(p.levelsOutputBlack)
    playerPreferences.ppLevelsOutputWhite.set(p.levelsOutputWhite)
    playerPreferences.ppSharpenAmount.set(p.sharpenAmount)
    playerPreferences.ppBloomRadius.set(p.bloomRadius)
    playerPreferences.ppBloomAmount.set(p.bloomAmount)
    playerPreferences.ppCgSaturation.set(p.cgSaturation)
    playerPreferences.ppCgBrightness.set(p.cgBrightness)
    playerPreferences.ppCgContrast.set(p.cgContrast)
    playerPreferences.ppCgGamma.set(p.cgGamma)
    playerPreferences.ppDenoiseStrength.set(p.denoiseStrength)
    playerPreferences.ppDenoiseRadius.set(p.denoiseRadius)
    playerPreferences.ppDenoiseCurve.set(p.denoiseCurve)
    playerPreferences.ppCelBands.set(p.celBands)
    playerPreferences.ppCelBandContrast.set(p.celBandContrast)
    playerPreferences.ppCelBandEdge.set(p.celBandEdge)
    playerPreferences.ppCelDetail.set(p.celDetail)
    playerPreferences.ppCelOutlineStrength.set(p.celOutlineStrength)
    playerPreferences.ppCelOutlineThreshold.set(p.celOutlineThreshold)
    playerPreferences.ppCelSaturation.set(p.celSaturation)
    playerPreferences.ppFilmicExposure.set(p.filmicExposure)
    playerPreferences.ppFilmicToe.set(p.filmicToe)
    playerPreferences.ppFilmicShoulder.set(p.filmicShoulder)
    playerPreferences.ppFilmicAmount.set(p.filmicAmount)
    playerPreferences.ppBlurRadius.set(p.blurRadius)
    playerPreferences.ppBlurStrength.set(p.blurStrength)
    playerPreferences.ppBlurFocusSize.set(p.blurFocusSize)
    playerPreferences.ppBlurFocusSoftness.set(p.blurFocusSoftness)
    playerPreferences.ppCartoonEdgeStrength.set(p.cartoonEdgeStrength)
    playerPreferences.ppCartoonLevels.set(p.cartoonLevels)
    playerPreferences.ppCartoonSoftEdgeStrength.set(p.cartoonSoftEdgeStrength)
    playerPreferences.ppCartoonSoftShadowGuard.set(p.cartoonSoftShadowGuard)
    playerPreferences.ppCartoonSoftLevels.set(p.cartoonSoftLevels)
    playerPreferences.ppCartoonSoftSmoothing.set(p.cartoonSoftSmoothing)
    playerPreferences.ppCartoonSoftSaturation.set(p.cartoonSoftSaturation)
    playerPreferences.ppCaStrength.set(p.caStrength)
    playerPreferences.ppCaFalloff.set(p.caFalloff)
    playerPreferences.ppDebandThreshold.set(p.debandThreshold)
    playerPreferences.ppDebandRadius.set(p.debandRadius)
    playerPreferences.ppDebandGrain.set(p.debandGrain)
    playerPreferences.ppGrainIntensity.set(p.grainIntensity)
    playerPreferences.ppGrainSize.set(p.grainSize)
    playerPreferences.ppGrainColored.set(p.grainColored)
    playerPreferences.ppLensDistortion.set(p.lensDistortion)
    playerPreferences.ppLensZoom.set(p.lensZoom)
    playerPreferences.ppMotionLength.set(p.motionLength)
    playerPreferences.ppMotionZoom.set(p.motionZoom)
    playerPreferences.ppMotionPan.set(p.motionPan)
    playerPreferences.ppMotionAngle.set(p.motionAngle)
    playerPreferences.ppMotionSpin.set(p.motionSpin)
    playerPreferences.ppReflHorizon.set(p.reflHorizon)
    playerPreferences.ppReflAmount.set(p.reflAmount)
    playerPreferences.ppReflFalloff.set(p.reflFalloff)
    playerPreferences.ppReflPerspective.set(p.reflPerspective)
    playerPreferences.ppReflRipple.set(p.reflRipple)
    playerPreferences.ppReflRippleSpeed.set(p.reflRippleSpeed)
    playerPreferences.ppScanlinesDensity.set(p.scanlinesDensity)
    playerPreferences.ppScanlinesIntensity.set(p.scanlinesIntensity)
    playerPreferences.ppScanlinesTint.set(p.scanlinesTint)
    playerPreferences.ppSplitShadowHue.set(p.splitShadowHue)
    playerPreferences.ppSplitShadowStrength.set(p.splitShadowStrength)
    playerPreferences.ppSplitHighlightHue.set(p.splitHighlightHue)
    playerPreferences.ppSplitHighlightStrength.set(p.splitHighlightStrength)
    playerPreferences.ppSplitBalance.set(p.splitBalance)
    playerPreferences.ppVignetteStrength.set(p.vignetteStrength)
    playerPreferences.ppVignetteAspect.set(p.vignetteAspect)
    playerPreferences.ppWbTemperature.set(p.wbTemperature)
    playerPreferences.ppWbTint.set(p.wbTint)
    playerPreferences.ppCrtDensity.set(p.crtDensity)
    playerPreferences.ppCrtRollSpeed.set(p.crtRollSpeed)
    playerPreferences.ppCrtBleed.set(p.crtBleed)
  }

  // ==================== Utility ====================

  fun showToast(message: String) {
    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
  }

  override fun onCleared() {
    if (hostedInBrowser) {
      // The core, its shaders, subtitle styling and Syncplay bindings belong to the playback owner.
      runCatching { metadataCache.evictAll() }
      super.onCleared()
      return
    }
    // The core can outlive this screen (background playback), so never leave the gesture style behind.
    restoreSubtitleGestureHighlight()
    // Deterministic cleanup of resources that previously relied on GC.
    // viewModelScope is auto-cancelled by ViewModel, but the following
    // resources are not coroutine-scoped and need explicit release.
    // See issue 2.1 in the leak audit.

    // Stop the realtime subtitle service and delete its temp .srt file.
    // Without this the file lingers in cacheDir until the system reclaims
    // it, and the service may keep an active session open.
    runCatching { stopRealtimeSubtitles(showToastMessage = false) }
    runCatching { cancelAutoCropAnalysis() }


    // The metadataCache (Pair<String, String> entries) is small and
    // bounded at 100 entries, so it is not urgent to clear, but clearing
    // here keeps the working set fresh for the next session.
    runCatching { metadataCache.evictAll() }

    runCatching { syncplayManager.clearPlayerBindings() }
    _isAmbientLifecycleActive.value = false
    runCatching { disableAmbientShader() }
    runCatching { clearPostProcessingShaders() }

    super.onCleared()
  }
}

// Extension functions
fun Float.normalize(
  inMin: Float,
  inMax: Float,
  outMin: Float,
  outMax: Float,
): Float = (this - inMin) * (outMax - outMin) / (inMax - inMin) + outMin

fun <T> Flow<T>.collectAsState(
  scope: CoroutineScope,
  initialValue: T? = null,
) = object : ReadOnlyProperty<Any?, T?> {
  private var value: T? = initialValue

  init {
    scope.launch { collect { value = it } }
  }

  override fun getValue(
    thisRef: Any?,
    property: KProperty<*>,
  ) = value
}

private fun String.md5(): String {
  val digest = MessageDigest.getInstance("MD5").digest(toByteArray())
  return digest.joinToString("") { byte -> "%02x".format(byte) }
}
