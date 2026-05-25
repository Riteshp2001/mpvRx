package app.gyrolet.mpvrx.exoplayer.feature.player.ui

import androidx.annotation.IntRange
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.icons.AppIcon

private const val NORMAL_MAX_PERCENTAGE = 100

fun percentage(
    value: Int,
    range: ClosedRange<Int>,
): Float = ((value - range.start - 0f) / (range.endInclusive - range.start)).coerceIn(0f, 1f)

@Composable
fun VerticalSlider(
    value: Int,
    range: ClosedRange<Int>,
    modifier: Modifier = Modifier,
    overflowValue: Int? = null,
    overflowRange: ClosedRange<Int>? = null,
    isActive: Boolean = true // ExoPlayer's view is only shown when active
) {
    val coercedValue = value.coerceIn(range)
    val trackWidthAnim = remember { Animatable(22f) }

    LaunchedEffect(isActive) {
        if (isActive) {
            trackWidthAnim.animateTo(
                targetValue = 32f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        } else {
            kotlinx.coroutines.delay(150)
            trackWidthAnim.animateTo(
                targetValue = 22f,
                animationSpec = spring(
                    dampingRatio = 0.4f,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
    }

    val trackWidth = trackWidthAnim.value.dp

    Box(
        modifier = modifier
            .height(120.dp)
            .width(32.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(trackWidth)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(16.dp),
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val targetHeight by animateFloatAsState(percentage(coercedValue, range), label = "vsliderheight")
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(targetHeight)
                    .background(MaterialTheme.colorScheme.tertiary),
            )
            if (overflowRange != null && overflowValue != null) {
                val overflowHeight by animateFloatAsState(
                    percentage(overflowValue, overflowRange),
                    label = "vslideroverflowheight",
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(overflowHeight)
                        .background(MaterialTheme.colorScheme.errorContainer),
                )
            }
        }
    }
}

@Composable
fun VerticalProgressView(
    modifier: Modifier = Modifier,
    width: Dp = 32.dp, // unused but kept for API compat
    icon: AppIcon,
    @IntRange(from = 0, to = 200) value: Int,
    maxValue: Int = NORMAL_MAX_PERCENTAGE,
    boostColor: Color = Color(0xFFFC6E6E), // unused
) {
    val displayValue = value.coerceIn(0, maxValue)
    val isBoostActive = maxValue > NORMAL_MAX_PERCENTAGE && value > NORMAL_MAX_PERCENTAGE
    
    val baseValue = if (isBoostActive) NORMAL_MAX_PERCENTAGE else displayValue
    val overflowValue = if (isBoostActive) value - NORMAL_MAX_PERCENTAGE else null
    val overflowRange = if (isBoostActive) 0..(maxValue - NORMAL_MAX_PERCENTAGE) else null

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp), // spacing smaller
        ) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            VerticalSlider(
                value = baseValue,
                range = 0..NORMAL_MAX_PERCENTAGE,
                overflowValue = overflowValue,
                overflowRange = overflowRange,
                isActive = true
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
            )
        }
    }
}
