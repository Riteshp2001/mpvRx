// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppConnectedControlShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import android.graphics.Color as AndroidColor
import app.gyrolet.mpvrx.ui.components.AppSlider as Slider

/** Modes shared across the app's color pickers. */
enum class ColorPickerMode { Wheel, Field, Sliders }

/** Inline editor shared by player panels and theme preferences. Colors are Android ARGB. */
@Composable
fun ExpressiveColorPicker(
  color: Int,
  onColorChange: (Int) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  showAlpha: Boolean = true,
  attachedToHeader: Boolean = false,
  forcedSliders: Boolean? = null,
  forcedMode: ColorPickerMode? = null,
  wheelMaxSize: Dp = 240.dp,
) {
  var modeIndex by rememberSaveable { mutableIntStateOf(ColorPickerMode.Wheel.ordinal) }
  // The legacy forcedSliders option remains supported.
  val mode =
    forcedMode
      ?: forcedSliders?.let { if (it) ColorPickerMode.Sliders else ColorPickerMode.Field }
      ?: ColorPickerMode.entries[modeIndex.coerceIn(0, ColorPickerMode.entries.lastIndex)]
  val hsv = remember { floatArrayOf(0f, 0f, 0f).also { AndroidColor.colorToHSV(color, it) } }
  var hue by remember { mutableFloatStateOf(hsv[0]) }
  var saturation by remember { mutableFloatStateOf(hsv[1]) }
  var brightness by remember { mutableFloatStateOf(hsv[2]) }
  var emittedColor by remember { mutableIntStateOf(color) }
  // Preserve the chosen hue at white/gray and both hue/saturation at black.
  LaunchedEffect(color) {
    if (color != emittedColor) {
      AndroidColor.colorToHSV(color, hsv)
      if (hsv[1] > 0f && hsv[2] > 0f) hue = hsv[0]
      if (hsv[2] > 0f) saturation = hsv[1]
      brightness = hsv[2]
      emittedColor = color
    }
  }

  fun update(
    h: Float = hue,
    s: Float = saturation,
    v: Float = brightness,
  ) {
    hue = h
    saturation = s
    brightness = v
    emittedColor = AndroidColor.HSVToColor(if (showAlpha) AndroidColor.alpha(color) else 255, floatArrayOf(h, s, v))
    onColorChange(emittedColor)
  }
  val count = if (showAlpha) 4 else 3
  val offset = if (attachedToHeader) 1 else 0
  Column(
    modifier.alpha(if (enabled) 1f else 0.45f),
    verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
  ) {
    PickerTile(0 + offset, count + offset) {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (forcedMode == null && forcedSliders == null) {
          Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(AppConnectedControlShapeTokens.spacing),
          ) {
            listOf(
              R.string.color_picker_wheel,
              R.string.color_picker_field,
              R.string.color_picker_sliders,
            ).forEachIndexed {
              index,
              label,
              ->
              val selected = modeIndex == index
              val shape =
                rememberConnectedShape(index, 3, ConnectedLayout.Horizontal, tokens = AppConnectedControlShapeTokens)
              val colors = MaterialTheme.colorScheme
              Surface(
                modifier =
                  Modifier.weight(1f).clip(shape).selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = { modeIndex = index },
                  ),
                shape = shape,
                color = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
                contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
                border = BorderStroke(1.dp, if (selected) colors.secondaryContainer else colors.outlineVariant),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                  horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  if (selected) Icon(Icons.RoundedFilled.Check, null, modifier = Modifier.size(16.dp))
                  Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                  )
                }
              }
            }
          }
        }
        when (mode) {
          ColorPickerMode.Wheel -> {
            HueSaturationWheel(
              hue = hue,
              sat = saturation,
              value = brightness,
              enabled = enabled,
              maxSize = wheelMaxSize,
              onChange = { h, s -> update(h = h, s = s) },
            )
            GradientBar(
              label = stringResource(R.string.color_picker_brightness),
              fraction = brightness,
              valueText = "${(brightness * 100f).roundToInt()}%",
              brush =
                Brush.horizontalGradient(
                  listOf(Color.Black, Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, 1f)))),
                ),
              enabled = enabled,
              onFraction = { update(v = it) },
            )
          }
          ColorPickerMode.Sliders -> {
            listOf(
              R.string.player_sheets_sub_color_red,
              R.string.player_sheets_sub_color_green,
              R.string.player_sheets_sub_color_blue,
            ).forEachIndexed {
              index,
              label,
              ->
              val shift = 16 - index * 8
              ChannelSlider(stringResource(label), (color ushr shift) and 255, enabled) {
                onColorChange((color and (255 shl shift).inv()) or (it shl shift))
              }
            }
          }
          ColorPickerMode.Field -> {
            Row(Modifier.fillMaxWidth().height(180.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              val fieldLabel = stringResource(R.string.color_picker_field_description)
              Canvas(
                Modifier
                  .weight(1f)
                  .fillMaxHeight()
                  .clip(RoundedCornerShape(20.dp))
                  .semantics { contentDescription = fieldLabel }
                  .colorGesture(enabled) { x, y -> update(s = x, v = 1f - y) },
              ) {
                drawRect(Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f))))
                drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val radius = 9.dp.toPx()
                val point =
                  Offset(
                    (saturation * size.width).coerceIn(radius + 2.dp.toPx(), size.width - radius - 2.dp.toPx()),
                    ((1f - brightness) * size.height).coerceIn(
                      radius + 2.dp.toPx(),
                      size.height - radius - 2.dp.toPx(),
                    ),
                  )
                drawCircle(Color.Black.copy(alpha = 0.5f), radius + 2.dp.toPx(), point, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, radius, point, style = Stroke(2.dp.toPx()))
              }
              val hueLabel = stringResource(R.string.color_picker_hue)
              Canvas(
                Modifier
                  .width(48.dp)
                  .fillMaxHeight()
                  .clip(RoundedCornerShape(24.dp))
                  .semantics { contentDescription = hueLabel }
                  .colorGesture(enabled) { _, y -> update(h = (y * 360f).coerceAtMost(359.99f)) },
              ) {
                drawRect(
                  Brush.verticalGradient(
                    listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
                  ),
                )
                val y = (hue / 360f * size.height).coerceIn(12.dp.toPx(), size.height - 12.dp.toPx())
                drawCircle(
                  Color.Black.copy(alpha = 0.5f),
                  11.dp.toPx(),
                  Offset(center.x, y),
                  style = Stroke(4.dp.toPx()),
                )
                drawCircle(Color.White, 11.dp.toPx(), Offset(center.x, y), style = Stroke(2.dp.toPx()))
              }
            }
          }
        }
      }
    }
    PickerTile(1 + offset, count + offset) {
      Row(
        Modifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Canvas(Modifier.size(44.dp).clip(CircleShape)) {
          drawCheckerboard()
          drawRect(Color(color))
        }
        Column {
          Text(
            stringResource(R.string.color_picker_selected),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            if (showAlpha) "#%08X".format(color) else "#%06X".format(color and 0xFFFFFF),
            style = MaterialTheme.typography.titleMedium,
          )
        }
      }
    }
    if (showAlpha) {
      PickerTile(2 + offset, count + offset) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.player_sheets_sub_color_alpha), style = MaterialTheme.typography.labelLarge)
            Text(
              "${(AndroidColor.alpha(color) / 255f * 100).roundToInt()}%",
              style = MaterialTheme.typography.labelLarge,
            )
          }
          val alphaLabel = stringResource(R.string.player_sheets_sub_color_alpha)
          Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape)) {
              drawCheckerboard()
              drawRect(Brush.horizontalGradient(listOf(Color(color).copy(alpha = 0f), Color(color).copy(alpha = 1f))))
            }
            Slider(
              modifier = Modifier.semantics { contentDescription = alphaLabel },
              value = AndroidColor.alpha(color).toFloat(),
              onValueChange = { onColorChange((color and 0xFFFFFF) or (it.roundToInt().coerceIn(0, 255) shl 24)) },
              enabled = enabled,
              valueRange = 0f..255f,
              colors =
                SliderDefaults.colors(
                  activeTrackColor = Color.Transparent,
                  inactiveTrackColor = Color.Transparent,
                ),
            )
          }
        }
      }
    }
    PickerTile(count - 1 + offset, count + offset) {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.color_picker_quick_colors), style = MaterialTheme.typography.labelMedium)
        Row(
          Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          listOf(
            0xFFFFFFFF,
            0xFF9E9E9E,
            0xFF424242,
            0xFF000000,
            0xFFFF1744,
            0xFFFF9100,
            0xFFFFEA00,
            0xFF00C853,
            0xFF00E5FF,
            0xFF2979FF,
            0xFFD500F9,
          ).forEach { preset ->
            val rgb = preset.toInt()
            val isSelected = (color and 0xFFFFFF) == (rgb and 0xFFFFFF)
            Box(
              Modifier
                .size(48.dp)
                .semantics {
                  contentDescription = "#%06X".format(rgb and 0xFFFFFF)
                  selected = isSelected
                }.clip(CircleShape)
                .clickable(enabled = enabled) {
                  onColorChange((rgb and 0xFFFFFF) or ((if (showAlpha) AndroidColor.alpha(color) else 255) shl 24))
                }.padding(
                  6.dp,
                ).border(
                  if (isSelected) 3.dp else 1.dp,
                  if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                  CircleShape,
                ).padding(if (isSelected) 4.dp else 1.dp)
                .background(Color(rgb), CircleShape),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PickerTile(
  index: Int,
  count: Int,
  content: @Composable () -> Unit,
) {
  Surface(
    shape = rememberConnectedShape(index, count, ConnectedLayout.Vertical),
    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f),
    modifier = Modifier.fillMaxWidth(),
  ) { content() }
}

@Composable
private fun ChannelSlider(
  label: String,
  value: Int,
  enabled: Boolean,
  onChange: (Int) -> Unit,
) {
  Column {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label, style = MaterialTheme.typography.labelMedium)
      Text(value.toString(), style = MaterialTheme.typography.labelMedium)
    }
    Slider(
      value = value.toFloat(),
      onValueChange = { onChange(it.roundToInt().coerceIn(0, 255)) },
      valueRange =
        0f..255f,
      enabled = enabled,
      modifier = Modifier.semantics { contentDescription = label },
    )
  }
}

