package app.gyrolet.mpvrx.repository.lyrics

import android.util.AtomicFile
import android.util.Log
import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class LyricsProviderCache(private val directory: File) {
  @Serializable
  private data class Entry(
    val mediaPath: String,
    val provider: String,
    val lyrics: Lyrics,
  )

  private val json = Json { ignoreUnknownKeys = true }

  @Synchronized
  fun remove(mediaPath: String) {
    val key = trackKey(mediaPath)
    LyricsProvider.entries.forEach { provider ->
      AtomicFile(File(directory, "${key}_${provider.name}.json")).delete()
    }
  }

  @Synchronized
  fun read(mediaPath: String): Map<LyricsProvider, Lyrics> {
    if (mediaPath.isBlank()) return emptyMap()
    val trackKey = trackKey(mediaPath)
    return buildMap {
      LyricsProvider.available.forEach { provider ->
        val file = File(directory, "${trackKey}_${provider.name}.json")
        if (!file.exists() && !File("${file.path}.bak").exists()) return@forEach
        val atomicFile = AtomicFile(file)
        try {
          val entry = atomicFile.openRead().use { input ->
            require(input.channel.size() <= MAX_ENTRY_BYTES)
            json.decodeFromString<Entry>(input.readBytes().toString(Charsets.UTF_8))
          }
          if (entry.mediaPath == mediaPath && entry.provider == provider.name && entry.lyrics.isValid()) {
            put(provider, entry.lyrics)
            file.setLastModified(System.currentTimeMillis())
          } else {
            atomicFile.delete()
          }
        } catch (error: Exception) {
          Log.w(TAG, "Unable to read saved provider lyrics", error)
          atomicFile.delete()
        }
      }
    }
  }

  @Synchronized
  fun write(mediaPath: String, provider: LyricsProvider, lyrics: Lyrics) {
    if (mediaPath.isBlank() || !lyrics.isValid()) return
    try {
      val bytes = json.encodeToString(Entry(mediaPath, provider.name, lyrics)).toByteArray(Charsets.UTF_8)
      if (bytes.size > MAX_ENTRY_BYTES) return
      if (!directory.isDirectory && !directory.mkdirs()) return
      val file = AtomicFile(File(directory, "${trackKey(mediaPath)}_${provider.name}.json"))
      val output = file.startWrite()
      try {
        output.write(bytes)
        file.finishWrite(output)
      } catch (error: Exception) {
        file.failWrite(output)
        throw error
      }
      trim()
    } catch (error: Exception) {
      Log.w(TAG, "Unable to save provider lyrics", error)
    }
  }

  private fun trackKey(mediaPath: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(mediaPath.toByteArray(Charsets.UTF_8))
      .joinToString("") { byte -> "%02x".format(byte) }

  private fun trim() {
    val files = directory.listFiles { file -> file.isFile && file.extension == "json" }
      ?.sortedBy { it.lastModified() } ?: return
    var totalBytes = files.sumOf { it.length() }
    var count = files.size
    for (file in files) {
      if (totalBytes <= MAX_CACHE_BYTES && count <= MAX_ENTRIES) break
      val size = file.length()
      if (file.delete()) {
        totalBytes -= size
        count--
      }
    }
  }

  private companion object {
    const val TAG = "LyricsProviderCache"
    const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
    const val MAX_CACHE_BYTES = 16L * 1024L * 1024L
    const val MAX_ENTRIES = 512
  }
}
