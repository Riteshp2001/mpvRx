package app.gyrolet.mpvrx.core.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Small named-document storage; desktop writes files in the app data directory. */
interface TextStorage {
  fun read(name: String): String?

  fun write(name: String, contents: String)
}

@Serializable
data class PlaybackRecord(
  val path: String,
  val title: String,
  val positionSeconds: Double,
  val durationSeconds: Double,
  val lastPlayedMillis: Long,
  val isAudio: Boolean = false,
) {
  val progress: Float
    get() = if (durationSeconds > 0) (positionSeconds / durationSeconds).toFloat().coerceIn(0f, 1f) else 0f

  /** Matches the Android library's "watched" threshold. */
  val isFinished: Boolean get() = durationSeconds > 0 && positionSeconds >= durationSeconds * 0.95
}

/** Resume positions and the recently played list, kept in one JSON document. */
class PlaybackHistoryRepository(
  private val storage: TextStorage,
  private val maxEntries: Int = 500,
) {
  private val json = Json { ignoreUnknownKeys = true }
  private val serializer = ListSerializer(PlaybackRecord.serializer())
  private val mutableRecords = MutableStateFlow(load())

  /** Most recent first. */
  val records: StateFlow<List<PlaybackRecord>> = mutableRecords

  fun get(path: String): PlaybackRecord? = mutableRecords.value.firstOrNull { it.path == path }

  fun save(record: PlaybackRecord) {
    mutableRecords.update { current ->
      (listOf(record) + current.filterNot { it.path == record.path }).take(maxEntries)
    }
    persist()
  }

  fun remove(path: String) {
    mutableRecords.update { current -> current.filterNot { it.path == path } }
    persist()
  }

  fun clear() {
    mutableRecords.value = emptyList()
    persist()
  }

  private fun load(): List<PlaybackRecord> =
    storage.read(FILE_NAME)?.let { raw -> runCatching { json.decodeFromString(serializer, raw) }.getOrNull() }
      ?: emptyList()

  private fun persist() {
    storage.write(FILE_NAME, json.encodeToString(serializer, mutableRecords.value))
  }

  private companion object {
    const val FILE_NAME = "playback_history.json"
  }
}
