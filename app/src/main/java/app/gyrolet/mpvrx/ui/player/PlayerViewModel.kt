/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
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
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager
import app.gyrolet.mpvrx.domain.hdr.HdrToysManager
import app.gyrolet.mpvrx.domain.network.NetworkPlaybackUri
import app.gyrolet.mpvrx.domain.torrent.TorrentStreamingState
import app.gyrolet.mpvrx.domain.torrent.formatTorrentSpeed
import app.gyrolet.mpvrx.domain.syncplay.SyncplayFile
import app.gyrolet.mpvrx.domain.syncplay.SyncplayPlaybackState
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.AudioChannels
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.MpvConfigOverride
import app.gyrolet.mpvrx.preferences.MpvConfigControlledFeatures
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import app.gyrolet.mpvrx.preferences.IntroSegmentProvider
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.repository.IntroDbLookupOutcome
import app.gyrolet.mpvrx.repository.IntroDbLookupRequest
import app.gyrolet.mpvrx.repository.IntroDbRepository
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
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.EQ_MAX_DB
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.EQ_MIN_DB
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.EqualizerPreset
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.EqualizerState
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.ui.player.screenshot.ScreenshotSaver
import app.gyrolet.mpvrx.ui.player.screenshot.ScreenshotSettings
import app.gyrolet.mpvrx.ui.preferences.CustomButton
import app.gyrolet.mpvrx.ui.preferences.CustomButtonScriptLanguage
import app.gyrolet.mpvrx.domain.thumbnail.ThumbFastEngine
import app.gyrolet.mpvrx.ui.player.thumbfast.ThumbFastPreviewState
import app.gyrolet.mpvrx.utils.media.AudioEqualizerManager
import app.gyrolet.mpvrx.utils.media.ChecksumUtils
import app.gyrolet.mpvrx.utils.media.MediaInfoParser
import app.gyrolet.mpvrx.utils.media.ParsedMediaInfo
import app.gyrolet.mpvrx.utils.media.SubtitleHashUtils
import app.gyrolet.mpvrx.utils.media.fileExtension
import app.gyrolet.mpvrx.utils.media.resolveSubtitleLookupDirectories
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import `is`.xyz.mpv.FastThumbnails
import `is`.xyz.mpv.Utils
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import java.security.MessageDigest
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

