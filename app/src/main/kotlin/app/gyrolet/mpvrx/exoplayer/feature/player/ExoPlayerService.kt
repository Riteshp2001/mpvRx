package app.gyrolet.mpvrx.exoplayer

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION
import androidx.media3.common.Player.DISCONTINUITY_REASON_REMOVE
import androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.ExtractorsFactory
import app.gyrolet.mpvrx.exoplayer.feature.player.service.extensionRendererMode
import app.gyrolet.mpvrx.exoplayer.feature.player.service.logName
import app.gyrolet.mpvrx.exoplayer.feature.player.service.shouldEnableDecoderFallback
import app.gyrolet.mpvrx.exoplayer.feature.player.service.shouldRetryWithSoftwareDecoder
import app.gyrolet.mpvrx.exoplayer.feature.player.service.shouldUseAudioExtensionFallback
import app.gyrolet.mpvrx.exoplayer.feature.player.service.shouldApplyVideoEffects
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.session.CommandButton
import androidx.media3.session.CommandButton.ICON_UNDEFINED
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleSpeed
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import app.gyrolet.mpvrx.exoplayer.core.common.Logger
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.deleteFiles
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.getFilenameFromUri
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.getLocalSubtitles
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.getPath
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.matchesSubtitleBase
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.subtitleCacheDir
import app.gyrolet.mpvrx.ui.browser.networkstreaming.clients.FtpClient
import app.gyrolet.mpvrx.ui.browser.networkstreaming.clients.SmbClient
import app.gyrolet.mpvrx.ui.browser.networkstreaming.clients.WebDavClient
import app.gyrolet.mpvrx.exoplayer.core.data.repository.MediaRepository
import app.gyrolet.mpvrx.exoplayer.core.data.repository.PreferencesRepository
import app.gyrolet.mpvrx.exoplayer.core.data.repository.buildPlaybackStateCandidates
import app.gyrolet.mpvrx.exoplayer.core.data.repository.buildRemoteFolderPlaybackAnchorKey
import app.gyrolet.mpvrx.exoplayer.core.data.repository.buildRemotePlaybackStateKey
import app.gyrolet.mpvrx.exoplayer.core.data.repository.isRemotePlaybackStateKey
import app.gyrolet.mpvrx.exoplayer.core.model.DecoderPriority
import app.gyrolet.mpvrx.exoplayer.core.model.LoopMode
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences
import app.gyrolet.mpvrx.exoplayer.core.model.RemoteFile
import app.gyrolet.mpvrx.exoplayer.core.model.RemoteServer
import app.gyrolet.mpvrx.exoplayer.core.model.Resume
import app.gyrolet.mpvrx.exoplayer.core.model.ServerProtocol
import app.gyrolet.mpvrx.exoplayer.feature.player.datasource.FtpDataSource
import app.gyrolet.mpvrx.exoplayer.feature.player.datasource.SmbDataSource
import app.gyrolet.mpvrx.exoplayer.feature.player.engine.media3.MkvCuesParser
import app.gyrolet.mpvrx.exoplayer.feature.player.engine.media3.SeekMapInjectingExtractor
import app.gyrolet.mpvrx.exoplayer.feature.player.engine.media3.buildSeekMapFromCues
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.addAdditionalSubtitleConfiguration
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.audioTrackIndex
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.copy
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.getManuallySelectedTrackIndex
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.getSubtitleMime
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.isApproximateSeekEnabled
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.isVideoEffectsAvailable
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.localParentPath
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.positionMs
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.remoteDirectoryPath
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.remoteFilePath
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.remoteProtocol
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.remoteServerId
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.requestHeaders
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.setMetadataExtras
import app.gyrolet.mpvrx.exoplayer.core.data.repository.OnlineSubtitleRepository
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.getSubtitleMime
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.setIsScrubbingModeEnabled
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.subtitleDelayMilliseconds
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.subtitleSpeed
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.subtitleTrackIndex
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.switchTrack
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.uriToSubtitleConfiguration
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.videoZoom
import app.gyrolet.mpvrx.exoplayer.feature.player.service.CustomCommands
import app.gyrolet.mpvrx.exoplayer.feature.player.service.NormalizingRenderersFactory
import app.gyrolet.mpvrx.exoplayer.feature.player.service.VideoEffectsState
import app.gyrolet.mpvrx.exoplayer.feature.player.service.VideoFilterPreferences
import app.gyrolet.mpvrx.exoplayer.feature.player.service.VideoFiltersEffect
import app.gyrolet.mpvrx.exoplayer.feature.player.service.VideoFilterTransition
import app.gyrolet.mpvrx.exoplayer.feature.player.service.VolumeNormalizationAudioProcessor
import app.gyrolet.mpvrx.exoplayer.feature.player.subtitle.AssHandlerRegistry
import app.gyrolet.mpvrx.exoplayer.feature.player.subtitle.NormalizingAssMatroskaExtractor
import org.koin.android.ext.android.inject

@OptIn(UnstableApi::class)
class ExoPlayerService : MediaSessionService() {

    private val serviceScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaSession: MediaSession? = null

    companion object {
        private const val TAG = "ExoPlayerService"
        private const val FAST_SEEK_MIN_DELTA_MS = 2_000L
        private const val STARTUP_PRECISE_RESUME_THRESHOLD_MS = 10_000L
        private const val MKV_CUES_CACHE_MAGIC = 0x4E505145
        private val DIRECT_SUBTITLE_URI_SCHEMES = setOf("smb", "ftp")
        private val REMOTE_SOURCE_URI_SCHEMES = setOf("smb", "ftp")

        private val ISO_639_2T_TO_1 = mapOf(
            "zho" to "zh", "chi" to "zh",
            "eng" to "en",
            "jpn" to "ja",
            "kor" to "ko",
            "fra" to "fr", "fre" to "fr",
            "deu" to "de", "ger" to "de",
            "spa" to "es",
            "por" to "pt",
            "rus" to "ru",
            "ara" to "ar",
            "tha" to "th",
            "vie" to "vi",
            "ita" to "it",
            "pol" to "pl",
            "nld" to "nl", "dut" to "nl",
            "tur" to "tr",
            "ind" to "id",
            "msa" to "ms", "may" to "ms",
        )
        private val REMOTE_SUBTITLE_EXTENSIONS = setOf(
            "ass",
            "srt",
            "ssa",
            "ttml",
            "vtt",
        )
        private const val VIDEO_FILTER_PREVIEW_DELAY_MS = 40L
        private const val VIDEO_FILTER_TRANSITION_DURATION_MS = 160L
        private const val PAUSED_FRAME_REFRESH_OFFSET_MS = 50L
    }

    val preferencesRepository: PreferencesRepository by inject()
    val mediaRepository: MediaRepository by inject()
    val onlineSubtitleRepository: OnlineSubtitleRepository by inject()
    val webDavClient: WebDavClient by inject()
    val smbClient: SmbClient by inject()
    val ftpClient: FtpClient by inject()
    val imageLoader: ImageLoader by inject()

    private val playerPreferences: PlayerPreferences
        get() = preferencesRepository.playerPreferences.value

    private fun updateFolderPlaybackAnchor(mediaItem: MediaItem) {
        val preferences = preferencesRepository.applicationPreferences.value
        if (!preferences.shouldRestoreLastPlayedMediaInFolders) return

        serviceScope.launch {
            val playbackStateUri = mediaItem.resolvePlaybackStateUri()
            val localParentPath = mediaItem.mediaMetadata.localParentPath
                ?: mediaRepository.getVideoByUri(playbackStateUri)?.parentPath
                    ?.takeIf { it.isNotBlank() }
            val remoteAnchorKey = buildRemoteFolderPlaybackAnchorKey(
                remoteProtocol = mediaItem.mediaMetadata.remoteProtocol,
                remoteServerId = mediaItem.mediaMetadata.remoteServerId,
                directoryPath = mediaItem.mediaMetadata.remoteDirectoryPath,
            )

            preferencesRepository.updateApplicationPreferences { currentPreferences ->
                var updatedPreferences = currentPreferences

                if (!localParentPath.isNullOrBlank()) {
                    updatedPreferences = updatedPreferences.copy(
                        localFolderLastPlayedMediaUris = updatedPreferences.localFolderLastPlayedMediaUris +
                            (localParentPath to playbackStateUri),
                    )
                }

                if (remoteAnchorKey != null) {
                    val remoteFilePath = mediaItem.mediaMetadata.remoteFilePath ?: return@updateApplicationPreferences updatedPreferences
                    updatedPreferences = updatedPreferences.copy(
                        remoteFolderLastPlayedMediaPaths = updatedPreferences.remoteFolderLastPlayedMediaPaths +
                            (remoteAnchorKey to remoteFilePath),
                    )
                }

                updatedPreferences
            }
        }
    }

    private val customCommands = CustomCommands.asSessionCommands()

    private var isMediaItemReady = false

    private val volumeNormalizationAudioProcessor = VolumeNormalizationAudioProcessor()
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var requestedVolumeGain: Int = 0
    private val mediaParserRetried = mutableSetOf<String>()
    private val softwareDecoderRetried = mutableSetOf<String>()
    private var isPendingExternalSubAutoSelect = false
    private var assHandler: AssHandler? = null
    private var pendingPreciseSeekPromotionJob: Job? = null
    private var preciseSeekRequestId = 0L
    private var pendingStartupPreciseResumeToken: String? = null
    private var pendingStartupPreciseResumePositionMs: Long? = null
    private var activeDecoderPriority: DecoderPriority = DecoderPriority.AUTOMATIC
    private var currentVideoEffectsState = VideoEffectsState(
        filters = VideoFilterPreferences.default(),
        decoderPriority = DecoderPriority.AUTOMATIC,
    )
    private var activeVideoFiltersEffect: VideoFiltersEffect? = null
    private var isCurrentVideoHdr = false
    private var hasRenderedFirstFrameForCurrentItem = false
    private var pendingVideoFiltersJob: Job? = null
    private var videoFilterTransition = VideoFilterTransition.default()
    private lateinit var fastStartMediaSourceFactory: DefaultMediaSourceFactory
    private lateinit var preciseSeekMediaSourceFactory: DefaultMediaSourceFactory
    private var sessionLoadErrorHandlingPolicy: LoadErrorHandlingPolicy? = null
    private var sessionDrmSessionManagerProvider: DrmSessionManagerProvider? = null
    private lateinit var sessionMediaSourceFactory: MediaSource.Factory
    private lateinit var assSubtitleParserFactory: AssSubtitleParserFactory

    private val mkvSeekMapCache = ConcurrentHashMap<String, androidx.media3.extractor.SeekMap>()
    private val mkvCueParseJobs = ConcurrentHashMap<String, Deferred<androidx.media3.extractor.SeekMap?>>()
    private val preciseSeekMediaIds = ConcurrentHashMap.newKeySet<String>()

    private var startupTimestamp = 0L
    private val startupAnalyticsListener = object : AnalyticsListener {
        override fun onPlaybackStateChanged(
            eventTime: AnalyticsListener.EventTime,
            state: Int,
        ) {
            if (state == Player.STATE_BUFFERING) {
                startupTimestamp = System.currentTimeMillis()
            }
            val label = when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($state)"
            }
            Logger.info(TAG, "startup state=$label t=${elapsed()}ms")
        }

