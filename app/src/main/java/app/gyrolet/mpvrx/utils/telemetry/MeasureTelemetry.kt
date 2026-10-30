/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.utils.telemetry

import android.app.Application
import android.content.Context
import android.util.Log
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.preference.defaultSharedPreferences
import app.gyrolet.mpvrx.presentation.crash.CrashMonitor
import sh.measure.android.Measure
import sh.measure.android.config.MeasureConfig

/** Measure is initialized only in the main app process and is stopped until consent. */
internal object MeasureTelemetry {
  private const val TAG = "MeasureTelemetry"
  private var initialized = false
  private var application: Application? = null

  @Synchronized
  fun initialize(application: Application, enabled: Boolean) {
    if (!BuildConfig.MEASURE_CONFIGURED || initialized) return
    runCatching {
      Measure.init(application, MeasureConfig(autoStart = false))
      this.application = application
      initialized = true
      if (enabled) Measure.start()
      // mpvRx's local crash recovery remains the topmost handler. Measure is its fallback.
      CrashMonitor.ensureInstalled(application)
    }.onFailure { Log.e(TAG, "Measure SDK initialization failed", it) }
  }

  @Synchronized
  fun setEnabled(enabled: Boolean): Boolean {
    if (!initialized) return false
    return runCatching {
      if (enabled) Measure.start() else Measure.stop()
      // The SDK can install an exception handler on start; wrap it again for mpvRx.
      application?.let(CrashMonitor::ensureInstalled)
    }.onFailure { Log.e(TAG, "Could not update Measure consent", it) }.isSuccess
  }

  fun isEnabled(context: Context): Boolean =
    BuildConfig.MEASURE_CONFIGURED &&
      context.defaultSharedPreferences().getBoolean(AdvancedPreferences.MEASURE_ENABLED_KEY, false)
}
