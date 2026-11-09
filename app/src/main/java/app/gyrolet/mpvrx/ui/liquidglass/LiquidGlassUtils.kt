package app.gyrolet.mpvrx.ui.liquidglass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.LiquidGlassHighlightStyle
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.player.controls.PlayerButtonAlpha
import org.koin.compose.koinInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

val LocalKyantPlayerBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Prevent HDR playback from refracting the sampled video across player glass surfaces. */
internal val LocalPlayerHdrOutputEnabled = staticCompositionLocalOf { false }

/**
 * False inside the video player while the video sits on a native surface Liquid Glass cannot
 * sample (HDR output without "Liquid glass in HDR"); null everywhere else, where the preference
 * alone decides.
 */
internal val LocalPlayerLiquidGlassActive = staticCompositionLocalOf<Boolean?> { null }

/**
 * Whether Liquid Glass applies here. Player components read this instead of the raw preference,
 * so they fall back to their regular, non-glass form whenever the player turns glass off.
 */
@Composable
fun liquidGlassActive(): Boolean {
    val enabled by koinInject<AppearancePreferences>().liquidGlassEnabled.collectAsState()
    return enabled && LocalPlayerLiquidGlassActive.current != false
}

object LiquidControlColors {
    val accent: Color
        @Composable get() = MaterialTheme.colorScheme.primary
    val content: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface
    val disabledContent: Color
        @Composable get() = content.copy(alpha = PlayerButtonAlpha.DISABLED_CONTENT)
    val surface: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = PlayerButtonAlpha.GLASS_CONTAINER)
    val selectedSurface: Color
        @Composable get() = accent.copy(alpha = PlayerButtonAlpha.SELECTED_GLASS_CONTAINER)
    val inactiveTrack: Color
        @Composable get() = content.copy(alpha = 0.2f)
}

data class LiquidGlassSettings(
    val opacity: Float,
    val blur: Float,
    val refractionHeight: Float,
    val refractionAmount: Float,
    val depthEffect: Boolean,
    val chromaticAberration: Boolean,
    val vibrancy: Boolean,
    val saturation: Float,
    val brightness: Float,
    val contrast: Float,
    val highlightStyle: LiquidGlassHighlightStyle,
    val highlightStrength: Float,
    val highlightWidth: Float,
    val highlightBlur: Float,
    val allowRefraction: Boolean = true,
) {
    fun surfaceColor(base: Color): Color = base.copy(alpha = (base.alpha * opacity).coerceIn(0f, 1f))

    fun highlight(base: Highlight): Highlight = base.copy(
        style = when (highlightStyle) {
            LiquidGlassHighlightStyle.Component -> base.style
            LiquidGlassHighlightStyle.Default -> Highlight.Default.style
            LiquidGlassHighlightStyle.Ambient -> Highlight.Ambient.style
            LiquidGlassHighlightStyle.Plain -> Highlight.Plain.style
        },
        alpha = (base.alpha * highlightStrength).coerceIn(0f, 1f),
        width = base.width * highlightWidth,
        blurRadius = base.blurRadius * highlightBlur,
    )
}

@Composable
fun rememberLiquidGlassSettings(): LiquidGlassSettings {
    val preferences = koinInject<AppearancePreferences>()
    val hdrVideoOutput = LocalPlayerHdrOutputEnabled.current
    val opacity by preferences.liquidGlassOpacity.collectAsState()
    val blur by preferences.liquidGlassBlur.collectAsState()
    val refractionHeight by preferences.liquidGlassRefractionHeight.collectAsState()
    val refractionAmount by preferences.liquidGlassRefractionAmount.collectAsState()
    val depthEffect by preferences.liquidGlassDepthEffect.collectAsState()
    val chromaticAberration by preferences.liquidGlassChromaticAberration.collectAsState()
    val vibrancy by preferences.liquidGlassVibrancy.collectAsState()
    val saturation by preferences.liquidGlassSaturation.collectAsState()
    val brightness by preferences.liquidGlassBrightness.collectAsState()
    val contrast by preferences.liquidGlassContrast.collectAsState()
    val highlightStyle by preferences.liquidGlassHighlightStyle.collectAsState()
    val highlightStrength by preferences.liquidGlassHighlightStrength.collectAsState()
    val highlightWidth by preferences.liquidGlassHighlightWidth.collectAsState()
    val highlightBlur by preferences.liquidGlassHighlightBlur.collectAsState()
    fun Float.bounded(default: Float, range: ClosedFloatingPointRange<Float> = 0f..2f): Float =
        if (isFinite()) coerceIn(range) else default

    return remember(
        opacity, blur, refractionHeight, refractionAmount, depthEffect,
        chromaticAberration, vibrancy, saturation, brightness, contrast,
        highlightStyle, highlightStrength, highlightWidth, highlightBlur, hdrVideoOutput
    ) {
        LiquidGlassSettings(
            opacity.bounded(1f, 0f..1f), blur.bounded(1f),
            refractionHeight.bounded(1f), refractionAmount.bounded(1f),
            depthEffect, chromaticAberration, vibrancy,
            saturation.bounded(1f), brightness.bounded(0f, -1f..1f), contrast.bounded(1f),
            highlightStyle, highlightStrength.bounded(1f), highlightWidth.bounded(1f), highlightBlur.bounded(1f),
            allowRefraction = !hdrVideoOutput,
        )
    }
}

