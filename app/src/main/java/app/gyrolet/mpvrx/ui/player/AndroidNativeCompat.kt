package app.gyrolet.mpvrx.ui.player

import android.os.Build
import android.util.Log

/** Process-level compatibility switches required by the currently bundled libmpv. */
internal object AndroidNativeCompat {
  private const val TAG = "MpvAndroidCompat"
  private const val ANDROID_16_API = 36

  @Volatile
  private var mpvSubprocessWorkaroundApplied = false

  /**
   * Applies the Android 16 fdsan workaround before mpv can start yt-dlp or a script subprocess.
   * fdsan must stay disabled after the first raw clone because the child shares and mutates the
   * parent's ownership metadata; restoring the fatal level would only turn this into a later crash.
   */
  fun applyMpvSubprocessWorkaround() {
    if (Build.VERSION.SDK_INT < ANDROID_16_API || mpvSubprocessWorkaroundApplied) return

    synchronized(this) {
      if (mpvSubprocessWorkaroundApplied) return

      runCatching {
        System.loadLibrary("android_compat")
      }.onSuccess {
        mpvSubprocessWorkaroundApplied = true
        Log.w(TAG, "Applied Android 16 libmpv subprocess compatibility workaround")
      }.onFailure { error ->
        Log.e(TAG, "Failed to apply Android 16 libmpv subprocess workaround", error)
      }
    }
  }
}
