package app.gyrolet.mpvrx.ui.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import app.gyrolet.mpvrx.MainActivity
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.thumbnail.EmbeddedArtworkResolver
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.ui.player.components.ambientBoxBlur
import app.gyrolet.mpvrx.ui.theme.CustomThemeDefinition
import app.gyrolet.mpvrx.ui.theme.DarkMode
import app.gyrolet.mpvrx.ui.theme.resolveAppColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

class MediaPlayerWidget : AppWidgetProvider() {
  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    requestUpdate(context)
  }

  override fun onAppWidgetOptionsChanged(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
    newOptions: Bundle,
  ) {
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
        android.util.Log.w(TAG, "Unable to update media widget", error)
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
    private const val TAG = "MediaPlayerWidget"
    private const val ACTION_REFRESH = "app.gyrolet.mpvrx.action.REFRESH_MEDIA_WIDGET"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val updates = java.util.concurrent.atomic.AtomicLong()

    private data class WidgetSize(
      val width: Int,
      val height: Int,
      val band: Int,
      val wide: Boolean,
    )

    private data class WidgetDimensions(
      val landscape: WidgetSize,
      val portrait: WidgetSize,
      val responsive: Map<SizeF, WidgetSize>,
    ) {
      val allSizes: List<WidgetSize>
        get() = (responsive.values + landscape + portrait).distinct()
    }

    private data class ArtworkKey(val uri: String, val size: WidgetSize)

    private data class WidgetPalette(
      val dark: Boolean,
      val background: Int,
      val foreground: Int,
      val secondary: Int,
      val accent: Int,
    )

    private val artworkCache = object : LruCache<ArtworkKey, Bitmap>(8 * 1024 * 1024) {
      override fun sizeOf(key: ArtworkKey, value: Bitmap): Int = value.byteCount
    }

    fun requestUpdate(context: Context) {
      val manager = AppWidgetManager.getInstance(context)
      if (manager.getAppWidgetIds(ComponentName(context, MediaPlayerWidget::class.java)).isEmpty()) return
      context.sendBroadcast(Intent(context, MediaPlayerWidget::class.java).setAction(ACTION_REFRESH))
    }

    private suspend fun render(context: Context) {
      val update = updates.incrementAndGet()
      val manager = AppWidgetManager.getInstance(context)
      val ids = manager.getAppWidgetIds(ComponentName(context, MediaPlayerWidget::class.java))
      if (ids.isEmpty()) return

      val session = PlaybackSession.state.value
      val item = session.currentItem.takeIf {
        session.phase in setOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
      }
      val queue = PlaybackSession.queue.value
      val title = item?.let {
        PlaybackSession.getPropertyString("media-title")?.takeIf(String::isNotBlank)
          ?: it.title?.takeIf(String::isNotBlank)
      }
      val artist = item?.let {
        PlaybackSession.getPropertyString("metadata/artist")?.takeIf(String::isNotBlank)
          ?: it.artist?.takeIf(String::isNotBlank)
      }
      val playing = item != null && !session.paused
      val palette = resolvePalette(context)
      val open = PendingIntent.getActivity(
        context,
        7200,
        openIntent(context, item, title),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
      val sizes = ids.associateWith { id ->
        val options = manager.getAppWidgetOptions(id)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 180
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).takeIf { it > 0 } ?: minWidth
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).takeIf { it > 0 } ?: 112
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: minHeight
        val responsive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          @Suppress("DEPRECATION")
          options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            .orEmpty()
            .filter { it.width > 0f && it.height > 0f }
            .distinct()
            .associateWith { size -> measure(context, size.width.roundToInt(), size.height.roundToInt()) }
        } else {
          emptyMap()
        }
        WidgetDimensions(
          landscape = measure(context, maxWidth, minHeight),
          portrait = measure(context, minWidth, maxHeight),
          responsive = responsive,
        )
      }
      val allSizes = sizes.values.flatMap(WidgetDimensions::allSizes).distinct()
      val themeCacheKey = "widget-theme://${palette.background}/${palette.accent}"
      val themeBackgrounds = withContext(Dispatchers.Default) {
        allSizes.associateWith { size ->
          val key = ArtworkKey(themeCacheKey, size)
          artworkCache.get(key) ?: composeThemeBackground(size, palette).also { artworkCache.put(key, it) }
        }
      }

      fun views(size: WidgetSize): RemoteViews {
        val layout = if (size.wide) R.layout.media_player_widget_wide else R.layout.media_player_widget
        val views = RemoteViews(context.packageName, layout)
        views.setTextViewText(R.id.media_widget_title, title ?: context.getString(R.string.media_widget_empty))
        views.setTextViewText(
          R.id.media_widget_artist,
          artist ?: item?.let {
            context.getString(
              if (it.isDefinitelyAudioOnly()) R.string.media_widget_audio else R.string.media_widget_video,
            )
          }.orEmpty(),
        )
        views.setViewVisibility(R.id.media_widget_artist, if (size.wide && item != null) View.VISIBLE else View.GONE)
        views.setTextColor(R.id.media_widget_title, palette.foreground)
        views.setTextColor(R.id.media_widget_artist, palette.secondary)
        views.setImageViewResource(
          R.id.media_widget_play,
          if (playing) R.drawable.media_widget_pause else R.drawable.media_widget_play,
        )
        views.setContentDescription(
          R.id.media_widget_play,
          context.getString(if (playing) R.string.audiobook_pause else R.string.ui_play),
        )
        views.setOnClickPendingIntent(R.id.media_widget_root, open)
        views.setOnClickPendingIntent(R.id.media_widget_metadata, open)
        views.setInt(
          R.id.media_widget_root,
          "setBackgroundResource",
          if (palette.dark) R.drawable.media_widget_background_dark else R.drawable.media_widget_background_light,
        )
        views.setInt(
          R.id.media_widget_scrim,
          "setBackgroundResource",
          if (palette.dark) R.drawable.media_widget_scrim_dark else R.drawable.media_widget_scrim_light,
        )
        views.setImageViewResource(R.id.media_widget_placeholder_halo, haloDrawable(palette.dark, size.wide))
        views.setInt(R.id.media_widget_placeholder_halo, "setColorFilter", palette.accent)
        views.setInt(R.id.media_widget_placeholder_halo, "setImageAlpha", if (item == null) 220 else 150)
        views.setImageViewResource(R.id.media_widget_placeholder, R.drawable.media_widget_music_note)
        views.setInt(R.id.media_widget_placeholder, "setColorFilter", palette.foreground)
        val artwork = item?.artworkUri?.let { artworkCache.get(ArtworkKey(it, size)) }
        val showPlaceholder = artwork == null
        views.setImageViewBitmap(R.id.media_widget_artwork, artwork ?: themeBackgrounds.getValue(size))
        views.setViewVisibility(R.id.media_widget_artwork, View.VISIBLE)
        views.setViewVisibility(R.id.media_widget_placeholder_halo, if (showPlaceholder) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.media_widget_placeholder, if (showPlaceholder) View.VISIBLE else View.GONE)

        listOf(
          R.id.media_widget_previous to MediaPlaybackService.ACTION_NOTIFICATION_PREVIOUS,
          R.id.media_widget_play to MediaPlaybackService.ACTION_NOTIFICATION_PLAY_PAUSE,
          R.id.media_widget_next to MediaPlaybackService.ACTION_NOTIFICATION_NEXT,
        ).forEachIndexed { index, (viewId, action) ->
          val available = item != null && when (viewId) {
            R.id.media_widget_previous -> PlaybackQueueReducer.peekPrevious(queue) != null
            R.id.media_widget_next -> PlaybackQueueReducer.peekNext(queue) != null
            else -> true
          }
          val pending = if (available) {
            PendingIntent.getForegroundService(
              context,
              7201 + index,
              Intent(context, MediaPlaybackService::class.java).setAction(action),
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
          } else {
            open
          }
          views.setOnClickPendingIntent(viewId, pending)
          views.setBoolean(viewId, "setEnabled", available)
          views.setInt(viewId, "setImageAlpha", if (available) 255 else 72)
          views.setInt(
            viewId,
            "setColorFilter",
            if (viewId == R.id.media_widget_play) palette.accent else palette.foreground,
          )
          views.setInt(
            viewId,
            "setBackgroundResource",
            if (palette.dark) {
              R.drawable.media_widget_control_background_dark
            } else {
              R.drawable.media_widget_control_background_light
            },
          )
        }
        return views
      }

      fun publish() {
        if (updates.get() != update || PlaybackSession.state.value.generation != session.generation) return
        sizes.forEach { (id, dimensions) ->
          val remoteViews = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dimensions.responsive.isNotEmpty()) {
            RemoteViews(dimensions.responsive.mapValues { (_, size) -> views(size) })
          } else {
            RemoteViews(views(dimensions.landscape), views(dimensions.portrait))
          }
          runCatching { manager.updateAppWidget(id, remoteViews) }
            .onFailure { error -> android.util.Log.w(TAG, "Unable to render media widget", error) }
        }
      }

      publish()
      val uri = item?.artworkUri?.takeIf(String::isNotBlank) ?: return
      val missing = allSizes.filter { artworkCache.get(ArtworkKey(uri, it)) == null }
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

    private fun openIntent(context: Context, item: PlaybackItem?, title: String?): Intent {
      val intent = if (item == null) {
        Intent(context, MainActivity::class.java)
      } else {
        Intent(context, PlayerActivity::class.java).apply {
          action = MediaPlaybackService.ACTION_OPEN_PLAYER
          type = item.mimeType ?: if (item.isDefinitelyAudioOnly()) "audio/*" else "video/*"
          putExtra("uri", item.originalUri)
          putExtra("title", title ?: item.title)
          putExtra("media_identifier", item.stableId)
          putExtra("launch_source", "widget")
          putExtra("internal_launch", true)
          putExtra("is_audio", item.isDefinitelyAudioOnly())
          putExtra("media_library_audio", item.isDefinitelyAudioOnly())
          item.audiobook?.let { book ->
            putExtra(AudiobookPlayback.EXTRA_BOOK_ID, book.bookId)
            putExtra(AudiobookPlayback.EXTRA_TRACK_ID, book.trackId)
          }
        }
      }
      return intent.addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
      )
    }

    private fun resolvePalette(context: Context): WidgetPalette {
      val preferences = org.koin.java.KoinJavaComponent.get<AppearancePreferences>(AppearancePreferences::class.java)
      val dark = when (preferences.darkMode.get()) {
        DarkMode.Dark -> true
        DarkMode.Light -> false
        DarkMode.System ->
          context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
      }
      val selectedCustomThemeName = preferences.selectedCustomThemeName.get()
      val serializedCustomThemes = preferences.customTheme.get()
      val customTheme = CustomThemeDefinition.parseCollection(serializedCustomThemes)
        .firstOrNull { it.name == selectedCustomThemeName }
        ?: CustomThemeDefinition.parse(serializedCustomThemes).takeIf { selectedCustomThemeName.isBlank() }
      val colors = resolveAppColorScheme(
        context = context,
        appTheme = preferences.appTheme.get(),
        customTheme = customTheme,
        useDarkTheme = dark,
        amoledMode = preferences.amoledMode.get(),
      )
      return WidgetPalette(
        dark = dark,
        background = colors.background.toArgb(),
        foreground = colors.onBackground.toArgb(),
        secondary = colors.onSurfaceVariant.toArgb(),
        accent = colors.primary.toArgb(),
      )
    }

    private fun haloDrawable(dark: Boolean, wide: Boolean): Int = when {
      wide && dark -> R.drawable.media_widget_placeholder_halo_wide_dark
      wide -> R.drawable.media_widget_placeholder_halo_wide_light
      dark -> R.drawable.media_widget_placeholder_halo_dark
      else -> R.drawable.media_widget_placeholder_halo_light
    }

    private fun measure(context: Context, widthDp: Int, heightDp: Int): WidgetSize {
      val density = context.resources.displayMetrics.density
      val scale = minOf(1f, 768f / (maxOf(widthDp, heightDp) * density))
      val wide = widthDp >= 240 && context.resources.configuration.fontScale <= 1.3f
      val band = context.resources.getDimension(
        if (wide) R.dimen.media_widget_band_wide else R.dimen.media_widget_band_compact,
      )
      return WidgetSize(
        width = (widthDp * density * scale).roundToInt().coerceAtLeast(1),
        height = (heightDp * density * scale).roundToInt().coerceAtLeast(1),
        band = (band * scale).roundToInt().coerceAtLeast(1),
        wide = wide,
      )
    }

    private fun composeThemeBackground(size: WidgetSize, palette: WidgetPalette): Bitmap {
      val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      canvas.drawColor(palette.background)
      val accent = Color.argb(
        if (palette.dark) 76 else 52,
        Color.red(palette.accent),
        Color.green(palette.accent),
        Color.blue(palette.accent),
      )
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
          0f,
          0f,
          size.width.toFloat(),
          size.height.toFloat(),
          accent,
          Color.TRANSPARENT,
          Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, size.width.toFloat(), size.height.toFloat(), this)
      }
      return bitmap
    }

    private fun composeArtwork(source: Bitmap, size: WidgetSize): Bitmap {
      val cover = if (source.config == Bitmap.Config.HARDWARE) {
        checkNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
      } else {
        source
      }
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
      val small = Bitmap.createBitmap(
        (size.width / 6).coerceAtLeast(1),
        (regionHeight / 6).coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
      )
      Canvas(small).drawBitmap(
        result,
        Rect(0, regionTop, size.width, size.height),
        Rect(0, 0, small.width, small.height),
        paint,
      )
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
        pixels[index] = Color.rgb(
          channels[index * 3].roundToInt(),
          channels[index * 3 + 1].roundToInt(),
          channels[index * 3 + 2].roundToInt(),
        )
      }
      small.setPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
      val bounds = RectF(0f, regionTop.toFloat(), size.width.toFloat(), size.height.toFloat())
      val layer = canvas.saveLayer(bounds, null)
      canvas.drawBitmap(small, null, bounds, paint)
      paint.shader = LinearGradient(
        0f,
        bounds.top,
        0f,
        bounds.bottom,
        Color.TRANSPARENT,
        Color.BLACK,
        Shader.TileMode.CLAMP,
      )
      paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
      canvas.drawRect(bounds, paint)
      canvas.restoreToCount(layer)
      small.recycle()
      return result
    }
  }
}