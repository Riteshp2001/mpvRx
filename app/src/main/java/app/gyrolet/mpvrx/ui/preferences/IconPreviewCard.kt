// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.connectedShape

@Composable
internal fun IconPreviewCard(content: @Composable ColumnScope.() -> Unit) {
  val colors = MaterialTheme.colorScheme
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(connectedShape(0, 1, ConnectedLayout.Vertical))
        .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.tertiaryContainer)))
        .padding(horizontal = 24.dp, vertical = 28.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(
      stringResource(R.string.app_icon_preview),
      style = MaterialTheme.typography.labelLarge,
      color = colors.onSurfaceVariant,
      modifier =
        Modifier
          .background(colors.surface.copy(alpha = 0.92f), RoundedCornerShape(20.dp))
          .padding(horizontal = 12.dp, vertical = 6.dp),
    )
    content()
  }
}
