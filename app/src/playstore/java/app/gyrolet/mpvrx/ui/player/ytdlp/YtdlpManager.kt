/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.ytdlp

import android.content.Context
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Compile-time adapter with no executable download, installation, update or extraction code. */
@Suppress("UNUSED_PARAMETER")
object YtdlpManager {
  val installationInfo: StateFlow<YtdlpInstallationInfo?> =
    MutableStateFlow<YtdlpInstallationInfo?>(YtdlpInstallationInfo.NotInstalled).asStateFlow()

  fun getYtdlDir(context: Context): File = File(context.filesDir, "ytdl")

  fun getExecutablePath(context: Context): String =
    throw UnsupportedOperationException("Web extraction is unavailable in this distribution")

  suspend fun refreshInstallationInfo(context: Context): YtdlpInstallationInfo = YtdlpInstallationInfo.NotInstalled

  fun requiresYtdlp(source: String): Boolean = false

  fun isPotentialPlaylistUrl(source: String): Boolean = false

  fun canonicalPlaylistSource(source: String): String = source

  fun playPlaylist(
    context: Context,
    playlist: YtdlpPlaylistMetadata,
    launchSource: String,
  ) = Unit

  suspend fun extractPlaylist(
    context: Context,
    source: String,
    preferences: YtdlPreferences,
    userAgentOverride: String? = null,
    onLog: (String) -> Unit = {},
  ): Result<YtdlpPlaylistMetadata> =
    Result.failure(UnsupportedOperationException("Web extraction is unavailable in this distribution"))

  // Direct URLs, HLS and DASH remain playable through libmpv without an extractor.
  suspend fun prepareForPlayback(
    context: Context,
    source: String,
    onLog: (String) -> Unit = {},
  ): Boolean = true

  suspend fun ensureRuntimeInstalled(
    context: Context,
    onLog: (String) -> Unit = {},
  ): Boolean = false

  suspend fun copyAssets(context: Context) = Unit

  fun configureMpvHookBeforeInit(context: Context) {
    PlaybackSession.setIntegrationOptionString("ytdl", "no")
  }

  fun setupMpvOptions(
    context: Context,
    ytdlPreferences: YtdlPreferences,
    subtitlesPreferences: SubtitlesPreferences,
  ) = Unit

  suspend fun runInstall(
    context: Context,
    onLog: (String) -> Unit,
  ): Boolean = false

  suspend fun runUpdate(
    context: Context,
    onLog: (String) -> Unit,
  ): Boolean = false

  suspend fun runUpdateToNightly(
    context: Context,
    onLog: (String) -> Unit,
  ): Boolean = false

  fun isInstalled(context: Context): Boolean = false
}
