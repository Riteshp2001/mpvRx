/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.presentation.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.theme.animatedConnectedContainerColor
import app.gyrolet.mpvrx.ui.theme.appPressFeedback

/** Borderless information row using the same fill and connected geometry as Selected items. */
@Composable
internal fun ConnectedInfoSurface(
  index: Int,
  itemCount: Int,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  val shape = rememberConnectedShape(index, itemCount, ConnectedLayout.Vertical, pressed = pressed && onClick != null)
  if (onClick == null) {
    Surface(
      modifier = modifier.fillMaxWidth(),
      shape = shape,
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      contentColor = MaterialTheme.colorScheme.onSurface,
      content = content,
    )
  } else {
    Surface(
      onClick = onClick,
      modifier = modifier.fillMaxWidth().appPressFeedback(interactionSource, pressedScale = 0.985f),
      shape = shape,
      color = animatedConnectedContainerColor(
        selected = false, pressed = pressed, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
      ),
      interactionSource = interactionSource,
      contentColor = MaterialTheme.colorScheme.onSurface,
      content = content,
    )
  }
}

/** Shared quick-stat layout for single-media and selection information. */
@Composable
internal fun ConnectedInfoStatCard(
  title: String,
  value: String,
  icon: AppIcon,
  accentColor: Color,
  index: Int,
  itemCount: Int,
  columns: Int,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  Surface(
    onClick = onClick,
    modifier = modifier.appPressFeedback(interactionSource, pressedScale = 0.985f),
    shape = rememberConnectedShape(
      index = index,
      itemCount = itemCount,
      layout = if (columns == 1) ConnectedLayout.Vertical else ConnectedLayout.Grid,
      columns = columns,
      pressed = pressed,
    ),
    color = animatedConnectedContainerColor(
      selected = false, pressed = pressed, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ),
    interactionSource = interactionSource,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    Row(
      modifier = Modifier.padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = accentColor.copy(alpha = 0.12f),
        contentColor = accentColor,
        modifier = Modifier.size(40.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = value,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
