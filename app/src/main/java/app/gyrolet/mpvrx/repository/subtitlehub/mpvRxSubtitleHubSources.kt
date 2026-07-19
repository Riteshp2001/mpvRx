package app.gyrolet.mpvrx.repository.subtitlehub

object mpvRxSubtitleHubSources {
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