@Suppress("TooManyFunctions")
class PlayerViewModel : ViewModel(),
  KoinComponent {
  private var hostReference = WeakReference<PlayerHost>(null)
  private val host: PlayerHost
    get() = checkNotNull(hostReference.get()) { "Player host is not attached" }

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

  private val _torrentState = MutableStateFlow<TorrentStreamingState>(TorrentStreamingState.Idle)
  val torrentState: StateFlow<TorrentStreamingState> = _torrentState.asStateFlow()

  private var realtimeSubsJob: Job? = null
  private var realtimeSrtFile: java.io.File? = null

  private var playlistMetadataJob: Job? = null
  private var controlsVisibleForPolling = false
  private var seekBarVisibleForPolling = false
  private val skippedSegments = mutableSetOf<SkipSegment>()
  private var chapterDerivedSegments: List<SkipSegment> = emptyList()
  private var introDbSegments: List<app.gyrolet.mpvrx.repository.IntroDbSegment> = emptyList()
  private var introDbSourceKey: String = IntroSegmentProvider.INTRO_DB.sourceKey
  private var introLookupJob: Job? = null
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
  private val ambientCropRegex = Regex("""^(\d+)x(\d+)""")
  @Volatile private var pinnedSeekThumbnailSource: String? = null

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
      val item = session.currentItem
      val isNetworkStream =
        sequenceOf(item?.originalUri, item?.playableUri)
          .filterNotNull()
          .any { uri -> uri.startsWith("http://", true) || uri.startsWith("https://", true) }
      if (!isNetworkStream) {
        return@combine persistentListOf()
      }

      tracks
        .asSequence()
        .filter { track -> track.isVideo && !track.isAlbumArtwork }
        .sortedByDescending(TrackNode::isSelected)
        .distinctBy { track ->
          listOf(
            track.programId,
            track.programIds,
            track.demuxW,
            track.demuxH,
            track.demuxFps,
            track.effectiveBitrate,
            track.codec,
            track.effectiveTitle,
          )
        }.sortedWith(
          compareByDescending<TrackNode>(::videoQualityDimension)
            .thenByDescending(::videoPixelCount)
            .thenByDescending { track -> track.demuxFps ?: 0.0 }
            .thenByDescending { track -> track.effectiveBitrate ?: 0L },
        ).toList()
        .toImmutableList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

  val showVideoQualitySelector: StateFlow<Boolean> =
    combine(videoQualityTracks, PlaybackSession.state) { qualityTracks, session ->
      if (qualityTracks.isEmpty()) return@combine false

      val item = session.currentItem
      val isYtdlpPage =
        sequenceOf(item?.originalUri, item?.playableUri)
          .filterNotNull()
          .any(YtdlpManager::requiresYtdlp)
      isYtdlpPage || qualityTracks.size > 1
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

  fun selectVideoQuality(track: TrackNode) {
    if (currentItemRequiresYtdlp() && !MpvConfigOverridePolicy.isOwnedByMpvConf("ytdl-format")) {
      val selectedAudio = pairedYtdlTrack(track, TrackNode::isAudio)
        ?: allTracks.value.firstOrNull { candidate -> candidate.isAudio && candidate.isSelected }
      val selector = buildYtdlFormatSelector(videoTrack = track, audioTrack = selectedAudio)
      if (selector != null && host.reloadCurrentYtdlFormat(selector)) return
    }

    val selectedProgramIds = track.effectiveProgramIds.toSet()
    if (selectedProgramIds.isNotEmpty()) {
      allTracks.value
        .asSequence()
        .filter(TrackNode::isAudio)
        .filter { audioTrack -> audioTrack.effectiveProgramIds.any(selectedProgramIds::contains) }
        .sortedByDescending(TrackNode::isSelected)
        .firstOrNull()
        ?.let { audioTrack -> PlaybackSession.setPropertyInt("aid", audioTrack.id) }
    }
    PlaybackSession.setPropertyInt("vid", track.id)
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
      if (selector != null && host.reloadCurrentYtdlFormat(selector)) return
    }

    setTrackSelectionId("aid", track.id)
  }

  private fun currentItemRequiresYtdlp(): Boolean {
    val item = PlaybackSession.state.value.currentItem
    return sequenceOf(item?.originalUri, item?.playableUri)
      .filterNotNull()
      .any(YtdlpManager::requiresYtdlp)
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

  val isAudioOnly: StateFlow<Boolean> =
    combine(
      allTracks,
      PlaybackSession.propString["path"],
      PlaybackSession.propString["stream-open-filename"],
      PlaybackSession.state,
    ) { tracks, path, streamPath, session ->
      val currentPath = path?.takeIf { it.isNotBlank() } ?: streamPath
      val queuedItem = session.currentItem
      val itemDeclaresAudio =
        queuedItem?.mimeType?.startsWith("audio/", ignoreCase = true) == true ||
          (queuedItem != null && (
            queuedItem.originalUri.contains("/Audio/", ignoreCase = true) ||
            queuedItem.originalUri.contains("includeItemTypes=Audio", ignoreCase = true) ||
            queuedItem.playableUri.contains("/Audio/", ignoreCase = true)
          )) ||
          sequenceOf(queuedItem?.originalUri, queuedItem?.playableUri, queuedItem?.title)
            .filterNotNull()
            .any { candidate -> candidate.fileExtension() in FileTypeUtils.AUDIO_EXTENSIONS }
      val isFileAudioExt =
        currentPath?.let { p ->
          val ext = p.fileExtension()
          ext in FileTypeUtils.AUDIO_EXTENSIONS
        } ?: false
      val isFileVideoExt =
        (currentPath?.let { it.fileExtension() in FileTypeUtils.VIDEO_EXTENSIONS } ?: false) ||
          sequenceOf(queuedItem?.originalUri, queuedItem?.playableUri, queuedItem?.title)
            .filterNotNull()
            .any { candidate -> candidate.fileExtension() in FileTypeUtils.VIDEO_EXTENSIONS }

      val hasRealVideo = tracks.any { it.isVideo && !it.isAlbumArtwork }
      val detectedAudio =
        when {
          // A real video track or a known video container always wins. During demux startup mpv
          // can briefly expose only the audio track; treating that transient state as final made
          // MP4/MKV files permanently switch to the audio-player UI.
          hasRealVideo || isFileVideoExt -> false
          itemDeclaresAudio || isFileAudioExt -> true
          tracks.isNotEmpty() -> tracks.any { it.isAudio }
          else -> false
        }
      detectedAudio
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

  // Audio player UI state
  val albumArtBounds = MutableStateFlow<android.graphics.Rect?>(null)
  // The style and artwork/visualizer display choice are persisted via audioPreferences.
  val showVisualizerInAudioPlayer = MutableStateFlow(audioPreferences.showAudioVisualizer.get())
  val equalizerState = MutableStateFlow(EqualizerState())
  private val audioEqualizerManager = AudioEqualizerManager()
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
    val syncOffsetMs: Int = 0,
    val errorMessage: String? = null,
  )

  val lyricsUiState = MutableStateFlow(LyricsUiState())

  private var lyricsLoadJob: Job? = null
  private var lyricsTranslateJob: Job? = null

  fun setEqualizerEnabled(enabled: Boolean) {
    equalizerState.value = equalizerState.value.copy(isEnabled = enabled)
    applyEqualizerMpvFilters(immediate = true)
  }

  fun applyEqualizerPreset(preset: EqualizerPreset) {
    if (preset == EqualizerPreset.CUSTOM) return
    equalizerState.value =
      equalizerState.value.copy(
        currentPreset = preset,
        bandGains = preset.gains,
      )
    applyEqualizerMpvFilters(immediate = true)
  }

  fun setEqualizerBandGain(
    index: Int,
    gainDb: Int,
  ) {
    val currentGains = equalizerState.value.bandGains.toMutableList()
    if (index in currentGains.indices && currentGains[index] != gainDb) {
      currentGains[index] = gainDb.coerceIn(EQ_MIN_DB, EQ_MAX_DB)
      equalizerState.value =
        equalizerState.value.copy(
          currentPreset = EqualizerPreset.CUSTOM,
          bandGains = currentGains,
        )
      applyEqualizerMpvFilters(immediate = false)
    }
  }

  fun loadLyricsForCurrentTrack(forceRefresh: Boolean = false) {
    val path = PlaybackSession.getPropertyString("path") ?: PlaybackSession.getPropertyString("stream-open-filename") ?: return
    if (path.isBlank()) return

    val title = currentMediaTitle.takeIf { it.isNotBlank() }
      ?: PlaybackSession.getPropertyString("metadata/by-key/Title")
      ?: PlaybackSession.getPropertyString("media-title")
      ?: ""

    val artist = PlaybackSession.getPropertyString("metadata/by-key/Artist")
      ?: PlaybackSession.getPropertyString("metadata/by-key/ARTIST")
      ?: PlaybackSession.getPropertyString("metadata/by-key/album_artist")
      ?: ""

    val duration = PlaybackSession.getPropertyInt("duration") ?: 0

    lyricsUiState.value = lyricsUiState.value.copy(isLoading = true, errorMessage = null, syncOffsetMs = 0)

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
      )

      // The track may have changed again while this fetch was in-flight; only apply the
      // result if we're still on the same track (extra guard on top of job cancellation).
      val stillCurrentPath = PlaybackSession.getPropertyString("path")
        ?: PlaybackSession.getPropertyString("stream-open-filename")
      if (stillCurrentPath != path) return@launch

      val activeIndex = app.gyrolet.mpvrx.utils.media.LyricsUtils.getActiveLineIndex(
        syncedLines = result.activeLyrics?.synced,
        positionMs = (precisePosition.value * 1000).toLong(),
        offsetMs = 0,
      )
      val autoTranslate = audioPreferences.lyricsAutoTranslate.get()
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
        syncOffsetMs = 0,
      )

      val hasSynced = result.activeLyrics?.synced?.isNotEmpty() == true

      if (autoTranslate && hasSynced) {
        translateLyrics(defaultTargetLang)
      }
    }
  }

  fun switchLyricsSource(sourceType: app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType) {
    val path = PlaybackSession.getPropertyString("path") ?: PlaybackSession.getPropertyString("stream-open-filename") ?: return
    if (path.isBlank()) return

    val current = lyricsUiState.value
    val autoTranslate = audioPreferences.lyricsAutoTranslate.get()
    val defaultTargetLang = audioPreferences.lyricsTargetLanguage.get().ifBlank { "en" }

    if (sourceType == app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType.ONLINE && current.onlineLyrics == null) {
      lyricsUiState.value = current.copy(isLoading = true)
      lyricsLoadJob?.cancel()
      lyricsTranslateJob?.cancel()
      lyricsLoadJob = viewModelScope.launch(Dispatchers.IO) {
        val title = currentMediaTitle.takeIf { it.isNotBlank() }
          ?: PlaybackSession.getPropertyString("metadata/by-key/Title")
          ?: PlaybackSession.getPropertyString("media-title")
          ?: ""
        val artist = PlaybackSession.getPropertyString("metadata/by-key/Artist")
          ?: PlaybackSession.getPropertyString("metadata/by-key/ARTIST")
          ?: PlaybackSession.getPropertyString("metadata/by-key/album_artist")
          ?: ""
        val duration = PlaybackSession.getPropertyInt("duration") ?: 0

        val online = lyricsRepository.fetchOnlineLyrics(title, artist, duration)

        val stillCurrentPath = PlaybackSession.getPropertyString("path")
          ?: PlaybackSession.getPropertyString("stream-open-filename")
        if (stillCurrentPath != path) return@launch

        val updatedSources = (current.availableSources + app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType.ONLINE).distinct()
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
          activeLineIndex = activeIndex,
        )

        if (autoTranslate && hasSynced) {
          translateLyrics(defaultTargetLang)
        }
      }
      return
    }

    val updatedResult = lyricsRepository.switchSource(path, sourceType)
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
      )

      if (autoTranslate && hasSynced) {
        translateLyrics(defaultTargetLang)
      }
    }
  }

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
    val current = lyricsUiState.value
    val baseLyrics = current.originalLyrics ?: current.lyrics ?: return
    val lang = targetLang ?: audioPreferences.lyricsTargetLanguage.get().ifBlank { "en" }
    val path = PlaybackSession.getPropertyString("path") ?: PlaybackSession.getPropertyString("stream-open-filename") ?: ""

    audioPreferences.lyricsAutoTranslate.set(true)
    audioPreferences.lyricsTargetLanguage.set(lang)

    lyricsUiState.value = current.copy(
      isTranslating = true,
      targetLanguage = lang,
      originalLyrics = current.originalLyrics ?: current.lyrics,
    )

    lyricsTranslateJob?.cancel()
    lyricsTranslateJob = viewModelScope.launch(Dispatchers.IO) {
      val translated = lyricsTranslationService.translateLyrics(
        lyrics = baseLyrics,
        targetLanguage = lang,
        cacheKey = path,
      )

      // Bail out if the track changed while translation was in-flight, so a slow
      // translation for the previous song can't overwrite the new song's lyrics.
      val stillCurrentPath = PlaybackSession.getPropertyString("path")
        ?: PlaybackSession.getPropertyString("stream-open-filename")
      if (stillCurrentPath != path) return@launch

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
      )
    }
  }

  fun toggleLyricsTranslation() {
    val current = lyricsUiState.value
    if (current.isTranslationActive) {
      audioPreferences.lyricsAutoTranslate.set(false)
      val orig = current.originalLyrics ?: return
      val activeIndex = app.gyrolet.mpvrx.utils.media.LyricsUtils.getActiveLineIndex(
        syncedLines = orig.synced,
        positionMs = (precisePosition.value * 1000).toLong(),
        offsetMs = current.syncOffsetMs,
      )
      lyricsUiState.value = current.copy(
        isTranslationActive = false,
        lyrics = orig,
        activeLineIndex = activeIndex,
      )
    } else {
      translateLyrics()
    }
  }

  fun setEqualizerVolumeBoost(db: Int) {
    if (equalizerState.value.volumeBoostDb != db) {
      equalizerState.value = equalizerState.value.copy(volumeBoostDb = db.coerceIn(0, 10))
      applyEqualizerMpvFilters(immediate = false)
    }
  }

  fun applyEqualizerMpvFilters(immediate: Boolean = false) {
    val state = equalizerState.value

    // 1. Hardware Android AudioFx (Equalizer & LoudnessEnhancer matching AFinity)
    audioEqualizerManager.updateState(
      enabled = state.isEnabled,
      bandGains = state.bandGains,
      volumeBoostDb = state.volumeBoostDb,
    )

    // 2. MPV Audio Filter Fallback
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

    // 5. Equalizer filters (if enabled)
    if (state.isEnabled) {
      val maxGain = state.bandGains.maxOrNull() ?: 0
      if (maxGain > 0) {
        filterList.add("volume=volume=${-maxGain}dB")
      }
      val freqs = listOf(60, 230, 910, 3600, 14000)
      for (i in 0 until 5) {
        val gain = state.bandGains.getOrElse(i) { 0 }
        if (gain != 0) {
          filterList.add("equalizer=f=${freqs[i]}:width_type=o:width=1.5:g=$gain")
        }
      }
      if (state.volumeBoostDb > 0) {
        filterList.add("volume=volume=${state.volumeBoostDb}dB")
      }
    }

    val afString = filterList.joinToString(",")
    PlaybackSession.setPropertyString("af", afString)
  }

  fun updateAlbumArtBounds(rect: android.graphics.Rect?) {
    albumArtBounds.value = rect
  }

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

    val codec = PlaybackSession.getPropertyString("audio-codec-name")?.uppercase() ?: "Unknown"
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

    val bitrateInt = PlaybackSession.getPropertyInt("audio-bitrate") ?: 0
    val bitrateStr = if (bitrateInt > 0) "${bitrateInt / 1000} kbps" else "Variable / Unknown"

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

  private val _seekBarShown = MutableStateFlow(false)
  val seekBarShown: StateFlow<Boolean> = _seekBarShown.asStateFlow()

  private val _areControlsLocked = MutableStateFlow(false)
  val areControlsLocked: StateFlow<Boolean> = _areControlsLocked.asStateFlow()

  val playerUpdate = MutableStateFlow<PlayerUpdates>(PlayerUpdates.None)
  val isBrightnessSliderShown = MutableStateFlow(false)
  val isVolumeSliderShown = MutableStateFlow(false)
  val volumeSliderTimestamp = MutableStateFlow(0L)
  val brightnessSliderTimestamp = MutableStateFlow(0L)
  val currentBrightness =
    MutableStateFlow(
      runCatching {
        Settings.System
          .getFloat(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
          .normalize(0f, 255f, 0f, 1f)
      }.getOrElse { 0f },
    )

  val sheetShown = MutableStateFlow(Sheets.None)
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

  private val _thumbFastPreviewState = MutableStateFlow(ThumbFastPreviewState())
  val thumbFastPreviewState: StateFlow<ThumbFastPreviewState> = _thumbFastPreviewState.asStateFlow()

  private var thumbFastJob: Job? = null
  private var thumbFastRequestId = 0L

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
  private val _videoZoom = MutableStateFlow(0f)
  val videoZoom: StateFlow<Float> = _videoZoom.asStateFlow()

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

  @Volatile private var lastAmbientScaleX = -1.0
  @Volatile private var lastAmbientScaleY = -1.0
  private var ambientDebounceJob: kotlinx.coroutines.Job? = null
  private val ambientScheduleLock = Any()
  private val ambientRenderLock = Any()
  private val ambientUpdateGeneration = AtomicLong()
  private val _isAmbientLifecycleActive = MutableStateFlow(false)
  val isAmbientLifecycleActive: StateFlow<Boolean> = _isAmbientLifecycleActive.asStateFlow()
  private val ambientShaderSeq = AtomicLong()
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
  private var batteryReceiver: BroadcastReceiver? = null
  private var androidSystemInfoBridgeJob: Job? = null

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
    viewModelScope.launch {
      combine(
        PlaybackSession.propString["path"],
        PlaybackSession.propString["stream-open-filename"],
      ) { p1, p2 -> p1?.takeIf { it.isNotBlank() } ?: p2 }
        .collect { currentPath ->
          if (!currentPath.isNullOrBlank()) {
            loadLyricsForCurrentTrack()
          }
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
    syncplayManager.playbackStateProvider = { currentSyncplayPlaybackState() }
    syncplayManager.fileInfoProvider = { currentSyncplayFileInfo() }
    syncplayManager.onRemotePause = { shouldPause ->
      viewModelScope.launch(playbackStateDispatcher) {
        val currentlyPaused = PlaybackSession.getPropertyBoolean("pause") ?: false
        if (currentlyPaused != shouldPause) {
          if (!shouldPause) {
            val focusGranted = withContext(Dispatchers.Main) { host.requestAudioFocus() }
            if (!focusGranted) return@launch
          }
          PlaybackSession.setPropertyBoolean("pause", shouldPause)
          if (shouldPause) {
            withContext(Dispatchers.Main) { host.abandonAudioFocus() }
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
          delay(250L)
          continue
        }
        runCatching {
          val time = PlaybackSession.getPropertyDouble("time-pos")
          if (time != null) {
            val posFloat = time.toFloat()
            if (_precisePosition.value != posFloat) {
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
              // Invalidate the spec cache so the new budget cap is applied on the
              // next scheduled ambient update.
              lastCompiledSpec = null
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
            lastAmbientScaleX = -1.0 // Force a complete shader rewrite
            // Slight delay ensures MPV's video-params (w/h/crop) are fully populated.
            scheduleAmbientUpdate(250)
          }
          // --------------------------------------------------------
        }
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
    _repeatMode.value = playerPreferences.repeatMode.get()
    _shuffleEnabled.value = playerPreferences.shuffleEnabled.get()
    PlaybackSession.setRepeatMode(_repeatMode.value)
    PlaybackSession.setShuffleEnabled(_shuffleEnabled.value)

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
      ) { _, _, _ -> }.collect {
        if (!_isMpvCoreReady.value) return@collect
        applyEqualizerMpvFilters(immediate = true)
      }
    }

    // Monitor duration and AB loop changes to automatically enable precise seeking
    viewModelScope.launch(playbackStateDispatcher) {
      combine(_duration, abLoopState) { duration, abLoop ->
        Pair(duration, abLoop)
      }.collect { (duration, abLoop) ->
        if (!_isMpvCoreReady.value) return@collect
        val videoDuration = duration ?: 0
        val isLoopActive = abLoop.a != null || abLoop.b != null
        val shouldUsePreciseSeeking = playerPreferences.usePreciseSeeking.get() || videoDuration < 120 || isLoopActive
        PlaybackSession.setPropertyString("hr-seek", if (shouldUsePreciseSeeking) "yes" else "no")
        PlaybackSession.setPropertyString("hr-seek-framedrop", if (shouldUsePreciseSeeking) "no" else "yes")
      }
    }

    // Refresh custom buttons whenever their configuration changes.
    viewModelScope.launch {
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
      }
    }

    setupCustomButtons()
  }

  fun onMpvCoreInitialized() {
    _isMpvCoreReady.value = true
    scheduleAmbientUpdate(0)
    startMpvStateCollectors()
    isMpvReadyForCustomButtons = true
    reloadCustomButtonsScript("mpv_core_initialized")
    startAndroidSystemInfoBridge()
  }

  /** Stops every ViewModel path that can read or write libmpv during native teardown. */
  fun onMpvCoreStopping() {
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

  private fun startMpvStateCollectors() {
    if (mpvStateCollectorsJob?.isActive == true) return
    mpvStateCollectorsJob =
      viewModelScope.launch(playbackStateDispatcher) {
        launch { PlaybackSession.propBoolean["pause"].collect { _paused.value = it } }
        launch { PlaybackSession.propInt["time-pos"].collect { _pos.value = it } }
        launch { PlaybackSession.propInt["duration"].collect { _duration.value = it } }
        launch { PlaybackSession.propInt["volume-max"].collect { _volumeBoostCap.value = it } }
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
      onBatteryStateChanged(state.charging)
    }.onFailure { error ->
      Log.w(TAG, "Failed to publish Android battery properties", error)
    }
  }

  private data class AndroidBatteryState(
    val level: Int,
    val charging: Boolean,
    val plugged: Boolean,
  )

  private fun readAndroidBatteryState(context: Context): AndroidBatteryState {
    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
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
    )
  }

  fun onVideoLoadStarted() {
    dismissThumbFastSeek()
    pinnedSeekThumbnailSource = null
    introLookupJob?.cancel()
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
    loadLyricsForCurrentTrack(forceRefresh = true)
  }

  fun updateTorrentState(state: TorrentStreamingState) {
    _torrentState.value = state
  }

  fun torrentBufferingText(state: TorrentStreamingState): String =
    when (state) {
      is TorrentStreamingState.Idle -> ""
      is TorrentStreamingState.Connecting -> state.phase
      is TorrentStreamingState.Streaming -> {
        val speed = formatTorrentSpeed(state.downloadSpeed)
        val peers = "${state.peers} peers"
        val progress = "${(state.bufferProgress * 100).toInt()}%"
        "$speed | $peers | $progress"
      }
      is TorrentStreamingState.Error -> state.message.ifBlank { "Torrent error" }
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

  // Cached values
  private val doubleTapToSeekDuration by lazy { gesturePreferences.doubleTapToSeekDuration.get() }
  private val inputMethodManager by lazy {
    appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
  }

  // Seek coalescing for smooth performance
  private var pendingSeekOffset: Int = 0
  private var seekCoalesceJob: Job? = null
  private val previewSeekLock = Any()
  private var pendingPreviewSeekPosition: Float? = null
  private var previewSeekJob: Job? = null

  private companion object {
    const val TAG = "PlayerViewModel"
    const val AUTO_SHOW_SKIP_CHIP_DURATION = 10.0
    const val SEEK_COALESCE_DELAY_MS = 60L
    const val PREVIEW_SEEK_INTERVAL_MS = 25L
    const val SEEK_THUMBNAIL_TIMEOUT_MS = 2_500L
    const val SEEK_THUMBNAIL_MAX_INFLIGHT_DECODES = 3
    const val SEEK_THUMBNAIL_FAILURE_COOLDOWN_MS = 10_000L
    const val SEEK_THUMBNAIL_FAILURE_CACHE_MAX = 128
    const val SEEK_THUMBNAIL_MAX_SIZE = 320
    const val SEEK_THUMBNAIL_CACHE_KB = 32 * 1024
    const val SEEK_THUMBNAIL_CACHE_BUCKETS_PER_SECOND = 1f
    val QUALITY_HEIGHT_REGEX = Regex("""(?i)(\d{3,4})p""")
    const val SEEK_THUMBNAIL_PREFETCH_RADIUS = 2
    const val NATIVE_LINEAR_HDR_YOUTUBE_BLUR_RADIUS = 100.0
    val MPV_ONLY_PSEUDO_PROTOCOLS =
      setOf("fd", "fdclose", "edl", "memory", "null", "av", "lavf", "archive", "slice", "mf", "hex", "bd", "dvd", "dvb")
    const val PLAYLIST_METADATA_PREFETCH_RADIUS = 40
    const val PLAYLIST_METADATA_PREFETCH_LIMIT = 120
    const val INTRO_MARKER_CACHE_PREFS = "intro_marker_cache"
    const val INTRO_MARKER_CACHE_PREFIX = "intro_marker:v2:"
    const val INTRO_MARKER_CACHE_MAX_ENTRIES = 200
    const val INTRO_MARKER_CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1000L
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

        // Store mapping for reliable physical deletion later
        mpvPathToUriMap[mpvPath] = uri.toString()

        withContext(Dispatchers.Main) {
          PlaybackSession.command("sub-add", mpvPath, mode)
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

    val actualLanguage = if (language.isBlank()) aiPreferences.sttLanguage.get().ifBlank { "en" } else language
    val actualFormat = if (outputFormat.isBlank()) aiPreferences.subtitleGenerationOutputFormat.get() else outputFormat

    viewModelScope.launch(Dispatchers.IO) {
      _isGeneratingSubtitles.value = true
      _subtitleGenerationProgress.value = 0f
      _subtitleGenerationStatus.value = "Preparing audio"

      try {
        val result =
          subtitleGenerationService.generateSubtitles(
            videoUri = videoUri,
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
    val media = host.currentMediaLookupHint()?.takeIf { it.isNotBlank() } ?: return null
    return if (media.startsWith("/")) File(media).toUri() else Uri.parse(media)
  }

  fun startRealtimeSubtitles(language: String) {
    val videoUri = currentVideoUriForSubtitleGeneration()
    if (videoUri == null) {
      showToast("Could not find current video path")
      return
    }
    val videoDurationMs = (_preciseDuration.value * 1000f).toLong()
    if (videoDurationMs <= 0) {
      showToast("Video duration unknown")
      return
    }

    realtimeSrtFile = java.io.File.createTempFile("realtime_subs_", ".srt", appContext.cacheDir)

    _isRealtimeSubsActive.value = true
    _realtimeSubsLanguage.value = language
    _realtimeSubsProgress.value = 0f

    realtimeSubtitleService.start(
      videoUri = videoUri,
      videoDurationMs = videoDurationMs,
      language = language,
      scope = viewModelScope,
      onProgress = { progress ->
        _realtimeSubsProgress.value = progress.chunkIndex.toFloat() / progress.totalChunks.coerceAtLeast(1)
        _translationStatus.value = "Chunk ${progress.chunkIndex + 1}/${progress.totalChunks}"
      },
      onNewContent = { srtContent ->
        realtimeSrtFile?.writeText(srtContent)
        val srtPath = realtimeSrtFile?.absolutePath ?: return@start
        viewModelScope.launch(Dispatchers.Main) {
          if (realtimeSrtFileAdded) {
            PlaybackSession.command("sub-reload", srtPath)
          } else {
            PlaybackSession.command("sub-add", srtPath, "select")
            realtimeSrtFileAdded = true
          }
        }
      },
      onComplete = {
        _isRealtimeSubsActive.value = false
        _realtimeSubsLanguage.value = ""
        _realtimeSubsProgress.value = 0f
        _translationStatus.value = ""
        realtimeSrtFile = null
        showToast("Real-time subtitles complete")
      },
      onError = { error ->
        _isRealtimeSubsActive.value = false
        _realtimeSubsLanguage.value = ""
        _realtimeSubsProgress.value = 0f
        _translationStatus.value = ""
        showToast("Real-time subtitles error: $error")
      },
    )
  }

  fun stopRealtimeSubtitles(showToastMessage: Boolean = true) {
    val wasActive = _isRealtimeSubsActive.value
    realtimeSubtitleService.stop()
    _isRealtimeSubsActive.value = false
    _realtimeSubsLanguage.value = ""
    _realtimeSubsProgress.value = 0f
    _translationStatus.value = ""
    realtimeSrtFile?.delete()
    realtimeSrtFile = null
    realtimeSrtFileAdded = false
    if (showToastMessage && wasActive) {
      showToast("Real-time subtitles stopped")
    }
  }

  private var realtimeSrtFileAdded = false

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

      // 2. Reset Video Zoom
      if (_videoZoom.value != 0f) {
        _videoZoom.value = 0f
        runCatching { PlaybackSession.setPropertyDouble("video-zoom", 0.0) }
      }

      // 3. Reset Video Pan
      if (_videoPanX.value != 0f || _videoPanY.value != 0f) {
        _videoPanX.value = 0f
        _videoPanY.value = 0f
        runCatching {
          PlaybackSession.setPropertyDouble("video-pan-x", 0.0)
          PlaybackSession.setPropertyDouble("video-pan-y", 0.0)
        }
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

    val showChip =
      activeSegment != null &&
        (positionSeconds - activeSegment.startSeconds) < AUTO_SHOW_SKIP_CHIP_DURATION
    if (_currentSkippableSegment.value != activeSegment) {
      _currentSkippableSegment.value = activeSegment
    }
    if (_showSkipChipAuto.value != showChip) {
      _showSkipChipAuto.value = showChip
    }

    if (paused == true || activeSegment == null) return
    val autoSkipEnabled =
      when (activeSegment.type) {
        SkipSegmentType.INTRO -> playerPreferences.autoSkipIntro.get()
        SkipSegmentType.RECAP -> playerPreferences.autoSkipIntro.get()
        SkipSegmentType.OUTRO -> playerPreferences.autoSkipOutro.get()
        SkipSegmentType.CREDITS -> playerPreferences.autoSkipOutro.get()
        SkipSegmentType.PREVIEW -> playerPreferences.autoSkipOutro.get()
      }
    if (!autoSkipEnabled) return

    skippedSegments += activeSegment
    _showSkipChipAuto.value = false
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
    val merged = SkipMarkerResolver.merge(resolveIntroDbSegments() + chapterDerivedSegments)
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
    if (!playerPreferences.enableIntroDb.get()) {
      pendingIntroLookupTitle = null
      introDbSegments = emptyList()
      mergeSkipSegments()
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
    val lookupHints = host.currentPlayerLookupHints()
    val lookupRequest =
      IntroDbLookupRequest(
        mediaTitle = mediaTitle,
        canonicalTitle = lookupHints.canonicalTitle,
        lookupHint = host.currentMediaLookupHint(),
        imdbId = lookupHints.imdbId,
        tmdbId = lookupHints.tmdbId,
        mediaType = lookupHints.mediaType,
        season = lookupHints.season,
        episode = lookupHints.episode,
        provider = provider,
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

    introLookupJob?.cancel()
    introLookupJob =
      viewModelScope.launch {
        val outcome =
          if (provider == IntroSegmentProvider.HYBRID) {
            val providers =
              listOf(
                IntroSegmentProvider.INTRO_DB,
                IntroSegmentProvider.THE_INTRO_DB,
                IntroSegmentProvider.ANI_SKIP,
                IntroSegmentProvider.ANIME_SKIP,
              )
            val receivedOutcomes =
              providers
                .map { lookupProvider ->
                  async(Dispatchers.IO) {
                    try {
                      introDbRepository.lookupSegments(lookupRequest.copy(provider = lookupProvider))
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                      throw cancellation
                    } catch (error: Exception) {
                      IntroDbLookupOutcome.Error(error.message ?: "unknown", lookupProvider)
                    }
                  }
                }.awaitAll()
            val loadedOutcomes = receivedOutcomes.filterIsInstance<IntroDbLookupOutcome.Loaded>()

            if (loadedOutcomes.isNotEmpty()) {
              val primary = loadedOutcomes.first()
              IntroDbLookupOutcome.Loaded(
                imdbId = primary.imdbId,
                segments = loadedOutcomes.flatMap(IntroDbLookupOutcome.Loaded::segments).distinct(),
                source = primary.source,
                provider = IntroSegmentProvider.HYBRID,
              )
            } else {
              val fallbackOutcome =
                receivedOutcomes.firstOrNull { it is IntroDbLookupOutcome.NoSegments }
                  ?: receivedOutcomes.firstOrNull { it is IntroDbLookupOutcome.Unresolved }
                  ?: receivedOutcomes.firstOrNull()

              if (fallbackOutcome != null) {
                when (fallbackOutcome) {
                  is IntroDbLookupOutcome.NoSegments ->
                    IntroDbLookupOutcome.NoSegments(
                      fallbackOutcome.imdbId,
                      fallbackOutcome.source,
                      IntroSegmentProvider.HYBRID,
                    )
                  is IntroDbLookupOutcome.Unresolved ->
                    IntroDbLookupOutcome.Unresolved(
                      fallbackOutcome.title,
                      IntroSegmentProvider.HYBRID,
                    )
                  is IntroDbLookupOutcome.Error ->
                    IntroDbLookupOutcome.Error(
                      fallbackOutcome.reason,
                      IntroSegmentProvider.HYBRID,
                    )
                  else -> fallbackOutcome
                }
              } else {
                IntroDbLookupOutcome.Error("No outcomes", IntroSegmentProvider.HYBRID)
              }
            }
          } else {
            introDbRepository.lookupSegments(lookupRequest)
          }

        if (currentMediaTitle != lookupKey) return@launch

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

    if ((System.currentTimeMillis() - entry.cachedAtMs) > INTRO_MARKER_CACHE_TTL_MS) {
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
      id == primarySid -> setTrackSelectionId("sid", null)
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
        val focusGranted = withContext(Dispatchers.Main) { host.requestAudioFocus() }
        if (!focusGranted) return@launch
        PlaybackSession.setPropertyBoolean("pause", false)
        syncplayManager.updatePlayerState(precisePosition.value.toDouble(), false, doSeek = false)
      } else {
        PlaybackSession.setPropertyBoolean("pause", true)
        syncplayManager.updatePlayerState(precisePosition.value.toDouble(), true, doSeek = false)
        withContext(Dispatchers.Main) { host.abandonAudioFocus() }
      }
    }
  }

  fun pause() {
    viewModelScope.launch(playbackStateDispatcher) {
      PlaybackSession.setPropertyBoolean("pause", true)
      syncplayManager.updatePlayerState(precisePosition.value.toDouble(), true, doSeek = false)
      withContext(Dispatchers.Main) { host.abandonAudioFocus() }
    }
  }

  fun unpause() {
    viewModelScope.launch(playbackStateDispatcher) {
      val focusGranted = withContext(Dispatchers.Main) { host.requestAudioFocus() }
      if (!focusGranted) return@launch
      PlaybackSession.setPropertyBoolean("pause", false)
      syncplayManager.updatePlayerState(precisePosition.value.toDouble(), false, doSeek = false)
    }
  }

  // ==================== UI Control ====================

  fun showControls() {
    if (sheetShown.value != Sheets.None || panelShown.value != Panels.None) return
    if (!isAudioOnly.value) {
      try {
        if (playerPreferences.showSystemStatusBar.get()) {
          host.windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
          host.windowInsetsController.isAppearanceLightStatusBars = false
        }
        if (playerPreferences.showSystemNavigationBar.get()) {
          host.windowInsetsController.show(WindowInsetsCompat.Type.navigationBars())
        }
      } catch (e: Exception) {
        // Defensive: InsetsController animation can crash under FD pressure
        // (e.g. during high-res HEVC playback on certain devices)
        Log.e(TAG, "Failed to show system bars", e)
      }
    }
    _controlsShown.value = true
    controlsVisibleForPolling = true
  }

  fun hideControls() {
    if (!isAudioOnly.value) {
      try {
        host.windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
        host.windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
      } catch (e: Exception) {
        Log.e(TAG, "Failed to hide system bars", e)
      }
    }
    _controlsShown.value = false
    _seekBarShown.value = false
    controlsVisibleForPolling = false
    seekBarVisibleForPolling = false
  }

  fun autoHideControls() {
    if (!isAudioOnly.value) {
      try {
        host.windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
        host.windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
      } catch (e: Exception) {
        Log.e(TAG, "Failed to hide system bars", e)
      }
    }
    _controlsShown.value = false
    _seekBarShown.value = true
    controlsVisibleForPolling = false
    seekBarVisibleForPolling = true
  }

  fun showSeekBar() {
    if (sheetShown.value == Sheets.None) {
      _seekBarShown.value = true
      seekBarVisibleForPolling = true
    }
  }

  fun hideSeekBar() {
    _seekBarShown.value = false
    seekBarVisibleForPolling = false
  }

  fun updateThumbFastSeek(
    positionSeconds: Float,
    xFraction: Float,
    relativeDeltaText: String? = null,
    chapterTitle: String? = null,
  ) {
    if (!playerPreferences.useThumbFastSeekPreview.get()) {
      dismissThumbFastSeek()
      return
    }

    if (host.isCurrentMediaKnownAudio() || isAudioOnly.value) {
      dismissThumbFastSeek()
      return
    }

    val durationSec = duration?.toFloat() ?: _preciseDuration.value.coerceAtLeast(0f)
    val clampedPosition =
      if (durationSec > 0f) {
        positionSeconds.coerceIn(0f, durationSec)
      } else {
        positionSeconds.coerceAtLeast(0f)
      }

    val clampedFraction = xFraction.coerceIn(0f, 1f)
    val formattedTime = Utils.prettyTime(clampedPosition.toInt(), false)

    val source = resolveSeekThumbnailSource()
    if (source.isNullOrBlank()) {
      _thumbFastPreviewState.update {
        it.copy(
          isVisible = true,
          positionSeconds = clampedPosition,
          durationSeconds = durationSec,
          formattedTime = formattedTime,
          relativeDeltaText = relativeDeltaText,
          chapterTitle = chapterTitle,
          normalizedXFraction = clampedFraction,
          isLoading = false,
        )
      }
      return
    }

    val isNetwork = ThumbFastEngine.isNetworkSource(source)
    val allowNetwork = playerPreferences.thumbFastNetworkStreams.get()
    val canDecode = !isNetwork || allowNetwork

    val cachedBitmap = ThumbFastEngine.getCached(source, clampedPosition.toDouble())
    val nearestCachedBitmap = cachedBitmap ?: ThumbFastEngine.getNearestCached(source, clampedPosition.toDouble())

    _thumbFastPreviewState.update { current ->
      current.copy(
        isVisible = true,
        positionSeconds = clampedPosition,
        durationSeconds = durationSec,
        formattedTime = formattedTime,
        relativeDeltaText = relativeDeltaText,
        chapterTitle = chapterTitle,
        bitmap = nearestCachedBitmap ?: current.bitmap,
        isLoading = canDecode && cachedBitmap == null && nearestCachedBitmap == null,
        normalizedXFraction = clampedFraction,
      )
    }

    if (cachedBitmap != null || !canDecode) return

    val requestId = ++thumbFastRequestId
    thumbFastJob?.cancel()
    thumbFastJob =
      viewModelScope.launch(Dispatchers.IO) {
        val bitmap = ThumbFastEngine.getThumbnail(source, clampedPosition.toDouble(), isNetwork = isNetwork)
        if (requestId == thumbFastRequestId) {
          _thumbFastPreviewState.update { current ->
            if (!current.isVisible) return@update current
            current.copy(
              bitmap = bitmap ?: current.bitmap,
              isLoading = false,
            )
          }
        }
      }
  }

  fun dismissThumbFastSeek() {
    thumbFastJob?.cancel()
    thumbFastJob = null
    thumbFastRequestId++
    _thumbFastPreviewState.update {
      it.copy(
        isVisible = false,
        bitmap = null,
        isLoading = false,
        relativeDeltaText = null,
        chapterTitle = null,
      )
    }
  }

  fun updateSeekThumbnailPreview(
    positionSeconds: Float,
    durationSeconds: Float,
  ) {
    val xFraction = if (durationSeconds > 0f) (positionSeconds / durationSeconds).coerceIn(0f, 1f) else 0f
    updateThumbFastSeek(positionSeconds, xFraction)
  }

  fun hideSeekThumbnailPreview() {
    dismissThumbFastSeek()
  }

  private fun resolveSeekThumbnailSource(): String? {
    pinnedSeekThumbnailSource?.let { return it }
    val resolved =
      sequenceOf(
        runCatching { PlaybackSession.getPropertyString("stream-open-filename") }.getOrNull(),
        runCatching { PlaybackSession.getPropertyString("path") }.getOrNull(),
        host.currentThumbnailSource(),
      ).mapNotNull { candidate -> candidate?.takeIf { it.isNotBlank() } }
        .firstOrNull(ThumbFastEngine::isSourceDecodable)
    if (resolved != null) pinnedSeekThumbnailSource = resolved
    return resolved
  }

  fun lockControls() {
    _areControlsLocked.value = true
  }

  fun unlockControls() {
    _areControlsLocked.value = false
  }

  // ==================== Seeking ====================

  fun seekBy(offset: Int) {
    coalesceSeek(offset)
  }

  /**
   * Conflated live preview used by the legacy/full-screen seek mode and the audio seekbar.
   * Pointer events can arrive much faster than a decoder can seek, so only the newest target is
   * applied at a bounded rate. Preview seeks are keyframe-only and never spam Syncplay peers.
   */
  fun previewSeekTo(position: Float) {
    synchronized(previewSeekLock) {
      pendingPreviewSeekPosition = position.coerceAtLeast(0f)
      if (previewSeekJob?.isActive == true) return
      previewSeekJob = viewModelScope.launch(Dispatchers.IO) { runPreviewSeekLoop() }
    }
  }

  private suspend fun runPreviewSeekLoop() {
    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
      val target =
        synchronized(previewSeekLock) {
          pendingPreviewSeekPosition?.also { pendingPreviewSeekPosition = null }
            ?: run {
              previewSeekJob = null
              return
            }
        }
      PlaybackSession.command("seek", target.toString(), "absolute+keyframes")
      delay(PREVIEW_SEEK_INTERVAL_MS)
    }
  }

  private fun cancelPreviewSeek() {
    synchronized(previewSeekLock) {
      previewSeekJob?.cancel()
      previewSeekJob = null
      pendingPreviewSeekPosition = null
    }
  }

  fun seekTo(
    position: Int,
    fast: Boolean = false,
  ) {
    cancelPreviewSeek()
    viewModelScope.launch(Dispatchers.IO) {
      val maxDuration =
        (PlaybackSession.getPropertyInt("duration") ?: duration ?: _preciseDuration.value.toInt())
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

      // Cancel pending relative seek before absolute seek
      seekCoalesceJob?.cancel()
      pendingSeekOffset = 0

      // Exact seeking is intentionally opt-in. Forcing it on every short clip is expensive and can
      // leave sparse-keyframe MP4/MKV files on a black frame. Drag previews always use keyframes.
      val seekMode =
        if (!fast && playerPreferences.usePreciseSeeking.get()) "absolute+exact" else "absolute+keyframes"
      PlaybackSession.command("seek", clampedPosition.toString(), seekMode)
      syncplayManager.updatePlayerState(
        clampedPosition.toDouble(),
        PlaybackSession.getPropertyBoolean("pause") ?: false,
        doSeek = true,
      )
    }
  }

  private fun coalesceSeek(offset: Int) {
    pendingSeekOffset += offset
    seekCoalesceJob?.cancel()
    seekCoalesceJob =
      viewModelScope.launch(Dispatchers.IO) {
        delay(SEEK_COALESCE_DELAY_MS)
        val toApply = pendingSeekOffset
        pendingSeekOffset = 0

        if (toApply != 0) {
          val duration = PlaybackSession.getPropertyInt("duration") ?: 0
          val currentPos = PlaybackSession.getPropertyInt("time-pos") ?: 0

          if (duration > 0 && currentPos + toApply >= duration) {
            // If seeking past the end, force seek to 100% absolute to ensure EOF is triggered
            PlaybackSession.command("seek", "100", "absolute-percent+exact")
            syncplayManager.updatePlayerState(
              duration.toDouble(),
              PlaybackSession.getPropertyBoolean("pause") ?: false,
              doSeek = true,
            )
          } else {
            val shouldUsePreciseSeeking = playerPreferences.usePreciseSeeking.get()
            val seekMode = if (shouldUsePreciseSeeking) "relative+exact" else "relative+keyframes"
            PlaybackSession.command("seek", toApply.toString(), seekMode)
            syncplayManager.updatePlayerState(
              (currentPos + toApply).toDouble(),
              PlaybackSession.getPropertyBoolean("pause") ?: false,
              doSeek = true,
            )
          }
        }
      }
  }

  fun leftSeek() {
    _seekState.update { s ->
      s.copy(amount = if ((pos ?: 0) > 0) s.amount - doubleTapToSeekDuration else s.amount, isForwards = false)
    }
    seekBy(-doubleTapToSeekDuration)
  }

  fun rightSeek() {
    _seekState.update { s ->
      s.copy(
        amount =
          if ((pos ?: 0) <
            (duration ?: 0)
          ) {
            s.amount + doubleTapToSeekDuration
          } else {
            s.amount
          },
        isForwards = true,
      )
    }
    seekBy(doubleTapToSeekDuration)
  }

  fun updateSeekAmount(amount: Int) {
    _seekState.update { it.copy(amount = amount) }
  }

  fun updateSeekText(text: String?) {
    _seekState.update { it.copy(text = text) }
  }

  fun updateIsSeekingForwards(isForwards: Boolean) {
    _seekState.update { it.copy(isForwards = isForwards) }
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
    val isAudio = host.isCurrentMediaKnownAudio() || isAudioOnly.value
    val minBrightness = if (isAudio) 0f else -0.75f
    val coercedBrightness = brightness.coerceIn(minBrightness, 1f)
    host.hostWindow.attributes =
      host.hostWindow.attributes.apply {
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
    runCatching {
      host.hostWindow.attributes =
        host.hostWindow.attributes.apply {
          screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
  }

  fun changeVolumeBy(
    change: Int,
    showUi: Boolean = false,
  ) {
    val isAudio = host.isCurrentMediaKnownAudio() || isAudioOnly.value
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

  fun changeSubtitlePositionTo(position: Int) {
    val newPosition = clampSubtitlePosition(position)
    subtitlesPreferences.subPos.set(newPosition)
    syncSubtitleLayout(newPosition)
    playerUpdate.value = PlayerUpdates.ShowText(appContext.getString(R.string.subtitle_position_update, newPosition))
  }

  private fun syncSubtitleLayout(primaryPosition: Int = subtitlesPreferences.subPos.get()) {
    applySubtitleLayout(primaryPosition, subtitlesPreferences.overrideAssSubs.get())
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
        @Suppress("DEPRECATION")
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        host.hostWindowManager.defaultDisplay.getRealMetrics(dm)

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
        PlaybackSession.setPropertyDouble("video-aspect-override", screenRatio)
        PlaybackSession.setPropertyDouble("panscan", 0.0)
      }
    }

    // Update the state
    playerPreferences.lastVideoAspect.set(aspect)
    playerPreferences.lastCustomAspectRatio.set(-1f)
    _videoAspect.value = aspect
    _currentAspectRatio.value = -1.0 // Reset custom ratio when using standard modes

    // Notify the UI
    if (showUpdate) {
      playerUpdate.value = PlayerUpdates.AspectRatio
    }
  }

  fun setCustomAspectRatio(
    ratio: Double,
    showUpdate: Boolean = true,
  ) {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.VIDEO_ASPECT)) return
    PlaybackSession.setPropertyDouble("panscan", 0.0)
    PlaybackSession.setPropertyDouble("video-aspect-override", ratio)
    playerPreferences.lastCustomAspectRatio.set(ratio.toFloat())
    _currentAspectRatio.value = ratio
    if (showUpdate) {
      playerUpdate.value = PlayerUpdates.AspectRatio
    }
  }

  fun restoreSavedVideoAspect(showUpdate: Boolean = false) {
    val customAspectRatio = playerPreferences.lastCustomAspectRatio.get()
    if (customAspectRatio > 0f) {
      setCustomAspectRatio(customAspectRatio.toDouble(), showUpdate)
      return
    }

    changeVideoAspect(playerPreferences.lastVideoAspect.get(), showUpdate)
  }

  // ==================== Screen Rotation ====================

  fun cycleScreenRotations() {
    if (isAudioOnly.value) {
      host.hostRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
      return
    }
    // Temporarily cycle orientation WITHOUT modifying preferences
    // Preferences remain the single source of truth and will be reapplied on next video
    host.hostRequestedOrientation =
      when (host.hostRequestedOrientation) {
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

  fun handleLeftDoubleTap() {
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

  fun handleCenterDoubleTap() {
    when (gesturePreferences.centerSingleActionGesture.get()) {
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom ->
        viewModelScope.launch(Dispatchers.IO) {
          PlaybackSession.command("keypress", CustomKeyCodes.DoubleTapCenter.keyCode)
        }
      SingleActionGesture.Seek, SingleActionGesture.None -> {}
    }
  }

  fun handleCenterSingleTap() {
    when (gesturePreferences.centerSingleActionGesture.get()) {
      SingleActionGesture.PlayPause -> pauseUnpause()
      SingleActionGesture.Custom ->
        viewModelScope.launch(Dispatchers.IO) {
          PlaybackSession.command("keypress", CustomKeyCodes.DoubleTapCenter.keyCode)
        }
      SingleActionGesture.Seek, SingleActionGesture.None -> {}
    }
  }

  fun handleRightDoubleTap() {
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
      _videoZoom.value = 0f
      return
    }
    _videoZoom.value = zoom
    runCatching { PlaybackSession.setPropertyDouble("video-zoom", zoom.toDouble()) }
  }

  // Video pan (for pan & zoom feature)
  private val _videoPanX = MutableStateFlow(0f)
  val videoPanX: StateFlow<Float> = _videoPanX.asStateFlow()

  private val _videoPanY = MutableStateFlow(0f)
  val videoPanY: StateFlow<Float> = _videoPanY.asStateFlow()

  fun setVideoPan(
    x: Float,
    y: Float,
  ) {
    _videoPanX.value = if (MpvConfigOverridePolicy.isOwnedByMpvConf("video-pan-x")) 0f else x
    _videoPanY.value = if (MpvConfigOverridePolicy.isOwnedByMpvConf("video-pan-y")) 0f else y
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

    val durationValue = PlaybackSession.getPropertyDouble("duration") ?: 0.0
    val fps =
      PlaybackSession.getPropertyDouble("container-fps")
        ?: PlaybackSession.getPropertyDouble("estimated-vf-fps")
        ?: 0.0

    _totalFrames.value =
      if (durationValue > 0 && fps > 0) {
        (durationValue * fps).toInt()
      } else {
        0
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
      val title = item.title?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
      val resolvedUri =
        if (uri.scheme == "content") {
          uri.extractLocalPath()?.let { Uri.fromFile(File(it)) } ?: uri
        } else {
          uri
        }
      val path = resolvedUri.toString()
      val isAudio =
        path
          .substringBefore('?')
          .substringBefore('#')
          .substringAfterLast('.', "")
          .lowercase() in FileTypeUtils.AUDIO_EXTENSIONS ||
          resolvedUri.toString().lowercase().contains("audio") ||
          uri.toString().lowercase().contains("audio")
      val isCurrentlyPlaying = index == queue.currentIndex

      // Try to get from cache first (synchronized access)
      val cacheKey = resolvedUri.toString()
      val extractedDuration =
        item.durationSeconds
          ?.takeIf { seconds -> seconds > 0 }
          ?.let { seconds -> formatDuration(seconds * 1000L) }
          .orEmpty()
      val (durationStr, resolutionStr) =
        synchronized(metadataCache) { metadataCache[cacheKey] }
          ?: (extractedDuration to "")

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
        tvgLogo = item.artworkUri.orEmpty(),
        networkConnectionId = item.networkSource?.connectionId,
        networkPath = item.networkSource?.relativePath.orEmpty(),
      )
    }
  }

  private fun getVideoMetadata(uri: Uri): Pair<String, String> {
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

      // Get duration
      val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
      val durationStr =
        if (durationMs != null) {
          formatDuration(durationMs.toLong())
        } else {
          ""
        }

      // Get resolution
      val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
      val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
      val resolutionStr =
        if (width != null && height != null) {
          "${width}x$height"
        } else {
          ""
        }

      durationStr to resolutionStr
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
    host.playQueueItem(index)
  }

  fun reorderPlaylistItem(
    from: Int,
    to: Int,
  ) {
    host.reorderQueueItem(from, to)
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

        if (forceMetadata) {
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

        val metadataItems =
          if (items.size <= PLAYLIST_METADATA_PREFETCH_LIMIT) {
            items
          } else {
            val currentIndex = queue.currentIndex.coerceIn(0, items.lastIndex)
            val startIndex = maxOf(0, currentIndex - PLAYLIST_METADATA_PREFETCH_RADIUS)
            val endIndex = minOf(items.lastIndex, currentIndex + PLAYLIST_METADATA_PREFETCH_RADIUS)
            items.subList(startIndex, endIndex + 1)
          }.filter { item -> queue.items.getOrNull(item.index)?.durationSeconds == null }

        // Limit concurrent metadata extraction to avoid overwhelming resources
        val batchSize = 5
        metadataItems.chunked(batchSize).forEach { batch ->
          val updates = mutableMapOf<String, Pair<String, String>>()

          // Extract metadata for the batch
          batch.forEach { item ->
            val cacheKey = item.uri.toString()

            // Skip if already in cache (LruCache is thread-safe)
            if (metadataCache.get(cacheKey) == null) {
              // Extract metadata
              val (durationStr, resolutionStr) = getVideoMetadata(item.uri)

              // Update cache and track update
              updateMetadataCache(cacheKey, durationStr to resolutionStr)
              updates[cacheKey] = durationStr to resolutionStr
            }
          }

          // Apply all batched updates at once (single playlist update)
          if (updates.isNotEmpty()) {
            _playlistItems.value =
              _playlistItems.value.map { currentItem ->
                val cacheKey = currentItem.uri.toString()
                val (durationStr, resolutionStr) = updates[cacheKey] ?: return@map currentItem
                currentItem.copy(duration = durationStr, resolution = resolutionStr)
              }
          }
        }
      }
  }

  fun hasNext(): Boolean = PlaybackSession.hasNext()

  fun hasPrevious(): Boolean = PlaybackSession.hasPrevious()

  fun playNext() {
    host.playNextQueueItem()
  }

  fun playPrevious() {
    host.playPreviousQueueItem()
  }

  // ==================== Repeat and Shuffle ====================

  fun applyPersistedShuffleState() {
    PlaybackSession.setShuffleEnabled(_shuffleEnabled.value)
    if (_shuffleEnabled.value) {
      host.onQueueShuffleChanged(true)
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
    host.onQueueShuffleChanged(_shuffleEnabled.value)

    // Show overlay update instead of toast
    playerUpdate.value = PlayerUpdates.Shuffle(_shuffleEnabled.value)
  }

  fun shouldRepeatCurrentFile(): Boolean =
    _repeatMode.value == RepeatMode.ONE ||
      (_repeatMode.value == RepeatMode.ALL && !PlaybackSession.queue.value.isExplicitQueue)

  fun shouldRepeatPlaylist(): Boolean =
    _repeatMode.value == RepeatMode.ALL && PlaybackSession.queue.value.isExplicitQueue

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
      applyHdrScreenOutputOptions(mode, pipelineReady, boostSdr)
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
      !MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AMBIENT)

  private fun isAmbientGlowRuntimeActive(): Boolean =
    isAmbientRuntimeActive() && _ambientStyle.value == AmbientStyle.Glow

  fun toggleAmbientMode() {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AMBIENT)) return
    _isAmbientEnabled.value = !_isAmbientEnabled.value
    playerPreferences.isAmbientEnabled.set(_isAmbientEnabled.value)
    if (_isAmbientEnabled.value) {
      if (_ambientStyle.value == AmbientStyle.Glow) {
        lastAmbientScaleX = -1.0
        scheduleAmbientUpdate(0)
      }
      playerUpdate.value = PlayerUpdates.ShowText(appContext.getString(R.string.player_ambience_on))
    } else {
      disableAmbientShader()
      playerUpdate.value = PlayerUpdates.ShowText(appContext.getString(R.string.player_ambience_off))
    }
  }

  /** Switches between the shader-backed Glow and frame-captured YouTube styles. */
  fun setAmbientStyle(style: AmbientStyle) {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AMBIENT)) return
    if (_ambientStyle.value == style) return
    _ambientStyle.value = style
    playerPreferences.ambientStyle.set(style)
    playerUpdate.value =
      PlayerUpdates.ShowText(
        appContext.getString(R.string.ambient_style_update, appContext.getString(style.titleRes)),
      )
    if (style == AmbientStyle.Glow) {
      lastAmbientScaleX = -1.0
      scheduleAmbientUpdate(0)
    } else {
      disableAmbientShader()
    }
  }

  /** Disables the ambient shader and resets video scale. Safe to call from any state. */
  private fun disableAmbientShader() {
    synchronized(ambientScheduleLock) {
      ambientUpdateGeneration.incrementAndGet()
      ambientDebounceJob?.cancel()
      ambientDebounceJob = null
    }
    synchronized(ambientRenderLock) {
      ambientShaderFile?.let { file ->
        runCatching { PlaybackSession.command("change-list", "glsl-shaders", "remove", file.absolutePath) }
        file.delete()
      }
      ambientShaderFile = null
      // Reset the shader cache and scale tracking so a subsequent enable always
      // compiles a fresh shader and recalculates the correct video-scale offsets.
      lastCompiledSpec = null
      lastAmbientScaleX = -1.0
      lastAmbientScaleY = -1.0
      runCatching {
        PlaybackSession.setPropertyDouble("video-scale-x", 1.0)
        PlaybackSession.setPropertyDouble("video-scale-y", 1.0)
        PlaybackSession.setPropertyString("blend-subtitles", "no")
      }
    }
  }

  /** Called when the device orientation changes. Refreshes Glow for the new output dimensions. */
  fun onOrientationChanged() {
    if (!isAmbientGlowRuntimeActive()) return

    // The compiled spec is the cache key; scale sentinels alone do not force a rebuild.
    lastCompiledSpec = null
    lastAmbientScaleX = -1.0
    lastAmbientScaleY = -1.0
    scheduleAmbientUpdate(200)
  }

  /** Removes the old file-specific ambient shader while preserving the user's selected ambient mode. */
  fun prepareAmbientForNewVideo() {
    if (!_isAmbientEnabled.value || MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AMBIENT)) return
    disableAmbientShader()
    lastAmbientScaleX = -1.0
    lastAmbientScaleY = -1.0
  }

  /**
   * Re-injects the ambient shader if ambient mode is currently ON.
   * Called after shader-stack changes so ambient stays as the last OUTPUT pass.
   */
  fun restartAmbientIfActive() {
    if (!isAmbientGlowRuntimeActive()) return
    disableAmbientShader()
    // Small delay to let Anime4K shaders settle.
    scheduleAmbientUpdate(200)
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
    _ambientBlurSamples.value = blurSamples
    _ambientMaxRadius.value = maxRadius
    _ambientGlowIntensity.value = glowIntensity
    _ambientSatBoost.value = satBoost
    _ambientVignetteStrength.value = vignetteStrength
    _ambientWarmth.value = warmth
    _ambientFadeCurve.value = fadeCurve
    _ambientOpacity.value = opacity

    // Persist to preferences
    playerPreferences.ambientBlurSamples.set(blurSamples)
    playerPreferences.ambientMaxRadius.set(maxRadius)
    playerPreferences.ambientGlowIntensity.set(glowIntensity)
    playerPreferences.ambientSatBoost.set(satBoost)
    playerPreferences.ambientVignetteStrength.set(vignetteStrength)
    playerPreferences.ambientWarmth.set(warmth)
    playerPreferences.ambientFadeCurve.set(fadeCurve)
    playerPreferences.ambientOpacity.set(opacity)

    scheduleAmbientUpdate()
  }

  private fun scheduleAmbientUpdate(delayMs: Long = 150L) {
    synchronized(ambientScheduleLock) {
      if (!isAmbientGlowRuntimeActive()) return

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

  /** Balanced profile — good quality/performance trade-off for most devices. */
  fun applyAmbientProfileBalanced() {
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

  /** High Quality profile — maximum visual fidelity for high-end devices. */
  fun applyAmbientProfileHighQuality() {
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

  fun updateAmbientBatterySaver(enabled: Boolean) {
    _isAmbientBatterySaver.value = enabled
    playerPreferences.ambientBatterySaver.set(enabled)
    if (enabled && _isAmbientEnabled.value) {
      applyBatterySaverPolicy()
    } else if (!enabled && ambientWasOnBattery && _isAmbientEnabled.value) {
      restoreFromBatterySaver()
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

  fun onBatteryStateChanged(isCharging: Boolean) {
    if (!_isAmbientBatterySaver.value || !_isAmbientEnabled.value) return
    if (isCharging) {
      restoreFromBatterySaver()
    } else {
      applyBatterySaverPolicy()
    }
  }

  private suspend fun updateAmbientStretch(generation: Long) {
    if (!isAmbientGlowRuntimeActive() || generation != ambientUpdateGeneration.get()) return

    runCatching {
      val osdW = PlaybackSession.getPropertyInt("osd-width") ?: 1920
      val osdH = PlaybackSession.getPropertyInt("osd-height") ?: 1080

      // Portrait mode: ambient glow goes on top/bottom (letterbox)
      // Landscape mode: ambient glow goes on left/right (pillarbox)
      // Both are handled by the same scaleX/scaleY math below

      var vidW = (PlaybackSession.getPropertyInt("video-params/w") ?: 1920).toDouble()
      var vidH = (PlaybackSession.getPropertyInt("video-params/h") ?: 1080).toDouble()
      val par = PlaybackSession.getPropertyDouble("video-params/par") ?: 1.0
      val rot = PlaybackSession.getPropertyInt("video-params/rotate") ?: 0

      // Intercept autocrop boundaries — if a crop is active, use the cropped dimensions
      // so the shader's aspect-ratio math matches the actual visible video area
      val crop = PlaybackSession.getPropertyString("video-crop") ?: ""
      val cropMatch = ambientCropRegex.find(crop)
      if (cropMatch != null) {
        vidW = cropMatch.groupValues[1].toDouble()
        vidH = cropMatch.groupValues[2].toDouble()
      }

      if (osdW <= 0 || osdH <= 0 || vidW <= 0.0 || vidH <= 0.0) return

      // Apply pixel aspect ratio (non-square pixels)
      vidW *= par
      // Swap dimensions for 90°/270° rotated videos (portrait shot stored as landscape)
      if (rot == 90 || rot == 270) {
        val tmp = vidW
        vidW = vidH
        vidH = tmp
      }

      val screenAr = osdW.toDouble() / osdH.toDouble()
      val vidAr = vidW / vidH

      // Scale the video to fill the screen — the shader remaps it back to the
      // correct aspect ratio, so only the "overflow" area receives ambient glow.
      val scaleX = if (screenAr > vidAr) screenAr / vidAr else 1.0
      val scaleY = if (vidAr > screenAr) vidAr / screenAr else 1.0

      // ── Snapshot current parameter values ─────────────────────────────────
      val sx = scaleX
      val sy = scaleY
      // Thermal-aware sample budget: cap shader complexity before the device enters
      // hard CPU/GPU throttling.  On a cool device this is a no-op.
      val rawSamples = _ambientBlurSamples.value
      val samples = ThermalMonitor.clampAmbientSampleBudget(rawSamples, thermalHeadroom)
      val radius = _ambientMaxRadius.value
      val glow = _ambientGlowIntensity.value
      val sat = _ambientSatBoost.value
      val vignette = _ambientVignetteStrength.value
      val warmth = _ambientWarmth.value
      val curve = _ambientFadeCurve.value
      val opacity = _ambientOpacity.value

      // ── Generate GLSL shader ───────────────────────────────────────────────
      val spec =
        buildAmbientSpec(
          sx = sx,
          sy = sy,
          blurSamples = samples,
          maxRadius = radius,
          glowIntensity = glow,
          satBoost = sat,
          vignetteStrength = vignette,
          warmth = warmth,
          fadeCurve = curve,
          opacity = opacity,
        )

      // ── Shader parameter cache ──────────────────────────────────────────────────────
      // Compare the AmbientGlowShaderSpec data class (cheap equality) before building
      // the GLSL string. This avoids allocating the multi-KB shader string and
      // running buildSpiralTapTable trig math on no-op refreshes (e.g. thermal
      // monitor ticks that don't change the effective sample budget, orientation
      // callbacks that fire with unchanged video dimensions).
      val shaderIsCurrent =
        synchronized(ambientRenderLock) {
          spec == lastCompiledSpec && ambientShaderFile?.exists() == true
        }
      if (shaderIsCurrent) {
        return
      }

      // Each reload gets a unique filename so MPV never reuses a cached
      // compiled shader — incrementing seq guarantees a fresh compile every time.
      val shaderCode = AmbientShaderBuilder.build(appContext, spec)
      val newFile = File(appContext.cacheDir, "ambient_${ambientShaderSeq.incrementAndGet()}.glsl")
      // Blocking file write — dispatched to IO pool to avoid stalling renderPrepDispatcher.
      // Catch CancellationException here: IO is not preemptible, so the write always
      // completes fully even when the job is cancelled mid-flight. Without this guard,
      // the file would be written but never tracked or deleted (disk leak on every
      // superseded debounced update — fast slider drags, orientation flips, etc.).
      try {
        withContext(kotlinx.coroutines.Dispatchers.IO) { newFile.writeText(shaderCode) }
      } catch (error: Throwable) {
        newFile.delete()
        throw error
      }
      currentCoroutineContext().ensureActive()

      synchronized(ambientRenderLock) {
        if (!isAmbientGlowRuntimeActive() || generation != ambientUpdateGeneration.get()) {
          newFile.delete()
          return@synchronized
        }
        ambientShaderFile?.let { oldFile ->
          runCatching { PlaybackSession.command("change-list", "glsl-shaders", "remove", oldFile.absolutePath) }
          oldFile.delete()
        }
        PlaybackSession.setPropertyDouble("video-scale-x", scaleX)
        PlaybackSession.setPropertyDouble("video-scale-y", scaleY)
        val blendMode = if (subtitlesPreferences.blendSubtitlesWithVideo.get()) "video" else "no"
        PlaybackSession.setPropertyString("blend-subtitles", blendMode)
        PlaybackSession.command("change-list", "glsl-shaders", "append", newFile.absolutePath)
        lastAmbientScaleX = scaleX
        lastAmbientScaleY = scaleY
        ambientShaderFile = newFile
        lastCompiledSpec = spec
      }
    }.onFailure { e ->
      // runCatching catches Throwable including CancellationException — rethrow it so
      // structured concurrency is not broken and debounce cancellation does not log
      // spurious "Failed to update ambient stretch" stack traces that would mask real errors.
      if (e is kotlinx.coroutines.CancellationException) throw e
      Log.e(TAG, "Failed to update ambient stretch", e)
    }
  }

  /**
  * Builds an [AmbientGlowShaderSpec] from the current ambient parameter values.
   * The spec is a lightweight data class that captures all shader inputs;
   * [AmbientShaderBuilder.build] converts it to a GLSL string only when the
   * spec has actually changed from the last compiled version.
   */
  private fun buildAmbientSpec(
    sx: Double,
    sy: Double,
    blurSamples: Int,
    maxRadius: Float,
    glowIntensity: Float,
    satBoost: Float,
    vignetteStrength: Float,
    warmth: Float,
    fadeCurve: Float,
    opacity: Float,
  ): AmbientGlowShaderSpec {
    val context = AmbientRenderContext(scaleX = sx, scaleY = sy)
    val shared =
      AmbientSharedShaderConfig(
        bezelDepth = 0f,
        vignetteStrength = vignetteStrength,
        opacity = opacity,
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

  // ==================== Utility ====================

  fun showToast(message: String) {
    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
  }

  override fun onCleared() {
    // Deterministic cleanup of resources that previously relied on GC.
    // viewModelScope is auto-cancelled by ViewModel, but the following
    // resources are not coroutine-scoped and need explicit release.
    // See issue 2.1 in the leak audit.

    // Stop the realtime subtitle service and delete its temp .srt file.
    // Without this the file lingers in cacheDir until the system reclaims
    // it, and the service may keep an active session open.
    runCatching { stopRealtimeSubtitles(showToastMessage = false) }

    // Evict all cached Bitmaps from the ThumbFast LruCache so the
    // memory is returned immediately rather than waiting for the next GC pass.
    runCatching { ThumbFastEngine.clearMemoryCache() }
    runCatching { ThumbFastEngine.clearFailures() }

    // The metadataCache (Pair<String, String> entries) is small and
    // bounded at 100 entries, so it is not urgent to clear, but clearing
    // here keeps the working set fresh for the next session.
    runCatching { metadataCache.evictAll() }

    runCatching { syncplayManager.clearPlayerBindings() }
    runCatching { audioEqualizerManager.release() }
    _isAmbientLifecycleActive.value = false
    runCatching { disableAmbientShader() }

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
