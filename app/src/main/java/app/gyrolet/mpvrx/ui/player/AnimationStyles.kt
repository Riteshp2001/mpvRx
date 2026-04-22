package app.gyrolet.mpvrx.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Which style to use when player controls appear/disappear. */
enum class ControlsAnimationStyle(val displayName: String) {
  Default("Default"),
  Elastic("Elastic Bounce"),
  Cinematic("Cinematic Scale"),
  SlideUp("Slide Up"),
  Minimal("Minimal Fade"),
  None("None"),
}

/** Animation style when the video first opens. */
enum class VideoOpenAnimation(val displayName: String) {
  Default("Default"),
  FadeDark("Fade from Black"),
  ZoomBurst("Zoom Burst"),
  SlideUp("Slide Up"),
  CinemaBars("Cinema Bars"),
  None("None"),
}

/** Tracks whether the selected open animation should still cover the player while media loads. */
data class VideoOpenAnimationState(
  val loadToken: Long = 0L,
  val isWaitingForVideo: Boolean = true,
)

/** Animation style for tab / screen navigation. */
enum class NavigationAnimStyle(val displayName: String) {
  Default("Default"),
  Elastic("Elastic Slide"),
  Depth("Depth Zoom"),
  FlipFade("Flip Fade"),
  Minimal("Minimal Fade"),
  None("None"),
}

// ────────────────────────────────────────────────────────────────────────────
// Controls enter / exit helpers
// ────────────────────────────────────────────────────────────────────────────

/**
 * Build an [EnterTransition] for controls that have a *horizontal* natural direction.
 *
 * @param offsetX  lambda that returns the initial horizontal pixel offset (same convention as
 *                 [slideInHorizontally]).  Ignored for styles that don't slide horizontally.
 */
fun buildControlsEnterH(
  style: ControlsAnimationStyle,
  reduceMotion: Boolean,
  enterMs: Int,
  offsetX: (fullWidth: Int) -> Int,
): EnterTransition = when {
  style == ControlsAnimationStyle.None -> EnterTransition.None

  style == ControlsAnimationStyle.Minimal ->
    fadeIn(tween(50))

  style == ControlsAnimationStyle.Cinematic ->
    scaleIn(tween(enterMs), initialScale = 0.94f) + fadeIn(tween(enterMs))

  style == ControlsAnimationStyle.SlideUp ->
    slideInVertically(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 420f)) { it } +
      fadeIn(tween(100))

  style == ControlsAnimationStyle.Elastic ->
    slideInHorizontally(
      spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 420f),
      offsetX,
    ) + fadeIn(tween(80))

  // Default style — respect the existing reduceMotion flag
  !reduceMotion ->
    slideInHorizontally(tween(enterMs), offsetX) + fadeIn(tween(enterMs))

  else -> fadeIn(tween(enterMs))
}

fun buildControlsExitH(
  style: ControlsAnimationStyle,
  reduceMotion: Boolean,
  exitMs: Int,
  offsetX: (fullWidth: Int) -> Int,
): ExitTransition = when {
  style == ControlsAnimationStyle.None -> ExitTransition.None

  style == ControlsAnimationStyle.Minimal ->
    fadeOut(tween(50))

  style == ControlsAnimationStyle.Cinematic ->
    scaleOut(tween(exitMs), targetScale = 0.94f) + fadeOut(tween(exitMs))

  style == ControlsAnimationStyle.SlideUp ->
    slideOutVertically(tween(exitMs)) { -it } + fadeOut(tween(exitMs))

  style == ControlsAnimationStyle.Elastic ->
    slideOutHorizontally(tween(exitMs), offsetX) + fadeOut(tween((exitMs * 0.8f).toInt()))

  !reduceMotion ->
    slideOutHorizontally(tween(exitMs), offsetX) + fadeOut(tween(exitMs))

  else -> fadeOut(tween(exitMs))
}

/**
 * Build an [EnterTransition] for controls that have a *vertical* natural direction.
 *
 * @param offsetY  lambda returning the initial vertical pixel offset.
 */
fun buildControlsEnterV(
  style: ControlsAnimationStyle,
  reduceMotion: Boolean,
  enterMs: Int,
  offsetY: (fullHeight: Int) -> Int,
): EnterTransition = when {
  style == ControlsAnimationStyle.None -> EnterTransition.None

  style == ControlsAnimationStyle.Minimal ->
    fadeIn(tween(50))

  style == ControlsAnimationStyle.Cinematic ->
    scaleIn(tween(enterMs), initialScale = 0.94f) + fadeIn(tween(enterMs))

  style == ControlsAnimationStyle.SlideUp ->
    slideInVertically(
      spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 420f),
    ) { it } + fadeIn(tween(80))

  style == ControlsAnimationStyle.Elastic ->
    slideInVertically(
      spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 420f),
      offsetY,
    ) + fadeIn(tween(80))

  !reduceMotion ->
    slideInVertically(tween(enterMs), offsetY) + fadeIn(tween(enterMs))

  else -> fadeIn(tween(enterMs))
}

