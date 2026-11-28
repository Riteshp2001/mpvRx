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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.liquidglass.LocalKyantPlayerBackdrop
import app.gyrolet.mpvrx.ui.liquidglass.LocalPlayerGlassSurfaceActive
import app.gyrolet.mpvrx.ui.liquidglass.playerGlassSurface
import app.gyrolet.mpvrx.ui.liquidglass.playerGlassSurfaceEnabled
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusGroup
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.LocalMotionPolicy
import app.gyrolet.mpvrx.ui.theme.MotionPolicy
import app.gyrolet.mpvrx.ui.tv.TvSafeArea
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PlayerSheet(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  tonalElevation: Dp = 0.dp,
  customMaxWidth: Dp? = null,
  surfaceColor: Color? = null,
  isSwipeActive: Boolean = false,
  swipeOffset: Float = 0f,
  title: String? = null,
  actions: @Composable RowScope.() -> Unit = {},
  useFrostedGlass: Boolean = true,
  scrimAlpha: Float = 0.5f,
  dragToDismiss: Boolean = true,
  confirmDismiss: () -> Boolean = { true },
  leadingIcon: AppIcon? = null,
  subtitle: String? = null,
  showDragHandle: Boolean = true,
  scrollHeaderActions: Boolean = false,
  content: @Composable () -> Unit,
) {
  val window = sheetWindow()
  val television = window.television
  val liquidGlassEnabled = playerGlassSurfaceEnabled()
  val playerBackdrop = LocalKyantPlayerBackdrop.current
  val isFrosted = useFrostedGlass && liquidGlassEnabled
  // TV/remote: request focus INTO the sheet content when it opens, so the D-pad can navigate/select
  // its rows instead of being trapped behind the (hidden) first-layer player controls. Ported from
  // mpvEx-TV; rememberTvInitialFocusRequester retries across the entrance animation and is TV-gated.
  val sheetInitialFocus = rememberTvInitialFocusRequester()
  val reducedMotion = AppMotion.playerReducedMotion()
  // Reduced motion shortens the sheet's travel. It does not remove the scrim,
  // which is what makes the sheet modal rather than a panel floating over a
  // still-live screen.
  val currentSheetSpec by rememberUpdatedState<FiniteAnimationSpec<Float>>(
    if (reducedMotion) AppMotion.ReducedAlpha else AppMotion.noBounce(380f),
  )
  val sheetAnimationSpec =
    remember {
      object : FiniteAnimationSpec<Float> {
        override fun <Vector : AnimationVector> vectorize(
          converter: TwoWayConverter<Float, Vector>,
        ): VectorizedFiniteAnimationSpec<Vector> = currentSheetSpec.vectorize(converter)
      }
    }
  val density = LocalDensity.current
  val latestOnDismissRequest by rememberUpdatedState(onDismissRequest)
  val latestConfirmDismiss by rememberUpdatedState(confirmDismiss)
  var opened by remember { mutableStateOf(false) }
  var closing by remember { mutableStateOf(false) }
  var dismissalDelivered by remember { mutableStateOf(false) }
  var sheetHeightPx by remember { mutableStateOf(0) }
  val deliverDismissal: () -> Unit = {
    if (!dismissalDelivered) {
      dismissalDelivered = true
      latestOnDismissRequest()
    }
  }
  val isImeVisible = WindowInsets.ime.getBottom(density) > 0
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
  // The bottom-aligned sheet has left the screen once it travelled its own height; the rest of
  // the spring toward the closed anchor is invisible.
  val sheetOffscreen: () -> Boolean = {
    val offset = anchoredDraggableState.offset
    sheetHeightPx > 0 && offset.isFinite() && offset >= sheetHeightPx - 1f
  }

  val scaledSwipeOffset = swipeOffset * 2f
  val currentIsSwipeActive by rememberUpdatedState(isSwipeActive)
  val currentScaledSwipeOffset by rememberUpdatedState(scaledSwipeOffset)
  val height = anchoredDraggableState.anchors.positionOf(1)
  val swipeProgress = if (height > 0) (-scaledSwipeOffset / height).coerceIn(0f, 1f) else 0f
  val targetAlpha =
    if (closing) {
      0f
    } else if (isSwipeActive) {
      scrimAlpha * swipeProgress
    } else if (anchoredDraggableState.targetValue == 0) {
      scrimAlpha
    } else {
      0f
    }
  val alpha by animateFloatAsState(
    targetAlpha,
    animationSpec = if (reducedMotion) AppMotion.ReducedAlpha else AppMotion.Effect.Alpha,
    label = "alpha",
  )

  // Ask before moving the sheet so an unsaved-changes prompt does not hide the form.
  val internalOnDismissRequest: () -> Unit = {
    if (!closing && !dismissalDelivered && latestConfirmDismiss()) closing = true
  }
  val closeLabel = stringResource(R.string.ui_close)
  // A leaving sheet must not keep an invisible full-screen scrim over the player's gestures.
  val scrimBlocksTouches = !closing && !dismissalDelivered && !(opened && anchoredDraggableState.targetValue == 1)
  val scrimInteractionSource = remember { MutableInteractionSource() }
  Box(Modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
    Box(
      Modifier
        .matchParentSize()
        .background(Color.Black.copy(alpha))
        .focusProperties { canFocus = false }
        .then(
          if (scrimBlocksTouches) {
            Modifier.clickable(
              interactionSource = scrimInteractionSource,
              indication = null,
              onClickLabel = closeLabel,
              onClick = internalOnDismissRequest,
            )
          } else {
            Modifier
          },
        ),
    )
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          // Lifts the whole sheet above the keyboard instead of padding inside a height-capped surface.
          .imePadding()
          .windowInsetsPadding(
            CenteredSheetInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
          ).then(if (television) Modifier.padding(TvSafeArea.Padding) else Modifier)
          .onSizeChanged {
            if (it.height <= 0) return@onSizeChanged
            val anchors =
              DraggableAnchors {
                0 at 0f
                1 at it.height.toFloat()
              }
            anchoredDraggableState.updateAnchors(
              anchors,
              // IME/inset changes must not choose the nearest anchor mid-transition.
              newTarget =
                if (anchoredDraggableState.isAnimationRunning) {
                  anchoredDraggableState.targetValue
                } else {
                  anchoredDraggableState.settledValue
                },
            )
          },
      contentAlignment = Alignment.BottomCenter,
    ) {
      val sheetShape = bottomAttachedSheetShape(television)
      val baseContainerColor = surfaceColor ?: MaterialTheme.colorScheme.surfaceContainerHigh
      val canDrag = !television && dragToDismiss
      val dragEnabled = canDrag && opened && !closing
      Surface(
        modifier =
          Modifier
            .onSizeChanged { sheetHeightPx = it.height }
            .sizeIn(maxWidth = window.maxWidth(customMaxWidth))
            .fillMaxWidth()
            .then(modifier)
            .tvFocusGroup()
            .tvInitialFocus(sheetInitialFocus)
            .offset {
              val baseOffset = anchoredDraggableState.offset.takeIf { it.isFinite() } ?: 0f
              IntOffset(0, baseOffset.roundToInt())
            }.graphicsLayer { this.alpha = if (anchoredDraggableState.anchors.size > 0) 1f else 0f }
            // Stop taps inside the sheet from hitting the scrim without making the sheet a fake button.
            .pointerInput(Unit) {}
            .semantics {
              if (!title.isNullOrBlank()) paneTitle = title
              dismiss(closeLabel) {
                internalOnDismissRequest()
                true
              }
            }.then(
              if (dragEnabled) {
                Modifier.nestedScroll(
                  remember(anchoredDraggableState) { anchoredDraggableState.preUpPostDownNestedScrollConnection() },
                )
              } else {
                Modifier
              },
            ).anchoredDraggable(
              state = anchoredDraggableState,
              orientation = Orientation.Vertical,
              enabled = dragEnabled,
            ).playerGlassSurface(playerBackdrop, sheetShape, baseContainerColor, enabled = isFrosted),
        shape = sheetShape,
        color = if (isFrosted) Color.Transparent else baseContainerColor.copy(alpha = 1f),
        tonalElevation = if (isFrosted) 0.dp else tonalElevation,
        border =
          if (isFrosted) {
            null
          } else {
            BorderStroke(
              1.dp,
              MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
            )
          },
      ) {
        BackHandler(enabled = !closing && !dismissalDelivered, onBack = internalOnDismissRequest)
        CompositionLocalProvider(
          LocalPlayerGlassSurfaceActive provides isFrosted,
          LocalMotionPolicy provides MotionPolicy(reduceMotion = reducedMotion),
          androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) {
          ResponsiveSheetContent(
            window = window,
            modifier =
              Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(bottom = if (isImeVisible) 0.dp else 4.dp),
          ) { resize ->
            SheetOpeningHandle(
              resize = resize,
              showDragHandle = showDragHandle && canDrag,
              gesturesEnabled = canDrag,
              resizeEnabled = opened && !closing,
            )
            if (title != null) {
              PlayerSheetHeader(
                title = title,
                subtitle = subtitle,
                actions = actions,
                leadingContent = { SheetHeaderIcon(leadingIcon) },
                scrollActions = scrollHeaderActions,
              )
            }
            Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
              content()
            }
          }
        }
      }
    }

    val openedWithSwipe = remember { isSwipeActive }
    LaunchedEffect(anchoredDraggableState, closing) {
      if (closing) {
        snapshotFlow { anchoredDraggableState.offset.isFinite() }.first { it }
        launch {
          anchoredDraggableState.animateTo(1)
          deliverDismissal()
        }
        // Hand the player back as soon as the sheet is out of sight.
        snapshotFlow { sheetOffscreen() }.first { it }
        deliverDismissal()
        return@LaunchedEffect
      }
      if (opened) return@LaunchedEffect
      var swiped = openedWithSwipe
      val (_, finalSwipeOffset, closedOffset) =
        snapshotFlow {
          Triple(currentIsSwipeActive, currentScaledSwipeOffset, anchoredDraggableState.anchors.positionOf(1))
        }.first { (swipeActive, dragOffset, closed) ->
          swiped = swiped || swipeActive
          val offset = anchoredDraggableState.offset
          if (!closed.isFinite() || !offset.isFinite()) return@first false
          if (swiped) {
            anchoredDraggableState.dispatchRawDelta((closed + dragOffset).coerceIn(0f, closed) - offset)
          }
          !swipeActive
        }
      if (swiped && -finalSwipeOffset <= closedOffset * 0.1f && latestConfirmDismiss()) {
        closing = true
      } else {
        anchoredDraggableState.animateTo(0)
        opened = true
      }
    }

    LaunchedEffect(anchoredDraggableState) {
      while (true) {
        // A swipe that settles closed is finished once the sheet is out of sight.
        snapshotFlow {
          opened &&
            !closing &&
            !dismissalDelivered &&
            (
              anchoredDraggableState.settledValue == 1 ||
                (
                  anchoredDraggableState.isAnimationRunning &&
                    anchoredDraggableState.targetValue == 1 &&
                    sheetOffscreen()
                )
            )
        }.first { it }
        if (latestConfirmDismiss()) {
          deliverDismissal()
          return@LaunchedEffect
        }
        try {
          anchoredDraggableState.animateTo(0)
        } catch (e: CancellationException) {
          // A new drag or a close may take the sheet over; only leaving composition ends this loop.
          ensureActive()
        }
      }
    }
  }
}

