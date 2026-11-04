/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.appPressFeedback
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor

/**
 * A theme preview card that displays a mini preview of the app UI with the theme's colors.
 * Inspired by Aniyomi's theme picker design.
 */
@Composable
fun ThemePreviewCard(
  label: String,
  colorScheme: ColorScheme,
  isSelected: Boolean,
  onClick: (Offset) -> Unit,
  modifier: Modifier = Modifier,
  actionOverlay: (@Composable () -> Unit)? = null,
  enabled: Boolean = true,
) {
  var cardCenter by remember { mutableStateOf(Offset.Zero) }
  val isTelevision = DeviceFormFactor.isTelevision(LocalContext.current)
  // Use the current MaterialTheme primary for selection to ensure visibility
  val selectionColor = MaterialTheme.colorScheme.primary
  val interactionSource = remember { MutableInteractionSource() }

  val borderWidth = if (isSelected) 3.dp else 1.dp

  val borderColor by animateColorAsState(
    targetValue = if (isSelected) selectionColor else Color.Transparent,
    animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
    label = "themeSelectionOutline",
  )

  val elevation = if (isSelected) 8.dp else 2.dp

  Column(
    modifier =
      modifier
        .width(100.dp)
        .appPressFeedback(interactionSource, enabled, pressedScale = 0.96f)
        .semantics { selected = isSelected }
        .onGloballyPositioned {
          val bounds = it.boundsInWindow()
          cardCenter = bounds.center
        }.then(
          if (!enabled) {
            Modifier
          } else if (isTelevision) {
            Modifier
              .tvFocusHighlight(RoundedCornerShape(12.dp), focusedScale = 1.05f)
              .clickable(interactionSource, LocalIndication.current) { onClick(cardCenter) }
          } else {
            Modifier.clickable(interactionSource, LocalIndication.current) { onClick(cardCenter) }
          },
        ),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // Theme preview card
    Box(
      modifier =
        Modifier
          .size(width = 90.dp, height = 140.dp)
          .shadow(
            elevation = elevation,
            shape = RoundedCornerShape(12.dp),
            ambientColor =
              if (isSelected) {
                selectionColor.copy(
                  alpha = 0.3f,
                )
              } else {
                Color.Black.copy(alpha = 0.2f)
              },
            spotColor = if (isSelected) selectionColor.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.2f),
          ).clip(RoundedCornerShape(12.dp))
          .background(colorScheme.surface)
          .border(
            width = borderWidth,
            color = borderColor,
            shape = RoundedCornerShape(12.dp),
          ),
    ) {
      // Inner content
      Column(
        modifier =
          Modifier
            .matchParentSize()
            .padding(if (isSelected) 3.dp else 1.dp)
            .clip(RoundedCornerShape(if (isSelected) 9.dp else 11.dp))
            .background(colorScheme.background)
            .padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        // Top bar simulation
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .height(16.dp)
              .clip(RoundedCornerShape(4.dp))
              .background(colorScheme.surfaceVariant),
        )

        // Middle section - card with toggle
        Surface(
          modifier =
            Modifier
              .fillMaxWidth()
              .height(32.dp),
          color = colorScheme.surfaceVariant,
          shape = RoundedCornerShape(6.dp),
        ) {
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            // Toggle representation
            Box(
              modifier =
                Modifier
                  .size(width = 24.dp, height = 12.dp)
                  .clip(RoundedCornerShape(6.dp))
                  .background(colorScheme.primary),
            )
            // Accent indicator
            Box(
              modifier =
                Modifier
                  .size(10.dp)
                  .clip(CircleShape)
                  .background(colorScheme.tertiary),
            )
          }
        }

        // Bottom section - filled button simulation
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .height(14.dp)
              .clip(RoundedCornerShape(4.dp))
              .background(colorScheme.primaryContainer),
        )

        // Bottom accent dot
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.Center,
        ) {
          Box(
            modifier =
              Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(colorScheme.secondary),
          )
        }
      }
      actionOverlay?.let { overlay ->
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
          overlay()
        }
      }
      if (isSelected) {
        Box(
          modifier =
            Modifier
              .align(Alignment.TopStart)
              .padding(5.dp)
              .size(24.dp)
              .shadow(3.dp, CircleShape)
              .clip(CircleShape)
              .background(Color.White)
              .border(1.dp, Color.Black.copy(alpha = 0.45f), CircleShape),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.Check,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = Color.Black,
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(6.dp))

    // Theme name
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      fontSize = 11.sp,
      fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
      color =
        if (isSelected) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.onSurface
        },
      textAlign = TextAlign.Center,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}
