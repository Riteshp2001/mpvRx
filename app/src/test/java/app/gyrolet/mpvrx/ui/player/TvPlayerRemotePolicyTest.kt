package app.gyrolet.mpvrx.ui.player

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvPlayerRemotePolicyTest {
  @Test
  fun hiddenChromeUsesDirectPlaybackActions() {
    assertAction(KeyEvent.KEYCODE_DPAD_LEFT, TvPlayerRemoteAction.SEEK_BACKWARD)
    assertAction(KeyEvent.KEYCODE_DPAD_RIGHT, TvPlayerRemoteAction.SEEK_FORWARD)
    assertAction(KeyEvent.KEYCODE_DPAD_UP, TvPlayerRemoteAction.SHOW_CONTROLS)
    assertAction(KeyEvent.KEYCODE_DPAD_DOWN, TvPlayerRemoteAction.SHOW_CONTROLS)
    assertAction(KeyEvent.KEYCODE_DPAD_CENTER, TvPlayerRemoteAction.TOGGLE_PLAYBACK)
    assertAction(KeyEvent.KEYCODE_ENTER, TvPlayerRemoteAction.TOGGLE_PLAYBACK)
    assertAction(KeyEvent.KEYCODE_NUMPAD_ENTER, TvPlayerRemoteAction.TOGGLE_PLAYBACK)
    assertAction(KeyEvent.KEYCODE_BUTTON_A, TvPlayerRemoteAction.TOGGLE_PLAYBACK)
    assertAction(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT, TvPlayerRemoteAction.SEEK_BACKWARD)
  }

  @Test
  fun visibleChromeDelegatesNavigationToComposeFocus() {
    listOf(
      KeyEvent.KEYCODE_DPAD_LEFT,
      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_DPAD_UP,
      KeyEvent.KEYCODE_DPAD_DOWN,
      KeyEvent.KEYCODE_DPAD_CENTER,
      KeyEvent.KEYCODE_ENTER,
    ).forEach { keyCode ->
      assertAction(keyCode, TvPlayerRemoteAction.DELEGATE, controlsVisible = true)
      assertAction(keyCode, TvPlayerRemoteAction.DELEGATE, overlayVisible = true)
    }
  }

  @Test
  fun mediaKeysRemainGlobal() {
    assertAction(KeyEvent.KEYCODE_MEDIA_PLAY, TvPlayerRemoteAction.PLAY, controlsVisible = true)
    assertAction(KeyEvent.KEYCODE_MEDIA_PAUSE, TvPlayerRemoteAction.PAUSE, overlayVisible = true)
    assertAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, TvPlayerRemoteAction.TOGGLE_PLAYBACK)
    assertAction(KeyEvent.KEYCODE_MEDIA_STOP, TvPlayerRemoteAction.STOP)
    assertAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS, TvPlayerRemoteAction.PREVIOUS)
    assertAction(KeyEvent.KEYCODE_MEDIA_NEXT, TvPlayerRemoteAction.NEXT)
    assertAction(KeyEvent.KEYCODE_MEDIA_REWIND, TvPlayerRemoteAction.SEEK_BACKWARD)
    assertAction(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, TvPlayerRemoteAction.SEEK_FORWARD)
    assertAction(KeyEvent.KEYCODE_BUTTON_L1, TvPlayerRemoteAction.PREVIOUS)
    assertAction(KeyEvent.KEYCODE_BUTTON_R1, TvPlayerRemoteAction.NEXT)
    assertAction(KeyEvent.KEYCODE_BUTTON_L2, TvPlayerRemoteAction.SEEK_BACKWARD)
    assertAction(KeyEvent.KEYCODE_BUTTON_R2, TvPlayerRemoteAction.SEEK_FORWARD)
  }

  @Test
  fun menuOpensMoreControlsUnlessAnOverlayOwnsFocus() {
    assertAction(KeyEvent.KEYCODE_MENU, TvPlayerRemoteAction.SHOW_MENU)
    assertAction(KeyEvent.KEYCODE_GUIDE, TvPlayerRemoteAction.SHOW_MENU, controlsVisible = true)
    assertAction(KeyEvent.KEYCODE_MENU, TvPlayerRemoteAction.DELEGATE, overlayVisible = true)
  }

  @Test
  fun dedicatedTrackAndChannelKeysUseNativePlayerActions() {
    assertAction(KeyEvent.KEYCODE_CAPTIONS, TvPlayerRemoteAction.SHOW_SUBTITLES)
    assertAction(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK, TvPlayerRemoteAction.SHOW_AUDIO_TRACKS)
    assertAction(KeyEvent.KEYCODE_CHANNEL_UP, TvPlayerRemoteAction.SPEED_UP)
    assertAction(KeyEvent.KEYCODE_CHANNEL_DOWN, TvPlayerRemoteAction.SPEED_DOWN)
    assertAction(KeyEvent.KEYCODE_CAPTIONS, TvPlayerRemoteAction.DELEGATE, overlayVisible = true)
  }

  @Test
  fun numericKeysMapToTimelinePercentages() {
    assertAction(KeyEvent.KEYCODE_0, TvPlayerRemoteAction.SEEK_TO_PERCENT)
    assertAction(KeyEvent.KEYCODE_7, TvPlayerRemoteAction.SEEK_TO_PERCENT)
    assertAction(KeyEvent.KEYCODE_NUMPAD_9, TvPlayerRemoteAction.SEEK_TO_PERCENT)
    assertEquals(0, TvPlayerRemotePolicy.seekPercentForKeyCode(KeyEvent.KEYCODE_0))
    assertEquals(70, TvPlayerRemotePolicy.seekPercentForKeyCode(KeyEvent.KEYCODE_7))
    assertEquals(90, TvPlayerRemotePolicy.seekPercentForKeyCode(KeyEvent.KEYCODE_NUMPAD_9))
    assertEquals(null, TvPlayerRemotePolicy.seekPercentForKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT))
  }

  @Test
  fun onlySeekActionsRepeat() {
    assertTrue(TvPlayerRemotePolicy.shouldRepeat(TvPlayerRemoteAction.SEEK_BACKWARD))
    assertTrue(TvPlayerRemotePolicy.shouldRepeat(TvPlayerRemoteAction.SEEK_FORWARD))
    assertFalse(TvPlayerRemotePolicy.shouldRepeat(TvPlayerRemoteAction.TOGGLE_PLAYBACK))
    assertFalse(TvPlayerRemotePolicy.shouldRepeat(TvPlayerRemoteAction.STOP))
  }

  @Test
  fun delegatedDpadKeysAreRecognizedAsFocusedUiNavigation() {
    assertTrue(TvPlayerRemotePolicy.isNavigationKey(KeyEvent.KEYCODE_DPAD_LEFT))
    assertTrue(TvPlayerRemotePolicy.isNavigationKey(KeyEvent.KEYCODE_DPAD_CENTER))
    assertTrue(TvPlayerRemotePolicy.isNavigationKey(KeyEvent.KEYCODE_MENU))
    assertFalse(TvPlayerRemotePolicy.isNavigationKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    assertFalse(TvPlayerRemotePolicy.isNavigationKey(KeyEvent.KEYCODE_A))
  }

  private fun assertAction(
    keyCode: Int,
    expected: TvPlayerRemoteAction,
    controlsVisible: Boolean = false,
    overlayVisible: Boolean = false,
  ) {
    assertEquals(
      expected,
      TvPlayerRemotePolicy.actionFor(keyCode, controlsVisible, overlayVisible),
    )
  }
}