/**
 * HSV wheel adapted from contributor Utsavrajputt's mpvRx PR #880.
 * Angle selects hue, radius selects saturation, and brightness is controlled separately.
 */
@Composable
private fun HueSaturationWheel(
  hue: Float,
  sat: Float,
  value: Float,
  enabled: Boolean,
  maxSize: Dp,
  onChange: (hue: Float, sat: Float) -> Unit,
) {
  val wheelLabel = stringResource(R.string.color_picker_wheel)
  val thumbRadiusPx = with(LocalDensity.current) { 12.dp.toPx() }
  val hueColors =
    remember {
      (0..12).map { Color(AndroidColor.HSVToColor(floatArrayOf((it * 30f) % 360f, 1f, 1f))) }
    }
  val latestChange by rememberUpdatedState(onChange)
  BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    val side = minOf(maxWidth, maxSize)
    Canvas(
      Modifier
        .size(side)
        .alpha(if (enabled) 1f else 0.45f)
        .semantics {
          contentDescription = wheelLabel
          stateDescription = "${hue.roundToInt()}°, ${(sat * 100f).roundToInt()}%"
          progressBarRangeInfo = ProgressBarRangeInfo(sat.coerceIn(0f, 1f), 0f..1f)
          setProgress { target ->
            if (enabled) latestChange(hue, target.coerceIn(0f, 1f))
            enabled
          }
        }.onKeyEvent { event ->
          if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
          when (event.key) {
            Key.DirectionLeft -> {
              latestChange((hue - 5f + 360f) % 360f, sat)
              true
            }
            Key.DirectionRight -> {
              latestChange((hue + 5f) % 360f, sat)
              true
            }
            Key.DirectionUp -> {
              latestChange(hue, (sat + 0.02f).coerceAtMost(1f))
              true
            }
            Key.DirectionDown -> {
              latestChange(hue, (sat - 0.02f).coerceAtLeast(0f))
              true
            }
            else -> false
          }
        }.focusable(enabled)
        .pointerInput(enabled) {
          if (!enabled) return@pointerInput

          fun update(position: Offset) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = (min(size.width, size.height) / 2f - thumbRadiusPx).coerceAtLeast(1f)
            val dx = position.x - center.x
            val dy = position.y - center.y
            val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            latestChange((angle + 360f) % 360f, (hypot(dx, dy) / radius).coerceIn(0f, 1f))
          }
          awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            update(down.position)
            drag(down.id) {
              it.consume()
              update(it.position)
            }
          }
        },
    ) {
      val center = Offset(size.width / 2f, size.height / 2f)
      val radius = (min(size.width, size.height) / 2f - thumbRadiusPx).coerceAtLeast(1f)
      drawCircle(Brush.sweepGradient(hueColors, center), radius, center)
      drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), center, radius), radius, center)
      drawCircle(Color.Black.copy(alpha = (1f - value).coerceIn(0f, 1f)), radius, center)
      val radians = Math.toRadians(hue.toDouble())
      val thumb =
        Offset(
          center.x + (cos(radians) * radius * sat).toFloat(),
          center.y + (sin(radians) * radius * sat).toFloat(),
        )
      drawCircle(Color(AndroidColor.HSVToColor(floatArrayOf(hue, sat, value))), thumbRadiusPx - 2f, thumb)
      drawCircle(Color.White, thumbRadiusPx, thumb, style = Stroke(width = 3.dp.toPx()))
      drawCircle(Color.Black.copy(alpha = 0.35f), thumbRadiusPx + 1.5f, thumb, style = Stroke(width = 1.5.dp.toPx()))
    }
  }
}

