/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.playerSettingSummary
import app.gyrolet.mpvrx.ui.preferences.playerSettingAvailability
import me.zhanghai.compose.preference.LocalPreferenceTheme
import androidx.compose.ui.semantics.onClick as semanticOnClick

enum class PreferenceAction { Navigate, Dialog, None }

/** Shared preference row, including the availability of an action in the running player. */
@Composable
fun AppPreference(
  title: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  icon: (@Composable () -> Unit)? = null,
  summary: (@Composable () -> Unit)? = null,
  onClick: (() -> Unit)? = null,
  action: PreferenceAction = PreferenceAction.Navigate,
) {
  val availability = playerSettingAvailability(modifier)
  if (!availability.visible) return
  val canClick = enabled && availability.enabled
  val direction = LocalLayoutDirection.current
  val clickLabel = when (action) {
    PreferenceAction.Navigate -> stringResource(R.string.pref_action_open_page)
    PreferenceAction.Dialog -> stringResource(R.string.pref_action_open_dialog)
    PreferenceAction.None -> null
  }
  me.zhanghai.compose.preference.Preference(
    title = title,
    modifier = if (onClick != null) modifier.semantics {
      role = Role.Button
      semanticOnClick(label = clickLabel, action = null)
    } else modifier,
    enabled = canClick,
    icon = icon,
    summary = playerSettingSummary(availability, enabled, summary),
    widgetContainer = if (onClick != null && action != PreferenceAction.None) {
      {
        Icon(
          imageVector = when {
            action == PreferenceAction.Dialog -> Icons.RoundedFilled.ArrowDropDown
            direction == LayoutDirection.Rtl -> Icons.RoundedFilled.ChevronLeft
            else -> Icons.RoundedFilled.ChevronRight
          },
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canClick) 1f else 0.38f),
          modifier = Modifier.padding(horizontal = LocalPreferenceTheme.current.horizontalSpacing).size(20.dp),
        )
      }
    } else null,
    onClick = onClick?.let { click -> { if (canClick) click() } },
  )
}
