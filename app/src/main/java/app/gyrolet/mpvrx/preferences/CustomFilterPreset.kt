/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import org.json.JSONArray
import org.json.JSONObject

/**
 * A user created video filter preset.
 *
 * Holds the same six adjustable values as the built in
 * [app.gyrolet.mpvrx.ui.player.FilterPreset] entries, but is named by the user and is
 * persisted through `DecoderPreferences.customFilterPresets`.
 *
 * A preset list is stored as one JSON array because a preference only round trips a single
 * string. JSON keeps preset names free to contain spaces, punctuation and non-ASCII text,
 * and keeps the stored value readable when a settings backup is inspected by hand.
 */
data class CustomFilterPreset(
  val name: String,
  val brightness: Int,
  val saturation: Int,
  val contrast: Int,
  val gamma: Int,
  val hue: Int,
  val sharpness: Int,
) {
  /** Case insensitive match, so "Night Mode" and "night mode" name the same preset. */
  fun hasName(
    candidate: String,
  ): Boolean = name.equals(candidate, ignoreCase = true)

  companion object {
    /** Kept short enough to stay readable as a chip label next to the built in presets. */
    const val MAX_NAME_LENGTH = 24

    private const val KEY_NAME = "name"
    private const val KEY_BRIGHTNESS = "brightness"
    private const val KEY_SATURATION = "saturation"
    private const val KEY_CONTRAST = "contrast"
    private const val KEY_GAMMA = "gamma"
    private const val KEY_HUE = "hue"
    private const val KEY_SHARPNESS = "sharpness"

    /**
     * Trims and shortens a user supplied name.
     * Returns an empty string when nothing usable is left, which callers treat as a cancel.
     */
    fun normalizeName(
      raw: String,
    ): String = raw.trim().take(MAX_NAME_LENGTH).trim()

    /** Serialises a whole preset list, preserving the order it is displayed in. */
    fun encodeAll(presets: List<CustomFilterPreset>): String {
      val array = JSONArray()
      presets.forEach { preset ->
        array.put(
          JSONObject()
            .put(KEY_NAME, preset.name)
            .put(KEY_BRIGHTNESS, preset.brightness)
            .put(KEY_SATURATION, preset.saturation)
            .put(KEY_CONTRAST, preset.contrast)
            .put(KEY_GAMMA, preset.gamma)
            .put(KEY_HUE, preset.hue)
            .put(KEY_SHARPNESS, preset.sharpness),
        )
      }
      return array.toString()
    }

    /**
     * Parses a stored preset list.
     *
     * Unreadable input and malformed entries are skipped instead of throwing, so a hand
     * edited or partially written preference can never stop the panel from rendering. An
     * empty result is a valid outcome and simply means "no custom presets".
     */
    fun decodeAll(
      stored: String,
    ): List<CustomFilterPreset> {
      if (stored.isBlank()) return emptyList()
      val array = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
      val decoded = mutableListOf<CustomFilterPreset>()
      for (index in 0 until array.length()) {
        decode(array.optJSONObject(index))?.let(decoded::add)
      }
      return decoded.distinctBy { it.name.lowercase() }
    }

    private fun decode(
      entry: JSONObject?,
    ): CustomFilterPreset? {
      if (entry == null) return null
      val name = normalizeName(entry.optString(KEY_NAME))
      if (name.isEmpty()) return null
      return CustomFilterPreset(
        name = name,
        brightness = entry.optInt(KEY_BRIGHTNESS),
        saturation = entry.optInt(KEY_SATURATION),
        contrast = entry.optInt(KEY_CONTRAST),
        gamma = entry.optInt(KEY_GAMMA),
        hue = entry.optInt(KEY_HUE),
        sharpness = entry.optInt(KEY_SHARPNESS),
      )
    }
  }
}
