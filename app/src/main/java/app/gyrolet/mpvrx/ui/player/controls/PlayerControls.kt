/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls

import app.gyrolet.mpvrx.ui.player.controls.components.WindowedSeekPreview
import app.gyrolet.mpvrx.ui.player.DeclaredPlaybackMediaKind
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.declaredMediaKind
import app.gyrolet.mpvrx.domain.torrent.TorrentStreamingState
import app.gyrolet.mpvrx.domain.torrent.formatTorrentBytes
import app.gyrolet.mpvrx.domain.torrent.formatTorrentSpeed
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.Debug
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.areNavigationBarsVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.constraintlayout.compose.ConstrainScope
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.components.SwipeDismissibleToast
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.AiPreferences
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.PlayerButton
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.allPlayerButtons
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.preferences.preference.deleteAndGet
import app.gyrolet.mpvrx.preferences.preference.minusAssign
import app.gyrolet.mpvrx.preferences.preference.plusAssign
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.Decoder.Companion.getDecoderFromValue
import app.gyrolet.mpvrx.ui.player.InAppMusicSheetState
import app.gyrolet.mpvrx.ui.player.Panels
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.ui.player.PlayerUpdates
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.MPVView
import app.gyrolet.mpvrx.ui.player.Sheets
import app.gyrolet.mpvrx.ui.player.VideoOpenAnimationOverlay
import app.gyrolet.mpvrx.ui.player.components.VideoGlassBackdropSource
import app.gyrolet.mpvrx.ui.player.components.rememberVideoGlassSource
import app.gyrolet.mpvrx.ui.player.buildControlsEnterH
import app.gyrolet.mpvrx.ui.player.buildControlsEnterV
import app.gyrolet.mpvrx.ui.player.buildControlsExitH
import app.gyrolet.mpvrx.ui.player.buildControlsExitV
import app.gyrolet.mpvrx.ui.player.controls.components.AnimatedPlayPauseIcon
import app.gyrolet.mpvrx.ui.player.controls.components.BrightnessSlider
import app.gyrolet.mpvrx.ui.player.controls.components.ControlsButton
import app.gyrolet.mpvrx.ui.player.controls.components.LocalForceDarkPlayerButtonsBackground
import app.gyrolet.mpvrx.ui.player.controls.components.LocalPlayerButtonsOpacity
import app.gyrolet.mpvrx.ui.player.controls.components.asPlayerButtonBackground
import app.gyrolet.mpvrx.ui.player.controls.components.MediaScopesOverlay
import app.gyrolet.mpvrx.ui.player.controls.components.fadingHorizontalScroll
import app.gyrolet.mpvrx.ui.player.controls.components.MultipleSpeedPlayerUpdate
import app.gyrolet.mpvrx.ui.player.controls.components.ResumeAvailablePlayerUpdate
import app.gyrolet.mpvrx.ui.player.controls.components.ResumedFromPlayerUpdate
import app.gyrolet.mpvrx.ui.player.controls.components.SeekPlayerUpdate
import app.gyrolet.mpvrx.ui.player.controls.components.SeekbarWithTimers
import app.gyrolet.mpvrx.ui.player.controls.components.TextPlayerUpdate
import app.gyrolet.mpvrx.ui.player.controls.components.VolumeSlider
import app.gyrolet.mpvrx.ui.player.controls.components.playerButtonContentColor
import app.gyrolet.mpvrx.ui.player.controls.components.rememberBufferingState
import app.gyrolet.mpvrx.ui.player.controls.components.BufferingPhase
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.toFixed
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor
import app.gyrolet.mpvrx.ui.theme.playerRippleConfiguration
import app.gyrolet.mpvrx.ui.theme.spacing
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import app.gyrolet.mpvrx.ui.liquidglass.AdaptiveControlsContainer
import app.gyrolet.mpvrx.ui.liquidglass.LiquidPillButton
import app.gyrolet.mpvrx.ui.liquidglass.LocalKyantPlayerBackdrop
import app.gyrolet.mpvrx.ui.liquidglass.LocalPlayerHdrOutputEnabled
import app.gyrolet.mpvrx.ui.liquidglass.LocalPlayerLiquidGlassActive
import app.gyrolet.mpvrx.ui.player.shouldUseTextureRendering
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.abs
import kotlin.math.roundToInt

@Suppress("CompositionLocalAllowlist")
val LocalPlayerButtonsClickEvent = staticCompositionLocalOf { {} }

fun <T> playerControlsExitAnimationSpec(durationMillis: Int = 300): FiniteAnimationSpec<T> =
  tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)

fun <T> playerControlsEnterAnimationSpec(durationMillis: Int = 100): FiniteAnimationSpec<T> =
  tween(durationMillis = durationMillis, easing = LinearOutSlowInEasing)

/**
 * The full music player with its sheets and panels. PlayerActivity shows it for audio, and the
 * browser's in-app sheet ([inAppSheet]) shows the same screen live while the service keeps playing.
 */
@Composable
@Suppress("ViewModelForwarding")
internal fun AudioPlayerScreen(
  viewModel: PlayerViewModel,
  onBackPress: () -> Unit,
  modifier: Modifier = Modifier,
  titleForControls: () -> String? = { null },
  inAppSheet: InAppMusicSheetState? = null,
) {
  val playerPreferences = koinInject<PlayerPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val enableLiquidGlass by appearancePreferences.liquidGlassEnabled.collectAsState()
  val playbackQueue by PlaybackSession.queue.collectAsStateWithLifecycle()
  val currentPlaybackItem = playbackQueue.currentItem
  val chapters by viewModel.playbackChapters.collectAsStateWithLifecycle(persistentListOf())
  val playbackSpeed by PlaybackSession.propFloat["speed"].collectAsStateWithLifecycle()
  val configuredDecoder by PlaybackSession.propString["hwdec"].collectAsStateWithLifecycle()
  val activeDecoder by PlaybackSession.propString["hwdec-current"].collectAsStateWithLifecycle()
  val decoder = remember(activeDecoder, configuredDecoder) {
    getDecoderFromValue(activeDecoder?.takeIf { it.isNotBlank() } ?: configuredDecoder ?: "auto")
  }
  val onOpenSheet: (Sheets) -> Unit = remember(viewModel) {
    {
      viewModel.sheetShown.update { _ -> it }
      if (it == Sheets.None) {
        viewModel.showControls()
      } else {
        viewModel.hideControls()
        viewModel.panelShown.update { Panels.None }
      }
    }
  }
  val onOpenPanel: (Panels) -> Unit = remember(viewModel) {
    {
      viewModel.panelShown.update { _ -> it }
      if (it == Panels.None) {
        viewModel.showControls()
      } else {
        viewModel.hideControls()
        viewModel.sheetShown.update { Sheets.None }
      }
    }
  }

  val audioBook by app.gyrolet.mpvrx.ui.player.AudiobookPlayback.book.collectAsState()
  val audioPositionState = viewModel.precisePosition.collectAsStateWithLifecycle()
  // Only the chapter index is needed here. Reading the position clock directly re-ran this whole
  // screen every polling tick; a derived index recomposes only when the chapter changes.
  val audioChapterIndex by remember(chapters, currentPlaybackItem, audioBook) {
    derivedStateOf {
      val audioPosition = audioPositionState.value
      val audioTimelinePosition = currentPlaybackItem?.audiobook?.let { info ->
        audioBook?.takeIf { it.book.id == info.bookId }?.positionInBook(info.trackId, (audioPosition * 1000).toLong())?.div(1000f)
      } ?: audioPosition
      chapters.indexOfLast {
        it.start <= audioTimelinePosition
      }
    }
  }
  val rawMediaTitle by PlaybackSession.propString["media-title"].collectAsStateWithLifecycle()
  val queuedTitle =
    playbackQueue.currentItem?.title?.takeIf { playbackQueue.isExplicitQueue && it.isNotBlank() }
  val currentTitleForControls by rememberUpdatedState(titleForControls)
  val isYouTubeSource = app.gyrolet.mpvrx.utils.media.HttpUtils.isYouTubeUrl(currentPlaybackItem?.originalUri)
  val mediaTitle =
    remember(queuedTitle, rawMediaTitle, currentPlaybackItem?.title, isYouTubeSource) {
      sequenceOf(queuedTitle, currentTitleForControls(), rawMediaTitle, currentPlaybackItem?.title)
        .filterNotNull()
        .firstOrNull { it.isNotBlank() && (!isYouTubeSource || !app.gyrolet.mpvrx.utils.media.HttpUtils.isLikelyJunkTitle(it)) }
    }

  val sheetShown by viewModel.sheetShown.collectAsState()
  val panel by viewModel.panelShown.collectAsState()
  val subtitles by viewModel.subtitleTracks.collectAsState(persistentListOf())
  val audioTracks by viewModel.audioTracks.collectAsState(persistentListOf())
  val speedPresets by playerPreferences.speedPresets.collectAsState()
  val sortedSpeedPresets = remember(speedPresets) { speedPresets.map { it.toFloat() }.sorted() }

  if (inAppSheet != null) {
    // PlayerActivity closes sheets and panels from its own back handling; the browser does not.
    BackHandler(enabled = sheetShown != Sheets.None || panel != Panels.None) {
      if (panel != Panels.None) onOpenPanel(Panels.None) else onOpenSheet(Sheets.None)
    }
  }

  // Only sheets and panels sample this layer; the controls would otherwise sample themselves.
  val audioBackdrop = rememberLayerBackdrop()
  Box(modifier = modifier.fillMaxSize()) {
    AudioPlayerControls(
      viewModel = viewModel,
      mediaTitle = mediaTitle,
      onBackPress = onBackPress,
      onOpenSheet = onOpenSheet,
      onOpenPanel = onOpenPanel,
      modifier = if (enableLiquidGlass) Modifier.layerBackdrop(audioBackdrop) else Modifier,
      sheetBackdrop = audioBackdrop.takeIf { enableLiquidGlass },
      inAppSheet = inAppSheet,
    )

    CompositionLocalProvider(
      LocalKyantPlayerBackdrop provides audioBackdrop.takeIf { enableLiquidGlass },
    ) {
      PlayerSheets(
        viewModel = viewModel,
        sheetShown = sheetShown,
        subtitles = subtitles.toImmutableList(),
        onAddSubtitle = viewModel::addSubtitle,
        onToggleSubtitle = viewModel::toggleSubtitle,
        isSubtitleSelected = viewModel::isSubtitleSelected,
        subtitleSelectionIndicator = viewModel::subtitleSelectionIndicator,
        onRemoveSubtitle = viewModel::removeSubtitle,
        audioTracks = audioTracks.toImmutableList(),
        onAddAudio = viewModel::addAudio,
        onSelectAudio = viewModel::selectAudioTrack,
        chapter = chapters.getOrNull(audioChapterIndex),
        chapters = chapters.toImmutableList(),
        onSeekToChapter = {
          chapters.getOrNull(it)?.let(viewModel::seekToPlaybackChapter)
        },
        decoder = decoder,
        onUpdateDecoder = { PlaybackSession.setPropertyString("hwdec", it.value) },
        speed = playbackSpeed ?: playerPreferences.defaultSpeed.get(),
        onSpeedChange = {
          if (currentPlaybackItem?.audiobook != null) app.gyrolet.mpvrx.ui.player.AudiobookPlayback.setSpeed(it.toFixed(2))
          else PlaybackSession.setPropertyFloat("speed", it.toFixed(2))
        },
        onMakeDefaultSpeed = { playerPreferences.defaultSpeed.set(it.toFixed(2)) },
        onAddSpeedPreset = { playerPreferences.speedPresets += it.toFixed(2).toString() },
        onRemoveSpeedPreset = { playerPreferences.speedPresets -= it.toFixed(2).toString() },
        onResetSpeedPresets = playerPreferences.speedPresets::delete,
        speedPresets = sortedSpeedPresets,
        onResetDefaultSpeed = {
          val speed = playerPreferences.defaultSpeed.deleteAndGet().toFixed(2)
          if (currentPlaybackItem?.audiobook != null) app.gyrolet.mpvrx.ui.player.AudiobookPlayback.setSpeed(speed)
          else PlaybackSession.setPropertyFloat("speed", speed)
        },
        onStartSleepTimer = viewModel::startTimer,
        onOpenPanel = onOpenPanel,
        onShowSheet = onOpenSheet,
        onDismissRequest = { onOpenSheet(Sheets.None) },
      )

      PlayerPanels(
        panelShown = panel,
        viewModel = viewModel,
        onDismissRequest = { onOpenPanel(Panels.None) },
      )
    }
  }
}

