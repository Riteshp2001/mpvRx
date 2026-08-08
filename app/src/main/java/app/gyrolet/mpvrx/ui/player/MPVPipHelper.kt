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
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.utils.media.resolveSeekMode
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

import androidx.core.content.ContextCompat

private const val PIP_INTENTS_FILTER = "pip_action"
private const val PIP_INTENT_ACTION = "pip_action_code"
private const val PIP_PLAY = 1
private const val PIP_PAUSE = 2
private const val PIP_REWIND = 3
private const val PIP_FORWARD = 4

class MPVPipHelper(
  private val activity: AppCompatActivity,
  private val mpvView: MPVView,
  private val isAudioPlayer: () -> Boolean = { false },
) : KoinComponent {
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
          val seekMode = resolveSeekMode(playerPreferences)
          when (intent?.getIntExtra(PIP_INTENT_ACTION, 0)) {
            PIP_PLAY -> PlaybackSession.setPropertyBoolean("pause", false)
            PIP_PAUSE -> PlaybackSession.setPropertyBoolean("pause", true)
            PIP_REWIND -> PlaybackSession.command("seek", "-10", seekMode)
            PIP_FORWARD -> PlaybackSession.command("seek", "10", seekMode)
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
    if (activity.isFinishing || activity.isDestroyed) return

    val params = buildPipParams()
    runCatching { activity.setPictureInPictureParams(params) }
  }

  private fun buildPipParams(): PictureInPictureParams =
    PictureInPictureParams
      .Builder()
      .apply {
        getVideoAspectRatio()?.let { aspectRatio ->
          setAspectRatio(aspectRatio)
          calculateSourceRect(aspectRatio)?.let { sourceRect -> setSourceRectHint(sourceRect) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          val autoPipAllowed = playerPreferences.autoPiPOnNavigation.get() && !isAudioPlayer()
          setAutoEnterEnabled(autoPipAllowed)
          // Video surfaces can resize continuously, so let Android morph the
          // full-screen frame into and out of PiP instead of cross-fading it.
          setSeamlessResizeEnabled(!isAudioPlayer())
        }

        setActions(createPipActions())
      }.build()

  private fun getVideoAspectRatio(): Rational? {
    val width = PlaybackSession.getPropertyInt("video-out-params/dw") ?: 0
    val height = PlaybackSession.getPropertyInt("video-out-params/dh") ?: 0

    if (width == 0 || height == 0) return null

    return Rational(width, height).takeIf { it.toFloat() in 0.5f..2.39f }
  }

  private fun calculateSourceRect(aspectRatio: Rational): Rect? {
    val visiblePlayerRect = Rect()
    if (!mpvView.getGlobalVisibleRect(visiblePlayerRect) || visiblePlayerRect.isEmpty) return null

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
      createRemoteAction("rewind", Icons.Platform.FastRewind, PIP_REWIND),
      if (isPlaying) {
        createRemoteAction("pause", Icons.Platform.Pause, PIP_PAUSE)
      } else {
        createRemoteAction("play", Icons.Platform.Play, PIP_PLAY)
      },
      createRemoteAction("forward", Icons.Platform.FastForward, PIP_FORWARD),
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

  fun enterPipMode() {
    if (isAudioPlayer()) {
      Log.d("MPVPipHelper", "PiP mode is disabled for audio playback")
      return
    }
    runCatching {
      activity.enterPictureInPictureMode(buildPipParams())
    }.onFailure {
      Log.e("MPVPipHelper", "Failed to enter PiP mode", it)
    }
  }

  fun onStop() {
    unregisterPipReceiver()
  }
}
