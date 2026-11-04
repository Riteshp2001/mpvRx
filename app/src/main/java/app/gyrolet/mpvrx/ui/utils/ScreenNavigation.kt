/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.utils

import androidx.activity.BackEventCompat
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight
import app.gyrolet.mpvrx.ui.browser.NavigationBarState
import app.gyrolet.mpvrx.ui.player.NavigationAnimStyle
import app.gyrolet.mpvrx.ui.player.LocalInAppMusicSheet
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.LocalAppWallpaperActive
import app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor
import app.gyrolet.mpvrx.ui.tv.TvEdgeToEdgeScreen
import app.gyrolet.mpvrx.ui.tv.TvScreenFocusHost
import kotlin.math.roundToInt
import org.koin.compose.koinInject

private val NavigationEnterEasing = CubicBezierEasing(0.05f, 0.70f, 0.10f, 1.00f)
private val NavigationExitEasing = CubicBezierEasing(0.30f, 0.00f, 0.80f, 0.15f)
internal val LocalCurrentScreen = staticCompositionLocalOf<Screen?> { null }

/** The Appearance slider is a duration multiplier: smaller values finish sooner. */
internal fun navigationDurationMillis(speed: Float, baseMillis: Int = 260): Int =
  (baseMillis * (speed.takeIf { it.isFinite() } ?: 1f).coerceIn(0.25f, 2.5f)).roundToInt()

private fun navigationSpringStiffness(base: Float, speed: Float): Float {
  val multiplier = (speed.takeIf { it.isFinite() } ?: 1f).coerceIn(0.25f, 2.5f)
  return (base / (multiplier * multiplier)).coerceIn(110f, 2400f)
}

/** Native pager settling for taps: springy, cancellable and independent of screen composition. */
internal fun navigationTabAnimationSpec(
  style: NavigationAnimStyle,
  speed: Float,
): FiniteAnimationSpec<Float> =
  when (style) {
    NavigationAnimStyle.None -> tween(0)
    NavigationAnimStyle.Minimal ->
      tween(
        durationMillis = navigationDurationMillis(speed, 170),
        easing = NavigationEnterEasing,
      )
    NavigationAnimStyle.FlipFade ->
      tween(
        durationMillis = navigationDurationMillis(speed, 210),
        easing = NavigationEnterEasing,
      )
    NavigationAnimStyle.Depth ->
      spring(
        dampingRatio = 0.92f,
        stiffness = navigationSpringStiffness(560f, speed),
      )
    NavigationAnimStyle.Default ->
      spring(
        dampingRatio = 0.90f,
        stiffness = navigationSpringStiffness(760f, speed),
      )
  }

