/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.thumbfast

import android.graphics.Bitmap
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
) {
  private data class Request(
    val source: String,
    val userAgent: String,
    val httpHeaders: String,
    val positionSeconds: Double,
    val sourceEpoch: Int,
  )

  private val lock = Any()
  private val _state = MutableStateFlow(ThumbFastPreviewState())
  val state: StateFlow<ThumbFastPreviewState> = _state.asStateFlow()

  private var nativeHandle = 0L
  private var initializeJob: Job? = null
  private var framePumpJob: Job? = null
  private var latestRequest: Request? = null
  private var currentSourceKey: String? = null
  private var sourceEpoch = 0
  private var closed = false

  fun request(
    source: String?,
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
    val normalizedUserAgent = userAgent.orEmpty()
    val normalizedHeaders = httpHeaders.orEmpty()
    val sourceKey =
      normalizedSource?.let {
        buildString {
          append(it)
          append('\u0000')
          append(normalizedUserAgent)
          append('\u0000')
          append(normalizedHeaders)
        }
      }

    var request: Request? = null
    var handle = 0L
    var sourceChanged = false
    synchronized(lock) {
      if (closed) return

      if (normalizedSource != null && sourceKey != null) {
        sourceChanged = currentSourceKey != sourceKey
        if (sourceChanged) {
          currentSourceKey = sourceKey
          sourceEpoch++
        }
        request =
          Request(
            source = normalizedSource,
            userAgent = normalizedUserAgent,
            httpHeaders = normalizedHeaders,
            positionSeconds = clampedPosition.toDouble(),
            sourceEpoch = sourceEpoch,
          )
        latestRequest = request
        handle = nativeHandle
        if (handle == 0L) ensureInitializedLocked()
      } else {
        latestRequest = null
      }
    }

    _state.update { current ->
      current.copy(
        visible = true,
        positionSeconds = clampedPosition,
        fraction = fraction,
        bitmap = if (sourceChanged) null else current.bitmap,
        isLoading = normalizedSource != null && (sourceChanged || current.bitmap == null),
      )
    }

    request?.let { pending ->
      if (handle != 0L) sendRequest(handle, pending)
    }
  }

  /**
   * Hides the preview and cancels the current target without destroying the warm secondary core.
   * [resetSource] is used on a new playback generation so the next scrub always reopens media,
   * even when the new item happens to reuse the same URL and authentication values.
   */
  fun clear(resetSource: Boolean = false) {
    val handle =
      synchronized(lock) {
        if (closed) return
        latestRequest = null
        if (resetSource) {
          currentSourceKey = null
          sourceEpoch++
        }
        nativeHandle
      }
    if (handle != 0L) NativeThumbFast.clear(handle)
    _state.value = ThumbFastPreviewState()
  }

  fun close() {
    val handle =
      synchronized(lock) {
        if (closed) return
        closed = true
        latestRequest = null
        currentSourceKey = null
        sourceEpoch++
        nativeHandle.also { nativeHandle = 0L }
      }

    // Cancel the Kotlin poller first. The JNI wait is bounded; nativeDestroy also waits for any
    // in-flight JNI waiter to leave before freeing the engine object.
    framePumpJob?.cancel()
    initializeJob?.cancel()
    framePumpJob = null
    initializeJob = null

    if (handle != 0L) {
      NativeThumbFast.clear(handle)
      NativeThumbFast.destroy(handle)
    }
    _state.value = ThumbFastPreviewState()
  }

  private fun ensureInitializedLocked() {
    if (initializeJob?.isActive == true || closed) return
    initializeJob =
      scope.launch(Dispatchers.IO) {
        val createdHandle = NativeThumbFast.create()
        var pending: Request? = null
        var shouldDestroy = false
        synchronized(lock) {
          if (closed) {
            shouldDestroy = createdHandle != 0L
          } else if (createdHandle != 0L) {
            nativeHandle = createdHandle
            pending = latestRequest
          }
        }

        if (shouldDestroy) {
          NativeThumbFast.destroy(createdHandle)
          return@launch
        }

        if (createdHandle == 0L) {
          Log.e(TAG, "Native ThumbFast engine could not be created")
          _state.update { it.copy(isLoading = false) }
          return@launch
        }

        pending?.let { sendRequest(createdHandle, it) }
        startFramePump(createdHandle)
      }
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

  private companion object {
    const val TAG = "ThumbFastPreview"
    const val FRAME_HEADER_SIZE = 4
    const val FRAME_WAIT_TIMEOUT_MS = 250
  }
}
