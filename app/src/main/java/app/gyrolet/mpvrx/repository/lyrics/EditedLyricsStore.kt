/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.repository.lyrics

import android.util.AtomicFile
import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class EditedLyricsStore(private val directory: File) {
  @Serializable
  private data class Entry(val mediaPath: String, val lyrics: Lyrics)

  private val json = Json { ignoreUnknownKeys = true }

  @Synchronized
  fun contains(mediaPath: String): Boolean {
    val file = fileFor(mediaPath)
    return file.exists() || File("${file.path}.bak").exists()
  }

  @Synchronized
  fun read(mediaPath: String): Lyrics? {
    val file = fileFor(mediaPath)
    if (!file.exists() && !File("${file.path}.bak").exists()) return null
    val entry = try {
      AtomicFile(file).openRead().use { input ->
        if (input.channel.size() > MAX_ENTRY_BYTES) throw IOException("Saved lyrics exceed the size limit.")
        json.decodeFromString<Entry>(input.readBytes().toString(Charsets.UTF_8))
      }
    } catch (error: SerializationException) {
      throw IOException("Saved lyrics could not be decoded.", error)
    }
    if (entry.mediaPath != mediaPath || !entry.lyrics.isValid()) {
      throw IOException("Saved lyrics do not match this track or contain no lyric text.")
    }
    return entry.lyrics.copy(areFromRemote = false, sourceType = LyricsSourceType.LOCAL)
  }

  @Synchronized
  fun write(mediaPath: String, lyrics: Lyrics) {
    require(lyrics.isValid()) { "Edited lyrics must contain lyric text." }
    val entry = Entry(mediaPath, lyrics.copy(areFromRemote = false, sourceType = LyricsSourceType.LOCAL))
    val bytes = try {
      json.encodeToString(entry).toByteArray(Charsets.UTF_8)
    } catch (error: SerializationException) {
      throw IOException("Edited lyrics could not be encoded.", error)
    }
    if (bytes.size > MAX_ENTRY_BYTES) throw IOException("Edited lyrics exceed the size limit.")
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("The edited lyrics directory could not be created.")

    val file = AtomicFile(fileFor(mediaPath))
    val output = file.startWrite()
    try {
      output.write(bytes)
      file.finishWrite(output)
    } catch (error: IOException) {
      file.failWrite(output)
      throw error
    }
  }

  @Synchronized
  fun remove(mediaPath: String) {
    val file = fileFor(mediaPath)
    AtomicFile(file).delete()
    if (file.exists() || File("${file.path}.bak").exists() || File("${file.path}.new").exists()) {
      throw IOException("Saved lyric edits could not be removed.")
    }
  }

  private fun fileFor(mediaPath: String): File {
    require(mediaPath.isNotBlank()) { "Edited lyrics must belong to a track." }
    val key = MessageDigest.getInstance("SHA-256")
      .digest(mediaPath.toByteArray(Charsets.UTF_8))
      .joinToString("") { byte -> "%02x".format(byte) }
    return File(directory, "$key.json")
  }

  private companion object {
    const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
  }
}
