package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
fun <T> OptionsDialog(
  title: String,
  options: List<T>,
  selectedOption: T,
  onOptionSelected: (T) -> Unit,
  onDismiss: () -> Unit,
  optionLabel: @Composable (T) -> String,
) {
  val configuration = LocalConfiguration.current

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
      )
    },
    text = {
      Column {
        HorizontalDivider()
        LazyColumn(
          contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
          modifier = Modifier.selectableGroup(),
        ) {
          items(options) { option ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable { onOptionSelected(option) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(
                selected = option == selectedOption,
                onClick = { onOptionSelected(option) },
              )
              Text(
                text = optionLabel(option),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 16.dp),
              )
            }
          }
        }
        HorizontalDivider()
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.generic_cancel))
      }
    },
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    tonalElevation = 6.dp,
    shape = MaterialTheme.shapes.extraLarge,
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      dismissOnBackPress = true,
      dismissOnClickOutside = true,
    ),
  )
}