@OptIn(
  ExperimentalMaterial3Api::class,
  ExperimentalMaterial3ExpressiveApi::class,
  ExperimentalFoundationApi::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
@Suppress("CyclomaticComplexMethod", "ViewModelForwarding")
fun PlayerControls(
  viewModel: PlayerViewModel,
  onBackPress: () -> Unit,
  videoSurface: MPVView? = null,
  modifier: Modifier = Modifier,
) {
  val spacing = MaterialTheme.spacing
  val isTelevision = DeviceFormFactor.isTelevision(LocalContext.current)
  val advancedPreferences = koinInject<AdvancedPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val aiPreferences = koinInject<AiPreferences>()
  val aiEnabled by aiPreferences.enabled.collectAsState()
  val realtimeSubsEnabled by aiPreferences.realtimeSubsEnabled.collectAsState()
  val liquidGlassPreference by appearancePreferences.liquidGlassEnabled.collectAsState()
  val liquidGlassInHdr by appearancePreferences.liquidGlassInHdr.collectAsState()
  val hdrOutputPreference by koinInject<DecoderPreferences>().hdrScreenOutput.collectAsState()
  // Glass needs the video on the Compose-recordable renderer, so this mirrors the renderer choice.
  // When HDR output keeps the video on a native surface ("Liquid glass in HDR" off, or HDR owned by
  // mpv.conf), every player control uses its regular form, exactly as with Liquid Glass off.
  val enableLiquidGlass =
    shouldUseTextureRendering(
      liquidGlass = liquidGlassPreference,
      hdrOutput = hdrOutputPreference,
      liquidGlassInHdr = liquidGlassInHdr,
    )
  val forceDarkButtonBackground by appearancePreferences.forceDarkPlayerButtonsBackground.collectAsState()
  val portraitPlaybackControlsPosition by
    appearancePreferences.portraitPlaybackControlsPosition.collectAsState()
  val landscapePlaybackControlsPosition by
    appearancePreferences.landscapePlaybackControlsPosition.collectAsState()
  val portraitSeekbarPlacement by appearancePreferences.portraitSeekbarPlacement.collectAsState()
  val landscapeSeekbarPlacement by appearancePreferences.landscapeSeekbarPlacement.collectAsState()
  val playerButtonsBackgroundOpacity by appearancePreferences.playerButtonsBackgroundOpacity.collectAsState()
  val playerPreferences = koinInject<PlayerPreferences>()
  val gesturePreferences = koinInject<GesturePreferences>()
  val reduceMotion by playerPreferences.reduceMotion.collectAsState()
  val audioPreferences = koinInject<AudioPreferences>()
  val showSystemStatusBar by playerPreferences.showSystemStatusBar.collectAsState()
  // Insets follow the actual system UI, including bars revealed temporarily in immersive mode.
  val navigationBarRequested by playerPreferences.showSystemNavigationBar.collectAsState()
  val showSystemNavigationBar = navigationBarRequested || WindowInsets.areNavigationBarsVisible
  val showControlsDrawer by playerPreferences.showControlsDrawer.collectAsState()
  var seekRippleRequest by remember { mutableStateOf<SeekRippleRequest?>(null) }
  val controlsShown by viewModel.controlsShown.collectAsState()
  val windowedSeekActive by viewModel.isWindowedSeekPreviewActive.collectAsStateWithLifecycle()
  val controlsInteractionEpoch by viewModel.controlsInteractionEpoch.collectAsState()
  val tvPlayFocusRequester = rememberTvInitialFocusRequester(enabled = controlsShown)
  val overlaysFollowVideoZoom by playerPreferences.overlaysFollowVideoZoom.collectAsState()
  val videoPanX by viewModel.videoPanX.collectAsState()
  val videoPanY by viewModel.videoPanY.collectAsState()
  val statisticsPage by advancedPreferences.enabledStatisticsPage.collectAsState()
  val areControlsLocked by viewModel.areControlsLocked.collectAsState()
  val seekBarShown by viewModel.seekBarShown.collectAsState()
  val paused by PlaybackSession.propBoolean["pause"].collectAsStateWithLifecycle()
  val playbackSessionState by PlaybackSession.state.collectAsStateWithLifecycle()
  val duration by PlaybackSession.propInt["duration"].collectAsStateWithLifecycle()
  val playbackQueue by PlaybackSession.queue.collectAsStateWithLifecycle()
  val preciseDuration by viewModel.preciseDuration.collectAsState()
  val scopeAudioTracks by viewModel.audioTracks.collectAsState(persistentListOf())
  val mediaScopesState by viewModel.mediaScopesUiState.collectAsState()
  val currentQueueItem = playbackSessionState.currentItem ?: playbackQueue.currentItem
  val playbackSpeed by PlaybackSession.propFloat["speed"].collectAsStateWithLifecycle()
  val seekbarDuration =
    if (preciseDuration > 0) {
      preciseDuration
    } else if ((duration ?: 0) > 0) {
      duration!!.toFloat()
    } else {
      currentQueueItem?.durationSeconds?.takeIf { it > 0 }?.toFloat() ?: 0f
    }
  val seekState by viewModel.seekState.collectAsState()
  val brightness by viewModel.currentBrightness.collectAsState()
  val doubleTapSeekAmount = seekState.amount
  val showDoubleTapOvals by playerPreferences.showDoubleTapOvals.collectAsState()
  val showSeekTime by playerPreferences.showSeekTimeWhileSeeking.collectAsState()
  val showSeekbarDuringHorizontalSeeking by gesturePreferences.showSeekbarDuringHorizontalSeeking.collectAsState()
  val showBufferedRange by playerPreferences.showBufferedRange.collectAsState()
  val showChapterIndicators by playerPreferences.showChapterIndicators.collectAsState()
  // The buffering pill reads torrent details itself, so stats ticks do not re-run the controls tree.
  val torrentStateHolder = viewModel.torrentState.collectAsState()
  val videoOpenAnimState by viewModel.videoOpenAnimationState.collectAsState()
  val showLoadingCircle by playerPreferences.showLoadingCircle.collectAsState()

  var isSeeking by remember { mutableStateOf(false) }
  // Opening a network item (including link resolution before mpv starts) waits on the network too.
  val openingNetworkStream =
    videoOpenAnimState.isWaitingForVideo &&
      playbackSessionState.error == null &&
      playbackSessionState.phase != app.gyrolet.mpvrx.ui.player.PlaybackPhase.ERROR &&
      currentQueueItem?.let { item ->
        sequenceOf(item.playableUri, item.originalUri).any { uri ->
          app.gyrolet.mpvrx.utils.media.HttpUtils.isNetworkStream(android.net.Uri.parse(uri))
        }
      } == true
  val bufferingState =
    rememberBufferingState(
      enabled = showLoadingCircle && !isSeeking && !windowedSeekActive,
      sessionState = playbackSessionState,
      openingNetworkStream = openingNetworkStream,
    )
  val isMpvBuffering = bufferingState.isCacheStall
  val safeAreaWindow by playerPreferences.safeAreaWindow.collectAsState()
  val safeAreaInsetModifier =
    if (safeAreaWindow) {
      Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
    } else {
      Modifier
    }
  val navigationBarBottomInsetModifier =
    if (showSystemNavigationBar) {
      Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
    } else {
      Modifier
    }
  // Null until repeated seekbar updates start a scrub; a single tap never pauses playback.
  var seekbarScrubWasPaused by remember { mutableStateOf<Boolean?>(null) }
  var seekbarWindowedPreviewStarted by remember { mutableStateOf(false) }
  var seekbarInteractionCancelled by remember { mutableStateOf(false) }
  var seekbarInteractionGeneration by remember { mutableStateOf(playbackSessionState.generation) }
  val mpvSeeking by PlaybackSession.propBoolean["seeking"].collectAsStateWithLifecycle()
  val isPlayerSeeking = isSeeking || (mpvSeeking ?: false)
  // mpv can remain "seeking" until its cache refills. Only active scrubbing suppresses the spinner;
  // a real cache wait stays visible even when the controls have faded out.
  val showBufferingIndicator = bufferingState.visible
  var resetControlsTimestamp by remember { mutableStateOf(0L) }
  val configuredDecoder by PlaybackSession.propString["hwdec"].collectAsStateWithLifecycle()
  val activeDecoder by PlaybackSession.propString["hwdec-current"].collectAsStateWithLifecycle()
  val decoder = remember(activeDecoder, configuredDecoder) {
    getDecoderFromValue(activeDecoder?.takeIf { it.isNotBlank() } ?: configuredDecoder ?: "auto")
  }
  val isSpeedNonOne = remember(playbackSpeed) {
    abs((playbackSpeed ?: 1f) - 1f) > 0.001f
  }
  val playerTimeToDisappear by playerPreferences.playerTimeToDisappear.collectAsState()
  val chapters by viewModel.playbackChapters.collectAsStateWithLifecycle(persistentListOf())
  val chapterPositionState = viewModel.precisePosition.collectAsStateWithLifecycle()
  val currentChapter by remember(chapters) {
    androidx.compose.runtime.derivedStateOf { chapters.indexOfLast { it.start <= chapterPositionState.value }.takeIf { it >= 0 } }
  }
  val skipSegments by viewModel.skipSegments.collectAsState(persistentListOf())
  val currentSkippableSegment by viewModel.currentSkippableSegment.collectAsState()
  val showSkipChipAuto by viewModel.showSkipChipAuto.collectAsState()
  val playlistMode by playerPreferences.playlistMode.collectAsState()
  val playlistItems by viewModel.playlistItems.collectAsState()

  val customButtons by viewModel.customButtons.collectAsState()
  val showVideoQualitySelector by viewModel.showVideoQualitySelector.collectAsState()

  val abLoop by viewModel.abLoopState.collectAsState()
  val abLoopA = abLoop.a
  val abLoopB = abLoop.b
  val repeatMode by viewModel.repeatMode.collectAsState()
  val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()
  val transformState by viewModel.transformState.collectAsState()
  val isHdrOutputEnabled by viewModel.isHdrScreenOutputEnabled.collectAsState()
  val isAmbientEnabled by viewModel.isAmbientEnabled.collectAsState()
  val isPostProcessingEnabled by viewModel.isPostProcessingEnabled.collectAsState()
  val backgroundPlaybackEnabled by PlaybackSession.videoBackgroundPlaybackEnabled.collectAsState(
    initial = PlaybackSession.isVideoBackgroundPlaybackEnabled(),
  )

  val onOpenSheet: (Sheets) -> Unit = remember(viewModel) {
    {
      viewModel.sheetShown.update { _ -> it }
      if (it == Sheets.None) {
        viewModel.showControls()
      } else {
        viewModel.hideControls()
        viewModel.panelShown.update { Panels.None }
      }
    }
  }

  val onOpenPanel: (Panels) -> Unit = remember(viewModel) {
    {
      viewModel.panelShown.update { _ -> it }
      if (it == Panels.None) {
        viewModel.showControls()
      } else {
        viewModel.hideControls()
        viewModel.sheetShown.update { Sheets.None }
      }
    }
  }

  val isAudioOnly by viewModel.isAudioOnly.collectAsState()
  val activity = LocalActivity.current as? PlayerActivity
  val currentPlaybackItem = playbackQueue.currentItem
  val useAudioPlayer =
    when (currentPlaybackItem?.declaredMediaKind()) {
      null -> activity?.isCurrentMediaKnownAudio() == true
      DeclaredPlaybackMediaKind.VIDEO -> false
      DeclaredPlaybackMediaKind.AUDIO -> true
      DeclaredPlaybackMediaKind.UNKNOWN -> isAudioOnly || activity?.isCurrentMediaKnownAudio() == true
    }
  if (useAudioPlayer) {
    AudioPlayerScreen(
      viewModel = viewModel,
      onBackPress = onBackPress,
      modifier = modifier,
      titleForControls = { activity?.getTitleForControls() },
    )
    return
  }

  val playerActivity = LocalActivity.current as PlayerActivity
  val configuration = LocalConfiguration.current
  var playerBounds by remember { mutableStateOf(IntSize.Zero) }
  val isPortrait =
    remember(configuration.orientation, playerBounds) {
      if (playerBounds != IntSize.Zero) {
        playerBounds.width < playerBounds.height
      } else {
        configuration.orientation == ORIENTATION_PORTRAIT
      }
    }
  val aspect by viewModel.videoAspect.collectAsState()
  val currentZoom by viewModel.videoZoom.collectAsState()
  val rawMediaTitle by PlaybackSession.propString["media-title"].collectAsStateWithLifecycle()
  val queuedTitle =
    playbackQueue.currentItem?.title?.takeIf { playbackQueue.isExplicitQueue && it.isNotBlank() }
  val mediaTitle =
    remember(queuedTitle, rawMediaTitle, playerActivity) {
      queuedTitle
        ?: playerActivity.getTitleForControls().takeIf { it.isNotBlank() }
        ?: rawMediaTitle
    }

  val topRightControlsPref by appearancePreferences.topRightControls.collectAsState()
  val bottomRightControlsPref by appearancePreferences.bottomRightControls.collectAsState()
  val bottomLeftControlsPref by appearancePreferences.bottomLeftControls.collectAsState()
  val portraitBottomControlsPref by appearancePreferences.portraitBottomControls.collectAsState()

  val (configuredTopRightButtons, configuredBottomRightButtons, configuredBottomLeftButtons) =
    remember(
      topRightControlsPref,
      bottomRightControlsPref,
      bottomLeftControlsPref,
    ) {
      val usedButtons = mutableSetOf<app.gyrolet.mpvrx.preferences.PlayerButton>()
      val topR = appearancePreferences.parseButtons(topRightControlsPref, usedButtons)
      val bottomR = appearancePreferences.parseButtons(bottomRightControlsPref, usedButtons)
      val bottomL = appearancePreferences.parseButtons(bottomLeftControlsPref, usedButtons)
      listOf(topR, bottomR, bottomL)
    }

  val configuredPortraitBottomButtons =
    remember(portraitBottomControlsPref) {
      appearancePreferences.parseButtons(portraitBottomControlsPref, mutableSetOf())
    }
  val hasPlaylistSupport = viewModel.hasPlaylistSupport()
  fun visibleLayoutButtons(buttons: List<PlayerButton>, portrait: Boolean) = buttons.filter { button ->
    when (button) {
      PlayerButton.VIDEO_QUALITY -> showVideoQualitySelector
      PlayerButton.CURRENT_CHAPTER -> portrait || chapters.isNotEmpty()
      PlayerButton.PICTURE_IN_PICTURE -> !isAudioOnly
      PlayerButton.SHUFFLE -> hasPlaylistSupport
      else -> true
    }
  }
  val topRightButtons = visibleLayoutButtons(configuredTopRightButtons, false)
  val bottomRightButtons = visibleLayoutButtons(configuredBottomRightButtons, false)
  val bottomLeftButtons = visibleLayoutButtons(configuredBottomLeftButtons, false)
  val portraitBottomButtons = visibleLayoutButtons(configuredPortraitBottomButtons, true)
  val landscapeHasConfiguredQualityButton =
    remember(topRightButtons, bottomRightButtons, bottomLeftButtons) {
      PlayerButton.VIDEO_QUALITY in topRightButtons ||
        PlayerButton.VIDEO_QUALITY in bottomRightButtons ||
        PlayerButton.VIDEO_QUALITY in bottomLeftButtons
    }
  val portraitHasConfiguredQualityButton = PlayerButton.VIDEO_QUALITY in portraitBottomButtons
    val playerDrawerButtons =
    remember(
      showVideoQualitySelector,
      chapters,
      isAudioOnly,
      hasPlaylistSupport,
    ) {
      allPlayerButtons.filter { button ->
        when (button) {
          PlayerButton.PICTURE_IN_PICTURE -> !isAudioOnly
          PlayerButton.VIDEO_QUALITY -> showVideoQualitySelector
          PlayerButton.SHUFFLE -> hasPlaylistSupport
          else -> true
        }
      }
    }

  var showUnlockOnLeft by remember { mutableStateOf(true) }
  var isPlayerDrawerShown by remember { mutableStateOf(false) }
  LaunchedEffect(showControlsDrawer, areControlsLocked) {
    if (areControlsLocked || !showControlsDrawer) {
      val wasOpen = isPlayerDrawerShown
      isPlayerDrawerShown = false
      if (wasOpen && !areControlsLocked) viewModel.showControls()
    }
  }
  val setPlayerDrawerShown: (Boolean) -> Unit = { visible ->
    if (visible && showControlsDrawer && !areControlsLocked) {
      isPlayerDrawerShown = true
      viewModel.dismissAdjustmentOverlays()
      viewModel.hideControls()
    } else if (!visible && isPlayerDrawerShown) {
      isPlayerDrawerShown = false
      if (viewModel.sheetShown.value == Sheets.None && viewModel.panelShown.value == Panels.None) {
        viewModel.showControls()
      }
    }
  }
  val isBrightnessSliderShown by viewModel.isBrightnessSliderShown.collectAsState()
  val isVolumeSliderShown by viewModel.isVolumeSliderShown.collectAsState()
  val showVolumeGestureOverlay by playerPreferences.showVolumeGestureOverlay.collectAsState()
  val showBrightnessGestureOverlay by playerPreferences.showBrightnessGestureOverlay.collectAsState()
  val areSlidersShown =
    (isBrightnessSliderShown && showBrightnessGestureOverlay) ||
      (isVolumeSliderShown && showVolumeGestureOverlay)
  val gestureSeekUpdate by viewModel.playerUpdate.collectAsState()
  // Swipe-seek pauses playback internally; the auto-hide timer should still run through it.
  val isPlayingForAutoHide = paused == false || gestureSeekUpdate is PlayerUpdates.HorizontalSeek

  LaunchedEffect(
    controlsShown,
    controlsInteractionEpoch,
    isPlayingForAutoHide,
    isSeeking,
    windowedSeekActive,
    resetControlsTimestamp,
    areControlsLocked,
    isAudioOnly,
    isPlayerDrawerShown,
  ) {
    if (!isTelevision &&
      !isAudioOnly &&
      controlsShown &&
      isPlayingForAutoHide &&
      !isSeeking &&
      !windowedSeekActive &&
      !isPlayerDrawerShown
    ) {
      // Use 2 second delay when controls are locked, otherwise use user preference
      val delayTime = if (areControlsLocked) 2000L else playerTimeToDisappear.toLong()
      delay(delayTime)
      viewModel.hideControls()
    }
  }

  val videoOpenAnim by playerPreferences.videoOpenAnimation.collectAsState()
  val animSpeed by playerPreferences.animationSpeed.collectAsState()

  val transparentOverlay by animateFloatAsState(
    if (controlsShown && !areControlsLocked && !areSlidersShown) .8f else 0f,
    animationSpec =
      if (reduceMotion) {
        snap()
      } else if (controlsShown && !areControlsLocked && !areSlidersShown) {
        playerControlsEnterAnimationSpec((100 * animSpeed).toInt().coerceAtLeast(30))
      } else {
        playerControlsExitAnimationSpec((300 * animSpeed).toInt().coerceAtLeast(50))
      },
    label = "controls_transparent_overlay",
  )

  // Vignette behind the top and bottom bars. Fades with the bars instead of sliding, so it stays
  // anchored to the frame edges whatever animation style the bars themselves use.
  val edgeVignetteAlpha by animateFloatAsState(
    if (controlsShown && !areControlsLocked && !areSlidersShown) 1f else 0f,
    animationSpec =
      if (reduceMotion) {
        snap()
      } else if (controlsShown && !areControlsLocked && !areSlidersShown) {
        playerControlsEnterAnimationSpec((100 * animSpeed).toInt().coerceAtLeast(30))
      } else {
        playerControlsExitAnimationSpec((300 * animSpeed).toInt().coerceAtLeast(50))
      },
    label = "controls_edge_vignette",
  )

  if (isTelevision) {
    val tvSheetShown by viewModel.sheetShown.collectAsState()
    val tvPanelShown by viewModel.panelShown.collectAsState()
    val tvSubtitles by viewModel.subtitleTracks.collectAsState(persistentListOf())
    val tvAudioTracks by viewModel.audioTracks.collectAsState(persistentListOf())
    val tvSpeedPresets by playerPreferences.speedPresets.collectAsState()
    val tvSortedSpeedPresets = remember(tvSpeedPresets) { tvSpeedPresets.map { it.toFloat() }.sorted() }
    val tvChapterStarts = remember(chapters) { chapters.map { it.start } }
    val tvVolumeVisible by viewModel.isVolumeSliderShown.collectAsState()
    val tvShowVolumeOverlay by playerPreferences.showVolumeGestureOverlay.collectAsState()
    val tvVolumeTimestamp by viewModel.volumeSliderTimestamp.collectAsState()
    val tvVolume by viewModel.currentVolume.collectAsState()
    val tvVolumePercent by viewModel.currentVolumePercent.collectAsState()
    val tvMpvVolume by PlaybackSession.propInt["volume"].collectAsStateWithLifecycle()
    val tvSwapVolumeAndBrightness by playerPreferences.swapVolumeAndBrightness.collectAsState()
    val tvControlsAnimStyle by playerPreferences.controlsAnimStyle.collectAsState()
    val tvVolumeBoostCap by audioPreferences.volumeBoostCap.collectAsState()
    val tvDisplayVolumeAsPercentage by playerPreferences.displayVolumeAsPercentage.collectAsState()
    // Some TV remotes send volume keys to Android instead of to this Activity.
    // Observe actual system-stream changes as well as the handled key events.
    DisposableEffect(playerActivity, viewModel) {
      val audio = playerActivity.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
      var lastVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
      val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
          val observedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
          if (observedVolume != lastVolume) {
            lastVolume = observedVolume
            if (viewModel.currentVolume.value != observedVolume) {
              viewModel.syncCurrentVolumeState()
              viewModel.displayVolumeSlider()
            }
          }
        }
      }
      playerActivity.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
      onDispose { playerActivity.contentResolver.unregisterContentObserver(volumeObserver) }
    }
    // The regular volume slider is below the TV-only return; keep its timing identical here.
    LaunchedEffect(tvVolumeTimestamp) {
      if (tvVolumeVisible && tvVolumeTimestamp > 0L) {
        delay(1_000L)
        viewModel.isVolumeSliderShown.value = false
      }
    }

    CompositionLocalProvider(
      LocalPlayerHdrOutputEnabled provides isHdrOutputEnabled,
      LocalPlayerLiquidGlassActive provides enableLiquidGlass,
      LocalForceDarkPlayerButtonsBackground provides forceDarkButtonBackground,
    ) {
      PlayerControlsContentTheme {
        Box(
          modifier =
            modifier
              .fillMaxSize()
              .clipToBounds()
              .onSizeChanged { playerBounds = it },
        ) {
          VideoOpenAnimationOverlay(
            style = videoOpenAnim,
            speedMultiplier = animSpeed,
            animationState = videoOpenAnimState,
          )
          DoubleTapToSeekOvals(
            amount = doubleTapSeekAmount,
            showOvals = false,
            showSeekIcon = true,
            showSeekTime = true,
            rippleRequest = seekRippleRequest,
          )
          // Status HUD remains visible even after playback controls fade away.
          PersistentStatusHud(
            viewModel = viewModel,
            preferences = playerPreferences,
            durationSeconds = seekbarDuration,
            playbackSpeed = playbackSpeed ?: 1f,
          )

          MediaScopesOverlay(
            viewModel = viewModel,
            audioTracks = scopeAudioTracks.toImmutableList(),
            durationSeconds = seekbarDuration,
            modifier = Modifier.fillMaxSize(),
          )

          TvPlayerControls(
            viewModel = viewModel,
            controlsShown = controlsShown,
            overlayOpen = tvSheetShown != Sheets.None || tvPanelShown != Panels.None,
            paused = paused == true,
            mediaTitle = mediaTitle,
            chapterTitle = currentChapter?.let(chapters::getOrNull)?.name,
            durationSeconds = seekbarDuration,
            chapterStarts = if (showChapterIndicators) tvChapterStarts else emptyList(),
            showBufferedRange = showBufferedRange,
            hasSubtitles = true,
            hasAudioTracks = tvAudioTracks.isNotEmpty(),
            hasChapters = chapters.isNotEmpty(),
            showQualityButton = showVideoQualitySelector,
            playbackSpeed = playbackSpeed ?: 1f,
            skipSegmentLabel = currentSkippableSegment?.label,
            showBuffering = showBufferingIndicator,
            autoHideMillis = playerTimeToDisappear.toLong(),
            onOpenSheet = onOpenSheet,
          )

          PlayerSheets(
            viewModel = viewModel,
            sheetShown = tvSheetShown,
            subtitles = tvSubtitles.toImmutableList(),
            onAddSubtitle = viewModel::addSubtitle,
            onToggleSubtitle = viewModel::toggleSubtitle,
            isSubtitleSelected = viewModel::isSubtitleSelected,
            subtitleSelectionIndicator = viewModel::subtitleSelectionIndicator,
            onRemoveSubtitle = viewModel::removeSubtitle,
            audioTracks = tvAudioTracks.toImmutableList(),
            onAddAudio = viewModel::addAudio,
            onSelectAudio = viewModel::selectAudioTrack,
            chapter = currentChapter?.let(chapters::getOrNull),
            chapters = chapters.toImmutableList(),
            onSeekToChapter = {
              chapters.getOrNull(it)?.let(viewModel::seekToPlaybackChapter)
            },
            decoder = decoder,
            onUpdateDecoder = { PlaybackSession.setPropertyString("hwdec", it.value) },
            speed = playbackSpeed ?: playerPreferences.defaultSpeed.get(),
            onSpeedChange = { PlaybackSession.setPropertyFloat("speed", it.toFixed(2)) },
            onMakeDefaultSpeed = { playerPreferences.defaultSpeed.set(it.toFixed(2)) },
            onAddSpeedPreset = { playerPreferences.speedPresets += it.toFixed(2).toString() },
            onRemoveSpeedPreset = { playerPreferences.speedPresets -= it.toFixed(2).toString() },
            onResetSpeedPresets = playerPreferences.speedPresets::delete,
            speedPresets = tvSortedSpeedPresets,
            onResetDefaultSpeed = {
              PlaybackSession.setPropertyFloat("speed", playerPreferences.defaultSpeed.deleteAndGet().toFixed(2))
            },
            onStartSleepTimer = viewModel::startTimer,
            onOpenPanel = onOpenPanel,
            onShowSheet = onOpenSheet,
            onDismissRequest = { onOpenSheet(Sheets.None) },
          )
          PlayerPanels(
            panelShown = tvPanelShown,
            viewModel = viewModel,
            onDismissRequest = { onOpenPanel(Panels.None) },
          )
          AnimatedVisibility(
            visible = tvVolumeVisible && tvShowVolumeOverlay,
            enter =
              buildControlsEnterH(
                tvControlsAnimStyle,
                reduceMotion,
                (100 * animSpeed).toInt().coerceAtLeast(30),
              ) { if (tvSwapVolumeAndBrightness) it else -it },
            exit =
              buildControlsExitH(
                tvControlsAnimStyle,
                reduceMotion,
                (300 * animSpeed).toInt().coerceAtLeast(50),
              ) { if (tvSwapVolumeAndBrightness) it else -it },
            modifier =
              Modifier
                .align(if (tvSwapVolumeAndBrightness) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 48.dp),
          ) {
            val currentBoost = (tvMpvVolume ?: 100) - 100
            val showBoost = tvVolumeBoostCap > 0 || currentBoost > 0
            VolumeSlider(
              tvVolume,
              volumePercentage = tvVolumePercent,
              mpvVolume = tvMpvVolume ?: 100,
              range = 0..viewModel.maxVolume,
              boostRange = if (showBoost) 0..maxOf(tvVolumeBoostCap, currentBoost) else null,
              displayAsPercentage = tvDisplayVolumeAsPercentage,
            )
          }
        }
      }
    }
    return
  }

  GestureHandler(
    viewModel = viewModel,
    externalPanelShown = isPlayerDrawerShown,
    onDismissExternalPanel = { setPlayerDrawerShown(false) },
    onLockedTouchSideChanged = { isLeft ->
      showUnlockOnLeft = isLeft
      resetControlsTimestamp = System.currentTimeMillis()
    },
    onSeekRipple = { seekRippleRequest = it },
  )

  DoubleTapToSeekOvals(
    amount = doubleTapSeekAmount,
    showOvals = showDoubleTapOvals,
    showSeekIcon = showSeekTime,
    showSeekTime = showSeekTime,
    rippleRequest = seekRippleRequest,
  )

  val glassSource = rememberVideoGlassSource(videoSurface)
  val videoSurfaceReady = videoSurface?.surfaceReady?.collectAsState()?.value == true
  val videoFrameReady = videoSurface?.textureFrameReadyState?.collectAsState()?.value == true
  // Recreate the recorder with the actual source scene. The old persistent recorder could
  // keep painting a released TextureView's pixels after an HDR/native-surface handoff.
  val videoBackdrop = key(glassSource, isHdrOutputEnabled, liquidGlassInHdr) {
    // HDR output changes the captured colorspace even when the TextureView stays mounted.
    // A new recorder must not replay pre-HDR pixels or filters over the new output.
    rememberLayerBackdrop(onDraw = { with(glassSource) { drawVideo() } })
  }
  // Native HDR output is a separate SurfaceView: Kyaant cannot sample it from Compose.
  // Hide the live sampler during mode changes and use the non-sampling frosted treatment.
  val playerKyantBackdrop = videoBackdrop.takeIf {
    enableLiquidGlass &&
      (!isHdrOutputEnabled || liquidGlassInHdr) &&
      videoSurface?.isTextureSurface == true &&
      videoSurfaceReady &&
      videoFrameReady &&
      glassSource.scene != null
  }

  CompositionLocalProvider(
    LocalKyantPlayerBackdrop provides playerKyantBackdrop,
    LocalPlayerHdrOutputEnabled provides isHdrOutputEnabled,
    LocalPlayerLiquidGlassActive provides enableLiquidGlass,
    LocalForceDarkPlayerButtonsBackground provides forceDarkButtonBackground,
    LocalPlayerButtonsOpacity provides playerButtonsBackgroundOpacity.coerceIn(0f, 1f),
  ) {
    PlayerControlsContentTheme {
      Box(
        modifier =
          modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { playerBounds = it },
      ) {
    VideoOpenAnimationOverlay(
      style = videoOpenAnim,
      speedMultiplier = animSpeed,
      animationState = videoOpenAnimState,
    )
    if (enableLiquidGlass) {
      if (playerKyantBackdrop != null) {
        VideoGlassBackdropSource(glassSource, videoBackdrop)
      }
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                listOf(
                  Color.Black,
                  Color.Transparent,
                  Color.Transparent,
                  Color.Black,
                ),
              ),
              alpha = transparentOverlay,
            ),
      )
    }
    if (brightness < 0) {
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = -brightness }
            .background(Color.Black)
            .zIndex(0f),
      )
    }
    if (statisticsPage == 6) {
      // Mirror the SurfaceView transform so page 6 stays on the video like mpv's own stats pages.
      Box(
        Modifier.fillMaxSize().graphicsLayer {
          if (overlaysFollowVideoZoom) {
            val zoomScale = app.gyrolet.mpvrx.ui.player.videoZoomMultiplier(currentZoom)
            scaleX = zoomScale
            scaleY = zoomScale
            translationX = videoPanX
            translationY = videoPanY
          }
        },
      ) {
        CustomStatsPageSixOverlay(
          viewModel = viewModel,
          modifier =
            Modifier
              .align(Alignment.TopStart)
              .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                  WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
              ).padding(top = 16.dp, start = 14.dp),
        )
      }
    }

    // Keep this outside the controls' AnimatedVisibility / constraint layout.
    PersistentStatusHud(
      viewModel = viewModel,
      preferences = playerPreferences,
      durationSeconds = seekbarDuration,
      playbackSpeed = playbackSpeed ?: 1f,
    )

    MediaScopesOverlay(
      viewModel = viewModel,
      audioTracks = scopeAudioTracks.toImmutableList(),
      durationSeconds = seekbarDuration,
      modifier = Modifier.fillMaxSize(),
    )

        CompositionLocalProvider(
          LocalRippleConfiguration provides playerRippleConfiguration,
          LocalPlayerButtonsClickEvent provides { resetControlsTimestamp = System.currentTimeMillis() },
          LocalKyantPlayerBackdrop provides playerKyantBackdrop,
          LocalForceDarkPlayerButtonsBackground provides forceDarkButtonBackground,
          LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) {
      CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Ltr,
      ) {
        val density = LocalDensity.current
        val bottomHeights = remember(isPortrait) { BottomClusterHeights() }

        ConstraintLayout(
          modifier =
            Modifier
              .fillMaxSize()
              .drawBehind {
                if (reduceMotion && transparentOverlay > 0f) {
                  drawRect(FullScreenScrimBrush, alpha = transparentOverlay)
                }
                // Only while the bars are up, and only at the edges, so the frame stays clean.
                if (edgeVignetteAlpha > 0f) {
                  drawRect(ControlsEdgeVignetteBrush, alpha = edgeVignetteAlpha)
                }
              }.then(safeAreaInsetModifier)
              .then(navigationBarBottomInsetModifier),
          ) {
          val (topLeftControls, topRightControls) = createRefs()
          val (volumeSlider, brightnessSlider) = createRefs()
          val unlockControlsButton = createRef()
          val (bottomRightControls, bottomLeftControls) = createRefs()
          val playerPauseButton = createRef()
          val bufferingIndicator = createRef()
          val skipSegmentChip = createRef()
          val seekbar = createRef()
          val (playerUpdates) = createRefs()
          val (customLeftButtonsRef, customRightButtonsRef) = createRefs()
          val customButtonsPortraitRef = createRef()

          val playbackPosition =
            if (isPortrait) portraitPlaybackControlsPosition else landscapePlaybackControlsPosition
          val hasPlaylistControls =
            playlistMode && (playlistItems.size > 1 || viewModel.getPlaylistTotalCount() > 1)
          val automaticQualityButton =
            showVideoQualitySelector &&
              !(if (isPortrait) portraitHasConfiguredQualityButton else landscapeHasConfiguredQualityButton)
          val hasBottomActions =
            automaticQualityButton ||
              (if (isPortrait) {
                portraitBottomButtons.isNotEmpty()
              } else {
                bottomLeftButtons.isNotEmpty() || bottomRightButtons.isNotEmpty()
              })
          val controlsWidth =
            if (playerBounds.width > 0) with(density) { playerBounds.width.toDp() } else configuration.screenWidthDp.dp
          val safeHorizontalInsets = with(density) {
            val insets = WindowInsets.safeDrawing
            (insets.getLeft(this, LayoutDirection.Ltr) + insets.getRight(this, LayoutDirection.Ltr)).toDp()
          }
          val playbackGeometry = resolvePlaybackControlsGeometry(
            width = controlsWidth,
            playbackPosition = playbackPosition,
            hasPlaylistControls = hasPlaylistControls,
            hasActions = hasBottomActions,
            spacing = spacing,
            horizontalInsets = safeHorizontalInsets,
          )
          val playPauseSize = playbackGeometry.playPauseSize
          val skipButtonSize = playbackGeometry.skipButtonSize
          val playbackButtonGap = playbackGeometry.buttonGap
          val bottomLayout =
            resolveBottomControlsLayout(
              seekbarPlacement = if (isPortrait) portraitSeekbarPlacement else landscapeSeekbarPlacement,
              playbackPosition = playbackPosition,
              hasButtons = hasBottomActions,
              allowSharedRow = playbackGeometry.canShareRow,
            )
          val buttonsBesidePlayback = bottomLayout.buttonsBesidePlayback
          val playbackNearSeekbar = buttonsBesidePlayback
          val portraitLeftButtons =
            if (buttonsBesidePlayback) portraitBottomButtons.take(portraitBottomButtons.size / 2) else emptyList()
          val portraitRightButtons =
            if (buttonsBesidePlayback) portraitBottomButtons.drop(portraitBottomButtons.size / 2) else portraitBottomButtons
          val hasLeftButtons = if (isPortrait) portraitLeftButtons.isNotEmpty() else bottomLeftButtons.isNotEmpty()
          val hasRightButtons =
            automaticQualityButton || (if (isPortrait) portraitRightButtons.isNotEmpty() else bottomRightButtons.isNotEmpty())
          val sharedPlaybackRowCenterX = createGuidelineFromStart(0.5f)
          val sharedRowSideInset =
            if (buttonsBesidePlayback) {
              playbackGeometry.rowHalfWidth + playbackGeometry.sideGap
            } else {
              0.dp
            }
          val bottomStack = bottomLayout.bottomToTop

          fun measured(
            px: Int,
            fallback: Dp,
          ): Dp = if (px == UNMEASURED_HEIGHT) fallback else with(density) { px.toDp() }
          val buttonsHeight =
            maxOf(
              if (hasRightButtons) measured(bottomHeights.rightButtons, BottomClusterFallbackRowHeight) else 0.dp,
              if (hasLeftButtons) measured(bottomHeights.leftButtons, BottomClusterFallbackRowHeight) else 0.dp,
            )

          fun bandHeight(slot: BottomSlot): Dp =
            when (slot) {
              BottomSlot.Buttons -> if (buttonsBesidePlayback) maxOf(buttonsHeight, playPauseSize) else buttonsHeight
              BottomSlot.Playback -> playPauseSize
              BottomSlot.Seekbar -> measured(bottomHeights.seekbar, BottomClusterFallbackRowHeight)
            }

          // Keep the seekbar close to its controls. Bands come from remembered heights, so a
          // layer leaving the layout (buffering, volume/brightness sliders, hidden controls) moves nothing.
          val clusterEdge = if (isPortrait) spacing.large else spacing.medium
          val clusterGap = spacing.extraSmall
          val customButtonsGap = spacing.smaller * 2
          val bandCenters = HashMap<BottomSlot, Dp>()
          var nextBandBottom = clusterEdge
          var bottomClusterTop = clusterEdge
          for (slot in bottomStack) {
            val height = bandHeight(slot)
            bandCenters[slot] = nextBandBottom + height / 2
            if (height > 0.dp) {
              bottomClusterTop = nextBandBottom + height
              nextBandBottom = bottomClusterTop + clusterGap
            }
          }
          val buttonsBandCenter = createGuidelineFromBottom(bandCenters[BottomSlot.Buttons] ?: clusterEdge)
          val seekbarBandCenter = createGuidelineFromBottom(bandCenters[BottomSlot.Seekbar] ?: clusterEdge)
          val playbackBandCenter =
            createGuidelineFromBottom(
              bandCenters[if (buttonsBesidePlayback) BottomSlot.Buttons else BottomSlot.Playback] ?: clusterEdge,
            )

          fun ConstrainScope.linkInBottomStack(slot: BottomSlot) {
            val center =
              when (slot) {
                BottomSlot.Buttons -> buttonsBandCenter
                BottomSlot.Playback -> playbackBandCenter
                BottomSlot.Seekbar -> seekbarBandCenter
              }
            top.linkTo(center)
            bottom.linkTo(center)
          }

          val volume by viewModel.currentVolume.collectAsState()
          val volumePercent by viewModel.currentVolumePercent.collectAsState()
          val mpvVolume by PlaybackSession.propInt["volume"].collectAsStateWithLifecycle()
          val swapVolumeAndBrightness by playerPreferences.swapVolumeAndBrightness.collectAsState()
          // Overlay visibility — Group 1
          val controlsAnimStyle by playerPreferences.controlsAnimStyle.collectAsState()
          val enterMs = (100 * animSpeed).toInt().coerceAtLeast(30)
          val exitMs = (300 * animSpeed).toInt().coerceAtLeast(50)

          // Slider display duration: 1000ms shown + 300ms exit animation = 1300ms total
          val sliderDisplayDuration = 1000L

          val volumeSliderTimestamp by viewModel.volumeSliderTimestamp.collectAsState()
          val brightnessSliderTimestamp by viewModel.brightnessSliderTimestamp.collectAsState()

          // Track timestamp to restart timer on every gesture event
          LaunchedEffect(volumeSliderTimestamp) {
            if (isVolumeSliderShown && volumeSliderTimestamp > 0) {
              delay(sliderDisplayDuration)
              viewModel.isVolumeSliderShown.update { false }
            }
          }

          LaunchedEffect(brightnessSliderTimestamp) {
            if (isBrightnessSliderShown && brightnessSliderTimestamp > 0) {
              delay(sliderDisplayDuration)
              viewModel.isBrightnessSliderShown.update { false }
            }
          }

          val navigationBarsPadding =
            if (showSystemNavigationBar && !safeAreaWindow) {
              WindowInsets.navigationBars.asPaddingValues()
            } else {
              null
            }
          val navigationStartPaddingModifier =
            navigationBarsPadding?.let { navBarPadding ->
              Modifier.padding(
                start = navBarPadding.calculateLeftPadding(LayoutDirection.Ltr),
              )
            } ?: Modifier
          val navigationEndPaddingModifier =
            navigationBarsPadding?.let { navBarPadding ->
              Modifier.padding(
                end = navBarPadding.calculateRightPadding(LayoutDirection.Ltr),
              )
            } ?: Modifier
          val navigationHorizontalPaddingModifier =
            navigationBarsPadding?.let { navBarPadding ->
              Modifier.padding(
                start = navBarPadding.calculateLeftPadding(LayoutDirection.Ltr),
                end = navBarPadding.calculateRightPadding(LayoutDirection.Ltr),
              )
            } ?: Modifier

          AnimatedVisibility(
            isBrightnessSliderShown && showBrightnessGestureOverlay,
            enter =
              buildControlsEnterH(
                controlsAnimStyle,
                reduceMotion,
                enterMs,
              ) { if (swapVolumeAndBrightness) -it else it },
            exit =
              buildControlsExitH(
                controlsAnimStyle,
                reduceMotion,
                exitMs,
              ) { if (swapVolumeAndBrightness) -it else it },
            modifier =
              Modifier.clickable {
                viewModel.dismissAdjustmentOverlays()
                viewModel.showControls()
              }.constrainAs(brightnessSlider) {
                if (swapVolumeAndBrightness) {
                  start.linkTo(parent.start, if (isPortrait) spacing.large else spacing.extraLarge)
                } else {
                  end.linkTo(parent.end, if (isPortrait) spacing.large else spacing.extraLarge)
                }
                top.linkTo(parent.top, spacing.larger)
                bottom.linkTo(parent.bottom, spacing.extraLarge)
              },
          ) { BrightnessSlider(brightness, 0f..1f, 0f..0.75f) }

          AnimatedVisibility(
            isVolumeSliderShown && showVolumeGestureOverlay,
            enter =
              buildControlsEnterH(
                controlsAnimStyle,
                reduceMotion,
                enterMs,
              ) { if (swapVolumeAndBrightness) it else -it },
            exit =
              buildControlsExitH(
                controlsAnimStyle,
                reduceMotion,
                exitMs,
              ) { if (swapVolumeAndBrightness) it else -it },
            modifier =
              Modifier.clickable {
                viewModel.dismissAdjustmentOverlays()
                viewModel.showControls()
              }.constrainAs(volumeSlider) {
                if (swapVolumeAndBrightness) {
                  end.linkTo(parent.end, if (isPortrait) spacing.large else spacing.extraLarge)
                } else {
                  start.linkTo(parent.start, if (isPortrait) spacing.large else spacing.extraLarge)
                }
                top.linkTo(parent.top, spacing.larger)
                bottom.linkTo(parent.bottom, spacing.extraLarge)
              },
          ) {
            val boostCap by audioPreferences.volumeBoostCap.collectAsState()
            val displayVolumeAsPercentage by playerPreferences.displayVolumeAsPercentage.collectAsState()

            // Show if boost is allowed (boostCap > 0) OR if we are currently boosted (> 100)
            val currentBoost = (mpvVolume ?: 100) - 100
            val showBoost = boostCap > 0 || currentBoost > 0
            val effBoostCap = maxOf(boostCap, currentBoost)

            VolumeSlider(
              volume,
              volumePercentage = volumePercent,
              mpvVolume = mpvVolume ?: 100,
              range = 0..viewModel.maxVolume,
              boostRange = if (showBoost) 0..effBoostCap else null,
              displayAsPercentage = displayVolumeAsPercentage,
            )
          }

          val holdForMultipleSpeed by playerPreferences.holdForMultipleSpeed.collectAsState()
          val currentPlayerUpdate by viewModel.playerUpdate.collectAsState()
          val isHorizontalSeekActive = currentPlayerUpdate is PlayerUpdates.HorizontalSeek
          val isTranslatingSub by viewModel.isTranslatingSub.collectAsState()
          val translationProgress by viewModel.translationProgress.collectAsState()
          val translationStatus by viewModel.translationStatus.collectAsState()
          val translatingTrackName by viewModel.translatingTrackName.collectAsState()
          val isRealtimeSubsActive by viewModel.isRealtimeSubsActive.collectAsState()
          val realtimeSubsLanguage by viewModel.realtimeSubsLanguage.collectAsState()
          val realtimeSubsStatus by viewModel.realtimeSubsStatus.collectAsState()
          val isGeneratingSubtitles by viewModel.isGeneratingSubtitles.collectAsState()
          val subtitleGenerationProgress by viewModel.subtitleGenerationProgress.collectAsState()
          val subtitleGenerationStatus by viewModel.subtitleGenerationStatus.collectAsState()

          // Overlay visibility — Groups 2 & 5
          val showHoldSpeedOverlay by playerPreferences.showHoldSpeedOverlay.collectAsState()
          val showAspectRatioOverlay by playerPreferences.showAspectRatioOverlay.collectAsState()
          val showZoomLevelOverlay by playerPreferences.showZoomLevelOverlay.collectAsState()
          val showRepeatShuffleOverlay by playerPreferences.showRepeatShuffleOverlay.collectAsState()
          val showActionFeedbackOverlay by playerPreferences.showActionFeedbackOverlay.collectAsState()
          val showResumeIndicatorOverlay by playerPreferences.showResumeIndicatorOverlay.collectAsState()
          val showProviderStatusOverlay by playerPreferences.showProviderStatusOverlay.collectAsState()

          // Determines whether the center action-pill should be visible for the current update.
          // Each update type is gated by its own toggle so the user can silence individual
          // categories without affecting the others or the underlying gesture behaviour.
          val shouldShowPlayerUpdate =
            when (currentPlayerUpdate) {
              is PlayerUpdates.MultipleSpeed -> showHoldSpeedOverlay
              is PlayerUpdates.DynamicSpeedControl -> showHoldSpeedOverlay
              is PlayerUpdates.AspectRatio -> showAspectRatioOverlay
              is PlayerUpdates.VideoZoom -> showZoomLevelOverlay
              is PlayerUpdates.SubtitleZoom -> showZoomLevelOverlay
              is PlayerUpdates.RepeatMode -> showActionFeedbackOverlay
              is PlayerUpdates.Shuffle -> showRepeatShuffleOverlay
              is PlayerUpdates.ShowText -> showActionFeedbackOverlay
              is PlayerUpdates.ProviderStatusText -> showProviderStatusOverlay
              is PlayerUpdates.ResumedFrom -> showResumeIndicatorOverlay
              is PlayerUpdates.ResumeAvailable -> true
              is PlayerUpdates.StartedAfresh -> showResumeIndicatorOverlay
              is PlayerUpdates.HorizontalSeek -> showActionFeedbackOverlay && !windowedSeekActive
              is PlayerUpdates.FrameInfo -> true // Groups 3/4 — not in scope
              is PlayerUpdates.None -> false
            }
          val aspectRatio by viewModel.videoAspect.collectAsState()
          val currentAspectRatio by viewModel.currentAspectRatio.collectAsState()
          val videoZoom by viewModel.videoZoom.collectAsState()

          val areButtonsVisible = controlsShown && !areControlsLocked && !areSlidersShown
          val seekbarVisible = (controlsShown || if (isHorizontalSeekActive) showSeekbarDuringHorizontalSeeking else seekBarShown) &&
            !areControlsLocked && !areSlidersShown
          val leftCustomButtons = remember(customButtons) { customButtons.filter { it.isLeft } }
          val rightCustomButtons = remember(customButtons) { customButtons.filterNot { it.isLeft } }
          val showLandscapeLeftCustomButtons = areButtonsVisible && !isPortrait && leftCustomButtons.isNotEmpty()
          val showLandscapeRightCustomButtons = areButtonsVisible && !isPortrait && rightCustomButtons.isNotEmpty()
          val showPortraitCustomButtons = areButtonsVisible && isPortrait && customButtons.isNotEmpty()
          // Custom rows carry transparent padding for liquid-glass shadows; offset it so the visible
          // pills have twice the old 8dp clearance, independently of the tighter seekbar gaps.
          val customRowBottomOffset = bottomClusterTop + customButtonsGap - CustomButtonsRowInsetVertical
          val customRowEdgeMargin = spacing.large - CustomButtonsRowInsetHorizontal
          val customRowAboveSkipChip =
            showLandscapeLeftCustomButtons || showLandscapeRightCustomButtons || showPortraitCustomButtons
          val customRowVisibleHeight =
            (maxOf(
              if (showLandscapeLeftCustomButtons) measured(bottomHeights.leftCustomRow, CustomButtonsRowFallbackHeight) else 0.dp,
              if (showLandscapeRightCustomButtons || showPortraitCustomButtons) measured(bottomHeights.rightCustomRow, CustomButtonsRowFallbackHeight) else 0.dp,
            ) - CustomButtonsRowInsetVertical * 2).coerceAtLeast(0.dp)
          val visibleBottomClusterTop = when {
            areButtonsVisible -> bottomClusterTop
            seekbarVisible -> (bandCenters[BottomSlot.Seekbar] ?: clusterEdge) + bandHeight(BottomSlot.Seekbar) / 2
            else -> clusterEdge
          }
          val skipChipBottomTarget =
            visibleBottomClusterTop +
              if (customRowAboveSkipChip) customButtonsGap + customRowVisibleHeight + spacing.smaller else spacing.smaller
          val skipChipBottomOffset by animateDpAsState(
            targetValue = skipChipBottomTarget,
            animationSpec = if (reduceMotion) snap() else AppMotion.Spatial.StandardDp,
            label = "skip_chip_bottom_offset",
          )

          val skipChipVisible =
            currentSkippableSegment != null &&
              !areSlidersShown &&
              ((controlsShown && !areControlsLocked) || showSkipChipAuto)


          var updateInteracting by remember(currentPlayerUpdate) { mutableStateOf(false) }
          LaunchedEffect(currentPlayerUpdate, aspectRatio, videoZoom, updateInteracting) {
            if (updateInteracting) return@LaunchedEffect
            if (currentPlayerUpdate is PlayerUpdates.MultipleSpeed ||
              currentPlayerUpdate is PlayerUpdates.DynamicSpeedControl ||
              currentPlayerUpdate is PlayerUpdates.None
            ) {
              return@LaunchedEffect
            }
            val dismissDelay =
              if (
                currentPlayerUpdate is PlayerUpdates.ResumedFrom ||
                currentPlayerUpdate is PlayerUpdates.ResumeAvailable ||
                currentPlayerUpdate is PlayerUpdates.StartedAfresh
              ) {
                4000L
              } else {
                2000L
              }
            delay(dismissDelay)
            viewModel.playerUpdate.update { PlayerUpdates.None }
          }

          AnimatedVisibility(
            shouldShowPlayerUpdate,
            enter = buildControlsEnterV(controlsAnimStyle, reduceMotion, enterMs) { -it },
            exit = buildControlsExitV(controlsAnimStyle, reduceMotion, exitMs) { -it },
            modifier =
              Modifier
                .then(
                  if (showSystemStatusBar && (!isPortrait || !controlsShown || areControlsLocked)) {
                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                  } else {
                    Modifier
                  },
                ).constrainAs(playerUpdates) {
                  linkTo(parent.start, parent.end)
                  if (isPortrait && controlsShown && !areControlsLocked) {
                    top.linkTo(topLeftControls.bottom, spacing.medium)
                  } else {
                    top.linkTo(parent.top, if (isPortrait) 104.dp else 64.dp)
                  }
                },
          ) {
            key(currentPlayerUpdate) {
              SwipeDismissibleToast(
                onDismiss = { if (viewModel.playerUpdate.value == currentPlayerUpdate) viewModel.playerUpdate.value = PlayerUpdates.None },
                onInteracting = { updateInteracting = it },
              ) {
                when (currentPlayerUpdate) {
                  is PlayerUpdates.MultipleSpeed ->
                    MultipleSpeedPlayerUpdate(
                      currentSpeed = holdForMultipleSpeed.coerceIn(0.5f, 4f),
                    )
                  is PlayerUpdates.DynamicSpeedControl -> {
                    val speedUpdate = currentPlayerUpdate as PlayerUpdates.DynamicSpeedControl
                    MultipleSpeedPlayerUpdate(currentSpeed = speedUpdate.speed)
                  }
                  is PlayerUpdates.AspectRatio -> {
                    val customRatiosSet by playerPreferences.customAspectRatios.collectAsState()
                    val displayText =
                      if (currentAspectRatio > 0) {
                        // Custom aspect ratio - try to find its label first
                        val customLabel =
                          customRatiosSet.firstNotNullOfOrNull { str ->
                            val parts = str.split("|")
                            if (parts.size == 2) {
                              val savedRatio = parts[1].toDoubleOrNull()
                              if (savedRatio != null && kotlin.math.abs(savedRatio - currentAspectRatio) < 0.01) {
                                parts[0] // Return the label
                              } else {
                                null
                              }
                            } else {
                              null
                            }
                          }

                        customLabel ?: run {
                          // No custom label found, use preset names or format as ratio
                          val ratio = currentAspectRatio
                          when {
                            kotlin.math.abs(ratio - 16.0 / 9.0) < 0.01 -> "16:9"
                            kotlin.math.abs(ratio - 4.0 / 3.0) < 0.01 -> "4:3"
                            kotlin.math.abs(ratio - 16.0 / 10.0) < 0.01 -> "16:10"
                            kotlin.math.abs(ratio - 21.0 / 9.0) < 0.01 -> "21:9"
                            kotlin.math.abs(ratio - 32.0 / 9.0) < 0.01 -> "32:9"
                            kotlin.math.abs(ratio - 1.0) < 0.01 -> "1:1"
                            kotlin.math.abs(ratio - 2.35) < 0.01 -> "2.35:1"
                            kotlin.math.abs(ratio - 2.39) < 0.01 -> "2.39:1"
                            else -> String.format("%.2f:1", ratio)
                          }
                        }
                      } else {
                        // Standard mode (Fit/Crop/Stretch)
                        stringResource(aspectRatio.titleRes)
                      }
                    TextPlayerUpdate(displayText)
                  }
                  is PlayerUpdates.ShowText ->
                    TextPlayerUpdate((currentPlayerUpdate as PlayerUpdates.ShowText).value)

                  is PlayerUpdates.ProviderStatusText ->
                    TextPlayerUpdate((currentPlayerUpdate as PlayerUpdates.ProviderStatusText).value)

                  is PlayerUpdates.VideoZoom -> {
                    val zoomPercentage = (videoZoom * 100).toInt()
                    TextPlayerUpdate(String.format("Zoom: %d%%", zoomPercentage))
                  }

                  is PlayerUpdates.SubtitleZoom -> {
                    val zoomUpdate = currentPlayerUpdate as PlayerUpdates.SubtitleZoom
                    val label = stringResource(
                      if (zoomUpdate.secondary) R.string.player_sheets_secondary_sub_scale else R.string.player_sheets_sub_primary_scale,
                    )
                    TextPlayerUpdate(String.format("%s: %.2fx", label, zoomUpdate.scale))
                  }

                  is PlayerUpdates.HorizontalSeek -> {
                    val seekUpdate = currentPlayerUpdate as PlayerUpdates.HorizontalSeek
                    SeekPlayerUpdate(
                      currentTime = seekUpdate.currentTime,
                      seekDelta = "[${seekUpdate.seekDelta}]",
                    )
                  }

                  is PlayerUpdates.RepeatMode -> {
                    val mode = (currentPlayerUpdate as PlayerUpdates.RepeatMode).mode
                    val text =
                      when (mode) {
                        app.gyrolet.mpvrx.ui.player.RepeatMode.OFF -> "Repeat: Off"
                        app.gyrolet.mpvrx.ui.player.RepeatMode.ONE -> "Repeat: Current file"
                        app.gyrolet.mpvrx.ui.player.RepeatMode.ALL -> {
                          if (playlistMode && playlistItems.isNotEmpty()) {
                            "Repeat: All playlist"
                          } else {
                            "Repeat: Current file"
                          }
                        }
                      }
                    TextPlayerUpdate(text)
                  }

                  is PlayerUpdates.Shuffle -> {
                    val enabled = (currentPlayerUpdate as PlayerUpdates.Shuffle).enabled
                    val text =
                      if (enabled) {
                        if (playlistMode && playlistItems.isNotEmpty()) {
                          "Shuffle: On"
                        } else {
                          "Shuffle: Not available"
                        }
                      } else {
                        "Shuffle: Off"
                      }
                    TextPlayerUpdate(text)
                  }

                  is PlayerUpdates.ResumedFrom -> {
                    val resumedUpdate = currentPlayerUpdate as PlayerUpdates.ResumedFrom
                    ResumedFromPlayerUpdate(
                      position = resumedUpdate.position,
                      onRestart = {
                        viewModel.playerUpdate.value = PlayerUpdates.None
                        viewModel.restartFromBeginning()
                      },
                    )
                  }

                  is PlayerUpdates.ResumeAvailable -> {
                    val resumeUpdate = currentPlayerUpdate as PlayerUpdates.ResumeAvailable
                    ResumeAvailablePlayerUpdate(
                      position = resumeUpdate.position,
                      onResume = {
                        viewModel.playerUpdate.value = PlayerUpdates.None
                        viewModel.seekTo(resumeUpdate.position)
                      },
                    )
                  }

                  is PlayerUpdates.StartedAfresh -> {
                    TextPlayerUpdate(stringResource(R.string.player_started_afresh_pill))
                  }

                  is PlayerUpdates.FrameInfo -> {
                    val frameInfo = (currentPlayerUpdate as PlayerUpdates.FrameInfo)
                    val text =
                      if (frameInfo.totalFrames > 0) {
                        "Frame: ${frameInfo.currentFrame}/${frameInfo.totalFrames}"
                      } else {
                        "Frame: ${frameInfo.currentFrame}"
                      }
                    TextPlayerUpdate(text)
                  }

                  else -> {}
                }
              }
            }
          }

          AnimatedVisibility(
            visible = showLandscapeLeftCustomButtons,
            enter = buildControlsEnterH(controlsAnimStyle, reduceMotion, enterMs) { -it },
            exit = buildControlsExitH(controlsAnimStyle, reduceMotion, exitMs) { -it },
            modifier =
              navigationStartPaddingModifier.constrainAs(customLeftButtonsRef) {
                start.linkTo(parent.start, customRowEdgeMargin)
                bottom.linkTo(parent.bottom, customRowBottomOffset)
                width = Dimension.preferredWrapContent
                height = Dimension.wrapContent
              }.onSizeChanged { bottomHeights.leftCustomRow = it.height },
          ) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              modifier =
                Modifier
                  .horizontalScroll(rememberScrollState())
                  .padding(horizontal = CustomButtonsRowInsetHorizontal, vertical = CustomButtonsRowInsetVertical),
            ) {
              leftCustomButtons.forEach { button ->
                key(button.id) {
                  CustomControlButton(
                    label = button.label,
                    liquidGlass = enableLiquidGlass,
                    onClick = {
                      resetControlsTimestamp = System.currentTimeMillis()
                      viewModel.callCustomButton(button.id)
                    },
                    onLongClick = {
                      resetControlsTimestamp = System.currentTimeMillis()
                      viewModel.callCustomButtonLongPress(button.id)
                    },
                  )
                }
              }
            }
          }

          AnimatedVisibility(
            visible = showLandscapeRightCustomButtons,
            enter = buildControlsEnterH(controlsAnimStyle, reduceMotion, enterMs) { it },
            exit = buildControlsExitH(controlsAnimStyle, reduceMotion, exitMs) { it },
            modifier =
              navigationEndPaddingModifier
                .constrainAs(customRightButtonsRef) {
                  end.linkTo(parent.end, customRowEdgeMargin)
                  bottom.linkTo(parent.bottom, customRowBottomOffset)
                  width = Dimension.preferredWrapContent
                  height = Dimension.wrapContent
                }.onSizeChanged { bottomHeights.rightCustomRow = it.height },
          ) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              modifier =
                Modifier
                  .horizontalScroll(rememberScrollState(), reverseScrolling = true)
                  .padding(horizontal = CustomButtonsRowInsetHorizontal, vertical = CustomButtonsRowInsetVertical),
            ) {
              rightCustomButtons.forEach { button ->
                key(button.id) {
                  CustomControlButton(
                    label = button.label,
                    liquidGlass = enableLiquidGlass,
                    onClick = {
                      resetControlsTimestamp = System.currentTimeMillis()
                      viewModel.callCustomButton(button.id)
                    },
                    onLongClick = {
                      resetControlsTimestamp = System.currentTimeMillis()
                      viewModel.callCustomButtonLongPress(button.id)
                    },
                  )
                }
              }
            }
          }

          AnimatedVisibility(
            visible = showPortraitCustomButtons,
            enter = buildControlsEnterV(controlsAnimStyle, reduceMotion, enterMs) { it },
            exit = buildControlsExitV(controlsAnimStyle, reduceMotion, exitMs) { it },
            modifier =
              navigationHorizontalPaddingModifier
                .constrainAs(customButtonsPortraitRef) {
                  start.linkTo(parent.start, customRowEdgeMargin)
                  end.linkTo(parent.end, customRowEdgeMargin)
                  bottom.linkTo(parent.bottom, customRowBottomOffset)
                  width = Dimension.fillToConstraints
                  height = Dimension.wrapContent
                }.onSizeChanged { bottomHeights.rightCustomRow = it.height },
          ) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              modifier =
                Modifier
                  .horizontalScroll(rememberScrollState())
                  .padding(horizontal = CustomButtonsRowInsetHorizontal, vertical = CustomButtonsRowInsetVertical),
            ) {
              customButtons.forEach { button ->
                key(button.id) {
                  CustomControlButton(
                    label = button.label,
                    liquidGlass = enableLiquidGlass,
                    onClick = {
                      resetControlsTimestamp = System.currentTimeMillis()
                      viewModel.callCustomButton(button.id)
                    },
                    onLongClick = {
                      resetControlsTimestamp = System.currentTimeMillis()
                      viewModel.callCustomButtonLongPress(button.id)
                    },
                  )
                }
              }
            }
          }

          AnimatedVisibility(
            visible = controlsShown && areControlsLocked,
            enter = buildControlsEnterV(controlsAnimStyle, reduceMotion, enterMs) { it },
            exit = buildControlsExitV(controlsAnimStyle, reduceMotion, exitMs) { it },
            modifier =
              Modifier
                .then(
                  if (showSystemStatusBar) {
                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                  } else {
                    Modifier
                  },
                ).then(
                  if (showSystemNavigationBar && !safeAreaWindow) {
                    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
                    Modifier.padding(
                      start = navBarPadding.calculateLeftPadding(LayoutDirection.Ltr),
                      end = navBarPadding.calculateRightPadding(LayoutDirection.Ltr),
                    )
                  } else {
                    Modifier
                  },
                ).constrainAs(unlockControlsButton) {
                  top.linkTo(parent.top, if (isPortrait) spacing.extraLarge else spacing.small)
                  if (showUnlockOnLeft) {
                    start.linkTo(parent.start, spacing.large)
                  } else {
                    end.linkTo(parent.end, spacing.large)
                  }
                },
          ) {
            PlayerButtonTheme {
              ControlsButton(
                icon = Icons.RoundedFilled.LockOpen,
                onClick = viewModel::unlockControls,
                title = stringResource(R.string.ui_unlock_controls),
                color = playerButtonContentColor(),
                modifier = Modifier.size(48.dp),
              )
            }
          }

          AnimatedVisibility(
            visible = skipChipVisible,
            enter = buildControlsEnterH(controlsAnimStyle, reduceMotion, enterMs) { it },
            exit = buildControlsExitH(controlsAnimStyle, reduceMotion, exitMs) { it },
            modifier =
              navigationEndPaddingModifier
                .constrainAs(skipSegmentChip) {
                  end.linkTo(parent.end, spacing.large)
                  bottom.linkTo(parent.bottom, skipChipBottomOffset)
                },
          ) {
            val segment = currentSkippableSegment ?: return@AnimatedVisibility
            val segmentColor = segment.type.accentColor
            val segmentSurfaceColor =
              Color(
                red = segmentColor.red * 0.30f,
                green = segmentColor.green * 0.30f,
                blue = segmentColor.blue * 0.30f,
                alpha = 0.88f,
              ).asPlayerButtonBackground()
            val segmentBorderColor =
              Color(
                red = segmentColor.red * 0.72f,
                green = segmentColor.green * 0.72f,
                blue = segmentColor.blue * 0.72f,
                alpha = 0.96f,
              ).asPlayerButtonBackground()
            if (enableLiquidGlass) {
              LiquidPillButton(
                onClick = {
                  resetControlsTimestamp = System.currentTimeMillis()
                  viewModel.skipActiveSegment()
                },
                horizontalPadding = 16.dp,
                height = 40.dp,
              ) {
                Text(
                  text = segment.label,
                  style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                  color = segmentColor.copy(alpha = 1f),
                )
              }
            } else {
              Surface(
                shape = RoundedCornerShape(999.dp),
                color = segmentSurfaceColor,
                border = BorderStroke(1.5.dp, segmentBorderColor),
                modifier =
                  Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable {
                      resetControlsTimestamp = System.currentTimeMillis()
                      viewModel.skipActiveSegment()
                    },
              ) {
                Text(
                  text = segment.label,
                  style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                  color = segmentColor.copy(alpha = 1f),
                  modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
              }
            }
          }

          AnimatedVisibility(
            visible = showBufferingIndicator,
            enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.88f, animationSpec = tween(200)),
            exit = fadeOut(tween(250)) + scaleOut(targetScale = 0.88f, animationSpec = tween(250)),
            modifier =
              Modifier.constrainAs(bufferingIndicator) {
                start.linkTo(parent.absoluteLeft)
                end.linkTo(parent.absoluteRight)
                // Always centred: in the bottom cluster it would cover the seekbar while the play row is hidden.
                top.linkTo(parent.top)
                bottom.linkTo(parent.bottom)
              },
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              LoadingIndicator(
                modifier = Modifier.size(76.dp),
              )
              val cachePercent = bufferingState.cachePercent
              val cacheSeconds = bufferingState.cacheSeconds
              val currentTorrentState = torrentStateHolder.value
              val bufferText =
                when {
                  currentTorrentState is TorrentStreamingState.Streaming &&
                    currentQueueItem?.playableUri == currentTorrentState.localUrl -> {
                    val streamState = currentTorrentState
                    val speed = app.gyrolet.mpvrx.domain.torrent.formatTorrentSpeed(streamState.downloadSpeed)
                    val peers = "${streamState.peers} peers"
                    val progress = "${(streamState.bufferProgress * 100).toInt()}%"
                    "$speed | $peers | $progress"
                  }
                  // mpv only reports a fill target while it is actually holding playback for cache.
                  isMpvBuffering && cachePercent != null && cachePercent in 1..99 ->
                    stringResource(R.string.player_buffering_percent, cachePercent)
                  isMpvBuffering && cacheSeconds != null ->
                    stringResource(
                      R.string.player_buffering_seconds,
                      String.format(java.util.Locale.getDefault(), "%.1f", cacheSeconds),
                    )
                  bufferingState.phase == BufferingPhase.OPENING -> stringResource(R.string.player_opening_stream)
                  else -> stringResource(R.string.ui_buffering)
                }
              if (enableLiquidGlass) {
                LiquidPillButton(
                  onClick = {},
                  isInteractive = false,
                  horizontalPadding = 12.dp,
                  height = 32.dp,
                ) {
                  Box(
                    modifier =
                      Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                  )
                  Text(
                    text = bufferText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                  )
                }
              } else {
                Surface(
                  shape = CircleShape,
                  color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                  border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                  ) {
                    Box(
                      modifier =
                        Modifier
                          .size(6.dp)
                          .clip(CircleShape)
                          .background(MaterialTheme.colorScheme.primary),
                    )
                    Text(
                      text = bufferText,
                      style = MaterialTheme.typography.labelMedium,
                      color = MaterialTheme.colorScheme.onPrimaryContainer,
                      fontWeight = FontWeight.SemiBold,
                    )
                  }
                }
              }
            }
          }

          AnimatedVisibility(
            visible = controlsShown && !areControlsLocked && !areSlidersShown && !showBufferingIndicator,
            enter = buildControlsEnterV(controlsAnimStyle, reduceMotion, enterMs) { 0 },
            exit = buildControlsExitV(controlsAnimStyle, reduceMotion, exitMs) { 0 },
            modifier =
              Modifier.constrainAs(playerPauseButton) {
                start.linkTo(parent.absoluteLeft)
                end.linkTo(parent.absoluteRight)
                if (playbackNearSeekbar) {
                  linkInBottomStack(BottomSlot.Playback)
                } else {
                  top.linkTo(parent.top)
                  bottom.linkTo(parent.bottom)
                }
              },
          ) {
            val hasPlaylistControls =
              playlistMode && (playlistItems.size > 1 || viewModel.getPlaylistTotalCount() > 1)

            if (hasPlaylistControls) {
              androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(playbackButtonGap),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                AdaptiveControlsContainer(
                  onClick = {
                    if (viewModel.hasPrevious()) {
                      resetControlsTimestamp = System.currentTimeMillis()
                      viewModel.playPrevious()
                    }
                  },
                  modifier =
                    Modifier
                      .size(skipButtonSize)
                      .tvFocusHighlight(CircleShape, enabled = viewModel.hasPrevious()),
                  color =
                    if (viewModel.hasPrevious()) {
                      playerButtonContentColor()
                    } else {
                      playerButtonContentColor().copy(alpha = 0.38f)
                    },
                  isInteractive = viewModel.hasPrevious(),
                  buttonSize = skipButtonSize,
                  horizontalPadding = 0.dp,
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.SkipPrevious,
                    contentDescription =
                      androidx.compose.ui.res.stringResource(
                        app.gyrolet.mpvrx.R.string.pref_gesture_media_previous,
                      ),
                    tint = LocalContentColor.current,
                    modifier =
                      Modifier
                        .fillMaxSize()
                        .padding(MaterialTheme.spacing.small),
                  )
                }

                AdaptiveControlsContainer(
                  onClick = {
                    resetControlsTimestamp = System.currentTimeMillis()
                    viewModel.pauseUnpause()
                  },
                  modifier =
                    Modifier
                      .size(playPauseSize)
                      .tvInitialFocus(tvPlayFocusRequester)
                      .tvFocusHighlight(CircleShape),
                  color = playerButtonContentColor(),
                  buttonSize = playPauseSize,
                  horizontalPadding = 0.dp,
                ) {
                  AnimatedPlayPauseIcon(
                    isPlaying = paused == false,
                    modifier =
                      Modifier
                        .fillMaxSize()
                        .padding(MaterialTheme.spacing.medium),
                    tint = LocalContentColor.current,
                  )
                }

                AdaptiveControlsContainer(
                  onClick = {
                    if (viewModel.hasNext()) {
                      resetControlsTimestamp = System.currentTimeMillis()
                      viewModel.playNext()
                    }
                  },
                  modifier =
                    Modifier
                      .size(skipButtonSize)
                      .tvFocusHighlight(CircleShape, enabled = viewModel.hasNext()),
                  color =
                    if (viewModel.hasNext()) {
                      playerButtonContentColor()
                    } else {
                      playerButtonContentColor().copy(alpha = 0.38f)
                    },
                  isInteractive = viewModel.hasNext(),
                  buttonSize = skipButtonSize,
                  horizontalPadding = 0.dp,
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.SkipNext,
                    contentDescription =
                      androidx.compose.ui.res.stringResource(
                        app.gyrolet.mpvrx.R.string.pref_gesture_media_next,
                      ),
                    tint = LocalContentColor.current,
                    modifier =
                      Modifier
                        .fillMaxSize()
                        .padding(MaterialTheme.spacing.small),
                  )
                }
              }
            } else {
              AdaptiveControlsContainer(
                onClick = {
                  resetControlsTimestamp = System.currentTimeMillis()
                  viewModel.pauseUnpause()
                },
                modifier =
                  Modifier
                    .size(playPauseSize)
                    .tvInitialFocus(tvPlayFocusRequester)
                    .tvFocusHighlight(CircleShape),
                color = playerButtonContentColor(),
                buttonSize = playPauseSize,
                horizontalPadding = 0.dp,
              ) {
                AnimatedPlayPauseIcon(
                  isPlaying = paused == false,
                  modifier =
                    Modifier
                      .fillMaxSize()
                      .padding(MaterialTheme.spacing.medium),
                  tint = LocalContentColor.current,
                )
              }
            }
          }

          WindowedSeekPreview(
            viewModel = viewModel,
            durationSeconds = seekbarDuration,
            isPortrait = isPortrait,
            onCancel = {
              seekbarInteractionCancelled = true
              isSeeking = false
              seekbarScrubWasPaused = null
            },
            modifier = Modifier.constrainAs(createRef()) {
              start.linkTo(parent.start, spacing.large + if (isPortrait) spacing.large else 62.dp)
              end.linkTo(parent.end, spacing.large + if (isPortrait) spacing.large else 62.dp)
              width = Dimension.fillToConstraints
              if (seekbarVisible) bottom.linkTo(seekbar.top, 8.dp)
              else bottom.linkTo(parent.bottom, 80.dp)
            },
          )

          AnimatedVisibility(
            visible =
              seekbarVisible,
            enter = buildControlsEnterV(controlsAnimStyle, reduceMotion, enterMs) { it },
            exit = buildControlsExitV(controlsAnimStyle, reduceMotion, exitMs) { it },
            modifier =
              Modifier
                .then(
                  if (showSystemNavigationBar && !safeAreaWindow) {
                    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
                    Modifier.padding(
                      start = navBarPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                      end = navBarPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    )
                  } else {
                    Modifier
                  },
                ).constrainAs(seekbar) {
                  linkInBottomStack(BottomSlot.Seekbar)
                  start.linkTo(parent.start, spacing.large)
                  end.linkTo(parent.end, spacing.large)
                }.onSizeChanged { bottomHeights.seekbar = it.height },
          ) {
            val position by PlaybackSession.propInt["time-pos"].collectAsStateWithLifecycle()
            val precisePosition by viewModel.precisePosition.collectAsStateWithLifecycle()
            val invertDuration by playerPreferences.invertDuration.collectAsState()
            val remaining  by PlaybackSession.propFloat["playtime-remaining"].collectAsStateWithLifecycle()
            val seekbarStyle by appearancePreferences.seekbarStyle.collectAsState()
            val useWavySeekbar by playerPreferences.useWavySeekbar.collectAsState()
            val displayedSeekbarPosition = precisePosition
            // Memoize the immutable copies so they are not reallocated on every position
            // tick (this scope recomposes ~20x/sec while scrubbing).
            val seekbarChapters =
              remember(chapters, showChapterIndicators) {
                if (showChapterIndicators) chapters.toImmutableList() else persistentListOf()
              }
            val skipSegmentsImmutable = remember(skipSegments) { skipSegments.toImmutableList() }
            val cachedPreviewTimes by viewModel.seekThumbnailTimes.collectAsStateWithLifecycle()

            // mpv republishes the cache endpoint several times per second during playback. Reading
            // it at the top of PlayerControls re-ran the whole controls tree (and re-solved its
            // ConstraintLayout) on every update, even with the controls hidden; keep it here.
            val demuxerCacheTimeFlow = remember { PlaybackSession.propDouble["demuxer-cache-time"] }
            val demuxerCacheTime = if (showBufferedRange) demuxerCacheTimeFlow.collectAsState().value else null
            val currentDemuxerCacheTime =
              demuxerCacheTime
                ?.toFloat()
                ?.takeIf { it > 0f && !it.isNaN() && !it.isInfinite() && seekbarDuration > 0f }
                ?.coerceIn(0f, seekbarDuration)
            var stableDemuxerCacheTime by remember { mutableFloatStateOf(currentDemuxerCacheTime ?: 0f) }
            LaunchedEffect(showBufferedRange, seekbarDuration, currentDemuxerCacheTime, isPlayerSeeking) {
              when {
                !showBufferedRange || seekbarDuration <= 0f -> stableDemuxerCacheTime = 0f
                currentDemuxerCacheTime != null -> stableDemuxerCacheTime = currentDemuxerCacheTime
                !isPlayerSeeking -> stableDemuxerCacheTime = 0f
              }
            }

            val effectiveRemaining =
              (remaining ?: 0f).takeIf { it > 0f }
                ?: (seekbarDuration - displayedSeekbarPosition).coerceAtLeast(0f)

            SeekbarWithTimers(
              position = displayedSeekbarPosition,
              committedPosition = precisePosition,
              externalScrubbing = isHorizontalSeekActive,
              duration = seekbarDuration,
              remaining = effectiveRemaining,
              onValueChange = {
                if (seekbarInteractionCancelled || seekbarInteractionGeneration != PlaybackSession.state.value.generation ||
                  (seekbarWindowedPreviewStarted && viewModel.seekThumbnailFrame.value.targetMs == null)
                ) {
                  return@SeekbarWithTimers
                }
                // Seeker emits one value and then finishes for a tap. Defer decoder preview
                // and pausing until another value arrives, so a tap commits only one seek.
                if (isSeeking) {
                  if (seekbarScrubWasPaused == null) {
                    val alreadyPaused = paused ?: false
                    seekbarScrubWasPaused = alreadyPaused
                    if (!alreadyPaused) {
                      viewModel.pause()
                    }
                  }
                  seekbarWindowedPreviewStarted = viewModel.previewWindowedSeekTo(it, seekbarScrubWasPaused ?: true)
                  if (!seekbarWindowedPreviewStarted) viewModel.seekPreviewTo(it)
                }
                isSeeking = true
                resetControlsTimestamp = System.currentTimeMillis()
              },
              onValueChangeFinished = { targetPosition ->
                val cancelled = seekbarInteractionCancelled || seekbarInteractionGeneration != PlaybackSession.state.value.generation ||
                  (seekbarWindowedPreviewStarted && viewModel.seekThumbnailFrame.value.targetMs == null)
                seekbarWindowedPreviewStarted = false
                val resumePlayback = seekbarScrubWasPaused == false
                seekbarScrubWasPaused = null
                isSeeking = false
                resetControlsTimestamp = System.currentTimeMillis()
                if (!cancelled) viewModel.seekFromScrub(targetPosition, resumePlayback = resumePlayback)
                viewModel.showControls()
              },
              onInteractionStarted = {
                if (!isSeeking) {
                  seekbarWindowedPreviewStarted = false
                  seekbarScrubWasPaused = null
                }
                seekbarInteractionCancelled = false
                seekbarInteractionGeneration = PlaybackSession.state.value.generation
              },
              onInteractionCancelled = {
                seekbarInteractionCancelled = true
                val restoreLegacyPlayback = !seekbarWindowedPreviewStarted && seekbarScrubWasPaused == false
                viewModel.cancelWindowedSeekPreview()
                if (restoreLegacyPlayback) viewModel.unpause()
                seekbarScrubWasPaused = null
                isSeeking = false
              },
              timersInverted = Pair(false, invertDuration),
              durationTimerOnCLick = {
                resetControlsTimestamp = System.currentTimeMillis()
                playerPreferences.invertDuration.set(!invertDuration)
              },
              positionTimerOnClick = {},
              chapters = seekbarChapters,
              skipSegments = skipSegmentsImmutable,
              paused = paused ?: false,
              seekbarStyle = seekbarStyle,
              useWavySeekbar = useWavySeekbar,
              timerTextColor = Color.White,
              loopStart = abLoopA?.toFloat(),
              loopEnd = abLoopB?.toFloat(),
              bufferDuration = stableDemuxerCacheTime.takeIf { showBufferedRange && it > 0f },
              cachedPreviewTimesMs = cachedPreviewTimes,
              isPortrait = isPortrait,
            )
          }

          AnimatedVisibility(
            visible = controlsShown && !areControlsLocked && !areSlidersShown,
            enter = buildControlsEnterH(controlsAnimStyle, reduceMotion, enterMs) { -it },
            exit = buildControlsExitH(controlsAnimStyle, reduceMotion, exitMs) { -it },
            modifier =
              Modifier
                .then(
                  if (showSystemStatusBar) {
                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                  } else {
                    Modifier
                  },
                ).then(
                  if (showSystemNavigationBar && !safeAreaWindow) {
                    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
                    Modifier.padding(
                      start = navBarPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                      end = navBarPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    )
                  } else {
                    Modifier
                  },
                ).constrainAs(topLeftControls) {
                  top.linkTo(parent.top, if (isPortrait) spacing.extraLarge else spacing.small)
                  start.linkTo(parent.start, spacing.large)
                  if (isPortrait) {
                    width = Dimension.fillToConstraints
                    end.linkTo(parent.end, spacing.large)
                  } else {
                    width = Dimension.fillToConstraints
                    end.linkTo(topRightControls.start, spacing.extraSmall)
                  }
                },
          ) {
            val showAiIndicators = aiEnabled
            val showRealtimeSubs = aiEnabled && realtimeSubsEnabled
            if (isPortrait) {
              TopPlayerControlsPortrait(
                mediaTitle = mediaTitle,
                onBackPress = onBackPress,
                onOpenSheet = onOpenSheet,
                viewModel = viewModel,
                isTranslatingSub = showAiIndicators && isTranslatingSub,
                isRealtimeSubsActive = showRealtimeSubs && isRealtimeSubsActive,
                realtimeSubsLanguage = realtimeSubsLanguage,
                realtimeSubsStatus = realtimeSubsStatus,
                translationStatus = translationStatus,
                translatingTrackName = translatingTrackName,
              )
            } else {
              TopLeftPlayerControlsLandscape(
                mediaTitle = mediaTitle,
                onBackPress = onBackPress,
                onOpenSheet = onOpenSheet,
                viewModel = viewModel,
                isTranslatingSub = showAiIndicators && isTranslatingSub,
                isRealtimeSubsActive = showRealtimeSubs && isRealtimeSubsActive,
                realtimeSubsLanguage = realtimeSubsLanguage,
                realtimeSubsStatus = realtimeSubsStatus,
                translationStatus = translationStatus,
                translatingTrackName = translatingTrackName,
              )
            }
          }

          AnimatedVisibility(
            visible = controlsShown && !areControlsLocked && !isPortrait && !areSlidersShown,
            enter = buildControlsEnterH(controlsAnimStyle, reduceMotion, enterMs) { it },
            exit = buildControlsExitH(controlsAnimStyle, reduceMotion, exitMs) { it },
            modifier =
              Modifier
                .then(
                  if (showSystemStatusBar) {
                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                  } else {
                    Modifier
                  },
                ).then(
                  if (showSystemNavigationBar && !safeAreaWindow) {
                    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
                    Modifier.padding(
                      start = navBarPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                      end = navBarPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    )
                  } else {
                    Modifier
                  },
                ).constrainAs(topRightControls) {
                  top.linkTo(parent.top, spacing.small)
                  end.linkTo(parent.end, spacing.large)
                },
          ) {
            TopRightPlayerControlsLandscape(
              buttons = topRightButtons,
              chapters = chapters,
              currentChapter = currentChapter,
              isSpeedNonOne = isSpeedNonOne,
              currentZoom = currentZoom,
              aspect = aspect,
              mediaTitle = mediaTitle,
              decoder = decoder,
              playbackSpeed = playbackSpeed ?: 1f,
              onBackPress = onBackPress,
              onOpenSheet = onOpenSheet,
              onOpenPanel = onOpenPanel,
              viewModel = viewModel,
              activity = playerActivity,
            )
          }

          AnimatedVisibility(
            visible = hasRightButtons && controlsShown && !areControlsLocked && !areSlidersShown,
            enter = buildControlsEnterH(controlsAnimStyle, reduceMotion, enterMs) { it },
            exit = buildControlsExitH(controlsAnimStyle, reduceMotion, exitMs) { it },
            modifier =
              Modifier
                .then(
                  if (showSystemNavigationBar && !safeAreaWindow) {
                    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
                    Modifier.padding(
                      start = navBarPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                      end = navBarPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    )
                  } else {
                    Modifier
                  },
                ).constrainAs(bottomRightControls) {
                  linkInBottomStack(BottomSlot.Buttons)
                  if (isPortrait && !buttonsBesidePlayback) {
                    start.linkTo(parent.start, spacing.large)
                    end.linkTo(parent.end, spacing.large)
                    width = Dimension.fillToConstraints
                  } else {
                    end.linkTo(parent.end, spacing.large)
                    if (buttonsBesidePlayback) {
                      start.linkTo(sharedPlaybackRowCenterX, sharedRowSideInset)
                    } else if (hasLeftButtons) {
                      start.linkTo(sharedPlaybackRowCenterX, spacing.smaller)
                    } else {
                      start.linkTo(parent.start, spacing.large)
                    }
                    width = Dimension.fillToConstraints
                  }
                }.onSizeChanged { bottomHeights.rightButtons = it.height },
          ) {
            if (isPortrait) {
              BottomPlayerControlsPortrait(
                buttons = portraitRightButtons,
                showVideoQualitySelector = automaticQualityButton,
                chapters = chapters,
                currentChapter = currentChapter,
                isSpeedNonOne = isSpeedNonOne,
                currentZoom = currentZoom,
                aspect = aspect,
                mediaTitle = mediaTitle,
                decoder = decoder,
                playbackSpeed = playbackSpeed ?: 1f,
                onBackPress = onBackPress,
                onOpenSheet = onOpenSheet,
                onOpenPanel = onOpenPanel,
                viewModel = viewModel,
                activity = playerActivity,
                alignToEnd = buttonsBesidePlayback,
                horizontalArrangement =
                  if (buttonsBesidePlayback) {
                    Arrangement.spacedBy(spacing.extraSmall, Alignment.End)
                  } else {
                    Arrangement.spacedBy(
                      spacing.medium,
                      Alignment.CenterHorizontally,
                    )
                  },
              )
            } else {
              val rowScrollState = rememberScrollState()
              BottomRightPlayerControlsLandscape(
                buttons = bottomRightButtons,
                showVideoQualitySelector = automaticQualityButton,
                chapters = chapters,
                currentChapter = currentChapter,
                isSpeedNonOne = isSpeedNonOne,
                currentZoom = currentZoom,
                aspect = aspect,
                mediaTitle = mediaTitle,
                decoder = decoder,
                playbackSpeed = playbackSpeed ?: 1f,
                onBackPress = onBackPress,
                onOpenSheet = onOpenSheet,
                onOpenPanel = onOpenPanel,
                viewModel = viewModel,
                activity = playerActivity,
                // Beside play/pause the group keeps its buttons at the screen edge and scrolls toward the centre.
                modifier = Modifier.fillMaxWidth().fadingHorizontalScroll(rowScrollState, reverseScrolling = true),
                horizontalArrangement =
                  Arrangement.spacedBy(spacing.extraSmall, Alignment.End),
              )
            }
          }

          AnimatedVisibility(
            visible = hasLeftButtons && controlsShown && !areControlsLocked && !areSlidersShown,
            enter = buildControlsEnterH(controlsAnimStyle, reduceMotion, enterMs) { -it },
            exit = buildControlsExitH(controlsAnimStyle, reduceMotion, exitMs) { -it },
            modifier =
              Modifier
                .then(
                  if (showSystemNavigationBar && !safeAreaWindow) {
                    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
                    Modifier.padding(
                      start = navBarPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                      end = navBarPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    )
                  } else {
                    Modifier
                  },
                ).constrainAs(bottomLeftControls) {
                  linkInBottomStack(BottomSlot.Buttons)
                  start.linkTo(parent.start, spacing.large)
                  width = Dimension.fillToConstraints
                  if (buttonsBesidePlayback) {
                    end.linkTo(sharedPlaybackRowCenterX, sharedRowSideInset)
                  } else if (hasRightButtons) {
                    end.linkTo(sharedPlaybackRowCenterX, spacing.smaller)
                  } else {
                    end.linkTo(parent.end, spacing.large)
                  }
                }.onSizeChanged { bottomHeights.leftButtons = it.height },
          ) {
            if (isPortrait) {
              BottomPlayerControlsPortrait(
                buttons = portraitLeftButtons,
                showVideoQualitySelector = false,
                chapters = chapters,
                currentChapter = currentChapter,
                isSpeedNonOne = isSpeedNonOne,
                currentZoom = currentZoom,
                aspect = aspect,
                mediaTitle = mediaTitle,
                decoder = decoder,
                playbackSpeed = playbackSpeed ?: 1f,
                onBackPress = onBackPress,
                onOpenSheet = onOpenSheet,
                onOpenPanel = onOpenPanel,
                viewModel = viewModel,
                activity = playerActivity,
                horizontalArrangement = Arrangement.spacedBy(spacing.extraSmall, Alignment.Start),
              )
            } else {
              val rowScrollState = rememberScrollState()
              BottomLeftPlayerControlsLandscape(
                buttons = bottomLeftButtons,
                chapters = chapters,
                currentChapter = currentChapter,
                isSpeedNonOne = isSpeedNonOne,
                currentZoom = currentZoom,
                aspect = aspect,
                mediaTitle = mediaTitle,
                decoder = decoder,
                playbackSpeed = playbackSpeed ?: 1f,
                onBackPress = onBackPress,
                onOpenSheet = onOpenSheet,
                onOpenPanel = onOpenPanel,
                viewModel = viewModel,
                activity = playerActivity,
                modifier = Modifier.fillMaxWidth().fadingHorizontalScroll(rowScrollState),
              )
            }
          }
        }
      }
    }

    val sheetShown by viewModel.sheetShown.collectAsState()
    val subtitles by viewModel.subtitleTracks.collectAsState(persistentListOf())
    val audioTracks by viewModel.audioTracks.collectAsState(persistentListOf())
    val speedPresets by playerPreferences.speedPresets.collectAsState()
    val sortedSpeedPresets =
      androidx.compose.runtime.remember(
        speedPresets,
      ) { speedPresets.map { it.toFloat() }.sorted() }

    PlayerSheets(
      viewModel = viewModel,
      sheetShown = sheetShown,
      subtitles = subtitles.toImmutableList(),
      onAddSubtitle = viewModel::addSubtitle,
      onToggleSubtitle = viewModel::toggleSubtitle,
      isSubtitleSelected = viewModel::isSubtitleSelected,
      subtitleSelectionIndicator = viewModel::subtitleSelectionIndicator,
      onRemoveSubtitle = viewModel::removeSubtitle,
      audioTracks = audioTracks.toImmutableList(),
      onAddAudio = viewModel::addAudio,
      onSelectAudio = viewModel::selectAudioTrack,
      chapter = currentChapter?.let(chapters::getOrNull),
      chapters = chapters.toImmutableList(),
      onSeekToChapter = {
        chapters.getOrNull(it)?.let(viewModel::seekToPlaybackChapter)
      },
      decoder = decoder,
      onUpdateDecoder = { PlaybackSession.setPropertyString("hwdec", it.value) },
      speed = playbackSpeed ?: playerPreferences.defaultSpeed.get(),
      onSpeedChange = { PlaybackSession.setPropertyFloat("speed", it.toFixed(2)) },
      onMakeDefaultSpeed = { playerPreferences.defaultSpeed.set(it.toFixed(2)) },
      onAddSpeedPreset = { playerPreferences.speedPresets += it.toFixed(2).toString() },
      onRemoveSpeedPreset = { playerPreferences.speedPresets -= it.toFixed(2).toString() },
      onResetSpeedPresets = playerPreferences.speedPresets::delete,
      speedPresets = sortedSpeedPresets,
      onResetDefaultSpeed = {
        PlaybackSession.setPropertyFloat("speed", playerPreferences.defaultSpeed.deleteAndGet().toFixed(2))
      },
      onStartSleepTimer = viewModel::startTimer,
      onOpenPanel = onOpenPanel,
      onShowSheet = onOpenSheet,
      onDismissRequest = { onOpenSheet(Sheets.None) },
    )

    val panel by viewModel.panelShown.collectAsState()
    LaunchedEffect(sheetShown, panel) {
      if (sheetShown != Sheets.None || panel != Panels.None) isPlayerDrawerShown = false
    }
    PlayerPanels(
      panelShown = panel,
      viewModel = viewModel,
      onDismissRequest = { onOpenPanel(Panels.None) },
    )


    val activePlayerDrawerButtons =
      remember(
        isSpeedNonOne,
        currentZoom,
        repeatMode,
        shuffleEnabled,
        transformState,
        abLoopA,
        abLoopB,
        isHdrOutputEnabled,
        isAmbientEnabled,
        isPostProcessingEnabled,
        backgroundPlaybackEnabled,
        statisticsPage,
        mediaScopesState.overlayVisible,
      ) {
        buildSet {
          if (isSpeedNonOne) add(PlayerButton.PLAYBACK_SPEED)
          if (kotlin.math.abs(currentZoom) >= 0.005f) add(PlayerButton.VIDEO_ZOOM)
          if (repeatMode != app.gyrolet.mpvrx.ui.player.RepeatMode.OFF) add(PlayerButton.REPEAT_MODE)
          if (shuffleEnabled) add(PlayerButton.SHUFFLE)
          if (transformState.isMirrored) add(PlayerButton.MIRROR)
          if (transformState.isVerticalFlipped) add(PlayerButton.VERTICAL_FLIP)
          if (abLoopA != null || abLoopB != null) add(PlayerButton.AB_LOOP)
          if (isHdrOutputEnabled) add(PlayerButton.HDR_MODE)
          if (isAmbientEnabled) add(PlayerButton.AMBIENT_MODE)
          if (isPostProcessingEnabled) add(PlayerButton.POST_PROCESSING)
          if (backgroundPlaybackEnabled) add(PlayerButton.BACKGROUND_PLAYBACK)
          if (statisticsPage == 6) add(PlayerButton.TIME_NETWORK)
          if (mediaScopesState.overlayVisible) add(PlayerButton.SCOPES)
        }
      }

    if (showControlsDrawer) {
      PlayerButtonTheme {
        PlayerControlDrawer(
          buttons = playerDrawerButtons,
          activeButtons = activePlayerDrawerButtons,
          controlsVisible =
            controlsShown &&
              !areControlsLocked &&
              !areSlidersShown &&
              sheetShown == Sheets.None &&
              panel == Panels.None,
          panelVisible = isPlayerDrawerShown,
          onPanelVisibilityChanged = setPlayerDrawerShown,
          renderButton = { button ->
            RenderPlayerButton(
              button = button,
              chapters = chapters,
              currentChapter = currentChapter,
              isPortrait = isPortrait,
              isSpeedNonOne = isSpeedNonOne,
              currentZoom = currentZoom,
              aspect = aspect,
              mediaTitle = mediaTitle,
              borderless = true,
              decoder = decoder,
              playbackSpeed = playbackSpeed ?: 1f,
              onBackPress = onBackPress,
              onOpenSheet = onOpenSheet,
              onOpenPanel = onOpenPanel,
              viewModel = viewModel,
              activity = playerActivity,
              buttonSize = 44.dp,
              compact = true,
            )
          },
        )
      }
    }
      }
    }
  }
}

