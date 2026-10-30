/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Utility object that wraps Android's [PowerManager.getThermalHeadroom] API
 * (available from Android 11 / API 30 onward) to give the player pipeline a
 * proactive view of the device's thermal margin.
 *
 * Android reports the fraction of the thermal envelope in use: larger values mean more
 * thermal pressure, with 1.0 at the severe-throttling threshold. Share samples between the
 * ambient and Anime4K callers so their requests cannot trip the platform's rate limit.
 */
object ThermalMonitor {
  private const val TAG = "ThermalMonitor"

  /**
   * Seconds of thermal forecast requested from [PowerManager.getThermalHeadroom].
   * 10 s is a reasonable balance between responsiveness and API cost.
   */
  private const val FORECAST_SECONDS = 10
  private const val SAMPLE_INTERVAL_MS = 10_000L
  private var lastSampleAtMs = -SAMPLE_INTERVAL_MS
  private var lastHeadroom = 0f

  /**
   * Returns thermal-envelope use, including values above 1.0. Keep the last finite sample
   * when Android returns NaN (unsupported or rate-limited); before any sample, apply no cap.
   */
  @Synchronized
  fun getHeadroom(context: Context): Float {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0f
    val now = SystemClock.elapsedRealtime()
    if (now - lastSampleAtMs < SAMPLE_INTERVAL_MS) return lastHeadroom
    lastSampleAtMs = now
    try {
      val pm = context.getSystemService(PowerManager::class.java)
      val sample = pm?.getThermalHeadroom(FORECAST_SECONDS)
      if (sample != null && sample.isFinite()) lastHeadroom = sample.coerceAtLeast(0f)
    } catch (e: Exception) {
      Log.w(TAG, "Thermal headroom unavailable: ${e.message}")
    }
    return lastHeadroom
  }

  /**
   * Maps [headroom] to a capped ambient-shader sample budget.
   *
   * The caps are tuned so that under thermal pressure the GPU completes each
   * frame well within the display's vsync budget, avoiding thermal runaway:
   *
   * | Headroom  | Severity | Max budget |
   * |-----------|----------|------------|
   * | < 0.60    | None     | uncapped   |
   * | 0.60–0.80 | Mild     | 12         |
   * | 0.80–1.00 | Moderate | 8          |
   * | ≥ 1.00    | Severe   | 4 (Eco)    |
   *
   * @param baselineBudget The sample budget configured by the user (e.g. 24).
   * @param headroom The current thermal headroom from [getHeadroom].
   * @return The effective budget to use, always ≤ [baselineBudget].
   */
  fun clampAmbientSampleBudget(
    baselineBudget: Int,
    headroom: Float,
  ): Int =
    when {
      headroom >= 1.00f -> baselineBudget.coerceAtMost(4)
      headroom >= 0.80f -> baselineBudget.coerceAtMost(8)
      headroom >= 0.60f -> baselineBudget.coerceAtMost(12)
      else -> baselineBudget
    }

  /**
   * Returns **true** when the device's thermal state is severe enough that
   * Anime4K should be reduced or disabled to avoid worsening throttling.
   *
   * Anime4K requires significant GPU shader compute (especially in HQ/UHQ
   * modes) and is one of the largest contributors to thermal load.
   */
  fun shouldThrottleAnime4K(headroom: Float): Boolean = headroom >= 0.80f
}
