/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.cast

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import app.gyrolet.mpvrx.preferences.PlayerButton
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.theme.controlColor
import com.google.android.gms.cast.framework.CastButtonFactory
import androidx.compose.ui.graphics.Color as ComposeColor

/** Uses the SDK button for Cast behavior while keeping the app's rounded symbol visible. */
@Composable
fun CastPlayerButton(
  hideBackground: Boolean,
  buttonSize: Dp,
) {
  val castContentDescription =
    androidx.compose.ui.res
      .stringResource(app.gyrolet.mpvrx.R.string.ui_cast)
  Surface(
    shape = CircleShape,
    color =
      if (hideBackground) {
        ComposeColor.Transparent
      } else {
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)
      },
    contentColor = if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
    border =
      if (hideBackground) {
        null
      } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
      },
    modifier = Modifier.size(buttonSize),
  ) {
    Box(contentAlignment = Alignment.Center) {
      AndroidView(
        factory = { context ->
          MediaRouteButton(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = castContentDescription
            CastButtonFactory.setUpMediaRouteButton(context.applicationContext, this)
            setRemoteIndicatorDrawable(ColorDrawable(Color.TRANSPARENT))
          }
        },
        modifier = Modifier.fillMaxSize(),
      )
      Icon(
        imageVector = PlayerButton.CAST.icon,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
      )
    }
  }
}
