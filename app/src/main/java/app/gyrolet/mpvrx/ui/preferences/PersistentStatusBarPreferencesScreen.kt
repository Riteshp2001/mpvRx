/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.PersistentStatusPosition
import app.gyrolet.mpvrx.preferences.PersistentStatusSlot
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.player.controls.PersistentPlaybackProgressLine
import app.gyrolet.mpvrx.ui.player.controls.PersistentStatusHudRow
import app.gyrolet.mpvrx.ui.player.controls.rememberHudClock
import app.gyrolet.mpvrx.ui.tv.isTelevision
import app.gyrolet.mpvrx.ui.tv.tvContentFocus
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import app.gyrolet.mpvrx.ui.preferences.components.AppListPreference as ListPreference
import org.koin.compose.koinInject

/**
 * Full-featured HUD configuration, intentionally placed under Player Controls / Layout.
 * Connected groups and dialogs use the same UI primitives as the remaining settings.
 */
@Serializable
object PersistentStatusBarPreferencesScreen : Screen {
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val prefs = koinInject<PlayerPreferences>()
    val resources = LocalResources.current
    val enabled by prefs.persistentStatusEnabled.collectAsState()
    val position by prefs.persistentStatusPosition.collectAsState()
    val left by prefs.persistentStatusLeftSlot.collectAsState()
    val center by prefs.persistentStatusCenterSlot.collectAsState()
    val right by prefs.persistentStatusRightSlot.collectAsState()
    val preferFinishTime by prefs.persistentStatusPreferFinishTime.collectAsState()
    val showSpeed by prefs.persistentStatusShowSpeed.collectAsState()
    val microBattery by prefs.persistentStatusMicroBattery.collectAsState()
    val showProgressLine by prefs.persistentStatusProgressLine.collectAsState()
    val clockFormat by prefs.clockFormat.collectAsState()
    val now = rememberHudClock()
    val embeddedInPlayer = LocalPlayerSettingsPanel.current

