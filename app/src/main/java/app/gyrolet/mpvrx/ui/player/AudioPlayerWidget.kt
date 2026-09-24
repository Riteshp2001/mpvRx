package app.gyrolet.mpvrx.ui.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import app.gyrolet.mpvrx.MainActivity
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.thumbnail.EmbeddedArtworkResolver
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioPlayerWidget : AppWidgetProvider() {
  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    requestUpdate(context)
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_REFRESH) {
      super.onReceive(context, intent)
      return
    }
    val pending = goAsync()
    scope.launch {
      try {
        render(context.applicationContext)
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        android.util.Log.w("AudioPlayerWidget", "Unable to update music widget", error)
      } finally {
        pending.finish()
      }
    }
  }

  companion object {
    private const val ACTION_REFRESH = "app.gyrolet.mpvrx.action.REFRESH_AUDIO_WIDGET"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val updates = java.util.concurrent.atomic.AtomicLong()

    fun requestUpdate(context: Context) {
      val manager = AppWidgetManager.getInstance(context)
      if (manager.getAppWidgetIds(ComponentName(context, AudioPlayerWidget::class.java)).isEmpty()) return
      context.sendBroadcast(Intent(context, AudioPlayerWidget::class.java).setAction(ACTION_REFRESH))
    }

    private suspend fun render(context: Context) {
      val update = updates.incrementAndGet()
      val manager = AppWidgetManager.getInstance(context)
      val ids = manager.getAppWidgetIds(ComponentName(context, AudioPlayerWidget::class.java))
      if (ids.isEmpty()) return
      val session = PlaybackSession.state.value
      val live = PlaybackSession.audioState.value.takeIf {
        PlaybackSession.usingExoPlayer && it.generation == session.generation && session.currentItem != null
      }
      val retain = org.koin.java.KoinJavaComponent.get<AdvancedPreferences>(AdvancedPreferences::class.java).enableRecentlyPlayed.get()
      val saved = if (live == null && retain) AudioQueuePersistence.read(context) else null
      if (updates.get() != update) return
      val item = live?.item ?: saved?.queue?.currentItem
      val views = RemoteViews(context.packageName, R.layout.audio_player_widget)
      views.setTextViewText(R.id.audio_widget_title, live?.title ?: item?.title ?: context.getString(R.string.audio_widget_empty))
      views.setTextViewText(R.id.audio_widget_artist, live?.artist ?: item?.artist.orEmpty())
      val playing = live?.paused == false
      views.setImageViewResource(R.id.audio_widget_play, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
      views.setContentDescription(R.id.audio_widget_play, context.getString(if (playing) R.string.audiobook_pause else R.string.ui_play))
      views.setImageViewResource(R.id.audio_widget_artwork, R.mipmap.ic_launcher)
      val openIntent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      views.setOnClickPendingIntent(R.id.audio_widget_metadata, PendingIntent.getActivity(context, 7200, openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
      listOf(
        R.id.audio_widget_previous to MediaPlaybackService.ACTION_NOTIFICATION_PREVIOUS,
        R.id.audio_widget_play to MediaPlaybackService.ACTION_NOTIFICATION_PLAY_PAUSE,
        R.id.audio_widget_next to MediaPlaybackService.ACTION_NOTIFICATION_NEXT,
      ).forEachIndexed { index, (viewId, action) ->
        val pending = PendingIntent.getForegroundService(context, 7201 + index,
          Intent(context, MediaPlaybackService::class.java).setAction(action),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(viewId, pending)
        views.setBoolean(viewId, "setEnabled", item != null)
      }
      manager.updateAppWidget(ids, views)
      val generation = session.generation
      val artwork = item?.artworkUri?.let { uri ->
        withContext(Dispatchers.IO) {
          runCatching {
            EmbeddedArtworkResolver.decodeArtworkUri(context, uri)?.let { original ->
              val scale = minOf(1f, 192f / maxOf(original.width, original.height))
              if (scale == 1f) original else Bitmap.createScaledBitmap(original,
                (original.width * scale).toInt().coerceAtLeast(1), (original.height * scale).toInt().coerceAtLeast(1), true)
            }
          }.getOrNull()
        }
      }
      if (artwork != null && updates.get() == update && PlaybackSession.state.value.generation == generation) {
        views.setImageViewBitmap(R.id.audio_widget_artwork, artwork)
        manager.updateAppWidget(ids, views)
      }
    }
  }
}