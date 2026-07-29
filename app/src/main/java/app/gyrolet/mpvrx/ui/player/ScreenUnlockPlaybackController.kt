/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player

internal class ScreenUnlockPlaybackController {
  private var pendingResumeAfterUnlock = false

  fun onScreenTurnedOff(
    autoplayAfterScreenUnlockEnabled: Boolean,
    wasPlayingBeforePause: Boolean,
    isCurrentlyPaused: Boolean?,
    backgroundPlaybackActive: Boolean,
    isUserFinishing: Boolean,
    isFinishing: Boolean,
  ) {
    val wasPlayingWhenScreenTurnedOff = wasPlayingBeforePause || isCurrentlyPaused == false

    pendingResumeAfterUnlock =
      autoplayAfterScreenUnlockEnabled &&
        wasPlayingWhenScreenTurnedOff &&
        !backgroundPlaybackActive &&
        !isUserFinishing &&
        !isFinishing
  }

  fun hasPendingResume(): Boolean = pendingResumeAfterUnlock

  fun consumeResumeAfterUnlockIfReady(isDeviceLocked: Boolean): Boolean {
    if (!pendingResumeAfterUnlock || isDeviceLocked) return false

    pendingResumeAfterUnlock = false
    return true
  }
}
