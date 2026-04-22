package app.gyrolet.mpvrx.ui.player

internal class ScreenUnlockPlaybackController {
  private var pendingResumeAfterUnlock = false

  fun onScreenTurnedOff(
    autoplayAfterScreenUnlockEnabled: Boolean,
    wasPlayingBeforePause: Boolean,
    isCurrentlyPaused: Boolean?,
    backgroundPlaybackActive: Boolean,
    isInPictureInPictureMode: Boolean,
    isUserFinishing: Boolean,
    isFinishing: Boolean,
  ) {
    val wasPlayingWhenScreenTurnedOff = wasPlayingBeforePause || isCurrentlyPaused == false

    pendingResumeAfterUnlock =
      autoplayAfterScreenUnlockEnabled &&
        wasPlayingWhenScreenTurnedOff &&
        !backgroundPlaybackActive &&
        !isInPictureInPictureMode &&
        !isUserFinishing &&
        !isFinishing
  }

  fun consumeResumeAfterUnlockIfReady(isDeviceLocked: Boolean): Boolean {
    if (!pendingResumeAfterUnlock || isDeviceLocked) return false

    pendingResumeAfterUnlock = false
    return true
  }
}
