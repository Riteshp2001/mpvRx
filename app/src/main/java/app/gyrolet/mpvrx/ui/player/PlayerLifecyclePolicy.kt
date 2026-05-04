package app.gyrolet.mpvrx.ui.player

internal object PlayerLifecyclePolicy {
  fun shouldPauseOnPause(
    automaticBackgroundPlayback: Boolean,
    manualBackgroundPlayback: Boolean,
    isUserFinishing: Boolean,
    isInPictureInPictureMode: Boolean,
  ): Boolean {
    if (isInPictureInPictureMode) return false

    return (!automaticBackgroundPlayback && !manualBackgroundPlayback) ||
      (isUserFinishing && !manualBackgroundPlayback)
  }

  fun shouldKeepBackgroundPlaybackAliveOnDestroy(
    manualBackgroundPlayback: Boolean,
    isUserFinishing: Boolean,
    isFinishing: Boolean,
  ): Boolean = manualBackgroundPlayback && (isUserFinishing || isFinishing)

  fun shouldTreatStopAsPipDismissal(
    wasInPictureInPictureMode: Boolean,
    isChangingConfigurations: Boolean,
    manualBackgroundPlayback: Boolean,
    alreadyHandled: Boolean,
  ): Boolean =
    wasInPictureInPictureMode &&
      !isChangingConfigurations &&
      !manualBackgroundPlayback &&
      !alreadyHandled

  fun shouldStartAutomaticBackgroundPlaybackOnStop(
    automaticBackgroundPlayback: Boolean,
    manualBackgroundPlayback: Boolean,
    isUserFinishing: Boolean,
    isFinishing: Boolean,
    isInPictureInPictureMode: Boolean,
  ): Boolean =
    automaticBackgroundPlayback &&
      !manualBackgroundPlayback &&
      !isUserFinishing &&
      !isFinishing &&
      !isInPictureInPictureMode
}
