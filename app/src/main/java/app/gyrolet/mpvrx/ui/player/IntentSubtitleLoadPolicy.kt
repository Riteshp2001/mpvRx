/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
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
    if (hasEnabledSubtitleExtra) {
      return enabledSubtitles.distinct().map { enabledSubtitle ->
        IntentSubtitleLoadEntry(
          value = enabledSubtitle,
          metadataIndex = subtitles.indexOf(enabledSubtitle),
          select = true,
        )
      }
    }

    return subtitles.mapIndexed { index, subtitle ->
      IntentSubtitleLoadEntry(
        value = subtitle,
        metadataIndex = index,
        select = false,
      )
    }
  }
}