private const val MEMORY_STATS_SAMPLE_INTERVAL_MS = 5_000L

private data class ProcessMemorySnapshot(
  val totalPssBytes: Long,
  val javaHeapUsedBytes: Long,
  val javaHeapMaxBytes: Long,
  val nativeHeapBytes: Long,
)

private fun readProcessMemorySnapshot(): ProcessMemorySnapshot {
  val runtime = Runtime.getRuntime()
  val memoryInfo = Debug.MemoryInfo()
  Debug.getMemoryInfo(memoryInfo)
  return ProcessMemorySnapshot(
    totalPssBytes = memoryInfo.totalPss.toLong() * 1024L,
    javaHeapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
    javaHeapMaxBytes = runtime.maxMemory(),
    nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
  )
}

private data class CustomStatsSnapshot(
  val fileName: String,
  val renderContext: String,
  val video: String,
  val audio: String,
  val cpuPercent: Float,
  val processMemoryText: String,
  val playbackCacheText: String,
  val batteryPercentText: String,
  val batteryRateText: String,
  val batteryWattsText: String,
  val batteryTempText: String,
  val hdrActive: String,
  val sessionPlayTimeText: String,
  val decoderEfficiencyText: String,
  val thermalStateText: String,
  val peakTempText: String,
  val tempRiseText: String,
)

