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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.util.fastCoerceIn
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.opacity
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.LiquidGlassHighlightStyle
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.player.controls.PlayerButtonAlpha
import org.koin.compose.koinInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

val LocalKyantPlayerBackdrop = staticCompositionLocalOf<Backdrop?> { null }

object LiquidControlColors {
    val accent: Color
        @Composable get() = MaterialTheme.colorScheme.primary
    val content: Color
        @Composable get() = Color.White
    val disabledContent: Color
        @Composable get() = content.copy(alpha = PlayerButtonAlpha.DISABLED_CONTENT)
    // Minimal clear surface film: refraction comes from the live screen/video backdrop.
    val surface: Color
        @Composable get() = Color.White.copy(alpha = 0.10f)
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
    val shadowStrength: Float,
    val shadowRadius: Float,
    val innerShadowStrength: Float,
    val innerShadowRadius: Float,
) {
    fun surfaceColor(base: Color): Color = base

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

    fun shadow(base: Shadow): Shadow = base.copy(
        color = base.color.copy(alpha = (base.color.alpha * shadowStrength).coerceIn(0f, 1f)),
        radius = base.radius * shadowRadius,
        offset = DpOffset(base.offset.x * shadowRadius, base.offset.y * shadowRadius),
    )

    fun innerShadow(base: InnerShadow): InnerShadow = base.copy(
        color = base.color.copy(alpha = (base.color.alpha * innerShadowStrength).coerceIn(0f, 1f)),
        radius = base.radius * innerShadowRadius,
        offset = DpOffset(base.offset.x * innerShadowRadius, base.offset.y * innerShadowRadius),
    )
}

@Composable
fun rememberLiquidGlassSettings(): LiquidGlassSettings {
    val preferences = koinInject<AppearancePreferences>()
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
    val shadowStrength by preferences.liquidGlassShadowStrength.collectAsState()
    val shadowRadius by preferences.liquidGlassShadowRadius.collectAsState()
    val innerShadowStrength by preferences.liquidGlassInnerShadowStrength.collectAsState()
    val innerShadowRadius by preferences.liquidGlassInnerShadowRadius.collectAsState()
    fun Float.bounded(default: Float, range: ClosedFloatingPointRange<Float> = 0f..2f): Float =
        if (isFinite()) coerceIn(range) else default

    return LiquidGlassSettings(
        opacity.bounded(1f, 0f..1f), blur.bounded(1f),
        refractionHeight.bounded(1f), refractionAmount.bounded(1f),
        depthEffect, chromaticAberration, vibrancy,
        saturation.bounded(1f), brightness.bounded(0f, -1f..1f), contrast.bounded(1f),
        highlightStyle, highlightStrength.bounded(1f), highlightWidth.bounded(1f), highlightBlur.bounded(1f),
        shadowStrength.bounded(1f), shadowRadius.bounded(1f),
        innerShadowStrength.bounded(1f), innerShadowRadius.bounded(1f),
    )
}

fun BackdropEffectScope.liquidGlassEffects(
    settings: LiquidGlassSettings,
    blurRadius: Float,
    refractionHeight: Float,
    refractionAmount: Float,
    vibrant: Boolean = false,
    refractionEnabled: Boolean = true,
) {
    if (vibrant && settings.vibrancy) vibrancy()
    colorControls(settings.brightness, settings.contrast, settings.saturation)
    if (settings.blur > 0f) blur(blurRadius * settings.blur)
    if (refractionEnabled && settings.refractionHeight > 0f && settings.refractionAmount > 0f) {
        lens(
            refractionHeight * settings.refractionHeight,
            refractionAmount * settings.refractionAmount,
            depthEffect = settings.depthEffect,
            chromaticAberration = settings.chromaticAberration,
        )
    }
    if (settings.opacity < 1f) opacity(settings.opacity)
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
        while (true) {
            val event = awaitPointerEvent()
            val anyPressed = event.changes.any { it.pressed }
            if (!anyPressed) {
                onDragEnd()
                break
            }
            val change = event.changes.firstOrNull { it.id == pointer } ?: event.changes.first()
            pointer = change.id
            onDrag(change, change.position - change.previousPosition)
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

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            awaitFrame()
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
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
        animationScope.launch {
            velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec)
        }
    }
}

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

    val pressProgress: Float get() = pressProgressAnimation.value
    val offset: Offset get() = positionAnimation.value - startPosition

    private val shader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        RuntimeShader(
            """
            uniform float2 size;
            layout(color) uniform half4 color;
            uniform float radius;
            uniform float2 position;

            half4 main(float2 coord) {
                float dist = distance(coord, position);
                float intensity = smoothstep(radius, radius * 0.5, dist);
                return color * intensity;
            }
            """.trimIndent()
        )
    } else {
        null
    }

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressProgressAnimation.value
        if (progress > 0f) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
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
                    ShaderBrush(shader),
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
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                    launch { positionAnimation.snapTo(startPosition) }
                }
            },
            onDragEnd = {
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                }
            },
            onDragCancel = {
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                }
            }
        ) { change, _ ->
            animationScope.launch {
                positionAnimation.snapTo(change.position)
            }
        }
    }
}
