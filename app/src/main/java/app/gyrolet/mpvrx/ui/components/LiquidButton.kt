package app.gyrolet.mpvrx.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import app.gyrolet.mpvrx.ui.utils.InteractiveHighlight
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import org.koin.compose.koinInject
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import androidx.compose.runtime.getValue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.layout.size

val LocalScreenBackdrop = compositionLocalOf<Backdrop?> { null }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiquidButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    isInteractive: Boolean = true,
    useGlass: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    height: Dp = 48.dp,
    horizontalPadding: Dp = 16.dp,
    spacing: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val animationScope = rememberCoroutineScope()
    val contentColor = LocalContentColor.current
    val effectiveTint = if (tint.isSpecified) tint else contentColor

    val preferences = koinInject<AppearancePreferences>()
    val blurRadius by preferences.liquidButtonBlur.collectAsState()
    val lensRadius by preferences.liquidButtonLensRadius.collectAsState()
    val lensDepth by preferences.liquidButtonLensDepth.collectAsState()
    val density = androidx.compose.ui.platform.LocalDensity.current

    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(
            animationScope = animationScope
        )
    }

    Row(
        modifier
            .then(
                if (useGlass) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            vibrancy()
                            blur(with(density) { blurRadius.dp.toPx() })
                            lens(with(density) { lensRadius.dp.toPx() }, with(density) { lensDepth.dp.toPx() }, chromaticAberration = true)
                        },
                        layerBlock = if (isInteractive) {
                            {
                                val width = size.width
                                val height = size.height

                                val progress = interactiveHighlight.pressProgress
                                val scale = lerp(1f, 1f + (4f.dp.toPx() / size.height), progress)

                                val maxOffset = size.minDimension
                                val initialDerivative = 0.05f
                                val offset = interactiveHighlight.offset
                                translationX = maxOffset * tanh((initialDerivative * offset.x) / maxOffset)
                                translationY = maxOffset * tanh((initialDerivative * offset.y) / maxOffset)

                                val maxDragScale = 4f.dp.toPx() / size.height
                                val offsetAngle = atan2(offset.y, offset.x)
                                scaleX =
                                    scale +
                                            maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                                            (width / height).fastCoerceAtMost(1f)
                                scaleY =
                                    scale +
                                            maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                                            (height / width).fastCoerceAtMost(1f)
                            }
                        } else {
                            null
                        },
                        onDrawSurface = {
                            if (effectiveTint.isSpecified) {
                                drawRect(effectiveTint, blendMode = BlendMode.Screen, alpha = 0.15f)
                                drawRect(effectiveTint.copy(alpha = 0.03f))
                            }
                            if (surfaceColor.isSpecified) {
                                drawRect(surfaceColor)
                            }
                        }
                    )
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                interactionSource = null,
                indication = if (isInteractive) null else LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .then(
                if (isInteractive) {
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                }
            )
            .height(height)
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