@Composable
private fun CustomStatsPageSixOverlay(
  viewModel: PlayerViewModel,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current.applicationContext
  val torrentState by viewModel.torrentState.collectAsState()
  val isHdrOutputEnabled by viewModel.isHdrScreenOutputEnabled.collectAsState()
  val hdrScreenMode by viewModel.hdrScreenMode.collectAsState()
  val hdrOutputText =
    stringResource(
      R.string.hdr_mode_output_diagnostic,
      stringResource(hdrScreenMode.shortTitleRes),
    )
  val processMemoryFormat = stringResource(R.string.diagnostics_process_memory_value)
  val playbackCacheFormat = stringResource(R.string.diagnostics_playback_cache_value)
  val stats by produceState(
    initialValue =
      CustomStatsSnapshot(
        fileName = "--",
        renderContext = "--",
        video = "--",
        audio = "--",
        cpuPercent = 0f,
        processMemoryText = "--",
        playbackCacheText = "--",
        batteryPercentText = "--%",
        batteryRateText = "Unknown",
        batteryWattsText = "-- W",
        batteryTempText = "--°C",
        hdrActive = "--",
        sessionPlayTimeText = "00:00:00",
        decoderEfficiencyText = "Unknown",
        thermalStateText = "Normal",
        peakTempText = "--°C",
        tempRiseText = "+0.0°C",
      ),
    isHdrOutputEnabled,
    hdrOutputText,
    processMemoryFormat,
    playbackCacheFormat,
  ) {
    var lastCpuMs = runCatching { android.os.Process.getElapsedCpuTime() }.getOrDefault(0L)
    var lastTimeMs = android.os.SystemClock.elapsedRealtime()
    var lastMemorySampleMs = lastTimeMs
    var memorySnapshot = withContext(Dispatchers.Default) { readProcessMemorySnapshot() }

    var startBatteryTemp: Float? = null
    var peakBatteryTemp = 0.0f
    var totalActivePlayTimeMs = 0L
    val processorCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    var smoothedCpuPercent = 0f
    var cpuSampleCount = 0

    while (true) {
      val fileName = runCatching { PlaybackSession.getPropertyString("media-title") ?: "--" }.getOrDefault("--")
      val currentVideoOutput =
        runCatching {
          PlaybackSession.getPropertyString("current-vo")
            ?: PlaybackSession.getPropertyString("vo")
            ?: "--"
        }.getOrDefault("--")
      val videoCodec = runCatching { PlaybackSession.getPropertyString("current-tracks/video/codec") ?: "--" }.getOrDefault("--")
      val audioCodec = runCatching { PlaybackSession.getPropertyString("current-tracks/audio/codec") ?: "--" }.getOrDefault("--")

      val currentCpuMs = runCatching { android.os.Process.getElapsedCpuTime() }.getOrDefault(lastCpuMs)
      val currentTimeMs = android.os.SystemClock.elapsedRealtime()
      val cpuDelta = (currentCpuMs - lastCpuMs).coerceAtLeast(0L)
      val timeDelta = (currentTimeMs - lastTimeMs).coerceAtLeast(1L)
      if (currentTimeMs - lastMemorySampleMs >= MEMORY_STATS_SAMPLE_INTERVAL_MS) {
        memorySnapshot =
          runCatching {
            withContext(Dispatchers.Default) { readProcessMemorySnapshot() }
          }.getOrDefault(memorySnapshot)
        lastMemorySampleMs = currentTimeMs
      }
      val rawCpuPercent =
        if (cpuSampleCount > 0) {
          ((cpuDelta.toFloat() / timeDelta.toFloat()) * 100f / processorCount)
            .coerceIn(0f, 100f)
        } else {
          0f
        }

      when (cpuSampleCount) {
        0 -> Unit
        1 -> smoothedCpuPercent = rawCpuPercent
        else -> smoothedCpuPercent = smoothedCpuPercent * 0.65f + rawCpuPercent * 0.35f
      }
      cpuSampleCount++

      val processMemoryText =
        String.format(
          processMemoryFormat,
          formatTorrentBytes(memorySnapshot.totalPssBytes),
          formatTorrentBytes(memorySnapshot.javaHeapUsedBytes),
          formatTorrentBytes(memorySnapshot.javaHeapMaxBytes),
          formatTorrentBytes(memorySnapshot.nativeHeapBytes),
        )
      val cacheDurationSeconds =
        runCatching { PlaybackSession.getPropertyDouble("demuxer-cache-duration") }
          .getOrNull()
          ?.takeIf { it.isFinite() && it >= 0.0 }
          ?: 0.0
      val packetCacheBytes =
        runCatching { PlaybackSession.getPropertyDouble("demuxer-cache-state/fw-bytes") }
          .getOrNull()
          ?.takeIf { it.isFinite() && it >= 0.0 }
          ?.toLong()
          ?: 0L
      val fileCacheBytes =
        runCatching { PlaybackSession.getPropertyDouble("demuxer-cache-state/file-cache-bytes") }
          .getOrNull()
          ?.takeIf { it.isFinite() && it >= 0.0 }
          ?.toLong()
          ?: 0L
      val playbackCacheText =
        String.format(
          playbackCacheFormat,
          cacheDurationSeconds,
          formatTorrentBytes(packetCacheBytes),
          formatTorrentBytes(fileCacheBytes),
        )

      val battery = readBatterySnapshot(context)
      val isPaused = runCatching { PlaybackSession.getPropertyBoolean("pause") }.getOrDefault(false) == true

      if (!isPaused) {
        totalActivePlayTimeMs += timeDelta
      }
      val playSecs = totalActivePlayTimeMs / 1000L
      val sessionPlayTimeText = String.format("%02d:%02d:%02d", playSecs / 3600, (playSecs % 3600) / 60, playSecs % 60)

      val currentTempText = battery.tempText.replace("°C", "").trim()
      val currentTemp = currentTempText.toFloatOrNull() ?: 0f

      if (startBatteryTemp == null && currentTemp > 0f) {
        startBatteryTemp = currentTemp
      }
      if (currentTemp > peakBatteryTemp) {
        peakBatteryTemp = currentTemp
      }

      val peakTempText = if (peakBatteryTemp > 0f) String.format("%.1f°C", peakBatteryTemp) else "--°C"
      val tempRiseText =
        if (startBatteryTemp != null) {
          val rise = currentTemp - startBatteryTemp
          String.format("%+.1f°C", rise)
        } else {
          "+0.0°C"
        }

      val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
      val thermalStatus =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
          powerManager?.currentThermalStatus ?: 0
        } else {
          0
        }
      val thermalStateText =
        when (thermalStatus) {
          0 -> "Normal"
          1 -> "Light Throttling"
          2 -> "Moderate Throttling"
          3 -> "Severe Throttling"
          4 -> "Critical Throttling"
          5 -> "Emergency!"
          6 -> "Overheating Shutdown!"
          else -> "Normal"
        }

      val currentHwdec = runCatching { PlaybackSession.getPropertyString("hwdec-current") ?: "no" }.getOrDefault("no")
      val gpuApi = runCatching { PlaybackSession.getPropertyString("gpu-api") ?: "--" }.getOrDefault("--")
      val gpuContext = runCatching { PlaybackSession.getPropertyString("gpu-context") ?: "--" }.getOrDefault("--")
      val renderContext = "$currentVideoOutput | $gpuApi | $gpuContext"
      val decoderEfficiencyText =
        when {
          currentHwdec == "no" || currentHwdec.isBlank() -> "Low (Software Decoding, CPU-heavy)"
          currentHwdec.contains("copy") -> "Moderate (Hardware-copy, GPU texture overhead)"
          else -> "High (Hardware Direct, $gpuApi backend)"
        }

      value =
        CustomStatsSnapshot(
          fileName = fileName,
          renderContext = renderContext,
          video = videoCodec,
          audio = audioCodec,
          cpuPercent = smoothedCpuPercent,
          processMemoryText = processMemoryText,
          playbackCacheText = playbackCacheText,
          batteryPercentText = battery.percentageText,
          batteryRateText = battery.rateText,
          batteryWattsText = battery.wattsText,
          batteryTempText = battery.tempText,
          hdrActive =
            runCatching {
              val sourceGamma = PlaybackSession.getPropertyString("video-params/gamma").orEmpty()
              val sourcePrimaries = PlaybackSession.getPropertyString("video-params/primaries").orEmpty()
              val sourcePeak = PlaybackSession.getPropertyDouble("video-params/sig-peak") ?: 0.0

              val isHdrSource =
                sourceGamma == "pq" ||
                  sourceGamma == "hlg" ||
                  (sourcePrimaries == "bt.2020" && sourcePeak > 1.0)

              val sourceLabel = if (isHdrSource) "HDR Source" else "SDR Source"
              val outputLabel =
                if (isHdrOutputEnabled) {
                  hdrOutputText
                } else {
                  "SDR Output"
                }

              "$sourceLabel | $outputLabel"
            }.getOrDefault("Unknown"),
          sessionPlayTimeText = sessionPlayTimeText,
          decoderEfficiencyText = decoderEfficiencyText,
          thermalStateText = thermalStateText,
          peakTempText = peakTempText,
          tempRiseText = tempRiseText,
        )

      lastCpuMs = currentCpuMs
      lastTimeMs = currentTimeMs

      delay(if (isPaused) 2000L else 1000L)
    }
  }

  Column(
    modifier =
      modifier
        .widthIn(max = 520.dp)
        .alpha(0.88f),
    verticalArrangement = Arrangement.spacedBy(1.dp),
  ) {
    val baseStyle =
      MaterialTheme.typography.bodySmall.copy(
        color = Color.White,
        fontSize = 8.sp,
        lineHeight = 10.sp,
        shadow =
          Shadow(
            color = Color.Black,
            offset =
              androidx.compose.ui.geometry
                .Offset(1.2f, 1.2f),
            blurRadius = 3f,
          ),
      )
    val headerStyle =
      baseStyle.copy(
        fontWeight = FontWeight.Bold,
        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        fontSize = 8.5.sp,
      )
    val labelStyle = baseStyle.copy(fontWeight = FontWeight.Bold)
    val valueStyle = baseStyle

    OutlinedText(stringResource(R.string.diagnostics_playback_decoder_header), style = headerStyle)
    OutlinedLabeled("File", stats.fileName, labelStyle, valueStyle)
    OutlinedLabeled(
      "Decoder & VO",
      "${stats.renderContext} | ${stats.video} | Eff: ${stats.decoderEfficiencyText}",
      labelStyle,
      valueStyle,
    )
    OutlinedLabeled("Audio", "${stats.audio} | HDR: ${stats.hdrActive}", labelStyle, valueStyle)

    when (val currentTorrentState = torrentState) {
      is TorrentStreamingState.Connecting -> {
        Spacer(modifier = Modifier.height(2.dp))
        OutlinedText(stringResource(R.string.diagnostics_torrent_header), style = headerStyle)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Box(modifier = Modifier.weight(1f)) {
            OutlinedLabeled(
              stringResource(R.string.diagnostics_torrent_peer_status),
              stringResource(
                R.string.diagnostics_torrent_peers_value,
                currentTorrentState.peers,
                currentTorrentState.seeds,
              ),
              labelStyle,
              valueStyle,
            )
          }
          Box(modifier = Modifier.weight(1f)) {
            OutlinedLabeled(
              stringResource(R.string.diagnostics_torrent_transfer),
              stringResource(
                R.string.diagnostics_torrent_transfer_value,
                formatTorrentSpeed(currentTorrentState.downloadSpeed),
                formatTorrentSpeed(currentTorrentState.uploadSpeed),
              ),
              labelStyle,
              valueStyle,
            )
          }
        }
      }
      is TorrentStreamingState.Streaming -> {
        val fileSize = currentTorrentState.fileSize.coerceAtLeast(0L)
        val downloadedBytes = currentTorrentState.downloadedBytes.coerceAtLeast(0L).coerceAtMost(fileSize)
        val bufferProgress =
          currentTorrentState.bufferProgress
            .takeIf { it.isFinite() }
            ?.coerceIn(0f, 1f)
            ?: 0f
        Spacer(modifier = Modifier.height(2.dp))
        OutlinedText(stringResource(R.string.diagnostics_torrent_header), style = headerStyle)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Box(modifier = Modifier.weight(1f)) {
            OutlinedLabeled(
              stringResource(R.string.diagnostics_torrent_peer_status),
              stringResource(
                R.string.diagnostics_torrent_peers_value,
                currentTorrentState.peers,
                currentTorrentState.seeds,
              ),
              labelStyle,
              valueStyle,
            )
          }
          Box(modifier = Modifier.weight(1f)) {
            OutlinedLabeled(
              stringResource(R.string.diagnostics_torrent_loaded),
              stringResource(
                R.string.diagnostics_torrent_loaded_value,
                formatTorrentBytes(downloadedBytes),
                formatTorrentBytes(fileSize),
                (bufferProgress * 100f).roundToInt(),
              ),
              labelStyle,
              valueStyle,
            )
          }
        }
        OutlinedLabeled(
          stringResource(R.string.diagnostics_torrent_transfer),
          stringResource(
            R.string.diagnostics_torrent_transfer_value,
            formatTorrentSpeed(currentTorrentState.downloadSpeed),
            formatTorrentSpeed(currentTorrentState.uploadSpeed),
          ),
          labelStyle,
          valueStyle,
        )
        LinearProgressIndicator(
          progress = { bufferProgress },
          modifier =
            Modifier
              .fillMaxWidth()
              .height(3.dp)
              .padding(vertical = 0.5.dp),
        )
      }
      is TorrentStreamingState.Error,
      TorrentStreamingState.Idle,
      -> Unit
    }

    Spacer(modifier = Modifier.height(2.dp))
    OutlinedText(stringResource(R.string.diagnostics_power_thermals_header), style = headerStyle)
    OutlinedLabeled(
      "Battery",
      "${stats.batteryPercentText} | ${stats.batteryWattsText} | Rate: ${stats.batteryRateText}",
      labelStyle,
      valueStyle,
    )
    OutlinedLabeled(
      "Temp",
      "${stats.batteryTempText} (Peak: ${stats.peakTempText} | Rise: ${stats.tempRiseText})",
      labelStyle,
      valueStyle,
    )
    OutlinedLabeled("Thermal", stats.thermalStateText, labelStyle, valueStyle)

    Spacer(modifier = Modifier.height(2.dp))
    OutlinedText(stringResource(R.string.diagnostics_session_header), style = headerStyle)
    OutlinedLabeled("Active", stats.sessionPlayTimeText, labelStyle, valueStyle)

    LinearProgressIndicator(
      progress = { stats.cpuPercent / 100f },
      modifier =
        Modifier
          .fillMaxWidth()
          .height(3.dp)
          .padding(vertical = 0.5.dp),
    )
    OutlinedLabeled(stringResource(R.string.diagnostics_app_cpu), "${stats.cpuPercent.toInt()}%", labelStyle, valueStyle)

    Spacer(modifier = Modifier.height(2.dp))
    OutlinedText(stringResource(R.string.diagnostics_memory_cache_header), style = headerStyle)
    OutlinedLabeled(
      stringResource(R.string.diagnostics_process_memory),
      stats.processMemoryText,
      labelStyle,
      valueStyle,
    )
    OutlinedLabeled(
      stringResource(R.string.diagnostics_playback_cache),
      stats.playbackCacheText,
      labelStyle,
      valueStyle,
    )
  }
}

