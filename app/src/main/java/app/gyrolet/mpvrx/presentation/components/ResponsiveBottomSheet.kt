/* SPDX-License-Identifier: AGPL-3.0-or-later */

package app.gyrolet.mpvrx.presentation.components

import android.annotation.SuppressLint
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.tv.TvSafeArea
import app.gyrolet.mpvrx.ui.tv.isTelevision
import kotlinx.coroutines.launch
import kotlin.math.abs

internal data class SheetWindow(val width: Dp, val height: Dp, val television: Boolean) {
  private val landscape = width >= height
  private val large = minOf(width, height) >= 600.dp

  fun maxWidth(preferredWidth: Dp? = null): Dp = when {
    television -> minOf((width - TvSafeArea.Horizontal * 2).coerceAtLeast(0.dp), preferredWidth ?: 960.dp)
    large -> minOf(width - 48.dp, preferredWidth ?: if (landscape) 1000.dp else 840.dp)
    landscape -> preferredWidth ?: minOf(width - 48.dp, 640.dp)
    else -> preferredWidth ?: Dp.Unspecified
  }

  fun maxHeight(availableHeight: Dp, keyboardVisible: Boolean): Dp =
    if (!television && landscape && !keyboardVisible) availableHeight * 0.85f else availableHeight

  fun initialHeight(availableHeight: Dp, keyboardVisible: Boolean): Dp {
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

internal class CenteredSheetInsets(private val insets: WindowInsets) : WindowInsets {
  override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int =
    maxOf(insets.getLeft(density, layoutDirection), insets.getRight(density, layoutDirection))

  override fun getRight(density: Density, layoutDirection: LayoutDirection): Int =
    getLeft(density, layoutDirection)

  override fun getTop(density: Density): Int = insets.getTop(density)

  override fun getBottom(density: Density): Int = insets.getBottom(density)
}

internal data class SheetResize(val expanded: Boolean, val setExpanded: (Boolean) -> Unit)

private val SheetExpandThreshold = 24.dp

internal fun Modifier.sheetExpandDrag(setExpanded: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
  var travelled = 0f
  var committed = false
  detectVerticalDragGestures(
    onDragStart = {
      travelled = 0f
      committed = false
    },
    onVerticalDrag = { change, amount ->
      change.consume()
      if (!committed) {
        travelled += amount
        if (abs(travelled) >= SheetExpandThreshold.toPx()) {
          committed = true
          setExpanded(travelled < 0f)
        }
      }
    },
  )
}

/** Constrain the content, not its outer anchor, so pinned actions aren't below the fold. */
@Composable
internal fun ResponsiveSheetContent(
  window: SheetWindow,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.(SheetResize?) -> Unit,
) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  var contentHeightPx by remember { mutableIntStateOf(0) }
  val density = LocalDensity.current
  val safeDrawing = WindowInsets.safeDrawing
  val verticalInsets = with(density) {
    (safeDrawing.getTop(density) + safeDrawing.getBottom(density)).toDp()
  }
  val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
  val reduceMotion = AppMotion.shouldReduceMotion()

  val availableHeight = (window.height - verticalInsets).coerceAtLeast(0.dp)
  val initialHeight = window.initialHeight(availableHeight, keyboardVisible)
  val animatedHeight by animateDpAsState(
    targetValue = if (expanded) availableHeight else initialHeight,
    animationSpec = if (reduceMotion) AppMotion.ReducedDp else AppMotion.noBounce(380f),
    label = "sheetExpansionHeight",
  )
  val contentHeight = animatedHeight.coerceIn(0.dp, availableHeight)
  val isAtInitialLimit = contentHeightPx >= with(density) { initialHeight.roundToPx() }
  val resize = if (availableHeight > initialHeight && (expanded || isAtInitialLimit)) {
    SheetResize(expanded) { expanded = it }
  } else null
  Column(
    modifier.fillMaxWidth()
      .heightIn(min = if (expanded) contentHeight else 0.dp, max = contentHeight)
      .onSizeChanged { contentHeightPx = it.height },
  ) {
    content(resize)
  }
}

@Composable
internal fun SheetOpeningHandle(
  resize: SheetResize?,
  showDragHandle: Boolean,
  gesturesEnabled: Boolean = true,
) {
  if (resize != null || showDragHandle) {
    PlayerSheetDragHandle(
      onVerticalDrag = resize?.setExpanded,
      visible = gesturesEnabled || resize != null,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResponsiveBottomSheet(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  sheetState: SheetState = rememberBottomSheetState(
    initialValue = SheetValue.Hidden,
    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
  ),
  sheetGesturesEnabled: Boolean = true,
  shape: Shape = BottomSheetDefaults.ExpandedShape,
  containerColor: Color = BottomSheetDefaults.ContainerColor,
  contentColor: Color = contentColorFor(containerColor),
  showDragHandle: Boolean = true,
  properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
  content: @Composable ColumnScope.() -> Unit,
) {
  val window = sheetWindow()
  val scope = rememberCoroutineScope()
  val onDismiss by rememberUpdatedState(onDismissRequest)
  val closeLabel = stringResource(R.string.ui_close)
  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    modifier = modifier.semantics {
      if (sheetGesturesEnabled) {
        dismiss(closeLabel) {
          scope.launch {
            sheetState.hide()
            if (!sheetState.isVisible) onDismiss()
          }
          true
        }
      }
    },
    sheetState = sheetState,
    sheetMaxWidth = window.maxWidth(),
    sheetGesturesEnabled = sheetGesturesEnabled,
    shape = shape,
    containerColor = containerColor,
    contentColor = contentColor,
    dragHandle = null,
    contentWindowInsets = { CenteredSheetInsets(WindowInsets.safeDrawing) },
    properties = properties,
  ) {
    ResponsiveSheetContent(
      window = window,
      modifier = if (window.television) {
        Modifier.padding(vertical = TvSafeArea.Vertical)
      } else {
        Modifier.padding(top = 4.dp)
      },
    ) { resize ->
      SheetOpeningHandle(
        resize = resize,
        showDragHandle = showDragHandle && !window.television,
        gesturesEnabled = sheetGesturesEnabled,
      )
      content()
    }
  }
}
