package app.gyrolet.mpvrx.ui.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import app.gyrolet.mpvrx.MainActivity
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.thumbnail.EmbeddedArtworkResolver
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.ui.player.components.ambientBoxBlur
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

class AudioPlayerWidget : AppWidgetProvider() {
  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    requestUpdate(context)
  }

  override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
    requestUpdate(context)
  }

  override fun onDisabled(context: Context) {
    updates.incrementAndGet()
    artworkCache.evictAll()
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_REFRESH) {
      super.onReceive(context, intent)
      return
    }
    val pending = goAsync()
    val rendering = scope.launch {
      try {
        render(context.applicationContext)
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        android.util.Log.w("AudioPlayerWidget", "Unable to update music widget", error)
      }
    }
    scope.launch {
      try {
        withTimeoutOrNull(8_000L) { rendering.join() }
      } finally {
        pending.finish()
      }
    }
  }

  companion object {
    private const val ACTION_REFRESH = "app.gyrolet.mpvrx.action.REFRESH_AUDIO_WIDGET"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val updates = java.util.concurrent.atomic.AtomicLong()
    private data class WidgetSize(val width: Int, val height: Int, val band: Int, val wide: Boolean)
    private data class ArtworkKey(val uri: String, val size: WidgetSize)
    private val artworkCache = object : LruCache<ArtworkKey, Bitmap>(8 * 1024 * 1024) {
      override fun sizeOf(key: ArtworkKey, value: Bitmap): Int = value.byteCount
    }

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
      val liveItem = session.currentItem.takeIf {
        session.phase in setOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
      }
      val audio = PlaybackSession.audioState.value.takeIf {
        PlaybackSession.usingExoPlayer && it.generation == session.generation && liveItem != null
      }
      val retain = org.koin.java.KoinJavaComponent.get<AdvancedPreferences>(AdvancedPreferences::class.java).enableRecentlyPlayed.get()
      val saved = if (liveItem == null && retain) AudioQueuePersistence.read(context) else null
      if (updates.get() != update) return
      val item = audio?.item ?: liveItem ?: saved?.queue?.currentItem
      val queue = if (liveItem != null) PlaybackSession.queue.value else saved?.queue
      val title = audio?.title?.takeIf(String::isNotBlank)
        ?: if (liveItem != null) PlaybackSession.getPropertyString("media-title")?.takeIf(String::isNotBlank) else null
      val artist = audio?.artist?.takeIf(String::isNotBlank)
        ?: if (liveItem != null) PlaybackSession.getPropertyString("metadata/artist")?.takeIf(String::isNotBlank) else null
      val playing = liveItem != null && !session.paused
      val canControl = liveItem != null || saved != null &&
        org.koin.java.KoinJavaComponent.get<AudioPreferences>(AudioPreferences::class.java).audioEngine.get() == AudioEngineKind.ExoPlayer
      val openIntent = if (liveItem != null) {
        Intent(context, PlayerActivity::class.java).apply {
          action = MediaPlaybackService.ACTION_OPEN_PLAYER
          type = liveItem.mimeType ?: "audio/*".takeIf { liveItem.isDefinitelyAudioOnly() }
          putExtra("uri", liveItem.originalUri)
          putExtra("title", title ?: liveItem.title)
          putExtra("media_identifier", liveItem.stableId)
          putExtra("launch_source", "notification")
          putExtra("internal_launch", true)
          putExtra("is_audio", liveItem.isDefinitelyAudioOnly())
          putExtra("media_library_audio", liveItem.isDefinitelyAudioOnly())
          liveItem.audiobook?.let { book ->
            putExtra(AudiobookPlayback.EXTRA_BOOK_ID, book.bookId)
            putExtra(AudiobookPlayback.EXTRA_TRACK_ID, book.trackId)
          }
        }
      } else Intent(context, MainActivity::class.java)
      openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      val open = PendingIntent.getActivity(context, 7200, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      val sizes = ids.associateWith { id ->
        val options = manager.getAppWidgetOptions(id)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 248
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).takeIf { it > 0 } ?: minWidth
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).takeIf { it > 0 } ?: 128
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: minHeight
        measure(context, maxWidth, minHeight) to measure(context, minWidth, maxHeight)
      }
      fun views(size: WidgetSize): RemoteViews {
        val views = RemoteViews(context.packageName, if (size.wide) R.layout.audio_player_widget_wide else R.layout.audio_player_widget)
        views.setTextViewText(R.id.audio_widget_title, title ?: item?.title?.takeIf(String::isNotBlank) ?: context.getString(R.string.audio_widget_empty))
        views.setTextViewText(R.id.audio_widget_artist, artist ?: item?.artist.orEmpty())
        views.setViewVisibility(R.id.audio_widget_artist, if (size.wide && !(artist ?: item?.artist).isNullOrBlank()) View.VISIBLE else View.GONE)
        views.setImageViewResource(R.id.audio_widget_play, if (playing) R.drawable.audio_widget_pause else R.drawable.audio_widget_play)
        views.setContentDescription(R.id.audio_widget_play, context.getString(if (playing) R.string.audiobook_pause else R.string.ui_play))
        views.setOnClickPendingIntent(R.id.audio_widget_root, open)
        views.setOnClickPendingIntent(R.id.audio_widget_metadata, open)
        val artwork = item?.artworkUri?.let { artworkCache.get(ArtworkKey(it, size)) }
        if (artwork != null) views.setImageViewBitmap(R.id.audio_widget_artwork, artwork)
        views.setViewVisibility(R.id.audio_widget_placeholder, if (artwork == null) View.VISIBLE else View.GONE)
        listOf(
          R.id.audio_widget_previous to MediaPlaybackService.ACTION_NOTIFICATION_PREVIOUS,
          R.id.audio_widget_play to MediaPlaybackService.ACTION_NOTIFICATION_PLAY_PAUSE,
          R.id.audio_widget_next to MediaPlaybackService.ACTION_NOTIFICATION_NEXT,
        ).forEachIndexed { index, (viewId, action) ->
          val available = canControl && when (viewId) {
            R.id.audio_widget_previous -> queue?.let(PlaybackQueueReducer::peekPrevious) != null
            R.id.audio_widget_next -> queue?.let(PlaybackQueueReducer::peekNext) != null
            else -> item != null
          }
          val pending = if (available) PendingIntent.getForegroundService(context, 7201 + index,
            Intent(context, MediaPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else open
          views.setOnClickPendingIntent(viewId, pending)
          views.setBoolean(viewId, "setEnabled", available || !canControl)
          views.setInt(viewId, "setImageAlpha", if (available) 255 else 90)
        }
        return views
      }
      fun publish() {
        if (updates.get() != update || PlaybackSession.state.value.generation != session.generation) return
        sizes.forEach { (id, dimensions) ->
          runCatching { manager.updateAppWidget(id, RemoteViews(views(dimensions.first), views(dimensions.second))) }
            .onFailure { android.util.Log.w("AudioPlayerWidget", "Unable to render music widget", it) }
        }
      }
      publish()
      val uri = item?.artworkUri?.takeIf(String::isNotBlank) ?: return
      val missing = sizes.values.flatMap { listOf(it.first, it.second) }.distinct()
        .filter { artworkCache.get(ArtworkKey(uri, it)) == null }
      if (missing.isEmpty()) return
      val artwork = withContext(Dispatchers.IO) { EmbeddedArtworkResolver.decodeArtworkUri(context, uri) } ?: return
      withContext(Dispatchers.Default) {
        for (size in missing) {
          if (updates.get() != update) break
          artworkCache.put(ArtworkKey(uri, size), composeArtwork(artwork, size))
        }
      }
      publish()
    }

    private fun measure(context: Context, widthDp: Int, heightDp: Int): WidgetSize {
      val density = context.resources.displayMetrics.density
      val scale = minOf(1f, 768f / (maxOf(widthDp, heightDp) * density))
      val wide = widthDp >= 240 && context.resources.configuration.fontScale <= 1.3f
      val band = context.resources.getDimension(if (wide) R.dimen.audio_widget_band_wide else R.dimen.audio_widget_band_compact)
      return WidgetSize(
        (widthDp * density * scale).roundToInt().coerceAtLeast(1),
        (heightDp * density * scale).roundToInt().coerceAtLeast(1),
        (band * scale).roundToInt().coerceAtLeast(1), wide,
      )
    }

    private fun composeArtwork(source: Bitmap, size: WidgetSize): Bitmap {
      val cover = if (source.config == Bitmap.Config.HARDWARE) checkNotNull(source.copy(Bitmap.Config.ARGB_8888, false)) else source
      val result = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(result)
      val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
      val scale = maxOf(size.width.toFloat() / cover.width, size.height.toFloat() / cover.height)
      val left = (size.width - cover.width * scale) / 2f
      val top = (size.height - cover.height * scale) / 2f
      canvas.drawBitmap(cover, null, RectF(left, top, left + cover.width * scale, top + cover.height * scale), paint)
      if (cover !== source) cover.recycle()
      val regionHeight = (size.band * 2).coerceAtMost(size.height)
      val regionTop = size.height - regionHeight
      val small = Bitmap.createBitmap((size.width / 6).coerceAtLeast(1), (regionHeight / 6).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
      Canvas(small).drawBitmap(result, Rect(0, regionTop, size.width, size.height), Rect(0, 0, small.width, small.height), paint)
      val pixels = IntArray(small.width * small.height)
      small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
      val channels = FloatArray(pixels.size * 3)
      pixels.forEachIndexed { index, color ->
        channels[index * 3] = Color.red(color).toFloat()
        channels[index * 3 + 1] = Color.green(color).toFloat()
        channels[index * 3 + 2] = Color.blue(color).toFloat()
      }
      ambientBoxBlur(channels, FloatArray(channels.size), small.width, small.height, radius = 3, passes = 3)
      for (index in pixels.indices) {
        pixels[index] = Color.rgb(channels[index * 3].roundToInt(), channels[index * 3 + 1].roundToInt(), channels[index * 3 + 2].roundToInt())
      }
      small.setPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
      val bounds = RectF(0f, regionTop.toFloat(), size.width.toFloat(), size.height.toFloat())
      val layer = canvas.saveLayer(bounds, null)
      canvas.drawBitmap(small, null, bounds, paint)
      paint.shader = LinearGradient(0f, bounds.top, 0f, bounds.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
      paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
      canvas.drawRect(bounds, paint)
      canvas.restoreToCount(layer)
      small.recycle()
      return result
    }
  }
}