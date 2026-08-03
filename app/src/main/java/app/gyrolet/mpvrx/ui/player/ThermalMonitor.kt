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
import android.util.Log

/**
 * Utility object that wraps Android's [PowerManager.getThermalHeadroom] API
 * (available from Android 11 / API 30 onward) to give the player pipeline a
 * proactive view of the device's thermal margin.
 *
 * Callers sample headroom at a low frequency (e.g. every 10 seconds) and use
 * the result to scale down GPU-intensive workloads — ambient shader sample
 * budgets, Anime4K quality tiers, frame-interpolation complexity — *before*
 * the OS is forced to hard-throttle CPU/GPU clocks.  Preventing thermal
 * throttling also prevents the visible frame-rate hitching and battery drain
 * that follow sustained SoC heat buildup.
 */
object ThermalMonitor {
  private const val TAG = "ThermalMonitor"

  /**
   * Seconds of thermal forecast requested from [PowerManager.getThermalHeadroom].
   * 10 s is a reasonable balance between responsiveness and API cost.
   */
  private const val FORECAST_SECONDS = 10

  /**
   * Returns the device's current thermal headroom as a value in **[0f, 1f]**.
   *
   * - **1.0** – device is cool; full quality is safe.
   * - **0.0** – device is at its thermal limit; quality should be at minimum.
   *
   * Falls back to **1.0f** (no restriction) on devices below API 30, or when
   * the API call fails for any reason (e.g. no thermal sensor support).
   */
  fun getHeadroom(context: Context): Float {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 1.0f
    return try {
      val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
      pm.getThermalHeadroom(FORECAST_SECONDS).coerceIn(0f, 1f)
    } catch (e: Exception) {
      Log.w(TAG, "Thermal headroom unavailable: ${e.message}")
      1.0f
    }
  }

  /**
   * Maps [headroom] to a capped ambient-shader sample budget.
   *
   * The caps are tuned so that under thermal pressure the GPU completes each
   * frame well within the display's vsync budget, avoiding thermal runaway:
   *
   * | Headroom  | Severity | Max budget |
   * |-----------|----------|------------|
   * | ≥ 0.80    | None     | uncapped   |
   * | 0.60–0.80 | Mild     | 12         |
   * | 0.40–0.60 | Moderate | 8          |
   * | < 0.40    | Severe   | 4 (Eco)    |
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
      headroom < 0.40f -> baselineBudget.coerceAtMost(4)
      headroom < 0.60f -> baselineBudget.coerceAtMost(8)
      headroom < 0.80f -> baselineBudget.coerceAtMost(12)
      else -> baselineBudget
    }

  /**
   * Returns **true** when the device's thermal state is severe enough that
   * Anime4K should be reduced or disabled to avoid worsening throttling.
   *
   * Anime4K requires significant GPU shader compute (especially in HQ/UHQ
   * modes) and is one of the largest contributors to thermal load.
   */
  fun shouldThrottleAnime4K(headroom: Float): Boolean = headroom < 0.40f
}
