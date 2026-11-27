/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.clip

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.gyrolet.mpvrx.data.network.proxy.NetworkStreamingProxy
import app.gyrolet.mpvrx.domain.network.NetworkPlaybackUri
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Crop coordinates in the video orientation visible to the user. */
data class ClipCrop(
  val x: Int,
  val y: Int,
  val width: Int,
  val height: Int,
  val rotation: Int,
)

/** A player track selected for output; it does not mutate playback track choice. */
data class ClipTrackOption(
  val id: Int,
  val type: String,
  val ordinal: Int,
  val title: String,
  val language: String,
  val externalFile: String? = null,
  val isExternal: Boolean = externalFile != null,
  val codec: String = "",
  val demuxId: Int? = null,
) {
  val displayLabel: String
    get() =
      listOfNotNull(
        title.takeIf { it.isNotBlank() },
        language.takeIf { it.isNotBlank() },
      ).joinToString(" · ").ifBlank { "$type #$id" }
}

data class ClipRequest(
  val item: PlaybackItem,
  val startSeconds: Double,
  val endSeconds: Double,
  val crop: ClipCrop? = null,
  val audioTrack: ClipTrackOption? = null,
  val subtitleTrack: ClipTrackOption? = null,
  val subtitleDelaySeconds: Double = 0.0,
)

sealed interface ClipExportState {
  data object Idle : ClipExportState

  data class Exporting(
    val progress: Float,
    val cancelling: Boolean = false,
  ) : ClipExportState

  data class Success(
    val uri: Uri,
    val displayName: String,
  ) : ClipExportState

  data class Error(
    val message: String,
  ) : ClipExportState
}

/**
 * Process-scoped Clip export worker.
 *
 * Playback remains owned by libmpv. Export is intentionally handled by Media3 Transformer so a
 * bundled libmpv build without encoding mode can never fail Clip save during mpv_initialize().
 */
object ClipExportManager {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val exporting = AtomicBoolean(false)
  private val streamSequence = AtomicLong(0L)
  private val _state = MutableStateFlow<ClipExportState>(ClipExportState.Idle)

  @Volatile
  private var activeJob: Job? = null

  val state: StateFlow<ClipExportState> = _state.asStateFlow()

  fun export(
    context: Context,
    request: ClipRequest,
  ): Boolean {
    if (request.endSeconds <= request.startSeconds + MIN_CLIP_SECONDS) return false
    if (!exporting.compareAndSet(false, true)) return false

    // Capture the displayed/oriented frame size while this request still refers to the actively
    // playing item. CropSelectionView reports coordinates in this same orientation.
    val cropFrameSize =
      request.crop?.let { crop ->
        val sourceWidth = PlaybackSession.getPropertyInt("video-params/w") ?: 0
        val sourceHeight = PlaybackSession.getPropertyInt("video-params/h") ?: 0
        val rotation = ((crop.rotation % 360) + 360) % 360
        if (rotation == 90 || rotation == 270) {
          sourceHeight to sourceWidth
        } else {
          sourceWidth to sourceHeight
        }
      }

    val appContext = context.applicationContext
    lateinit var job: Job
    job =
      scope.launch(start = CoroutineStart.LAZY) {
        var resolvedSource: ResolvedSource? = null
        var temporaryOutput: File? = null
        try {
          resolvedSource = resolveSource(appContext, request.item)
          temporaryOutput = createTemporaryOutput(appContext)

          val subtitleCues =
            request.subtitleTrack?.let { track ->
              ClipSubtitleLoader.load(
                appContext,
                resolvedSource.uri,
                track,
                request.item.headers,
                request.startSeconds,
                request.endSeconds,
                request.subtitleDelaySeconds,
              )
            }
          val error =
            Media3ClipExporter.export(
              context = appContext,
              source = resolvedSource.uri,
              output = temporaryOutput.absolutePath,
              startSeconds = request.startSeconds,
              endSeconds = request.endSeconds,
              crop = request.crop,
              cropFrameWidth = cropFrameSize?.first ?: 0,
              cropFrameHeight = cropFrameSize?.second ?: 0,
              headers = request.item.headers,
              audioTrack = request.audioTrack,
              subtitleCues = subtitleCues,
              onProgress = { progress ->
                val current = _state.value as? ClipExportState.Exporting
                _state.value =
                  ClipExportState.Exporting(
                    progress = progress.toFloat().coerceIn(0f, 1f),
                    cancelling = current?.cancelling == true,
                  )
              },
            )

          if (error != null) {
            _state.value = ClipExportState.Error(error)
            return@launch
          }

          if (!temporaryOutput.exists() || temporaryOutput.length() <= 0L) {
            error("Clip export finished without producing an output video")
          }

          val displayName = buildDisplayName(request.item)
          val savedUri = saveToVideoLibrary(appContext, temporaryOutput, displayName)
          temporaryOutput = null
          _state.value = ClipExportState.Success(savedUri, displayName)
        } catch (error: CancellationException) {
          throw error
        } catch (error: Throwable) {
          _state.value =
            ClipExportState.Error(
              error.message?.takeIf { it.isNotBlank() } ?: "Unable to save this clip",
            )
        } finally {
          resolvedSource?.close?.invoke()
          temporaryOutput?.delete()
        }
      }
    activeJob = job
    job.invokeOnCompletion { error ->
      if (activeJob === job) {
        activeJob = null
        exporting.set(false)
        if (error is CancellationException && _state.value is ClipExportState.Exporting) {
          _state.value = ClipExportState.Idle
        }
      }
    }
    _state.value = ClipExportState.Exporting(0f)
    job.start()
    return true
  }

