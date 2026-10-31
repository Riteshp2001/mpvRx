/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.PlayerButton
import app.gyrolet.mpvrx.preferences.getPlayerButtonLabel
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.liquidglass.AdaptiveControlsContainer
import app.gyrolet.mpvrx.ui.player.controls.components.fadingHorizontalScroll
import kotlin.math.abs

/** Slot pitch shared by collapsed icons and their expanded labels, so icons never shift. */
internal val PlayerOverflowItemWidth = 72.dp

/** Aligns the first overflow icon with Back above it. */
internal val PlayerOverflowStartInset = (PlayerOverflowItemWidth - PlayerControlButtonSize) / 2

private val ToggleSize = 36.dp
private val ToggleGap = 4.dp
private val ShadowRoom = 4.dp
private val ExpandDragDistance = 24.dp
private val LabelShadow = Shadow(Color.Black.copy(alpha = 0.75f), Offset(0f, 1f), 4f)

/**
 * MX Player-style row below the title: up to four overflowed top-bar icons and an arrow. Tapping
 * the arrow, or dragging anywhere on the row toward its end, expands it into every overflowed action
 * with a label, scrolling when they do not fit.
 */
@Composable
internal fun PlayerControlOverflowPanel(
  buttons: List<PlayerButton>,
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  onInteractionChanged: (Boolean) -> Unit,
  reducedMotion: Boolean,
  modifier: Modifier = Modifier,
  renderButton: @Composable (PlayerButton) -> Unit,
) {
  val changeExpanded by rememberUpdatedState(onExpandedChange)
  val changeInteraction by rememberUpdatedState(onInteractionChanged)
  val scrollState = rememberScrollState()
  var pressed by remember { mutableStateOf(false) }
  val title = stringResource(R.string.player_overflow_title)
  val expandLabel = stringResource(R.string.generic_expand)
  val collapseLabel = stringResource(R.string.generic_collapse)
  val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val isExpanded by rememberUpdatedState(expanded)
  val endDirection by rememberUpdatedState(if (isRtl) -1f else 1f)
  val arrowRotation by animateFloatAsState(
    targetValue = if (expanded) 180f else 0f,
    animationSpec = if (reducedMotion) snap() else tween(220),
    label = "PlayerOverflowArrow",
  )

  LaunchedEffect(pressed, scrollState.isScrollInProgress) {
    changeInteraction(pressed || scrollState.isScrollInProgress)
  }
  DisposableEffect(Unit) {
    onDispose { changeInteraction(false) }
  }
  // Collapsed icons are the leading actions; never leave them scrolled out of view.
  LaunchedEffect(expanded) {
    if (!expanded) scrollState.scrollTo(0)
  }

  PlayerButtonTheme {
    BoxWithConstraints(modifier) {
      val quickCount =
        ((maxWidth - ToggleSize - ToggleGap) / PlayerOverflowItemWidth).toInt()
          .coerceIn(1, MaxPlayerButtonsPerRegion)
          .coerceAtMost(buttons.size)
      Row(
        modifier = Modifier
          .pointerInput(Unit) {
            try {
              awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                do {
                  val event = awaitPointerEvent(PointerEventPass.Final)
                } while (event.changes.any { it.pressed })
                pressed = false
              }
            } finally {
              pressed = false
            }
          }
          .pointerInput(Unit) {
            // Initial pass, so a claimed drag cancels the button under the finger instead of clicking it.
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
              if (isExpanded) return@awaitEachGesture
              val slop = viewConfiguration.touchSlop
              val expandDistance = ExpandDragDistance.toPx()
              var dx = 0f
              var dy = 0f
              var claimed = false
              while (true) {
                val change =
                  awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) break
                val delta = change.positionChange()
                dx += delta.x
                dy += delta.y
                val towardEnd = dx * endDirection
                if (!claimed) {
                  if (abs(dy) > slop && abs(dy) >= abs(dx)) break
                  if (towardEnd < -slop) break
                  claimed = towardEnd > slop && abs(dx) > abs(dy)
                }
                if (claimed) {
                  change.consume()
                  if (!isExpanded && towardEnd >= expandDistance) changeExpanded(true)
                }
              }
            }
          },
        verticalAlignment = Alignment.Top,
      ) {
        Row(
          modifier = Modifier.weight(1f, fill = false).fadingHorizontalScroll(scrollState, shadowRoom = ShadowRoom),
          verticalAlignment = Alignment.Top,
        ) {
          buttons.forEachIndexed { index, button ->
            key(button) {
              if (index < quickCount) {
                OverflowItem(button, showLabel = expanded, reducedMotion = reducedMotion, renderButton = renderButton)
              } else {
                AnimatedVisibility(
                  visible = expanded,
                  enter =
                    if (reducedMotion) EnterTransition.None
                    else fadeIn(tween(180)) + expandHorizontally(tween(220), expandFrom = Alignment.Start),
                  exit =
                    if (reducedMotion) ExitTransition.None
                    else fadeOut(tween(120)) + shrinkHorizontally(tween(200), shrinkTowards = Alignment.Start),
                ) {
                  OverflowItem(button, showLabel = true, reducedMotion = reducedMotion, renderButton = renderButton)
                }
              }
            }
          }
        }
        Box(
          modifier = Modifier.padding(start = ToggleGap, top = ShadowRoom).height(PlayerControlButtonSize),
          contentAlignment = Alignment.Center,
        ) {
          AdaptiveControlsContainer(
            onClick = { changeExpanded(!expanded) },
            modifier = Modifier.size(ToggleSize).semantics {
              if (expanded) {
                collapse(collapseLabel) {
                  changeExpanded(false)
                  true
                }
              } else {
                expand(expandLabel) {
                  changeExpanded(true)
                  true
                }
              }
            },
            buttonSize = ToggleSize,
            horizontalPadding = 0.dp,
          ) {
            Icon(
              imageVector = if (isRtl) Icons.RoundedFilled.ChevronLeft else Icons.RoundedFilled.ChevronRight,
              contentDescription = title,
              modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = arrowRotation },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun OverflowItem(
  button: PlayerButton,
  showLabel: Boolean,
  reducedMotion: Boolean,
  renderButton: @Composable (PlayerButton) -> Unit,
) {
  val label = getPlayerButtonLabel(button)
  Column(
    modifier = Modifier.width(PlayerOverflowItemWidth),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier = Modifier.size(PlayerControlButtonSize).semantics(mergeDescendants = true) { contentDescription = label },
      contentAlignment = Alignment.Center,
    ) {
      renderButton(button)
    }
    AnimatedVisibility(
      visible = showLabel,
      enter =
        if (reducedMotion) EnterTransition.None
        else fadeIn(tween(180)) + expandVertically(tween(220), expandFrom = Alignment.Top),
      exit =
        if (reducedMotion) ExitTransition.None
        else fadeOut(tween(120)) + shrinkVertically(tween(200), shrinkTowards = Alignment.Top),
    ) {
      // Labels sit directly over video, as in MX Player, so they carry their own shadow.
      Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall.copy(shadow = LabelShadow, hyphens = Hyphens.Auto),
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp).clearAndSetSemantics {},
      )
    }
  }
}
