/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.imageviewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

@Composable
fun ZoomableImage(
  bitmap: Bitmap?,
  placeholder: Bitmap?,
  isLoading: Boolean,
  error: Throwable?,
  onRetry: () -> Unit,
  rotationDegrees: Float,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
) {
  val zoomableState =
    rememberZoomableState(
      zoomSpec = ZoomSpec(maxZoomFactor = 5f),
    )
  BoxWithConstraints(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    // A quarter turn swaps the axes, so the box an image must fit into is the screen seen sideways.
    // Sizing against the unswapped axes is what pushed a rotated image past the screen edges and
    // cropped it — a rotated full-body portrait lost its head and feet.
    val quarterTurned = rotationDegrees.toInt() % 180 != 0
    val fitWidth = if (quarterTurned) maxHeight else maxWidth
    val fitHeight = if (quarterTurned) maxWidth else maxHeight

    when {
      bitmap != null -> {
        val imageAspect = remember(bitmap) { bitmap.width.toFloat() / bitmap.height.toFloat() }
        val fitted = fitInto(imageAspect, fitWidth, fitHeight)
        // The gesture surface stays full-screen so a zoomed image can use all of it and a pinch can
        // start in the blank space around the image; putting the fit constraint here instead would
        // confine zoomed content to the image's own rectangle. Telephoto assumes that surface is the
        // whole content, so it has to be told the rectangle the image really covers, or it anchors a
        // pinch in the letterbox to the empty space under the fingers.
        val density = LocalDensity.current
        val contentSize =
          remember(fitted, quarterTurned, density) {
            with(density) {
              // A quarter turn puts the image on screen with its axes swapped.
              if (quarterTurned) {
                Size(fitted.height.toPx(), fitted.width.toPx())
              } else {
                Size(fitted.width.toPx(), fitted.height.toPx())
              }
            }
          }
        LaunchedEffect(zoomableState, contentSize) {
          zoomableState.setContentLocation(
            ZoomableContentLocation.scaledInsideAndCenterAligned(contentSize),
          )
        }
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .zoomable(
                state = zoomableState,
                onClick = { onClick() },
              ),
          contentAlignment = Alignment.Center,
        ) {
          Box(
            modifier =
              Modifier
                .size(fitted.width, fitted.height)
                .rotate(rotationDegrees),
          ) {
            Image(
              bitmap = bitmap.asImageBitmap(),
              contentDescription = null,
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Fit,
            )
          }
        }
      }

      // Also true when the thumbnail is ready but the full-size load has not reported in yet, so the
      // placeholder shows immediately instead of flashing the "no image" glyph first. An error wins
      // so a failed page still offers its retry button.
      placeholder != null && error == null -> {
        val placeholderAspect =
          remember(placeholder) { placeholder.width.toFloat() / placeholder.height.toFloat() }
        val fitted = fitInto(placeholderAspect, fitWidth, fitHeight)
        Image(
          bitmap = remember(placeholder) { placeholder.asImageBitmap() },
          contentDescription = null,
          modifier =
            Modifier
              .size(fitted.width, fitted.height)
              .rotate(rotationDegrees)
              .background(Color.Black),
          contentScale = ContentScale.Fit,
          alpha = 0.5f,
        )
      }

      isLoading -> {
        CircularProgressIndicator(
          modifier = Modifier.size(48.dp),
          color = MaterialTheme.colorScheme.primary,
        )
      }

      error != null -> {
        Column(
          modifier = Modifier.fillMaxSize(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          Text(
            text = stringResource(R.string.ui_image_load_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(12.dp))
          FilledTonalButton(onClick = onRetry) {
            Icon(
              imageVector = Icons.RoundedFilled.Refresh,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.ui_retry))
          }
        }
      }

      else -> {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.Image,
            contentDescription = "No image",
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
          )
        }
      }
    }
  }
}

/** Largest `w x h` with [aspect] that fits inside [maxWidth] x [maxHeight]. */
private fun fitInto(
  aspect: Float,
  maxWidth: Dp,
  maxHeight: Dp,
): DpSize {
  val widthFromHeight = maxHeight * aspect
  return if (widthFromHeight <= maxWidth) {
    DpSize(widthFromHeight, maxHeight)
  } else {
    DpSize(maxWidth, maxWidth / aspect)
  }
}
