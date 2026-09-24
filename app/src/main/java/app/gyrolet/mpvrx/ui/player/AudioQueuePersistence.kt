package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.util.AtomicFile
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class SavedAudioQueue(
  val version: Int = 1,
  val queue: PlaybackQueueState,
  val positionMs: Long = 0L,
  val speed: Float = 1f,
  val paused: Boolean = true,
  val volume: Float = 100f,
  val muted: Boolean = false,
)

internal object AudioQueuePersistence {
  private val lock = Mutex()
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private const val MAX_BYTES = 8 * 1024 * 1024

  suspend fun save(context: Context, saved: SavedAudioQueue) = withContext(Dispatchers.IO) {
    lock.withLock {
      if (!retentionEnabled()) return@withLock
      if (saved.queue.currentItem == null) return@withLock
      val durableQueue = saved.queue.copy(items = saved.queue.items.map { item ->
        if (item.playableUri.startsWith("fd://")) item.copy(playableUri = item.originalUri) else item
      })
      val bytes = json.encodeToString(saved.copy(queue = durableQueue)).toByteArray(Charsets.UTF_8)
      if (bytes.size > MAX_BYTES) return@withLock
      val file = file(context)
      var output: java.io.FileOutputStream? = null
      try {
        output = file.startWrite()
        output.write(bytes)
        file.finishWrite(output)
      } catch (error: Exception) {
        file.failWrite(output)
        throw error
      }
    }
  }

  suspend fun read(context: Context): SavedAudioQueue? = withContext(Dispatchers.IO) {
    lock.withLock {
      if (!retentionEnabled()) return@withLock null
      runCatching {
        val file = file(context)
        if (file.baseFile.length() > MAX_BYTES) return@runCatching null
        val saved = json.decodeFromString<SavedAudioQueue>(file.readFully().toString(Charsets.UTF_8))
        if (saved.version != 1 || saved.queue.currentItem == null ||
          !saved.speed.isFinite() || !saved.volume.isFinite()
        ) return@runCatching null
        val queue = saved.queue
        val validShuffle = queue.shuffleOrder.sorted() == queue.items.indices.toList() &&
          queue.shuffleOrder.getOrNull(queue.shufflePosition) == queue.currentIndex
        saved.copy(
          queue = if (queue.shuffleEnabled && !validShuffle) {
            PlaybackQueueReducer.setShuffleEnabled(queue.copy(shuffleEnabled = false), true)
          } else queue,
          positionMs = saved.positionMs.coerceAtLeast(0),
          speed = saved.speed.coerceIn(0.1f, 4f),
          volume = saved.volume.coerceIn(0f, 300f),
        )
      }.getOrNull()
    }
  }

  suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
    lock.withLock { file(context).delete() }
  }

  private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "audio_queue.json"))

  private fun retentionEnabled(): Boolean = org.koin.java.KoinJavaComponent
    .get<AdvancedPreferences>(AdvancedPreferences::class.java).enableRecentlyPlayed.get()
}