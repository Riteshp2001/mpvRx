/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.components

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.format.DateUtils
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.thumbnail.EmbeddedArtworkResolver
import app.gyrolet.mpvrx.domain.thumbnail.getArtwork
import app.gyrolet.mpvrx.domain.thumbnail.isLocalArtworkSource
import app.gyrolet.mpvrx.ui.player.EmbeddedMotionArtwork
import app.gyrolet.mpvrx.ui.player.MotionArtworkImage
import app.gyrolet.mpvrx.ui.player.rememberMotionArtworkDrawable
import app.gyrolet.mpvrx.presentation.components.LiquidGlassStyle
import app.gyrolet.mpvrx.presentation.components.LiquidGlassSurface
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.browser.NavigationBarState
import app.gyrolet.mpvrx.ui.browser.rememberMiniPlayerShape
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.AnimatedIcon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.DeclaredPlaybackMediaKind
import app.gyrolet.mpvrx.ui.player.LocalInAppMusicSheet
import app.gyrolet.mpvrx.ui.player.MediaPlaybackService
import app.gyrolet.mpvrx.ui.player.PlaybackPhase
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.ui.player.PlayerSheetDestination
import app.gyrolet.mpvrx.ui.player.PlayerSheetTransitions
import app.gyrolet.mpvrx.ui.player.TrackNode
import app.gyrolet.mpvrx.ui.player.declaredMediaKind
import app.gyrolet.mpvrx.ui.player.inAppMusicSheetDrag
import app.gyrolet.mpvrx.ui.player.playerSheetAnchor
import app.gyrolet.mpvrx.ui.player.swipeUpToFullPlayer
import app.gyrolet.mpvrx.ui.player.toObject
import app.gyrolet.mpvrx.ui.player.controls.components.AbLoopIcon
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
  val isServiceRunning by MediaPlaybackService.foregroundActive.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val sessionState by PlaybackSession.state.collectAsStateWithLifecycle()
  val playerPreferences: PlayerPreferences = koinInject()
  val enableVideoMiniPlayer by playerPreferences.enableVideoMiniPlayer.collectAsState()

  val backstack = LocalBackStack.current
  val currentScreen = backstack.lastOrNull()
  val isSettingsScreen = currentScreen != null &&
    (currentScreen.javaClass.name.startsWith("app.gyrolet.mpvrx.ui.preferences") ||
     currentScreen.javaClass.name.startsWith("app.gyrolet.mpvrx.ui.editor"))

  val currentItem = sessionState.currentItem
  val trackListNode by PlaybackSession.propNode["track-list"].collectAsStateWithLifecycle()
  val json: Json = koinInject()

  val tracks = remember(trackListNode) { trackListNode?.toObject<List<TrackNode>>(json).orEmpty() }
  val hasRealVideo = tracks.any { it.isVideo && !it.isAlbumArtwork }
  val hasAlbumArt = tracks.any { it.isAlbumArtwork }

  val isAudioOnlyItem =
    when (currentItem?.declaredMediaKind() ?: DeclaredPlaybackMediaKind.UNKNOWN) {
      DeclaredPlaybackMediaKind.AUDIO -> true
      DeclaredPlaybackMediaKind.VIDEO -> false
      DeclaredPlaybackMediaKind.UNKNOWN -> !hasRealVideo && (hasAlbumArt || tracks.any { it.isAudio })
    }

  val isMiniPlayerAllowed = isAudioOnlyItem ||
    (enableVideoMiniPlayer && !app.gyrolet.mpvrx.ui.tv.isTelevision())

  // A full-player collapse lands here before the service flips to foreground; showing the mini
  // player only after that would make it pop out and slide back in under the finished morph.
  val collapseHoldId = PlayerSheetTransitions.collapsedMediaId?.takeIf { it == currentItem?.stableId }
  val expansionHoldId = PlayerSheetTransitions.expandingMediaId?.takeIf { it == currentItem?.stableId }
  val sheetMotion = PlayerSheetTransitions.motion?.takeIf { it.endpoints.full.mediaId == currentItem?.stableId }
  LaunchedEffect(collapseHoldId) {
    val heldId = collapseHoldId ?: return@LaunchedEffect
    withTimeoutOrNull(2_000L) {
      MediaPlaybackService.foregroundActive.first { it }
    }
    PlayerSheetTransitions.releaseCollapsedHold(heldId)
  }

  val hasActiveMedia = (isServiceRunning || collapseHoldId != null || expansionHoldId != null) && currentItem != null &&
    !isSettingsScreen &&
    isMiniPlayerAllowed &&
    sessionState.phase != PlaybackPhase.IDLE &&
    sessionState.phase != PlaybackPhase.STOPPING &&
    sessionState.phase != PlaybackPhase.UNINITIALIZED &&
    sessionState.phase != PlaybackPhase.ERROR

  // A tossed-away card is already off-screen when it reports in, so it leaves without an exit
  // animation and the session ends only then, never re-laying out the card mid-flight.
  var dismissedMediaId by remember { mutableStateOf<String?>(null) }
  var exitAfterToss by remember { mutableStateOf(false) }
  LaunchedEffect(dismissedMediaId, hasActiveMedia) {
    val dismissed = dismissedMediaId ?: return@LaunchedEffect
    // Playback that refused to stop must never be left without its controls.
    if (hasActiveMedia) delay(DISMISS_RESTORE_TIMEOUT_MS)
    if (dismissedMediaId == dismissed) dismissedMediaId = null
  }
  val isMediaActive = hasActiveMedia && dismissedMediaId != currentItem.stableId
  LaunchedEffect(isMediaActive) { if (isMediaActive) exitAfterToss = false }
  val dismissPlayer: () -> Unit = remember(context) {
    {
      exitAfterToss = true
      dismissedMediaId = PlaybackSession.state.value.currentItem?.stableId
      context.startService(
        Intent(context, MediaPlaybackService::class.java).setAction(
          MediaPlaybackService.ACTION_NOTIFICATION_STOP,
        ),
      )
    }
  }

  // Keep the mini player alive while browser selection mode is active. Its outer
  // placement is lifted above the selection actions instead of hiding/overlapping.
  val visibilityState = remember { MutableTransitionState(false) }
  visibilityState.targetState = isMediaActive
  val isMiniPlayerVisible = visibilityState.currentState || visibilityState.targetState
  SideEffect {
    NavigationBarState.isMiniPlayerVisible = isMiniPlayerVisible
    NavigationBarState.miniPlayerConnectionEnabled = visibilityState.targetState
  }
  DisposableEffect(Unit) {
    onDispose {
      NavigationBarState.isMiniPlayerVisible = false
      NavigationBarState.miniPlayerConnectionEnabled = false
    }
  }

  val reduceMotion = AppMotion.shouldReduceMotion()
  AnimatedVisibility(
    visibleState = visibilityState,
    enter = if (collapseHoldId != null || sheetMotion != null) {
      EnterTransition.None
    } else if (reduceMotion) {
      EnterTransition.None
    } else {
      slideInVertically(
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        initialOffsetY = { it / 2 },
      ) + fadeIn(animationSpec = tween(240))
    },
    exit = when {
      expansionHoldId != null || sheetMotion != null || exitAfterToss -> ExitTransition.None
      reduceMotion -> ExitTransition.None
      else ->
        slideOutVertically(
          animationSpec = tween(260, easing = FastOutLinearInEasing),
          targetOffsetY = { it / 2 },
        ) + fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing))
    },
    modifier = modifier,
  ) {
    MiniPlayerContent(
      context = context,
      enableVideoMiniPlayer = enableVideoMiniPlayer,
      isAudioOnlyItem = isAudioOnlyItem,
      hasRealVideo = hasRealVideo,
      detachedPlaybackActive = isServiceRunning && expansionHoldId == null,
      onDismiss = dismissPlayer,
    )
  }
}

