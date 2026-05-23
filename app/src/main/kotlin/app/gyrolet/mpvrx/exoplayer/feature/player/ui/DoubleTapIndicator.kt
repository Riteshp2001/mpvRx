package app.gyrolet.mpvrx.exoplayer.feature.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons
import app.gyrolet.mpvrx.ui.icons.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import app.gyrolet.mpvrx.exoplayer.core.model.DoubleTapGesture
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.feature.player.state.TapGestureState

@Composable
fun DoubleTapIndicator(modifier: Modifier = Modifier, tapGestureState: TapGestureState) {
    if (tapGestureState.seekMillis == 0L) return
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = if (tapGestureState.seekMillis > 0) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(
                    fraction = when (tapGestureState.doubleTapGesture) {
                        DoubleTapGesture.PLAY_PAUSE -> 0f
                        DoubleTapGesture.FAST_FORWARD_AND_REWIND -> 0.5f
                        DoubleTapGesture.BOTH -> 0.35f
                        DoubleTapGesture.NONE -> 0f
                    },
                )
                .clip(if (tapGestureState.seekMillis > 0) RightSideOvalShape() else LeftSideOvalShape())
                .background(Color.White.copy(0.2f))
                .indication(tapGestureState.interactionSource, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DoubleTapSeekTriangles(isForward = tapGestureState.seekMillis > 0)
                Text(
                    text = "%+ds".format(tapGestureState.seekMillis.milliseconds.inWholeSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun DoubleTapSeekTriangles(
    isForward: Boolean,
    modifier: Modifier = Modifier,
) {
    val animationDuration = 750L

    val alpha1 = remember { Animatable(0f) }
    val alpha2 = remember { Animatable(0f) }
    val alpha3 = remember { Animatable(0f) }

    LaunchedEffect(animationDuration) {
        while (true) {
            alpha1.animateTo(1f, animationSpec = tween((animationDuration / 5).toInt()))
            alpha2.animateTo(1f, animationSpec = tween((animationDuration / 5).toInt()))
            alpha3.animateTo(1f, animationSpec = tween((animationDuration / 5).toInt()))
            alpha1.animateTo(0f, animationSpec = tween((animationDuration / 5).toInt()))
            alpha2.animateTo(0f, animationSpec = tween((animationDuration / 5).toInt()))
            alpha3.animateTo(0f, animationSpec = tween((animationDuration / 5).toInt()))
        }
    }

    val rotation = if (isForward) 0f else 180f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.rotate(rotation),
    ) {
        DoubleTapArrow(alpha1.value)
        DoubleTapArrow(alpha2.value)
        DoubleTapArrow(alpha3.value)
    }
}

private class RightSideOvalShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = Path().apply {
            moveTo(size.width, size.height)
            lineTo(size.width, 0f)
            lineTo(size.width * 0.1f, 0f)
            cubicTo(
                size.width * 0.1f,
                0f,
                -size.width * 0.1f,
                size.height / 2,
                size.width * 0.1f,
                size.height,
            )
            close()
        }
        return Outline.Generic(path)
    }
}

private class LeftSideOvalShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width * 0.9f, 0f)
            cubicTo(
                size.width * 0.9f,
                0f,
                size.width * 1.1f,
                size.height / 2,
                size.width * 0.9f,
                size.height,
            )
            lineTo(0f, size.height)
            close()
        }
        return Outline.Generic(path)
    }
}

@Composable
private fun DoubleTapArrow(alpha: Float) {
    Icon(
        imageVector = NextIcons.Play,
        contentDescription = null,
        modifier = Modifier
            .size(20.dp)
            .graphicsLayer { this.alpha = alpha },
        tint = Color.White,
    )
}