@Composable
private fun OutlinedText(
  text: String,
  style: androidx.compose.ui.text.TextStyle,
) {
  Box {
    Text(
      text = text,
      style =
        style.copy(
          color = Color.Black,
          shadow = null,
          drawStyle =
            Stroke(
              width = with(LocalDensity.current) { 1.2.dp.toPx() },
              join = StrokeJoin.Round,
            ),
        ),
    )
    Text(
      text = text,
      style = style,
    )
  }
}

private const val UNMEASURED_HEIGHT = -1

/** Last measured heights of the bottom-cluster layers, kept while a layer is hidden. */
@Stable
private class BottomClusterHeights {
  var rightButtons by mutableIntStateOf(UNMEASURED_HEIGHT)
  var leftButtons by mutableIntStateOf(UNMEASURED_HEIGHT)
  var seekbar by mutableIntStateOf(UNMEASURED_HEIGHT)
  var leftCustomRow by mutableIntStateOf(UNMEASURED_HEIGHT)
  var rightCustomRow by mutableIntStateOf(UNMEASURED_HEIGHT)
}

// Used until a layer is first laid out: one 48dp touch-target row.
private val BottomClusterFallbackRowHeight = 48.dp
private val CustomButtonsRowInsetHorizontal = 8.dp
private val CustomButtonsRowInsetVertical = 8.dp
private val CustomButtonsRowFallbackHeight = 36.dp + CustomButtonsRowInsetVertical * 2

