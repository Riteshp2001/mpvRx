package app.gyrolet.mpvrx.ui.components

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.AppIcon
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import android.widget.Toast as NativeToast

enum class AppToastType { Normal, Success, Info, Warning, Error, Loading }

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
)

class AppToast private constructor(
  private val context: Context,
  text: CharSequence?,
  duration: Int,
) {
  private val id = nextId.incrementAndGet()
  private val revision = AtomicLong()
  @Volatile private var message: CharSequence = text ?: ""
  @Volatile var duration: Int = duration
  @Volatile private var type: AppToastType = AppToastType.Normal
  @Volatile private var icon: AppToastIcon? = null
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
      if (!AppToastRouter.show(AppToastMessage(id, text, duration, type, icon))) {
        nativeToast = NativeToast.makeText(context, text, duration).also { it.show() }
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
      AppToast(context.applicationContext, text, duration)

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
  val mainHandler = Handler(Looper.getMainLooper())
  private val sinks = linkedMapOf<Any, WeakReference<AppToastSink>>()

  fun register(owner: Any, sink: AppToastSink) {
    sinks.remove(owner)
    sinks[owner] = WeakReference(sink)
  }

  fun unregister(owner: Any) {
    sinks.remove(owner)
  }

  fun show(message: AppToastMessage): Boolean {
    sinks.entries.removeAll { it.value.get() == null }
    return sinks.values.toList().asReversed().any { it.get()?.show(message) == true }
  }

  fun dismiss(id: Long) {
    sinks.values.forEach { it.get()?.dismiss(id) }
  }
}