/** Color-aware brightness track with drag, TalkBack and keyboard controls. */
@Composable
private fun GradientBar(
  label: String,
  fraction: Float,
  valueText: String,
  brush: Brush,
  enabled: Boolean,
  onFraction: (Float) -> Unit,
) {
  val thumbRadiusPx = with(LocalDensity.current) { 13.dp.toPx() }
  val latestFraction by rememberUpdatedState(onFraction)
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label, style = MaterialTheme.typography.labelMedium)
      Text(valueText, style = MaterialTheme.typography.labelMedium)
    }
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(36.dp)
          .alpha(if (enabled) 1f else 0.45f)
          .semantics {
            contentDescription = label
            stateDescription = valueText
            progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
            setProgress { target ->
              if (enabled) latestFraction(target.coerceIn(0f, 1f))
              enabled
            }
          }.onKeyEvent { event ->
            if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (event.key) {
              Key.DirectionLeft -> {
                latestFraction((fraction - 1f / 64f).coerceIn(0f, 1f))
                true
              }
              Key.DirectionRight -> {
                latestFraction((fraction + 1f / 64f).coerceIn(0f, 1f))
                true
              }
              else -> false
            }
          }.focusable(enabled)
          .pointerInput(enabled) {
            if (!enabled) return@pointerInput

            fun update(x: Float) {
              val span = (size.width - 2f * thumbRadiusPx).coerceAtLeast(1f)
              latestFraction(((x - thumbRadiusPx) / span).coerceIn(0f, 1f))
            }
            awaitEachGesture {
              val down = awaitFirstDown()
              down.consume()
              update(down.position.x)
              drag(down.id) {
                it.consume()
                update(it.position.x)
              }
            }
          },
      contentAlignment = Alignment.CenterStart,
    ) {
      Box(
        Modifier
          .fillMaxWidth()
          .height(20.dp)
          .clip(RoundedCornerShape(10.dp))
          .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
          .background(brush),
      )
      Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        val span = (size.width - 2f * thumbRadiusPx).coerceAtLeast(1f)
        val thumb = Offset(thumbRadiusPx + span * fraction.coerceIn(0f, 1f), size.height / 2f)
        drawCircle(Color.White, thumbRadiusPx, thumb)
        drawCircle(Color.Black.copy(alpha = 0.35f), thumbRadiusPx, thumb, style = Stroke(width = 1.5.dp.toPx()))
      }
    }
  }
}

/** Capture the gesture immediately so picking cannot drag the parent player panel. */
@Composable
private fun Modifier.colorGesture(
  enabled: Boolean,
  onChange: (Float, Float) -> Unit,
): Modifier {
  val latestChange by rememberUpdatedState(onChange)
  return pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
      val down = awaitFirstDown()
      down.consume()

      fun update(position: Offset) {
        latestChange(
          (position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
          (
            position.y /
              size.height.coerceAtLeast(1)
          ).coerceIn(0f, 1f),
        )
      }
      update(down.position)
      do {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (change.pressed) update(change.position)
        change.consume()
      } while (change.pressed)
    }
  }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCheckerboard() {
  val cell = 6.dp.toPx()
  drawRect(Color.White)
  for (row in 0..(size.height / cell).toInt()) {
    for (column in 0..(size.width / cell).toInt()) {
      if ((row + column) % 2 == 0) drawRect(Color.LightGray, Offset(column * cell, row * cell), Size(cell, cell))
    }
  }
}
