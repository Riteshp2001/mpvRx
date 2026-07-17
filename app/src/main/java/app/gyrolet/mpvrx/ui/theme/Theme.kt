package app.gyrolet.mpvrx.ui.theme

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import androidx.core.view.drawToBitmap
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import org.koin.compose.koinInject

// ============================================================================
// Theme Transition Animation State & Components
// ============================================================================
/**
 * State for managing theme transition animations.
 * This class holds the animation state and provides methods to trigger transitions.
 */
class ThemeTransitionState {
    var isAnimating by mutableStateOf(false)
        private set
    var clickPosition by mutableStateOf(Offset.Zero)
        private set
    var screenshotBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var animationProgress = Animatable(0f)
        private set
    
    private var captureView: View? = null
    
    fun setView(view: View?) {
        captureView = view
    }
    
    /**
     * Start a theme transition animation from the given position.
     * Captures the current screen and begins the reveal animation.
     * Will NOT start a new animation if one is already in progress.
     */
    fun startTransition(position: Offset) {
        // Don't allow new animation while one is in progress
        if (isAnimating) return
        
        captureView?.let { view ->
            try {
                // Capture before setting isAnimating to ensure we get the current state
                val bitmap = view.drawToBitmap()
                animationProgress = Animatable(0f)
                screenshotBitmap = bitmap
                clickPosition = position
                isAnimating = true
            } catch (e: Exception) {
                // If capture fails, just skip the animation
                screenshotBitmap = null
                isAnimating = false
            }
        }
    }
    
    fun finishTransition() {
        val oldBitmap = screenshotBitmap
        screenshotBitmap = null
        clickPosition = Offset.Zero
        isAnimating = false
        // Let Compose detach the ImageBitmap before releasing its Android backing bitmap.
        captureView?.postDelayed(
            { oldBitmap?.takeUnless { it.isRecycled }?.recycle() },
            BITMAP_RECYCLE_DELAY_MS,
        )
    }
    
    suspend fun resetProgress() {
        animationProgress.snapTo(0f)
    }

    private companion object {
        const val BITMAP_RECYCLE_DELAY_MS = 96L
    }
}

/**
 * CompositionLocal to provide ThemeTransitionState down the composition tree
 */
val LocalThemeTransitionState = staticCompositionLocalOf<ThemeTransitionState?> { null }

@Composable
fun rememberThemeTransitionState(): ThemeTransitionState {
    return remember { ThemeTransitionState() }
}

/**
 * Reveals the new theme below a frozen frame of the old theme. The old frame is
 * removed by a feathered radial alpha mask, which gives the transition the soft
 * blur edge used by Telegram-style theme switches without blurring text or video.
 */
