package app.gyrolet.mpvrx.ui.player.controls.components.panels

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.player.HdrScreenMode
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.theme.spacing

@Composable
fun HdrScreenOutputPanel(
  viewModel: PlayerViewModel,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val mode by viewModel.hdrScreenMode.collectAsState()
  val pipelineReady by viewModel.isHdrScreenOutputPipelineReady.collectAsState()

  DraggablePanel(
    modifier = modifier,
    header = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = MaterialTheme.spacing.medium)
          .padding(top = MaterialTheme.spacing.small),
      ) {
        Text(
          text = "HDR Output",
          style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onDismissRequest) {
          Icon(Icons.Default.Close, null, modifier = Modifier.size(32.dp))
        }
      }
    },
  ) {
    Column(
      modifier = Modifier.padding(MaterialTheme.spacing.medium),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
      if (!pipelineReady) {
        HdrPipelineUnavailableStatus()
      }
      // OFF is the default state controlled by the HDR toggle button, not shown here.
      // Only the four selectable HDR modes are presented in the panel.
      HdrScreenMode.selectableModes.forEach { option ->
        HdrModeOption(
          mode = option,
          selected = mode == option,
          enabled = pipelineReady,
          onClick = { viewModel.setHdrScreenMode(option) },
        )
      }
    }
  }
}

@Composable
private fun HdrPipelineUnavailableStatus(
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val containerColor = colors.errorContainer.copy(alpha = 0.72f)
  val contentColor = colors.onErrorContainer

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = containerColor,
    contentColor = contentColor,
    border = BorderStroke(1.dp, contentColor.copy(alpha = 0.16f)),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier = Modifier
          .size(38.dp)
          .clip(CircleShape)
          .background(contentColor.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Default.HdrOff,
          contentDescription = null,
          modifier = Modifier.size(22.dp),
          tint = contentColor,
        )
      }
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = "HDR cannot be enabled",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = "Enable GPU Next and Vulkan before using HDR modes",
          style = MaterialTheme.typography.bodySmall,
          color = contentColor.copy(alpha = 0.78f),
        )
      }
    }
  }
}

@Composable
private fun HdrModeOption(
  mode: HdrScreenMode,
  selected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val containerColor = when {
    selected -> colors.primaryContainer.copy(alpha = 0.78f)
    else -> colors.surfaceContainerHigh.copy(alpha = 0.9f)
  }
  val contentColor = when {
    !enabled -> colors.onSurface.copy(alpha = 0.38f)
    selected -> colors.onPrimaryContainer
    else -> colors.onSurface
  }
  val borderColor = when {
    selected -> colors.primary.copy(alpha = 0.45f)
    else -> colors.outlineVariant.copy(alpha = 0.28f)
  }

  Surface(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(18.dp))
      .clickable(enabled = enabled, onClick = onClick),
    shape = RoundedCornerShape(18.dp),
    color = containerColor,
    contentColor = contentColor,
    border = BorderStroke(1.dp, borderColor),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier = Modifier
          .size(42.dp)
          .clip(CircleShape)
          .background(
            if (selected) colors.primary.copy(alpha = 0.14f)
            else colors.surfaceVariant.copy(alpha = 0.86f),
          ),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = if (mode == HdrScreenMode.OFF) Icons.Default.HdrOff else Icons.Default.HdrOn,
          contentDescription = null,
          modifier = Modifier.size(24.dp),
          tint =
            if (selected) colors.primary
            else contentColor.copy(alpha = 0.78f),
        )
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = mode.title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = mode.description,
          style = MaterialTheme.typography.bodySmall,
          color = contentColor.copy(alpha = 0.72f),
        )
      }

      RadioButton(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
      )
    }
  }
}
