package app.gyrolet.mpvrx.core.platform

import app.gyrolet.mpvrx.core.history.TextStorage
import app.gyrolet.mpvrx.core.preferences.KeyValueBackend
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Writes go to a temp file then atomically replace the target, so a crash never truncates settings. */
class FileTextStorage(private val directory: File) : TextStorage {
  override fun read(name: String): String? = File(directory, name).takeIf(File::isFile)?.readText()

  @Synchronized
  override fun write(name: String, contents: String) {
    directory.mkdirs()
    val target = File(directory, name)
    val temp = File(directory, "$name.tmp")
    temp.writeText(contents)
    try {
      Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }
}

class JsonFileKeyValueBackend(
  private val storage: TextStorage,
  private val fileName: String = "settings.json",
) : KeyValueBackend {
  private val json = Json { prettyPrint = true }
  private val serializer = MapSerializer(String.serializer(), String.serializer())

  override fun load(): Map<String, String> =
    storage.read(fileName)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap()

  override fun save(values: Map<String, String>) {
    storage.write(fileName, json.encodeToString(serializer, values.toSortedMap()))
  }
}
