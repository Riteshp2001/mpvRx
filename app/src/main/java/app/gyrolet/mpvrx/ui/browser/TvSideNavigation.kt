/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Side-panel navigation for Android TV. Layout, sizing, colours and focus behaviour are adapted
 * from NuvioTV's sidebar (https://github.com/NuvioMedia/NuvioTV, GPL-3.0).
 */

package app.gyrolet.mpvrx.ui.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.tv.TvSafeArea
import app.gyrolet.mpvrx.ui.theme.AppMotion

internal object TvSideNavigationDefaults {
  val CollapsedWidth: Dp = 88.dp
  val ExpandedWidth: Dp = 264.dp
  val ItemHeight: Dp = 52.dp
  val IconSize: Dp = 22.dp
  val LeadingVisualSize: Dp = 34.dp
  val PanelCornerRadius: Dp = 30.dp

  const val PanelInMillis = 345
  const val PanelOutMillis = 385
  const val ColorMillis = 180
  const val FocusedScale = 1.06f
  val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

/**
 * A collapsible left rail. It stays icon-only while the content owns focus and expands over the
 * content (without relaying it out) once D-pad focus enters the rail.
 *
 * Toward the content (Right, or Left in RTL) always calls [onFocusContent], which returns to the
 * item that was focused there before. Geometric search from the expanded rail used to skip
 * anything partly under it and land on a toolbar action instead. The opposite key is a no-op at
 * the screen edge.
 */
@Composable
internal fun TvSideNavigation(
  tabs: List<MainScreen.MainTab>,
  selectedTab: MainScreen.MainTab,
  onTabSelected: (MainScreen.MainTab) -> Unit,
  onOpenSettings: () -> Unit,
  selectedItemFocusRequester: FocusRequester,
  onRailFocusChanged: (Boolean) -> Unit,
  onFocusContent: () -> Boolean,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  val reduceMotion = AppMotion.shouldReduceMotion()
  val currentOnFocusContent by rememberUpdatedState(onFocusContent)
  val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val progress by animateFloatAsState(
    targetValue = if (expanded) 1f else 0f,
    animationSpec =
      if (reduceMotion) {
        snap()
      } else if (expanded) {
        tween(TvSideNavigationDefaults.PanelInMillis, easing = FastOutSlowInEasing)
      } else {
        tween(TvSideNavigationDefaults.PanelOutMillis, easing = LinearOutSlowInEasing)
      },
    label = "tv_rail_progress",
  )
  val width by animateDpAsState(
    targetValue = if (expanded) TvSideNavigationDefaults.ExpandedWidth else TvSideNavigationDefaults.CollapsedWidth,
    animationSpec =
      if (reduceMotion) snap() else tween(
        durationMillis = if (expanded) TvSideNavigationDefaults.PanelInMillis else TvSideNavigationDefaults.PanelOutMillis,
        easing = TvSideNavigationDefaults.EmphasizedEasing,
      ),
    label = "tv_rail_width",
  )

  val surface = MaterialTheme.colorScheme.surfaceContainer
  val panelShape =
    RoundedCornerShape(
      topEnd = TvSideNavigationDefaults.PanelCornerRadius * progress,
      bottomEnd = TvSideNavigationDefaults.PanelCornerRadius * progress,
    )
  val lastIndex = tabs.lastIndex

  Box(
    modifier =
      modifier
        .fillMaxHeight()
        .width(width)
        .clip(panelShape)
        .background(
          Brush.horizontalGradient(
            listOf(
              surface.copy(alpha = 0.55f + 0.42f * progress),
              surface.copy(alpha = 0.35f + 0.6f * progress),
            ),
          ),
        ),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxHeight()
          .fillMaxWidth()
          .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
          .padding(horizontal = 12.dp, vertical = TvSafeArea.Vertical)
          .onFocusChanged { state ->
            if (expanded != state.hasFocus) {
              expanded = state.hasFocus
              onRailFocusChanged(state.hasFocus)
            }
          }.onPreviewKeyEvent { event ->
            val towardContent = if (isRtl) Key.DirectionLeft else Key.DirectionRight
            val towardEdge = if (isRtl) Key.DirectionRight else Key.DirectionLeft
            when (event.key) {
              towardContent -> {
                if (event.type == KeyEventType.KeyDown) currentOnFocusContent()
                true
              }
              towardEdge -> true
              else -> false
            }
          }.focusRestorer(selectedItemFocusRequester)
          .focusGroup(),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      TvRailHeader(expanded = expanded)
      Spacer(Modifier.weight(1f))
      tabs.forEachIndexed { index, tab ->
        val selected = tab == selectedTab
        TvSideNavigationItem(
          label = mainNavigationLabel(tab),
          selected = selected,
          expanded = expanded,
          onClick = { onTabSelected(tab) },
          modifier =
            Modifier
              .then(if (selected) Modifier.focusRequester(selectedItemFocusRequester) else Modifier)
              .focusProperties {
                if (index == 0) up = FocusRequester.Cancel
              },
        ) { tint ->
          MainTabIcon(
            tab = tab,
            tint = tint,
            contentDescription = null,
            iconSize = TvSideNavigationDefaults.IconSize,
          )
        }
      }
      Spacer(Modifier.weight(1f))
      TvSideNavigationItem(
        label = stringResource(R.string.tv_nav_settings),
        selected = false,
        expanded = expanded,
        onClick = onOpenSettings,
        modifier =
          Modifier.focusProperties {
            down = FocusRequester.Cancel
            if (lastIndex < 0) up = FocusRequester.Cancel
          },
      ) { tint ->
        Icon(
          Icons.RoundedFilled.Settings,
          contentDescription = null,
          tint = tint,
          modifier = Modifier.size(TvSideNavigationDefaults.IconSize),
        )
      }
    }
  }
}

