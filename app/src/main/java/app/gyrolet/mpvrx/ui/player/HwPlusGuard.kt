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
import androidx.annotation.StringRes
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import app.gyrolet.mpvrx.ui.components.AppToast
import app.gyrolet.mpvrx.ui.components.AppToastType
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * HW+ (direct MediaCodec) hands frames to the renderer as GPU images, so mpv skips CPU video
 * filters (`vf`) and shader passes hooked on the LUMA/CHROMA planes for them.
 */
internal object HwPlusGuard {
  private const val SWITCH_TIMEOUT_MS = 3_000L
  private const val SWITCH_POLL_MS = 50L
  private val scope = MainScope()

  private fun inUse(): Boolean = PlaybackSession.getPropertyString("hwdec-current") == Decoder.HWPlus.value

  /**
   * Returns true, and says why in a toast, when HW+ would silently ignore [feature].
   * The toast can switch to HW decoding and then run [retry].
   */
  fun blocks(context: Context, @StringRes feature: Int, retry: () -> Unit): Boolean {
    if (!inUse()) return false
    val toast =
      AppToast.makeText(
        context,
        context.getString(R.string.player_hwplus_unsupported, context.getString(feature)),
        AppToast.LENGTH_LONG,
      ).withType(AppToastType.Warning)
    if (!MpvConfigOverridePolicy.isOwnedByMpvConf("hwdec")) {
      toast.withAction(context.getString(R.string.player_hwplus_use_hw)) {
        PlaybackSession.setPropertyString("hwdec", Decoder.HW.value)
        scope.launch {
          // A filter added before the swap would get a HW+ frame and stay disabled.
          val switched = withTimeoutOrNull(SWITCH_TIMEOUT_MS) { while (inUse()) delay(SWITCH_POLL_MS) } != null
          if (switched) retry()
        }
      }
    }
    toast.show()
    return true
  }
}
