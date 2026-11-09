/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.appDialogEnter
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape

internal enum class DialogActionEmphasis { None, Primary, Secondary }
internal val LocalDialogActionEmphasis = staticCompositionLocalOf { DialogActionEmphasis.None }

/** Shared expressive shell; callers retain their dismissal, input, focus and action behavior. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAlertDialog(
  onDismissRequest: () -> Unit,
  confirmButton: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  dismissButton: (@Composable () -> Unit)? = null,
  icon: (@Composable () -> Unit)? = null,
  title: (@Composable () -> Unit)? = null,
  text: (@Composable () -> Unit)? = null,
  shape: Shape = MaterialTheme.shapes.extraLargeIncreased,
  containerColor: Color = AlertDialogDefaults.containerColor,
  iconContentColor: Color = AlertDialogDefaults.iconContentColor,
  titleContentColor: Color = AlertDialogDefaults.titleContentColor,
  textContentColor: Color = AlertDialogDefaults.textContentColor,
  tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
  properties: DialogProperties = DialogProperties(),
  singleRowActions: Boolean = false,
) {
  val colors = MaterialTheme.colorScheme
  val maximumHeight = (LocalConfiguration.current.screenHeightDp.dp - 48.dp).coerceAtLeast(120.dp)
  val hasHeader = title != null || icon != null
  val sections = (if (hasHeader) 1 else 0) + (if (text != null) 1 else 0) + 1
  val bodyIndex = if (hasHeader) 1 else 0
  BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier, properties = properties) {
    Surface(
      modifier = Modifier.appDialogEnter(),
      shape = shape,
      color = containerColor,
      border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.45f)),
      tonalElevation = tonalElevation,
    ) {
      Column(
        modifier = Modifier.widthIn(min = 280.dp, max = 560.dp).heightIn(max = maximumHeight).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
      ) {
        if (hasHeader) {
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = rememberConnectedShape(0, sections, ConnectedLayout.Vertical),
            color = containerColor,
            contentColor = titleContentColor,
          ) {
            Row(
              modifier = Modifier.padding(20.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (icon != null) {
                Surface(shape = MaterialTheme.shapes.largeIncreased, color = Color.Transparent, contentColor = iconContentColor) {
                  Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { icon() }
                }
              }
              if (title != null) {
                Box(Modifier.weight(1f).semantics { heading() }) {
                  ProvideTextStyle(MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)) { title() }
                }
              }
            }
          }
        }
        if (text != null) {
          Surface(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            shape = rememberConnectedShape(bodyIndex, sections, ConnectedLayout.Vertical),
            color = containerColor,
            contentColor = textContentColor,
          ) {
            Box(Modifier.padding(20.dp)) {
              ProvideTextStyle(MaterialTheme.typography.bodyLarge) { text() }
            }
          }
        }
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = rememberConnectedShape(sections - 1, sections, ConnectedLayout.Vertical),
          color = containerColor,
        ) {
          if (singleRowActions) {
            // Keep Cancel beside split confirmations while their status label changes width.
            Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (dismissButton != null) {
                CompositionLocalProvider(LocalDialogActionEmphasis provides DialogActionEmphasis.Secondary) { dismissButton() }
              }
              // Wrap the confirmation so unused width does not become a gap after Cancel.
              Box(Modifier.weight(1f, fill = false), contentAlignment = Alignment.CenterEnd) {
                CompositionLocalProvider(LocalDialogActionEmphasis provides DialogActionEmphasis.Primary) { confirmButton() }
              }
            }
          } else {
            FlowRow(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              if (dismissButton != null) {
                CompositionLocalProvider(LocalDialogActionEmphasis provides DialogActionEmphasis.Secondary) { dismissButton() }
              }
              CompositionLocalProvider(LocalDialogActionEmphasis provides DialogActionEmphasis.Primary) { confirmButton() }
            }
          }
        }
      }
    }
  }
}
