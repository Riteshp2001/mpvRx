package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.components.LiquidButton
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

object PlayerLiquidTokens {
    val ButtonSize: Dp = 40.dp
    val CenterButtonSize: Dp = 72.dp
    val IconSize: Dp = 22.dp
    val CenterIconSize: Dp = 34.dp
    val PillHeight: Dp = 40.dp

    val contentColor: Color
        @Composable get() = Color.White

    val disabledContentColor: Color
        @Composable get() = Color.White.copy(alpha = 0.45f)

    val selectedContentColor: Color
        @Composable get() = MaterialTheme.colorScheme.primary

    val surfaceColor: Color
        @Composable get() = Color.Black.copy(alpha = 0.26f)

    val selectedSurfaceColor: Color
        @Composable get() = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
}

@Composable
fun LiquidIconButton(
    icon: AppIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    title: String? = null,
    tint: Color = PlayerLiquidTokens.contentColor,
    surfaceColor: Color = PlayerLiquidTokens.surfaceColor,
    size: Dp = PlayerLiquidTokens.ButtonSize,
    iconSize: Dp = PlayerLiquidTokens.IconSize,
    spacing: Dp = 8.dp,
    useGlass: Boolean = true,
    backdrop: Backdrop? = null,
) {
    val resolvedBackdrop = backdrop ?: LocalPlayerBackdrop.current ?: rememberLayerBackdrop()
    CompositionLocalProvider(LocalContentColor provides tint) {
        LiquidButton(
            onClick = onClick,
            onLongClick = onLongClick,
            backdrop = resolvedBackdrop,
            modifier = modifier.requiredSize(size),
            tint = tint,
            surfaceColor = surfaceColor,
            height = size,
            horizontalPadding = 0.dp,
            spacing = spacing,
            useGlass = useGlass,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
fun LiquidPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    isInteractive: Boolean = true,
    tint: Color = PlayerLiquidTokens.contentColor,
    surfaceColor: Color = PlayerLiquidTokens.surfaceColor,
    height: Dp = PlayerLiquidTokens.PillHeight,
    spacing: Dp = 8.dp,
    horizontalPadding: Dp = 16.dp,
    useGlass: Boolean = true,
    backdrop: Backdrop? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val resolvedTint = if (tint.isSpecified) tint else PlayerLiquidTokens.contentColor
    val resolvedBackdrop = backdrop ?: LocalPlayerBackdrop.current ?: rememberLayerBackdrop()
    CompositionLocalProvider(LocalContentColor provides resolvedTint) {
        LiquidButton(
            onClick = onClick,
            onLongClick = onLongClick,
            backdrop = resolvedBackdrop,
            modifier = modifier,
            tint = resolvedTint,
            surfaceColor = surfaceColor,
            isInteractive = isInteractive,
            useGlass = useGlass,
            height = height,
            horizontalPadding = horizontalPadding,
            spacing = spacing,
            content = content,
        )
    }
}

@Composable
fun LiquidActionRow(
    modifier: Modifier = Modifier,
    contentColor: Color = PlayerLiquidTokens.contentColor,
    content: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Row(modifier = modifier, content = content)
    }
}
