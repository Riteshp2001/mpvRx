/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.torrent

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class TorrentStreamingEngine(
  context: Context,
) {
  companion object {
    private const val TAG = "TorrentStreamingEngine"
    private const val METADATA_TIMEOUT_MS = 90_000L
    private const val MAX_METADATA_BYTES = 16L * 1024L * 1024L
    private const val MAX_TORRENT_FILES = 100_000
    private const val STATUS_INTERVAL_MS = 1_000L

    private val playableMimeTypes =
      mapOf(
        "3g2" to "video/3gpp2",
        "3gp" to "video/3gpp",
        "asf" to "video/x-ms-asf",
        "avi" to "video/x-msvideo",
        "divx" to "video/*",
        "f4v" to "video/mp4",
        "flv" to "video/x-flv",
        "m2ts" to "video/mp2t",
        "m2v" to "video/mpeg",
        "m4s" to "video/iso.segment",
        "m4v" to "video/mp4",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "mp4" to "video/mp4",
        "mpeg" to "video/mpeg",
        "mpg" to "video/mpeg",
        "mts" to "video/mp2t",
        "ogm" to "video/ogg",
        "ogv" to "video/ogg",
        "rm" to "video/*",
        "rmvb" to "video/*",
        "ts" to "video/mp2t",
        "vob" to "video/mpeg",
        "webm" to "video/webm",
        "wmv" to "video/x-ms-wmv",
        "xvid" to "video/*",
        "aac" to "audio/aac",
        "ac3" to "audio/ac3",
        "aif" to "audio/aiff",
        "aiff" to "audio/aiff",
        "alac" to "audio/mp4",
        "ape" to "audio/*",
        "dff" to "audio/*",
        "dsf" to "audio/*",
        "dts" to "audio/vnd.dts",
        "eac3" to "audio/eac3",
        "flac" to "audio/flac",
        "m4a" to "audio/mp4",
        "mka" to "audio/x-matroska",
        "mp1" to "audio/mpeg",
        "mp2" to "audio/mpeg",
        "mp3" to "audio/mpeg",
        "oga" to "audio/ogg",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        "ra" to "audio/*",
        "tak" to "audio/*",
        "tta" to "audio/*",
        "wav" to "audio/wav",
        "wave" to "audio/wav",
        "weba" to "audio/webm",
        "wma" to "audio/x-ms-wma",
      )
  }

  private data class ActiveStream(
    val session: SessionManager,
    val handle: TorrentHandle,
    val proxy: TorrentProxyServer,
    val cacheDir: File,
    val statsJob: Job,
  )

  private data class PreparedTorrent(
    val handle: TorrentHandle,
    val info: TorrentInfo,
    val requestedFileIndex: Int?,
    val durableSource: String,
    val infoHash: String,
  )

  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lifecycleMutex = Mutex()
  private val generation = AtomicLong(0L)
  private val httpClient =
    OkHttpClient
      .Builder()
      .connectTimeout(20L, TimeUnit.SECONDS)
      .readTimeout(30L, TimeUnit.SECONDS)
      .callTimeout(45L, TimeUnit.SECONDS)
      .build()

  private val _state = MutableStateFlow<TorrentStreamingState>(TorrentStreamingState.Idle)
  val state: StateFlow<TorrentStreamingState> = _state.asStateFlow()

  @Volatile
  private var active: ActiveStream? = null

  @Volatile
  private var closed = false

  suspend fun startStream(request: TorrentStreamRequest): TorrentStreamResult =
    withContext(Dispatchers.IO) {
      val startGeneration = generation.incrementAndGet()
      lifecycleMutex.withLock {
        check(!closed) { "Torrent streaming engine is shut down" }
        cleanupActive()
        ensureCurrent(startGeneration)

        val source = request.source.trim()
        if (source.isEmpty()) throw streamError("Torrent source is empty.")
        if (hasV2OnlyMagnet(source)) {
          throw streamError("BitTorrent v2-only torrents are not supported yet.")
        }
        val normalized = normalizeTorrentSource(source)
          ?: if (isMetadataUri(source)) source else throw streamError("Unsupported torrent source.")

        val cacheDir = File(appContext.cacheDir, "torrent_streaming/${UUID.randomUUID()}")
        if (!cacheDir.mkdirs() && !cacheDir.isDirectory) {
          throw streamError("Couldn't create the torrent streaming cache.")
        }

        val session = SessionManager()
        var handle: TorrentHandle? = null
        var proxy: TorrentProxyServer? = null
        try {
          _state.value = TorrentStreamingState.Connecting("Starting Torrent Engine...")
          session.start()
          ensureCurrent(startGeneration)

          val prepared =
            if (normalized.startsWith("magnet:?", ignoreCase = true)) {
              prepareMagnet(session, cacheDir, normalized, request.fileIndex, startGeneration)
            } else {
              prepareMetadata(session, cacheDir, normalized, request.fileIndex, startGeneration)
            }
          handle = prepared.handle

          val files = validateAndEnumerateFiles(prepared.info, cacheDir)
          val playableFiles = files.filter { it.mimeType.isNotEmpty() }
          if (playableFiles.isEmpty()) throw streamError("Torrent contains no playable audio or video files.")

          val selectedIndex = prepared.requestedFileIndex
          val selected =
            if (selectedIndex == null) {
              playableFiles.maxBy { it.size }
            } else {
              playableFiles.firstOrNull { it.index == selectedIndex }
                ?: throw streamError("The selected torrent file is not playable.")
            }
          configureStreaming(handle, prepared.info, selected)
          ensureCurrent(startGeneration)

          val storage = prepared.info.files()
          val fileOffset = storage.fileOffset(selected.index)
          val pieceLength = prepared.info.pieceLength()
          val firstPiece = (fileOffset / pieceLength).toInt()
          val lastPiece = ((fileOffset + selected.size - 1L) / pieceLength).toInt()
          val selectedPath = safeCacheFile(cacheDir, selected.path)

          proxy =
            TorrentProxyServer(
              target =
                TorrentProxyServer.Target(
                  handle = handle,
                  file = selectedPath,
                  fileOffset = fileOffset,
                  fileSize = selected.size,
                  pieceLength = pieceLength,
                  firstPiece = firstPiece,
                  lastPiece = lastPiece,
                  mimeType = selected.mimeType,
                ),
            ).also { it.start() }

          val torrentName = safeDisplayName(prepared.info.name(), selected.name)
          val result =
            TorrentStreamResult(
              localUrl = proxy.serverUrl,
              selectedFile = selected,
              source = prepared.durableSource,
              infoHash = prepared.infoHash,
              torrentName = torrentName,
              playableFiles = playableFiles,
            )
          val statsJob = startStatsMonitoring(startGeneration, handle, result)
          active = ActiveStream(session, handle, proxy, cacheDir, statsJob)
          proxy = null
          handle = null

          _state.value = result.toStreamingState()
          result
        } catch (cancellation: CancellationException) {
          proxy?.close()
          cleanup(session, handle, cacheDir)
          if (generation.get() == startGeneration) _state.value = TorrentStreamingState.Idle
          throw cancellation
        } catch (error: Throwable) {
          proxy?.close()
          cleanup(session, handle, cacheDir)
          val safe = (error as? TorrentStreamException) ?: streamError("Couldn't start torrent streaming.", error)
          Log.e(TAG, "Torrent start failed (${error::class.java.simpleName})")
          if (generation.get() == startGeneration) _state.value = TorrentStreamingState.Error(safe.message.orEmpty())
          throw safe
        }
      }
    }

  /** Lifecycle-safe and non-blocking. Native shutdown and cache deletion run on the engine IO scope. */
  fun stopStream() {
    val stopGeneration = generation.incrementAndGet()
    active?.statsJob?.cancel()
    _state.value = TorrentStreamingState.Idle
    scope.launch {
      lifecycleMutex.withLock {
        if (generation.get() == stopGeneration) {
          cleanupActive()
          _state.value = TorrentStreamingState.Idle
        }
      }
    }
  }

  fun shutdown() {
    if (closed) return
    closed = true
    val stopGeneration = generation.incrementAndGet()
    active?.statsJob?.cancel()
    _state.value = TorrentStreamingState.Idle
    scope.launch {
      lifecycleMutex.withLock {
        if (generation.get() == stopGeneration) cleanupActive()
      }
      scope.cancel()
    }
  }

  private suspend fun prepareMagnet(
    session: SessionManager,
    cacheDir: File,
    source: String,
    explicitFileIndex: Int?,
    startGeneration: Long,
  ): PreparedTorrent {
    val parsed = parseMagnet(source) ?: throw streamError("Magnet link does not contain a supported v1 info hash.")
    val hash = runCatching { Sha1Hash.parseHex(parsed.infoHash) }.getOrNull()
      ?: throw streamError("Magnet link contains an invalid v1 info hash.")

    _state.value = TorrentStreamingState.Connecting("Connecting to peers and fetching torrent metadata...")
    // Upload mode still permits metadata exchange but prevents unselected payload files from
    // racing onto disk before their paths and priorities have been validated.
    session.download(parsed.cleanMagnetUri, cacheDir, TorrentFlags.UPLOAD_MODE)
    val handle = waitForHandle(session, hash, startGeneration)
    val info = waitForMetadata(handle, startGeneration)
    if (!info.hasV1()) throw streamError("BitTorrent v2-only torrents are not supported yet.")
    if (!info.infoHash().toHex().equals(parsed.infoHash, ignoreCase = true)) {
      throw streamError("Torrent metadata did not match the requested info hash.")
    }
    return PreparedTorrent(
      handle = handle,
      info = info,
      requestedFileIndex = explicitFileIndex ?: parsed.fileIdx,
      durableSource = magnetWithoutFileSelection(parsed.cleanMagnetUri),
      infoHash = parsed.infoHash.lowercase(),
    )
  }

  private suspend fun prepareMetadata(
    session: SessionManager,
    cacheDir: File,
    source: String,
    requestedFileIndex: Int?,
    startGeneration: Long,
  ): PreparedTorrent {
    _state.value = TorrentStreamingState.Connecting("Reading torrent metadata...")
    val payload = readMetadata(source)
    val endpoints = runCatching { extractTorrentMetadataEndpoints(payload) }.getOrElse {
      throw streamError("The selected file contains invalid torrent metadata.")
    }
    val info = runCatching { TorrentInfo(payload) }.getOrElse {
      throw streamError("The selected file is not valid torrent metadata.")
    }
    if (!info.isValid) throw streamError("The selected file is not valid torrent metadata.")
    if (!info.hasV1()) throw streamError("BitTorrent v2-only torrents are not supported yet.")
    validateAndEnumerateFiles(info, cacheDir)
    val hashHex = info.infoHash().toHex().lowercase()
    val priorities = Array(info.files().numFiles()) { Priority.IGNORE }

    session.download(info, cacheDir, null, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
    val handle = waitForHandle(session, info.infoHash(), startGeneration)
    endpoints.trackers.forEach { tracker -> handle.addTracker(AnnounceEntry(tracker)) }
    endpoints.webSeeds.forEach(handle::addUrlSeed)
    if (endpoints.trackers.isNotEmpty()) handle.forceReannounce()
    if (info.isPrivate && endpoints.trackers.isEmpty()) {
      throw streamError("Private torrent metadata does not contain a supported tracker.")
    }
    val durableSource =
      buildMagnetUri(
        infoHash = hashHex,
        trackers = endpoints.trackers,
        displayName = safeDisplayName(info.name(), hashHex),
        webSeeds = endpoints.webSeeds,
      )
    return PreparedTorrent(handle, info, requestedFileIndex, durableSource, hashHex)
  }

  private suspend fun waitForHandle(
    session: SessionManager,
    hash: Sha1Hash,
    startGeneration: Long,
  ): TorrentHandle {
    val deadline = System.currentTimeMillis() + METADATA_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      ensureCurrent(startGeneration)
      session.find(hash)?.takeIf { it.isValid }?.let { return it }
      delay(100L)
    }
    throw streamError("Timed out while adding the torrent.")
  }

  private suspend fun waitForMetadata(
    handle: TorrentHandle,
    startGeneration: Long,
  ): TorrentInfo {
    val deadline = System.currentTimeMillis() + METADATA_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      ensureCurrent(startGeneration)
      if (!handle.isValid) throw streamError("Torrent session stopped before metadata was received.")
      handle.torrentFile()?.takeIf { it.isValid }?.let { return it }
      runCatching {
        val status = handle.status()
        _state.value =
          TorrentStreamingState.Connecting(
            phase = if (status.numPeers() > 0) "Downloading torrent metadata..." else "Searching for torrent peers...",
            downloadSpeed = status.downloadPayloadRate().toLong(),
            uploadSpeed = status.uploadPayloadRate().toLong(),
            peers = status.numPeers(),
            seeds = status.numSeeds(),
          )
      }
      delay(250L)
    }
    throw streamError("Timed out while fetching torrent metadata. Check that the torrent has active peers.")
  }

  private fun validateAndEnumerateFiles(
    info: TorrentInfo,
    cacheDir: File,
  ): List<TorrentFileItem> {
    val storage = info.files()
    val count = storage.numFiles()
    if (count <= 0) throw streamError("Torrent contains no files.")
    if (count > MAX_TORRENT_FILES) throw streamError("Torrent contains too many files.")
    if (info.pieceLength() <= 0) throw streamError("Torrent metadata has an invalid piece size.")

    return buildList(count) {
      for (index in 0 until count) {
        val path = storage.filePath(index)
        validateMetadataPath(path, storage.fileAbsolutePath(index), cacheDir)
        val size = storage.fileSize(index)
        val offset = storage.fileOffset(index)
        if (size < 0L || offset < 0L || (size > 0L && offset > Long.MAX_VALUE - size)) {
          throw streamError("Torrent metadata contains an invalid file size.")
        }
        val mimeType =
          if (storage.padFileAt(index) || size <= 0L) {
            ""
          } else {
            playableMimeTypes[path.substringAfterLast('.', "").lowercase()].orEmpty()
          }
        add(
          TorrentFileItem(
            index = index,
            path = path,
            name = path.substringAfterLast('/').substringAfterLast('\\'),
            size = size,
            mimeType = mimeType,
          ),
        )
      }
    }
  }

  private fun configureStreaming(
    handle: TorrentHandle,
    info: TorrentInfo,
    selected: TorrentFileItem,
  ) {
    handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
    val storage = info.files()
    val priorities = Array(storage.numFiles()) { Priority.IGNORE }
    priorities[selected.index] = Priority.TOP_PRIORITY
    handle.prioritizeFiles(priorities)

    val pieceLength = info.pieceLength().toLong()
    val fileOffset = storage.fileOffset(selected.index)
    val firstPiece = (fileOffset / pieceLength).toInt()
    val lastPiece = ((fileOffset + selected.size - 1L) / pieceLength).toInt()
    handle.setSequentialRange(firstPiece, lastPiece)

    val headEnd = (firstPiece + 15).coerceAtMost(lastPiece)
    for (piece in firstPiece..headEnd) {
      handle.piecePriority(piece, Priority.TOP_PRIORITY)
      handle.setPieceDeadline(piece, (piece - firstPiece) * 100)
    }
    val tailStart = (lastPiece - 7).coerceAtLeast(firstPiece)
    for (piece in tailStart..lastPiece) handle.piecePriority(piece, Priority.TOP_PRIORITY)
    handle.unsetFlags(TorrentFlags.UPLOAD_MODE)
    handle.resume()
  }

  private fun startStatsMonitoring(
    startGeneration: Long,
    handle: TorrentHandle,
    result: TorrentStreamResult,
  ): Job =
    scope.launch {
      while (isActive && generation.get() == startGeneration && handle.isValid) {
        try {
          val status = handle.status()
          val downloaded = handle.fileProgress().getOrNull(result.selectedFile.index)?.coerceAtLeast(0L) ?: 0L
          val bufferProgress =
            if (result.selectedFile.size > 0L) {
              (downloaded.toDouble() / result.selectedFile.size.toDouble()).coerceIn(0.0, 1.0).toFloat()
            } else {
              0f
            }
          _state.value =
            TorrentStreamingState.Streaming(
              localUrl = result.localUrl,
              selectedFileIndex = result.selectedFile.index,
              fileName = result.selectedFile.name,
              fileSize = result.selectedFile.size,
              downloadSpeed = status.downloadPayloadRate().toLong(),
              uploadSpeed = status.uploadPayloadRate().toLong(),
              peers = status.numPeers(),
              seeds = status.numSeeds(),
              bufferProgress = bufferProgress,
              totalProgress = status.progress().coerceIn(0f, 1f),
              downloadedBytes = downloaded,
            )
        } catch (error: Exception) {
          Log.w(TAG, "Unable to read torrent status (${error::class.java.simpleName})")
        }
        delay(STATUS_INTERVAL_MS)
      }
    }

  private fun readMetadata(source: String): ByteArray {
    val uri = Uri.parse(source)
    val input =
      when (uri.scheme?.lowercase()) {
        "content" -> appContext.contentResolver.openInputStream(uri)
          ?: throw streamError("Couldn't open the selected torrent metadata.")
        "file" -> {
          val path = uri.path ?: throw streamError("Torrent metadata path is invalid.")
          FileInputStream(File(path))
        }
        "http", "https" -> return readRemoteMetadata(source)
        else -> throw streamError("Only magnet links and content, file, HTTP or HTTPS torrent metadata are supported.")
      }
    return input.use { readBounded(it) }
  }

  private fun readRemoteMetadata(source: String): ByteArray {
    val request = runCatching { Request.Builder().url(source).get().build() }.getOrElse {
      throw streamError("Torrent metadata URL is invalid.")
    }
    return try {
      httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw streamError("Couldn't download torrent metadata (HTTP ${response.code}).")
        val body = response.body
        if (body.contentLength() > MAX_METADATA_BYTES) throw streamError("Torrent metadata is too large.")
        body.byteStream().use { readBounded(it) }
      }
    } catch (error: TorrentStreamException) {
      throw error
    } catch (error: IOException) {
      throw streamError("Couldn't download torrent metadata.", error)
    }
  }

  private fun readBounded(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      total += read
      if (total > MAX_METADATA_BYTES) throw streamError("Torrent metadata is too large.")
      output.write(buffer, 0, read)
    }
    if (total == 0L) throw streamError("Torrent metadata is empty.")
    return output.toByteArray()
  }

  private fun validateMetadataPath(
    path: String,
    absolute: Boolean,
    cacheDir: File,
  ) {
    if (
      path.isBlank() || path.length > 4096 || absolute || File(path).isAbsolute ||
      path.any { it == '\u0000' || it.isISOControl() } ||
      path.replace('\\', '/').split('/').any { it.isEmpty() || it == "." || it == ".." }
    ) {
      throw streamError("Torrent metadata contains an unsafe file path.")
    }
    safeCacheFile(cacheDir, path)
  }

  private fun safeCacheFile(
    cacheDir: File,
    path: String,
  ): File {
    val root = cacheDir.canonicalFile
    val candidate = File(root, path).canonicalFile
    val prefix = root.path + File.separator
    if (!candidate.path.startsWith(prefix)) throw streamError("Torrent metadata contains an unsafe file path.")
    return candidate
  }

  private fun cleanupActive() {
    val stream = active ?: return
    active = null
    stream.statsJob.cancel()
    stream.proxy.close()
    cleanup(stream.session, stream.handle, stream.cacheDir)
  }

  private fun cleanup(
    session: SessionManager,
    handle: TorrentHandle?,
    cacheDir: File,
  ) {
    if (handle?.isValid == true) {
      runCatching { session.remove(handle, SessionHandle.DELETE_FILES) }
    }
    runCatching { session.stop() }
    if (cacheDir.exists() && !cacheDir.deleteRecursively()) {
      Log.w(TAG, "Torrent cache cleanup did not remove every file")
    }
    cacheDir.parentFile?.takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
  }

  private fun ensureCurrent(expected: Long) {
    if (closed || generation.get() != expected) throw CancellationException("Torrent stream request superseded")
  }

  private fun TorrentStreamResult.toStreamingState() =
    TorrentStreamingState.Streaming(
      localUrl = localUrl,
      selectedFileIndex = selectedFile.index,
      fileName = selectedFile.name,
      fileSize = selectedFile.size,
      downloadSpeed = 0L,
      uploadSpeed = 0L,
      peers = 0,
      seeds = 0,
      bufferProgress = 0f,
      totalProgress = 0f,
      downloadedBytes = 0L,
    )

  private fun safeDisplayName(
    value: String?,
    fallback: String,
  ): String =
    value
      ?.trim()
      ?.filterNot { it.isISOControl() }
      ?.take(240)
      ?.takeIf(String::isNotBlank)
      ?: fallback

  private fun isMetadataUri(source: String): Boolean =
    Uri.parse(source).scheme?.lowercase() in setOf("content", "file", "http", "https")

  private fun streamError(
    message: String,
    cause: Throwable? = null,
  ) = TorrentStreamException(message, cause)
}
