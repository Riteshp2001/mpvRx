/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.getEnum

enum class MusicSourceProvider(val id: String) {
  LOCAL("local"),
  JELLYFIN("jellyfin");

  companion object {
    fun fromId(id: String): MusicSourceProvider =
      entries.firstOrNull { it.id == id } ?: LOCAL
  }
}

class MediaServerPreferences(
  preferenceStore: PreferenceStore,
) {
  val musicSourceProvider =
    preferenceStore.getEnum("media_server_music_source_provider", MusicSourceProvider.LOCAL)
}