private const val DISMISS_RESTORE_TIMEOUT_MS = 3_000L

/** Tilt per card width of travel; the card pivots at its bottom like a held card being thrown. */
private const val DISMISS_TILT_DEGREES = 8f
private const val DISMISS_MAX_TILT_DEGREES = 14f

/** Slightly underdamped, so a short drag wobbles home instead of sliding back flat. */
private val DismissSettleSpring = spring(
  dampingRatio = 0.6f,
  stiffness = 420f,
  visibilityThreshold = 0.5f,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MiniPlayerContent(
  context: Context,
  enableVideoMiniPlayer: Boolean,
  isAudioOnlyItem: Boolean,
  hasRealVideo: Boolean,
  detachedPlaybackActive: Boolean,
  onDismiss: () -> Unit,
) {
  val sessionState by PlaybackSession.state.collectAsStateWithLifecycle()
  val queueState by PlaybackSession.queue.collectAsStateWithLifecycle()
  val currentItem = sessionState.currentItem
  val paused by PlaybackSession.propBoolean["pause"].collectAsStateWithLifecycle()
  val rawMediaTitle by PlaybackSession.propString["media-title"].collectAsStateWithLifecycle()
  val duration by PlaybackSession.propInt["duration"].collectAsStateWithLifecycle()
  val positionState = PlaybackSession.propInt["time-pos"].collectAsStateWithLifecycle()
  val videoAspectRaw by PlaybackSession.propDouble["video-params/aspect"].collectAsStateWithLifecycle()
  val videoWidth by PlaybackSession.propLong["video-params/w"].collectAsStateWithLifecycle()
  val videoHeight by PlaybackSession.propLong["video-params/h"].collectAsStateWithLifecycle()
  val videoRotate by PlaybackSession.propInt["video-params/rotate"].collectAsStateWithLifecycle()
  val loopA by PlaybackSession.propDouble["ab-loop-a"].collectAsStateWithLifecycle()
  val loopB by PlaybackSession.propDouble["ab-loop-b"].collectAsStateWithLifecycle()
  val isLoopASet = loopA?.let { it.isFinite() && it >= 0.0 } == true
  val isLoopBSet = loopB?.let { it.isFinite() && it >= 0.0 } == true

  val isPlaying = paused == false
  val activeBook by app.gyrolet.mpvrx.ui.player.AudiobookPlayback.book.collectAsStateWithLifecycle()
  val audiobook = activeBook?.takeIf { it.book.id == currentItem?.audiobook?.bookId }
  val isAudiobook = currentItem?.audiobook != null

  val bookOffsetSec = currentItem?.audiobook?.let { audiobook?.positionInBook(it.trackId, 0) }?.div(1000f) ?: 0f
  val totalDurSec = if (audiobook != null && audiobook.durationMs > 0) audiobook.durationMs / 1000f else (duration?.toFloat() ?: 0f)
  // Position ticks every second. Read it only where it is shown (the progress fill draws it, and
  // audiobooks print it) so playback does not recompose the whole mini player each second.
  fun totalPosSec(): Float {
    val currentPosSec = positionState.value?.toFloat() ?: 0f
    return if (audiobook != null) (bookOffsetSec + currentPosSec).coerceIn(0f, audiobook.durationMs / 1000f) else currentPosSec
  }
  fun progressFraction(): Float = if (totalDurSec > 0f) (totalPosSec() / totalDurSec).coerceIn(0f, 1f) else 0f

  val isYouTubeSource = app.gyrolet.mpvrx.utils.media.HttpUtils.isYouTubeUrl(currentItem?.originalUri)
  val title =
    audiobook?.book?.title
      ?: queueState.currentItem?.title?.takeIf { queueState.isExplicitQueue && it.isNotBlank() }
      ?: rawMediaTitle?.takeIf { it.isNotBlank() && (!isYouTubeSource || !app.gyrolet.mpvrx.utils.media.HttpUtils.isLikelyJunkTitle(it)) }
      ?: currentItem?.title?.takeIf { it.isNotBlank() }
      ?: "Media Track"

  // Service ownership changes before the Activity animation ends. Keep the same content/height
  // through that handoff; changing to the audio row destroys the Surface and visibly resizes it.
  val isVideoMode = !isAudioOnlyItem && enableVideoMiniPlayer &&
    (hasRealVideo || currentItem?.declaredMediaKind() == DeclaredPlaybackMediaKind.VIDEO)

  val coverArtPath =
    currentItem?.originalUri?.takeIf { it.isNotBlank() }
      ?: currentItem?.playableUri?.takeIf { it.isNotBlank() }
  val effectiveArtworkUri = currentItem?.artworkUri
    ?: audiobook?.book?.coverUri?.takeIf { it.isNotBlank() }
  val artwork =
    rememberMiniPlayerArtwork(
      pathOrUri = if (isAudioOnlyItem) coverArtPath else null,
      artworkUri = if (isAudioOnlyItem) effectiveArtworkUri else null,
    )
  val coverArt = artwork?.bitmap

  val coroutineScope = rememberCoroutineScope()
  val videoSurface = remember { arrayOfNulls<MiniPlayerSurfaceView>(1) }
  var dismissOffset by remember { mutableFloatStateOf(0f) }
  val dismissMotion = remember { arrayOfNulls<Job>(1) }
  // Once thrown, the card ignores further touches and is never pulled back by state changes.
  var tossed by remember { mutableStateOf(false) }
  // Resting left, right and window width, so the throw always clears the screen edge.
  val restBounds = remember { FloatArray(3) }
  val itemId = currentItem?.stableId
  LaunchedEffect(itemId) {
    if (itemId == null || tossed) return@LaunchedEffect
    dismissMotion[0]?.cancel()
    dismissOffset = 0f
  }
  val actionHaptics = app.gyrolet.mpvrx.ui.utils.rememberAppHaptics()
  val density = LocalDensity.current
  val dismissThresholdPx = with(density) { 100.dp.toPx() }
  val dismissFlingVelocityPx = with(density) { 700.dp.toPx() }
  val tossClearancePx = with(density) { 32.dp.toPx() }
  val buttonTossVelocityPx = with(density) { 1_600.dp.toPx() }
  val anticipationPx = with(density) { 14.dp.toPx() }
  val towardCloseButton = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
  val reduceMotion = AppMotion.playerReducedMotion()
  val currentOnDismiss by rememberUpdatedState(onDismiss)
  val musicSheet = LocalInAppMusicSheet.current
  val useInAppSheet = isAudioOnlyItem && musicSheet?.isAvailable == true
  val isTelevision = DeviceFormFactor.isTelevision(context)

  val launchPlayer = remember(context, isAudioOnlyItem, reduceMotion, musicSheet) {
    launch@{
      // Music opens as a live sheet in this window; video and TV keep the player screen.
      if (isAudioOnlyItem && musicSheet?.isAvailable == true) return@launch musicSheet.expand()
      val mediaId = PlaybackSession.state.value.currentItem?.stableId
      if (!PlayerSheetTransitions.holdExpansion(mediaId)) return@launch false
      videoSurface[0]?.setPlaybackActive(false)

      fun openPlayer(sheetArrived: Boolean): Boolean {
        val intent = Intent(context, PlayerActivity::class.java).apply {
          action = MediaPlaybackService.ACTION_OPEN_PLAYER
          putExtra("is_audio", isAudioOnlyItem)
          putExtra("internal_launch", true)
          putExtra("launch_source", "mini_player")
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val enterAnimation = if (sheetArrived || reduceMotion) 0 else R.anim.slide_in_up
        try {
          // Supply the animation at launch; setting it only in the destination's onCreate can
          // leave a default window animation running underneath the Compose sheet transition.
          context.startActivity(intent, ActivityOptions.makeCustomAnimation(context, enterAnimation, 0).toBundle())
        } catch (error: Exception) {
          PlayerSheetTransitions.releaseExpansionHold()
          android.util.Log.e("MiniPlayer", "Unable to expand player", error)
          return false
        }
        if (context is Activity && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          @Suppress("DEPRECATION")
          context.overridePendingTransition(enterAnimation, 0)
        }
        return true
      }

      // The browser is idle and already on screen, so it runs the whole expansion. PlayerActivity
      // is created only once the sheet rests full screen, so its startup never stalls the motion.
      val animateSheet = isAudioOnlyItem && PlayerSheetTransitions.begin(PlayerSheetDestination.FULL, mediaId) { arrived ->
        val stillVisible = (context as? androidx.lifecycle.LifecycleOwner)
          ?.lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) != false
        when {
          arrived -> if (!openPlayer(sheetArrived = true)) PlayerSheetTransitions.abandonExpansion()
          // The morph could not run; still honor the tap unless the user already left the app.
          stillVisible -> openPlayer(sheetArrived = false)
          else -> PlayerSheetTransitions.releaseExpansionHold()
        }
      }
      animateSheet || openPlayer(sheetArrived = false)
    }
  }

  val miniPlayerShape = rememberMiniPlayerShape()

  /**
   * Throws the card off the screen edge in [direction], keeping the release velocity and tilt.
   * Playback is silenced at once; the session itself ends once the card has left the screen.
   */
  fun tossAway(direction: Float, velocity: Float, anticipate: Boolean) {
    if (tossed) return
    tossed = true
    actionHaptics.confirm()
    PlaybackSession.setPropertyBoolean("pause", true)
    dismissMotion[0]?.cancel()
    dismissMotion[0] = coroutineScope.launch {
      try {
        if (!reduceMotion) {
          if (anticipate) {
            // A small wind-up away from the throw makes the button toss read as deliberate.
            animate(dismissOffset, -direction * anticipationPx, animationSpec = tween(110, easing = FastOutSlowInEasing)) { value, _ ->
              dismissOffset = value
            }
          }
          val (left, right, windowWidth) = restBounds
          val clearance = (right - left) * 0.2f + tossClearancePx
          val target = if (direction > 0f) windowWidth - left + clearance else -(right + clearance)
          val launchVelocity = if (anticipate) direction * buttonTossVelocityPx else velocity
          // Ends once well past the edge instead of creeping through the spring's tail.
          val toss = spring(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = clearance * 0.5f)
          animate(dismissOffset, target, launchVelocity, toss) { value, _ -> dismissOffset = value }
        }
      } finally {
        currentOnDismiss()
      }
    }
  }

  /** Throws the card past the edge it was dragged toward, or wobbles it back home. */
  fun settleDismissOffset(velocity: Float) {
    if (tossed) return
    val offset = dismissOffset
    val towardEdge = offset != 0f && sign(velocity) == sign(offset)
    val flung = towardEdge && abs(velocity) > dismissFlingVelocityPx
    val pulledBack = !towardEdge && abs(velocity) > dismissFlingVelocityPx
    if (offset != 0f && (flung || (abs(offset) > dismissThresholdPx && !pulledBack))) {
      tossAway(sign(offset), velocity, anticipate = false)
      return
    }
    dismissMotion[0]?.cancel()
    dismissMotion[0] = coroutineScope.launch {
      if (reduceMotion) {
        dismissOffset = 0f
      } else {
        animate(offset, 0f, velocity, DismissSettleSpring) { value, _ -> dismissOffset = value }
      }
    }
  }

  // TV: the title area is the "open player" button, a focus stop beside the playback buttons.
  val tvOpenPlayer =
    if (isTelevision) {
      Modifier
        .tvFocusHighlight(RoundedCornerShape(12.dp))
        .clickable { if (!tossed) launchPlayer() }
        .padding(horizontal = 8.dp, vertical = 4.dp)
    } else {
      Modifier
    }

  LiquidGlassSurface(
    modifier = Modifier
      .onGloballyPositioned { coordinates ->
        NavigationBarState.miniPlayerHeight = with(density) { coordinates.size.height.toDp() }
        // Measured before the toss offset, so these stay the card's resting bounds.
        val bounds = coordinates.boundsInRoot()
        restBounds[0] = bounds.left
        restBounds[1] = bounds.right
        restBounds[2] = coordinates.findRootCoordinates().size.width.toFloat()
      }
      .absoluteOffset { IntOffset(dismissOffset.roundToInt(), 0) }
      .graphicsLayer {
        val width = size.width.takeIf { it > 0f } ?: 1f
        rotationZ = (dismissOffset / width * DISMISS_TILT_DEGREES)
          .coerceIn(-DISMISS_MAX_TILT_DEGREES, DISMISS_MAX_TILT_DEGREES)
        transformOrigin = TransformOrigin(0.5f, 1f)
      }
      .then(
        if (isAudioOnlyItem) {
          Modifier.playerSheetAnchor(
            PlayerSheetDestination.MINI,
            currentItem?.stableId,
            miniPlayerShape,
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
          )
        } else {
          Modifier
        },
      )
      .clip(miniPlayerShape)
      .then(
        when {
          // A remote cannot drag; the title and the buttons cover expanding and dismissing.
          isTelevision -> Modifier
          useInAppSheet ->
            // The live sheet follows the finger from the first pixel; touching down starts warming it.
            Modifier.inAppMusicSheetDrag(
              state = musicSheet,
              collapsing = false,
              enabled = !tossed,
              onDown = musicSheet::prewarm,
            )
          else ->
            Modifier.swipeUpToFullPlayer(
              mediaId = currentItem?.stableId,
              animateSheet = isAudioOnlyItem,
              onExpand = { !tossed && launchPlayer() },
            )
        },
      )
      .pointerInput(dismissThresholdPx, dismissFlingVelocityPx, reduceMotion, isTelevision) {
        if (isTelevision) return@pointerInput
        val velocityTracker = VelocityTracker()
        var armed = false
        detectHorizontalDragGestures(
          onDragStart = {
            if (!tossed) {
              dismissMotion[0]?.cancel()
              velocityTracker.resetTracking()
              armed = abs(dismissOffset) > dismissThresholdPx
            }
          },
          onDragEnd = { settleDismissOffset(velocityTracker.calculateVelocity().x) },
          onDragCancel = { settleDismissOffset(0f) },
          onHorizontalDrag = { change, dragAmount ->
            change.consume()
            if (!tossed) {
              dismissOffset += dragAmount
              // The card moves with the finger, so track its offset rather than local positions.
              velocityTracker.addPosition(change.uptimeMillis, Offset(dismissOffset, 0f))
              val crossed = abs(dismissOffset) > dismissThresholdPx
              if (crossed != armed) {
                armed = crossed
                if (crossed) actionHaptics.tick()
              }
            }
          },
        )
      }
      .then(
        if (isTelevision) {
          // A clickable card is a single focus stop that hides its own buttons from the D-pad.
          Modifier.tvFocusHighlight(miniPlayerShape, clipToShape = false).focusGroup()
        } else {
          Modifier.clickable { if (!tossed) launchPlayer() }
        },
      ),
    shape = miniPlayerShape,
    style = LiquidGlassStyle.MiniPlayer,
    glassColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.30f),
    fallbackColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
    connectionProgress = NavigationBarState.dockProgress,
  ) {
    val progressColor = MaterialTheme.colorScheme.primary

    if (isVideoMode) {
      // mpv reports the pre-rotation shape; quarter-turned phone recordings are shown inverted,
      // matching the PiP aspect calculation.
      val sourceAspect = videoAspectRaw?.toFloat()?.takeIf { it.isFinite() && it > 0f }
        ?: if ((videoWidth ?: 0L) > 0L && (videoHeight ?: 0L) > 0L) {
          videoWidth!!.toFloat() / videoHeight!!.toFloat()
        } else {
          16f / 9f
        }
      val rotation = ((videoRotate ?: 0) % 360 + 360) % 360
      val aspect = if (rotation == 90 || rotation == 270) 1f / sourceAspect else sourceAspect

      // Constrain aspect ratio between 0.5 (portrait) and 2.4 (ultrawide)
      val safeAspect = aspect.coerceIn(0.5f, 2.39f)

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(76.dp)
          .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Video MPV Surface View Container
        Box(
          modifier = Modifier
            .fillMaxHeight()
            .aspectRatio(safeAspect)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black),
          contentAlignment = Alignment.Center,
        ) {
          AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { MiniPlayerSurfaceView(it).also { surface -> videoSurface[0] = surface } },
            onReset = null,
            update = { it.setPlaybackActive(detachedPlaybackActive) },
            onRelease = {
              it.releaseRenderer()
              if (videoSurface[0] === it) videoSurface[0] = null
            },
          )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Title and Status
        Column(
          modifier = Modifier.weight(1f).then(tvOpenPlayer),
          verticalArrangement = Arrangement.Center,
        ) {
          key(currentItem?.stableId ?: currentItem?.originalUri) {
            Text(
              text = title,
              style = MaterialTheme.typography.bodyMediumEmphasized,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = if (reduceMotion) Modifier else Modifier.basicMarquee(),
            )
          }
          Text(
            text = stringResource(if (isPlaying) R.string.notification_playing else R.string.downloads_paused),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
          )
        }

        // Control Buttons
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          IconButton(
            onClick = {
              actionHaptics.selection(true)
             context.startService(
                Intent(context, MediaPlaybackService::class.java).setAction(
                  MediaPlaybackService.ACTION_NOTIFICATION_PLAY_PAUSE,
                ),
              )
            },
            modifier = Modifier.size(48.dp),
          ) {
            AnimatedIcon(
              imageVector = if (isPlaying) Icons.RoundedFilled.Pause else Icons.RoundedFilled.PlayArrow,
              contentDescription = stringResource(if (isPlaying) R.string.audiobook_pause else R.string.ui_play),
              tint = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.size(26.dp),
              reduceMotion = reduceMotion,
            )
          }

          IconButton(
            onClick = { tossAway(towardCloseButton, velocity = 0f, anticipate = true) },
            modifier = Modifier.size(48.dp),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Close,
              contentDescription = stringResource(R.string.ui_close),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }
    } else {
      // Audio Mini Player View
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .drawBehind {
            val progressFraction = progressFraction()
            if (progressFraction > 0f) {
              val playedWidth = size.width * progressFraction
              drawRect(
                color = progressColor.copy(alpha = 0.22f),
                size = Size(
                  width = playedWidth,
                  height = size.height,
                ),
              )
              // Blur and vibrancy wash out a flat fill, so mark the played boundary explicitly.
              val edgeWidth = 2.dp.toPx().coerceAtMost(playedWidth)
              drawRect(
                color = progressColor.copy(alpha = 0.85f),
                topLeft = Offset(x = playedWidth - edgeWidth, y = 0f),
                size = Size(
                  width = edgeWidth,
                  height = size.height,
                ),
              )
            }
          }
          .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Music Cover Art
        Box(
          modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
          contentAlignment = Alignment.Center,
        ) {
          val artworkImageBitmap = remember(coverArt) { coverArt?.asImageBitmap() }
          val animatedDrawable = rememberMotionArtworkDrawable(artwork?.motionArtwork)
          if (animatedDrawable != null) {
            MotionArtworkImage(
              drawable = animatedDrawable,
              isPlaying = isPlaying,
              modifier = Modifier.fillMaxSize(),
            )
          } else if (artworkImageBitmap != null) {
            Image(
              bitmap = artworkImageBitmap,
              contentDescription = null,
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          } else {
            Icon(
              imageVector = Icons.RoundedFilled.Audiotrack,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(24.dp),
            )
          }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title & Track Status
        Column(
          modifier = Modifier.weight(1f).then(tvOpenPlayer),
          verticalArrangement = Arrangement.Center,
        ) {
          key(currentItem?.stableId ?: currentItem?.originalUri ?: audiobook?.book?.id) {
            Text(
              text = title,
              style = MaterialTheme.typography.bodyMediumEmphasized,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = if (reduceMotion) Modifier else Modifier.basicMarquee(),
            )
          }
          val statusText = when {
            audiobook != null -> {
              val author = audiobook.book.author.takeIf { it.isNotBlank() }
              val durText = DateUtils.formatElapsedTime(audiobook.durationMs / 1000)
              val posText = DateUtils.formatElapsedTime(totalPosSec().toLong())
              val timeInfo = "$posText / $durText"
              if (author != null) "$author • $timeInfo" else timeInfo
            }
            else -> stringResource(if (isPlaying) R.string.notification_playing else R.string.downloads_paused)
          }
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            if (isLoopASet || isLoopBSet) {
              val loopDescription = stringResource(R.string.btn_label_ab_loop)
              AbLoopIcon(
                modifier = Modifier.size(20.dp).semantics { contentDescription = loopDescription },
                tint = MaterialTheme.colorScheme.primary,
                isASet = isLoopASet,
                isBSet = isLoopBSet,
              )
            }
            Text(
              text = statusText,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
          }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Play / Pause Action Button
        IconButton(
          onClick = {
            actionHaptics.selection(true)
             context.startService(
              Intent(context, MediaPlaybackService::class.java).setAction(
                MediaPlaybackService.ACTION_NOTIFICATION_PLAY_PAUSE,
              ),
            )
          },
          modifier = Modifier.size(48.dp),
        ) {
          AnimatedIcon(
            imageVector = if (isPlaying) Icons.RoundedFilled.Pause else Icons.RoundedFilled.PlayArrow,
            contentDescription = stringResource(if (isPlaying) R.string.audiobook_pause else R.string.ui_play),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(26.dp),
            reduceMotion = reduceMotion,
          )
        }

        // Next Track Action Button
        IconButton(
          onClick = {
            actionHaptics.tick()
             context.startService(
              Intent(context, MediaPlaybackService::class.java).setAction(
                MediaPlaybackService.ACTION_NOTIFICATION_NEXT,
              ),
            )
          },
          modifier = Modifier.size(48.dp),
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.SkipNext,
            contentDescription = stringResource(R.string.pref_gesture_media_next),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
          )
        }

        // Close Action Button
        IconButton(
          onClick = { tossAway(towardCloseButton, velocity = 0f, anticipate = true) },
          modifier = Modifier.size(48.dp),
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.Close,
            contentDescription = stringResource(R.string.ui_close),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
        }
      }
    }
  }
}

