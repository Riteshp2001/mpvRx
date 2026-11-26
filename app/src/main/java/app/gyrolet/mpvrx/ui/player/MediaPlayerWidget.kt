package app.gyrolet.mpvrx.ui.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
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
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.domain.thumbnail.getArtwork
import org.koin.java.KoinJavaComponent
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
        app.gyrolet.mpvrx.utils.logging.Log.w(TAG, "Unable to update media widget", error)
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
    internal const val ACTION_REFRESH = "app.gyrolet.mpvrx.action.REFRESH_MEDIA_WIDGET"
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

    private data class PillArtworkKey(
      val uri: String,
      val width: Int,
      val height: Int,
      val artSize: Int,
    )

    private data class PillArtwork(
      val body: Bitmap,
      val disc: Bitmap,
    )

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

    private val pillArtworkCache = object : LruCache<PillArtworkKey, PillArtwork>(6 * 1024 * 1024) {
      override fun sizeOf(key: PillArtworkKey, value: PillArtwork): Int =
        value.body.byteCount + value.disc.byteCount
    }

    private val pillUpdates = java.util.concurrent.atomic.AtomicLong()

    fun requestUpdate(context: Context) {
      val manager = AppWidgetManager.getInstance(context)
      val standardIds = manager.getAppWidgetIds(ComponentName(context, MediaPlayerWidget::class.java))
      val pillIds = manager.getAppWidgetIds(ComponentName(context, MediaPlayerPillWidget::class.java))
      if (standardIds.isNotEmpty()) {
        context.sendBroadcast(Intent(context, MediaPlayerWidget::class.java).setAction(ACTION_REFRESH))
      }
      if (pillIds.isNotEmpty()) {
        context.sendBroadcast(Intent(context, MediaPlayerPillWidget::class.java).setAction(ACTION_REFRESH))
      }
    }

    internal fun requestPillUpdate(context: Context) {
      val manager = AppWidgetManager.getInstance(context)
      if (manager.getAppWidgetIds(ComponentName(context, MediaPlayerPillWidget::class.java)).isEmpty()) return
      context.sendBroadcast(Intent(context, MediaPlayerPillWidget::class.java).setAction(ACTION_REFRESH))
    }

    internal fun clearArtworkCache() {
      updates.incrementAndGet()
      artworkCache.evictAll()
      clearPillCache()
    }

    private fun artworkIdentity(item: PlaybackItem?, repository: ThumbnailRepository): String? =
      item?.let { "${it.originalUri}|${it.artworkUri}|${repository.cacheRevision.value}" }

    internal fun clearPillCache() {
      pillUpdates.incrementAndGet()
      pillArtworkCache.evictAll()
    }

    private suspend fun render(context: Context) {
      val update = updates.incrementAndGet()
      val manager = AppWidgetManager.getInstance(context)
      val ids = manager.getAppWidgetIds(ComponentName(context, MediaPlayerWidget::class.java))
      if (ids.isEmpty()) return

      val repository = KoinJavaComponent.get<ThumbnailRepository>(ThumbnailRepository::class.java)
      val revision = repository.cacheRevision.value
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
        PlaybackSession.getPropertyString("metadata/by-key/artist")?.takeIf(String::isNotBlank)
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
        views.setImageViewResource(R.id.media_widget_placeholder_halo, haloDrawable(palette.dark, size.wide))
        views.setInt(R.id.media_widget_placeholder_halo, "setColorFilter", palette.accent)
        views.setInt(R.id.media_widget_placeholder_halo, "setImageAlpha", if (item == null) 220 else 150)
        views.setImageViewResource(R.id.media_widget_placeholder, R.drawable.media_widget_music_note)
        views.setInt(R.id.media_widget_placeholder, "setColorFilter", palette.foreground)
        val artwork = item?.let { artworkCache.get(ArtworkKey(checkNotNull(artworkIdentity(item, repository)), size)) }
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
        if (updates.get() != update || revision != repository.cacheRevision.value || PlaybackSession.state.value.generation != session.generation) return
        sizes.forEach { (id, dimensions) ->
          val remoteViews = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dimensions.responsive.isNotEmpty()) {
            RemoteViews(dimensions.responsive.mapValues { (_, size) -> views(size) })
          } else {
            RemoteViews(views(dimensions.landscape), views(dimensions.portrait))
          }
          runCatching { manager.updateAppWidget(id, remoteViews) }
            .onFailure { error -> app.gyrolet.mpvrx.utils.logging.Log.w(TAG, "Unable to render media widget", error) }
        }
      }

      publish()
      val uri = artworkIdentity(item, repository) ?: return
      val missing = allSizes.filter { artworkCache.get(ArtworkKey(uri, it)) == null }
      if (missing.isEmpty()) return
      val artwork = repository.getArtwork(item?.originalUri, item?.artworkUri, item?.isDefinitelyAudioOnly() == true) ?: return
      if (revision != repository.cacheRevision.value) return
      withContext(Dispatchers.Default) {
        for (size in missing) {
          if (updates.get() != update) break
          artworkCache.put(ArtworkKey(uri, size), composeArtwork(artwork, size))
        }
      }
      publish()
    }

    internal suspend fun renderPill(context: Context) {
      val update = pillUpdates.incrementAndGet()
      val manager = AppWidgetManager.getInstance(context)
      val ids = manager.getAppWidgetIds(ComponentName(context, MediaPlayerPillWidget::class.java))
      if (ids.isEmpty()) return

      val repository = KoinJavaComponent.get<ThumbnailRepository>(ThumbnailRepository::class.java)
      val revision = repository.cacheRevision.value
      val session = PlaybackSession.state.value
      val item = session.currentItem.takeIf {
        session.phase in setOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
      }
      val queue = PlaybackSession.queue.value
      val title = item?.let {
        PlaybackSession.getPropertyString("media-title")?.takeIf(String::isNotBlank)
          ?: it.title?.takeIf(String::isNotBlank)
      } ?: context.getString(R.string.media_widget_empty)
      val playing = item != null && !session.paused
      val palette = resolvePalette(context)
      val open = PendingIntent.getActivity(
        context,
        7350,
        openIntent(context, item, title),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
      val density = context.resources.displayMetrics.density
      val pillHeightPx = context.resources.getDimensionPixelSize(R.dimen.media_widget_pill_height)
      val pillArtPx = context.resources.getDimensionPixelSize(R.dimen.media_widget_pill_art)
      val uri = artworkIdentity(item, repository)

      fun servicePendingIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getForegroundService(
          context,
          requestCode,
          Intent(context, MediaPlaybackService::class.java).setAction(action),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

      for (id in ids) {
        if (pillUpdates.get() != update || revision != repository.cacheRevision.value) return
        val options = manager.getAppWidgetOptions(id)
        val widthDp =
          options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            .takeIf { it > 0 }
            ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 }
            ?: 250
        val widthPx = (widthDp * density).roundToInt().coerceIn(1, 1024)
        val key = uri?.let { PillArtworkKey(it, widthPx, pillHeightPx, pillArtPx) }
        val cached = key?.let(pillArtworkCache::get)
        val fallbackBody = composePillFallback(widthPx, pillHeightPx)

        fun pillViews(artwork: PillArtwork?): RemoteViews {
          val views = RemoteViews(context.packageName, R.layout.media_player_pill_widget)
          views.setImageViewBitmap(R.id.media_widget_pill_background, artwork?.body ?: fallbackBody)
          if (artwork?.disc != null) {
            views.setImageViewBitmap(R.id.media_widget_pill_artwork, artwork.disc)
          } else {
            views.setImageViewResource(R.id.media_widget_pill_artwork, R.drawable.media_widget_pill_art_placeholder)
          }
          views.setTextViewText(R.id.media_widget_pill_title, title)
          views.setImageViewResource(
            R.id.media_widget_pill_play,
            if (playing) R.drawable.media_widget_pause else R.drawable.media_widget_play,
          )
          views.setContentDescription(
            R.id.media_widget_pill_play,
            context.getString(if (playing) R.string.audiobook_pause else R.string.ui_play),
          )
          views.setOnClickPendingIntent(R.id.media_widget_pill_root, open)

          val previousAvailable = item != null && PlaybackQueueReducer.peekPrevious(queue) != null
          val nextAvailable = item != null && PlaybackQueueReducer.peekNext(queue) != null
          val shuffleAvailable = item != null && item.audiobook == null
          val controls =
            listOf(
              Triple(R.id.media_widget_pill_favorite, item != null, MediaPlaybackService.ACTION_NOTIFICATION_FAVORITE),
              Triple(R.id.media_widget_pill_previous, previousAvailable, MediaPlaybackService.ACTION_NOTIFICATION_PREVIOUS),
              Triple(R.id.media_widget_pill_play, item != null, MediaPlaybackService.ACTION_NOTIFICATION_PLAY_PAUSE),
              Triple(R.id.media_widget_pill_next, nextAvailable, MediaPlaybackService.ACTION_NOTIFICATION_NEXT),
              Triple(R.id.media_widget_pill_shuffle, shuffleAvailable, MediaPlaybackService.ACTION_NOTIFICATION_SHUFFLE),
            )
          controls.forEachIndexed { index, (viewId, available, action) ->
            views.setOnClickPendingIntent(
              viewId,
              if (available) servicePendingIntent(7360 + index, action) else open,
            )
            views.setBoolean(viewId, "setEnabled", available)
            views.setInt(viewId, "setImageAlpha", if (available) 255 else 72)
            views.setInt(viewId, "setBackgroundResource", R.drawable.media_widget_control_background_dark)
            val tint =
              if (viewId == R.id.media_widget_pill_shuffle && queue.shuffleEnabled) palette.accent else Color.WHITE
            views.setInt(viewId, "setColorFilter", tint)
          }
          return views
        }

        runCatching { manager.updateAppWidget(id, pillViews(cached)) }
        if (cached == null && uri != null) {
          val source = repository.getArtwork(item?.originalUri, item?.artworkUri, item?.isDefinitelyAudioOnly() == true)
          if (source != null && pillUpdates.get() == update && revision == repository.cacheRevision.value) {
            val composed = withContext(Dispatchers.Default) {
              composePillArtwork(source, widthPx, pillHeightPx, pillArtPx)
            }
            pillArtworkCache.put(checkNotNull(key), composed)
            if (pillUpdates.get() == update) {
              runCatching { manager.updateAppWidget(id, pillViews(composed)) }
            }
          }
        }
      }
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

    private fun composePillFallback(width: Int, height: Int): Bitmap {
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      canvas.drawRect(
        0f,
        0f,
        width.toFloat(),
        height.toFloat(),
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          shader =
            LinearGradient(
              0f,
              0f,
              0f,
              height.toFloat(),
              intArrayOf(0xFF2E3446.toInt(), 0xFF1B2130.toInt(), 0xFF07090E.toInt()),
              floatArrayOf(0f, 0.45f, 1f),
              Shader.TileMode.CLAMP,
            )
        },
      )
      canvas.drawRect(
        0f,
        0f,
        width.toFloat(),
        height.toFloat(),
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          shader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0x40000000, 0x70000000, Shader.TileMode.CLAMP)
        },
      )
      return roundedBitmap(bitmap, height / 2f, 210).also {
        if (it !== bitmap) bitmap.recycle()
      }
    }

    private fun composePillArtwork(source: Bitmap, width: Int, height: Int, artSize: Int): PillArtwork {
      val cover =
        if (source.config == Bitmap.Config.HARDWARE) {
          checkNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
        } else {
          source
        }
      val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
      val workingWidth = minOf(64, width).coerceAtLeast(8)
      val workingHeight = (workingWidth * height / width.coerceAtLeast(1)).coerceAtLeast(4)
      val small = Bitmap.createBitmap(workingWidth, workingHeight, Bitmap.Config.ARGB_8888)
      drawCenterCrop(Canvas(small), cover, workingWidth, workingHeight, paint)
      val pixels = IntArray(workingWidth * workingHeight)
      small.getPixels(pixels, 0, workingWidth, 0, 0, workingWidth, workingHeight)
      val channels = FloatArray(pixels.size * 3)
      pixels.forEachIndexed { index, color ->
        channels[index * 3] = Color.red(color).toFloat()
        channels[index * 3 + 1] = Color.green(color).toFloat()
        channels[index * 3 + 2] = Color.blue(color).toFloat()
      }
      ambientBoxBlur(
        channels,
        FloatArray(channels.size),
        workingWidth,
        workingHeight,
        radius = (workingWidth * 0.10f).roundToInt().coerceAtLeast(1),
        passes = 3,
      )
      for (index in pixels.indices) {
        pixels[index] =
          Color.rgb(
            channels[index * 3].roundToInt().coerceIn(0, 255),
            channels[index * 3 + 1].roundToInt().coerceIn(0, 255),
            channels[index * 3 + 2].roundToInt().coerceIn(0, 255),
          )
      }
      small.setPixels(pixels, 0, workingWidth, 0, 0, workingWidth, workingHeight)

      val rawBody = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      Canvas(rawBody).apply {
        drawBitmap(small, null, Rect(0, 0, width, height), paint)
        drawRect(
          0f,
          0f,
          width.toFloat(),
          height.toFloat(),
          Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0x40000000, 0x70000000, Shader.TileMode.CLAMP)
          },
        )
      }
      small.recycle()
      val body = roundedBitmap(rawBody, height / 2f, 210)
      if (body !== rawBody) rawBody.recycle()

      val square = Bitmap.createBitmap(artSize, artSize, Bitmap.Config.ARGB_8888)
      drawCenterCrop(Canvas(square), cover, artSize, artSize, paint)
      val disc = roundedBitmap(square, artSize / 2f, 255)
      if (disc !== square) square.recycle()
      if (cover !== source) cover.recycle()
      return PillArtwork(body = body, disc = disc)
    }

    private fun roundedBitmap(source: Bitmap, radius: Float, alpha: Int): Bitmap {
      val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
      Canvas(out).drawRoundRect(
        RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()),
        radius,
        radius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          this.alpha = alpha
          shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        },
      )
      return out
    }

    private fun drawCenterCrop(canvas: Canvas, source: Bitmap, width: Int, height: Int, paint: Paint) {
      val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
      val sourceWidth = width / scale
      val sourceHeight = height / scale
      val left = (source.width - sourceWidth) / 2f
      val top = (source.height - sourceHeight) / 2f
      canvas.drawBitmap(
        source,
        Rect(
          left.toInt(),
          top.toInt(),
          (left + sourceWidth).toInt().coerceAtMost(source.width),
          (top + sourceHeight).toInt().coerceAtMost(source.height),
        ),
        Rect(0, 0, width, height),
        paint,
      )
    }

    private fun composeArtwork(source: Bitmap, size: WidgetSize): Bitmap {
      val cover =
        if (source.config == Bitmap.Config.HARDWARE) {
          checkNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
        } else {
          source
        }
      val result = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(result)
      val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
      drawCenterCrop(canvas, cover, size.width, size.height, paint)
      if (cover !== source) cover.recycle()

      val regionHeight = (size.band * 2).coerceIn(1, size.height)
      val regionTop = size.height - regionHeight
      val workingWidth = (size.width / 4).coerceAtLeast(2)
      val workingHeight = (regionHeight * workingWidth / size.width.coerceAtLeast(1)).coerceAtLeast(2)
      val base = Bitmap.createBitmap(workingWidth, workingHeight, Bitmap.Config.ARGB_8888)
      Canvas(base).drawBitmap(
        result,
        Rect(0, regionTop, size.width, size.height),
        Rect(0, 0, workingWidth, workingHeight),
        paint,
      )
      val basePixels = IntArray(workingWidth * workingHeight)
      base.getPixels(basePixels, 0, workingWidth, 0, 0, workingWidth, workingHeight)
      base.recycle()
      val baseChannels = FloatArray(basePixels.size * 3)
      basePixels.forEachIndexed { index, color ->
        baseChannels[index * 3] = Color.red(color).toFloat()
        baseChannels[index * 3 + 1] = Color.green(color).toFloat()
        baseChannels[index * 3 + 2] = Color.blue(color).toFloat()
      }

      val bounds = RectF(0f, regionTop.toFloat(), size.width.toFloat(), size.height.toFloat())
      val stops = floatArrayOf(0.28f, 0.50f, 0.70f, 0.86f)
      val strengths = floatArrayOf(0.035f, 0.070f, 0.110f, 0.155f)
      val bandInWorkingPixels = size.band * workingHeight.toFloat() / regionHeight
      strengths.forEachIndexed { index, strength ->
        val channels = baseChannels.copyOf()
        ambientBoxBlur(
          channels,
          FloatArray(channels.size),
          workingWidth,
          workingHeight,
          radius = (bandInWorkingPixels * strength).roundToInt().coerceAtLeast(1),
          passes = 3,
        )
        val pixels = IntArray(basePixels.size)
        for (pixelIndex in pixels.indices) {
          pixels[pixelIndex] =
            Color.rgb(
              channels[pixelIndex * 3].roundToInt().coerceIn(0, 255),
              channels[pixelIndex * 3 + 1].roundToInt().coerceIn(0, 255),
              channels[pixelIndex * 3 + 2].roundToInt().coerceIn(0, 255),
            )
        }
        val level = Bitmap.createBitmap(workingWidth, workingHeight, Bitmap.Config.ARGB_8888)
        level.setPixels(pixels, 0, workingWidth, 0, 0, workingWidth, workingHeight)
        val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val layer = canvas.saveLayer(bounds, null)
        canvas.drawBitmap(level, null, bounds, layerPaint)
        val fadeStart = regionTop + stops[index] * regionHeight
        val fadeEnd = regionTop + (stops[index] + 0.26f).coerceAtMost(1f) * regionHeight
        layerPaint.shader =
          LinearGradient(
            0f,
            fadeStart,
            0f,
            maxOf(fadeEnd, fadeStart + 1f),
            Color.TRANSPARENT,
            Color.BLACK,
            Shader.TileMode.CLAMP,
          )
        layerPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawRect(bounds, layerPaint)
        canvas.restoreToCount(layer)
        level.recycle()
      }

      val scrimTop = (size.height - size.band * 1.2f).coerceAtLeast(0f)
      canvas.drawRect(
        0f,
        scrimTop,
        size.width.toFloat(),
        size.height.toFloat(),
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          shader =
            LinearGradient(
              0f,
              scrimTop,
              0f,
              size.height.toFloat(),
              intArrayOf(Color.TRANSPARENT, 0x40000000, 0xB8000000.toInt()),
              floatArrayOf(0f, 0.45f, 1f),
              Shader.TileMode.CLAMP,
            )
        },
      )
      return result
    }
  }
}

class MediaPlayerPillWidget : AppWidgetProvider() {
  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    MediaPlayerWidget.requestPillUpdate(context)
  }

  override fun onAppWidgetOptionsChanged(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
    newOptions: Bundle,
  ) {
    MediaPlayerWidget.requestPillUpdate(context)
  }

  override fun onDisabled(context: Context) {
    MediaPlayerWidget.clearPillCache()
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != MediaPlayerWidget.ACTION_REFRESH) {
      super.onReceive(context, intent)
      return
    }
    val pending = goAsync()
    val rendering =
      scope.launch {
        try {
          MediaPlayerWidget.renderPill(context.applicationContext)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          app.gyrolet.mpvrx.utils.logging.Log.w(TAG, "Unable to update media pill widget", error)
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
    private const val TAG = "MediaPlayerPillWidget"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  }
}