/** One host for full screens and nested panes, including system/predictive Back and saved state. */
@Composable
internal fun ScreenNavDisplay(
  backStack: NavBackStack<Screen>,
  modifier: Modifier = Modifier,
  opaqueBackground: Boolean = false,
  /**
   * Entries at or above this screen in the stack draw an opaque surface; entries below it keep the
   * wallpaper-aware one. Decided once per entry, so pushing/popping the screen never flips the
   * colour of an entry that is already on screen (which used to hard-cut the wallpaper to a solid
   * colour for one frame before the slide started).
   */
  opaqueFrom: Screen? = null,
  /**
   * TV: inset every entry by the overscan-safe margins, except screens that are full-bleed media
   * or lay out their own safe area ([TvEdgeToEdgeScreen]). Set only by the top-level host.
   */
  tvSafeArea: Boolean = false,
  tvAutoFocus: Boolean = true,
  tvFocusRequestKey: Any? = Unit,
  bottomOverlayClearance: Dp = 0.dp,
  bottomOverlayExempt: Screen? = null,
  onBack: () -> Unit = { backStack.popSafely() },
  content: @Composable (Screen) -> Unit = { it.Content() },
) {
  val preferences = koinInject<PlayerPreferences>()
  val style by preferences.appNavStyle.collectAsState()
  val speed by preferences.animationSpeed.collectAsState()
  val reduceMotion = AppMotion.shouldReduceMotion()
  val layoutDirection = LocalLayoutDirection.current
  val musicSheet = LocalInAppMusicSheet.current
  val direction = if (layoutDirection == LayoutDirection.Ltr) 1 else -1
  // With a wallpaper / animated background the entries are transparent, so a normal slide or
  // cross-fade shows the outgoing and incoming screens on top of each other (ghosted titles,
  // doubled lists, jumpy predictive back). Use a sequential fade-through there instead.
  val seeThrough = LocalAppWallpaperActive.current && !opaqueBackground
  val backgroundColor by rememberUpdatedState(
    if (opaqueBackground) MaterialTheme.colorScheme.background else wallpaperAwareBackgroundColor(),
  )

  NavDisplay(
    backStack = backStack,
    modifier = modifier.clipToBounds().background(backgroundColor),
    onBack = {
      // Navigation entries remain composed behind the music sheet. If their dispatcher wins,
      // collapse the overlay without popping a folder or leaving the selected music tab.
      if (musicSheet?.isVisible == true) musicSheet.collapse() else onBack()
    },
    sizeTransform = null,
    transitionSpec = { screenNavTransition(true, style, speed, direction, reduceMotion, seeThrough) },
    popTransitionSpec = { screenNavTransition(false, style, speed, direction, reduceMotion, seeThrough) },
    predictivePopTransitionSpec = { edge: Int ->
      screenNavTransition(
        forward = false,
        style = style,
        speed = speed,
        direction = if (edge == BackEventCompat.EDGE_RIGHT) -1 else 1,
        reduceMotion = reduceMotion,
        seeThrough = seeThrough,
      )
    },
    entryProvider = { route ->
      NavEntry(route) {
        val entryOpaque =
          remember(route) {
            opaqueBackground ||
              (opaqueFrom != null && backStack.indexOf(opaqueFrom).let { it >= 0 && it <= backStack.indexOf(route) })
          }
        val entryColor =
          if (entryOpaque) MaterialTheme.colorScheme.background else wallpaperAwareBackgroundColor()
        // The Preferences list handles its own bottom scroll clearance. Insetting its
        // whole NavEntry instead leaves a large blank strip above the mini-player.
        val scrollHandlesOverlay = route == app.gyrolet.mpvrx.ui.preferences.PreferencesScreen
        val insetByHost = bottomOverlayExempt != null && route != bottomOverlayExempt &&
          !NavigationBarState.isInSelectionMode && !scrollHandlesOverlay
        val inheritedClearance = LocalNavigationBarHeight.current
        CompositionLocalProvider(
          LocalCurrentScreen provides route,
          LocalNavigationBarHeight provides if (insetByHost) 0.dp else inheritedClearance,
        ) {
          Surface(Modifier.fillMaxSize(), color = entryColor) {
            Box(Modifier.fillMaxSize().padding(bottom = if (insetByHost) bottomOverlayClearance else 0.dp)) {
              // TV: predictable initial focus, focus restored on Back, and overscan-safe content.
              TvScreenFocusHost(
                scope = route,
                safeArea = tvSafeArea && route !is TvEdgeToEdgeScreen,
                active = route == backStack.lastOrNull(),
                autoFocus = tvAutoFocus,
                focusRequestKey = tvFocusRequestKey,
              ) {
                content(route)
              }
            }
          }
        }
      }
    },
  )
}

