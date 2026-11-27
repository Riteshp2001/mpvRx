/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.presentation.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.liquidglass.LocalPlayerGlassSurfaceActive
import app.gyrolet.mpvrx.ui.liquidglass.playerGlassContainerColor
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.spacing

@Composable
fun ExpandableCard(
  isExpanded: Boolean,
  title: @Composable (Boolean) -> Unit,
  onExpand: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  colors: CardColors = CardDefaults.cardColors(),
  shape: Shape = MaterialTheme.shapes.large,
  border: BorderStroke? =
    BorderStroke(
      1.dp,
      MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    ),
  elevation: CardElevation =
    CardDefaults.cardElevation(
      defaultElevation = 0.dp,
      pressedElevation = 0.dp,
      focusedElevation = 0.dp,
      hoveredElevation = 0.dp,
      draggedElevation = 0.dp,
      disabledElevation = 0.dp,
    ),
  content: @Composable () -> Unit,
) {
  val reduceMotion = AppMotion.shouldReduceMotion()
  val frosted = LocalPlayerGlassSurfaceActive.current
  val resolvedColors =
    if (frosted) {
      CardDefaults.cardColors(
        containerColor = playerGlassContainerColor(colors.containerColor),
        contentColor = colors.contentColor,
        disabledContainerColor = playerGlassContainerColor(colors.disabledContainerColor),
        disabledContentColor = colors.disabledContentColor,
      )
    } else {
      colors
    }
  val interactionSource = remember { MutableInteractionSource() }
  val actionLabel = stringResource(if (isExpanded) R.string.generic_collapse else R.string.generic_expand)
  val rotationState by animateFloatAsState(
    targetValue = if (isExpanded) 0f else 180f,
    animationSpec = if (reduceMotion) snap() else AppMotion.Spatial.ExpressiveFast,
    label = "card_rotation",
  )
  Card(
    modifier = modifier.animateContentSize(if (reduceMotion) snap() else AppMotion.IntSizeSpring),
    colors = resolvedColors,
    shape = shape,
    border = if (frosted) null else border,
    elevation = elevation,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .heightIn(min = 48.dp)
          .clickable(
            interactionSource = interactionSource,
            indication = androidx.compose.material3.ripple(),
            role = Role.Button,
            onClickLabel = actionLabel,
            onClick = { onExpand(!isExpanded) },
          ).padding(
            horizontal = MaterialTheme.spacing.medium,
            vertical = MaterialTheme.spacing.smaller,
          ),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.weight(1f)) {
        ProvideTextStyle(MaterialTheme.typography.titleMedium) {
          title(isExpanded)
        }
      }
      Icon(
        Icons.RoundedFilled.ArrowDropDown,
        contentDescription = null,
        modifier = Modifier.padding(start = MaterialTheme.spacing.small).size(24.dp).rotate(rotationState),
      )
    }
    if (isExpanded) {
      Box(
        Modifier
          .padding(
            start = MaterialTheme.spacing.medium,
            end = MaterialTheme.spacing.medium,
            bottom = MaterialTheme.spacing.medium,
          ),
      ) {
        content()
      }
    }
  }
}
