/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.preferences

import androidx.annotation.StringRes
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.preference.Preference
import app.gyrolet.mpvrx.preferences.preference.PreferenceStore

internal enum class ProfileHubCard(@StringRes val titleRes: Int) {
  RECENTS(R.string.ui_recents),
  PLAYLISTS(R.string.ui_playlists),
  SNAPSHOTS(R.string.ui_snapshots),
  NETWORK(R.string.ui_network),
}

internal enum class ProfileSection(@StringRes val titleRes: Int) {
  RECENTS(R.string.pref_advanced_enable_recently_played_title),
  PLAYLISTS(R.string.ui_playlists),
  SNAPSHOTS(R.string.ui_snapshots),
  STATISTICS(R.string.watch_stats_title),
}

internal enum class ProfileStatisticsCard(@StringRes val titleRes: Int) {
  TOTAL_TIME(R.string.watch_stats_total_time),
  MEDIA_BREAKDOWN(R.string.watch_stats_media_breakdown),
  LAST_SEVEN_DAYS(R.string.watch_stats_last_seven_days),
  MOST_PLAYED(R.string.watch_stats_top_media),
}

internal fun PreferenceStore.profileLayoutOrder(key: String, defaults: List<String>): Preference<List<String>> =
  getObject(
    key = key,
    defaultValue = defaults,
    serializer = { it.joinToString(",") },
    deserializer = { stored ->
      val known = stored.split(",").map(String::trim).filter { it in defaults }.distinct()
      known + defaults.filterNot { it in known }
    },
  )

internal fun <T : Enum<T>> visibleProfileItems(
  entries: List<T>,
  order: List<String>,
  hidden: Set<String>,
): List<T> =
  order.filterNot { it in hidden }.mapNotNull { name -> entries.firstOrNull { it.name == name } }
