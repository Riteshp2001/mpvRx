/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.music

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import app.gyrolet.mpvrx.utils.device.runAtLowThreadPriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

object MusicLibraryScanner {

  private const val TAG = "MusicLibraryScanner"
  private val ALBUM_ART_BASE_URI = Uri.parse("content://media/external/audio/albumart")
  private const val ALBUM_TAG_CACHE_FILE = "music_album_tags.bin"
  private const val ALBUM_TAG_CACHE_VERSION = 1
  private const val ALBUM_TAG_SAVE_INTERVAL = 64
  private const val ALBUM_TAG_MAX_STORED_LENGTH = 4096

  // Album tags read from the files themselves. The cache used to live only in memory, so every cold
  // start re-opened each untagged song with MediaMetadataRetriever while the user was scrolling.
  // Keys include the file size and modification time, so an edited file is read again.
  private val albumTagCache = ConcurrentHashMap<String, String>()
  private val albumTagCacheLock = Any()

  @Volatile private var albumTagCacheLoaded = false

  private data class PendingAlbumTag(
    val songIndex: Int,
    val indexedAlbum: String?,
    val mediaStoreAlbumId: Long,
  )

  suspend fun scanSongs(
    context: Context,
    onSongsIndexed: (suspend (List<MusicSong>) -> Unit)? = null,
  ): List<MusicSong> = withContext(Dispatchers.IO) {
    val songs = mutableListOf<MusicSong>()
    val pendingAlbumTags = mutableListOf<PendingAlbumTag>()
    ensureAlbumTagCacheLoaded(context)
    val usedAlbumTagKeys = HashSet<String>()
    var libraryQueryCompleted = false
    val projection = arrayOf(
      MediaStore.Audio.Media._ID,
      MediaStore.Audio.Media.TITLE,
      MediaStore.Audio.Media.ARTIST,
      MediaStore.Audio.Media.ALBUM,
      MediaStore.Audio.Media.ALBUM_ID,
      MediaStore.Audio.Media.DURATION,
      MediaStore.Audio.Media.DATA,
      MediaStore.Audio.Media.DATE_ADDED,
      MediaStore.Audio.Media.DATE_MODIFIED,
      MediaStore.Audio.Media.TRACK,
      MediaStore.Audio.Media.YEAR,
      MediaStore.Audio.Media.SIZE,
      MediaStore.Audio.Media.MIME_TYPE,
    ).let { columns ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) columns + MediaStore.MediaColumns.RELATIVE_PATH else columns
    }

    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} > 1000"
    val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

    try {
      context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        null,
        sortOrder
      )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
        val relativePathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
        } else -1
        val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        val mimeTypeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)

        while (cursor.moveToNext()) {
          currentCoroutineContext().ensureActive()
          val id = cursor.getLong(idCol)
          val path = cursor.getString(dataCol)
          val size = cursor.getLong(sizeCol)
          val duration = cursor.getLong(durationCol)
          val dateModified = cursor.getLong(dateModifiedCol)

          // On Android 10+ the DATA column may be null or stale; use content URI as fallback.
          val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
          val effectivePath = path ?: contentUri.toString()
          val file = path?.let { File(it) }
          if (size <= 0L && duration <= 0L) {
            val fileExists = try { file?.exists() == true } catch (_: Exception) { false }
            if (!fileExists) continue
          }
          if (app.gyrolet.mpvrx.domain.audiobook.AudiobookMarkerUtils.isAudiobookMediaFile(path ?: effectivePath)) continue

          val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: (file?.nameWithoutExtension ?: id.toString())
          val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist"
          val indexedAlbum = cursor.getString(albumCol)?.trim()?.takeIf { it.isNotBlank() && it != "<unknown>" }
          val folderName = file?.parentFile?.name ?: relativePathCol.takeIf { it >= 0 }?.let { column ->
            cursor.getString(column)?.let { File(it).name }
          }
          val needsAlbumVerification =
            indexedAlbum == null || folderName == null || indexedAlbum.equals(folderName, ignoreCase = true)
          val cachedAlbumTag = if (needsAlbumVerification) {
            val albumTagKey = albumTagCacheKey(contentUri, indexedAlbum, dateModified, size)
            usedAlbumTagKeys.add(albumTagKey)
            albumTagCache[albumTagKey]
          } else null
          val album = if (needsAlbumVerification) cachedAlbumTag?.takeIf(String::isNotBlank) else indexedAlbum
          val mediaStoreAlbumId = cursor.getLong(albumIdCol)
          val albumId = if (album?.equals(indexedAlbum, ignoreCase = true) == true) mediaStoreAlbumId else 0L
          val dateAdded = cursor.getLong(dateAddedCol)
          val track = cursor.getInt(trackCol)
          val year = cursor.getInt(yearCol)

          if (needsAlbumVerification && cachedAlbumTag == null) {
            pendingAlbumTags.add(PendingAlbumTag(songs.size, indexedAlbum, mediaStoreAlbumId))
          }

          // Keep MediaStore's artwork identity even when we intentionally avoid its album ID for
          // grouping because the embedded album tag disagrees with the index. Dropping the raw ID
          // here made otherwise valid cover art disappear for those songs.
          val albumArtUri =
            if (mediaStoreAlbumId > 0) ContentUris.withAppendedId(ALBUM_ART_BASE_URI, mediaStoreAlbumId) else null

          songs.add(
            MusicSong(
              id = id,
              title = title,
              artist = artist,
              album = album ?: "Unknown Album",
              albumId = albumId,
              durationMs = duration,
              path = effectivePath,
              uri = contentUri,
              dateAdded = dateAdded,
              trackNumber = track,
              year = year,
              albumArtUri = albumArtUri,
              size = size,
              hasAlbumTag = album != null,
              dateModified = dateModified,
              mimeType = mimeTypeCol.takeIf { it >= 0 }?.let(cursor::getString),
            )
          )
        }
        libraryQueryCompleted = true
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Error scanning songs from MediaStore", e)
    }

    if (pendingAlbumTags.isNotEmpty()) {
      currentCoroutineContext().ensureActive()
      onSongsIndexed?.invoke(songs.toList())
      var probedSinceSave = 0
      for (pending in pendingAlbumTags) {
        currentCoroutineContext().ensureActive()
        val song = songs[pending.songIndex]
        // Opening every untagged file is the heaviest part of a scan; keep it below the UI threads.
        val album = runAtLowThreadPriority {
          resolveAlbumTag(context, song.uri, pending.indexedAlbum, song.dateModified, song.size)
        }
        songs[pending.songIndex] = song.copy(
          album = album ?: "Unknown Album",
          albumId = if (album?.equals(pending.indexedAlbum, ignoreCase = true) == true) pending.mediaStoreAlbumId else 0L,
          hasAlbumTag = album != null,
        )
        // Save progress so an interrupted first scan is not repeated from scratch.
        if (++probedSinceSave >= ALBUM_TAG_SAVE_INTERVAL) {
          saveAlbumTagCache(context)
          probedSinceSave = 0
        }
      }
    }

    // Only a complete library listing may prune entries for changed or removed files; a failed
    // query just keeps what was read so far.
    if (libraryQueryCompleted) {
      val hasStaleEntries = albumTagCache.keys.any { it !in usedAlbumTagKeys }
      if (pendingAlbumTags.isNotEmpty() || hasStaleEntries) {
        albumTagCache.keys.retainAll(usedAlbumTagKeys)
        saveAlbumTagCache(context)
      }
    } else if (pendingAlbumTags.isNotEmpty()) {
      saveAlbumTagCache(context)
    }

    songs
  }

  private fun ensureAlbumTagCacheLoaded(context: Context) {
    if (albumTagCacheLoaded) return
    synchronized(albumTagCacheLock) {
      if (albumTagCacheLoaded) return
      runCatching {
        val file = File(context.applicationContext.noBackupFilesDir, ALBUM_TAG_CACHE_FILE)
        if (file.isFile) {
          DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            if (input.readInt() == ALBUM_TAG_CACHE_VERSION) {
              repeat(input.readInt()) {
                val key = input.readUTF()
                val album = input.readUTF()
                albumTagCache.putIfAbsent(key, album)
              }
            }
          }
        }
      }.onFailure { error -> Log.w(TAG, "Ignoring unreadable album tag cache", error) }
      albumTagCacheLoaded = true
    }
  }

  private fun saveAlbumTagCache(context: Context) {
    synchronized(albumTagCacheLock) {
      runCatching {
        val entries =
          albumTagCache.entries
            .map { it.key to it.value }
            .filter { (key, album) -> key.length <= ALBUM_TAG_MAX_STORED_LENGTH && album.length <= ALBUM_TAG_MAX_STORED_LENGTH }
        val directory = context.applicationContext.noBackupFilesDir
        val target = File(directory, ALBUM_TAG_CACHE_FILE)
        val temporary = File(directory, "$ALBUM_TAG_CACHE_FILE.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
          output.writeInt(ALBUM_TAG_CACHE_VERSION)
          output.writeInt(entries.size)
          entries.forEach { (key, album) ->
            output.writeUTF(key)
            output.writeUTF(album)
          }
        }
        if (!temporary.renameTo(target)) {
          target.delete()
          check(temporary.renameTo(target)) { "Could not replace $target" }
        }
      }.onFailure { error -> Log.w(TAG, "Could not persist album tag cache", error) }
    }
  }

  private fun albumTagCacheKey(uri: Uri, indexedAlbum: String?, dateModified: Long, size: Long): String =
    "$uri:$dateModified:$size:${indexedAlbum.orEmpty()}"

  private fun resolveAlbumTag(
    context: Context,
    uri: Uri,
    indexedAlbum: String?,
    dateModified: Long,
    size: Long,
  ): String? {
    val cacheKey = albumTagCacheKey(uri, indexedAlbum, dateModified, size)
    albumTagCache[cacheKey]?.let { return it.takeIf(String::isNotBlank) }
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(context, uri)
      val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        ?.trim()?.takeIf { it.isNotBlank() && it != "<unknown>" }
      albumTagCache.put(cacheKey, album.orEmpty())
      album
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      null
    } finally {
      retriever.release()
    }
  }

}
