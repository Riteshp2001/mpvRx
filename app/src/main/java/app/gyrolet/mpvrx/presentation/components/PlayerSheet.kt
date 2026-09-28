/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

@file:Suppress("DEPRECATION")

package app.gyrolet.mpvrx.presentation.components

import android.annotation.SuppressLint
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusGroup
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import androidx.compose.ui.focus.focusProperties
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.LocalMotionPolicy
import app.gyrolet.mpvrx.ui.theme.MotionPolicy
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun PlayerSheet(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  tonalElevation: Dp = 1.dp,
  customMaxWidth: Dp? = null,
  customMaxHeight: Dp? = null,
  surfaceColor: Color? = null,
  isSwipeActive: Boolean = false,
  swipeOffset: Float = 0f,
  title: String? = null,
  actions: @Composable RowScope.() -> Unit = {},
  content: @Composable () -> Unit,
) {
  val scope = rememberCoroutineScope()
  // TV/remote: request focus INTO the sheet content when it opens, so the D-pad can navigate/select
  // its rows instead of being trapped behind the (hidden) first-layer player controls. Ported from
  // mpvEx-TV; rememberTvInitialFocusRequester retries across the entrance animation and is TV-gated.
  val sheetInitialFocus = rememberTvInitialFocusRequester()
  val reducedMotion = AppMotion.playerReducedMotion()
  // Reduced motion shortens the sheet's travel. It does not remove the scrim,
  // which is what makes the sheet modal rather than a panel floating over a
  // still-live screen.
  val currentSheetSpec by rememberUpdatedState<FiniteAnimationSpec<Float>>(
    if (reducedMotion) AppMotion.ReducedAlpha else AppMotion.Spatial.Expressive,
  )
  val sheetAnimationSpec = remember {
    object : FiniteAnimationSpec<Float> {
      override fun <Vector : AnimationVector> vectorize(
        converter: TwoWayConverter<Float, Vector>,
      ): VectorizedFiniteAnimationSpec<Vector> = currentSheetSpec.vectorize(converter)
    }
  }
  val density = LocalDensity.current
  val latestOnDismissRequest by rememberUpdatedState(onDismissRequest)
  val maxWidth = customMaxWidth ?: 640.dp
  val isImeVisible = WindowInsets.ime.getBottom(density) > 0
  val maxHeight =
    customMaxHeight ?: when {
      isImeVisible -> LocalConfiguration.current.screenHeightDp.dp
      LocalConfiguration.current.orientation == ORIENTATION_PORTRAIT ->
        LocalConfiguration.current.screenHeightDp.dp * .85f
      else -> LocalConfiguration.current.screenHeightDp.dp
    }

  val screenHeightPx =
    with(density) {
      LocalConfiguration.current.screenHeightDp.dp
        .toPx()
    }
  val decayAnimationSpec = rememberSplineBasedDecay<Float>()
  val anchoredDraggableState =
    remember {
      AnchoredDraggableState(
        initialValue = 1,
        snapAnimationSpec = sheetAnimationSpec,
        decayAnimationSpec = decayAnimationSpec,
        positionalThreshold = { with(density) { 56.dp.toPx() } },
        velocityThreshold = { with(density) { 125.dp.toPx() } },
      )
    }

  val scaledSwipeOffset = swipeOffset * 2f
  val currentIsSwipeActive by rememberUpdatedState(isSwipeActive)
  val currentScaledSwipeOffset by rememberUpdatedState(scaledSwipeOffset)
  val height =
    if (anchoredDraggableState.anchors.size >
      0
    ) {
      anchoredDraggableState.anchors.positionOf(1)
    } else {
      screenHeightPx
    }
  val swipeProgress = if (height > 0) (-scaledSwipeOffset / height).coerceIn(0f, 1f) else 0f
  val targetAlpha =
    if (isSwipeActive) {
      if (anchoredDraggableState.anchors.size > 0) 0.5f * swipeProgress else 0f
    } else if (anchoredDraggableState.targetValue == 0) {
      0.5f
    } else {
      0f
    }
  val alpha by animateFloatAsState(
    targetAlpha,
    animationSpec = if (reducedMotion) AppMotion.ReducedAlpha else AppMotion.Effect.Alpha,
    label = "alpha",
  )

  val internalOnDismissRequest = {
    if (anchoredDraggableState.currentValue == 0) {
      scope.launch {
        anchoredDraggableState.animateTo(1)
      }
    }
  }
  Box(
    modifier =
      Modifier
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = internalOnDismissRequest,
          ).fillMaxSize()
          .focusProperties { canFocus = false }
          .background(Color.Black.copy(alpha))
        .onSizeChanged {
          val anchors =
            DraggableAnchors {
              0 at 0f
              1 at it.height.toFloat()
            }
          anchoredDraggableState.updateAnchors(anchors)
        },
    contentAlignment = Alignment.BottomCenter,
  ) {
    Surface(
      modifier =
        Modifier
          .sizeIn(maxWidth = maxWidth, maxHeight = maxHeight)
          .fillMaxWidth()
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
          ).nestedScroll(
            remember(anchoredDraggableState) {
              anchoredDraggableState.preUpPostDownNestedScrollConnection()
            },
          ).then(modifier)
          .tvFocusGroup()
          .tvInitialFocus(sheetInitialFocus)
          .focusProperties { canFocus = false }
          .offset {
            val baseOffset =
              anchoredDraggableState.offset
                .takeIf { it.isFinite() }
                ?: screenHeightPx
            IntOffset(0, baseOffset.roundToInt())
          }.graphicsLayer {
            this.alpha = if (anchoredDraggableState.anchors.size > 0) 1f else 0f
          }.anchoredDraggable(
            state = anchoredDraggableState,
            orientation = Orientation.Vertical,
          ).windowInsetsPadding(
            WindowInsets.systemBars
              .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
          ).imePadding(),
      shape = MaterialTheme.shapes.extraLarge.copy(bottomEnd = ZeroCornerSize, bottomStart = ZeroCornerSize),
      color = surfaceColor ?: MaterialTheme.colorScheme.surface,
      tonalElevation = tonalElevation,
      content = {
        BackHandler(
          enabled = anchoredDraggableState.targetValue == 0,
          onBack = internalOnDismissRequest,
        )
        CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = reducedMotion)) {
          Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            PlayerSheetDragHandle()
            if (title != null) {
              PlayerSheetHeader(title = title, actions = actions)
            }
            Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
              content()
            }
          }
        }
      },
    )

    LaunchedEffect(anchoredDraggableState, screenHeightPx) {
      snapshotFlow {
        Triple(
          currentIsSwipeActive,
          currentScaledSwipeOffset,
          anchoredDraggableState.anchors.size,
        )
      }.collectLatest { (swipeActive, latestSwipeOffset, anchorCount) ->
        if (swipeActive && anchorCount > 0) {
          val closedOffset = anchoredDraggableState.anchors.positionOf(1)
          val currentOffset = anchoredDraggableState.offset
          if (closedOffset.isFinite() && currentOffset.isFinite()) {
            val targetOffset = (closedOffset + latestSwipeOffset).coerceIn(0f, screenHeightPx)
            anchoredDraggableState.dispatchRawDelta(targetOffset - currentOffset)
          }
        }
      }
    }

    var wasSwipeActive by remember { mutableStateOf(false) }
    LaunchedEffect(anchoredDraggableState, isSwipeActive) {
      if (isSwipeActive) {
        wasSwipeActive = true
      } else {
        if (wasSwipeActive) {
          val currentOffset = anchoredDraggableState.offset
          // Settle to open (0) if the user dragged up by more than 10% of the sheet height
          val target = if (currentOffset >= height * 0.9f) 1 else 0
          anchoredDraggableState.animateTo(target)
          if (target == 1) {
            latestOnDismissRequest()
          }
          wasSwipeActive = false
        } else {
          // Title tap opening: wait for anchors to be measured before animating open (0)
          snapshotFlow { anchoredDraggableState.anchors.size }
            .filter { it > 0 }
            .collectLatest {
              anchoredDraggableState.animateTo(0)
            }
        }
      }
    }

    var wasOpened by remember { mutableStateOf(false) }
    LaunchedEffect(anchoredDraggableState) {
      snapshotFlow { anchoredDraggableState.currentValue }
        .collectLatest { value ->
          if (value == 0) {
            wasOpened = true
          } else if (value == 1 && wasOpened) {
            latestOnDismissRequest()
          }
        }
    }
  }
}

