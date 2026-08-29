/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.player.thumbfast.ThumbFastPreviewState
import `is`.xyz.mpv.Utils
import kotlin.math.roundToInt

@Composable
fun ThumbFastPreviewBubble(
  previewState: ThumbFastPreviewState,
  isPortrait: Boolean,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visible = previewState.isVisible && previewState.durationSeconds > 0f,
    enter = fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.88f, animationSpec = tween(180)),
    exit = fadeOut(animationSpec = tween(150)) + scaleOut(targetScale = 0.88f, animationSpec = tween(150)),
    modifier = modifier.fillMaxWidth(),
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
      val previewWidth = if (isPortrait) 160.dp else 144.dp
      val progress = previewState.normalizedXFraction.coerceIn(0f, 1f)
      val maxOffset = (maxWidth - previewWidth).coerceAtLeast(0.dp)
      val xOffset = maxOffset * progress
      val cardShape = RoundedCornerShape(12.dp)

      Column(
        modifier =
          Modifier
            .offset { IntOffset(xOffset.roundToPx(), 0) }
            .width(previewWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // Chapter Title Header
        previewState.chapterTitle?.takeIf { it.isNotBlank() }?.let { title ->
          Surface(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(bottom = 5.dp),
            shape = RoundedCornerShape(999.dp),
            color = Color.Black.copy(alpha = 0.85f),
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            tonalElevation = 0.dp,
          ) {
            Text(
              text = title,
              modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              textAlign = TextAlign.Center,
            )
          }
        }

        // Preview Card
        Surface(
          modifier =
            Modifier
              .fillMaxWidth()
              .aspectRatio(16f / 9f)
              .clip(cardShape),
          shape = cardShape,
          color = Color.Black.copy(alpha = 0.85f),
          contentColor = Color.White,
          border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
          tonalElevation = 0.dp,
          shadowElevation = 12.dp,
        ) {
          Box(modifier = Modifier.fillMaxSize()) {
            Crossfade(
              targetState = previewState.bitmap,
              animationSpec = tween(120),
              label = "ThumbFastBitmapCrossfade",
              modifier = Modifier.fillMaxSize(),
            ) { targetBitmap ->
              val imageBitmap = remember(targetBitmap) { targetBitmap?.takeUnless { it.isRecycled }?.asImageBitmap() }
              if (imageBitmap != null) {
                Image(
                  bitmap = imageBitmap,
                  contentDescription = null,
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.fillMaxSize(),
                )
              } else {
                Box(
                  modifier =
                    Modifier
                      .fillMaxSize()
                      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                )
              }
            }

            if (previewState.isLoading) {
              Box(
                modifier =
                  Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center,
              ) {
                CircularProgressIndicator(
                  modifier = Modifier.size(20.dp),
                  color = Color.White,
                  strokeWidth = 2.dp,
                )
              }
            }
          }
        }

        // Floating Timestamp & Delta Badges
        Surface(
          modifier = Modifier.padding(top = 6.dp),
          shape = RoundedCornerShape(999.dp),
          color = Color.Black.copy(alpha = 0.85f),
          contentColor = Color.White,
          border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
          tonalElevation = 0.dp,
        ) {
          val timeText =
            if (previewState.formattedTime.isNotBlank()) {
              previewState.formattedTime
            } else {
              Utils.prettyTime(previewState.positionSeconds.toInt(), false)
            }

          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = timeText,
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Medium,
            )

            previewState.relativeDeltaText?.takeIf { it.isNotBlank() }?.let { delta ->
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "($delta)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
              )
            }
          }
        }
      }
    }
  }
}
