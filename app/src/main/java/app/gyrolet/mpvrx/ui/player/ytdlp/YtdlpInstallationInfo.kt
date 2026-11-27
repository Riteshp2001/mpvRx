/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.ytdlp

enum class YtdlpReleaseChannel {
  STABLE,
  NIGHTLY,
  MASTER,
  CUSTOM,
  UNKNOWN,
  ;

  companion object {
    fun resolve(
      channel: String?,
      origin: String?,
      version: String?,
    ): YtdlpReleaseChannel {
      val normalizedChannel = channel.orEmpty().trim().lowercase()
      val normalizedOrigin = origin.orEmpty().trim().lowercase()
      return when {
        normalizedChannel == "stable" || normalizedOrigin == "yt-dlp/yt-dlp" -> STABLE
        normalizedChannel == "nightly" || normalizedOrigin == "yt-dlp/yt-dlp-nightly-builds" -> NIGHTLY
        normalizedChannel == "master" || normalizedOrigin == "yt-dlp/yt-dlp-master-builds" -> MASTER
        normalizedChannel.isNotEmpty() || normalizedOrigin.isNotEmpty() -> CUSTOM
        version.orEmpty().count { character -> character == '.' } >= 3 -> NIGHTLY
        !version.isNullOrBlank() -> STABLE
        else -> UNKNOWN
      }
    }
  }
}

data class YtdlpInstallationInfo(
  val isInstalled: Boolean,
  val version: String? = null,
  val channel: YtdlpReleaseChannel = YtdlpReleaseChannel.UNKNOWN,
  val commitHash: String? = null,
  val origin: String? = null,
  val variant: String? = null,
) {
  val shortCommitHash: String?
    get() = commitHash?.take(8)

  companion object {
    val NotInstalled = YtdlpInstallationInfo(isInstalled = false)
  }
}