@Composable
private fun TvRailHeader(expanded: Boolean) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .height(TvSideNavigationDefaults.ItemHeight)
        .padding(horizontal = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier
          .size(TvSideNavigationDefaults.LeadingVisualSize)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.primary),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = stringResource(R.string.app_name).take(1),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimary,
      )
    }
    AnimatedVisibility(
      visible = expanded,
      enter = fadeIn(tween(280)) + expandHorizontally(tween(TvSideNavigationDefaults.PanelInMillis)),
      exit = fadeOut(tween(200)) + shrinkHorizontally(tween(TvSideNavigationDefaults.PanelOutMillis)),
    ) {
      Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 14.dp),
      )
    }
  }
}

@Composable
private fun TvSideNavigationItem(
  label: String,
  selected: Boolean,
  expanded: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: @Composable (tint: Color) -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val focused by interactionSource.collectIsFocusedAsState()
  val reduceMotion = AppMotion.shouldReduceMotion()
  val selectedContainer = MaterialTheme.colorScheme.secondaryContainer
  val selectedContent = MaterialTheme.colorScheme.onSecondaryContainer
  val onSurface = MaterialTheme.colorScheme.onSurface
  val colorSpec = if (reduceMotion) snap<Color>() else AppMotion.Effect.Color

  val background by animateColorAsState(
    targetValue =
      when {
        focused && selected -> selectedContainer
        focused -> onSurface.copy(alpha = 0.12f)
        selected -> selectedContainer
        else -> Color.Transparent
      },
    animationSpec = colorSpec,
    label = "tv_rail_item_bg",
  )
  val contentColor by animateColorAsState(
    targetValue =
      when {
        selected -> selectedContent
        focused -> onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
      },
    animationSpec = colorSpec,
    label = "tv_rail_item_content",
  )
  val scale by animateFloatAsState(
    targetValue = if (focused && !reduceMotion) TvSideNavigationDefaults.FocusedScale else 1f,
    animationSpec = if (reduceMotion) snap() else AppMotion.Spatial.ExpressiveFast,
    label = "tv_rail_item_scale",
  )

  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(min = TvSideNavigationDefaults.ItemHeight)
        .semantics {
          contentDescription = label
          this.selected = selected
        }
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        }.clip(CircleShape)
        .background(background)
        .border(
          width = 2.dp,
          color = if (focused) contentColor else Color.Transparent,
          shape = CircleShape,
        )
        .clickable(
          interactionSource = interactionSource,
          indication = null,
          role = Role.Tab,
          onClick = onClick,
        ).padding(horizontal = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier.size(TvSideNavigationDefaults.LeadingVisualSize),
      contentAlignment = Alignment.Center,
    ) {
      icon(contentColor)
    }
    AnimatedVisibility(
      visible = expanded,
      enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(280)) + expandHorizontally(tween(TvSideNavigationDefaults.PanelInMillis)),
      exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(200)) + shrinkHorizontally(tween(TvSideNavigationDefaults.PanelOutMillis)),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
        color = contentColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 14.dp),
      )
    }
  }
}