    Scaffold(
      contentWindowInsets = settingsContentWindowInsets(),
      topBar = {
        SettingsTopAppBar(
          title = stringResource(R.string.pref_persistent_status_title),
          onBack = { backstack.popSafely() },
        )
      },
    ) { padding ->
      ProvideExpressivePreferenceLocals {
        LazyColumn(
          modifier = Modifier.fillMaxSize().padding(padding).tvContentFocus(),
        ) {
          if (!embeddedInPlayer) item {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
              Box(
                modifier = Modifier.fillMaxWidth()
                  .height(172.dp)
                  .background(Color(0xFF101216), RoundedCornerShape(26.dp)),
              ) {
                Column(
                  modifier = Modifier.align(Alignment.Center),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Text("▶", style = MaterialTheme.typography.headlineMedium, color = Color.White.copy(alpha = 0.3f))
                  Text(
                    stringResource(R.string.pref_persistent_status_preview),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f),
                  )
                }
                PersistentStatusHudRow(
                  leftSlot = left, centerSlot = center, rightSlot = right,
                  preferFinishTime = preferFinishTime, showSpeed = showSpeed,
                  microBattery = microBattery, positionSeconds = 1200f, durationSeconds = 3600f,
                  playbackSpeed = 1.5f, batteryLevel = 88, nowMillis = now, clockFormat = clockFormat,
                  modifier = Modifier.align(
                    if (position == PersistentStatusPosition.Top) Alignment.TopCenter
                    else Alignment.BottomCenter,
                  ),
                )
                if (showProgressLine) {
                  PersistentPlaybackProgressLine(
                    positionSeconds = 1200f,
                    durationSeconds = 3600f,
                    modifier = Modifier.align(Alignment.BottomCenter),
                  )
                }
              }
            }
          }

          item {
            PreferenceSectionHeader(
              title = stringResource(R.string.pref_persistent_status_controls),
              position = PreferenceSectionPosition.First,
            )
          }
          item {
            PreferenceCard {
              SwitchPreference(
                value = enabled,
                onValueChange = prefs.persistentStatusEnabled::set,
                title = { Text(stringResource(R.string.pref_persistent_status_enable)) },
                summary = { Text(stringResource(R.string.pref_persistent_status_enable_summary)) },
              )
              PreferenceDivider()
              SwitchPreference(
                value = showProgressLine,
                onValueChange = prefs.persistentStatusProgressLine::set,
                modifier = Modifier.settingsSearchTarget(R.string.pref_persistent_status_progress_line),
                title = { Text(stringResource(R.string.pref_persistent_status_progress_line)) },
              )
            }
          }
          item {
            PreferenceSectionHeader(
              title = stringResource(R.string.pref_persistent_status_position_section),
              position = PreferenceSectionPosition.Middle,
            )
          }
          item {
            PreferenceCard {
              ExpressivePreferenceItem {
                ListPreference(
                  value = position,
                  onValueChange = prefs.persistentStatusPosition::set,
                  values = PersistentStatusPosition.entries,
                  valueToText = { AnnotatedString(resources.getString(positionLabel(it))) },
                  title = { Text(stringResource(R.string.pref_persistent_status_position)) },
                  summary = {
                    Text(resources.getString(positionLabel(position)),
                      color = MaterialTheme.colorScheme.onSurfaceVariant)
                  },
                )
              }
            }
          }
          item {
            PreferenceSectionHeader(
              title = stringResource(R.string.pref_persistent_status_display),
              position = PreferenceSectionPosition.Middle,
            )
          }
          item {
            PreferenceCard {
              StatusSlotPreference(
                label = stringResource(R.string.pref_persistent_status_left),
                value = left,
                onValueChange = prefs.persistentStatusLeftSlot::set,
              )
              PreferenceDivider()
              StatusSlotPreference(
                label = stringResource(R.string.pref_persistent_status_center),
                value = center,
                onValueChange = prefs.persistentStatusCenterSlot::set,
              )
              PreferenceDivider()
              StatusSlotPreference(
                label = stringResource(R.string.pref_persistent_status_right),
                value = right,
                onValueChange = prefs.persistentStatusRightSlot::set,
              )
            }
          }
          item {
            PreferenceSectionHeader(
              title = stringResource(R.string.pref_persistent_status_extras),
              position = PreferenceSectionPosition.Last,
            )
          }
          item {
            PreferenceCard {
              SwitchPreference(
                value = preferFinishTime,
                onValueChange = prefs.persistentStatusPreferFinishTime::set,
                title = { Text(stringResource(R.string.pref_persistent_status_finish_time)) },
                summary = { Text(stringResource(R.string.pref_persistent_status_finish_summary)) },
              )
              PreferenceDivider()
              SwitchPreference(
                value = showSpeed,
                onValueChange = prefs.persistentStatusShowSpeed::set,
                title = { Text(stringResource(R.string.pref_persistent_status_speed)) },
              )
              DevicePreference(R.string.pref_persistent_status_micro_battery) {
                PreferenceDivider()
                SwitchPreference(
                  value = microBattery,
                  onValueChange = prefs.persistentStatusMicroBattery::set,
                  title = { Text(stringResource(R.string.pref_persistent_status_micro_battery)) },
                )
              }
            }
          }
          item { Box(Modifier.height(24.dp)) }
        }
      }
    }
  }
}

private fun positionLabel(value: PersistentStatusPosition): Int = when (value) {
  PersistentStatusPosition.Top -> R.string.pref_persistent_status_top
  PersistentStatusPosition.Bottom -> R.string.pref_persistent_status_bottom
}

private fun slotLabel(value: PersistentStatusSlot): Int = when (value) {
  PersistentStatusSlot.None -> R.string.pref_persistent_status_none
  PersistentStatusSlot.PlaybackProgress -> R.string.pref_persistent_status_progress
  PersistentStatusSlot.CurrentTime -> R.string.pref_persistent_status_clock
  PersistentStatusSlot.BatteryStatus -> R.string.pref_persistent_status_battery
}

@Composable
private fun StatusSlotPreference(
  label: String,
  value: PersistentStatusSlot,
  onValueChange: (PersistentStatusSlot) -> Unit,
) {
  val resources = LocalResources.current
  val television = isTelevision()
  val slots = PersistentStatusSlot.entries.filter { !television || it != PersistentStatusSlot.BatteryStatus }
  val displayedValue = value.takeIf { it in slots } ?: PersistentStatusSlot.None
  ExpressivePreferenceItem {
    ListPreference(
      value = displayedValue,
      onValueChange = onValueChange,
      values = slots,
      valueToText = { AnnotatedString(resources.getString(slotLabel(it))) },
      title = { Text(label, fontWeight = FontWeight.SemiBold) },
      summary = {
        Text(resources.getString(slotLabel(displayedValue)), color = MaterialTheme.colorScheme.onSurfaceVariant)
      },
    )
  }
}
