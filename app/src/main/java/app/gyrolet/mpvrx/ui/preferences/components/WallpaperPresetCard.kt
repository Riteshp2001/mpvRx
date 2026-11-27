/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.appPressFeedback

/**
 * Card in the same style as [ThemePreviewCard] (rounded 90x140 tile, selection ring + check badge,
 * label underneath) whose tile content is supplied by the caller.
 */
@Composable
fun WallpaperPresetCard(
  label: String,
  isSelected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  val selectionColor = MaterialTheme.colorScheme.primary
  val interactionSource = remember { MutableInteractionSource() }
  val borderColor by animateColorAsState(
    targetValue = if (isSelected) selectionColor else MaterialTheme.colorScheme.outlineVariant,
    animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
    label = "wallpaperSelectionOutline",
  )
  val shape = RoundedCornerShape(12.dp)
  val glow = if (isSelected) selectionColor.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.2f)

  Column(
    modifier =
      modifier
        .width(100.dp)
        .appPressFeedback(interactionSource, pressedScale = 0.96f)
        .semantics { selected = isSelected }
        .tvFocusHighlight(shape, focusedScale = 1.05f)
        .clickable(interactionSource, LocalIndication.current, onClick = onClick),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier =
        Modifier
          .size(width = 90.dp, height = 140.dp)
          .shadow(if (isSelected) 8.dp else 2.dp, shape, ambientColor = glow, spotColor = glow)
          .clip(shape)
          .background(MaterialTheme.colorScheme.surfaceContainerLow)
          .border(
            width = if (isSelected) 3.dp else 1.dp,
            color = borderColor,
            shape = shape,
          ),
    ) {
      Box(
        modifier =
          Modifier
            .matchParentSize()
            .padding(if (isSelected) 3.dp else 1.dp)
            .clip(RoundedCornerShape(if (isSelected) 9.dp else 11.dp)),
        content = content,
      )
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

    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      fontSize = 11.sp,
      fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
      color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}
