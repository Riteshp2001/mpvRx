/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.theme.animatedConnectedContainerColor
import app.gyrolet.mpvrx.ui.theme.appPressFeedback

/** The app-wide immediate-selection picker, including preference-backed settings. */
@Composable
fun <T> AppSingleChoiceDialog(
  value: T,
  values: List<T>,
  onValueChange: (T) -> Unit,
  onDismissRequest: () -> Unit,
  title: @Composable () -> Unit,
  valueToText: @Composable (T) -> AnnotatedString = { AnnotatedString(it.toString()) },
) {
  val selectedIndex = values.indexOf(value).coerceAtLeast(0)
  val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
  val initialFocus = rememberTvInitialFocusRequester(requestKey = selectedIndex)
  AppAlertDialog(
    onDismissRequest = onDismissRequest,
    title = title,
    confirmButton = {
      AppDialogTextButton(onClick = onDismissRequest) {
        Text(stringResource(R.string.generic_cancel))
      }
    },
    text = {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
      ) {
        itemsIndexed(values) { index, itemValue ->
          val selected = itemValue == value
          val interactionSource = remember { MutableInteractionSource() }
          val pressed by interactionSource.collectIsPressedAsState()
          val shape = rememberConnectedShape(
            index, values.size, ConnectedLayout.Vertical, pressed = pressed, selected = selected,
          )
          Surface(
            modifier = Modifier.appPressFeedback(interactionSource, pressedScale = 0.985f),
            shape = shape,
            color = animatedConnectedContainerColor(
              selected = selected, pressed = pressed,
              containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (index == selectedIndex) Modifier.tvInitialFocus(initialFocus) else Modifier)
                .tvFocusHighlight(shape, clipToShape = false)
                .selectable(
                  selected = selected,
                  role = Role.RadioButton,
                  interactionSource = interactionSource,
                  indication = androidx.compose.material3.ripple(),
                  onClick = {
                    onDismissRequest()
                    onValueChange(itemValue)
                  },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(selected = selected, onClick = null)
              Text(text = valueToText(itemValue), modifier = Modifier.weight(1f))
            }
          }
        }
      }
    },
  )
}
