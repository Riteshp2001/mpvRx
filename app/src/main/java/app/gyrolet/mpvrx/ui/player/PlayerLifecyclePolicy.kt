/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal object PlaybackActivityOwner {
  private val sequence = AtomicLong()
  private val ownershipLock = ReentrantReadWriteLock(true)
  private var activeToken = 0L

  fun claim(): Long =
    ownershipLock.write {
      sequence.incrementAndGet().also { token -> activeToken = token }
    }

  fun owns(token: Long): Boolean = ownershipLock.read { token > 0L && activeToken == token }

  fun beginRequest(
    token: Long,
    action: () -> Unit,
  ): Boolean =
    ownershipLock.write {
      if (token <= 0L || activeToken != token) return@write false
      action()
      true
    }

  fun <T> runIfOwner(
    token: Long,
    staleValue: T,
    action: () -> T,
  ): T =
    ownershipLock.read {
      if (token <= 0L || activeToken != token) staleValue else action()
    }
}

internal enum class PlaybackLaunchPreparation {
  INITIALIZE_CORE,
  REUSE_CORE,
  ATTACH_CURRENT_MEDIA,
  REPLACE_CURRENT_MEDIA,
  WAIT_FOR_STOP,
}

internal object PlayerLifecyclePolicy {
  fun launchPreparation(
    coreInitialized: Boolean,
    phase: PlaybackPhase,
    attachCurrentMedia: Boolean,
  ): PlaybackLaunchPreparation {
    if (!coreInitialized || phase == PlaybackPhase.UNINITIALIZED) {
      return PlaybackLaunchPreparation.INITIALIZE_CORE
    }
    if (attachCurrentMedia && phase in setOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)) {
      return PlaybackLaunchPreparation.ATTACH_CURRENT_MEDIA
    }
    return when (phase) {
      PlaybackPhase.LOADING,
      PlaybackPhase.READY,
      PlaybackPhase.BACKGROUND,
      -> PlaybackLaunchPreparation.REPLACE_CURRENT_MEDIA
      PlaybackPhase.STOPPING -> PlaybackLaunchPreparation.WAIT_FOR_STOP
      PlaybackPhase.INITIALIZING,
      PlaybackPhase.IDLE,
      PlaybackPhase.ERROR,
      -> PlaybackLaunchPreparation.REUSE_CORE
      PlaybackPhase.UNINITIALIZED -> PlaybackLaunchPreparation.INITIALIZE_CORE
    }
  }

  /** Auto-PiP owns Home navigation (or Back navigation when mini player is disabled) whenever a playable video can enter it. */
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

  /** When mini player is enabled, Back should hand off to the mini player instead of entering PiP. */
  fun shouldEnterPipOnBack(
    miniPlayerEnabled: Boolean,
    autoPipEnabled: Boolean,
    mediaReady: Boolean,
    isAudioMedia: Boolean,
    isActivityUnavailable: Boolean,
    isAlreadyInPip: Boolean,
  ): Boolean =
    !miniPlayerEnabled &&
      shouldEnterPipOnNavigation(
        autoPipEnabled = autoPipEnabled,
        mediaReady = mediaReady,
        isAudioMedia = isAudioMedia,
        isActivityUnavailable = isActivityUnavailable,
        isAlreadyInPip = isAlreadyInPip,
      )

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
    isActivityFinishing: Boolean,
    isChangingConfigurations: Boolean,
    isScreenOffOrLocked: Boolean,
    alreadyHandled: Boolean,
  ): Boolean =
    wasInPictureInPictureMode &&
      (!isInPictureInPictureMode || isActivityFinishing) &&
      !isChangingConfigurations &&
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
