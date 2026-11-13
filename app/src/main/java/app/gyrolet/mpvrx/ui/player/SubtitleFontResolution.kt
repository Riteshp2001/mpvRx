/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player

import app.gyrolet.mpvrx.preferences.DEFAULT_SUBTITLE_FONT_FAMILY
import app.gyrolet.mpvrx.preferences.LEGACY_DEFAULT_SUBTITLE_FONT_FAMILY
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences

/**
 * Resolution order for the mpv subtitle font family.
 *
 * Primary and secondary subtitles share one family: official mpv has no
 * `secondary-sub-font`, secondary inherits `sub-font`. Blank, mpv's generic
 * `sans-serif`, and the old bundled Google Sans value all resolve to
 * `sans-serif`, which mpvlib's fonts.conf maps to the system Roboto, with Noto
 * Sans covering other scripts.
 */
fun resolveSubtitleFontFamily(explicitFont: String): String =
  explicitFont.takeUnless {
    it.isBlank() ||
      it == DEFAULT_SUBTITLE_FONT_FAMILY ||
      it == LEGACY_DEFAULT_SUBTITLE_FONT_FAMILY
  } ?: DEFAULT_SUBTITLE_FONT_FAMILY

fun resolveSubtitleFontFamily(subtitlesPreferences: SubtitlesPreferences): String =
  resolveSubtitleFontFamily(subtitlesPreferences.font.get())

/**
 * Display text for the "Default" subtitle font row.
 */
fun defaultSubtitleFontDisplayName(@Suppress("UNUSED_PARAMETER") effectiveFamily: String): String = "Default"
