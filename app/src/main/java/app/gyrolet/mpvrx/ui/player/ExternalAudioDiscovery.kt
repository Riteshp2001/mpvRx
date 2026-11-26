/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import app.gyrolet.mpvrx.utils.logging.Log
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import `is`.xyz.mpv.Utils
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Checks exact companion filenames on accessible local storage; never lists a directory. */
internal object ExternalAudioDiscovery {
  private const val TAG = "ExternalAudioDiscovery"
  private val extensions = FileTypeUtils.AUDIO_EXTENSIONS.sorted().flatMap {
    listOf(it, it.uppercase(Locale.ROOT))
  }.distinct()

  suspend fun autoload(context: Context, generation: Long): Unit = withContext(Dispatchers.IO) {
    if (MpvConfigOverridePolicy.isOwnedByMpvConf("audio-file-auto")) return@withContext
    if (!PlaybackSession.isCurrentGeneration(generation)) return@withContext
    val item = PlaybackSession.state.value.currentItem ?: return@withContext
    if (item.audiobook != null || item.networkSource != null || item.isDefinitelyAudioOnly()) return@withContext
    val hasVideo = PlaybackSession.readLoadedMedia(generation) { properties ->
      (0 until (properties.int("track-list/count") ?: 0)).any { index ->
        properties.string("track-list/$index/type") == "video" &&
          properties.string("track-list/$index/image") != "yes"
      }
    } ?: return@withContext
    if (!hasVideo) return@withContext

    val mediaFile = localFile(context, item) ?: return@withContext
    val directory = mediaFile.parentFile ?: return@withContext
    val baseName = mediaFile.nameWithoutExtension
    if (baseName.isBlank()) return@withContext
    val seen = hashSetOf<Pair<Long, Long>>()
    for (extension in extensions) {
      currentCoroutineContext().ensureActive()
      if (!PlaybackSession.isCurrentGeneration(generation)) return@withContext
      val candidate = File(directory, "$baseName.$extension")
      if (!candidate.isFile || !candidate.canRead()) continue
      val identity = fileIdentity(candidate.absolutePath) ?: continue
      if (!seen.add(identity)) continue
      if (!addIfAbsent(candidate.absolutePath, generation, candidate.nameWithoutExtension)) return@withContext
    }
  }

  /** Native inspection avoids duplicates even before the UI track-list flow catches up. */
  fun addIfAbsent(path: String, generation: Long, title: String? = null): Boolean {
    val identity = fileIdentity(path)
    return PlaybackSession.addExternalAudioForGeneration(generation, path, title) { existing ->
      existing == path || (identity != null && identity == fileIdentity(existing))
    }
  }

  private fun localFile(context: Context, item: PlaybackItem): File? {
    val original = Uri.parse(item.originalUri)
    if (original.scheme?.lowercase(Locale.ROOT) !in setOf(null, "file", "content")) return null
    for (source in listOf(item.originalUri, item.playableUri)) {
      val uri = Uri.parse(source)
      val path = if (uri.scheme == null && source.startsWith('/')) source else uri.resolveLocalPath(context)
      val file = path?.let(::File)
      if (file?.isAbsolute == true) return file
    }
    return null
  }

  private fun fileIdentity(path: String): Pair<Long, Long>? {
    val localPath = when {
      path.startsWith('/') -> path
      path.startsWith("file://") -> Uri.parse(path).path
      path.startsWith("fd://") -> path.removePrefix("fd://").toIntOrNull()?.let { Utils.findRealPath(it) }
      else -> null
    } ?: return null
    return try {
      val stat = Os.stat(localPath)
      stat.st_dev to stat.st_ino
    } catch (error: ErrnoException) {
      if (error.errno != OsConstants.ENOENT && error.errno != OsConstants.ENOTDIR) {
        Log.w(TAG, "Could not inspect external audio path: $localPath", error)
      }
      null
    }
  }
}
