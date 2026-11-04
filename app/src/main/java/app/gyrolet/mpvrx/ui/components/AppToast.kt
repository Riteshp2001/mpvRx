package app.gyrolet.mpvrx.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import app.gyrolet.mpvrx.App
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.AppIcon
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import android.widget.Toast as NativeToast

enum class AppToastType { Normal, Success, Info, Warning, Error, Loading }

data class AppToastAction(val label: String, val onClick: () -> Unit)

sealed interface AppToastIcon {
  data class Symbol(val value: AppIcon) : AppToastIcon
  data class Emoji(val value: String) : AppToastIcon
}

internal data class AppToastMessage(
  val id: Long,
  val text: String,
  val duration: Int,
  val type: AppToastType,
  val icon: AppToastIcon?,
  val action: AppToastAction?,
)

class AppToast private constructor(
  context: Context,
  text: CharSequence?,
  duration: Int,
) {
  private val activity = WeakReference(context.findActivity())
  private val context = context.applicationContext
  private val id = nextId.incrementAndGet()
  private val revision = AtomicLong()
  @Volatile private var message: CharSequence = text ?: ""
  @Volatile var duration: Int = duration
  @Volatile private var type: AppToastType = AppToastType.Normal
  @Volatile private var icon: AppToastIcon? = null
  @Volatile private var action: AppToastAction? = null
  private var nativeToast: NativeToast? = null

  fun setText(text: CharSequence?) {
    message = text ?: ""
  }

  fun setText(@StringRes resourceId: Int) {
    message = context.getText(resourceId)
  }

  fun withType(type: AppToastType): AppToast = apply { this.type = type }

  fun withIcon(icon: AppIcon?): AppToast = apply { this.icon = icon?.let { AppToastIcon.Symbol(it) } }

  fun withIcon(emoji: String): AppToast = apply { icon = AppToastIcon.Emoji(emoji) }

  fun withAction(label: String, onClick: () -> Unit): AppToast = apply {
    action = AppToastAction(label, onClick)
  }

  fun update(text: CharSequence, type: AppToastType) {
    setText(text)
    withType(type)
    show()
  }

  fun show() {
    val token = revision.incrementAndGet()
    AppToastRouter.mainHandler.post {
      if (revision.get() != token) return@post
      val text = message.toString()
      if (text.isBlank()) return@post
      nativeToast?.cancel()
      nativeToast = null
      val fallback = {
        if (revision.get() == token) {
          nativeToast = NativeToast.makeText(context, text, duration).also { it.show() }
        }
      }
      val owner = activity.get()
      val waitForHost = owner != null && !owner.isFinishing && !owner.isDestroyed && !owner.isInPictureInPictureMode
      val isCurrent = { revision.get() == token }
      val toastMessage = AppToastMessage(id, text, duration, type, icon, action)
      if (!AppToastRouter.show(toastMessage, waitForHost, isCurrent, fallback)) {
        fallback()
      }
    }
  }

  fun cancel() {
    revision.incrementAndGet()
    AppToastRouter.mainHandler.post {
      AppToastRouter.dismiss(id)
      nativeToast?.cancel()
      nativeToast = null
    }
  }

  companion object {
    const val LENGTH_SHORT: Int = NativeToast.LENGTH_SHORT
    const val LENGTH_LONG: Int = NativeToast.LENGTH_LONG
    private val nextId = AtomicLong()

    fun makeText(context: Context, text: CharSequence?, duration: Int): AppToast =
      AppToast(context, text, duration)

    fun makeText(context: Context, @StringRes resourceId: Int, duration: Int): AppToast =
      makeText(context, context.getText(resourceId), duration).withType(
        when (resourceId) {
          R.string.generic_unknown_error, R.string.ui_failed_to_delete,
          R.string.toast_playback_load_failed, R.string.playlist_add_failed,
          R.string.downloads_location_invalid, R.string.app_icon_change_failed,
          R.string.playback_bookmark_update_failed, R.string.playlist_zip_import_failed,
          R.string.jellyfin_open_trailer_error -> AppToastType.Error
          R.string.downloads_queued, R.string.downloads_started,
          R.string.playlist_zip_import_success -> AppToastType.Success
          R.string.queue_requires_playback, R.string.video_swipe_recent_disabled,
          R.string.notification_disabled_in_advanced_settings -> AppToastType.Warning
          else -> AppToastType.Normal
        },
      )

    fun success(context: Context, text: CharSequence): AppToast = typed(context, text, AppToastType.Success)
    fun info(context: Context, text: CharSequence): AppToast = typed(context, text, AppToastType.Info)
    fun warning(context: Context, text: CharSequence): AppToast = typed(context, text, AppToastType.Warning)
    fun error(context: Context, text: CharSequence): AppToast = typed(context, text, AppToastType.Error)
    fun loading(context: Context, text: CharSequence): AppToast = typed(context, text, AppToastType.Loading)

    private fun typed(context: Context, text: CharSequence, type: AppToastType): AppToast =
      makeText(context, text, LENGTH_LONG).withType(type).also { it.show() }
  }
}

internal interface AppToastSink {
  fun show(message: AppToastMessage): Boolean
  fun dismiss(id: Long)
}

internal object AppToastRouter {
  private const val HOST_WAIT_MS = 1_500L
  private const val MAX_PENDING = 6

  private data class PendingToast(
    val message: AppToastMessage,
    val isCurrent: () -> Boolean,
    val fallback: () -> Unit,
    val expiry: Runnable,
  )

  val mainHandler = Handler(Looper.getMainLooper())
  private val sinks = linkedMapOf<Any, WeakReference<AppToastSink>>()
  private val pending = linkedMapOf<Long, PendingToast>()

  fun register(owner: Any, sink: AppToastSink) {
    sinks.remove(owner)
    sinks[owner] = WeakReference(sink)
    pending.values.toList().forEach { entry ->
      if (!entry.isCurrent() || deliver(entry.message)) removePending(entry.message.id)
    }
  }

  fun unregister(owner: Any) {
    sinks.remove(owner)
  }

  fun show(message: AppToastMessage, waitForHost: Boolean, isCurrent: () -> Boolean, fallback: () -> Unit): Boolean {
    removePending(message.id)
    if (deliver(message)) return true
    if (!waitForHost && !App.isInForeground) return false
    if (pending.size >= MAX_PENDING) pending.keys.firstOrNull()?.let(::removePending)
    val expiry = Runnable {
      pending.remove(message.id)?.let { entry -> if (entry.isCurrent()) entry.fallback() }
    }
    pending[message.id] = PendingToast(message, isCurrent, fallback, expiry)
    mainHandler.postDelayed(expiry, HOST_WAIT_MS)
    return true
  }

  private fun deliver(message: AppToastMessage): Boolean {
    sinks.entries.removeAll { it.value.get() == null }
    return sinks.values.toList().asReversed().any { it.get()?.show(message) == true }
  }

  fun dismiss(id: Long) {
    removePending(id)
    sinks.values.forEach { it.get()?.dismiss(id) }
  }

  private fun removePending(id: Long) {
    pending.remove(id)?.let { mainHandler.removeCallbacks(it.expiry) }
  }
}

private fun Context.findActivity(): Activity? {
  var current: Context = this
  val visited = mutableSetOf<Context>()
  while (visited.add(current)) {
    if (current is Activity) return current
    current = (current as? ContextWrapper)?.baseContext ?: return null
  }
  return null
}