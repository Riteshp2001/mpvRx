/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.thumbfast

import android.util.Log

/** Thin JNI bridge to the isolated libmpv preview core. */
internal object NativeThumbFast {
  private const val TAG = "NativeThumbFast"

  private val available: Boolean by lazy {
    runCatching {
      System.loadLibrary("thumbfast_preview")
      true
    }.onFailure { error ->
      Log.e(TAG, "Failed to load native ThumbFast preview engine", error)
    }.getOrDefault(false)
  }

  fun create(): Long = if (available) nativeCreate0() else 0L

  fun request(
    handle: Long,
    source: String,
    userAgent: String,
    httpHeaders: String,
    positionSeconds: Double,
    sourceEpoch: Int,
  ) {
    if (handle == 0L || !available) return
    nativeRequest0(handle, source, userAgent, httpHeaders, positionSeconds, sourceEpoch)
  }

  fun clear(handle: Long) {
    if (handle == 0L || !available) return
    nativeClear0(handle)
  }

  fun waitForFrame(
    handle: Long,
    afterSerial: Int,
    timeoutMs: Int,
  ): IntArray? =
    if (handle == 0L || !available) {
      null
    } else {
      nativeWaitForFrame0(handle, afterSerial, timeoutMs)
    }

  fun destroy(handle: Long) {
    if (handle == 0L || !available) return
    nativeDestroy0(handle)
  }

  private external fun nativeCreate0(): Long

  private external fun nativeRequest0(
    handle: Long,
    source: String,
    userAgent: String,
    httpHeaders: String,
    positionSeconds: Double,
    sourceEpoch: Int,
  )

  private external fun nativeClear0(handle: Long)

  private external fun nativeWaitForFrame0(
    handle: Long,
    afterSerial: Int,
    timeoutMs: Int,
  ): IntArray?

  private external fun nativeDestroy0(handle: Long)
}