private val FullScreenScrimBrush =
  Brush.verticalGradient(
    Pair(0f, Color.Black),
    Pair(.4f, Color.Transparent),
    Pair(.6f, Color.Transparent),
    Pair(1f, Color.Black),
  )

/**
 * Subtle edge vignette for the control bars: darkening confined to the top and bottom of the
 * frame so white controls stay legible over bright footage without dimming the whole picture.
 */
private val ControlsEdgeVignetteBrush =
  Brush.verticalGradient(
    0.00f to Color.Black.copy(alpha = 0.55f),
    0.06f to Color.Black.copy(alpha = 0.38f),
    0.14f to Color.Black.copy(alpha = 0.18f),
    0.22f to Color.Transparent,
    0.62f to Color.Transparent,
    0.74f to Color.Black.copy(alpha = 0.16f),
    0.86f to Color.Black.copy(alpha = 0.36f),
    0.95f to Color.Black.copy(alpha = 0.52f),
    1.00f to Color.Black.copy(alpha = 0.58f),
  )

@Composable
private fun OutlinedLabeled(
  label: String,
  value: String,
  labelStyle: androidx.compose.ui.text.TextStyle,
  valueStyle: androidx.compose.ui.text.TextStyle,
) {
  val annotated =
    buildAnnotatedString {
      withStyle(SpanStyle(fontWeight = labelStyle.fontWeight)) {
        append("$label: ")
      }
      withStyle(SpanStyle(fontWeight = valueStyle.fontWeight)) {
        append(value)
      }
    }
  Box {
    Text(
      text = annotated,
      style =
        labelStyle.copy(
          color = Color.Black,
          shadow = null,
          drawStyle =
            Stroke(
              width = with(LocalDensity.current) { 1.2.dp.toPx() },
              join = StrokeJoin.Round,
            ),
        ),
    )
    Text(
      text = annotated,
      style = labelStyle,
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CustomControlButton(
  label: String,
  liquidGlass: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  if (liquidGlass) {
    LiquidPillButton(
      onClick = onClick,
      onLongClick = onLongClick,
      height = 36.dp,
      horizontalPadding = 12.dp,
    ) {
      Text(
        text = label,
        modifier = Modifier.basicMarquee(),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        maxLines = 1,
        softWrap = false,
      )
    }
  } else {
    val buttonInteractionSource = remember { MutableInteractionSource() }
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f).asPlayerButtonBackground(),
      contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f).asPlayerButtonBackground()),
      modifier =
        Modifier
          .clip(CircleShape)
          .combinedClickable(
            interactionSource = buttonInteractionSource,
            indication = ripple(),
            onClick = onClick,
            onLongClick = onLongClick,
          ),
    ) {
      Text(
        text = label,
        modifier =
          Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .basicMarquee(),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        maxLines = 1,
        softWrap = false,
      )
    }
  }
}
