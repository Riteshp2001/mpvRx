/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.thumbfast

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ThumbFastPreviewState(
  val visible: Boolean = false,
  val positionSeconds: Float = 0f,
  val fraction: Float = 0f,
  val bitmap: Bitmap? = null,
  val isLoading: Boolean = false,
)

/**
 * Owns one interactive ThumbFast session for a PlayerViewModel.
 *
 * The native engine is a second, persistent libmpv core. Scrub requests only update that core's
 * latest target; the active playback core is never sought or screenshotted while the finger is
 * down. The last completed frame stays visible until a newer frame is ready, matching thumbfast's
 * newest-target-wins behavior without decoder-per-pointer-event churn.
 */
internal class ThumbFastPreviewController(
  private val scope: CoroutineScope,
  context: Context,
) {
  private data class Request(
    val source: String,
    val contentUri: String?,
    val sourceIdentity: String,
    val userAgent: String,
    val httpHeaders: String,
    val positionSeconds: Double,
    val sourceEpoch: Int,
  )

  private data class RetiredEngine(
    val handle: Long,
    val contentFd: ParcelFileDescriptor?,
    val framePumpJob: Job?,
    val initializeJob: Job?,
  )

  private val appContext = context.applicationContext
  private val lock = Any()
  private val _state = MutableStateFlow(ThumbFastPreviewState())
  val state: StateFlow<ThumbFastPreviewState> = _state.asStateFlow()

  private var nativeHandle = 0L
  private var initializeJob: Job? = null
  private var framePumpJob: Job? = null
  private var latestRequest: Request? = null
  private var currentSourceIdentity: String? = null
  private var ownedContentFd: ParcelFileDescriptor? = null
  private var ownedContentUri: String? = null
  private var sourceEpoch = 0
  private var engineEpoch = 0
  private var closed = false

  fun request(
    source: String?,
    contentUri: String?,
    positionSeconds: Float,
    durationSeconds: Float,
    userAgent: String?,
    httpHeaders: String?,
  ) {
    val clampedPosition =
      if (durationSeconds > 0f) {
        positionSeconds.coerceIn(0f, durationSeconds)
      } else {
        positionSeconds.coerceAtLeast(0f)
      }
    val fraction =
      if (durationSeconds > 0f) {
        (clampedPosition / durationSeconds).coerceIn(0f, 1f)
      } else {
        0f
      }

    val normalizedSource = source?.takeIf { it.isNotBlank() }
    val normalizedContentUri = contentUri?.takeIf { isContentUri(it) }
    val normalizedUserAgent = userAgent.orEmpty()
    val normalizedHeaders = httpHeaders.orEmpty()
    val mainSourceIsDescriptor = normalizedSource?.let(::isDescriptorSource) == true

    // Never hand the active player's descriptor to the secondary decoder. Android's content URI
    // fallback deliberately detaches an fd for main playback, and sharing that fd would also share
    // seek state. If we cannot reopen the original content URI independently, ThumbFast stays
    // unavailable for that source rather than risking main-player stutter/corruption.
    val usableSource =
      when {
        normalizedSource == null -> null
        mainSourceIsDescriptor && normalizedContentUri == null -> null
        else -> normalizedSource
      }
    val sourceIdentity =
      usableSource?.let {
        if (mainSourceIsDescriptor) {
          "content:$normalizedContentUri"
        } else {
          buildString {
            append(it)
            append('\u0000')
            append(normalizedUserAgent)
            append('\u0000')
            append(normalizedHeaders)
          }
        }
      }

    var sourceChanged = false
    synchronized(lock) {
      if (closed) return

      if (usableSource != null && sourceIdentity != null) {
        sourceChanged = currentSourceIdentity != sourceIdentity
        if (sourceChanged) {
          currentSourceIdentity = sourceIdentity
          sourceEpoch++
        }
        val pending =
          Request(
            source = usableSource,
            contentUri = normalizedContentUri,
            sourceIdentity = sourceIdentity,
            userAgent = normalizedUserAgent,
            httpHeaders = normalizedHeaders,
            positionSeconds = clampedPosition.toDouble(),
            sourceEpoch = sourceEpoch,
          )
        latestRequest = pending
        if (nativeHandle == 0L) {
          ensureInitializedLocked()
        } else {
          resolveNativeRequestLocked(pending)?.let { resolved ->
            // Keep the tiny native enqueue call under the lifecycle lock. That closes the window
            // where teardown could destroy a copied handle immediately before JNI used it.
            sendRequest(nativeHandle, resolved)
          }
        }
      } else {
        latestRequest = null
      }
    }

    _state.update { current ->
      current.copy(
        visible = true,
        positionSeconds = clampedPosition,
        fraction = fraction,
        bitmap = if (sourceChanged || usableSource == null) null else current.bitmap,
        isLoading = usableSource != null && (sourceChanged || current.bitmap == null),
      )
    }
  }

  /**
   * Hides the preview and cancels the current target without destroying the warm secondary core.
   * A new playback generation retires the old core entirely so a detached content fd can be closed
   * only after libmpv has stopped using it, and the next media item always receives fresh I/O state.
   */
  fun clear(resetSource: Boolean = false) {
    if (!resetSource) {
      synchronized(lock) {
        if (closed) return
        latestRequest = null
        if (nativeHandle != 0L) NativeThumbFast.clear(nativeHandle)
      }
      _state.value = ThumbFastPreviewState()
      return
    }

    val retired = retireEngine(resetSource = true, markClosed = false)
    destroyRetired(retired)
    _state.value = ThumbFastPreviewState()
  }

  fun close() {
    val retired = retireEngine(resetSource = true, markClosed = true)
    destroyRetired(retired)
    _state.value = ThumbFastPreviewState()
  }

  private fun retireEngine(
    resetSource: Boolean,
    markClosed: Boolean,
  ): RetiredEngine =
    synchronized(lock) {
      if (closed) return RetiredEngine(0L, null, null, null)
      if (markClosed) closed = true
      latestRequest = null
      if (resetSource) {
        currentSourceIdentity = null
        sourceEpoch++
        engineEpoch++
      }
      RetiredEngine(
        handle = nativeHandle.also { nativeHandle = 0L },
        contentFd = ownedContentFd.also { ownedContentFd = null },
        framePumpJob = framePumpJob.also { framePumpJob = null },
        initializeJob = initializeJob.also { initializeJob = null },
      ).also {
        ownedContentUri = null
      }
    }

  private fun destroyRetired(retired: RetiredEngine) {
    retired.framePumpJob?.cancel()
    retired.initializeJob?.cancel()
    if (retired.handle != 0L) {
      NativeThumbFast.clear(retired.handle)
      NativeThumbFast.destroy(retired.handle)
    }
    runCatching { retired.contentFd?.close() }
  }

  private fun ensureInitializedLocked() {
    if (initializeJob?.isActive == true || closed) return
    val creationEpoch = engineEpoch
    initializeJob =
      scope.launch(Dispatchers.IO) {
        val snapshot = synchronized(lock) { latestRequest }
        if (snapshot == null) return@launch

        val contentFd = openOwnedContentFd(snapshot)
        if (isDescriptorSource(snapshot.source) && contentFd == null) {
          Log.w(TAG, "Could not open an independent descriptor for ThumbFast content source")
          _state.update { it.copy(isLoading = false) }
          return@launch
        }

        val createdHandle = NativeThumbFast.create()
        var shouldDestroy = false
        var shouldStartPump = false
        synchronized(lock) {
          val latest = latestRequest
          if (
            closed ||
            creationEpoch != engineEpoch ||
            createdHandle == 0L ||
            latest == null ||
            latest.sourceIdentity != snapshot.sourceIdentity
          ) {
            shouldDestroy = createdHandle != 0L
          } else {
            nativeHandle = createdHandle
            ownedContentFd = contentFd
            ownedContentUri = snapshot.contentUri
            resolveNativeRequestLocked(latest)?.let { resolved ->
              sendRequest(createdHandle, resolved)
              shouldStartPump = true
            }
          }
        }

        if (shouldDestroy || !shouldStartPump) {
          if (createdHandle != 0L) NativeThumbFast.destroy(createdHandle)
          runCatching { contentFd?.close() }
          if (createdHandle == 0L) {
            Log.e(TAG, "Native ThumbFast engine could not be created")
            _state.update { it.copy(isLoading = false) }
          }
          return@launch
        }

        startFramePump(createdHandle)
      }
  }

  private fun openOwnedContentFd(request: Request): ParcelFileDescriptor? {
    if (!isDescriptorSource(request.source)) return null
    val contentUri = request.contentUri ?: return null
    return runCatching {
      appContext.contentResolver.openFileDescriptor(Uri.parse(contentUri), "r")
    }.onFailure { error ->
      Log.w(TAG, "Failed to open independent ThumbFast content descriptor", error)
    }.getOrNull()
  }

  private fun resolveNativeRequestLocked(request: Request): Request? {
    if (!isDescriptorSource(request.source)) return request
    val contentFd = ownedContentFd ?: return null
    if (ownedContentUri != request.contentUri) return null
    return request.copy(source = "fd://${contentFd.fd}")
  }

  private fun sendRequest(
    handle: Long,
    request: Request,
  ) {
    NativeThumbFast.request(
      handle = handle,
      source = request.source,
      userAgent = request.userAgent,
      httpHeaders = request.httpHeaders,
      positionSeconds = request.positionSeconds,
      sourceEpoch = request.sourceEpoch,
    )
  }

  private fun startFramePump(handle: Long) {
    synchronized(lock) {
      if (closed || nativeHandle != handle || framePumpJob?.isActive == true) return
      framePumpJob =
        scope.launch(Dispatchers.IO) {
          var serial = 0
          while (isActive) {
            val frame = NativeThumbFast.waitForFrame(handle, serial, FRAME_WAIT_TIMEOUT_MS) ?: continue
            if (!isActive || frame.size < FRAME_HEADER_SIZE) continue

            val nextSerial = frame[0]
            val frameSourceEpoch = frame[1]
            val width = frame[2]
            val height = frame[3]
            if (nextSerial <= serial || width <= 0 || height <= 0) continue
            val pixelCount = width * height
            if (pixelCount <= 0 || frame.size != FRAME_HEADER_SIZE + pixelCount) continue

            serial = nextSerial
            val expectedSourceEpoch = synchronized(lock) { sourceEpoch }
            if (frameSourceEpoch != expectedSourceEpoch) continue

            val bitmap =
              Bitmap.createBitmap(
                frame,
                FRAME_HEADER_SIZE,
                width,
                width,
                height,
                Bitmap.Config.ARGB_8888,
              )

            _state.update { current ->
              if (!current.visible) {
                current
              } else {
                current.copy(bitmap = bitmap, isLoading = false)
              }
            }
          }
        }
    }
  }

  private fun isContentUri(value: String): Boolean =
    runCatching { Uri.parse(value).scheme.equals("content", ignoreCase = true) }.getOrDefault(false)

  private fun isDescriptorSource(value: String): Boolean =
    value.startsWith("fd://", ignoreCase = true) ||
      value.startsWith("fdclose://", ignoreCase = true) ||
      value.startsWith("/proc/self/fd/", ignoreCase = true)

  private companion object {
    const val TAG = "ThumbFastPreview"
    const val FRAME_HEADER_SIZE = 4
    const val FRAME_WAIT_TIMEOUT_MS = 250
  }
}
