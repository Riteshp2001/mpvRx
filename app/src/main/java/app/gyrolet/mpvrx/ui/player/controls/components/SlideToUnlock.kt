/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppMotion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SlideToUnlock(
  onUnlock: () -> Unit,
  modifier: Modifier = Modifier,
  onDraggingChanged: (Boolean) -> Unit = {},
) {
  val coroutineScope = rememberCoroutineScope()
  val sliderSize = 56.dp
  val offsetX = remember { Animatable(0f) }
  var isDragging by remember { mutableStateOf(false) }

  Box(
    modifier =
      modifier
        .width(220.dp)
        .height(64.dp)
        .clip(RoundedCornerShape(32.dp))
        .background(Color.Black.copy(alpha = 0.6f))
        .padding(4.dp),
  ) {
    BoxWithConstraints(
      modifier = Modifier.fillMaxSize(),
    ) {
      val maxOffset = with(LocalDensity.current) { (maxWidth - sliderSize).toPx().coerceAtLeast(0f) }
      val unlockThreshold = if (maxOffset > 0f) maxOffset * 0.85f else Float.MAX_VALUE

      // Background text - positioned in the open area to the right of the handle
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(start = sliderSize, end = 12.dp)
            .graphicsLayer {
              alpha = if (maxOffset > 0f) 1f - (offsetX.value / maxOffset).coerceIn(0f, 1f) else 1f
            },
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = stringResource(R.string.ui_slide_to_unlock),
          color = Color.White.copy(alpha = 0.7f),
          fontSize = 15.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center,
        )
      }

      // Slider button
      val progress = if (maxOffset > 0f) (offsetX.value / maxOffset).coerceIn(0f, 1f) else 0f
      val showUnlockIcon = progress > 0.5f

      Box(
        modifier =
          Modifier
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .size(sliderSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .pointerInput(maxOffset) {
              if (maxOffset <= 0f) return@pointerInput

              detectHorizontalDragGestures(
                onDragStart = {
                  isDragging = true
                  onDraggingChanged(true)
                },
                onDragEnd = {
                  isDragging = false
                  onDraggingChanged(false)
                  if (offsetX.value >= unlockThreshold) {
                    // Unlock triggered - instantly unlock without animation
                    onUnlock()
                  } else {
                    // Snap back
                    coroutineScope.launch {
                      offsetX.animateTo(
                        targetValue = 0f,
                        animationSpec =
                          spring(
                            dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
                            stiffness = AppMotion.Spatial.Expressive.stiffness,
                          ),
                      )
                    }
                  }
                },
                onDragCancel = {
                  isDragging = false
                  onDraggingChanged(false)
                  coroutineScope.launch {
                    offsetX.animateTo(
                      targetValue = 0f,
                      animationSpec =
                        spring(
                          dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
                          stiffness = AppMotion.Spatial.Expressive.stiffness,
                        ),
                    )
                  }
                },
                onHorizontalDrag = { _, dragAmount ->
                  coroutineScope.launch {
                    val newValue = (offsetX.value + dragAmount).coerceIn(0f, maxOffset)
                    offsetX.snapTo(newValue)
                  }
                },
              )
            },
        contentAlignment = Alignment.Center,
      ) {
        // Crossfade between lock and unlock icons
        androidx.compose.animation.Crossfade(
          targetState = showUnlockIcon,
          animationSpec =
            spring(
              dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
              stiffness = AppMotion.Spatial.Standard.stiffness,
            ),
        ) { showUnlock ->
          Icon(
            imageVector = if (showUnlock) Icons.RoundedFilled.LockOpen else Icons.RoundedFilled.Lock,
            contentDescription = stringResource(R.string.ui_slide_to_unlock),
            tint = Color.White,
            modifier = Modifier.size(28.dp),
          )
        }
      }
    }
  }
}