/** A stable surface across the mini player's enter/exit animation and service handoff. */
private class MiniPlayerSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
  private var playbackActive = false
  private var previousSubtitleVisibility: Boolean? = null

  init {
    setZOrderMediaOverlay(true)
    holder.addCallback(this)
  }

  fun setPlaybackActive(active: Boolean) {
    playbackActive = active
    if (active) {
      attachIfReady()
      if (PlaybackSession.isRendererReadyFor(this)) hideSubtitles()
    } else {
      restoreSubtitleVisibility()
    }
    // Keep the outgoing Surface until the fullscreen owner replaces it or Android destroys it.
    // An ownership flag alone must not blank the source while its window is still visible.
  }

  private fun attachIfReady() {
    if (!playbackActive || !isAttachedToWindow || !holder.surface.isValid ||
      !MediaPlaybackService.isForegroundActive() || PlaybackSession.isSurfaceAttachedTo(this)
    ) return
    PlaybackSession.bindSurface(
      surface = holder.surface,
      width = width,
      height = height,
      owner = this,
      ownerIsActive = { playbackActive && isAttachedToWindow && MediaPlaybackService.isForegroundActive() },
      onRendererReady = {
        if (playbackActive && MediaPlaybackService.isForegroundActive() && PlaybackSession.isRendererReadyFor(this)) {
          hideSubtitles()
          if (PlaybackSession.getPropertyBoolean("pause") == true) {
            PlaybackSession.redrawVideoFrame()
          }
        }
      },
    )
  }

  private fun hideSubtitles() {
    if (previousSubtitleVisibility != null) return
    previousSubtitleVisibility = PlaybackSession.getPropertyBoolean("sub-visibility") ?: true
    PlaybackSession.setPropertyBoolean("sub-visibility", false)
  }

  private fun restoreSubtitleVisibility() {
    val previous = previousSubtitleVisibility ?: return
    previousSubtitleVisibility = null
    if (PlaybackSession.isSurfaceAttachedTo(this)) {
      PlaybackSession.setPropertyBoolean("sub-visibility", previous)
    }
  }

  fun releaseRenderer() {
    playbackActive = false
    restoreSubtitleVisibility()
    PlaybackSession.unbindSurface(this)
  }

  override fun surfaceCreated(holder: SurfaceHolder) = attachIfReady()

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    if (!PlaybackSession.resizeSurface(width, height, this)) attachIfReady()
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    // Retain the active flag: Android can recreate this Surface without a Compose update.
    restoreSubtitleVisibility()
    PlaybackSession.unbindSurface(this)
  }
}

