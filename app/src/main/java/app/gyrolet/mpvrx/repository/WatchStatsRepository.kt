package app.gyrolet.mpvrx.repository

import android.content.Context
import android.util.AtomicFile
import app.gyrolet.mpvrx.ui.player.DeclaredPlaybackMediaKind
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.ui.player.declaredMediaKind
import app.gyrolet.mpvrx.utils.logging.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WatchStatsSnapshot(
  val totalSeconds: Long = 0,
  val audioSeconds: Long = 0,
  val videoSeconds: Long = 0,
  val sessions: Long = 0,
  val days: Map<String, Long> = emptyMap(),
  val media: Map<String, WatchMediaStats> = emptyMap(),
)

@Serializable
data class WatchMediaStats(
  val title: String,
  val artworkUri: String? = null,
  /** Original playable identity used to regenerate local/video thumbnails. */
  val sourceUri: String? = null,
  val seconds: Long = 0,
  val sessions: Long = 0,
  val isAudio: Boolean = false,
  val lastWatchedAt: Long = 0,
)

class WatchStatsRepository(context: Context) {
  private val file = AtomicFile(File(context.filesDir, "watch_stats.json"))
  private val json = Json { ignoreUnknownKeys = true }
  private val mutex = Mutex()
  private val _resetVersion = MutableStateFlow(0L)
  val resetVersion = _resetVersion.asStateFlow()
  private val _revision = MutableStateFlow(0L)
  val revision = _revision.asStateFlow()

  // Only this repository writes the file, so the decoded copy stays current. Playback records every
  // 15 s; re-reading and decoding the whole file each time was steady garbage during playback.
  private var cached: WatchStatsSnapshot? = null

  suspend fun recordSession(item: PlaybackItem) = update { current ->
    val key = item.stableId.ifBlank { item.originalUri }
    val existing = current.media[key]
    current.copy(
      sessions = current.sessions + 1,
      media = current.media + (key to existingStats(item, existing).copy(
        sessions = (existing?.sessions ?: 0) + 1,
        lastWatchedAt = System.currentTimeMillis(),
      )),
    )
  }

  suspend fun recordPlayback(item: PlaybackItem, seconds: Long) {
    if (seconds <= 0) return
    update { current ->
      val isAudio = item.declaredMediaKind() == DeclaredPlaybackMediaKind.AUDIO
      val key = item.stableId.ifBlank { item.originalUri }
      val existing = current.media[key]
      val day = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate().toString()
      current.copy(
        totalSeconds = current.totalSeconds + seconds,
        audioSeconds = current.audioSeconds + seconds.takeIf { isAudio }.orZero(),
        videoSeconds = current.videoSeconds + seconds.takeUnless { isAudio }.orZero(),
        days = (current.days + (day to ((current.days[day] ?: 0) + seconds))).trimDays(),
        media = (current.media + (key to existingStats(item, existing).copy(
          seconds = (existing?.seconds ?: 0) + seconds,
          lastWatchedAt = System.currentTimeMillis(),
        ))).trimMedia(),
      )
    }
  }

  suspend fun snapshot(): WatchStatsSnapshot = mutex.withLock { withContext(Dispatchers.IO) { readCached() } }

  suspend fun clear() = mutex.withLock {
    withContext(Dispatchers.IO) { file.delete() }
    cached = WatchStatsSnapshot()
    _resetVersion.value++
    _revision.value++
  }

  private suspend fun update(transform: (WatchStatsSnapshot) -> WatchStatsSnapshot) = mutex.withLock {
    withContext(Dispatchers.IO) {
      val updated = transform(readCached())
      // Kept even if the write fails, so the next update persists it instead of losing it.
      cached = updated
      val output = file.startWrite()
      try {
        output.write(json.encodeToString(updated).toByteArray(Charsets.UTF_8))
        file.finishWrite(output)
        _revision.value++
      } catch (cancelled: CancellationException) {
        file.failWrite(output)
        throw cancelled
      } catch (error: Exception) {
        file.failWrite(output)
        Log.e(TAG, "Unable to save watch statistics", error)
      }
    }
  }

  private fun readCached(): WatchStatsSnapshot = cached ?: read().also { cached = it }

  private fun read(): WatchStatsSnapshot =
    if (!file.baseFile.exists() && !File("${file.baseFile.path}.bak").exists()) {
      WatchStatsSnapshot()
    } else {
      runCatching {
        file.openRead().bufferedReader().use { json.decodeFromString<WatchStatsSnapshot>(it.readText()) }
      }.onFailure { error -> Log.e(TAG, "Unable to read watch statistics", error) }
        .getOrDefault(WatchStatsSnapshot())
    }

  private fun existingStats(item: PlaybackItem, existing: WatchMediaStats?): WatchMediaStats =
    WatchMediaStats(
      title = item.title?.takeIf(String::isNotBlank) ?: existing?.title ?: item.originalUri.substringAfterLast('/'),
      artworkUri = item.artworkUri ?: existing?.artworkUri,
      sourceUri = item.originalUri.takeIf(String::isNotBlank) ?: existing?.sourceUri,
      seconds = existing?.seconds ?: 0,
      sessions = existing?.sessions ?: 0,
      isAudio = item.declaredMediaKind() == DeclaredPlaybackMediaKind.AUDIO,
      lastWatchedAt = existing?.lastWatchedAt ?: 0,
    )

  private fun Long?.orZero(): Long = this ?: 0
  private fun Map<String, Long>.trimDays(): Map<String, Long> = entries.sortedByDescending { it.key }.take(90).associate { it.toPair() }
  private fun Map<String, WatchMediaStats>.trimMedia(): Map<String, WatchMediaStats> =
    entries.sortedByDescending { it.value.lastWatchedAt }.take(250).associate { it.toPair() }

  private companion object {
    const val TAG = "WatchStatsRepository"
  }
}
