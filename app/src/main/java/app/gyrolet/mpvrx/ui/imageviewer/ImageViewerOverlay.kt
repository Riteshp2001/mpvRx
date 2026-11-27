/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.imageviewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.tv.tvOverscanPadding

@Composable
fun ImageViewerOverlay(
  title: String,
  currentIndex: Int,
  totalCount: Int,
  isVisible: Boolean,
  onCloseClick: () -> Unit,
  onRotateClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visible = isVisible,
    enter = fadeIn(),
    exit = fadeOut(),
    modifier = modifier,
  ) {
    val topScrimBrush =
      remember {
        Brush.verticalGradient(
          colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent),
        )
      }
    Box(modifier = Modifier.fillMaxSize()) {
      // Top gradient scrim with back button, title, rotate button
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .background(topScrimBrush)
            .statusBarsPadding()
            .tvOverscanPadding(bottom = false)
            .padding(horizontal = 8.dp, vertical = 8.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          IconButton(onClick = onCloseClick) {
            Icon(
              imageVector = Icons.RoundedFilled.ArrowBack,
              contentDescription = "Back",
              tint = Color.White,
            )
          }

          Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )

          IconButton(onClick = onRotateClick) {
            Icon(
              imageVector = Icons.RoundedFilled.ScreenRotation,
              contentDescription = "Rotate",
              tint = Color.White,
            )
          }
        }
      }

      // Bottom page indicator
      if (totalCount > 1) {
        Text(
          text = "${currentIndex + 1} / $totalCount",
          style = MaterialTheme.typography.labelLarge,
          color = Color.White,
          modifier =
            Modifier
              .align(Alignment.BottomCenter)
              .padding(bottom = 24.dp)
              .background(
                Color.Black.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
              ).padding(horizontal = 12.dp, vertical = 6.dp),
        )
      }
    }
  }
}
