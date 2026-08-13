/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

enum class RepeatMode {
  OFF,
  ONE,
  ALL,
}

enum class PlaybackPhase {
  UNINITIALIZED,
  INITIALIZING,
  IDLE,
  LOADING,
  READY,
  BACKGROUND,
  STOPPING,
  ERROR,
}

/** Metadata needed to reopen a saved network item without putting credentials in a URI. */
data class NetworkPlaybackSource(
  val connectionId: Long,
  val relativePath: String,
)

/**
 * One self-contained queue item. Keeping these values together prevents URI/title/network path
 * lists from drifting out of alignment when a queue is moved or played in the background.
 */
data class PlaybackItem(
  val stableId: String,
  val originalUri: String,
  val playableUri: String = originalUri,
  val title: String? = null,
  val mimeType: String? = null,
  val headers: Map<String, String> = NetworkStreamHeaderPolicy.withHttpDefaults(originalUri),
  val networkSource: NetworkPlaybackSource? = null,
  val playlistItemId: Int? = null,
  val artworkUri: String? = null,
) {
  companion object {
    fun fromUri(
      uri: String,
      playableUri: String = uri,
      title: String? = null,
      mimeType: String? = null,
      headers: Map<String, String> = emptyMap(),
      networkSource: NetworkPlaybackSource? = null,
      playlistItemId: Int? = null,
      artworkUri: String? = null,
    ): PlaybackItem =
      PlaybackItem(
        stableId =
          networkSource?.let { PlaybackIdentity.forNetwork(it.connectionId, it.relativePath) }
            ?: PlaybackIdentity.forUri(uri),
        originalUri = uri,
        playableUri = playableUri,
        title = title,
        mimeType = mimeType,
        headers = NetworkStreamHeaderPolicy.withHttpDefaults(uri, headers),
        networkSource = networkSource,
        playlistItemId = playlistItemId,
        artworkUri = artworkUri,
      )
  }
}

data class PlaybackQueueState(
  val items: List<PlaybackItem> = emptyList(),
  val currentIndex: Int = -1,
  val isExplicitQueue: Boolean = false,
  val isM3u: Boolean = false,
  val repeatMode: RepeatMode = RepeatMode.OFF,
  val shuffleEnabled: Boolean = false,
  val shuffleOrder: List<Int> = emptyList(),
  val shufflePosition: Int = -1,
) {
  val currentItem: PlaybackItem?
    get() = items.getOrNull(currentIndex)

  val hasItems: Boolean
    get() = items.isNotEmpty()
}

internal object PlaybackQueueReducer {
  fun replace(
    previous: PlaybackQueueState,
    items: List<PlaybackItem>,
    requestedIndex: Int,
    isExplicitQueue: Boolean,
    isM3u: Boolean,
  ): PlaybackQueueState {
    if (items.isEmpty()) {
      return previous.copy(
        items = emptyList(),
        currentIndex = -1,
        isExplicitQueue = false,
        isM3u = false,
        shuffleOrder = emptyList(),
        shufflePosition = -1,
      )
    }

    val index = requestedIndex.coerceIn(items.indices)
    return rebuildShuffle(
      previous.copy(
        items = items.toList(),
        currentIndex = index,
        isExplicitQueue = isExplicitQueue,
        isM3u = isM3u,
      ),
    )
  }

  fun select(
    previous: PlaybackQueueState,
    index: Int,
  ): PlaybackQueueState? {
    if (index !in previous.items.indices) return null
    val selected = previous.copy(currentIndex = index)
    if (!selected.shuffleEnabled) return selected

    val existingPosition = selected.shuffleOrder.indexOf(index)
    return if (existingPosition >= 0) {
      selected.copy(shufflePosition = existingPosition)
    } else {
      rebuildShuffle(selected)
    }
  }

  fun move(
    previous: PlaybackQueueState,
    from: Int,
    to: Int,
  ): PlaybackQueueState? {
    if (from !in previous.items.indices || to !in previous.items.indices) return null
    if (from == to) return previous

    val reordered = previous.items.toMutableList()
    reordered.add(to, reordered.removeAt(from))
    val newCurrentIndex =
      when {
        from == previous.currentIndex -> to
        from < previous.currentIndex && to >= previous.currentIndex -> previous.currentIndex - 1
        from > previous.currentIndex && to <= previous.currentIndex -> previous.currentIndex + 1
        else -> previous.currentIndex
      }

    return rebuildShuffle(previous.copy(items = reordered, currentIndex = newCurrentIndex))
  }

  fun setRepeatMode(
    previous: PlaybackQueueState,
    repeatMode: RepeatMode,
  ): PlaybackQueueState = previous.copy(repeatMode = repeatMode)