@Composable
fun PlayerSheetDragHandle(
  modifier: Modifier = Modifier,
  visible: Boolean = true,
  compact: Boolean = false,
) {
  Box(
    modifier
      .fillMaxWidth()
      .padding(top = if (compact) 8.dp else 12.dp, bottom = if (compact) 6.dp else 12.dp),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      Modifier
        .size(width = 34.dp, height = 4.dp)
        .background(
          if (visible) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f) else Color.Transparent,
          RoundedCornerShape(2.dp),
        ),
    )
  }
}

@Composable
internal fun SheetHeaderIcon(icon: AppIcon?) {
  if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun bottomAttachedSheetShape(television: Boolean): Shape {
  val shape = MaterialTheme.shapes.extraLargeIncreased
  return if (television) shape else shape.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerSheetHeader(
  title: String,
  modifier: Modifier = Modifier,
  leadingContent: (@Composable () -> Unit)? = null,
  subtitle: String? = null,
  titleContent: (@Composable () -> Unit)? = null,
  scrollActions: Boolean = false,
  actions: @Composable RowScope.() -> Unit = {},
) {
  Column(modifier.fillMaxWidth()) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
      val actionMaxWidth = maxWidth / 2
      Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        leadingContent?.invoke()
        if (title.isNotBlank() || titleContent != null || subtitle != null) {
          val titleWidth = if (scrollActions) Modifier.widthIn(max = 120.dp) else Modifier.weight(1f)
          Column(titleWidth.padding(vertical = 6.dp)) {
            if (titleContent != null) {
              titleContent()
            } else if (title.isNotBlank()) {
              Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMediumEmphasized,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
            if (subtitle != null) {
              Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        } else {
          Spacer(Modifier.weight(1f))
        }
        Row(
          modifier =
            (if (scrollActions) Modifier.weight(1f) else Modifier.widthIn(max = actionMaxWidth))
              .horizontalScroll(rememberScrollState(), reverseScrolling = true),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
          content = actions,
        )
      }
    }
    HorizontalDivider(
      modifier = Modifier.padding(horizontal = 12.dp),
      color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerSheetSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 12.dp)
        .semantics { heading() },
    horizontalArrangement =
      androidx.compose.foundation.layout.Arrangement
        .spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      modifier = Modifier.size(width = 4.dp, height = 24.dp),
      shape = MaterialTheme.shapes.extraSmall,
      color = MaterialTheme.colorScheme.primary,
      content = {},
    )
    Text(
      text = title,
      style = MaterialTheme.typography.labelLargeEmphasized,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerSheetAction(
  icon: AppIcon,
  label: String,
  onClick: () -> Unit,
  enabled: Boolean = true,
  highlighted: Boolean = false,
) {
  TooltipBox(
    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
    tooltip = { PlainTooltip { Text(label) } },
    state = rememberTooltipState(),
  ) {
    IconButton(
      onClick = onClick,
      enabled = enabled,
      shapes = IconButtonDefaults.shapes(),
      modifier = Modifier.size(48.dp).tvFocusHighlight(MaterialTheme.shapes.medium),
    ) {
      Icon(
        icon,
        contentDescription = label,
        modifier = Modifier.size(22.dp),
        tint =
          if (enabled &&
            highlighted
          ) {
            MaterialTheme.colorScheme.primary
          } else {
            androidx.compose.material3.LocalContentColor.current
          },
      )
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
