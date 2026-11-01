/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.ui.player.resolveLocalPath
import app.gyrolet.mpvrx.utils.media.resolveSubtitleStorageDirectory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/** Resume cache: one JSON file per in-progress translation, pruned after a month. */
internal class FileTranslationBatchCache(
  context: Context,
  private val json: Json,
) : TranslationBatchCache {
  @Serializable
  private data class Entry(
    val batches: Map<String, List<String>>,
  )

  private val dir = File(context.filesDir, "subtitle_translation_cache")

  init {
    val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
    dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach(File::delete)
    // Left behind by the previous implementation, which could never resume.
    File(context.filesDir, "ai_translation_cache").takeIf(File::exists)?.deleteRecursively()
  }

  override fun load(key: String): Map<Int, List<String>> =
    runCatching {
      json
        .decodeFromString(Entry.serializer(), file(key).readText())
        .batches
        .mapNotNull { (index, values) -> index.toIntOrNull()?.let { it to values } }
        .toMap()
    }.getOrDefault(emptyMap())

  override fun save(
    key: String,
    batches: Map<Int, List<String>>,
  ) {
    runCatching {
      dir.mkdirs()
      val tmp = File(dir, "$key.tmp")
      tmp.writeText(json.encodeToString(Entry.serializer(), Entry(batches.mapKeys { it.key.toString() })))
      tmp.renameTo(file(key)) || run { tmp.copyTo(file(key), overwrite = true); tmp.delete() }
    }.onFailure { Log.w(TAG, "Couldn't save translation progress", it) }
  }

  override fun clear(key: String) {
    file(key).delete()
  }

  private fun file(key: String) = File(dir, "$key.json")

  private companion object {
    const val TAG = "SubtitleTranslation"
  }
}

/** Remembers finished translations so the same file isn't translated twice. */
internal class TranslatedSubtitleIndex(
  private val context: Context,
  private val json: Json,
) {
  @Serializable
  data class Entry(
    val key: String,
    val uri: String,
    val fileName: String,
    /** Absolute path or document URI, used to recognize files this feature wrote. */
    val identity: String,
    val createdAtMs: Long,
    /** Same source text + target language, whatever the provider: such a file may be overwritten. */
    val sourceKey: String = "",
  )

  private val file = File(context.filesDir, "subtitle_translations.json")
  private val lock = Any()

  fun find(key: String): Entry? = synchronized(lock) { read().lastOrNull { it.key == key }?.takeIf(::isReadable) }

  /** True if this feature wrote the file at [identity] from the same source text and target. */
  fun isOurs(
    identity: String,
    sourceKey: String,
  ): Boolean = synchronized(lock) { read().any { it.identity == identity && it.sourceKey == sourceKey } }

  fun put(entry: Entry) =
    synchronized(lock) {
      val entries = read().filterNot { it.key == entry.key || it.identity == entry.identity } + entry
      runCatching { file.writeText(json.encodeToString(ListSerializer, entries.takeLast(MAX_ENTRIES))) }
        .onFailure { Log.w("SubtitleTranslation", "Couldn't update translation index", it) }
    }

  private fun read(): List<Entry> =
    if (!file.exists()) emptyList() else runCatching { json.decodeFromString(ListSerializer, file.readText()) }.getOrDefault(emptyList())

  private fun isReadable(entry: Entry): Boolean {
    val uri = Uri.parse(entry.uri)
    return when (uri.scheme) {
      "file", null -> uri.path?.let { File(it).canRead() } == true
      else -> runCatching { context.contentResolver.openInputStream(uri)?.use { true } == true }.getOrDefault(false)
    }
  }

  private companion object {
    const val MAX_ENTRIES = 500
    val ListSerializer = kotlinx.serialization.builtins.ListSerializer(Entry.serializer())
  }
}

/**
 * Writes a translated subtitle where the player will find it again, in order of preference:
 * next to the video, next to the source subtitle, the user's subtitle folder, then app storage.
 */
