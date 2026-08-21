/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

internal object PlayerLifecyclePolicy {
  /** Auto-PiP owns Home/Back navigation whenever a playable video can enter it. */
  fun shouldEnterPipOnNavigation(
    autoPipEnabled: Boolean,
    mediaReady: Boolean,
    isAudioMedia: Boolean,
    isActivityUnavailable: Boolean,
    isAlreadyInPip: Boolean,
  ): Boolean =
    autoPipEnabled &&
      mediaReady &&
      !isAudioMedia &&
      !isActivityUnavailable &&
      !isAlreadyInPip

  fun shouldPauseOnPause(
    backgroundPlaybackEnabled: Boolean,
    backgroundPlaybackSessionActive: Boolean,
    isUserFinishing: Boolean,
    isInPictureInPictureMode: Boolean,
    isScreenOffOrLocked: Boolean,
  ): Boolean {
    if (isUserFinishing && !backgroundPlaybackSessionActive) return true
    if (isInPictureInPictureMode && !isScreenOffOrLocked) return false

    return !backgroundPlaybackEnabled
  }

  fun shouldStartBackgroundPlaybackOnBack(
    backgroundPlaybackEnabled: Boolean,
    mediaReady: Boolean,
  ): Boolean = backgroundPlaybackEnabled && mediaReady

  fun shouldKeepBackgroundPlaybackAliveOnDestroy(
    backgroundPlaybackEnabled: Boolean,
    backgroundPlaybackSessionActive: Boolean,
  ): Boolean = backgroundPlaybackEnabled && backgroundPlaybackSessionActive

  fun shouldTreatStopAsPipDismissal(
    wasInPictureInPictureMode: Boolean,
    isInPictureInPictureMode: Boolean,
    isChangingConfigurations: Boolean,
    backgroundPlaybackEnabled: Boolean,
    isScreenOffOrLocked: Boolean,
    alreadyHandled: Boolean,
  ): Boolean =
    wasInPictureInPictureMode &&
      !isInPictureInPictureMode &&
      !isChangingConfigurations &&
      !backgroundPlaybackEnabled &&
      !isScreenOffOrLocked &&
      !alreadyHandled

  fun shouldStartBackgroundPlaybackOnStop(
    backgroundPlaybackEnabled: Boolean,
    backgroundPlaybackSessionActive: Boolean,
    isUserFinishing: Boolean,
    isFinishing: Boolean,
    isInPictureInPictureMode: Boolean,
    isScreenOffOrLocked: Boolean,
  ): Boolean =
    backgroundPlaybackEnabled &&
      !backgroundPlaybackSessionActive &&
      !isUserFinishing &&
      !isFinishing &&
      (!isInPictureInPictureMode || isScreenOffOrLocked)
}