@Composable
private fun ThemeTransitionOverlay(
    state: ThemeTransitionState,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    val featherPx = with(LocalDensity.current) { THEME_REVEAL_FEATHER.toPx() }
    val bitmap = state.screenshotBitmap
    val progress = state.animationProgress.value

    DisposableEffect(view, state) {
        state.setView(view)
        onDispose { state.setView(null) }
    }

    LaunchedEffect(state.isAnimating, bitmap, reduceMotion) {
        if (!state.isAnimating || bitmap == null) return@LaunchedEffect

        state.resetProgress()
        // Give the newly selected Material color scheme one frame to settle below the snapshot.
        withFrameNanos { }
        if (reduceMotion) {
            state.animationProgress.snapTo(1f)
        } else {
            // Keep the frozen frame opaque while the new theme finishes its root recomposition.
            kotlinx.coroutines.delay(THEME_CONTENT_SETTLE_DELAY_MS.toLong())
            state.animationProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = THEME_REVEAL_DURATION_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
        }
        state.finishTransition()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        content()

        if (bitmap != null && state.isAnimating) {
            val frozenFrame = remember(bitmap) { bitmap.asImageBitmap() }
            Image(
                bitmap = frozenFrame,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithCache {
                        val center = state.clickPosition.takeUnless { it == Offset.Zero }
                            ?: Offset(size.width / 2f, size.height / 2f)
                        val maxRadius = maxOf(
                            hypot(center.x, center.y),
                            hypot(size.width - center.x, center.y),
                            hypot(center.x, size.height - center.y),
                            hypot(size.width - center.x, size.height - center.y),
                        )
                        val revealRadius = (maxRadius + featherPx) * progress
                        val maskRadius = (revealRadius + featherPx).coerceAtLeast(1f)
                        val clearStop = ((revealRadius - featherPx) / maskRadius).coerceIn(0f, 1f)
                        val softStop = (revealRadius / maskRadius).coerceIn(clearStop, 1f)
                        val mask = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                clearStop to Color.Transparent,
                                softStop to Color.Black.copy(alpha = 0.28f),
                                1f to Color.Black,
                            ),
                            center = center,
                            radius = maskRadius,
                        )

                        onDrawWithContent {
                            drawContent()
                            if (progress > 0f) {
                                drawRect(brush = mask, blendMode = BlendMode.DstIn)
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
            )
        }
    }
}

private const val THEME_CONTENT_SETTLE_DELAY_MS = 100
private const val THEME_REVEAL_DURATION_MS = 650
private val THEME_REVEAL_FEATHER = 30.dp

@Composable
private fun ThemeTransitionContent(content: @Composable () -> Unit) {
    val state = LocalThemeTransitionState.current
    
    if (state != null) {
        ThemeTransitionOverlay(state = state, content = content)
    } else {
        content()
    }
}

// ============================================================================
// Main Theme
// ============================================================================

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MpvrxTheme(
    transitionState: ThemeTransitionState = rememberThemeTransitionState(),
    content: @Composable () -> Unit,
) {
    val preferences = koinInject<AppearancePreferences>()
    val darkMode by preferences.darkMode.collectAsState()
    val amoledMode by preferences.amoledMode.collectAsState()
    val appTheme by preferences.appTheme.collectAsState()
    val useSystemFont by preferences.useSystemFont.collectAsState()
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current

    val useDarkTheme = when (darkMode) {
        DarkMode.Dark -> true
        DarkMode.Light -> false
        DarkMode.System -> darkTheme
    }

    val colorScheme = when {
        appTheme.isDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            when {
                useDarkTheme && amoledMode -> {
                    dynamicDarkColorScheme(context).copy(
                        background = backgroundPureBlack,
                        surface = surfacePureBlack,
                        surfaceDim = surfaceDimPureBlack,
                        surfaceBright = surfaceBrightPureBlack,
                        surfaceContainerLowest = surfaceContainerLowestPureBlack,
                        surfaceContainerLow = surfaceContainerLowPureBlack,
                        surfaceContainer = surfaceContainerPureBlack,
                        surfaceContainerHigh = surfaceContainerHighPureBlack,
                        surfaceContainerHighest = surfaceContainerHighestPureBlack,
                    )
                }
                useDarkTheme -> dynamicDarkColorScheme(context)
                else -> dynamicLightColorScheme(context).withComfortableLightSurfaces()
            }
        }
        useDarkTheme && amoledMode -> appTheme.getAmoledColorScheme()
        useDarkTheme -> appTheme.getDarkColorScheme()
        else -> appTheme.getLightColorScheme()
    }

    // Provide theme transition state first, OUTSIDE MaterialExpressiveTheme
    CompositionLocalProvider(
      LocalSpacing provides Spacing(),
      LocalThemeTransitionState provides transitionState,
      LocalMotionPolicy provides rememberMotionPolicy(),
      LocalEmphasizedTypography provides AppEmphasizedTypography,
    ) {
      ThemeTransitionContent {
        MaterialExpressiveTheme(
          colorScheme = colorScheme,
          typography = if (useSystemFont) SystemTypography else AppTypography,
          shapes = AppShapes,
          motionScheme = MotionScheme.expressive(),
          content = content,
        )
      }
    }
}

/** Keeps wallpaper-derived accents while replacing near-white full-screen surfaces. */
private fun androidx.compose.material3.ColorScheme.withComfortableLightSurfaces(): androidx.compose.material3.ColorScheme {
    val base = Color(0xFFF7F5F8)
    fun tint(amount: Float) = androidx.compose.ui.graphics.lerp(base, primary, amount)

    return copy(
        background = tint(0.018f),
        surface = tint(0.018f),
        surfaceDim = tint(0.085f),
        surfaceBright = Color(0xFFFBF9FC),
        surfaceContainerLowest = Color(0xFFFBF9FC),
        surfaceContainerLow = tint(0.030f),
        surfaceContainer = tint(0.050f),
        surfaceContainerHigh = tint(0.072f),
        surfaceContainerHighest = tint(0.098f),
    )
}

enum class DarkMode(
    @StringRes val titleRes: Int,
) {
    Dark(R.string.pref_appearance_darkmode_dark),
    Light(R.string.pref_appearance_darkmode_light),
    System(R.string.pref_appearance_darkmode_system),
}

private const val RIPPLE_DRAGGED_ALPHA = .5f
private const val RIPPLE_FOCUSED_ALPHA = .6f
private const val RIPPLE_HOVERED_ALPHA = .4f
private const val RIPPLE_PRESSED_ALPHA = .6f

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
val playerRippleConfiguration
    @Composable get() =
        RippleConfiguration(
            color = MaterialTheme.colorScheme.primaryContainer,
            rippleAlpha =
            RippleAlpha(
                draggedAlpha = RIPPLE_DRAGGED_ALPHA,
                focusedAlpha = RIPPLE_FOCUSED_ALPHA,
                hoveredAlpha = RIPPLE_HOVERED_ALPHA,
                pressedAlpha = RIPPLE_PRESSED_ALPHA,
            ),
        )