class SubtitleOutputWriter(
  private val context: Context,
) {
  enum class Location(
    val description: String,
  ) {
    NEXT_TO_VIDEO("next to the video"),
    NEXT_TO_SUBTITLE("next to the original subtitle"),
    SUBTITLE_FOLDER("in your subtitle folder"),
    APP_FOLDER("in the app's subtitle folder (Android/data)"),
    JELLYFIN_SERVER("next to the movie on your Jellyfin server"),
  }

  data class Written(
    val uri: Uri,
    val fileName: String,
    val identity: String,
    val location: Location,
  )

  fun write(
    fileNames: List<String>,
    bytes: ByteArray,
    videoUri: Uri?,
    subtitleUri: Uri?,
    subtitleFolderTree: String,
    mediaFolderName: String,
    isOurs: (String) -> Boolean,
  ): Written {
    localDirectory(videoUri)?.let { dir -> writeFile(dir, fileNames, bytes, isOurs, Location.NEXT_TO_VIDEO)?.let { return it } }
    localDirectory(subtitleUri)
      ?.takeUnless { it.absolutePath.startsWith(context.cacheDir.absolutePath) || it.absolutePath.startsWith(context.filesDir.absolutePath) }
      ?.let { dir -> writeFile(dir, fileNames, bytes, isOurs, Location.NEXT_TO_SUBTITLE)?.let { return it } }
    writeToTree(subtitleFolderTree, mediaFolderName, fileNames, bytes, isOurs)?.let { return it }

    val appDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Subtitles")
    return writeFile(appDir, fileNames, bytes, isOurs, Location.APP_FOLDER)
      ?: throw SubtitleTranslationException.Configuration("Couldn't save the translated subtitle anywhere. Is storage full?")
  }

  /**
   * Moves a downloaded subtitle next to a local video as `<video>.<lang>.<ext>` so players
   * auto-load it. Returns null (and leaves the file alone) if the video's folder isn't writable.
   */
  fun moveNextToVideo(
    downloaded: Uri,
    videoUri: Uri?,
    languageCode: String,
  ): Uri? {
    val videoPath = videoUri?.let { runCatching { it.resolveLocalPath(context) }.getOrNull() } ?: return null
    val dir = File(videoPath).parentFile?.takeIf(File::isDirectory) ?: return null
    val sourcePath = runCatching { downloaded.resolveLocalPath(context) }.getOrNull()
    if (sourcePath != null && File(sourcePath).parentFile == dir) return null
    val bytes = runCatching { context.contentResolver.openInputStream(downloaded)?.use { it.readBytes() } }.getOrNull() ?: return null
    val base = File(videoPath).name.substringBeforeLast('.')
    val extension = (sourcePath ?: downloaded.lastPathSegment.orEmpty()).substringAfterLast('.', "srt").lowercase()
    val names = listOf("$base.$languageCode.$extension") + (2..9).map { "$base.$languageCode.$it.$extension" }
    // Re-downloading the same subtitle reuses its file instead of piling up copies.
    val written =
      writeFile(dir, names, bytes, { path -> runCatching { File(path).readBytes().contentEquals(bytes) }.getOrDefault(false) }, Location.NEXT_TO_VIDEO)
        ?: return null
    if (sourcePath != null && sourcePath.startsWith(context.getExternalFilesDir(null)?.absolutePath ?: "\u0000")) {
      File(sourcePath).delete()
      File(sourcePath).parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
    }
    return written.uri
  }

  private fun localDirectory(uri: Uri?): File? {
    if (uri == null) return null
    val path = runCatching { uri.resolveLocalPath(context) }.getOrNull() ?: return null
    return File(path).parentFile?.takeIf(File::isDirectory)
  }

  private fun writeFile(
    dir: File,
    fileNames: List<String>,
    bytes: ByteArray,
    isOurs: (String) -> Boolean,
    location: Location,
  ): Written? =
    runCatching {
      dir.mkdirs()
      val target =
        fileNames
          .map { File(dir, it) }
          .firstOrNull { !it.exists() || isOurs(it.absolutePath) }
          ?: return null
      target.writeBytes(bytes)
      Written(target.toUri(), target.name, target.absolutePath, location)
    }.onFailure { Log.i("SubtitleTranslation", "Can't write to $dir: ${it.message}") }.getOrNull()

  private fun writeToTree(
    tree: String,
    mediaFolderName: String,
    fileNames: List<String>,
    bytes: ByteArray,
    isOurs: (String) -> Boolean,
  ): Written? =
    runCatching {
      val root = resolveSubtitleStorageDirectory(context, tree, createIfMissing = true) ?: return null
      // Same per-video folder the player scans when a video opens, so the translation auto-loads.
      val folder =
        mediaFolderName.takeIf { it.isNotBlank() }?.let { name ->
          root.findFile(name)?.takeIf(DocumentFile::isDirectory) ?: root.createDirectory(name)
        } ?: root
      var chosen: DocumentFile? = null
      for (name in fileNames) {
        val existing = folder.findFile(name)
        if (existing == null) {
          chosen = folder.createFile("application/octet-stream", name)
          break
        }
        if (isOurs(existing.uri.toString())) {
          chosen = existing
          break
        }
      }
      val document = chosen ?: return null
      context.contentResolver.openOutputStream(document.uri, "wt")?.use { it.write(bytes) } ?: return null
      Written(document.uri, document.name ?: fileNames.first(), document.uri.toString(), Location.SUBTITLE_FOLDER)
    }.onFailure { Log.i("SubtitleTranslation", "Can't write to subtitle folder: ${it.message}") }.getOrNull()
}
