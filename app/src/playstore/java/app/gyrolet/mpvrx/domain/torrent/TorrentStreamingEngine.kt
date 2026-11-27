/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.torrent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Shared playback code can observe an idle engine; this distribution contains no P2P runtime. */
@Suppress("UNUSED_PARAMETER")
class TorrentStreamingEngine(
  context: Context,
) {
  val state: StateFlow<TorrentStreamingState> =
    MutableStateFlow<TorrentStreamingState>(TorrentStreamingState.Idle).asStateFlow()

  suspend fun prepareTorrent(source: String): TorrentCatalog = unavailable()

  suspend fun startStream(request: TorrentStreamRequest): TorrentStreamResult = unavailable()

  suspend fun downloadFile(
    source: String,
    fileIndex: Int,
    destination: File,
    onProgress: suspend (Long, Long, Long) -> Unit,
  ): Long = unavailable()

  fun stopStream() = Unit

  fun discardPreparation(preparationId: String) = Unit

  fun shutdown() = Unit

  private fun unavailable(): Nothing = throw TorrentStreamException("P2P streaming is unavailable in this distribution")
}
