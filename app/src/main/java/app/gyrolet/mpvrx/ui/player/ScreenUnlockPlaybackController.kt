/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
