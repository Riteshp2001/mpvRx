// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inspector.WindowInspector
import java.lang.ref.WeakReference
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Delivers phone commands to the focused MPVRX window, dialogs included, as a hardware remote would. */
internal object TvInputDispatcher {
  private const val MAIN_THREAD_TIMEOUT_MS = 3_000L
  private const val CTRL_META = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON

  private sealed interface Command {
    data class Key(
      val code: Int,
    ) : Command

    data object ClearField : Command

    data class Text(
      val value: String,
    ) : Command
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private val keyCodes =
    mapOf(
      RemoteKey.UP to KeyEvent.KEYCODE_DPAD_UP,
      RemoteKey.DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
      RemoteKey.LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
      RemoteKey.RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
      RemoteKey.CENTER to KeyEvent.KEYCODE_DPAD_CENTER,
      RemoteKey.BACK to KeyEvent.KEYCODE_BACK,
      RemoteKey.MENU to KeyEvent.KEYCODE_MENU,
      RemoteKey.PLAY_PAUSE to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
      RemoteKey.REWIND to KeyEvent.KEYCODE_MEDIA_REWIND,
      RemoteKey.FAST_FORWARD to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
      RemoteKey.VOLUME_UP to KeyEvent.KEYCODE_VOLUME_UP,
      RemoteKey.VOLUME_DOWN to KeyEvent.KEYCODE_VOLUME_DOWN,
      RemoteKey.BACKSPACE to KeyEvent.KEYCODE_DEL,
      RemoteKey.ENTER to KeyEvent.KEYCODE_ENTER,
    )
  private val dpadKeys =
    setOf(
      KeyEvent.KEYCODE_DPAD_UP,
      KeyEvent.KEYCODE_DPAD_DOWN,
      KeyEvent.KEYCODE_DPAD_LEFT,
      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_DPAD_CENTER,
    )
  private val volumeKeys = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)

  @Volatile private var resumedActivity: WeakReference<Activity>? = null

  fun onActivityResumed(activity: Activity) {
    resumedActivity = WeakReference(activity)
  }

  fun onActivityPaused(activity: Activity) {
    if (resumedActivity?.get() === activity) resumedActivity = null
  }

  /** Runs one command on the main thread; returns null on success or a [RemoteReason]. */
  fun perform(
    context: Context,
    message: RemoteMessage,
  ): String? {
    val command =
      when (message.type) {
        RemoteMessageType.KEY ->
          if (message.key == RemoteKey.CLEAR_FIELD) {
            Command.ClearField
          } else {
            Command.Key(keyCodes[message.key.orEmpty()] ?: return RemoteReason.UNSUPPORTED)
          }
        RemoteMessageType.TEXT ->
          Command.Text(
            message.text?.takeIf { it.isNotEmpty() && it.length <= REMOTE_MAX_TEXT_LENGTH }
              ?: return RemoteReason.UNSUPPORTED,
          )
        else -> return RemoteReason.UNSUPPORTED
      }
    val task = FutureTask<String?> { execute(context, command) }
    mainHandler.post(task)
    return try {
      task.get(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (_: Exception) {
      task.cancel(false)
      RemoteReason.BACKGROUND
    }
  }

  private fun execute(
    context: Context,
    command: Command,
  ): String? {
    val root = focusedRoot()
    when (command) {
      is Command.Key -> {
        val handled = root != null && pressKey(root, command.code)
        if (!handled && command.code in volumeKeys) {
          adjustVolume(context, command.code)
        } else if (root == null) {
          return RemoteReason.BACKGROUND
        }
      }
      Command.ClearField -> {
        root ?: return RemoteReason.BACKGROUND
        pressKey(root, KeyEvent.KEYCODE_A, CTRL_META)
        pressKey(root, KeyEvent.KEYCODE_DEL)
      }
      is Command.Text -> typeText(context, root ?: return RemoteReason.BACKGROUND, command.value)
    }
    return null
  }

  private fun pressKey(
    root: View,
    keyCode: Int,
    metaState: Int = 0,
  ): Boolean {
    val now = SystemClock.uptimeMillis()
    val source = if (keyCode in dpadKeys) InputDevice.SOURCE_DPAD else InputDevice.SOURCE_KEYBOARD
    val down =
      KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source)
    val handled = root.dispatchKeyEvent(down)
    root.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
    return handled
  }

  private fun typeText(
    context: Context,
    root: View,
    text: String,
  ) {
    // Typed keys keep text off the clipboard; only characters a keyboard cannot produce are pasted.
    val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
    if (events != null) {
      events.forEach { root.dispatchKeyEvent(it) }
      return
    }
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("MPVRX remote", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
    pressKey(root, KeyEvent.KEYCODE_V, CTRL_META)
  }

  private fun adjustVolume(
    context: Context,
    keyCode: Int,
  ) {
    context.getSystemService(AudioManager::class.java)?.adjustStreamVolume(
      AudioManager.STREAM_MUSIC,
      if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
      AudioManager.FLAG_SHOW_UI,
    )
  }

  private fun focusedRoot(): View? {
    val roots =
      if (Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.Q
      ) {
        WindowInspector.getGlobalWindowViews()
      } else {
        legacyWindowRoots()
      }
    return roots.lastOrNull { it.isAttachedToWindow && it.hasWindowFocus() }
      ?: resumedActivity
        ?.get()
        ?.takeIf { it.hasWindowFocus() }
        ?.window
        ?.decorView
  }

  // Android 9 and older have no public window list; this is the same field Curtains and Espresso read.
  @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
  private fun legacyWindowRoots(): List<View> =
    runCatching {
      val globalClass = Class.forName("android.view.WindowManagerGlobal")
      val global = globalClass.getMethod("getInstance").invoke(null)
      val views = globalClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global)
      (views as? List<*>)?.filterIsInstance<View>().orEmpty()
    }.getOrDefault(emptyList())
}
