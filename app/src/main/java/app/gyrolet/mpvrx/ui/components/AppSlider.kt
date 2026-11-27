package app.gyrolet.mpvrx.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSliderState
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import app.gyrolet.mpvrx.ui.liquidglass.LiquidSlider
import app.gyrolet.mpvrx.ui.liquidglass.LiquidSliderThumb
import app.gyrolet.mpvrx.ui.liquidglass.LiquidSliderTrack
import app.gyrolet.mpvrx.ui.liquidglass.LocalKyantPlayerBackdrop
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassActive
import app.gyrolet.mpvrx.ui.liquidglass.rememberLiquidSliderAnimation
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.tv.isTelevision
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSlider(
  value: Float,
  onValueChange: (Float) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
  steps: Int = 0,
  onValueChangeFinished: (() -> Unit)? = null,
  colors: SliderColors = SliderDefaults.colors(),
  interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
  onInteractionChange: (Boolean) -> Unit = {},
) {
  val glassEnabled = liquidGlassActive()
  if (!glassEnabled ||
    !valueRange.start.isFinite() ||
    !valueRange.endInclusive.isFinite() ||
    valueRange.endInclusive <= valueRange.start
  ) {
    val pressed by interactionSource.collectIsPressedAsState()
    val dragged by interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pressed, dragged) { onInteractionChange(pressed || dragged) }
    val materialState = remember(steps, valueRange) { SliderState(value, steps, valueRange) }
    materialState.value = value
    androidx.compose.material3.Slider(
      state = materialState,
      onValueChange = onValueChange,
      modifier = modifier,
      enabled = enabled,
      onValueChangeFinished = onValueChangeFinished,
      colors = colors,
      interactionSource = interactionSource,
    )
    return
  }

  val sliderState = remember(steps, valueRange) { SliderState(value, steps, valueRange) }
  var isUserInteracting by remember(sliderState) { mutableStateOf(false) }
  // Incoming preference/MPV updates must not overwrite a value owned by the active drag.
  // Sync only when the external value changes, rather than on every recomposition.
  LaunchedEffect(value, sliderState) {
    if (!isUserInteracting) sliderState.value = value
  }
  val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
  val canvasBackdrop = rememberCanvasBackdrop { drawRect(surfaceColor) }
  val backdrop = LocalKyantPlayerBackdrop.current ?: canvasBackdrop
  val span = valueRange.endInclusive - valueRange.start
  val updateValue: (Float) -> Unit = { changedValue ->
    sliderState.value = changedValue
    val snappedValue = sliderState.value
    if (snappedValue != value) onValueChange(snappedValue)
  }
  LiquidSlider(
    value = { sliderState.value },
    onValueChange = updateValue,
    valueRange = valueRange,
    steps = steps,
    visibilityThreshold = if (steps > 0) span / (steps + 1) / 100f else span / 1000f,
    backdrop = backdrop,
    modifier =
      modifier
        .tvSliderKeys(
          value = { sliderState.value },
          valueRange = valueRange,
          steps = steps,
          enabled = enabled,
          interactionSource = interactionSource,
          onValueChange = updateValue,
          onValueChangeFinished = onValueChangeFinished,
        ).progressSemantics(sliderState.value, valueRange, steps)
        .semantics {
          if (!enabled) disabled()
          setProgress { requestedValue ->
            if (!enabled) {
              false
            } else {
              val previousValue = sliderState.value
              updateValue(requestedValue)
              val changed = previousValue != sliderState.value
              if (changed) onValueChangeFinished?.invoke()
              changed
            }
          }
        },
    enabled = enabled,
    onValueChangeFinished = onValueChangeFinished,
    interactionSource = interactionSource,
    onInteractionChange = {
      isUserInteracting = it
      onInteractionChange(it)
    },
    accentColor = if (enabled) colors.activeTrackColor else colors.disabledActiveTrackColor,
    trackColor = if (enabled) colors.inactiveTrackColor else colors.disabledInactiveTrackColor,
  )
}

/** The glass slider has pointer input; give TV remotes an explicit, visible adjustment target. */
private fun Modifier.tvSliderKeys(
  value: () -> Float,
  valueRange: ClosedFloatingPointRange<Float>,
  steps: Int,
  enabled: Boolean,
  interactionSource: MutableInteractionSource,
  onValueChange: (Float) -> Unit,
  onValueChangeFinished: (() -> Unit)?,
): Modifier =
  composed {
    if (!isTelevision()) return@composed this
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    this
      .tvFocusHighlight()
      .onKeyEvent { event ->
        if (!enabled) return@onKeyEvent false
        val direction =
          when (event.key) {
            Key.DirectionRight -> if (rtl) -1 else 1
            Key.DirectionLeft -> if (rtl) 1 else -1
            else -> return@onKeyEvent false
          }
        if (event.type == KeyEventType.KeyDown) {
          val span = valueRange.endInclusive - valueRange.start
          val increment = span / (if (steps > 0) steps + 1 else 20)
          val changed = (value() + direction * increment).coerceIn(valueRange)
          onValueChange(changed)
          onValueChangeFinished?.invoke()
        }
        true
      }.focusable(enabled, interactionSource)
  }

