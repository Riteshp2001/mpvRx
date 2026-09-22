package app.gyrolet.mpvrx.ui.player

internal object AudioPropertyAdapter {
  val numbers = setOf("time-pos", "playback-time", "duration", "playtime-remaining", "time-remaining", "percent-pos", "volume", "speed", "aid", "chapter",
    "audio-params/samplerate", "audio-params/channel-count", "audio-bitrate", "bitrate", "chapters", "ab-loop-a", "ab-loop-b", "vid", "sid", "secondary-sid")
  val flags = setOf("pause", "mute", "paused-for-cache", "seekable", "eof-reached")
  val strings = setOf("media-title", "metadata/artist", "path", "stream-open-filename", "audio-codec-name", "audio-params/format",
    "metadata/by-key/Title", "metadata/by-key/Artist", "metadata/by-key/ARTIST", "metadata/by-key/Album", "metadata/by-key/ALBUM",
    "metadata/by-key/album_artist", "metadata/by-key/PERFORMER", "metadata/by-key/BITS_PER_SAMPLE", "metadata/by-key/bits_per_sample")

  fun number(state: AudioEngineSnapshot, property: String): Double? = when (property) {
    "time-pos", "playback-time" -> state.positionMs / 1000.0
    "duration" -> state.durationMs / 1000.0
    "playtime-remaining", "time-remaining" -> (state.durationMs - state.positionMs).coerceAtLeast(0) / 1000.0
    "percent-pos" -> if (state.durationMs > 0) state.positionMs * 100.0 / state.durationMs else 0.0
    "volume" -> state.volume.toDouble()
    "speed" -> state.speed.toDouble()
    "aid" -> (state.tracks.firstOrNull { it.selected }?.id ?: -1).toDouble()
    "vid", "sid", "secondary-sid" -> -1.0
    "chapter" -> state.chapters.indexOfLast { it.positionMs <= state.positionMs }.toDouble()
    "chapters", "chapter-list/count" -> state.chapters.size.toDouble()
    "track-list/count" -> state.tracks.size.toDouble()
    "audio-params/samplerate" -> state.output.sourceSampleRate.toDouble()
    "audio-params/channel-count" -> state.output.sourceChannels.toDouble()
    "audio-bitrate", "bitrate" -> state.output.sourceBitrate.toDouble()
    "audio-delay" -> 0.0
    "ab-loop-a" -> state.loopStartMs?.div(1000.0)
    "ab-loop-b" -> state.loopEndMs?.div(1000.0)
    else -> track(state, property)?.let { if (property.endsWith("/id")) it.id.toDouble() else null }
  }

  fun flag(state: AudioEngineSnapshot, property: String): Boolean? = when (property) {
    "pause" -> state.paused
    "mute" -> state.muted
    "paused-for-cache" -> state.buffering
    "seekable" -> state.seekable
    "eof-reached" -> state.ended
    "core-idle" -> state.paused || state.buffering || state.ended
    "idle-active" -> state.item == null
    else -> track(state, property)?.let { if (property.endsWith("/selected")) it.selected else false }
  }

  fun text(state: AudioEngineSnapshot, property: String): String? = when (property) {
    "media-title", "force-media-title" -> state.title
    "path", "stream-open-filename" -> state.item?.originalUri
    "metadata/artist" -> state.artist
    "audio-codec-name", "audio-codec" -> state.output.sourceMimeType?.substringAfter('/')
    "audio-params/format" -> state.output.sourceMimeType
    "audio-params/channels" -> state.output.sourceChannels.takeIf { it > 0 }?.toString()
    "file-format" -> state.item?.mimeType ?: state.output.sourceMimeType
    "aid" -> state.tracks.firstOrNull { it.selected }?.id?.toString() ?: "no"
    "vid", "sid", "secondary-sid" -> "no"
    "ab-loop-a" -> state.loopStartMs?.div(1000.0)?.toString() ?: "no"
    "ab-loop-b" -> state.loopEndMs?.div(1000.0)?.toString() ?: "no"
    else -> when {
      property.startsWith("metadata/by-key/") -> when (property.substringAfterLast('/').lowercase()) {
        "title" -> state.title
        "artist", "performer" -> state.artist
        "album_artist" -> state.metadata["album_artist"] ?: state.artist
        "album" -> state.album
        else -> state.metadata[property.substringAfterLast('/').lowercase(java.util.Locale.ROOT)]
      }
      else -> track(state, property)?.let { track ->
        when (property.substringAfterLast('/')) {
          "type" -> "audio"
          "title" -> track.title
          "lang", "language" -> track.language
          "codec", "codec-desc" -> track.codec
          else -> null
        }
      }
    }
  }

  private fun track(state: AudioEngineSnapshot, property: String): AudioEngineTrack? =
    if (property.startsWith("track-list/")) {
      property.substringAfter('/').substringBefore('/').toIntOrNull()?.let(state.tracks::getOrNull)
    } else {
      null
    }
}