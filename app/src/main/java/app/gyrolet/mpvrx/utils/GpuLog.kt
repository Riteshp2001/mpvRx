package app.gyrolet.mpvrx.utils

import android.util.Log
import app.gyrolet.mpvrx.preferences.GpuDriverPreferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Gated logger for the GPU driver subsystem.
 *
 * v/d/i are inline + lambda-based: when the verboseLogging preference is OFF
 * the message lambda never runs, so we pay nothing for string templating or
 * the `Log.*` JNI hop in the hot path. w/e are unconditional because
 * warnings/errors indicate problems users need to know about.
 *
 * Pattern: `GpuLog.d(TAG) { "cache hit for $id" }`
 */
object GpuLog : KoinComponent {
    @PublishedApi internal val prefs: GpuDriverPreferences by inject()

    inline fun v(tag: String, message: () -> String) {
        if (prefs.verboseLogging.get()) Log.v(tag, message())
    }

    inline fun d(tag: String, message: () -> String) {
        if (prefs.verboseLogging.get()) Log.d(tag, message())
    }

    inline fun i(tag: String, message: () -> String) {
        if (prefs.verboseLogging.get()) Log.i(tag, message())
    }

    fun w(tag: String, message: String, t: Throwable? = null) {
        if (t != null) Log.w(tag, message, t) else Log.w(tag, message)
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, message, t) else Log.e(tag, message)
    }
}
