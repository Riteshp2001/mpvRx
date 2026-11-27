/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import androidx.annotation.StringRes
import app.gyrolet.mpvrx.R

/** Kodi engine: tone mapping of Kodi's OpenGL renderer (Kodi's ETONEMAPMETHOD). */
enum class KodiToneMapping(
  @StringRes val titleRes: Int,
  val kodiValue: Int,
) {
  Off(R.string.pref_kodi_tone_mapping_off, 0),
  Reinhard(R.string.pref_kodi_tone_mapping_reinhard, 1),
  Aces(R.string.pref_kodi_tone_mapping_aces, 2),
  Hable(R.string.pref_kodi_tone_mapping_hable, 3),
}
