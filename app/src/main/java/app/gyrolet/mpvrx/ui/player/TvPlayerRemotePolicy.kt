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
  SEEK_BACKWARD,
  SEEK_FORWARD,
  TOGGLE_PLAYBACK,
}

internal object TvPlayerRemotePolicy {
  fun actionFor(
    keyCode: Int,
    controlsVisible: Boolean,
    overlayVisible: Boolean,
  ): TvPlayerRemoteAction =
    when (keyCode) {
      KeyEvent.KEYCODE_DPAD_UP,
      KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP,
      KeyEvent.KEYCODE_DPAD_DOWN,
      KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN,
      -> if (controlsVisible || overlayVisible) TvPlayerRemoteAction.DELEGATE else TvPlayerRemoteAction.SHOW_CONTROLS

      KeyEvent.KEYCODE_DPAD_LEFT,
      KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT,
      -> if (controlsVisible || overlayVisible) TvPlayerRemoteAction.DELEGATE else TvPlayerRemoteAction.SEEK_BACKWARD

      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT,
      -> if (controlsVisible || overlayVisible) TvPlayerRemoteAction.DELEGATE else TvPlayerRemoteAction.SEEK_FORWARD

      KeyEvent.KEYCODE_DPAD_CENTER,
      KeyEvent.KEYCODE_ENTER,
      KeyEvent.KEYCODE_NUMPAD_ENTER,
      KeyEvent.KEYCODE_BUTTON_A,
      KeyEvent.KEYCODE_BUTTON_START,
      -> if (controlsVisible || overlayVisible) TvPlayerRemoteAction.DELEGATE else TvPlayerRemoteAction.TOGGLE_PLAYBACK

      else -> TvPlayerRemoteAction.DELEGATE
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
      keyCode == KeyEvent.KEYCODE_BUTTON_START
}