@Composable
private fun GlassSliderThumb(
  value: () -> Float,
  valueRange: ClosedFloatingPointRange<Float>,
  interactionSource: MutableInteractionSource,
  enabled: Boolean,
  colors: SliderColors,
  backdrop: Backdrop,
  trackBackdrop: Backdrop,
) {
  val pressed by interactionSource.collectIsPressedAsState()
  val dragged by interactionSource.collectIsDraggedAsState()
  val interacting = enabled && (pressed || dragged)
  val reduceMotion = AppMotion.shouldReduceMotion()
  val animation =
    rememberLiquidSliderAnimation(
      value = value,
      valueRange = valueRange,
      visibilityThreshold = (valueRange.endInclusive - valueRange.start) / 1000f,
    )
  LaunchedEffect(animation, interacting, reduceMotion) {
    if (interacting && !reduceMotion) animation.press() else animation.release()
  }
  LiquidSliderThumb(
    animation = animation,
    backdrop = backdrop,
    trackBackdrop = trackBackdrop,
    accentColor = if (enabled) colors.activeTrackColor else colors.disabledActiveTrackColor,
    enabled = enabled,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRangeSlider(
  value: ClosedFloatingPointRange<Float>,
  onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
  steps: Int = 0,
  onValueChangeFinished: (() -> Unit)? = null,
  colors: SliderColors = SliderDefaults.colors(),
) {
  val glassEnabled = liquidGlassActive()
  val state = remember(steps, valueRange) { RangeSliderState(value.start, value.endInclusive, steps, valueRange) }
  if (
    !glassEnabled ||
    !valueRange.start.isFinite() ||
    !valueRange.endInclusive.isFinite() ||
    valueRange.endInclusive <= valueRange.start
  ) {
    state.startValue = value.start
    state.endValue = value.endInclusive
    androidx.compose.material3.RangeSlider(
      state = state,
      onValueChange = onValueChange,
      modifier = modifier,
      enabled = enabled,
      onValueChangeFinished = onValueChangeFinished,
      colors = colors,
    )
    return
  }
  val startInteraction = remember { MutableInteractionSource() }
  val endInteraction = remember { MutableInteractionSource() }
  val startPressed by startInteraction.collectIsPressedAsState()
  val startDragged by startInteraction.collectIsDraggedAsState()
  val endPressed by endInteraction.collectIsPressedAsState()
  val endDragged by endInteraction.collectIsDraggedAsState()
  val interacting = startPressed || startDragged || endPressed || endDragged
  LaunchedEffect(value, state, interacting) {
    if (!interacting) {
      state.startValue = value.start
      state.endValue = value.endInclusive
    }
  }
  val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
  val canvasBackdrop = rememberCanvasBackdrop { drawRect(surfaceColor) }
  val backdrop = LocalKyantPlayerBackdrop.current ?: canvasBackdrop
  val trackBackdrop = rememberLayerBackdrop()
  val span = valueRange.endInclusive - valueRange.start
  androidx.compose.material3.RangeSlider(
    state = state,
    onValueChange = onValueChange,
    modifier = modifier,
    enabled = enabled,
    onValueChangeFinished = onValueChangeFinished,
    colors = colors,
    startThumbInteractionSource = startInteraction,
    endThumbInteractionSource = endInteraction,
    startThumb = { rangeState ->
      GlassSliderThumb(
        { rangeState.startValue },
        valueRange,
        startInteraction,
        enabled,
        colors,
        backdrop,
        trackBackdrop,
      )
    },
    endThumb = { rangeState ->
      GlassSliderThumb({ rangeState.endValue }, valueRange, endInteraction, enabled, colors, backdrop, trackBackdrop)
    },
    track = { rangeState ->
      LiquidSliderTrack(
        startFraction = { (rangeState.startValue - valueRange.start) / span },
        endFraction = { (rangeState.endValue - valueRange.start) / span },
        backdrop = backdrop,
        accentColor = if (enabled) colors.activeTrackColor else colors.disabledActiveTrackColor,
        trackColor = if (enabled) colors.inactiveTrackColor else colors.disabledInactiveTrackColor,
        steps = steps,
        enabled = enabled,
        modifier = Modifier.layerBackdrop(trackBackdrop),
      )
    },
  )
}
