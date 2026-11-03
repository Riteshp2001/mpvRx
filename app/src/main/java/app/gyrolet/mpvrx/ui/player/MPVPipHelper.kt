/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.util.Rational
import android.view.View
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

import androidx.core.content.ContextCompat

private const val PIP_INTENTS_FILTER = "pip_action"
private const val PIP_INTENT_ACTION = "pip_action_code"
private const val PIP_PLAY = 1
private const val PIP_PAUSE = 2
private const val PIP_REWIND = 3
private const val PIP_FORWARD = 4
private const val PIP_CLOSE = 5

class MPVPipHelper(
  private val activity: AppCompatActivity,
  private val videoViewProvider: (() -> View?)? = null,
  private val isAudioPlayer: () -> Boolean = { false },
  private val isVideoLoaded: () -> Boolean = { false },
) : KoinComponent {

  constructor(
    activity: AppCompatActivity,
    mpvView: MPVView,
    isAudioPlayer: () -> Boolean = { false },
    isVideoLoaded: () -> Boolean = { false },
  ) : this(activity, { mpvView }, isAudioPlayer, isVideoLoaded)

  private val playerPreferences: PlayerPreferences by inject()
  private var pipReceiver: BroadcastReceiver? = null

  fun onPictureInPictureModeChanged(isInPipMode: Boolean) {
    if (isInPipMode) {
      registerPipReceiver()
    } else {
      unregisterPipReceiver()
    }
  }

  @Suppress("UnspecifiedRegisterReceiverFlag")
  private fun registerPipReceiver() {
    pipReceiver =
      object : BroadcastReceiver() {
        override fun onReceive(
          context: Context?,
          intent: Intent?,
        ) {
          when (intent?.getIntExtra(PIP_INTENT_ACTION, 0)) {
            PIP_PLAY -> PlaybackSession.setPropertyBoolean("pause", false)
            PIP_PAUSE -> PlaybackSession.setPropertyBoolean("pause", true)
            PIP_REWIND -> PlaybackSession.command("seek", "-10", "relative")
            PIP_FORWARD -> PlaybackSession.command("seek", "10", "relative")
            PIP_CLOSE -> {
              MediaPlaybackService.stopForTerminalDismissal()
              activity.finishAndRemoveTask()
              return
            }
          }
          updatePictureInPictureParams()
        }
      }

    val filter = IntentFilter(PIP_INTENTS_FILTER)
    ContextCompat.registerReceiver(activity, pipReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
  }

  private fun unregisterPipReceiver() {
    pipReceiver?.let {
      runCatching { activity.unregisterReceiver(it) }
      pipReceiver = null
    }
  }

  fun updatePictureInPictureParams() {
    if (!DeviceFormFactor.supportsPlaybackPictureInPicture(activity) || activity.isFinishing || activity.isDestroyed) return

    val params = buildPipParams()
    runCatching { activity.setPictureInPictureParams(params) }
  }

  private fun buildPipParams(): PictureInPictureParams =
    PictureInPictureParams
      .Builder()
      .apply {
        getVideoAspectRatio()?.let { aspectRatio ->
          setAspectRatio(aspectRatio)
          // In PiP the view reports PiP-window bounds; keep the full-screen hint for the expand animation.
          if (!activity.isInPictureInPictureMode) {
            calculateSourceRect(aspectRatio)?.let { sourceRect -> setSourceRectHint(sourceRect) }
          }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          val autoPipAllowed = playerPreferences.autoPiPOnNavigation.get() && !isAudioPlayer() && isVideoLoaded()
          setAutoEnterEnabled(autoPipAllowed)
          // Video surfaces can resize continuously, so let Android morph the
          // full-screen frame into and out of PiP instead of cross-fading it.
          setSeamlessResizeEnabled(!isAudioPlayer())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          setCloseAction(createRemoteAction("close", Icons.Platform.Stop, PIP_CLOSE))
        }

        setActions(createPipActions())
      }.build()

  private fun getVideoAspectRatio(): Rational? {
    val playback = PlaybackSession.state.value
    if (
      activity.isFinishing || activity.isDestroyed || isAudioPlayer() || !isVideoLoaded() ||
      !playback.surfaceAttached ||
      playback.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
    ) return null

    // video-out-params/dw and dh are valid mpv properties, but they disappear as soon as the
    // video output is detached. Prefer the already-observed source metadata so PiP layout
    // updates during exit/foreground transitions never poll an unavailable VO property.
    val width = PlaybackSession.propInt["video-params/w"].value ?: return null
    val height = PlaybackSession.propInt["video-params/h"].value ?: return null
    if (width <= 0 || height <= 0) return null

    val sourceAspect = PlaybackSession.propDouble["video-params/aspect"].value
      ?.takeIf { it.isFinite() && it > 0.0 }
      ?: (width.toDouble() / height.toDouble())
    val rotation = (PlaybackSession.getPropertyInt("video-params/rotate") ?: 0).mod(360)
    val displayedAspect = if (rotation == 90 || rotation == 270) 1.0 / sourceAspect else sourceAspect
    if (!displayedAspect.isFinite() || displayedAspect !in 0.5..2.39) return null

    // Android accepts integer Rational only; retaining 1/10000 aspect precision avoids
    // passing distorted ratios to PiP for anamorphic or rotated content.
    return Rational((displayedAspect * 10_000).toInt().coerceAtLeast(1), 10_000)
  }

  private fun calculateSourceRect(aspectRatio: Rational): Rect? {
    val targetView = videoViewProvider?.invoke() ?: return null
    val visiblePlayerRect = Rect()
    if (!targetView.getGlobalVisibleRect(visiblePlayerRect) || visiblePlayerRect.isEmpty) return null

    val viewWidth = visiblePlayerRect.width().toFloat()
    val viewHeight = visiblePlayerRect.height().toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f) return null

    val videoAspect = aspectRatio.toFloat()
    val viewAspect = viewWidth / viewHeight

    return if (viewAspect < videoAspect) {
      // Letterboxed (black bars top/bottom)
      val height = viewWidth / videoAspect
      val top = visiblePlayerRect.top + ((viewHeight - height) / 2).toInt()
      Rect(visiblePlayerRect.left, top, visiblePlayerRect.right, top + height.toInt())
    } else {
      // Pillarboxed (black bars left/right)
      val width = viewHeight * videoAspect
      val left = visiblePlayerRect.left + ((viewWidth - width) / 2).toInt()
      Rect(left, visiblePlayerRect.top, left + width.toInt(), visiblePlayerRect.bottom)
    }
  }

  private fun createPipActions(): List<RemoteAction> {
    val isPlaying = PlaybackSession.getPropertyBoolean("pause") == false

    return listOf(
      createRemoteAction("rewind 10 seconds", Icons.Platform.Replay10, PIP_REWIND),
      if (isPlaying) {
        createRemoteAction("pause", Icons.Platform.Pause, PIP_PAUSE)
      } else {
        createRemoteAction("play", Icons.Platform.Play, PIP_PLAY)
      },
      createRemoteAction("forward 10 seconds", Icons.Platform.Forward10, PIP_FORWARD),
    )
  }

  private fun createRemoteAction(
    title: String,
    @DrawableRes icon: Int,
    actionCode: Int,
  ): RemoteAction {
    val intent =
      Intent(PIP_INTENTS_FILTER).apply {
        putExtra(PIP_INTENT_ACTION, actionCode)
        setPackage(activity.packageName)
      }

    val pendingIntent =
      PendingIntent.getBroadcast(
        activity,
        actionCode,
        intent,
        PendingIntent.FLAG_IMMUTABLE,
      )

    return RemoteAction(
      Icon.createWithResource(activity, icon),
      title,
      title,
      pendingIntent,
    )
  }

  /**
   * Requests Picture-in-Picture and reports whether Android accepted the transition.
   *
   * Callers use the result to fall back to background playback or a normal close instead of
   * leaving the full-screen player stranded with hidden controls when PiP is unavailable.
   */
  fun enterPipMode(): Boolean {
    if (!DeviceFormFactor.supportsPlaybackPictureInPicture(activity) || isAudioPlayer() || !isVideoLoaded()) {
      Log.d("MPVPipHelper", "PiP mode is disabled: audio=${isAudioPlayer()}, videoLoaded=${isVideoLoaded()}")
      return false
    }
    return runCatching {
      activity.enterPictureInPictureMode(buildPipParams())
    }.onFailure {
      Log.e("MPVPipHelper", "Failed to enter PiP mode", it)
    }.getOrDefault(false)
  }

  fun onStop() {
    unregisterPipReceiver()
  }
}
