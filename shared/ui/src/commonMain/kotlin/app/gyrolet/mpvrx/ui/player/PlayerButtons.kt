package app.gyrolet.mpvrx.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.platform.LocalAppDependencies
import app.gyrolet.mpvrx.ui.util.collectAsState

/** Translucent tinted glass used behind every player button, as in the Android controls. */
@Composable
fun playerButtonContainer(): Color {
  val hidden by LocalAppDependencies.current.settings.appearance.hidePlayerButtonsBackground.collectAsState()
  return if (hidden) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
}

@Composable
private fun playerButtonBorder(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

@Composable
fun WithTooltip(
  text: String,
  content: @Composable () -> Unit,
) {
  TooltipBox(
    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
    tooltip = { PlainTooltip { Text(text) } },
    state = rememberTooltipState(),
    content = content,
  )
}

@Composable
fun PlayerIconButton(
  icon: ImageVector,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  size: Dp = 52.dp,
  active: Boolean = false,
  enabled: Boolean = true,
) {
  val container = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f) else playerButtonContainer()
  val tint =
    when {
      !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
      active -> MaterialTheme.colorScheme.onPrimaryContainer
      else -> MaterialTheme.colorScheme.onSurface
    }
  WithTooltip(contentDescription) {
    Box(
      modifier =
        modifier
          .size(size)
          .clip(CircleShape)
          .background(container)
          .border(1.dp, playerButtonBorder(), CircleShape)
          .clickable(enabled = enabled, onClick = onClick),
      contentAlignment = Alignment.Center,
    ) {
      Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
  }
}

@Composable
fun PlayerPill(
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  height: Dp = 52.dp,
  content: @Composable RowScope.() -> Unit,
) {
  Row(
    modifier =
      modifier
        .height(height)
        .clip(RoundedCornerShape(50))
        .background(playerButtonContainer())
        .border(1.dp, playerButtonBorder(), RoundedCornerShape(50))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 18.dp),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
  )
}

@Composable
fun PlayerTextPill(
  text: String,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  leading: ImageVector? = null,
  accent: String? = null,
) {
  PlayerPill(modifier, onClick) {
    if (leading != null) {
      Icon(leading, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
      Spacer(Modifier.width(10.dp))
    }
    if (accent != null) {
      Text(accent, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
      Text("  ·  ", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
  }
}
