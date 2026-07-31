/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.browser.fab

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.coroutines.launch

import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import org.koin.compose.koinInject

/**
 * A Quick Play Floating Action Button that appears on the main screen.
 * When tapped, it immediately resumes playback of the most recently played video.
 * The FAB is only visible when there is a recently played video available and enabled in settings.
 *
 * @param visible Controls overall visibility (e.g., hidden during selection mode or when permission is denied)
 * @param bottomPadding Bottom padding to position above the navigation bar
 * @param modifier Modifier for the FAB
 */
@Composable
fun QuickPlayFab(
  visible: Boolean,
  bottomPadding: Dp,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val showQuickPlayFab by appearancePreferences.showQuickPlayFab.collectAsState()
  var hasRecentlyPlayed by remember { mutableStateOf(false) }
  var isPressed by remember { mutableStateOf(false) }

  // Check for recently played videos on composition
  LaunchedEffect(Unit) {
    hasRecentlyPlayed = RecentlyPlayedOps.hasRecentlyPlayed()
  }

  // Pulse animation scale
  val scale by animateFloatAsState(
    targetValue = if (isPressed) 0.9f else 1f,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioMediumBouncy,
      stiffness = Spring.StiffnessMedium,
    ),
    label = "quick_play_scale",
  )

  AnimatedVisibility(
    visible = visible && hasRecentlyPlayed && showQuickPlayFab,
    enter = scaleIn(
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
      ),
    ) + fadeIn(),
    exit = scaleOut(
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
      ),
    ) + fadeOut(),
    modifier = modifier,
  ) {
    FloatingActionButton(
      onClick = {
        isPressed = true
        coroutineScope.launch {
          val lastPlayedEntity = RecentlyPlayedOps.getLastPlayedEntity()
          if (lastPlayedEntity != null) {
            MediaUtils.playFile(
              source = lastPlayedEntity.filePath,
              context = context,
              launchSource = "quick_play_fab",
              title = lastPlayedEntity.videoTitle?.takeIf { it.isNotBlank() }
                ?: lastPlayedEntity.fileName.takeIf { it.isNotBlank() },
            )
          }
          isPressed = false
        }
      },
      modifier = Modifier
        .padding(bottom = bottomPadding)
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        },
      containerColor = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      elevation = FloatingActionButtonDefaults.elevation(
        defaultElevation = 6.dp,
        pressedElevation = 12.dp,
      ),
    ) {
      Icon(
        Icons.RoundedFilled.PlayArrow,
        contentDescription = stringResource(R.string.ui_quick_play),
        modifier = Modifier.size(28.dp),
      )
    }
  }
}
