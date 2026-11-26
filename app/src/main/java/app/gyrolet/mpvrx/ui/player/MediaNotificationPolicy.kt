/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.support.v4.media.session.PlaybackStateCompat

/** The media notification's buttons, in the order they are posted. */
internal enum class MediaNotificationButton {
  PREVIOUS,
  PLAY,
  PAUSE,
  NEXT,

  /** The item is not a favorite yet: an empty heart that adds it. */
  FAVORITE,

  /** The item is a favorite: a filled heart that removes it. */
  FAVORITED,
  CLOSE,
}

internal data class MediaNotificationLayout(
  val buttons: List<MediaNotificationButton>,
  /** Indices into [buttons] that stay visible while the notification is collapsed. */
  val compactViewIndices: List<Int>,
)

internal enum class PreviousAction {
  PREVIOUS_ITEM,
  RESTART_ITEM,
}

internal enum class ControllerStopAction {
  PAUSE,
  END_PLAYBACK,
}

/**
 * Who drives playback while the notification is up. PlayerActivity keeps [activityForeground] set
 * while it is started on an unlocked screen, which includes its picture-in-picture window; Home,
 * Back to the browser, a dismissed PiP window or a locked screen hand playback to the service.
 */
internal data class MediaNotificationOwnership(
  val foregroundReady: Boolean,
  val mpvAccessReleased: Boolean,
  val activityForeground: Boolean,
  val handingBackToActivity: Boolean,
) {
  /** Notification and media-control buttons work, with or without the player on screen. */
  val canHandleTransport: Boolean
    get() = foregroundReady && !mpvAccessReleased && (activityForeground || !handingBackToActivity)

  /** The service alone owns playback: no player on screen and no handoff to one under way. */
  val canHandleDetachedTransport: Boolean
    get() = foregroundReady && !mpvAccessReleased && !activityForeground && !handingBackToActivity

  /** A controller's stop or a swiped-away notification pauses a player that is on screen. */
  val stopAction: ControllerStopAction
    get() = if (activityForeground) ControllerStopAction.PAUSE else ControllerStopAction.END_PLAYBACK
}

/** What tapping the notification opens: the player, in audio or video mode, for this item. */
internal data class NotificationReturnTarget(
  val isAudio: Boolean,
  val requestCode: Int,
  val mimeType: String?,
  val uri: String?,
  val title: String,
  val identifier: String,
  val positionSeconds: Int,
  val audiobook: AudiobookPlaybackInfo?,
)

/** A notification tap whose live session is gone reopens its item as a normal launch. */
internal data class NotificationFallbackLaunch(
  val uri: String,
  val mimeType: String?,
)

/**
 * Decisions behind the media notification and its MediaSession, laid out like Media3's default
 * media notification. Kept free of Android services so every combination can be unit tested.
 */
internal object MediaNotificationPolicy {
  /** Media3's seekToPrevious: past this point Previous restarts the current item. */
  const val SEEK_TO_PREVIOUS_RESTART_THRESHOLD_SECONDS = 3.0
  const val VIDEO_CONTENT_INTENT_REQUEST_CODE = 1100
  const val AUDIO_CONTENT_INTENT_REQUEST_CODE = 1101
  const val FALLBACK_AUDIO_MIME_TYPE = "audio/*"