fun BackdropEffectScope.liquidGlassEffects(
    settings: LiquidGlassSettings,
    blurRadius: Float,
    refractionHeight: Float,
    refractionAmount: Float,
    vibrant: Boolean = false,
    refractionEnabled: Boolean = true,
) {
    // HDR playback in TextureView is already composited through the app window's color space.
    // Running vibrancy/color controls a second time can exaggerate clipped highlights and
    // produce muddy, stretched-looking glass. Keep a soft blur and the surface tint instead.
    if (settings.allowRefraction) {
        if (vibrant && settings.vibrancy) vibrancy()
        colorControls(settings.brightness, settings.contrast, settings.saturation)
    }
    if (settings.blur > 0f) blur(blurRadius * settings.blur)
    if (refractionEnabled && settings.allowRefraction && settings.refractionHeight > 0f && settings.refractionAmount > 0f) {
        lens(
            refractionHeight * settings.refractionHeight,
            refractionAmount * settings.refractionAmount,
            depthEffect = settings.depthEffect,
            chromaticAberration = settings.chromaticAberration,
        )
    }
}

/**
 * A drop shadow kept outside [shape]. An elevation shadow also darkens the area under the shape,
 * which translucent fills reveal as a faint band.
 */
@Composable
fun Modifier.outsideShadow(
    shape: Shape,
    radius: Dp,
    color: Color = Color.Black.copy(alpha = 0.22f),
    offsetY: Dp = radius / 4,
): Modifier {
    if (radius <= 0.dp) return this
    val shadowContext = LocalGraphicsContext.current.shadowContext
    val painter = remember(shadowContext, shape, radius, color, offsetY) {
        shadowContext.createDropShadowPainter(
            shape,
            Shadow(radius = radius, color = color, offset = DpOffset(0.dp, offsetY)),
        )
    }
    return drawWithCache {
        val shapePath = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        onDrawBehind {
            clipPath(shapePath, ClipOp.Difference) { with(painter) { draw(size) } }
        }
    }
}

/** Glass surfaces are painted on a rectangular layer; fill only the capsule so no box shows around it. */
fun DrawScope.drawCapsuleTint(color: Color) {
    drawRoundRect(color, cornerRadius = CornerRadius(size.minDimension / 2f))
}

/** [drawCapsuleTint] for any shape. */
fun DrawScope.drawShapeTint(shape: Shape, color: Color) {
    drawOutline(shape.createOutline(size, layoutDirection, this), color)
}

fun DrawScope.drawShapeTint(shape: Shape, brush: Brush) {
    drawOutline(shape.createOutline(size, layoutDirection, this), brush)
}

fun Modifier.connectedGlassRim(
    shape: Shape,
    joinedAtTop: Boolean,
    strength: Float = 1f,
    connectionProgress: Float = 1f,
): Modifier = drawWithCache {
    val edge = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.27f), Color.White.copy(alpha = 0.02f)))
    val strokeWidth = 0.75.dp.toPx()
    val outline = shape.createOutline(size, layoutDirection, this)
    val inset = strokeWidth * connectionProgress.coerceIn(0f, 1f)
    onDrawWithContent {
        drawContent()
        clipRect(top = if (joinedAtTop) inset else 0f, bottom = size.height - if (joinedAtTop) 0f else inset) {
            drawOutline(outline, brush = edge, style = Stroke(strokeWidth), alpha = strength)
        }
    }
}

suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (PointerInputChange) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        onDragStart(down)
        var pointer = down.id
        try {
            while (true) {
                val event = awaitPointerEvent()
                val anyPressed = event.changes.any { it.pressed }
                if (!anyPressed) {
                    onDragEnd()
                    break
                }
                val change = event.changes.firstOrNull { it.id == pointer } ?: event.changes.first()
                if (change.isConsumed) {
                    onDragCancel()
                    break
                }
                pointer = change.id
                onDrag(change, change.position - change.previousPosition)
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            onDragCancel()
            throw c
        }
    }
}

class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {
    private val valueAnimationSpec = spring<Float>(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring<Float>(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring<Float>(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring<Float>(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring<Float>(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            }
        ) { _, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    private var pressJob: Job? = null
    private var velocityJob: Job? = null

    fun press() {
        velocityTracker.resetTracking()
        pressJob?.cancel()
        pressJob = animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        pressJob?.cancel()
        pressJob = animationScope.launch {
            awaitFrame()
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                withTimeoutOrNull(600L) {
                    snapshotFlow { valueAnimation.value }
                        .filter { abs(it - valueAnimation.targetValue) < threshold }
                        .first()
                }
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            launch {
                valueAnimation.animateTo(targetValue, valueAnimationSpec) {
                    updateVelocity()
                }
            }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    fun snapToValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            valueAnimation.snapTo(targetValue)
            updateVelocity()
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            System.currentTimeMillis(),
            Offset(value, 0f)
        )
        val targetVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        velocityJob?.cancel()
        velocityJob = animationScope.launch {
            velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec)
        }
    }
}

private const val HIGHLIGHT_SHADER_SRC = """
    uniform float2 size;
    layout(color) uniform half4 color;
    uniform float radius;
    uniform float2 position;

    half4 main(float2 coord) {
        float dist = distance(coord, position);
        float intensity = smoothstep(radius, radius * 0.5, dist);
        return color * intensity;
    }
"""

class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {
    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var startPosition = Offset.Zero
    private var dragJob: Job? = null
    private var pressJob: Job? = null

    val pressProgress: Float get() = pressProgressAnimation.value
    val offset: Offset get() = positionAnimation.value - startPosition

    private val shader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        RuntimeShader(HIGHLIGHT_SHADER_SRC)
    } else {
        null
    }
    private val shaderBrush = shader?.let { ShaderBrush(it) }

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressProgressAnimation.value
        if (progress > 0f) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null && shaderBrush != null) {
                drawRect(
                    Color.White.copy(0.08f * progress),
                    blendMode = BlendMode.Plus
                )
                shader.apply {
                    val pos = position(size, positionAnimation.value)
                    setFloatUniform("size", size.width, size.height)
                    setColorUniform("color", Color.White.copy(0.15f * progress).toArgb())
                    setFloatUniform("radius", size.minDimension * 1.5f)
                    setFloatUniform(
                        "position",
                        pos.x.fastCoerceIn(0f, size.width),
                        pos.y.fastCoerceIn(0f, size.height)
                    )
                }
                drawRect(
                    shaderBrush,
                    blendMode = BlendMode.Plus
                )
            } else {
                drawRect(
                    Color.White.copy(0.25f * progress),
                    blendMode = BlendMode.Plus
                )
            }
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectDragGestures(
            onDragStart = { down ->
                startPosition = down.position
                dragJob?.cancel()
                pressJob?.cancel()
                pressJob = animationScope.launch {
                    launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                    launch { positionAnimation.snapTo(startPosition) }
                }
            },
            onDragEnd = {
                dragJob?.cancel()
                pressJob?.cancel()
                pressJob = animationScope.launch {
                    launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                }
            },
            onDragCancel = {
                dragJob?.cancel()
                pressJob?.cancel()
                pressJob = animationScope.launch {
                    launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                }
            }
        ) { change, _ ->
            dragJob?.cancel()
            dragJob = animationScope.launch {
                positionAnimation.snapTo(change.position)
            }
        }
    }
}

