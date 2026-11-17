/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.preferences

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.ProfileHubCard
import app.gyrolet.mpvrx.preferences.ProfileSection
import app.gyrolet.mpvrx.preferences.ProfileStatisticsCard
import app.gyrolet.mpvrx.preferences.preference.Preference
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton
import app.gyrolet.mpvrx.ui.preferences.components.AppListPreference

private enum class ProfileEditorGroup(@StringRes val titleRes: Int) {
  HUBS(R.string.profile_layout_hubs),
  SECTIONS(R.string.profile_layout_sections),
  STATISTICS(R.string.profile_layout_statistics),
}

@Composable
internal fun ProfileLayoutPreferences(preferences: AppearancePreferences, showTitle: Boolean = true) {
  var group by rememberSaveable { mutableStateOf(ProfileEditorGroup.HUBS) }
  val searchTarget by SettingsSearchNavigation.target.collectAsState()
  LaunchedEffect(searchTarget?.requestId) {
    ProfileEditorGroup.entries.firstOrNull { searchTarget?.key == "res:${it.titleRes}" }?.let { group = it }
  }
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (showTitle) PreferenceSectionHeader(
      title = stringResource(R.string.profile_layout_title),
    )
    Text(
      text = stringResource(R.string.profile_layout_summary),
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PreferenceCard {
      ExpressivePreferenceItem(standalone = true) {
        AppListPreference(
          modifier = Modifier.settingsSearchTarget(R.string.profile_layout_title),
          value = group,
          onValueChange = { group = it },
          values = ProfileEditorGroup.entries,
          valueToText = { AnnotatedString(stringResource(it.titleRes)) },
          title = { Text(stringResource(R.string.generic_configure)) },
          summary = { Text(stringResource(group.titleRes)) },
        )
      }
    }
    when (group) {
      ProfileEditorGroup.HUBS -> ProfileLayoutGroup(
        titleRes = group.titleRes,
        choices = ProfileHubCard.entries.map { it.name to it.titleRes },
        orderPreference = preferences.profileHubOrder,
        hiddenPreference = preferences.profileHiddenHubs,
      )
      ProfileEditorGroup.SECTIONS -> ProfileLayoutGroup(
        titleRes = group.titleRes,
        choices = ProfileSection.entries.map { it.name to it.titleRes },
        orderPreference = preferences.profileSectionOrder,
        hiddenPreference = preferences.profileHiddenSections,
      )
      ProfileEditorGroup.STATISTICS -> ProfileLayoutGroup(
        titleRes = group.titleRes,
        choices = ProfileStatisticsCard.entries.map { it.name to it.titleRes },
        orderPreference = preferences.profileStatisticsOrder,
        hiddenPreference = preferences.profileHiddenStatistics,
      )
    }
    AppDialogTextButton(
      modifier = Modifier.padding(horizontal = 16.dp),
      onClick = {
        preferences.profileHubOrder.delete()
        preferences.profileHiddenHubs.delete()
        preferences.profileSectionOrder.delete()
        preferences.profileHiddenSections.delete()
        preferences.profileStatisticsOrder.delete()
        preferences.profileHiddenStatistics.delete()
      },
    ) {
      Text(stringResource(R.string.profile_layout_reset))
    }
  }
}

@Composable
private fun ProfileLayoutGroup(
  @StringRes titleRes: Int,
  choices: List<Pair<String, Int>>,
  orderPreference: Preference<List<String>>,
  hiddenPreference: Preference<Set<String>>,
) {
  val order by orderPreference.collectAsState()
  val hidden by hiddenPreference.collectAsState()
  PreferenceSectionHeader(
    title = stringResource(titleRes),
    modifier = Modifier.settingsSearchTarget(titleRes),
  )
  ExpressivePreferenceGroup {
    ReorderableTabPreferences(
      tabs = order.map { id ->
        ReorderableTabItem(
          id = id,
          title = stringResource(choices.first { it.first == id }.second),
          summary = "",
          enabled = id !in hidden,
        )
      },
      onEnabledChange = { id, enabled ->
        hiddenPreference.set(if (enabled) hidden - id else hidden + id)
      },
      onMove = { id, destination ->
        val moved = order.toMutableList()
        if (moved.remove(id)) {
          moved.add(destination.coerceIn(0, moved.size), id)
          orderPreference.set(moved)
        }
      },
    )
  }
}
