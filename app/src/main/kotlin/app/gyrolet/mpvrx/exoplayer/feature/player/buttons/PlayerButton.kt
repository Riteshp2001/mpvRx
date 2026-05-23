package app.gyrolet.mpvrx.exoplayer.feature.player.buttons

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerIconStyle
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons
import app.gyrolet.mpvrx.exoplayer.feature.player.LocalPlayerIconStyle
import app.gyrolet.mpvrx.ui.icons.Icon

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerButton(
    modifier: Modifier = Modifier,
    buttonSize: Dp = 40.dp,
    isEnabled: Boolean = true,
    isSelected: Boolean = false,
    label: String? = null,
    shouldShowSelectionBadge: Boolean = isSelected,
    shouldDimWhenUnselected: Boolean = false,
    shouldShowCustomizeFrame: Boolean = false,
    isInteractive: Boolean = true,
    isOutlineOnly: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val viewConfiguration = LocalViewConfiguration.current
    val hapticFeedback = LocalHapticFeedback.current
    val isCustomizingButton = label != null

    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val currentIsInteractive by rememberUpdatedState(isInteractive)

    LaunchedEffect(interactionSource) {
        var isLongPressClicked = false
        interactionSource.interactions.collectLatest { interaction ->
            if (!currentIsInteractive) return@collectLatest
            when (interaction) {
                is PressInteraction.Press -> {
                    isLongPressClicked = false
                    delay(viewConfiguration.longPressTimeoutMillis)
                    currentOnLongClick?.let {
                        isLongPressClicked = true
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        it.invoke()
                    }
                }

                is PressInteraction.Release -> {
                    if (!isLongPressClicked) {
                        currentOnClick()
                    }
                }
            }
        }
    }

    val colorScheme = MaterialTheme.colorScheme
    val playerIconStyle = LocalPlayerIconStyle.current
    val selectionBadgeBackgroundColor = colorScheme.primaryContainer
    val selectionBadgeInactiveColor = colorScheme.surfaceContainerHighest
    val customizeBorderColor = Color(0xFFFFEB3B)
    val outlineBorderColor = colorScheme.primary.copy(alpha = 0.95f)
    val outlineFillColor = colorScheme.primary.copy(alpha = 0.12f)
    val customizeLabelColor = Color.White
    val outlineLabelColor = colorScheme.primary.copy(alpha = 0.75f)
    val selectionBadgeSize = if (buttonSize >= 56.dp) 20.dp else 18.dp
    val selectionBadgeIconSize = if (buttonSize >= 56.dp) 13.dp else 12.dp

    val buttonContent: @Composable () -> Unit = {
        if (!isOutlineOnly) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
        }
    }

    val outlineModifier = Modifier.drawBehind {
        val strokeWidth = 1.5.dp.toPx()
        drawCircle(
            color = outlineFillColor,
            radius = size.minDimension / 2f,
        )
        drawCircle(
            color = outlineBorderColor,
            style = Stroke(
                width = strokeWidth,
                pathEffect = PathEffect.dashPathEffect(
                    intervals = floatArrayOf(12f, 12f),
                ),
            ),
            radius = size.minDimension / 2f - (strokeWidth / 2f),
        )
    }

    val whiteRippleConfiguration = RippleConfiguration(
        color = Color.White,
        rippleAlpha = RippleAlpha(
            pressedAlpha = 0.5f,
            focusedAlpha = 0.5f,
            draggedAlpha = 0.5f,
            hoveredAlpha = 0.5f,
        ),
    )

    val buttonWithBadge: @Composable () -> Unit = {
        Box(
            modifier = Modifier.size(buttonSize),
            contentAlignment = Alignment.Center,
        ) {
            when (playerIconStyle) {
                PlayerIconStyle.CLASSIC -> {
                    val backgroundModifier = if (isOutlineOnly) outlineModifier else Modifier
                    CompositionLocalProvider(
                        LocalContentColor provides if (isOutlineOnly) colorScheme.primary else Color.White,
                        LocalRippleConfiguration provides whiteRippleConfiguration,
                    ) {
                        IconButton(
                            onClick = {},
                            enabled = isEnabled,
                            modifier = Modifier.size(buttonSize).then(backgroundModifier),
                            interactionSource = interactionSource,
                            content = buttonContent,
                        )
                    }
                }

                PlayerIconStyle.TONAL, PlayerIconStyle.TRANSLUCENT -> {
                    val containerColor = when {
                        isOutlineOnly -> colorScheme.surface.copy(alpha = 0f)
                        playerIconStyle == PlayerIconStyle.TRANSLUCENT -> colorScheme.primary.copy(alpha = 0.20f)
                        else -> colorScheme.primary
                    }
                    val contentColor = when {
                        isOutlineOnly -> colorScheme.primary
                        playerIconStyle == PlayerIconStyle.TRANSLUCENT -> Color.White
                        else -> colorScheme.onPrimary
                    }
                    FilledTonalIconButton(
                        onClick = {},
                        enabled = isEnabled,
                        modifier = Modifier.size(buttonSize).then(if (isOutlineOnly) outlineModifier else Modifier),
                        interactionSource = interactionSource,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = containerColor,
                            contentColor = contentColor,
                            disabledContainerColor = colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                            disabledContentColor = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        ),
                        content = buttonContent,
                    )
                }
            }

            if (shouldShowSelectionBadge && !isOutlineOnly) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(selectionBadgeSize)
                        .background(
                            color = if (isSelected) selectionBadgeBackgroundColor else selectionBadgeInactiveColor,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = NextIcons.Check,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(selectionBadgeIconSize),
                        )
                    }
                }
            }
        }
    }

    if (!isCustomizingButton) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            buttonWithBadge()
        }
        return
    }

    Column(
        modifier = modifier
            .alpha(if (shouldDimWhenUnselected && !isSelected && !isOutlineOnly) 0.5f else 1f)
            .widthIn(min = buttonSize + 16.dp, max = 88.dp)
            .then(
                if (shouldShowCustomizeFrame) {
                    Modifier.drawBehind {
                        val strokeWidth = 1.dp.toPx()
                        drawRoundRect(
                            color = if (isOutlineOnly) outlineBorderColor else customizeBorderColor,
                            style = Stroke(
                                width = strokeWidth,
                                pathEffect = PathEffect.dashPathEffect(
                                    intervals = floatArrayOf(12f, 12f),
                                ),
                            ),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                        )
                        if (isOutlineOnly) {
                            drawRoundRect(
                                color = outlineFillColor,
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                            )
                        }
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        buttonWithBadge()
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isOutlineOnly) outlineLabelColor else customizeLabelColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