  fun cancel() {
    val current = _state.value as? ClipExportState.Exporting ?: return
    _state.value = current.copy(cancelling = true)
    activeJob?.cancel()
  }

  fun consumeTerminalState() {
    if (_state.value is ClipExportState.Success || _state.value is ClipExportState.Error) {
      _state.value = ClipExportState.Idle
    }
  }

  private fun createTemporaryOutput(context: Context): File {
    val directory = File(context.cacheDir, "clips").apply { mkdirs() }
    return File.createTempFile("mpvrx-clip-", ".mp4", directory).apply { delete() }
  }

  private fun resolveSource(
    context: Context,
    item: PlaybackItem,
  ): ResolvedSource {
    val contentUri =
      when {
        item.originalUri.startsWith("content://", ignoreCase = true) -> item.originalUri
        item.playableUri.startsWith("content://", ignoreCase = true) -> item.playableUri
        else -> null
      }

    // Media3 understands content:// directly. Do not detach the Android descriptor into fd://;
    // fd:// was only required by the removed native libmpv exporter.
    if (contentUri != null) {
      return ResolvedSource(uri = contentUri)
    }

    val networkReference =
      NetworkPlaybackUri.parse(item.playableUri)
        ?: item.networkSource?.let { source ->
          NetworkPlaybackUri.parse(NetworkPlaybackUri.create(source.connectionId, source.relativePath))
        }
    if (networkReference != null) {
      val proxy = NetworkStreamingProxy.getInstance()
      val streamId = "clip-${streamSequence.incrementAndGet()}"
      val uri =
        proxy.registerStream(
          streamId = streamId,
          connectionId = networkReference.connectionId,
          filePath = networkReference.path.value,
          mimeType = item.mimeType ?: "application/octet-stream",
        )
      return ResolvedSource(
        uri = uri,
        close = { runCatching { proxy.unregisterStream(streamId) } },
      )
    }

    val source = item.playableUri.ifBlank { item.originalUri }
    if (source.startsWith("fd://", ignoreCase = true)) {
      error("This video source can no longer be reopened for clipping")
    }
    return ResolvedSource(uri = source)
  }

  private fun buildDisplayName(item: PlaybackItem): String {
    val base =
      item.title
        ?.substringBeforeLast('.')
        ?.sanitizeFileName()
        ?.takeIf { it.isNotBlank() }
        ?: "MPVRX"
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return "${base}_clip_$stamp.mp4"
  }

  private fun saveToVideoLibrary(
    context: Context,
    source: File,
    displayName: String,
  ): Uri {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val resolver = context.contentResolver
      val values =
        ContentValues().apply {
          put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
          put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
          put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/mpvRx/Clips")
          put(MediaStore.Video.Media.IS_PENDING, 1)
        }
      val uri =
        resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
          ?: error("Unable to create a MediaStore entry for the clip")
      try {
        resolver.openOutputStream(uri, "w")?.use { output ->
          source.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Unable to open the saved clip")
        resolver.update(
          uri,
          ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
          null,
          null,
        )
        source.delete()
        return uri
      } catch (error: Throwable) {
        resolver.delete(uri, null, null)
        throw error
      }
    }

    // Android 8/9 still use the legacy public Movies directory. If the platform denies public
    // storage access, fall back to the app's external Movies directory rather than losing output.
    val publicResult =
      runCatching {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "mpvRx/Clips")
        check(directory.exists() || directory.mkdirs()) { "Unable to create Movies/mpvRx/Clips" }
        val target = uniqueFile(directory, displayName)
        source.copyTo(target)
        source.delete()
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("video/mp4"), null)
        Uri.fromFile(target)
      }
    publicResult.getOrNull()?.let { return it }

    val fallbackDirectory =
      File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "Clips")
        .apply { mkdirs() }
    val fallback = uniqueFile(fallbackDirectory, displayName)
    source.copyTo(fallback)
    source.delete()
    return Uri.fromFile(fallback)
  }

  private fun uniqueFile(
    directory: File,
    requestedName: String,
  ): File {
    val first = File(directory, requestedName)
    if (!first.exists()) return first
    val stem = requestedName.substringBeforeLast('.')
    val extension = requestedName.substringAfterLast('.', "mp4")
    var index = 2
    while (true) {
      val candidate = File(directory, "${stem}_$index.$extension")
      if (!candidate.exists()) return candidate
      index++
    }
  }

  private data class ResolvedSource(
    val uri: String,
    val close: () -> Unit = {},
  )

  private fun String.sanitizeFileName(): String =
    replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_")
      .trim()
      .take(80)

  private const val MIN_CLIP_SECONDS = 0.05
}
