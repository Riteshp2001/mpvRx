/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.LocalEmphasizedTypography

/**
 * A card container for grouping related preferences, mimicking modern Android settings UI.
 */
@Composable
fun PreferenceCard(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
    shape = MaterialTheme.shapes.extraLargeIncreased,
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
      ),
    elevation =
      CardDefaults.cardElevation(
        defaultElevation = 0.dp,
      ),
  ) {
    Column(
      modifier = Modifier.padding(vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
      content()
    }
  }
}

/**
 * A divider to separate preferences within a card.
 */
@Composable
fun PreferenceDivider(modifier: Modifier = Modifier) {
  HorizontalDivider(
    modifier = modifier.padding(horizontal = 16.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
  )
}

/**
 * A section header for preferences, displayed outside cards.
 */
@Composable
fun PreferenceSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
) {
  val emphasizedTypography = LocalEmphasizedTypography.current

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 8.dp),
  ) {
    Text(
      text = title,
      style = emphasizedTypography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Row(
      modifier =
        Modifier
          .padding(top = 10.dp)
          .fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        modifier =
          Modifier
            .width(42.dp)
            .height(4.dp),
        color = MaterialTheme.colorScheme.primary,
        shape = MaterialTheme.shapes.extraSmall,
        content = {},
      )
      Spacer(modifier = Modifier.width(8.dp))
      HorizontalDivider(
        modifier = Modifier.weight(1f),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
      )
    }
  }
}