  private val ATTACHABLE_PHASES = setOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)

  /**
   * Previous, play/pause and next (the compact view), then favorite and close. Previous is always
   * there because it restarts an item with nothing before it; Next only when there is a next item.
   */
  fun layout(
    paused: Boolean,
    hasNext: Boolean,
    isFavorite: Boolean,
  ): MediaNotificationLayout {
    val buttons = mutableListOf(MediaNotificationButton.PREVIOUS)
    buttons += if (paused) MediaNotificationButton.PLAY else MediaNotificationButton.PAUSE
    if (hasNext) buttons += MediaNotificationButton.NEXT
    val compactViewIndices = buttons.indices.toList()
    buttons += customButtons(isFavorite)
    return MediaNotificationLayout(buttons, compactViewIndices)
  }

  /** Favorite then close: the two custom slots of the Android 13+ media controls, in this order. */
  fun customButtons(isFavorite: Boolean): List<MediaNotificationButton> =
    listOf(
      if (isFavorite) MediaNotificationButton.FAVORITED else MediaNotificationButton.FAVORITE,
      MediaNotificationButton.CLOSE,
    )

  fun sessionState(
    phase: PlaybackPhase,
    paused: Boolean,
    buffering: Boolean,
  ): Int =
    when (phase) {
      PlaybackPhase.LOADING, PlaybackPhase.INITIALIZING -> PlaybackStateCompat.STATE_BUFFERING
      PlaybackPhase.UNINITIALIZED -> PlaybackStateCompat.STATE_NONE
      PlaybackPhase.IDLE, PlaybackPhase.STOPPING -> PlaybackStateCompat.STATE_STOPPED
      PlaybackPhase.ERROR -> PlaybackStateCompat.STATE_ERROR
      PlaybackPhase.READY, PlaybackPhase.BACKGROUND ->
        when {
          paused -> PlaybackStateCompat.STATE_PAUSED
          buffering -> PlaybackStateCompat.STATE_BUFFERING
          else -> PlaybackStateCompat.STATE_PLAYING
        }
    }

  /** A stopped, empty or failed session offers no buttons at all. */
  fun hasMedia(sessionState: Int): Boolean =
    sessionState != PlaybackStateCompat.STATE_STOPPED &&
      sessionState != PlaybackStateCompat.STATE_NONE &&
      sessionState != PlaybackStateCompat.STATE_ERROR

  /** As Media3 publishes them: play or pause beside play/pause, and Previous whenever there is media. */
  fun sessionActions(
    sessionState: Int,
    paused: Boolean,
    durationMs: Long,
    hasNext: Boolean,
  ): Long {
    if (!hasMedia(sessionState)) return 0L
    var actions =
      PlaybackStateCompat.ACTION_PLAY_PAUSE or
        (if (paused) PlaybackStateCompat.ACTION_PLAY else PlaybackStateCompat.ACTION_PAUSE) or
        PlaybackStateCompat.ACTION_STOP or
        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
    if (durationMs > 0L) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
    if (hasNext) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
    return actions
  }

  /**
   * Wall-clock start of 1x playback for the elapsed-time chronometer, or null while it must not
   * run: paused, buffering, loading, at another speed, or on a live stream without a known length.
   */
  fun chronometerBaseMillis(
    nowMillis: Long,
    phase: PlaybackPhase,
    paused: Boolean,
    buffering: Boolean,
    playbackSpeed: Float,
    durationMs: Long,
    positionMs: Long,
  ): Long? {
    val playing = !paused && !buffering && (phase == PlaybackPhase.READY || phase == PlaybackPhase.BACKGROUND)
    if (!playing || playbackSpeed != 1f || durationMs <= 0L) return null
    return nowMillis - positionMs.coerceAtLeast(0L)
  }

  fun previousAction(
    hasPrevious: Boolean,
    positionSeconds: Double,
  ): PreviousAction =
    if (hasPrevious && positionSeconds <= SEEK_TO_PREVIOUS_RESTART_THRESHOLD_SECONDS) {
      PreviousAction.PREVIOUS_ITEM
    } else {
      PreviousAction.RESTART_ITEM
    }

  /** An item's declared kind wins; an unknown one keeps what the player last detected. */
  fun resolveIsAudio(
    declaredKind: DeclaredPlaybackMediaKind,
    fallback: Boolean,
  ): Boolean =
    when (declaredKind) {
      DeclaredPlaybackMediaKind.AUDIO -> true
      DeclaredPlaybackMediaKind.VIDEO -> false
      DeclaredPlaybackMediaKind.UNKNOWN -> fallback
    }

  /**
   * Where a notification tap returns the user. The player's own audio verdict ([notificationIsAudio],
   * e.g. a video file with only an audio track) or a declared audio item opens the audio player;
   * anything else opens the video player, both at the current item and position.
   */
  fun returnTarget(
    currentItem: PlaybackItem?,
    declaredKind: DeclaredPlaybackMediaKind?,
    notificationIsAudio: Boolean,
    mediaUri: String?,
    mediaTitle: String,
    mediaIdentifier: String,
    positionSeconds: Double,
  ): NotificationReturnTarget {
    val isAudio = notificationIsAudio || declaredKind == DeclaredPlaybackMediaKind.AUDIO
    return NotificationReturnTarget(
      isAudio = isAudio,
      requestCode = if (isAudio) AUDIO_CONTENT_INTENT_REQUEST_CODE else VIDEO_CONTENT_INTENT_REQUEST_CODE,
      mimeType = currentItem?.mimeType ?: FALLBACK_AUDIO_MIME_TYPE.takeIf { isAudio },
      uri = currentItem?.originalUri?.takeIf { it.isNotBlank() } ?: mediaUri,
      title = currentItem?.title?.takeIf { it.isNotBlank() } ?: mediaTitle,
      identifier = currentItem?.stableId?.takeIf { it.isNotBlank() } ?: mediaIdentifier,
      positionSeconds =
        (positionSeconds.takeIf { it.isFinite() && it > 0.0 }?.toLong() ?: 0L)
          .coerceIn(0L, Int.MAX_VALUE.toLong())
          .toInt(),
      audiobook = currentItem?.audiobook,
    )
  }

  /** Only a session that is loading or playing an item can take the reopened player back. */
  fun isAttachableSession(
    hasCurrentItem: Boolean,
    phase: PlaybackPhase,
  ): Boolean = hasCurrentItem && phase in ATTACHABLE_PHASES

  /** The reopened player shows the audio layout for audio launches and audio items. */
  fun isAudioReentry(
    isAudioExtra: Boolean,
    mediaLibraryAudio: Boolean,
    itemMimeType: String?,
  ): Boolean = isAudioExtra || mediaLibraryAudio || itemMimeType?.startsWith("audio/") == true

  /**
   * The tap's own item when the live session is gone, so the user lands on that item rather than
   * on whatever the Activity showed last; null when the notification carried no item.
   */
  fun fallbackLaunch(
    uri: String?,
    isAudio: Boolean,
  ): NotificationFallbackLaunch? {
    val target = uri?.takeIf { it.isNotBlank() } ?: return null
    return NotificationFallbackLaunch(target, FALLBACK_AUDIO_MIME_TYPE.takeIf { isAudio })
  }
}