private data class MiniPlayerArtwork(
  val bitmap: Bitmap?,
  val motionArtwork: ByteArray? = null,
)

/** Only the current audio mini-player decodes motion frames; notifications remain static. */
@Composable
private fun rememberMiniPlayerArtwork(
  pathOrUri: String?,
  artworkUri: String?,
): MiniPlayerArtwork? {
  val context = LocalContext.current
  val repository = koinInject<app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository>()
  val revision by repository.cacheRevision.collectAsState()
  val generator by koinInject<app.gyrolet.mpvrx.preferences.BrowserPreferences>().thumbnailGenerator.collectAsState()
  var artwork by remember(pathOrUri, artworkUri, generator, revision) { mutableStateOf<MiniPlayerArtwork?>(null) }

  LaunchedEffect(pathOrUri, artworkUri, generator, revision) {
    if (pathOrUri.isNullOrBlank() && artworkUri.isNullOrBlank()) {
      artwork = null
      return@LaunchedEffect
    }
    artwork = withContext(Dispatchers.IO) {
      if (isLocalArtworkSource(pathOrUri) && generator != app.gyrolet.mpvrx.preferences.ThumbnailGenerator.Native) {
        return@withContext MiniPlayerArtwork(bitmap = repository.getArtwork(pathOrUri))
      }
      runCatching {
        val nativeArtworkUri = artworkUri?.takeUnless {
          isLocalArtworkSource(pathOrUri) && Uri.parse(it).authority == android.provider.MediaStore.AUTHORITY
        }
        if (!nativeArtworkUri.isNullOrBlank()) {
          EmbeddedArtworkResolver.decodeArtworkUri(context, nativeArtworkUri)?.let {
            return@runCatching MiniPlayerArtwork(bitmap = it)
          }
        }
        if (pathOrUri.isNullOrBlank()) return@runCatching null
        val cleanPath =
          when {
            pathOrUri.startsWith("file://", ignoreCase = true) -> Uri.parse(pathOrUri).path
            pathOrUri.startsWith("content://", ignoreCase = true) -> null
            else -> pathOrUri
          }
        val retriever = MediaMetadataRetriever()
        try {
          if (cleanPath != null) {
            retriever.setDataSource(cleanPath)
          } else {
            retriever.setDataSource(context, Uri.parse(pathOrUri))
          }
          val pictureBytes = retriever.embeddedPicture
          MiniPlayerArtwork(
            bitmap = EmbeddedArtworkResolver.decodeEmbeddedArtwork(cleanPath, retriever, pictureBytes),
            motionArtwork = EmbeddedMotionArtwork.animatedBytes(pictureBytes),
          )
        } finally {
          runCatching { retriever.release() }
        }
      }.getOrNull()
    }
  }
  return artwork
}
