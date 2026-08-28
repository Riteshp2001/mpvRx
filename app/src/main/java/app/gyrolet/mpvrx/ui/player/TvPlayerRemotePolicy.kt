/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.view.KeyEvent

internal enum class TvPlayerRemoteAction {
  DELEGATE,
  SHOW_CONTROLS,
  SHOW_MENU,
  SHOW_SUBTITLES,
  SHOW_AUDIO_TRACKS,
  SEEK_TO_PERCENT,
  SPEED_UP,
  SPEED_DOWN,
  SEEK_BACKWARD,
  SEEK_FORWARD,
  TOGGLE_PLAYBACK,
  PLAY,
  PAUSE,
  STOP,
  PREVIOUS,
  NEXT,
}

internal object TvPlayerRemotePolicy {
  fun actionFor(
    keyCode: Int,
    controlsVisible: Boolean,
    overlayVisible: Boolean,
  ): TvPlayerRemoteAction =
    when (keyCode) {
      KeyEvent.KEYCODE_MEDIA_PLAY -> TvPlayerRemoteAction.PLAY
      KeyEvent.KEYCODE_MEDIA_PAUSE -> TvPlayerRemoteAction.PAUSE
      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
      KeyEvent.KEYCODE_HEADSETHOOK,
      -> TvPlayerRemoteAction.TOGGLE_PLAYBACK
      KeyEvent.KEYCODE_MEDIA_STOP -> TvPlayerRemoteAction.STOP
      KeyEvent.KEYCODE_MEDIA_PREVIOUS,
      KeyEvent.KEYCODE_BUTTON_L1,
      -> TvPlayerRemoteAction.PREVIOUS
      KeyEvent.KEYCODE_MEDIA_NEXT,
      KeyEvent.KEYCODE_BUTTON_R1,
      -> TvPlayerRemoteAction.NEXT
      KeyEvent.KEYCODE_MEDIA_REWIND,
      KeyEvent.KEYCODE_BUTTON_L2,
      -> TvPlayerRemoteAction.SEEK_BACKWARD
      KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
      KeyEvent.KEYCODE_BUTTON_R2,
      -> TvPlayerRemoteAction.SEEK_FORWARD
      KeyEvent.KEYCODE_CAPTIONS ->
        if (overlayVisible) TvPlayerRemoteAction.DELEGATE else TvPlayerRemoteAction.SHOW_SUBTITLES
      KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK ->
        if (overlayVisible) TvPlayerRemoteAction.DELEGATE else TvPlayerRemoteAction.SHOW_AUDIO_TRACKS
      KeyEvent.KEYCODE_CHANNEL_UP -> TvPlayerRemoteAction.SPEED_UP
      KeyEvent.KEYCODE_CHANNEL_DOWN -> TvPlayerRemoteAction.SPEED_DOWN

      in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9,
      in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9,
      -> TvPlayerRemoteAction.SEEK_TO_PERCENT

      KeyEvent.KEYCODE_DPAD_UP,
      KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP,
      KeyEvent.KEYCODE_DPAD_DOWN,
      KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN,
      KeyEvent.KEYCODE_INFO,
      -> if (controlsVisible || overlayVisible) TvPlayerRemoteAction.DELEGATE else TvPlayerRemoteAction.SHOW_CONTROLS

      KeyEvent.KEYCODE_MENU,
      KeyEvent.KEYCODE_GUIDE,
      -> if (overlayVisible) TvPlayerRemoteAction.DELEGATE else TvPlayerRemoteAction.SHOW_MENU

      KeyEvent.KEYCODE_DPAD_LEFT,
      KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT,
      ->
        if (controlsVisible || overlayVisible) TvPlayerRemoteAction.DELEGATE else TvPlayerRemoteAction.SEEK_BACKWARD
      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT,
      ->
        if (controlsVisible || overlayVisible) TvPlayerRemoteAction.DELEGATE else TvPlayerRemoteAction.SEEK_FORWARD

      KeyEvent.KEYCODE_DPAD_CENTER,
      KeyEvent.KEYCODE_ENTER,
      KeyEvent.KEYCODE_NUMPAD_ENTER,
      KeyEvent.KEYCODE_BUTTON_A,
      KeyEvent.KEYCODE_BUTTON_START,
      -> if (controlsVisible || overlayVisible) TvPlayerRemoteAction.DELEGATE else TvPlayerRemoteAction.TOGGLE_PLAYBACK

      else -> TvPlayerRemoteAction.DELEGATE
    }

  fun shouldRepeat(action: TvPlayerRemoteAction): Boolean =
    when (action) {
      TvPlayerRemoteAction.SEEK_BACKWARD,
      TvPlayerRemoteAction.SEEK_FORWARD,
      -> true
      TvPlayerRemoteAction.DELEGATE,
      TvPlayerRemoteAction.SHOW_CONTROLS,
      TvPlayerRemoteAction.SHOW_MENU,
      TvPlayerRemoteAction.SHOW_SUBTITLES,
      TvPlayerRemoteAction.SHOW_AUDIO_TRACKS,
      TvPlayerRemoteAction.SEEK_TO_PERCENT,
      TvPlayerRemoteAction.SPEED_UP,
      TvPlayerRemoteAction.SPEED_DOWN,
      TvPlayerRemoteAction.TOGGLE_PLAYBACK,
      TvPlayerRemoteAction.PLAY,
      TvPlayerRemoteAction.PAUSE,
      TvPlayerRemoteAction.STOP,
      TvPlayerRemoteAction.PREVIOUS,
      TvPlayerRemoteAction.NEXT,
      -> false
    }

  fun seekPercentForKeyCode(keyCode: Int): Int? =
    when (keyCode) {
      in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (keyCode - KeyEvent.KEYCODE_0) * 10
      in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> (keyCode - KeyEvent.KEYCODE_NUMPAD_0) * 10
      else -> null
    }

  fun isNavigationKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_DPAD_UP ||
      keyCode == KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP ||
      keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
      keyCode == KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN ||
      keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
      keyCode == KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT ||
      keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
      keyCode == KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT ||
      keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
      keyCode == KeyEvent.KEYCODE_ENTER ||
      keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
      keyCode == KeyEvent.KEYCODE_BUTTON_A ||
      keyCode == KeyEvent.KEYCODE_BUTTON_START ||
      keyCode == KeyEvent.KEYCODE_MENU ||
      keyCode == KeyEvent.KEYCODE_INFO ||
      keyCode == KeyEvent.KEYCODE_GUIDE
}