fun buildControlsExitV(
  style: ControlsAnimationStyle,
  reduceMotion: Boolean,
  exitMs: Int,
  offsetY: (fullHeight: Int) -> Int,
): ExitTransition = when {
  style == ControlsAnimationStyle.None -> ExitTransition.None

  style == ControlsAnimationStyle.Minimal ->
    fadeOut(tween(50))

  style == ControlsAnimationStyle.Cinematic ->
    scaleOut(tween(exitMs), targetScale = 0.94f) + fadeOut(tween(exitMs))

  style == ControlsAnimationStyle.SlideUp ->
    slideOutVertically(tween(exitMs)) { -it } + fadeOut(tween(exitMs))

  style == ControlsAnimationStyle.Elastic ->
    slideOutVertically(tween(exitMs), offsetY) + fadeOut(tween((exitMs * 0.8f).toInt()))

  !reduceMotion ->
    slideOutVertically(tween(exitMs), offsetY) + fadeOut(tween(exitMs))

  else -> fadeOut(tween(exitMs))
}

// ────────────────────────────────────────────────────────────────────────────
// Video-open animation overlay
// ────────────────────────────────────────────────────────────────────────────

/**
 * Draws a full-screen overlay that stays in place while media is loading, then plays the selected
 * [VideoOpenAnimation] once the video is ready. No-op when [style] is
 * [VideoOpenAnimation.Default] or [VideoOpenAnimation.None].
 */
@Composable
fun VideoOpenAnimationOverlay(
  style: VideoOpenAnimation,
  speedMultiplier: Float,
  animationState: VideoOpenAnimationState,
) {
  if (style == VideoOpenAnimation.Default || style == VideoOpenAnimation.None) return

  val durationMs = (400 * speedMultiplier).toInt().coerceAtLeast(100)
  val holdMs = (120 * speedMultiplier).toInt().coerceAtLeast(50)

  key(animationState.loadToken) {
    var overlayVisible by remember { mutableStateOf(true) }

    LaunchedEffect(animationState.isWaitingForVideo) {
      if (animationState.isWaitingForVideo) {
        overlayVisible = true
        return@LaunchedEffect
      }

      delay(holdMs.toLong())
      overlayVisible = false
    }

    when (style) {

      VideoOpenAnimation.FadeDark -> {
        AnimatedVisibility(
          visible = overlayVisible,
          enter = EnterTransition.None,
          exit = fadeOut(tween(durationMs)),
        ) {
          Box(
            Modifier
              .fillMaxSize()
              .background(Color.Black),
          )
        }
      }

      VideoOpenAnimation.ZoomBurst -> {
        // Black mask that scales up + fades out so the video appears to "burst" in from behind.
        AnimatedVisibility(
          visible = overlayVisible,
          enter = EnterTransition.None,
          exit = scaleOut(
            tween(durationMs),
            targetScale = 1.18f,
          ) + fadeOut(tween(durationMs)),
        ) {
          Box(
            Modifier
              .fillMaxSize()
              .background(Color.Black),
          )
        }
      }

      VideoOpenAnimation.SlideUp -> {
        AnimatedVisibility(
          visible = overlayVisible,
          enter = EnterTransition.None,
          exit = slideOutVertically(
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 200f),
          ) { -it } + fadeOut(tween(durationMs)),
        ) {
          Box(
            Modifier
              .fillMaxSize()
              .background(Color.Black),
          )
        }
      }

      VideoOpenAnimation.CinemaBars -> {
        // Two black bars that hold while loading, then shrink away once the video is ready.
        val barHeight by animateDpAsState(
          targetValue = if (overlayVisible) 110.dp else 0.dp,
          animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = 180f),
          label = "cinema_bars",
        )
        Box(Modifier.fillMaxSize()) {
          Box(
            Modifier
              .fillMaxWidth()
              .height(barHeight)
              .background(Color.Black)
              .align(Alignment.TopCenter),
          )
          Box(
            Modifier
              .fillMaxWidth()
              .height(barHeight)
              .background(Color.Black)
              .align(Alignment.BottomCenter),
          )
        }
      }

    }
  }
}