private fun screenNavTransition(
  forward: Boolean,
  style: NavigationAnimStyle,
  speed: Float,
  direction: Int,
  reduceMotion: Boolean,
  seeThrough: Boolean = false,
): ContentTransform {
  val effectiveStyle =
    if (reduceMotion && style != NavigationAnimStyle.None) NavigationAnimStyle.Minimal else style
  val duration = navigationDurationMillis(speed)
  val alphaDuration = (duration * 0.72f).roundToInt().coerceAtLeast(1)

  if (seeThrough && effectiveStyle != NavigationAnimStyle.None) {
    // Outgoing screen is gone before the incoming one appears, so they never overlap.
    val exitDuration = (duration * 0.30f).roundToInt().coerceAtLeast(1)
    val enterDuration = (duration - exitDuration).coerceAtLeast(1)
    val enter =
      fadeIn(
        tween(
          durationMillis = enterDuration,
          delayMillis = exitDuration,
          easing = NavigationEnterEasing,
        ),
      )
    val exit = fadeOut(tween(durationMillis = exitDuration, easing = NavigationExitEasing))
    return if (effectiveStyle == NavigationAnimStyle.Minimal) {
      enter togetherWith exit
    } else {
      val sign = if (forward) direction else -direction
      (
        enter +
          slideInHorizontally(
            animationSpec =
              tween(
                durationMillis = enterDuration,
                delayMillis = exitDuration,
                easing = NavigationEnterEasing,
              ),
          ) { fullWidth -> fullWidth * sign / 16 }
        ) togetherWith exit
    }
  }

  return when (effectiveStyle) {
    NavigationAnimStyle.None -> EnterTransition.None togetherWith ExitTransition.None

    NavigationAnimStyle.Minimal ->
      fadeIn(
        tween(
          durationMillis = navigationDurationMillis(speed, 150),
          easing = NavigationEnterEasing,
        ),
      ) togetherWith
        fadeOut(
          tween(
            durationMillis = navigationDurationMillis(speed, 120),
            easing = NavigationExitEasing,
          ),
        )

    NavigationAnimStyle.FlipFade -> {
      val exitDuration = (duration * 0.34f).roundToInt().coerceAtLeast(1)
      val enterDuration = (duration - exitDuration).coerceAtLeast(1)
      (
        fadeIn(
          tween(
            durationMillis = enterDuration,
            delayMillis = exitDuration,
            easing = NavigationEnterEasing,
          ),
        ) +
          scaleIn(
            animationSpec = tween(durationMillis = duration, easing = NavigationEnterEasing),
            initialScale = 0.985f,
          )
        ) togetherWith
        (
          fadeOut(
            tween(durationMillis = exitDuration, easing = NavigationExitEasing),
          ) +
            scaleOut(
              animationSpec = tween(durationMillis = exitDuration, easing = NavigationExitEasing),
              targetScale = 0.992f,
            )
          )
    }

    NavigationAnimStyle.Depth ->
      if (forward) {
        (
          slideInHorizontally(
            animationSpec = tween(durationMillis = duration, easing = NavigationEnterEasing),
          ) { fullWidth -> fullWidth * direction / 10 } +
            scaleIn(
              animationSpec = tween(durationMillis = duration, easing = NavigationEnterEasing),
              initialScale = 0.985f,
            ) +
            fadeIn(
              tween(durationMillis = alphaDuration, easing = NavigationEnterEasing),
            )
          ) togetherWith
          (
            scaleOut(
              animationSpec = tween(durationMillis = duration, easing = NavigationExitEasing),
              targetScale = 0.945f,
            ) +
              fadeOut(
                tween(durationMillis = alphaDuration, easing = NavigationExitEasing),
                targetAlpha = 0.72f,
              )
            )
      } else {
        (
          scaleIn(
            animationSpec = tween(durationMillis = duration, easing = NavigationEnterEasing),
            initialScale = 0.945f,
          ) +
            fadeIn(
              tween(durationMillis = alphaDuration, easing = NavigationEnterEasing),
              initialAlpha = 0.72f,
            )
          ) togetherWith
          (
            slideOutHorizontally(
              animationSpec = tween(durationMillis = duration, easing = NavigationExitEasing),
            ) { fullWidth -> fullWidth * direction / 10 } +
              scaleOut(
                animationSpec = tween(durationMillis = duration, easing = NavigationExitEasing),
                targetScale = 1.012f,
              ) +
              fadeOut(
                tween(durationMillis = alphaDuration, easing = NavigationExitEasing),
              )
            )
      }

    NavigationAnimStyle.Default ->
      if (forward) {
        (
          slideInHorizontally(
            animationSpec = tween(durationMillis = duration, easing = NavigationEnterEasing),
          ) { fullWidth -> fullWidth * direction / 5 } +
            fadeIn(
              tween(durationMillis = alphaDuration, easing = NavigationEnterEasing),
            )
          ) togetherWith
          (
            slideOutHorizontally(
              animationSpec = tween(durationMillis = duration, easing = NavigationExitEasing),
            ) { fullWidth -> -fullWidth * direction / 12 } +
              fadeOut(
                tween(durationMillis = alphaDuration, easing = NavigationExitEasing),
                targetAlpha = 0.90f,
              )
            )
      } else {
        (
          slideInHorizontally(
            animationSpec = tween(durationMillis = duration, easing = NavigationEnterEasing),
          ) { fullWidth -> -fullWidth * direction / 12 } +
            fadeIn(
              tween(durationMillis = alphaDuration, easing = NavigationEnterEasing),
              initialAlpha = 0.90f,
            )
          ) togetherWith
          (
            slideOutHorizontally(
              animationSpec = tween(durationMillis = duration, easing = NavigationExitEasing),
            ) { fullWidth -> fullWidth * direction / 5 } +
              fadeOut(
                tween(durationMillis = alphaDuration, easing = NavigationExitEasing),
              )
            )
      }
  }
}