  fun setShuffleEnabled(
    previous: PlaybackQueueState,
    enabled: Boolean,
  ): PlaybackQueueState =
    if (previous.shuffleEnabled == enabled) {
      previous
    } else if (enabled) {
      rebuildShuffle(previous.copy(shuffleEnabled = true))
    } else {
      previous.copy(
        shuffleEnabled = false,
        shuffleOrder = emptyList(),
        shufflePosition = -1,
      )
    }

  fun next(previous: PlaybackQueueState): PlaybackQueueState? = advance(previous, forward = true)

  fun previous(previous: PlaybackQueueState): PlaybackQueueState? = advance(previous, forward = false)

  fun hasNext(previous: PlaybackQueueState): Boolean = peek(previous, forward = true) != null

  fun hasPrevious(previous: PlaybackQueueState): Boolean = peek(previous, forward = false) != null

  private fun advance(
    previous: PlaybackQueueState,
    forward: Boolean,
  ): PlaybackQueueState? {
    if (previous.items.isEmpty()) return null

    val prepared = if (previous.shuffleEnabled && previous.shuffleOrder.size != previous.items.size) {
      rebuildShuffle(previous)
    } else {
      previous
    }

    val nextIndex = peekIndex(prepared, forward) ?: return null
    return if (prepared.shuffleEnabled) {
      prepared.copy(
        currentIndex = prepared.shuffleOrder[nextIndex],
        shufflePosition = nextIndex,
      )
    } else {
      prepared.copy(currentIndex = nextIndex)
    }
  }

  private fun peek(
    previous: PlaybackQueueState,
    forward: Boolean,
  ): PlaybackItem? {
    if (previous.items.isEmpty()) return null
    val prepared = if (previous.shuffleEnabled && previous.shuffleOrder.size != previous.items.size) {
      rebuildShuffle(previous)
    } else {
      previous
    }
    val nextIndex = peekIndex(prepared, forward) ?: return null
    val itemIndex = if (prepared.shuffleEnabled) prepared.shuffleOrder[nextIndex] else nextIndex
    return prepared.items.getOrNull(itemIndex)
  }

  private fun peekIndex(
    state: PlaybackQueueState,
    forward: Boolean,
  ): Int? {
    val position = if (state.shuffleEnabled) state.shufflePosition else state.currentIndex
    val lastIndex = if (state.shuffleEnabled) state.shuffleOrder.lastIndex else state.items.lastIndex
    if (position !in 0..lastIndex) return null

    return if (forward) {
      when {
        position < lastIndex -> position + 1
        state.repeatMode == RepeatMode.ALL -> 0
        else -> null
      }
    } else {
      when {
        position > 0 -> position - 1
        state.repeatMode == RepeatMode.ALL -> lastIndex
        else -> null
      }
    }
  }

  private fun rebuildShuffle(state: PlaybackQueueState): PlaybackQueueState {
    if (!state.shuffleEnabled || state.items.isEmpty()) {
      return state.copy(shuffleOrder = emptyList(), shufflePosition = -1)
    }
    val currentIndex = state.currentIndex.coerceIn(state.items.indices)
    val remaining = state.items.indices.filter { it != currentIndex }.shuffled()
    return state.copy(
      currentIndex = currentIndex,
      shuffleOrder = listOf(currentIndex) + remaining,
      shufflePosition = 0,
    )
  }
}

object PlaybackIdentity {
  private const val VERSION_PREFIX = "media:v2:"

  fun forNetwork(
    connectionId: Long,
    relativePath: String,
  ): String = digest("network\u0000$connectionId\u0000${normalizeNetworkPath(relativePath)}")

  fun forUri(uri: String): String = digest("uri\u0000${canonicalizeUri(uri)}")

  fun forTorrent(
    infoHash: String,
    fileIndex: Int,
  ): String = digest("torrent\u0000${infoHash.trim().lowercase(Locale.ROOT)}\u0000$fileIndex")

  private fun digest(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return VERSION_PREFIX +
      bytes.joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
      }
  }

  private fun normalizeNetworkPath(path: String): String {
    val normalized = path.replace('\\', '/').split('/').fold(mutableListOf<String>()) { parts, part ->
      when (part) {
        "", "." -> Unit
        ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
        else -> parts += part
      }
      parts
    }
    return normalized.joinToString("/")
  }

  private fun canonicalizeUri(raw: String): String {
    val parsed = runCatching { URI(raw) }.getOrNull() ?: return raw.trim()
    val scheme = parsed.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return parsed.normalize().toString()

    val host = parsed.host?.lowercase(Locale.ROOT)
    val port =
      parsed.port.takeUnless { port ->
        port == -1 || (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
      } ?: -1
    return runCatching {
      URI(
        scheme,
        null,
        host,
        port,
        parsed.path.ifBlank { "/" },
        parsed.query,
        null,
      ).normalize().toString()
    }.getOrDefault(raw.trim())
  }
}
