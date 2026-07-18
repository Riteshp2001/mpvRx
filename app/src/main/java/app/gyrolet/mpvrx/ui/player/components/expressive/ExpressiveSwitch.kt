package app.gyrolet.mpvrx.ui.player.components.expressive

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.ElevationTokens

private val dpExpressiveSpring = spring<Dp>(dampingRatio = 0.9f, stiffness = 700f)

/**
 * Switch dimension tokens following M3 Expressive spec.
 */
object SwitchDimensions {
  val trackWidth: Dp = 52.dp
  val trackHeight: Dp = 32.dp
  val trackRadius: Dp = 16.dp
  val thumbUnchecked: Dp = 16.dp
  val thumbChecked: Dp = 24.dp
  val thumbPressed: Dp = 28.dp
  val iconSize: Dp = 16.dp
  val thumbPadding: Dp = 4.dp
  val borderStroke: Dp = 2.dp
}

/**
 * Expressive Switch with spring-animated thumb morphing.
 */
@Composable
fun ExpressiveSwitch(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  Box(
    modifier = modifier
      .semantics {
        role = Role.Switch
        stateDescription = if (checked) "On" else "Off"
      }
      .toggleable(
        value = checked,
        enabled = enabled,
        onValueChange = onCheckedChange,
        role = Role.Switch,
        indication = null,
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
      ),
    contentAlignment = Alignment.CenterStart,
  ) {
    val trackColor = if (checked) {
      MaterialTheme.colorScheme.primary
    } else {
      MaterialTheme.colorScheme.surfaceContainerHighest
    }

    val thumbSize by animateDpAsState(
      targetValue = if (checked) SwitchDimensions.thumbChecked else SwitchDimensions.thumbUnchecked,
      animationSpec = dpExpressiveSpring,
      label = "ExpressiveSwitchThumbSize",
    )

    val iconScale by animateFloatAsState(
      targetValue = if (checked) 1f else 0.85f,
      animationSpec = spring(dampingRatio = 0.9f, stiffness = 1400f),
      label = "ExpressiveSwitchIconScale",
    )

    val thumbOffsetX by animateDpAsState(
      targetValue = if (checked) {
        SwitchDimensions.trackWidth - SwitchDimensions.thumbPadding - thumbSize
      } else {
        SwitchDimensions.thumbPadding
      },
      animationSpec = dpExpressiveSpring,
      label = "ExpressiveSwitchThumbOffset",
    )

    val density = LocalDensity.current
    val trackRadiusPx = with(density) { SwitchDimensions.trackRadius.toPx() }
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val borderStrokePx = with(density) { SwitchDimensions.borderStroke.toPx() }

    Canvas(
      modifier = Modifier
        .size(SwitchDimensions.trackWidth, SwitchDimensions.trackHeight)
        .clip(MaterialTheme.shapes.extraLarge),
    ) {
      drawRoundRect(
        color = trackColor,
        size = size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackRadiusPx, trackRadiusPx),
        style = Fill,
      )

      if (!checked) {
        drawRoundRect(
          color = borderColor,
          size = size,
          cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackRadiusPx, trackRadiusPx),
          style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = borderStrokePx,
          ),
        )
      }
    }

    Surface(
      modifier = Modifier
        .padding(start = thumbOffsetX)
        .size(thumbSize)
        .scale(iconScale),
      shape = MaterialTheme.shapes.extraLarge,
      color = if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
      shadowElevation = ElevationTokens.Level1,
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.matchParentSize(),
      ) {
        if (checked) {
          Icon(Icons.RoundedFilled.Check, contentDescription = null, modifier = Modifier.size(SwitchDimensions.iconSize))
        } else {
          Icon(Icons.RoundedFilled.Close, contentDescription = null, modifier = Modifier.size(SwitchDimensions.iconSize))
        }
      }
    }
  }
}

/**
 * ExpressiveSwitch with a full-row clickable label.
 */
@Composable
fun ExpressiveSwitchWithLabel(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  label: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  Row(
    modifier = modifier
      .toggleable(
        value = checked,
        enabled = enabled,
        onValueChange = onCheckedChange,
        role = Role.Switch,
      )
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    label()
    Spacer(modifier = Modifier.weight(1f))
    ExpressiveSwitch(
      checked = checked,
      onCheckedChange = {},
      enabled = enabled,
    )
  }
}
