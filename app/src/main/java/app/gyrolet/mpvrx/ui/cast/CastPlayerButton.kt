/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.cast

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import app.gyrolet.mpvrx.preferences.PlayerButton
import app.gyrolet.mpvrx.ui.liquidglass.AdaptiveControlsButton
import com.google.android.gms.cast.framework.CastButtonFactory
import androidx.compose.ui.graphics.Color as ComposeColor

/** Uses the SDK button for Cast behavior while drawing the shared player button, including its drawer tile styling. */
@Composable
fun CastPlayerButton(
  borderless: Boolean,
  buttonSize: Dp,
  onInvoked: () -> Unit = {},
  contentColor: ComposeColor? = null,
) {
  val castContentDescription =
    androidx.compose.ui.res
      .stringResource(app.gyrolet.mpvrx.R.string.ui_cast)
  var routeButton by remember { mutableStateOf<MediaRouteButton?>(null) }
  Box(modifier = Modifier.size(buttonSize), contentAlignment = Alignment.Center) {
    AdaptiveControlsButton(
      onClick = { routeButton?.performClick() },
      modifier = Modifier.size(buttonSize),
      icon = PlayerButton.CAST.icon,
      title = castContentDescription,
      color = contentColor,
      buttonSize = buttonSize,
      borderless = borderless,
    )
    AndroidView(
      factory = { context ->
        MediaRouteButton(context).apply {
          setBackgroundColor(Color.TRANSPARENT)
          CastButtonFactory.setUpMediaRouteButton(context.applicationContext, this)
          setRemoteIndicatorDrawable(ColorDrawable(Color.TRANSPARENT))
          routeButton = this
        }
      },
      update = { button ->
        button.contentDescription = castContentDescription
        button.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        button.isFocusable = false
        button.setOnClickListener { onInvoked() }
      },
      modifier = Modifier.size(0.dp),
    )
  }
}
