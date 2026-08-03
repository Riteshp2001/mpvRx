/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitlehub

object MpvRxSubtitleHubSources {
  val ALL =
    linkedMapOf(
      "all" to "All verified SubtitleHub sources",
      "subdl_com" to "SubDL.com",
      "subtitlecat_com" to "SubtitleCat",
      "moviesubtitles_org" to "MovieSubtitles.org",
      "moviesubtitlesrt_com" to "MovieSubtitlesRT",
      "my_subs_co" to "My Subs",
      "tvsubtitles_net" to "TVSubtitles",
    )

  val DEFAULT = setOf("all")

  val ANDROID_SUPPORTED =
    setOf(
      "subdl_com",
      "subtitlecat_com",
      "moviesubtitles_org",
      "moviesubtitlesrt_com",
      "my_subs_co",
      "tvsubtitles_net",
    )

  fun resolveSelected(selected: Set<String>): Set<String> =
    if (selected.isEmpty() || selected.contains("all")) {
      ANDROID_SUPPORTED
    } else {
      selected.intersect(ANDROID_SUPPORTED)
    }
}
