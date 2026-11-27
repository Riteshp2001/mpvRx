// SPDX-License-Identifier: AGPL-3.0-or-later

package app.gyrolet.mpvrx.presentation.components

import android.annotation.SuppressLint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.tv.TvSafeArea
import app.gyrolet.mpvrx.ui.tv.isTelevision
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class SheetWindow(
  val width: Dp,
  val height: Dp,
  val television: Boolean,
) {
  private val landscape = width >= height
  private val large = minOf(width, height) >= 600.dp

  fun maxWidth(preferredWidth: Dp? = null): Dp =
    when {
      television -> minOf((width - TvSafeArea.Horizontal * 2).coerceAtLeast(0.dp), preferredWidth ?: 960.dp)
      large -> minOf(width - 48.dp, preferredWidth ?: if (landscape) 1000.dp else 840.dp)
      landscape -> preferredWidth ?: minOf(width - 48.dp, 640.dp)
      else -> Dp.Unspecified
    }

  fun maxHeight(
    availableHeight: Dp,
    keyboardVisible: Boolean,
  ): Dp = if (!television && landscape && !keyboardVisible) availableHeight * 0.85f else availableHeight

  fun initialHeight(
    availableHeight: Dp,
    keyboardVisible: Boolean,
  ): Dp {
    val capped = maxHeight(availableHeight, keyboardVisible)
    return when {
      television || landscape || keyboardVisible || availableHeight <= 440.dp -> capped
      else -> (capped * (if (large) 0.66f else 0.62f)).coerceAtLeast(320.dp)
    }
  }
}

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
internal fun sheetWindow(): SheetWindow {
  val configuration = LocalConfiguration.current
  return SheetWindow(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp, isTelevision())
}

internal class CenteredSheetInsets(
  private val insets: WindowInsets,
) : WindowInsets {
  override fun getLeft(
    density: Density,
    layoutDirection: LayoutDirection,
  ): Int = maxOf(insets.getLeft(density, layoutDirection), insets.getRight(density, layoutDirection))

  override fun getRight(
    density: Density,
    layoutDirection: LayoutDirection,
  ): Int = getLeft(density, layoutDirection)

  override fun getTop(density: Density): Int = insets.getTop(density)

  override fun getBottom(density: Density): Int = insets.getBottom(density)
}

internal data class SheetResize(
  val expanded: Boolean,
  val setExpanded: (Boolean) -> Unit,
)

internal val LocalCompactSheetControls = staticCompositionLocalOf { false }

@Composable
private fun Modifier.sheetExpandDrag(resize: SheetResize): Modifier {
  val latestResize by rememberUpdatedState(resize)
  return pointerInput(Unit) {
    awaitEachGesture {
      val down = awaitFirstDown()
      val expanded = latestResize.expanded
      val drag =
        awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
          // A downward drag from the compact height still belongs to swipe-to-dismiss.
          if (if (expanded) overSlop > 0f else overSlop < 0f) change.consume()
        }
      if (drag != null) {
        do {
          val event = awaitPointerEvent()
          event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
        latestResize.setExpanded(!expanded)
      }
    }
  }
}

/** Measures the sheet body against the host's live bounds; only user-requested expansion animates. */
@Composable
internal fun ResponsiveSheetContent(
  window: SheetWindow,
  modifier: Modifier = Modifier,
  reservedTopInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
  content: @Composable ColumnScope.(SheetResize?) -> Unit,
) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  var canResize by remember { mutableStateOf(false) }
  val imeInsets = WindowInsets.ime
  val reduceMotion = AppMotion.shouldReduceMotion()
  val expansion =
    animateFloatAsState(
      targetValue = if (expanded) 1f else 0f,
      animationSpec = if (reduceMotion) snap() else AppMotion.noBounce(380f),
      label = "sheetExpansionHeight",
    )

  Column(
    modifier
      .fillMaxWidth()
      .layout { measurable, constraints ->
        val availableHeight =
          (
            (if (constraints.hasBoundedHeight) constraints.maxHeight else window.height.roundToPx()) -
              reservedTopInsets.getTop(this)
          ).coerceAtLeast(0)
        val compactHeight =
          window
            .initialHeight(availableHeight.toDp(), imeInsets.getBottom(this) > 0)
            .roundToPx()
            .coerceIn(0, availableHeight)
        val fraction = expansion.value.coerceIn(0f, 1f)
        val heightLimit =
          (compactHeight + ((availableHeight - compactHeight) * fraction).roundToInt())
            .coerceAtLeast(constraints.minHeight)
        val placeable =
          measurable.measure(
            constraints.copy(
              minHeight = if (fraction > 0f) heightLimit else constraints.minHeight,
              maxHeight = heightLimit,
            ),
          )
        canResize = availableHeight > compactHeight && (placeable.height >= compactHeight || expanded)
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
      },
  ) {
    CompositionLocalProvider(LocalCompactSheetControls provides true) {
      content(if (canResize) SheetResize(expanded) { expanded = it } else null)
    }
  }
}