        override fun onLoadStarted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            retryCount: Int,
        ) {
            Logger.info(TAG, "startup loadStart t=${elapsed()}ms type=${mediaLoadData.dataType}")
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
        ) {
            Logger.info(
                TAG,
                "startup loadDone t=${elapsed()}ms type=${mediaLoadData.dataType} bytes=${loadEventInfo.bytesLoaded}",
            )
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            Logger.info(TAG, "startup firstFrame t=${elapsed()}ms")
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Logger.info(TAG, "startup decoderInit=$decoderName dur=${initializationDurationMs}ms t=${elapsed()}ms")
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Logger.info(TAG, "startup audioDecoder=$decoderName dur=${initializationDurationMs}ms t=${elapsed()}ms")
        }

        override fun onTracksChanged(
            eventTime: AnalyticsListener.EventTime,
            tracks: androidx.media3.common.Tracks,
        ) {
            val player = mediaSession?.player
            Logger.info(
                TAG,
                "startup tracksChanged t=${elapsed()}ms groups=${tracks.groups.size} seekable=${player?.isCurrentMediaItemSeekable} duration=${player?.duration}",
            )
        }
    }

    private fun elapsed(): Long = System.currentTimeMillis() - startupTimestamp

    private fun resolveTransitionPlaybackSpeed(
        transitionReason: Int,
        currentPlaybackSpeed: Float,
    ): Float = when (transitionReason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
        -> currentPlaybackSpeed

        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
        -> playerPreferences.defaultPlaybackSpeed

        else -> playerPreferences.defaultPlaybackSpeed
    }

    private val playbackStateListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                handleRepeatedPlayback(mediaSession?.player ?: return)
                return
            }
            isCurrentVideoHdr = false
            hasRenderedFirstFrameForCurrentItem = false
            pendingPreciseSeekPromotionJob?.cancel()
            pendingPreciseSeekPromotionJob = null
            preciseSeekRequestId++
            pendingStartupPreciseResumeToken = null
            pendingStartupPreciseResumePositionMs = null
            isMediaItemReady = false
            isPendingExternalSubAutoSelect = false
            updateCurrentVideoEffectsAvailability(mediaSession?.player as? ExoPlayer ?: return)
            if (mediaItem != null) {
                serviceScope.launch {
                    val playbackStateUri = mediaItem.resolvePlaybackStateUri()
                    mediaRepository.updateMediumLastPlayedTime(
                        uri = playbackStateUri,
                        lastPlayedTime = System.currentTimeMillis(),
                    )
                }
            }
            mediaItem?.mediaMetadata?.let { metadata ->
                mediaSession?.player?.run {
                    setPlaybackSpeed(
                        resolveTransitionPlaybackSpeed(
                            transitionReason = reason,
                            currentPlaybackSpeed = playbackParameters.speed,
                        ),
                    )
                    playerSpecificSubtitleDelayMilliseconds = metadata.subtitleDelayMilliseconds ?: 0L
                    playerSpecificSubtitleSpeed = metadata.subtitleSpeed ?: 1f
                }

                val resumePositionMs = metadata.positionMs?.takeIf { playerPreferences.resume == Resume.YES }
                if (metadata.isApproximateSeekEnabled) {
                    val restoredSeekMap = restoreCachedMkvSeekMap(mediaItem)
                    if (restoredSeekMap != null) {
                        mkvSeekMapCache[mediaItem.mediaId] = restoredSeekMap
                    }
                    scheduleMkvCueCache(mediaItem)

                    if (restoredSeekMap != null) {
                        preciseSeekMediaIds.add(mediaItem.mediaId)
                        resumePositionMs?.takeIf { it >= STARTUP_PRECISE_RESUME_THRESHOLD_MS }?.let {
                            Logger.info(TAG, "Resume cached precise-seek media item=${mediaItem.mediaId} position=$it")
                            mediaSession?.player?.seekTo(it)
                        }
                    } else {
                        resumePositionMs?.takeIf { it > 0L }?.let {
                            Logger.info(TAG, "Resume deferred precise-seek media item=${mediaItem.mediaId} position=$it")
                            pendingStartupPreciseResumeToken = mediaItem.mediaId
                            pendingStartupPreciseResumePositionMs = it
                        }
                    }
                    return
                }

                resumePositionMs?.let {
                    mediaSession?.player?.seekTo(it)
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            super.onPositionDiscontinuity(oldPosition, newPosition, reason)
            val oldMediaItem = oldPosition.mediaItem ?: return

            when (reason) {
                DISCONTINUITY_REASON_SEEK,
                DISCONTINUITY_REASON_AUTO_TRANSITION,
                -> {
                    if (newPosition.mediaItem == null || oldMediaItem == newPosition.mediaItem) return

                    val player = mediaSession?.player ?: return
                    val updatedPosition = oldPosition.positionMs.takeIf { reason == DISCONTINUITY_REASON_SEEK } ?: C.TIME_UNSET
                    val mediaItemToUpdate = player.getMediaItemAt(oldPosition.mediaItemIndex)
                        .takeIf { it.mediaId == oldMediaItem.mediaId }
                        ?: oldMediaItem

                    player.replaceMediaItem(
                        oldPosition.mediaItemIndex,
                        mediaItemToUpdate.copy(positionMs = updatedPosition),
                    )
                    serviceScope.launch {
                        val playbackStateUri = oldMediaItem.resolvePlaybackStateUri()
                        mediaRepository.updateMediumPosition(
                            uri = playbackStateUri,
                            position = updatedPosition,
                        )
                    }
                }

                DISCONTINUITY_REASON_REMOVE -> {
                    serviceScope.launch {
                        val durationMs = oldMediaItem.mediaMetadata.durationMs
                        val isAtEnd = durationMs != null && oldPosition.positionMs >= durationMs - 1000
                        val playbackStateUri = oldMediaItem.resolvePlaybackStateUri()
                        mediaRepository.updateMediumPosition(
                            uri = playbackStateUri,
                            position = if (isAtEnd) C.TIME_UNSET else oldPosition.positionMs,
                        )
                    }
                }

                else -> return
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
            if (tracks.groups.isEmpty()) return

            if (isPendingExternalSubAutoSelect) {
                isPendingExternalSubAutoSelect = false
                if (!playerPreferences.isSubtitleAutoLoadEnabled) return
                val player = mediaSession?.player ?: return
                val textTracks = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
                if (textTracks.isNotEmpty()) {
                    val rememberedSubtitleTrackIndex = player.currentMediaItem?.mediaMetadata?.subtitleTrackIndex
                    when {
                        rememberedSubtitleTrackIndex == -1 -> player.switchTrack(C.TRACK_TYPE_TEXT, -1)
                        rememberedSubtitleTrackIndex in textTracks.indices -> player.switchTrack(
                            C.TRACK_TYPE_TEXT,
                            rememberedSubtitleTrackIndex ?: -1,
                        )
                        else -> player.switchTrack(C.TRACK_TYPE_TEXT, findBestSubtitleTrackIndex(textTracks))
                    }
                }
                return
            }

            if (isMediaItemReady) return
            isMediaItemReady = true

            val player = mediaSession?.player ?: return
            val metadata = player.mediaMetadata
            if (playerPreferences.shouldRememberSelections) {
                metadata.audioTrackIndex?.let { player.switchTrack(C.TRACK_TYPE_AUDIO, it) }
            }

            if (!playerPreferences.isSubtitleAutoLoadEnabled) {
                player.switchTrack(C.TRACK_TYPE_TEXT, -1)
                return
            }

            val textTracks = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
            if (textTracks.isNotEmpty()) {
                when {
                    metadata.subtitleTrackIndex == -1 -> player.switchTrack(C.TRACK_TYPE_TEXT, -1)
                    metadata.subtitleTrackIndex in textTracks.indices -> player.switchTrack(C.TRACK_TYPE_TEXT, metadata.subtitleTrackIndex!!)
                    else -> player.switchTrack(C.TRACK_TYPE_TEXT, findBestSubtitleTrackIndex(textTracks))
                }
            }
            val currentMediaItem = player.currentMediaItem ?: return
            loadExternalSubtitlesForCurrentItem(
                mediaId = currentMediaItem.mediaId,
                requestHeaders = currentMediaItem.mediaMetadata.requestHeaders,
            )
        }

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            super.onTrackSelectionParametersChanged(parameters)
            val player = mediaSession?.player ?: return
            val currentMediaItem = player.currentMediaItem ?: return

            val audioTrackIndex = player.getManuallySelectedTrackIndex(C.TRACK_TYPE_AUDIO)
            val subtitleTrackIndex = player.getManuallySelectedTrackIndex(C.TRACK_TYPE_TEXT)

            serviceScope.launch {
                val playbackStateUri = currentMediaItem.resolvePlaybackStateUri()
                if (audioTrackIndex != null) {
                    mediaRepository.updateMediumAudioTrack(
                        uri = playbackStateUri,
                        audioTrackIndex = audioTrackIndex,
                    )
                }
                if (subtitleTrackIndex != null) {
                    mediaRepository.updateMediumSubtitleTrack(
                        uri = playbackStateUri,
                        subtitleTrackIndex = subtitleTrackIndex,
                    )
                }
            }

            player.replaceMediaItem(
                player.currentMediaItemIndex,
                currentMediaItem.copy(
                    audioTrackIndex = audioTrackIndex,
                    subtitleTrackIndex = subtitleTrackIndex,
                ),
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)
            if (playbackState == Player.STATE_IDLE) {
                val player = mediaSession?.player ?: return
                player.trackSelectionParameters = TrackSelectionParameters.DEFAULT
                player.setPlaybackSpeed(playerPreferences.defaultPlaybackSpeed)
                return
            }

            if (playbackState == Player.STATE_ENDED) {
                val player = mediaSession?.player ?: return
                player.setPlaybackSpeed(playerPreferences.defaultPlaybackSpeed)
                return
            }

            if (playbackState == Player.STATE_READY) {
                val player = mediaSession?.player ?: return
                val currentMediaItem = player.currentMediaItem ?: return
                serviceScope.launch {
                    val playbackStateUri = currentMediaItem.resolvePlaybackStateUri()
                    mediaRepository.updateMediumLastPlayedTime(
                        uri = playbackStateUri,
                        lastPlayedTime = System.currentTimeMillis(),
                    )
                }
                updateFolderPlaybackAnchor(currentMediaItem)
            }
        }

        override fun onPlayWhenReadyChanged(shouldPlayWhenReady: Boolean, reason: Int) {
            super.onPlayWhenReadyChanged(shouldPlayWhenReady, reason)
            if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return

            val player = mediaSession?.player ?: return
            if (player.repeatMode != Player.REPEAT_MODE_OFF) {
                player.seekTo(0)
                handleRepeatedPlayback(player)
                player.play()
                return
            }
            player.clearMediaItems()
            player.stop()
            stopSelf()
        }

        override fun onRenderedFirstFrame() {
            super.onRenderedFirstFrame()
            val player = mediaSession?.player ?: return
            val currentMediaItem = player.currentMediaItem ?: return

            val format = player.currentTracks.groups
                .firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
                ?.getTrackFormat(0)
            val width = format?.width ?: 0
            val height = format?.height ?: 0
            val rotation = format?.rotationDegrees ?: 0
            val transfer = format?.colorInfo?.colorTransfer
            isCurrentVideoHdr = transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG
            Logger.info(
                TAG,
                "startup firstFrameReady format=${width}x$height rot=$rotation duration=${player.duration} seekable=${player.isCurrentMediaItemSeekable} hdr=$isCurrentVideoHdr",
            )

            val duration = player.duration.takeIf { it != C.TIME_UNSET }
            val currentPosition = player.currentPosition.takeIf { it != C.TIME_UNSET }
            val updatedMediaItem = currentMediaItem.copy(
                positionMs = currentPosition,
                durationMs = duration,
                videoWidth = width,
                videoHeight = height,
                videoRotation = rotation,
                hasRenderedFirstFrame = true,
                isVideoEffectsAvailable = shouldApplyVideoEffects(activeDecoderPriority),
            )
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                updatedMediaItem,
            )
            continueDeferredStartupPreciseResume(updatedMediaItem)
            hasRenderedFirstFrameForCurrentItem = true
            (player as? ExoPlayer)?.let { applyVideoFilters(it, playerPreferences, force = true) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            mediaSession?.run {
                serviceScope.launch {
                    val currentMediaItem = player.currentMediaItem ?: return@launch
                    val playbackStateUri = currentMediaItem.resolvePlaybackStateUri()
                    mediaRepository.updateMediumPosition(
                        uri = playbackStateUri,
                        position = player.currentPosition,
                    )
                }
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            super.onRepeatModeChanged(repeatMode)
            serviceScope.launch {
                preferencesRepository.updatePlayerPreferences {
                    it.copy(
                        loopMode = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> LoopMode.OFF
                            Player.REPEAT_MODE_ONE -> LoopMode.ONE
                            Player.REPEAT_MODE_ALL -> LoopMode.ALL
                            else -> LoopMode.OFF
                        },
                    )
                }
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            super.onAudioSessionIdChanged(audioSessionId)
            if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
                releaseLoudnessEnhancer()
                return
            }
            initializeLoudnessEnhancer(audioSessionId)
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            Logger.error(TAG, "Player error: code=${error.errorCode} name=${error.errorCodeName}", error)
            if (retryWithSoftwareDecoder(error)) return
            retryWithFixedSource(error)
        }
    }

    private fun retryWithSoftwareDecoder(error: PlaybackException): Boolean {
        if (!isHardwareVideoDecoderError(error)) return false
        val session = mediaSession ?: return false
        val failedPlayer = session.player as? ExoPlayer ?: return false
        val mediaId = failedPlayer.currentMediaItem?.mediaId ?: return false
        if (!softwareDecoderRetried.add(mediaId)) return false
        val mediaItems = (0 until failedPlayer.mediaItemCount).map { failedPlayer.getMediaItemAt(it) }
        if (mediaItems.isEmpty()) return false

        val currentIndex = failedPlayer.currentMediaItemIndex.coerceIn(0, mediaItems.lastIndex)
        val playbackPosition = failedPlayer.currentPosition.coerceAtLeast(0L)
        val shouldPlayWhenReady = failedPlayer.playWhenReady
        val playbackParameters = failedPlayer.playbackParameters
        val trackSelectionParameters = failedPlayer.trackSelectionParameters
        val shuffleModeEnabled = failedPlayer.shuffleModeEnabled
        val repeatMode = failedPlayer.repeatMode
        val isSkipSilenceEnabled = failedPlayer.isSkipSilenceEnabledForPlayer
        val subtitleDelayMilliseconds = failedPlayer.playerSpecificSubtitleDelayMilliseconds
        val subtitleSpeed = failedPlayer.playerSpecificSubtitleSpeed
        val retryPlayer = createPlayer(
            decoderPriority = DecoderPriority.PREFER_APP,
            assHandler = assHandler ?: return false,
        )
        Logger.debug(TAG, "Retrying playback with software decoder: $mediaId")

        retryPlayer.setMediaItems(mediaItems, currentIndex, playbackPosition)
        retryPlayer.restoreRuntimeState(
            trackSelectionParameters = trackSelectionParameters,
            shuffleModeEnabled = shuffleModeEnabled,
            repeatMode = repeatMode,
            isSkipSilenceEnabled = isSkipSilenceEnabled,
            subtitleDelayMilliseconds = subtitleDelayMilliseconds,
            subtitleSpeed = subtitleSpeed,
            playbackParameters = playbackParameters,
            mediaItemIndex = currentIndex,
            positionMs = playbackPosition,
        )
        retryPlayer.playWhenReady = shouldPlayWhenReady

        releaseLoudnessEnhancer()
        failedPlayer.removeListener(playbackStateListener)
        failedPlayer.removeAnalyticsListener(startupAnalyticsListener)
        session.player = retryPlayer
        retryPlayer.prepare()
        failedPlayer.clearMediaItems()
        failedPlayer.stop()
        failedPlayer.release()
        updateCurrentVideoEffectsAvailability(retryPlayer)
        return true
    }

    private fun ExoPlayer.restoreRuntimeState(
        trackSelectionParameters: TrackSelectionParameters,
        shuffleModeEnabled: Boolean,
        repeatMode: Int,
        isSkipSilenceEnabled: Boolean,
        subtitleDelayMilliseconds: Long,
        subtitleSpeed: Float,
        playbackParameters: androidx.media3.common.PlaybackParameters,
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        this.trackSelectionParameters = trackSelectionParameters
        this.shuffleModeEnabled = shuffleModeEnabled
        this.repeatMode = repeatMode
        this.isSkipSilenceEnabledForPlayer = isSkipSilenceEnabled
        this.playerSpecificSubtitleDelayMilliseconds = subtitleDelayMilliseconds
        this.playerSpecificSubtitleSpeed = subtitleSpeed
        setPlaybackParameters(playbackParameters)
        seekTo(mediaItemIndex, positionMs)
    }

    private fun switchPlayerDecoderPriority(decoderPriority: DecoderPriority) {
        if (decoderPriority == activeDecoderPriority) return
        val session = mediaSession ?: return
        val currentPlayer = session.player as? ExoPlayer ?: return
        val mediaItems = (0 until currentPlayer.mediaItemCount).map { currentPlayer.getMediaItemAt(it) }
        if (mediaItems.isEmpty()) {
            Logger.info(TAG, "Switch decoder to ${decoderPriority.logName()} without active media items")
            val nextPlayer = createPlayer(
                decoderPriority = decoderPriority,
                assHandler = assHandler ?: return,
            )
            releaseLoudnessEnhancer()
            currentPlayer.removeListener(playbackStateListener)
            currentPlayer.removeAnalyticsListener(startupAnalyticsListener)
            session.player = nextPlayer
            currentPlayer.release()
            return
        }

        val currentIndex = currentPlayer.currentMediaItemIndex.coerceIn(0, mediaItems.lastIndex)
        val playbackPosition = currentPlayer.currentPosition.coerceAtLeast(0L)
        val shouldPlayWhenReady = currentPlayer.playWhenReady
        val playbackParameters = currentPlayer.playbackParameters
        val trackSelectionParameters = currentPlayer.trackSelectionParameters
        val shuffleModeEnabled = currentPlayer.shuffleModeEnabled
        val repeatMode = currentPlayer.repeatMode
        val isSkipSilenceEnabled = currentPlayer.isSkipSilenceEnabledForPlayer
        val subtitleDelayMilliseconds = currentPlayer.playerSpecificSubtitleDelayMilliseconds
        val subtitleSpeed = currentPlayer.playerSpecificSubtitleSpeed
        val currentDecoderPriority = activeDecoderPriority
        val nextPlayer = createPlayer(
            decoderPriority = decoderPriority,
            assHandler = assHandler ?: return,
        )
        Logger.info(
            TAG,
            "Switch decoder from ${currentDecoderPriority.logName()} to ${decoderPriority.logName()} at index=$currentIndex position=$playbackPosition",
        )

        nextPlayer.setMediaItems(mediaItems, currentIndex, playbackPosition)
        nextPlayer.restoreRuntimeState(
            trackSelectionParameters = trackSelectionParameters,
            shuffleModeEnabled = shuffleModeEnabled,
            repeatMode = repeatMode,
            isSkipSilenceEnabled = isSkipSilenceEnabled,
            subtitleDelayMilliseconds = subtitleDelayMilliseconds,
            subtitleSpeed = subtitleSpeed,
            playbackParameters = playbackParameters,
            mediaItemIndex = currentIndex,
            positionMs = playbackPosition,
        )
        nextPlayer.playWhenReady = shouldPlayWhenReady

        releaseLoudnessEnhancer()
        currentPlayer.removeListener(playbackStateListener)
        currentPlayer.removeAnalyticsListener(startupAnalyticsListener)
        session.player = nextPlayer
        nextPlayer.prepare()
        currentPlayer.clearMediaItems()
        currentPlayer.stop()
        currentPlayer.release()
        updateCurrentVideoEffectsAvailability(nextPlayer)
    }

    private fun isHardwareVideoDecoderError(error: PlaybackException): Boolean {
        if (!activeDecoderPriority.shouldRetryWithSoftwareDecoder()) return false
        val exoError = error as? ExoPlaybackException ?: return false
        if (exoError.type != ExoPlaybackException.TYPE_RENDERER) return false
        if (exoError.rendererFormat?.sampleMimeType?.startsWith("video/") != true) return false
        val rendererException = exoError.rendererException
        if (rendererException !is MediaCodecRenderer.DecoderInitializationException && rendererException.cause == null) return false
        return true
    }

    private fun retryWithFixedSource(error: PlaybackException) {
        if (!hasParserExceptionCause(error)) return
        val player = mediaSession?.player as? ExoPlayer ?: return
        val currentItem = player.currentMediaItem ?: return
        if (!mediaParserRetried.add(currentItem.mediaId)) return

        val mediaId = currentItem.mediaId
        serviceScope.launch {
            val uri = mediaId.toUri()
            val skipRegion = withContext(Dispatchers.IO) { detectDuplicateMoov(uri) }

            withContext(Dispatchers.Main) {
                val currentPlayer = mediaSession?.player as? ExoPlayer ?: return@withContext
                if (currentPlayer.playerError == null) return@withContext

                val index = (0 until currentPlayer.mediaItemCount).firstOrNull {
                    currentPlayer.getMediaItemAt(it).mediaId == mediaId
                } ?: return@withContext

                val item = currentPlayer.getMediaItemAt(index)
                val dataSourceFactory = if (skipRegion != null) {
                    Logger.debug(
                        TAG,
                        "Duplicate moov at ${skipRegion.start}+${skipRegion.length}, retrying: $mediaId",
                    )
                    DataSource.Factory {
                        GapSkipDataSource(
                            upstream = DefaultDataSource.Factory(applicationContext)
                                .createDataSource(),
                            targetUri = uri,
                            gapStart = skipRegion.start,
                            gapLength = skipRegion.length,
                        )
                    }
                } else {
                    Logger.debug(TAG, "Retrying with lenient extractor: $mediaId")
                    DefaultDataSource.Factory(applicationContext)
                }

                val mediaSource = DefaultMediaSourceFactory(
                    dataSourceFactory,
                    LenientExtractorsFactory(),
                ).createMediaSource(item)

                currentPlayer.removeMediaItem(index)
                currentPlayer.addMediaSource(index, mediaSource)
                currentPlayer.seekTo(index, 0)
                currentPlayer.prepare()
                currentPlayer.playWhenReady = true
            }
        }
    }

    private fun hasParserExceptionCause(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        repeat(3) {
            val current = cause ?: return false
            if (current is ParserException) return true
            cause = current.cause
        }
        return false
    }

    private data class SkipRegion(val start: Long, val length: Long)

    private fun createPlaybackExtractorsFactory(
        assSubtitleParserFactory: AssSubtitleParserFactory,
        assHandler: AssHandler,
        shouldUseFastStart: Boolean,
    ): ExtractorsFactory {
        val baseFactory = DefaultExtractorsFactory()
        return ExtractorsFactory {
            val extractors = baseFactory.createExtractors()
            for (i in extractors.indices) {
                if (extractors[i] is MatroskaExtractor) {
                    extractors[i] = NormalizingAssMatroskaExtractor(
                        assSubtitleParserFactory,
                        assHandler,
                    ).also { extractor ->
                        if (shouldUseFastStart) {
                            disableSeekForCues(extractor)
                        }
                    }
                }
            }
            extractors
        }
    }

    private fun createMediaSourceFactory(
        assSubtitleParserFactory: AssSubtitleParserFactory,
        assHandler: AssHandler,
        shouldUseFastStart: Boolean,
    ): DefaultMediaSourceFactory = DefaultMediaSourceFactory(
        DefaultDataSource.Factory(applicationContext),
        createPlaybackExtractorsFactory(
            assSubtitleParserFactory = assSubtitleParserFactory,
            assHandler = assHandler,
            shouldUseFastStart = shouldUseFastStart,
        ),
    ).setSubtitleParserFactory(assSubtitleParserFactory)

    private fun disableSeekForCues(extractor: MatroskaExtractor) {
        try {
            val field = MatroskaExtractor::class.java.getDeclaredField("seekForCuesEnabled")
            field.isAccessible = true
            field.set(extractor, false)
        } catch (e: Exception) {
            Logger.error(TAG, "disableSeekForCues failed", e)
        }
    }

    private fun warmUpCodecCache() {
        val mimeTypes = listOf(
            MimeTypes.VIDEO_H265,
            MimeTypes.VIDEO_H264,
            MimeTypes.AUDIO_AAC,
        )
        for (mimeType in mimeTypes) {
            try {
                MediaCodecUtil.getDecoderInfos(mimeType, false, false)
            } catch (_: MediaCodecUtil.DecoderQueryException) {
            }
        }
    }

    private fun detectDuplicateMoov(uri: Uri): SkipRegion? {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                var offset = 0L
                var hasSeenFirstMoov = false
                val header = ByteArray(8)

                while (true) {
                    if (!readFully(stream, header)) break

                    val size = ((header[0].toLong() and 0xFF) shl 24) or
                        ((header[1].toLong() and 0xFF) shl 16) or
                        ((header[2].toLong() and 0xFF) shl 8) or
                        (header[3].toLong() and 0xFF)
                    val type = String(header, 4, 4, Charsets.US_ASCII)

                    if (size < 8) break

                    if (type == "moov") {
                        if (hasSeenFirstMoov) {
                            return SkipRegion(start = offset, length = size)
                        }
                        hasSeenFirstMoov = true
                    }

                    if (type == "mdat") break

                    val bodySize = size - 8
                    var skipped = 0L
                    while (skipped < bodySize) {
                        val s = stream.skip(bodySize - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                    if (skipped < bodySize) break
                    offset += size
                }
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to scan MP4 structure", e)
        }
        return null
    }

    private class GapSkipDataSource(
        private val upstream: DataSource,
        private val targetUri: Uri,
        private val gapStart: Long,
        private val gapLength: Long,
    ) : DataSource by upstream {

        private var isTarget = false
        private var hasCrossedGap = false
        private var bytesUntilGap = Long.MAX_VALUE
        private var currentDataSpec: DataSpec? = null

        override fun open(dataSpec: DataSpec): Long {
            currentDataSpec = dataSpec
            isTarget = dataSpec.uri == targetUri
            if (!isTarget) return upstream.open(dataSpec)

            val virtualPos = dataSpec.position
            if (virtualPos >= gapStart) {
                hasCrossedGap = true
                bytesUntilGap = Long.MAX_VALUE
                val adjustedSpec = dataSpec.buildUpon()
                    .setPosition(virtualPos + gapLength)
                    .build()
                return upstream.open(adjustedSpec)
            }

            hasCrossedGap = false
            bytesUntilGap = gapStart - virtualPos
            val length = upstream.open(dataSpec)
            if (length == C.LENGTH_UNSET.toLong()) return length

            val physicalEnd = virtualPos + length
            return when {
                physicalEnd > gapStart + gapLength -> length - gapLength
                physicalEnd > gapStart -> gapStart - virtualPos
                else -> length
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!isTarget) return upstream.read(buffer, offset, length)

            if (!hasCrossedGap && bytesUntilGap <= 0L) {
                upstream.close()
                upstream.open(
                    DataSpec.Builder()
                        .setUri(currentDataSpec!!.uri)
                        .setPosition(gapStart + gapLength)
                        .build(),
                )
                hasCrossedGap = true
            }

            val toRead = if (!hasCrossedGap) {
                minOf(length.toLong(), bytesUntilGap).toInt()
            } else {
                length
            }
            val bytesRead = upstream.read(buffer, offset, toRead)
            if (bytesRead > 0 && !hasCrossedGap) {
                bytesUntilGap -= bytesRead
            }
            return bytesRead
        }

        override fun close() {
            isTarget = false
            upstream.close()
        }
    }

    private class LenientExtractorsFactory : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> {
            val defaults = DefaultExtractorsFactory().createExtractors()
            return Array(defaults.size + 1) { i ->
                if (i == 0) LenientMp4Extractor() else defaults[i - 1]
            }
        }
    }

    private class LenientMp4Extractor : Extractor {

        private val delegate = Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED)

        override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

        override fun init(output: ExtractorOutput) = delegate.init(output)

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = try {
            delegate.read(input, seekPosition)
        } catch (e: ParserException) {
            Logger.error(TAG, "Lenient extractor treating error as end of input", e)
            Extractor.RESULT_END_OF_INPUT
        }

        override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

        override fun release() = delegate.release()
    }

    private fun setEnhancerTargetGain(gain: Int) {
        requestedVolumeGain = gain.coerceAtLeast(0)
        if (loudnessEnhancer == null && playerPreferences.isVolumeBoostEnabled) {
            val audioSessionId = mediaSession?.player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET
            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                initializeLoudnessEnhancer(audioSessionId)
            }
        }
        applyLoudnessEnhancerGain()
    }

    private fun initializeLoudnessEnhancer(audioSessionId: Int) {
        if (!playerPreferences.isVolumeBoostEnabled) return
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        try {
            releaseLoudnessEnhancer()
            loudnessEnhancer = LoudnessEnhancer(audioSessionId)
            Logger.debug(TAG, "Loudness enhancer initialized: boost=true")
            applyLoudnessEnhancerGain()
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to initialize loudness enhancer", e)
            loudnessEnhancer = null
        }
    }

    private fun releaseLoudnessEnhancer() {
        val enhancer = loudnessEnhancer ?: return
        try {
            enhancer.enabled = false
        } catch (_: Exception) {
        }
        try {
            enhancer.release()
        } catch (_: Exception) {
        } finally {
            loudnessEnhancer = null
        }
    }

    private fun handleRepeatedPlayback(player: Player) {
        player.currentMediaItem?.mediaMetadata?.let { metadata ->
            player.setPlaybackSpeed(playerPreferences.defaultPlaybackSpeed)
            player.playerSpecificSubtitleDelayMilliseconds = metadata.subtitleDelayMilliseconds ?: 0L
            player.playerSpecificSubtitleSpeed = metadata.subtitleSpeed ?: 1f
        }
    }

    private fun applyVideoFilters(preferences: PlayerPreferences) {
        val player = mediaSession?.player as? ExoPlayer ?: return
        applyVideoFilters(player, preferences)
    }

    private fun applyVideoFilters(
        player: ExoPlayer,
        preferences: PlayerPreferences,
        force: Boolean = false,
    ) {
        val videoFilters = preferences.toVideoFilterPreferences()
        scheduleVideoFilters(
            player = player,
            videoFilters = videoFilters,
            delayMs = 0L,
            shouldSkipStalePreferences = true,
            logPrefix = "Apply",
            force = force,
        )
    }

    private fun previewVideoFilters(preferences: PlayerPreferences) {
        val player = mediaSession?.player as? ExoPlayer ?: return
        val videoFilters = preferences.toVideoFilterPreferences()
        scheduleVideoFilters(
            player = player,
            videoFilters = videoFilters,
            delayMs = VIDEO_FILTER_PREVIEW_DELAY_MS,
            shouldSkipStalePreferences = false,
            logPrefix = "Preview",
        )
    }

    private fun scheduleVideoFilters(
        player: ExoPlayer,
        videoFilters: VideoFilterPreferences,
        delayMs: Long,
        shouldSkipStalePreferences: Boolean,
        logPrefix: String,
        force: Boolean = false,
    ) {
        pendingVideoFiltersJob?.cancel()
        if (!force && currentVideoEffectsState == VideoEffectsState(videoFilters, activeDecoderPriority, isPipelineInitialized = true)) return

        pendingVideoFiltersJob = serviceScope.launch {
            fun hasStalePreferences() = shouldSkipStalePreferences &&
                preferencesRepository.playerPreferences.value.toVideoFilterPreferences() != videoFilters

            if (delayMs > 0L) delay(delayMs)
            if (hasStalePreferences()) return@launch

            val decoderPriority = activeDecoderPriority
            val transition = videoFilterTransition.to(
                targetFilters = videoFilters,
                startMs = SystemClock.elapsedRealtime(),
                durationMs = VIDEO_FILTER_TRANSITION_DURATION_MS,
            )
            if (hasStalePreferences()) return@launch

            applyVideoEffects(player, videoFilters, decoderPriority, transition)
            Logger.debug(TAG, "$logPrefix video filters: $videoFilters effect=${activeVideoFiltersEffect != null}")
        }.also { job ->
            job.invokeOnCompletion {
                if (pendingVideoFiltersJob == job) pendingVideoFiltersJob = null
            }
        }
    }

    private fun applyVideoEffects(
        player: ExoPlayer,
        videoFilters: VideoFilterPreferences,
        decoderPriority: DecoderPriority,
        transition: VideoFilterTransition,
    ) {
        val effect = activeVideoFiltersEffect
        val canUpdateActiveEffect = effect != null &&
            shouldUseVideoFiltersEffect(
                filters = videoFilters,
                decoderPriority = decoderPriority,
            )
        if (canUpdateActiveEffect) {
            videoFilterTransition = transition
            effect!!.updateTransition(transition)
            currentVideoEffectsState = VideoEffectsState(
                filters = videoFilters,
                decoderPriority = decoderPriority,
                isPipelineInitialized = true,
            )
            refreshPausedVideoFrame(player)
            updateCurrentVideoEffectsAvailability(player)
            return
        }

        val effects = buildVideoEffects(
            transition = transition,
            decoderPriority = decoderPriority,
        )
        if (!hasRenderedFirstFrameForCurrentItem && activeVideoFiltersEffect == null && effects.isNotEmpty()) {
            Logger.debug(TAG, "Defer setVideoEffects until first frame to resolve HDR state")
            return
        }
        if (effects.isEmpty() && activeVideoFiltersEffect == null) {
            currentVideoEffectsState = VideoEffectsState(
                filters = videoFilters,
                decoderPriority = decoderPriority,
                isPipelineInitialized = false,
            )
            Logger.debug(TAG, "Skip setVideoEffects: no filters and pipeline not initialized")
            updateCurrentVideoEffectsAvailability(player)
            return
        }
        videoFilterTransition = if (effects.isEmpty()) VideoFilterTransition.default() else transition
        currentVideoEffectsState = VideoEffectsState(
            filters = videoFilters,
            decoderPriority = decoderPriority,
            isPipelineInitialized = true,
        )
        activeVideoFiltersEffect = effects.filterIsInstance<VideoFiltersEffect>().firstOrNull()
        player.setVideoEffects(effects)
        refreshPausedVideoFrame(player)
        updateCurrentVideoEffectsAvailability(player)
    }

    private fun refreshPausedVideoFrame(player: ExoPlayer) {
        if (player.playWhenReady) return
        if (player.playbackState != Player.STATE_READY) return
        val position = player.currentPosition.takeIf { it != C.TIME_UNSET } ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val targetPosition = duration
            ?.let { (position + PAUSED_FRAME_REFRESH_OFFSET_MS).coerceAtMost(it) }
            ?.takeIf { it != position }
            ?: (position - PAUSED_FRAME_REFRESH_OFFSET_MS).coerceAtLeast(0L)
        if (targetPosition == position) return
        player.seekTo(targetPosition)
        player.seekTo(position)
    }

    private fun updateCurrentVideoEffectsAvailability(player: ExoPlayer) {
        val currentMediaItem = player.currentMediaItem ?: return
        val isVideoEffectsAvailable = shouldApplyVideoEffects(activeDecoderPriority)
        if (currentMediaItem.mediaMetadata.isVideoEffectsAvailable == isVideoEffectsAvailable) return

        player.replaceMediaItem(
            player.currentMediaItemIndex,
            currentMediaItem.copy(isVideoEffectsAvailable = isVideoEffectsAvailable),
        )
        Logger.debug(TAG, "Video effects availability: available=$isVideoEffectsAvailable decoder=$activeDecoderPriority")
    }

    private fun PlayerPreferences.toVideoFilterPreferences(): VideoFilterPreferences {
        if (!shouldApplyVideoFilters) return VideoFilterPreferences.default()

        val filters = VideoFilterPreferences(
            shouldApply = true,
            isBrightnessEnabled = isVideoBrightnessFilterEnabled,
            brightness = if (isVideoBrightnessFilterEnabled) {
                videoBrightness.coerceIn(PlayerPreferences.MIN_VIDEO_BRIGHTNESS, PlayerPreferences.MAX_VIDEO_BRIGHTNESS)
            } else {
                PlayerPreferences.DEFAULT_VIDEO_BRIGHTNESS
            },
            isContrastEnabled = isVideoContrastFilterEnabled,
            contrast = if (isVideoContrastFilterEnabled) {
                videoContrast.coerceIn(PlayerPreferences.MIN_VIDEO_CONTRAST, PlayerPreferences.MAX_VIDEO_CONTRAST)
            } else {
                PlayerPreferences.DEFAULT_VIDEO_CONTRAST
            },
            isSaturationEnabled = isVideoSaturationFilterEnabled,
            saturation = if (isVideoSaturationFilterEnabled) {
                videoSaturation.coerceIn(PlayerPreferences.MIN_VIDEO_SATURATION, PlayerPreferences.MAX_VIDEO_SATURATION)
            } else {
                PlayerPreferences.DEFAULT_VIDEO_SATURATION
            },
            isHueEnabled = isVideoHueFilterEnabled,
            hue = if (isVideoHueFilterEnabled) {
                videoHue.coerceIn(PlayerPreferences.MIN_VIDEO_HUE, PlayerPreferences.MAX_VIDEO_HUE)
            } else {
                PlayerPreferences.DEFAULT_VIDEO_HUE
            },
            isGammaEnabled = isVideoGammaFilterEnabled,
            gamma = if (isVideoGammaFilterEnabled) {
                videoGamma.coerceIn(PlayerPreferences.MIN_VIDEO_GAMMA, PlayerPreferences.MAX_VIDEO_GAMMA)
            } else {
                PlayerPreferences.DEFAULT_VIDEO_GAMMA
            },
            isSharpeningEnabled = isVideoSharpeningFilterEnabled,
            sharpening = if (isVideoSharpeningFilterEnabled) {
                videoSharpening.coerceIn(PlayerPreferences.DEFAULT_VIDEO_SHARPENING, PlayerPreferences.MAX_VIDEO_SHARPENING)
            } else {
                PlayerPreferences.DEFAULT_VIDEO_SHARPENING
            },
        )
        return if (filters.shouldCreateEffect()) filters else VideoFilterPreferences.default()
    }

    private fun Bundle.toPlayerPreferences(): PlayerPreferences = PlayerPreferences(
        shouldApplyVideoFilters = getBoolean(CustomCommands.SHOULD_APPLY_VIDEO_FILTERS_KEY, false),
        isVideoBrightnessFilterEnabled = getBoolean(CustomCommands.IS_VIDEO_BRIGHTNESS_FILTER_ENABLED_KEY, false),
        videoBrightness = getFloat(CustomCommands.VIDEO_BRIGHTNESS_KEY, PlayerPreferences.DEFAULT_VIDEO_BRIGHTNESS),
        isVideoContrastFilterEnabled = getBoolean(CustomCommands.IS_VIDEO_CONTRAST_FILTER_ENABLED_KEY, false),
        videoContrast = getFloat(CustomCommands.VIDEO_CONTRAST_KEY, PlayerPreferences.DEFAULT_VIDEO_CONTRAST),
        isVideoSaturationFilterEnabled = getBoolean(CustomCommands.IS_VIDEO_SATURATION_FILTER_ENABLED_KEY, false),
        videoSaturation = getFloat(CustomCommands.VIDEO_SATURATION_KEY, PlayerPreferences.DEFAULT_VIDEO_SATURATION),
        isVideoHueFilterEnabled = getBoolean(CustomCommands.IS_VIDEO_HUE_FILTER_ENABLED_KEY, false),
        videoHue = getFloat(CustomCommands.VIDEO_HUE_KEY, PlayerPreferences.DEFAULT_VIDEO_HUE),
        isVideoGammaFilterEnabled = getBoolean(CustomCommands.IS_VIDEO_GAMMA_FILTER_ENABLED_KEY, false),
        videoGamma = getFloat(CustomCommands.VIDEO_GAMMA_KEY, PlayerPreferences.DEFAULT_VIDEO_GAMMA),
        isVideoSharpeningFilterEnabled = getBoolean(CustomCommands.IS_VIDEO_SHARPENING_FILTER_ENABLED_KEY, false),
        videoSharpening = getFloat(CustomCommands.VIDEO_SHARPENING_KEY, PlayerPreferences.DEFAULT_VIDEO_SHARPENING),
    )

    private fun buildVideoEffects(
        transition: VideoFilterTransition,
        decoderPriority: DecoderPriority,
    ): List<Effect> {
        if (!shouldUseVideoFiltersEffect(transition.targetFilters, decoderPriority)) return emptyList()
        return listOf(
            VideoFiltersEffect(
                transition = transition,
                transitionDurationMs = VIDEO_FILTER_TRANSITION_DURATION_MS,
            ),
        )
    }

    private fun shouldUseVideoFiltersEffect(
        filters: VideoFilterPreferences,
        decoderPriority: DecoderPriority,
    ): Boolean = shouldApplyVideoEffects(decoderPriority) && !isCurrentVideoHdr && filters.shouldCreateEffect()

    private fun applyLoudnessEnhancerGain() {
        val enhancer = loudnessEnhancer ?: return
        val gain = requestedVolumeGain

        try {
            enhancer.setTargetGain(gain)
            enhancer.enabled = gain > 0
            Logger.debug(TAG, "Apply loudness gain: requested=$requestedVolumeGain, enabled=${gain > 0}")
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to apply loudness enhancer gain", e)
        }
    }

    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            return MediaSession.ConnectionResult.accept(
                connectionResult.availableSessionCommands
                    .buildUpon()
                    .addSessionCommands(customCommands)
                    .build(),
                connectionResult.availablePlayerCommands,
            )
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future(Dispatchers.Default) {
            preciseSeekMediaIds.clear()
            val updatedMediaItems = updatedMediaItemsWithMetadata(mediaItems)
            prepareCachedPreciseSeekMediaItems(updatedMediaItems)
            loadArtworkInBackground(updatedMediaItems)
            return@future MediaSession.MediaItemsWithStartPosition(updatedMediaItems, startIndex, startPositionMs)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = serviceScope.future(Dispatchers.Default) {
            val updatedMediaItems = updatedMediaItemsWithMetadata(mediaItems)
            prepareCachedPreciseSeekMediaItems(updatedMediaItems)
            loadArtworkInBackground(updatedMediaItems)
            return@future updatedMediaItems.toMutableList()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = serviceScope.future {
            val command = CustomCommands.fromSessionCommand(customCommand)
                ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)

            when (command) {
                CustomCommands.ADD_SUBTITLE_TRACK -> {
                    val subtitleUriString = args.getString(CustomCommands.SUBTITLE_TRACK_URI_KEY)
                    if (subtitleUriString.isNullOrBlank()) {
                        Logger.info(TAG, "Add subtitle track rejected: empty uri")
                        return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    }
                    val subtitleUri = subtitleUriString.toUri()
                    val player = mediaSession?.player
                    if (player == null) {
                        Logger.info(TAG, "Add subtitle track rejected: player unavailable")
                        return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    }
                    val currentMediaItem = player.currentMediaItem
                    if (currentMediaItem == null) {
                        Logger.info(TAG, "Add subtitle track rejected: current media item unavailable")
                        return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    }

                    val newSubConfiguration = uriToSubtitleConfiguration(
                        uri = subtitleUri,
                        subtitleEncoding = playerPreferences.subtitleTextEncoding,
                    )
                    val playbackStateUri = currentMediaItem.resolvePlaybackStateUri()
                    mediaRepository.updateMediumPosition(
                        uri = playbackStateUri,
                        position = player.currentPosition,
                    )
                    mediaRepository.addExternalSubtitleToMedium(
                        uri = playbackStateUri,
                        subtitleUri = subtitleUri,
                    )
                    player.addAdditionalSubtitleConfiguration(newSubConfiguration)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.PRECISE_SEEK_TO -> {
                    val targetPositionMs = args.getLong(CustomCommands.SEEK_POSITION_MS_KEY, C.TIME_UNSET)
                    if (targetPositionMs == C.TIME_UNSET) {
                        return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    }
                    return@future requestSeekForCurrentItem(targetPositionMs)
                }

                CustomCommands.SET_SKIP_SILENCE_ENABLED -> {
                    val isSkipSilenceEnabled = args.getBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY)
                    mediaSession?.player?.isSkipSilenceEnabledForPlayer = isSkipSilenceEnabled
                    mediaSession?.sessionExtras = Bundle().apply {
                        putBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY, isSkipSilenceEnabled)
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SKIP_SILENCE_ENABLED -> {
                    val isSkipSilenceEnabled = mediaSession?.player?.isSkipSilenceEnabledForPlayer ?: false
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY, isSkipSilenceEnabled)
                        },
                    )
                }

                CustomCommands.SET_IS_SCRUBBING_MODE_ENABLED -> {
                    val isScrubbingModeEnabled = args.getBoolean(CustomCommands.IS_SCRUBBING_MODE_ENABLED_KEY)
                    mediaSession?.player?.setIsScrubbingModeEnabled(isScrubbingModeEnabled)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.SET_PERSISTENT_PLAYBACK_SPEED -> {
                    val playbackSpeed = args.getFloat(CustomCommands.PLAYBACK_SPEED_KEY)
                    val player = mediaSession?.player
                        ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    player.setPlaybackSpeed(playbackSpeed)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.SET_TRANSIENT_PLAYBACK_SPEED -> {
                    val playbackSpeed = args.getFloat(CustomCommands.PLAYBACK_SPEED_KEY)
                    val player = mediaSession?.player
                        ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    player.setPlaybackSpeed(playbackSpeed)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.IS_LOUDNESS_GAIN_SUPPORTED -> {
                    val isSupported = loudnessEnhancer != null
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putBoolean(CustomCommands.IS_LOUDNESS_GAIN_SUPPORTED_KEY, isSupported)
                        },
                    )
                }

                CustomCommands.SET_LOUDNESS_GAIN -> {
                    val gain = args.getInt(CustomCommands.LOUDNESS_GAIN_KEY, 0)
                    setEnhancerTargetGain(gain)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_LOUDNESS_GAIN -> {
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putInt(CustomCommands.LOUDNESS_GAIN_KEY, requestedVolumeGain)
                        },
                    )
                }

                CustomCommands.PREVIEW_VIDEO_FILTERS -> {
                    previewVideoFilters(args.toPlayerPreferences())
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SUBTITLE_DELAY -> {
                    val subtitleDelay = mediaSession?.player?.playerSpecificSubtitleDelayMilliseconds ?: 0
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putLong(CustomCommands.SUBTITLE_DELAY_KEY, subtitleDelay)
                        },
                    )
                }

                CustomCommands.SET_SUBTITLE_DELAY -> {
                    val subtitleDelay = args.getLong(CustomCommands.SUBTITLE_DELAY_KEY)
                    mediaSession?.player?.playerSpecificSubtitleDelayMilliseconds = subtitleDelay
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SUBTITLE_SPEED -> {
                    val subtitleSpeed = mediaSession?.player?.playerSpecificSubtitleSpeed ?: 0f
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putFloat(CustomCommands.SUBTITLE_SPEED_KEY, subtitleSpeed)
                        },
                    )
                }

                CustomCommands.SET_SUBTITLE_SPEED -> {
                    val subtitleSpeed = args.getFloat(CustomCommands.SUBTITLE_SPEED_KEY)
                    mediaSession?.player?.playerSpecificSubtitleSpeed = subtitleSpeed
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.STOP_PLAYER_SESSION -> {
                    mediaSession?.run {
                        serviceScope.launch {
                            val currentMediaItem = player.currentMediaItem ?: return@launch
                            val playbackStateUri = currentMediaItem.resolvePlaybackStateUri()
                            mediaRepository.updateMediumPosition(
                                uri = playbackStateUri,
                                position = player.currentPosition,
                            )
                        }
                        player.clearMediaItems()
                        player.stop()
                    }
                    stopSelf()
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private fun createPlayer(
        decoderPriority: DecoderPriority,
        assHandler: AssHandler,
    ): ExoPlayer {
        activeDecoderPriority = decoderPriority
        val extensionRendererMode = decoderPriority.extensionRendererMode()
        val shouldEnableDecoderFallback = decoderPriority.shouldEnableDecoderFallback()
        val shouldUseAudioExtensionFallback = decoderPriority.shouldUseAudioExtensionFallback()

        val renderersFactory = NormalizingRenderersFactory(
            context = applicationContext,
            volumeNormalizationAudioProcessor = volumeNormalizationAudioProcessor,
            shouldUseAudioExtensionFallback = shouldUseAudioExtensionFallback,
        )
            .setEnableDecoderFallback(shouldEnableDecoderFallback)
            .setExtensionRendererMode(extensionRendererMode)

        val preferences = playerPreferences
        val trackSelector = DefaultTrackSelector(applicationContext).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage(preferences.preferredAudioLanguage)
                    .setPreferredTextLanguage(preferences.preferredSubtitleLanguage),
            )
        }

        return ExoPlayer.Builder(applicationContext)
            .setMediaSourceFactory(sessionMediaSourceFactory)
            .setRenderersFactory(renderersFactory.withAssSupport(assHandler))
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                preferences.shouldRequireAudioFocus,
            )
            .setHandleAudioBecomingNoisy(preferences.shouldPauseOnHeadsetDisconnect)
            .build()
            .also {
                assHandler.init(it)
                it.addListener(playbackStateListener)
                it.addAnalyticsListener(startupAnalyticsListener)
                it.pauseAtEndOfMediaItems = !preferences.shouldAutoPlay
                it.repeatMode = when (preferences.loopMode) {
                    LoopMode.OFF -> Player.REPEAT_MODE_OFF
                    LoopMode.ONE -> Player.REPEAT_MODE_ONE
                    LoopMode.ALL -> Player.REPEAT_MODE_ALL
                }
                currentVideoEffectsState = VideoEffectsState(
                    filters = VideoFilterPreferences.default(),
                    decoderPriority = activeDecoderPriority,
                )
                activeVideoFiltersEffect = null
                applyVideoFilters(it, preferences)
            }
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch(Dispatchers.IO) { warmUpCodecCache() }
        serviceScope.launch {
            preferencesRepository.playerPreferences
                .distinctUntilChanged { old, new -> old.decoderPriority == new.decoderPriority }
                .collect { preferences -> switchPlayerDecoderPriority(preferences.decoderPriority) }
        }
        serviceScope.launch {
            preferencesRepository.playerPreferences
                .distinctUntilChanged { old, new -> old.toVideoFilterPreferences() == new.toVideoFilterPreferences() }
                .collect(::applyVideoFilters)
        }
        serviceScope.launch {
            preferencesRepository.playerPreferences
                .distinctUntilChanged { old, new -> old.isVolumeBoostEnabled == new.isVolumeBoostEnabled }
                .collect { preferences ->
                    if (preferences.isVolumeBoostEnabled) {
                        val audioSessionId = mediaSession?.player?.audioSessionId ?: return@collect
                        initializeLoudnessEnhancer(audioSessionId)
                    } else {
                        releaseLoudnessEnhancer()
                    }
                }
        }
        serviceScope.launch {
            preferencesRepository.playerPreferences
                .distinctUntilChanged { old, new -> old.isVolumeNormalizationEnabled == new.isVolumeNormalizationEnabled }
                .collect { preferences ->
                    volumeNormalizationAudioProcessor.isEnabled = preferences.isVolumeNormalizationEnabled
                }
        }
        volumeNormalizationAudioProcessor.isEnabled = playerPreferences.isVolumeNormalizationEnabled
        val assHandler = AssHandler(renderType = AssRenderType.OVERLAY_CANVAS)
        this.assHandler = assHandler
        AssHandlerRegistry.register(assHandler)
        val assSubtitleParserFactory = AssSubtitleParserFactory(assHandler)
        this.assSubtitleParserFactory = assSubtitleParserFactory
        fastStartMediaSourceFactory = createMediaSourceFactory(
            assSubtitleParserFactory = assSubtitleParserFactory,
            assHandler = assHandler,
            shouldUseFastStart = true,
        )
        preciseSeekMediaSourceFactory = createMediaSourceFactory(
            assSubtitleParserFactory = assSubtitleParserFactory,
            assHandler = assHandler,
            shouldUseFastStart = false,
        )
        sessionMediaSourceFactory = object : MediaSource.Factory {
            override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
                sessionDrmSessionManagerProvider = drmSessionManagerProvider
                return this
            }

            override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
                sessionLoadErrorHandlingPolicy = loadErrorHandlingPolicy
                return this
            }

            override fun getSupportedTypes(): IntArray = fastStartMediaSourceFactory.supportedTypes

            override fun createMediaSource(mediaItem: MediaItem): MediaSource = this@ExoPlayerService.createMediaSource(mediaItem)
        }

        val player = createPlayer(
            decoderPriority = playerPreferences.decoderPriority,
            assHandler = assHandler,
        )

        try {
            mediaSession = MediaSession.Builder(this, player).apply {
                setSessionActivity(
                    PendingIntent.getActivity(
                        this@ExoPlayerService,
                        0,
                        Intent(this@ExoPlayerService, ExoPlayerActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                setCallback(mediaSessionCallback)
                setCustomLayout(
                    listOf(
                        CommandButton.Builder(ICON_UNDEFINED)
                            .setCustomIconResId(app.gyrolet.mpvrx.R.drawable.ic_close)
                            .setDisplayName("Stop")
                            .setSessionCommand(CustomCommands.STOP_PLAYER_SESSION.sessionCommand)
                            .setEnabled(true)
                            .build(),
                    ),
                )
            }.build()
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to create media session", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseLoudnessEnhancer()
        pendingPreciseSeekPromotionJob?.cancel()
        pendingPreciseSeekPromotionJob = null
        preciseSeekRequestId++
        assHandler?.let(AssHandlerRegistry::unregister)
        assHandler = null
        mediaSession?.run {
            player.clearMediaItems()
            player.stop()
            player.removeListener(playbackStateListener)
            player.release()
            release()
            mediaSession = null
        }
        subtitleCacheDir.deleteFiles()
        mkvCueParseJobs.clear()
        mediaParserRetried.clear()
        softwareDecoderRetried.clear()
        serviceScope.cancel()
    }

    private fun prepareCachedPreciseSeekMediaItems(mediaItems: List<MediaItem>) {
        mediaItems.forEach { mediaItem ->
            if (!mediaItem.mediaMetadata.isApproximateSeekEnabled) return@forEach
            restoreCachedMkvSeekMap(mediaItem)?.let { seekMap ->
                mkvSeekMapCache[mediaItem.mediaId] = seekMap
                preciseSeekMediaIds.add(mediaItem.mediaId)
            }
        }
    }

    private suspend fun updatedMediaItemsWithMetadata(
        mediaItems: List<MediaItem>,
    ): List<MediaItem> = supervisorScope {
        mediaItems.map { mediaItem ->
            async {
                val uri = mediaItem.mediaId.toUri()
                val playbackStateUri = mediaItem.resolvePlaybackStateUri()
                val primaryVideoState = mediaRepository.getVideoState(uri = playbackStateUri)
                val video = mediaRepository.getVideoByUri(uri = playbackStateUri)

                val validExternalSubs = (primaryVideoState?.externalSubs ?: emptyList()).filter { subUri ->
                    if (subUri.scheme in DIRECT_SUBTITLE_URI_SCHEMES) return@filter true
                    try {
                        contentResolver.openInputStream(subUri)?.close()
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                validExternalSubs.forEach(onlineSubtitleRepository::touchSubtitle)
                val existingSubConfigurations = mediaItem.localConfiguration?.subtitleConfigurations ?: emptyList()
                val restoredSubConfigurations = validExternalSubs.map { subtitleUri ->
                    uriToSubtitleConfiguration(
                        uri = subtitleUri,
                        subtitleEncoding = playerPreferences.subtitleTextEncoding,
                    )
                }
                val mergedSubConfigurations = mergeSubtitleConfigurations(
                    existing = existingSubConfigurations,
                    incoming = restoredSubConfigurations,
                )

                val title = mediaItem.mediaMetadata.title ?: video?.nameWithExtension ?: getFilenameFromUri(uri)
                val positionMs = mediaItem.mediaMetadata.positionMs ?: primaryVideoState?.position
                val durationMs = mediaItem.mediaMetadata.durationMs
                    ?: video?.duration?.takeIf { it > 0L }
                    ?: extractDurationMs(uri)
                val videoScale = mediaItem.mediaMetadata.videoZoom ?: primaryVideoState?.videoScale
                val audioTrackIndex = mediaItem.mediaMetadata.audioTrackIndex ?: primaryVideoState?.audioTrackIndex
                val subtitleTrackIndex = mediaItem.mediaMetadata.subtitleTrackIndex ?: primaryVideoState?.subtitleTrackIndex
                val subtitleDelay = mediaItem.mediaMetadata.subtitleDelayMilliseconds ?: primaryVideoState?.subtitleDelayMilliseconds
                val subtitleSpeed = mediaItem.mediaMetadata.subtitleSpeed ?: primaryVideoState?.subtitleSpeed
                
                val mediaPath = video?.path ?: primaryVideoState?.path ?: getPath(uri) ?: uri.path
                val isLocalUri = uri.scheme == ContentResolver.SCHEME_FILE || uri.scheme == ContentResolver.SCHEME_CONTENT
                val isApproximateSeekEnabled = isLocalUri && mediaPath?.endsWith(".mkv", ignoreCase = true) == true

                mediaItem.buildUpon().apply {
                    setSubtitleConfigurations(mergedSubConfigurations)
                    setMediaMetadata(
                        MediaMetadata.Builder().apply {
                            setTitle(title)
                            setDurationMs(durationMs)
                            setMetadataExtras(
                                positionMs = positionMs,
                                videoScale = videoScale,
                                audioTrackIndex = audioTrackIndex,
                                subtitleTrackIndex = subtitleTrackIndex,
                                subtitleDelayMilliseconds = subtitleDelay,
                                subtitleSpeed = subtitleSpeed,
                                isApproximateSeekEnabled = isApproximateSeekEnabled,
                                isVideoEffectsAvailable = shouldApplyVideoEffects(activeDecoderPriority),
                                requestHeaders = mediaItem.mediaMetadata.requestHeaders,
                                remoteServerId = mediaItem.mediaMetadata.remoteServerId,
                                remoteFilePath = mediaItem.mediaMetadata.remoteFilePath,
                                remoteProtocol = mediaItem.mediaMetadata.remoteProtocol,
                            )
                        }.build(),
                    )
                }.build()
            }
        }.awaitAll()
    }

    private fun extractDurationMs(uri: Uri): Long? {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(applicationContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            retriever?.release()
        }
    }

    private fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val requestHeaders = mediaItem.mediaMetadata.requestHeaders
        val uri = mediaItem.localConfiguration?.uri
        val dataSourceFactory = createDataSourceFactory(uri, requestHeaders)
        val cachedSeekMap = mkvSeekMapCache[mediaItem.mediaId]
        val shouldUseFastStart = mediaItem.mediaMetadata.isApproximateSeekEnabled && mediaItem.mediaId !in preciseSeekMediaIds
        if (cachedSeekMap != null && !shouldUseFastStart) {
            val factory = DefaultMediaSourceFactory(
                dataSourceFactory,
                createSeekMapInjectedExtractorsFactory(cachedSeekMap),
            ).setSubtitleParserFactory(assSubtitleParserFactory)
            sessionLoadErrorHandlingPolicy?.let(factory::setLoadErrorHandlingPolicy)
            sessionDrmSessionManagerProvider?.let(factory::setDrmSessionManagerProvider)
            return factory.createMediaSource(mediaItem)
        }

        val currentAssHandler = assHandler
        if (currentAssHandler != null) {
            val mediaSourceFactory = DefaultMediaSourceFactory(
                dataSourceFactory,
                createPlaybackExtractorsFactory(
                    assSubtitleParserFactory = assSubtitleParserFactory,
                    assHandler = currentAssHandler,
                    shouldUseFastStart = shouldUseFastStart,
                ),
            ).setSubtitleParserFactory(assSubtitleParserFactory)
            sessionLoadErrorHandlingPolicy?.let(mediaSourceFactory::setLoadErrorHandlingPolicy)
            sessionDrmSessionManagerProvider?.let(mediaSourceFactory::setDrmSessionManagerProvider)
            return mediaSourceFactory.createMediaSource(mediaItem)
        }

        return DefaultMediaSourceFactory(dataSourceFactory)
            .apply {
                sessionLoadErrorHandlingPolicy?.let(::setLoadErrorHandlingPolicy)
                sessionDrmSessionManagerProvider?.let(::setDrmSessionManagerProvider)
            }
            .setSubtitleParserFactory(assSubtitleParserFactory)
            .createMediaSource(mediaItem)
    }

    private fun createDataSourceFactory(
        uri: Uri?,
        requestHeaders: Map<String, String>,
    ): DataSource.Factory {
        if (uri?.scheme == "smb") {
            val username = requestHeaders["_smb_username"].orEmpty()
            val password = requestHeaders["_smb_password"].orEmpty()
            return SmbDataSource.Factory(username, password)
        }
        if (uri?.scheme == "ftp") {
            val username = requestHeaders["_ftp_username"].orEmpty()
            val password = requestHeaders["_ftp_password"].orEmpty()
            return FtpDataSource.Factory(username, password)
        }

        val httpHeaders = requestHeaders.filterKeys { !it.startsWith("_") }
        if (httpHeaders.isEmpty()) {
            return DefaultDataSource.Factory(applicationContext)
        }

        val okHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val httpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(httpHeaders)
        return DefaultDataSource.Factory(applicationContext, httpFactory)
    }

    private suspend fun promoteCurrentItemToPreciseSeek(
        targetPositionMs: Long,
        requestId: Long = ++preciseSeekRequestId,
    ): SessionResult {
        val player = mediaSession?.player as? ExoPlayer ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        val initialItem = player.currentMediaItem ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        val maxPosition = initialItem.mediaMetadata.durationMs ?: player.duration.takeIf { it != C.TIME_UNSET }
        val targetPosition = maxPosition?.let { targetPositionMs.coerceIn(0L, it) } ?: targetPositionMs.coerceAtLeast(0L)

        if (!initialItem.mediaMetadata.isApproximateSeekEnabled || initialItem.mediaId in preciseSeekMediaIds) {
            player.seekTo(targetPosition)
            return SessionResult(SessionResult.RESULT_SUCCESS)
        }

        val seekMap = mkvSeekMapCache[initialItem.mediaId]
            ?: withContext(Dispatchers.IO) { scheduleMkvCueCache(initialItem).await() }
        if (requestId != preciseSeekRequestId) {
            return SessionResult(SessionError.ERROR_BAD_VALUE)
        }

        val currentItem = player.currentMediaItem ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        val currentIndex = player.currentMediaItemIndex
        if (currentItem.mediaId != initialItem.mediaId) {
            return SessionResult(SessionError.ERROR_BAD_VALUE)
        }
        if (seekMap == null) {
            player.seekTo(targetPosition)
            return SessionResult(SessionResult.RESULT_SUCCESS)
        }
        mkvSeekMapCache[currentItem.mediaId] = seekMap

        val updatedMediaItem = currentItem.copy(
            positionMs = targetPosition,
            isApproximateSeekEnabled = false,
        )
        preciseSeekMediaIds.add(currentItem.mediaId)
        val shouldPlayWhenReady = player.playWhenReady
        player.addMediaSource(currentIndex + 1, createMediaSource(updatedMediaItem))
        player.seekTo(currentIndex + 1, targetPosition)
        player.removeMediaItem(currentIndex)
        player.prepare()
        player.playWhenReady = shouldPlayWhenReady
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private suspend fun MediaItem.resolvePlaybackStateUri(): String = mediaRepository.getCanonicalMediaUri(
        uri = buildRemotePlaybackStateKey(
            remoteProtocol = mediaMetadata.remoteProtocol,
            remoteServerId = mediaMetadata.remoteServerId,
            remoteFilePath = mediaMetadata.remoteFilePath,
        ) ?: mediaId,
    )

    private suspend fun requestSeekForCurrentItem(targetPositionMs: Long): SessionResult {
        val player = mediaSession?.player as? ExoPlayer ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        val currentItem = player.currentMediaItem ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        val maxPosition = currentItem.mediaMetadata.durationMs ?: player.duration.takeIf { it != C.TIME_UNSET }
        val targetPosition = maxPosition?.let { targetPositionMs.coerceIn(0L, it) } ?: targetPositionMs.coerceAtLeast(0L)

        if (!currentItem.mediaMetadata.isApproximateSeekEnabled) {
            player.seekTo(targetPosition)
            return SessionResult(SessionResult.RESULT_SUCCESS)
        }

        val startPosition = player.currentPosition.takeIf { it != C.TIME_UNSET } ?: 0L
        val startDelta = kotlin.math.abs(targetPosition - startPosition)
        if (startDelta < FAST_SEEK_MIN_DELTA_MS) {
            return SessionResult(SessionResult.RESULT_SUCCESS)
        }

        return promoteCurrentItemToPreciseSeek(targetPosition)
    }

    private fun scheduleMkvCueCache(mediaItem: MediaItem): Deferred<androidx.media3.extractor.SeekMap?> {
        val mediaId = mediaItem.mediaId
        mkvSeekMapCache[mediaId]?.let { return CompletableDeferred(it) }
        mkvCueParseJobs[mediaId]?.let { return it }

        val restoredSeekMap = restoreCachedMkvSeekMap(mediaItem)
        if (restoredSeekMap != null) {
            mkvSeekMapCache[mediaId] = restoredSeekMap
            return CompletableDeferred(restoredSeekMap)
        }

        val durationMs = mediaItem.mediaMetadata.durationMs ?: return CompletableDeferred(null)
        val uri = Uri.parse(mediaId)

        val parseJob = serviceScope.async(Dispatchers.IO) {
            val cuePoints = MkvCuesParser.parse(applicationContext, uri)
            if (cuePoints == null) return@async null

            val durationUs = durationMs * 1_000L
            val seekMap = buildSeekMapFromCues(cuePoints, durationUs)
            mkvSeekMapCache[mediaId] = seekMap
            persistMkvSeekMap(uri, cuePoints, durationUs)
            seekMap
        }
        parseJob.invokeOnCompletion {
            mkvCueParseJobs.remove(mediaId, parseJob)
        }
        mkvCueParseJobs[mediaId] = parseJob
        return parseJob
    }

    private fun continueDeferredStartupPreciseResume(currentMediaItem: MediaItem) {
        val player = mediaSession?.player ?: return
        val mediaId = currentMediaItem.mediaId
        if (pendingStartupPreciseResumeToken != mediaId) return

        val targetPosition = pendingStartupPreciseResumePositionMs ?: currentMediaItem.mediaMetadata.positionMs ?: return
        if (targetPosition < STARTUP_PRECISE_RESUME_THRESHOLD_MS) return
        if (player.currentPosition >= targetPosition - 1_000L) return

        pendingPreciseSeekPromotionJob?.cancel()
        pendingPreciseSeekPromotionJob = serviceScope.launch(Dispatchers.IO) {
            val seekMap = mkvSeekMapCache[mediaId]
                ?: restoreCachedMkvSeekMap(currentMediaItem)
                ?: scheduleMkvCueCache(currentMediaItem).await()
                ?: return@launch

            mkvSeekMapCache[mediaId] = seekMap
            withContext(Dispatchers.Main) {
                val currentPlayer = mediaSession?.player ?: return@withContext
                val current = currentPlayer.currentMediaItem ?: return@withContext
                if (current.mediaId != mediaId) return@withContext
                pendingStartupPreciseResumeToken = null
                pendingStartupPreciseResumePositionMs = null
                promoteCurrentItemToPreciseSeek(targetPosition)
            }
        }
    }

    private fun mkvCueCacheFile(uri: Uri): File? {
        val path = runCatching {
            when (uri.scheme) {
                ContentResolver.SCHEME_FILE -> uri.toFile().absolutePath
                ContentResolver.SCHEME_CONTENT -> getPath(uri)
                else -> null
            }
        }.getOrNull() ?: return null
        val sourceFile = File(path)
        if (!sourceFile.exists()) return null
        val cacheKey = sourceFile.absolutePath.hashCode().toUInt().toString(16)
        val cacheDir = File(cacheDir, "mkv-cues")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return File(cacheDir, "mkv-cues-$cacheKey.bin")
    }

    private fun persistMkvSeekMap(uri: Uri, cuePoints: List<app.gyrolet.mpvrx.exoplayer.feature.player.engine.media3.MkvCuePoint>, durationUs: Long) {
        val sourceFile = resolveLocalFile(uri) ?: return
        val cacheFile = mkvCueCacheFile(uri) ?: return
        runCatching {
            DataOutputStream(cacheFile.outputStream().buffered()).use { output ->
                output.writeInt(MKV_CUES_CACHE_MAGIC)
                output.writeLong(sourceFile.length())
                output.writeLong(sourceFile.lastModified())
                output.writeLong(durationUs)
                output.writeInt(cuePoints.size)
                cuePoints.forEach { cuePoint ->
                    output.writeLong(cuePoint.timeUs)
                    output.writeLong(cuePoint.clusterPosition)
                }
            }
        }
    }

    private fun restoreCachedMkvSeekMap(mediaItem: MediaItem): androidx.media3.extractor.SeekMap? {
        val uri = Uri.parse(mediaItem.mediaId)
        val sourceFile = resolveLocalFile(uri) ?: return null
        val cacheFile = mkvCueCacheFile(uri) ?: return null
        if (!cacheFile.exists()) return null

        return runCatching {
            DataInputStream(cacheFile.inputStream().buffered()).use { input ->
                val magic = input.readInt()
                if (magic != MKV_CUES_CACHE_MAGIC) return@runCatching null
                val fileSize = input.readLong()
                val lastModified = input.readLong()
                if (fileSize != sourceFile.length() || lastModified != sourceFile.lastModified()) {
                    return@runCatching null
                }
                val durationUs = input.readLong()
                val count = input.readInt()
                val cuePoints = List(count) {
                    app.gyrolet.mpvrx.exoplayer.feature.player.engine.media3.MkvCuePoint(
                        timeUs = input.readLong(),
                        clusterPosition = input.readLong(),
                    )
                }
                buildSeekMapFromCues(cuePoints, durationUs)
            }
        }.getOrNull()
    }

    private fun resolveLocalFile(uri: Uri): File? = when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> runCatching { uri.toFile() }.getOrNull()
        ContentResolver.SCHEME_CONTENT -> getPath(uri)?.let(::File)
        else -> null
    }?.takeIf(File::exists)

    private fun createSeekMapInjectedExtractorsFactory(
        seekMap: androidx.media3.extractor.SeekMap,
    ): ExtractorsFactory = ExtractorsFactory {
        val baseFactory = DefaultExtractorsFactory()
        val extractors = baseFactory.createExtractors()
        for (i in extractors.indices) {
            if (extractors[i] is MatroskaExtractor) {
                val assExtractor = NormalizingAssMatroskaExtractor(assSubtitleParserFactory, assHandler!!)
                disableSeekForCues(assExtractor)
                extractors[i] = SeekMapInjectingExtractor(assExtractor, seekMap)
            }
        }
        extractors
    }

    private suspend fun loadArtworkForUri(uri: Uri): ByteArray? = try {
        val result = imageLoader.execute(
            ImageRequest.Builder(this@ExoPlayerService)
                .data(uri)
                .build(),
        )
        (result as? SuccessResult)?.image?.toBitmap()?.toByteArray()
    } catch (_: Exception) {
        null
    }

    private suspend fun findMediaItemInSession(mediaId: String): Triple<Player, Int, MediaItem>? = withContext(
        Dispatchers.Main.immediate,
    ) {
        val player = mediaSession?.player ?: return@withContext null
        val index = (0 until player.mediaItemCount).firstOrNull {
            player.getMediaItemAt(it).mediaId == mediaId
        } ?: return@withContext null
        Triple(player, index, player.getMediaItemAt(index))
    }

    private fun loadArtworkInBackground(mediaItems: List<MediaItem>) {
        serviceScope.launch(Dispatchers.Default) {
            mediaItems.forEach { mediaItem ->
                launch {
                    val artworkData = loadArtworkForUri(mediaItem.mediaId.toUri()) ?: return@launch

                    withContext(Dispatchers.Main) {
                        val (player, index, currentMediaItem) = findMediaItemInSession(mediaItem.mediaId) ?: return@withContext
                        val updatedMediaItem = currentMediaItem.buildUpon()
                            .setMediaMetadata(
                                currentMediaItem.mediaMetadata.buildUpon()
                                    .setArtworkUri(null)
                                    .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                    .build(),
                            )
                            .build()
                        player.replaceMediaItem(index, updatedMediaItem)
                    }
                }
            }
        }
    }

    private fun loadExternalSubtitlesForCurrentItem(
        mediaId: String,
        requestHeaders: Map<String, String>,
    ) {
        serviceScope.launch(Dispatchers.IO) {
            val configurations = buildExternalSubtitleConfigurations(mediaId, requestHeaders)
            if (configurations.isEmpty()) return@launch

            withContext(Dispatchers.Main) {
                val (player, index, currentMediaItem) = findMediaItemInSession(mediaId) ?: return@withContext
                val existingConfigs = currentMediaItem.localConfiguration?.subtitleConfigurations ?: emptyList()
                val mergedConfigs = mergeSubtitleConfigurations(existingConfigs, configurations)
                if (mergedConfigs.size == existingConfigs.size) return@withContext

                val updatedMediaItem = currentMediaItem.buildUpon()
                    .setSubtitleConfigurations(mergedConfigs)
                    .build()
                val currentPosition = player.currentPosition
                val shouldPlayWhenReady = player.playWhenReady
                isPendingExternalSubAutoSelect = true
                player.addMediaItem(index + 1, updatedMediaItem)
                player.seekTo(index + 1, currentPosition)
                player.playWhenReady = shouldPlayWhenReady
                player.removeMediaItem(index)
            }
        }
    }

    private suspend fun buildExternalSubtitleConfigurations(
        mediaId: String,
        requestHeaders: Map<String, String>,
    ): List<MediaItem.SubtitleConfiguration> {
        val uri = mediaId.toUri()
        val playbackStateUri = mediaRepository.getCanonicalMediaUri(uri = mediaId)
        val video = mediaRepository.getVideoByUri(uri = playbackStateUri)
        val primaryVideoState = mediaRepository.getVideoState(uri = playbackStateUri)
        val dbExternalSubs = primaryVideoState?.externalSubs ?: emptyList()

        val localSubs = (video?.path ?: getPath(uri))?.let {
            File(it).getLocalSubtitles(
                context = this@ExoPlayerService,
                excludeSubsList = dbExternalSubs,
            )
        } ?: emptyList()

        val allExternalSubs = dbExternalSubs + localSubs
        if (allExternalSubs.isEmpty()) return emptyList()

        return allExternalSubs.map { subtitleUri ->
            uriToSubtitleConfiguration(
                uri = subtitleUri,
                subtitleEncoding = playerPreferences.subtitleTextEncoding,
            )
        }
    }

    private fun mergeSubtitleConfigurations(
        existing: List<MediaItem.SubtitleConfiguration>,
        incoming: List<MediaItem.SubtitleConfiguration>,
    ): List<MediaItem.SubtitleConfiguration> {
        val mergedById = LinkedHashMap<String, MediaItem.SubtitleConfiguration>()
        existing.forEach { subtitleConfiguration ->
            mergedById[subtitleConfiguration.id ?: subtitleConfiguration.uri.toString()] = subtitleConfiguration
        }
        incoming.forEach { subtitleConfiguration ->
            mergedById[subtitleConfiguration.id ?: subtitleConfiguration.uri.toString()] = subtitleConfiguration
        }
        return mergedById.values.toList()
    }

    private fun findBestSubtitleTrackIndex(textTracks: List<Tracks.Group>): Int {
        val preferred = playerPreferences.preferredSubtitleLanguage
        if (preferred.isBlank()) return 0

        val normalizedPref = normalizeLanguageTag(preferred)
        for (i in textTracks.indices) {
            val format = textTracks[i].getTrackFormat(0)
            if (matchesPreferredLanguage(format, normalizedPref)) return i
        }
        return 0
    }

    private fun matchesPreferredLanguage(format: Format, preferred: String): Boolean {
        val trackLang = format.language?.let(::normalizeLanguageTag) ?: return false

        if (preferred.startsWith("zh-") && (trackLang == "zh" || trackLang.startsWith("zh-"))) {
            return matchesChineseVariantByLabel(format.label, preferred)
        }

        return trackLang.startsWith(preferred) || preferred.startsWith(trackLang)
    }

    private fun matchesChineseVariantByLabel(label: String?, preferred: String): Boolean {
        if (label == null) return preferred == "zh"
        val lower = label.lowercase()
        val isSimplified = preferred.contains("hans") || preferred.contains("cn")
        val isTraditional = preferred.contains("hant") || preferred.contains("tw") || preferred.contains("hk")

        return when {
            isSimplified -> lower.containsAny("简", "chs", "simplified")
            isTraditional -> lower.containsAny("繁", "cht", "traditional")
            else -> true
        }
    }

    private fun normalizeLanguageTag(tag: String): String {
        val lower = tag.lowercase().replace('_', '-')
        return ISO_639_2T_TO_1[lower] ?: ISO_639_2T_TO_1[lower.substringBefore('-')]?.let {
            it + lower.removePrefix(lower.substringBefore('-'))
        } ?: lower
    }

    private fun String.containsAny(vararg keywords: String): Boolean = keywords.any { contains(it, ignoreCase = true) }

    private fun Bitmap.toByteArray(): ByteArray {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 100, stream)
        return stream.toByteArray()
    }
}

private fun readFully(stream: InputStream, buffer: ByteArray): Boolean {
    var pos = 0
    while (pos < buffer.size) {
        val read = stream.read(buffer, pos, buffer.size - pos)
        if (read < 0) return false
        pos += read
    }
    return true
}

@get:UnstableApi
@set:UnstableApi
private var Player.isSkipSilenceEnabledForPlayer: Boolean
    get() = when (this) {
        is ExoPlayer -> this.skipSilenceEnabled
        else -> false
    }
    set(value) {
        when (this) {
            is ExoPlayer -> this.skipSilenceEnabled = value
        }
    }

@get:UnstableApi
@set:UnstableApi
private var Player.playerSpecificSubtitleDelayMilliseconds: Long
    get() = when (this) {
        is ExoPlayer -> this.subtitleDelayMilliseconds
        else -> 0L
    }
    set(value) {
        when (this) {
            is ExoPlayer -> this.subtitleDelayMilliseconds = value
        }
    }

@get:UnstableApi
@set:UnstableApi
private var Player.playerSpecificSubtitleSpeed: Float
    get() = when (this) {
        is ExoPlayer -> this.subtitleSpeed
        else -> 0f
    }
    set(value) {
        when (this) {
            is ExoPlayer -> this.subtitleSpeed = value
        }
    }
