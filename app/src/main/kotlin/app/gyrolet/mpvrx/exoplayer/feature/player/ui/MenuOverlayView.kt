package app.gyrolet.mpvrx.exoplayer.feature.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.presentation.components.PlayerSheet

sealed interface MenuRoute {
    data object Root : MenuRoute
    data object SleepTimer : MenuRoute
    data object Decoder : MenuRoute
    data object PlaybackSpeed : MenuRoute
    data object Audio : MenuRoute
    data object Subtitle : MenuRoute
    data object Playlist : MenuRoute
    data object VideoContentScale : MenuRoute
    data object VideoFilters : MenuRoute
}

@Composable
fun BoxScope.MenuOverlayView(
    externalRoute: MenuRoute?,
    title: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    content: @Composable (MenuRoute) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val endPadding = WindowInsets.safeDrawing
        .asPaddingValues()
        .calculateEndPadding(layoutDirection)

    if (externalRoute != null) {
        PlayerSheet(
            onDismissRequest = {
                // If we can go back, back should probably pop the route rather than close the entire sheet,
                // or just let onBack handle it. But typically a swipe down should dismiss entirely.
                // We'll call onBack and expect the caller to handle dismissing if we are at root.
                onBack()
            },
            modifier = Modifier.testTag("panel_player_menu")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 16.dp, bottom = 16.dp)
                    .padding(end = endPadding),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (canGoBack) {
                        IconButton(
                            modifier = Modifier.testTag("btn_menu_back"),
                            onClick = onBack,
                        ) {
                            Icon(
                                imageVector = NextIcons.ArrowBack,
                                contentDescription = stringResource(R.string.navigate_up),
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
                AnimatedContent(
                    targetState = externalRoute,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "menu_route",
                    modifier = Modifier.fillMaxSize(),
                ) { route ->
                    content(route)
                }
            }
        }
    }
}
