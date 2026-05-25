package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.components.LiquidButton
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.theme.spacing
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.koin.compose.koinInject

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AdaptiveControlsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: AppIcon? = null,
    onLongClick: () -> Unit = {},
    text: String? = null,
    title: String? = null,
    color: Color? = null,
    surfaceColor: Color = Color.Unspecified,
    buttonSize: Dp = 40.dp,
    useGlass: Boolean = true,
    backdrop: Backdrop = rememberLayerBackdrop()
) {
    val preferences = koinInject<AppearancePreferences>()
    val enableLiquidGlass by preferences.enableLiquidGlass.collectAsState()
    val clickEvent = app.gyrolet.mpvrx.ui.player.controls.LocalPlayerButtonsClickEvent.current

    if (enableLiquidGlass) {
        val resolvedTint = color ?: PlayerLiquidTokens.contentColor
        val resolvedSurface = if (surfaceColor != Color.Unspecified) surfaceColor else PlayerLiquidTokens.surfaceColor
        
        LiquidButton(
            onClick = {
                clickEvent()
                onClick()
            },
            onLongClick = onLongClick,
            backdrop = backdrop,
            modifier = modifier.height(buttonSize).widthIn(min = buttonSize),
            tint = resolvedTint,
            surfaceColor = resolvedSurface,
            height = buttonSize,
            horizontalPadding = if (text != null) 8.dp else 0.dp,
            spacing = 4.dp,
            useGlass = useGlass,
        ) {
            if (icon != null) {
                app.gyrolet.mpvrx.ui.icons.Icon(
                    imageVector = icon,
                    contentDescription = title ?: text,
                    tint = resolvedTint,
                    modifier = Modifier.size(PlayerLiquidTokens.IconSize),
                )
            }
            if (text != null) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = resolvedTint,
                    maxLines = 1,
                )
            }
        }
    } else {
        val hideBackground by preferences.hidePlayerButtonsBackground.collectAsState()
        val interactionSource = remember { MutableInteractionSource() }

        Surface(
            modifier = modifier
                .clip(CircleShape)
                .combinedClickable(
                    onClick = {
                        clickEvent()
                        onClick()
                    },
                    onLongClick = onLongClick,
                    interactionSource = interactionSource,
                    indication = ripple(),
                ),
            shape = CircleShape,
            color = if (hideBackground) Color.Transparent else if (surfaceColor != Color.Unspecified) surfaceColor else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
            contentColor = color ?: MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = if (hideBackground || !useGlass) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)),
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = if (text != null) 8.dp else 0.dp)
                    .height(buttonSize)
                    .widthIn(min = buttonSize),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) {
                    app.gyrolet.mpvrx.ui.icons.Icon(
                        imageVector = icon,
                        contentDescription = title ?: text,
                        tint = color ?: MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(if (text == null) MaterialTheme.spacing.small else 0.dp)
                            .size(20.dp),
                    )
                }
                if (text != null) {
                    if (icon != null) Spacer(Modifier.width(4.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                        color = color ?: MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun AdaptiveControlsContainer(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    color: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    isInteractive: Boolean = true,
    useGlass: Boolean = true,
    hideBackground: Boolean = false,
    buttonSize: Dp = 40.dp,
    spacing: Dp = 8.dp,
    horizontalPadding: Dp? = null,
    backdrop: Backdrop = rememberLayerBackdrop(),
    content: @Composable RowScope.() -> Unit
) {
    val preferences = koinInject<AppearancePreferences>()
    val enableLiquidGlass by preferences.enableLiquidGlass.collectAsState()
    val clickEvent = app.gyrolet.mpvrx.ui.player.controls.LocalPlayerButtonsClickEvent.current

    if (enableLiquidGlass) {
        LiquidPillButton(
            onClick = {
                if (isInteractive) clickEvent()
                onClick()
            },
            onLongClick = onLongClick,
            modifier = modifier,
            isInteractive = isInteractive,
            useGlass = useGlass,
            tint = if (color != Color.Unspecified) color else PlayerLiquidTokens.contentColor,
            surfaceColor = if (surfaceColor != Color.Unspecified) surfaceColor else PlayerLiquidTokens.surfaceColor,
            height = buttonSize,
            spacing = spacing,
            horizontalPadding = horizontalPadding ?: 8.dp,
            backdrop = backdrop,
            content = content
        )
    } else {
        Surface(
            shape = CircleShape,
            color = if (hideBackground) Color.Transparent else if (surfaceColor != Color.Unspecified) surfaceColor else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
            contentColor = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = if (hideBackground || !useGlass) null else BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f),
            ),
            modifier = modifier
                .height(buttonSize)
                .clip(CircleShape)
                .then(
                    if (isInteractive) {
                        @OptIn(ExperimentalFoundationApi::class)
                        Modifier.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true),
                            onClick = {
                                clickEvent()
                                onClick()
                            },
                            onLongClick = onLongClick
                        )
                    } else {
                        Modifier
                    }
                ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = horizontalPadding ?: MaterialTheme.spacing.small),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    content = content
                )
            }
        }
    }
}