@Composable
internal fun SheetOpeningHandle(
  resize: SheetResize?,
  showDragHandle: Boolean,
  gesturesEnabled: Boolean = true,
  resizeEnabled: Boolean = gesturesEnabled,
  compact: Boolean = true,
) {
  if (resize != null || showDragHandle) {
    PlayerSheetDragHandle(
      modifier = if (resize != null && resizeEnabled) Modifier.sheetExpandDrag(resize) else Modifier,
      visible = gesturesEnabled || (resize != null && resizeEnabled),
      compact = compact,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ResponsiveBottomSheet(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  animateEntrance: Boolean = true,
  dismissEnabled: Boolean = true,
  shape: Shape = bottomAttachedSheetShape(sheetWindow().television),
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
  contentColor: Color = contentColorFor(containerColor),
  showDragHandle: Boolean = true,
  title: String? = null,
  leadingIcon: AppIcon? = null,
  leadingContent: (@Composable () -> Unit)? = null,
  subtitle: String? = null,
  actions: @Composable RowScope.() -> Unit = {},
  content: @Composable ColumnScope.() -> Unit,
) {
  val window = sheetWindow()
  val scope = rememberCoroutineScope()
  val onDismiss by rememberUpdatedState(onDismissRequest)
  val canDismiss by rememberUpdatedState(dismissEnabled)
  val reduceMotion = AppMotion.shouldReduceMotion()
  val sheetState =
    rememberBottomSheetState(
      initialValue = if (animateEntrance && !reduceMotion) SheetValue.Hidden else SheetValue.Expanded,
      enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
      confirmValueChange = { it != SheetValue.Hidden || canDismiss },
    )
  val closeLabel = stringResource(R.string.ui_close)
  ModalBottomSheet(
    onDismissRequest = { if (canDismiss) onDismiss() },
    modifier =
      modifier.semantics {
        if (!title.isNullOrBlank()) paneTitle = title
        if (dismissEnabled) {
          dismiss(closeLabel) {
            scope.launch {
              sheetState.hide()
              if (canDismiss && !sheetState.isVisible) onDismiss()
            }
            true
          }
        }
      },
    sheetState = sheetState,
    sheetMaxWidth = window.maxWidth(),
    sheetGesturesEnabled = dismissEnabled,
    shape = shape,
    containerColor = containerColor,
    contentColor = contentColor,
    dragHandle = null,
    // Material consumes top insets based on its moving offset. Reserve the top in the
    // height limit instead, so the entrance doesn't repeatedly resize the sheet.
    contentWindowInsets = {
      CenteredSheetInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
    },
    properties =
      ModalBottomSheetProperties(
        shouldDismissOnBackPress = dismissEnabled,
        shouldDismissOnClickOutside = dismissEnabled,
      ),
  ) {
    ResponsiveSheetContent(
      window = window,
      reservedTopInsets = WindowInsets.safeDrawing,
      modifier = if (window.television) Modifier.padding(vertical = TvSafeArea.Vertical) else Modifier,
    ) { resize ->
      SheetOpeningHandle(
        resize = resize,
        showDragHandle = showDragHandle && !window.television && dismissEnabled,
        gesturesEnabled = dismissEnabled,
        resizeEnabled =
          dismissEnabled &&
            sheetState.currentValue == SheetValue.Expanded &&
            sheetState.targetValue == SheetValue.Expanded &&
            !sheetState.isAnimationRunning,
      )
      if (title != null) {
        PlayerSheetHeader(
          title = title,
          leadingContent = leadingContent ?: { SheetHeaderIcon(leadingIcon) },
          subtitle = subtitle,
          actions = actions,
        )
      }
      content()
    }
  }
}
