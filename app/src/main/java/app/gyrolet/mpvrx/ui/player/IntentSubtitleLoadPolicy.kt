/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

internal data class IntentSubtitleLoadEntry<T>(
  val value: T,
  val metadataIndex: Int,
  val select: Boolean,
)

internal object IntentSubtitleLoadPolicy {
  fun <T> entriesToLoad(
    subtitles: List<T>,
    enabledSubtitles: List<T>,
    hasEnabledSubtitleExtra: Boolean,
  ): List<IntentSubtitleLoadEntry<T>> {
    val enabledSet = enabledSubtitles.toSet()
    val loaded = mutableListOf<IntentSubtitleLoadEntry<T>>()
    val seen = mutableSetOf<T>()

    for ((index, subtitle) in subtitles.withIndex()) {
      if (seen.add(subtitle)) {
        val isSelected = hasEnabledSubtitleExtra && enabledSet.contains(subtitle)
        loaded.add(
          IntentSubtitleLoadEntry(
            value = subtitle,
            metadataIndex = index,
            select = isSelected,
          ),
        )
      }
    }

    if (hasEnabledSubtitleExtra) {
      for (enabledSubtitle in enabledSubtitles) {
        if (seen.add(enabledSubtitle)) {
          loaded.add(
            IntentSubtitleLoadEntry(
              value = enabledSubtitle,
              metadataIndex = subtitles.indexOf(enabledSubtitle).takeIf { it >= 0 } ?: -1,
              select = true,
            ),
          )
        }
      }
    }

    return loaded
  }
}