@Composable
fun PlayerSheetDragHandle() {
  Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
    Box(
      Modifier.size(width = 34.dp, height = 4.dp)
        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(2.dp)),
    )
  }
}

@Composable
fun PlayerSheetHeader(
  title: String,
  modifier: Modifier = Modifier,
  actions: @Composable RowScope.() -> Unit = {},
) {
  Box(
    modifier = modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp),
  ) {
    Row(
      modifier = Modifier.align(Alignment.CenterEnd),
      verticalAlignment = Alignment.CenterVertically,
      content = actions,
    )
  }
}

@Composable
fun PlayerSheetSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
) {
  Text(
    text = title,
    modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).semantics { heading() },
    style = MaterialTheme.typography.labelLarge,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSheetAction(
  icon: AppIcon,
  label: String,
  onClick: () -> Unit,
  enabled: Boolean = true,
) {
  TooltipBox(
    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
    tooltip = { PlainTooltip { Text(label) } },
    state = rememberTooltipState(),
  ) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
      Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
    }
  }
}

private fun <T> AnchoredDraggableState<T>.preUpPostDownNestedScrollConnection() =
  object : NestedScrollConnection {
    override fun onPreScroll(
      available: Offset,
      source: NestedScrollSource,
    ): Offset {
      val delta = available.toFloat()
      return if (delta < 0 && source == NestedScrollSource.UserInput) {
        dispatchRawDelta(delta).toOffset()
      } else {
        Offset.Zero
      }
    }

    override fun onPostScroll(
      consumed: Offset,
      available: Offset,
      source: NestedScrollSource,
    ): Offset =
      if (source == NestedScrollSource.UserInput) {
        dispatchRawDelta(available.toFloat()).toOffset()
      } else {
        Offset.Zero
      }

    override suspend fun onPreFling(available: Velocity): Velocity {
      val toFling = available.toFloat()
      return if (toFling < 0 && offset > anchors.minPosition()) {
        settle(toFling)
        available
      } else {
        Velocity.Zero
      }
    }

    override suspend fun onPostFling(
      consumed: Velocity,
      available: Velocity,
    ): Velocity {
      val toFling = available.toFloat()
      return if (toFling > 0) {
        settle(toFling)
        available
      } else {
        Velocity.Zero
      }
    }

    private fun Float.toOffset(): Offset = Offset(0f, this)

    @JvmName("velocityToFloat")
    private fun Velocity.toFloat() = y

    private fun Offset.toFloat(): Float = y
  }
