package app.gyrolet.mpvrx.ui.player

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.ColorUtils
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Locale

/**
 * Background playback service for mpv with MediaSession integration.
 * On Android 16+ (API 36), uses progress-centric notifications with chapter segment indicators.
 */
class MediaPlaybackService :
  MediaBrowserServiceCompat(),
  MPVLib.EventObserver,
  KoinComponent {
  companion object {
    private const val TAG = "MediaPlaybackService"
    private const val NOTIFICATION_ID = 1
    private const val NOTIFICATION_CHANNEL_ID = "mpvrx_playback_channel"
    private const val PLAYBACK_STATE_SAVE_INTERVAL_MS = 5000L
    private val DEFAULT_ACCENT_COLOR = Color.rgb(214, 220, 228)
    const val ACTION_NOTIFICATION_PREVIOUS = "app.gyrolet.mpvrx.action.NOTIFICATION_PREVIOUS"
    const val ACTION_NOTIFICATION_NEXT = "app.gyrolet.mpvrx.action.NOTIFICATION_NEXT"

    @Volatile
    internal var thumbnail: Bitmap? = null

    @Volatile
    private var isServiceRunning = false

    fun createNotificationChannel(context: Context) {
      val channel =
        NotificationChannel(
          NOTIFICATION_CHANNEL_ID,
          context.getString(R.string.notification_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = context.getString(R.string.notification_channel_description)
          setShowBadge(false)
          enableLights(false)
          enableVibration(false)
        }

      (context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
        .createNotificationChannel(channel)
    }
  }

  private val binder = MediaPlaybackBinder()
  private lateinit var mediaSession: MediaSessionCompat
  private val playerPreferences: PlayerPreferences by inject()
  private val advancedPreferences: AdvancedPreferences by inject()
  private val browserPreferences: BrowserPreferences by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()

  private var mediaIdentifier = ""
  private var mediaTitle = ""
  private var mediaArtist = ""
  private var mediaUri: String? = null
  private var paused = false
  private var lastNotificationUpdateTime = 0L
  private var lastPlaybackStateSaveTime = 0L
  private val notificationUpdateIntervalMs = 1000L

  // Chapter & progress state for progress-centric notification
  private var chapters: List<ChapterNode> = emptyList()
  private var currentChapterIndex: Int = -1
  private var currentPositionSeconds: Double = 0.0
  private var mediaDurationSeconds: Double = 0.0
  private var accentColor: Int = DEFAULT_ACCENT_COLOR
  private var accentColorDim: Int = ColorUtils.setAlphaComponent(DEFAULT_ACCENT_COLOR, 90)
  private var accentColorDone: Int = ColorUtils.blendARGB(DEFAULT_ACCENT_COLOR, Color.BLACK, 0.28f)
  private var lastPaletteThumbnail: Bitmap? = null
  private val notificationDispatcher = Dispatchers.Default.limitedParallelism(1)
  private val serviceScope = CoroutineScope(SupervisorJob() + notificationDispatcher)
  private var playbackStateSaveJob: Job? = null

  inner class MediaPlaybackBinder : Binder() {
    fun getService() = this@MediaPlaybackService
  }

  override fun onCreate() {
    super.onCreate()
    Log.d(TAG, "Service created")

    isServiceRunning = true

    // Ensure notification channel exists before starting foreground service
    createNotificationChannel(this)

    setupMediaSession()

    // Only add MPV observer if MPV is initialized
    try {
      MPVLib.addObserver(this)
      MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
      MPVLib.observeProperty("media-title", MPVLib.MpvFormat.MPV_FORMAT_STRING)
      MPVLib.observeProperty("metadata/artist", MPVLib.MpvFormat.MPV_FORMAT_STRING)
      MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
      MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
      MPVLib.observeProperty("chapter", MPVLib.MpvFormat.MPV_FORMAT_INT64)
      Log.d(TAG, "MPV observer registered")
    } catch (e: Exception) {
      Log.e(TAG, "Error registering MPV observer", e)
    }
  }

  override fun onBind(intent: Intent): IBinder = binder

  @SuppressLint("ForegroundServiceType")
  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    Log.d(TAG, "Service starting with startId: $startId")

    // Handle media button events
    intent?.let {
      MediaButtonReceiver.handleIntent(mediaSession, it)

      val title = it.getStringExtra("media_title")
      val artist = it.getStringExtra("media_artist")
      val uri = it.getStringExtra("media_uri")
      val identifier = it.getStringExtra("media_identifier")

      if (!title.isNullOrBlank()) {
        mediaTitle = title
        mediaArtist = artist ?: ""
        Log.d(TAG, "Media info from intent: $mediaTitle")
      }
      if (!identifier.isNullOrBlank()) {
        mediaIdentifier = identifier
      }
      if (!uri.isNullOrBlank()) {
        mediaUri = uri
      }
    }

    // Fallback: Read current state from MPV if not provided via intent
    if (mediaTitle.isBlank()) {
      mediaTitle = MPVLib.getPropertyString("media-title") ?: ""
      mediaArtist = MPVLib.getPropertyString("metadata/artist") ?: ""
    }

    paused = MPVLib.getPropertyBoolean("pause") == true
    mediaDurationSeconds = runCatching { MPVLib.getPropertyDouble("duration") }.getOrNull() ?: 0.0
    currentPositionSeconds = runCatching { MPVLib.getPropertyDouble("time-pos") }.getOrNull() ?: 0.0
    currentChapterIndex = runCatching { MPVLib.getPropertyInt("chapter") }.getOrNull() ?: -1
    refreshNotificationPalette()

    updateMediaSession()

    if (!notificationsEnabled()) {
      Log.d(TAG, "Notification style disabled, stopping playback service")
      stopForegroundNotification()
      stopSelf()
      return START_NOT_STICKY
    }

    try {
      val type =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
          0
        }
      ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
      Log.d(TAG, "Foreground service started successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Error starting foreground service", e)
    }

    return START_NOT_STICKY
  }

  override fun onGetRoot(
    clientPackageName: String,
    clientUid: Int,
    rootHints: android.os.Bundle?,
  ) = BrowserRoot("root_id", null)

  override fun onLoadChildren(
    parentId: String,
    result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
  ) {
    result.sendResult(mutableListOf())
  }

  fun setMediaInfo(
    title: String,
    artist: String,
    thumbnail: Bitmap? = null,
    uri: String? = null,
    identifier: String? = null,
  ) {
    serviceScope.launch {
      MediaPlaybackService.thumbnail = thumbnail
      mediaTitle = title
      mediaArtist = artist
      uri?.let { mediaUri = it }
      identifier?.takeIf { it.isNotBlank() }?.let { mediaIdentifier = it }
      refreshNotificationPalette()
      updateMediaSession()
    }
  }

  fun setChapters(chapters: List<ChapterNode>) {
    serviceScope.launch {
      this@MediaPlaybackService.chapters = chapters.sortedBy { it.time }
      currentChapterIndex = runCatching { MPVLib.getPropertyInt("chapter") }.getOrNull() ?: currentChapterIndex
      updateNotification()
    }
  }

  private fun setupMediaSession() {
    mediaSession =
      MediaSessionCompat(this, TAG).apply {
        setCallback(
          object : MediaSessionCompat.Callback() {
            override fun onPlay() {
              Log.d(TAG, "onPlay called")
              MPVLib.setPropertyBoolean("pause", false)
            }

            override fun onPause() {
              Log.d(TAG, "onPause called")
              MPVLib.setPropertyBoolean("pause", true)
            }

            override fun onStop() {
              Log.d(TAG, "onStop called")
              stopSelf()
            }

            override fun onSkipToNext() {
              Log.d(TAG, "onSkipToNext called")
              val duration = MPVLib.getPropertyInt("duration") ?: 0
              val shouldUsePreciseSeeking = playerPreferences.usePreciseSeeking.get() || duration < 120
              val seekMode = if (shouldUsePreciseSeeking) "relative+exact" else "relative+keyframes"
              MPVLib.command("seek", "10", seekMode)
            }

            override fun onSkipToPrevious() {
              Log.d(TAG, "onSkipToPrevious called")
              val duration = MPVLib.getPropertyInt("duration") ?: 0
              val shouldUsePreciseSeeking = playerPreferences.usePreciseSeeking.get() || duration < 120
              val seekMode = if (shouldUsePreciseSeeking) "relative+exact" else "relative+keyframes"
              MPVLib.command("seek", "-10", seekMode)
            }

            override fun onSeekTo(pos: Long) {
              Log.d(TAG, "onSeekTo called: $pos")
              MPVLib.setPropertyDouble("time-pos", pos / 1000.0)
            }
          },
        )

        isActive = true
      }
    sessionToken = mediaSession.sessionToken

    // When using ProgressStyle, deactivate the media session so the system
    // doesn't override our notification with the media player widget.
    // Notification actions still provide play/pause/skip controls.
    if (useProgressNotification()) {
      mediaSession.isActive = false
    }
  }

  private fun currentNotificationStyle(): NotificationStyle =
    advancedPreferences.notificationStyle.get()
      .takeIf { it.isSupportedOn(Build.VERSION.SDK_INT) }
      ?: NotificationStyle.Media

  private fun notificationsEnabled(): Boolean = currentNotificationStyle() != NotificationStyle.None

  private fun useProgressNotification(): Boolean = currentNotificationStyle() == NotificationStyle.Progress

  private fun updateMediaSession() {
    try {
      val title = mediaTitle.ifBlank { "Unknown Video" }
      val duration = (mediaDurationSeconds * 1000).toLong().coerceAtLeast(0L)

      val metadataBuilder =
        MediaMetadataCompat
          .Builder()
          .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
          .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, mediaArtist)
          .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
          .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)

      thumbnail?.let {
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
      }
      mediaSession.setMetadata(metadataBuilder.build())

      val position = (currentPositionSeconds * 1000).toLong().coerceAtLeast(0L)

      val state = if (paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING

      mediaSession.setPlaybackState(
        PlaybackStateCompat
          .Builder()
          .setActions(
            PlaybackStateCompat.ACTION_PLAY or
              PlaybackStateCompat.ACTION_PAUSE or
              PlaybackStateCompat.ACTION_PLAY_PAUSE or
              PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
              PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
              PlaybackStateCompat.ACTION_STOP or
              PlaybackStateCompat.ACTION_SEEK_TO,
          ).setState(state, position, 1.0f)
          .build(),
      )

      updateNotification()
    } catch (e: Exception) {
      Log.e(TAG, "Error updating MediaSession", e)
    }
  }

  private fun updateNotification() {
    if (!notificationsEnabled()) {
      stopForegroundNotification()
      stopSelf()
      return
    }

    try {
      val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.notify(NOTIFICATION_ID, buildNotification())
    } catch (e: Exception) {
      Log.e(TAG, "Error updating notification", e)
    }
  }

  // ==================== Notification Builders ====================

  private fun buildNotification(): Notification =
    if (useProgressNotification()) buildModernNotification() else buildLegacyNotification()

  private fun buildContentIntent(): PendingIntent =
    PendingIntent.getActivity(
      this, 0,
      Intent(this, PlayerActivity::class.java).apply {
        action = "app.gyrolet.mpvrx.action.OPEN_PLAYER_FROM_NOTIFICATION"
        mediaUri?.let { putExtra("uri", it) }
        putExtra("title", mediaTitle)
        putExtra("launch_source", "notification")
        putExtra("internal_launch", true)
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      },
      PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  private fun buildTransportIntent(action: String, requestCode: Int): PendingIntent =
    PendingIntent.getActivity(
      this,
      requestCode,
      Intent(this, PlayerActivity::class.java).apply {
        this.action = action
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      },
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  private fun prevAction() = NotificationCompat.Action(
    android.R.drawable.ic_media_previous, "Previous",
    buildTransportIntent(ACTION_NOTIFICATION_PREVIOUS, 1001),
  )

  private fun playPauseAction() = NotificationCompat.Action(
    if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
    if (paused) "Play" else "Pause",
    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE),
  )

  private fun nextAction() = NotificationCompat.Action(
    android.R.drawable.ic_media_next, "Next",
    buildTransportIntent(ACTION_NOTIFICATION_NEXT, 1002),
  )

  private fun chapterContentText(): String {
    val chapterName = if (currentChapterIndex >= 0) {
      chapters.getOrNull(currentChapterIndex)?.title?.takeIf { it.isNotBlank() }
    } else null
    return chapterName ?: mediaArtist.ifBlank { getString(R.string.notification_playing) }
  }

  private fun chapterLabel(): String {
    val chapterNumber = currentChapterIndex + 1
    return if (chapterNumber > 0 && chapters.isNotEmpty()) {
      getString(R.string.notification_chapter_counter, chapterNumber, chapters.size)
    } else {
      getString(R.string.notification_playing)
    }
  }

  private fun playbackTimeText(): String =
    "${formatSeconds(currentPositionSeconds)} / ${formatSeconds(mediaDurationSeconds)}"

  private fun refreshNotificationPalette() {
    val currentThumbnail = thumbnail
    if (currentThumbnail === lastPaletteThumbnail) return

    // Extract dominant color from thumbnail for system-coherent appearance,
    // falling back to a neutral tone that blends with the system's glassmorphic style
    val dominantColor = currentThumbnail?.let { extractDominantColor(it) }
    accentColor = dominantColor ?: DEFAULT_ACCENT_COLOR
    accentColorDim = ColorUtils.setAlphaComponent(accentColor, 90)
    accentColorDone = ColorUtils.blendARGB(accentColor, Color.BLACK, 0.28f)
    lastPaletteThumbnail = currentThumbnail
  }

  private fun extractDominantColor(bitmap: Bitmap): Int? {
    if (bitmap.isRecycled) return null
    return try {
      val scaled = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
      var r = 0L; var g = 0L; var b = 0L; var count = 0
      for (x in 0 until scaled.width) {
        for (y in 0 until scaled.height) {
          val pixel = scaled.getPixel(x, y)
          r += Color.red(pixel)
          g += Color.green(pixel)
          b += Color.blue(pixel)
          count++
        }
      }
      if (scaled != bitmap) scaled.recycle()
      if (count == 0) return null
      Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    } catch (_: Exception) { null }
  }

  /**
   * Android 16+ (API 36): Progress-centric notification with chapter segment indicators.
   * Follows the same pattern as FaceDown's ProgressStyle implementation.
   * MediaSession must be inactive for the system to render ProgressStyle instead of the
   * media player widget.
   */
  private fun buildModernNotification(): Notification {
    val totalMs = (mediaDurationSeconds * 1000).toLong().coerceAtLeast(1)
    val currentMs = (currentPositionSeconds * 1000).toLong().coerceIn(0, totalMs)
    val remainingMs = totalMs - currentMs
    val liveAccentColor = accentColor
    val systemSurfaceAccent = DEFAULT_ACCENT_COLOR

    val style = NotificationCompat.ProgressStyle()

    if (chapters.isNotEmpty()) {
      val sorted = chapters.sortedBy { it.time }
      for (i in sorted.indices) {
        val startMs = (sorted[i].time * 1000).toLong()
        val endMs = if (i + 1 < sorted.size) (sorted[i + 1].time * 1000).toLong() else totalMs
        val segmentSize = (endMs - startMs).toInt().coerceAtLeast(1)
        val color = when {
          i < currentChapterIndex  -> accentColorDone
          i == currentChapterIndex -> liveAccentColor
          else                     -> accentColorDim
        }
        style.addProgressSegment(
          NotificationCompat.ProgressStyle.Segment(segmentSize).setColor(color),
        )
      }
    } else {
      style.addProgressSegment(
        NotificationCompat.ProgressStyle.Segment(totalMs.toInt())
          .setColor(liveAccentColor),
      )
    }

    val timeText = playbackTimeText()

    val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher_monochrome)
      .setContentTitle(mediaTitle.ifBlank { "Unknown Video" })
      .setContentText(chapterContentText())
      .setSubText(chapterLabel())
      .setLargeIcon(thumbnail)
      .setContentIntent(buildContentIntent())
      .setOngoing(!paused)
      .setAutoCancel(false)
      .setSilent(true)
      .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
      .setColor(systemSurfaceAccent)
      .setColorized(false)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOnlyAlertOnce(true)
      .addAction(prevAction())
      .addAction(playPauseAction())
      .addAction(nextAction())

    // Set ProgressStyle — this sets the visual style to segmented progress
    builder.setWhen(System.currentTimeMillis() + remainingMs)
    builder.setStyle(style.setProgress(currentMs.toInt()))
    builder.setShortCriticalText(timeText)

    return builder.build()
  }

  /**
   * Pre-Android 16: Classic MediaStyle notification with linear progress bar and chapter text.
   * Uses the system notification surface instead of forcing a colorized card tint.
   */
  private fun buildLegacyNotification(): Notification {
    val totalMs = (mediaDurationSeconds * 1000).toLong().coerceAtLeast(1)
    val currentMs = (currentPositionSeconds * 1000).toLong().coerceIn(0, totalMs)

    return NotificationCompat
      .Builder(this, NOTIFICATION_CHANNEL_ID)
      .setContentTitle(mediaTitle.ifBlank { "Unknown Video" })
      .setContentText(chapterContentText())
      .setSubText(playbackTimeText())
      .setSmallIcon(R.drawable.ic_launcher_monochrome)
      .setLargeIcon(thumbnail)
      .setContentIntent(buildContentIntent())
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOnlyAlertOnce(true)
      .setOngoing(!paused)
      .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
      .setColor(DEFAULT_ACCENT_COLOR)
      .setColorized(false)
      .addAction(prevAction())
      .addAction(playPauseAction())
      .addAction(nextAction())
      .setStyle(
        androidx.media.app.NotificationCompat
          .MediaStyle()
          .setMediaSession(mediaSession.sessionToken)
          .setShowActionsInCompactView(0, 1, 2),
      )
      .setProgress(totalMs.toInt(), currentMs.toInt(), false)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  private fun stopForegroundNotification() {
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
      }
    }.onFailure { error ->
      Log.e(TAG, "Error stopping foreground notification", error)
    }

    runCatching {
      val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.cancel(NOTIFICATION_ID)
    }.onFailure { error ->
      Log.e(TAG, "Error canceling playback notification", error)
    }
  }

  private fun formatSeconds(seconds: Double): String {
    val t = seconds.toLong().coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
  }

  // ==================== MPV Event Observers ====================

  override fun eventProperty(property: String) {}

  override fun eventProperty(
    property: String,
    value: Long,
  ) {
    if (property == "chapter") {
      serviceScope.launch {
        currentChapterIndex = value.toInt()
        updateNotification()
      }
    }
  }

  override fun eventProperty(
    property: String,
    value: Boolean,
  ) {
    if (property == "pause") {
      serviceScope.launch {
        paused = value
        updateMediaSession()
        schedulePlaybackStateSave(force = true)
      }
    }
  }

  override fun eventProperty(
    property: String,
    value: String,
  ) {
    when (property) {
      "media-title" -> {
        if (value.isNotBlank()) {
          serviceScope.launch {
            mediaTitle = value
            updateMediaSession()
          }
        }
      }
      "metadata/artist" -> {
        serviceScope.launch {
          mediaArtist = value
          updateMediaSession()
        }
      }
    }
  }

  override fun eventProperty(
    property: String,
    value: Double,
  ) {
    when (property) {
      "time-pos" -> {
        serviceScope.launch {
          currentPositionSeconds = value
          schedulePlaybackStateSave()
          val currentTime = System.currentTimeMillis()
          if (currentTime - lastNotificationUpdateTime >= notificationUpdateIntervalMs) {
            lastNotificationUpdateTime = currentTime
            updateMediaSession()
          }
        }
      }
      "duration" -> {
        serviceScope.launch {
          mediaDurationSeconds = value
        }
      }
    }
  }

  override fun eventProperty(
    property: String,
    value: MPVNode,
  ) {}

  override fun event(eventId: Int, data: MPVNode) {
    if (eventId == MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN) {
      Log.d(TAG, "MPV shutdown event received, stopping service")
      savePlaybackStateBlocking()
      stopSelf()
    }
  }

  private fun schedulePlaybackStateSave(force: Boolean = false) {
    val identifier = mediaIdentifier
    if (identifier.isBlank()) return

    val now = System.currentTimeMillis()
    if (!force && now - lastPlaybackStateSaveTime < PLAYBACK_STATE_SAVE_INTERVAL_MS) return
    lastPlaybackStateSaveTime = now

    playbackStateSaveJob?.cancel()
    playbackStateSaveJob =
      serviceScope.launch(Dispatchers.IO) {
        persistPlaybackState(identifier)
      }
  }

  private fun savePlaybackStateBlocking() {
    val identifier = mediaIdentifier
    if (identifier.isBlank()) return

    playbackStateSaveJob?.cancel()
    runCatching {
      runBlocking(Dispatchers.IO) {
        persistPlaybackState(identifier)
      }
    }.onFailure { error ->
      Log.e(TAG, "Error force-saving playback state", error)
    }
  }

  private suspend fun persistPlaybackState(identifier: String) {
    if (identifier.isBlank()) return

    runCatching {
      val oldState = playbackStateRepository.getVideoDataByTitle(identifier)
      val snapshot = capturePlaybackStateSnapshot(identifier, oldState) ?: return
      val playbackState =
        PlaybackStatePersistence.buildEntity(
          oldState = oldState,
          snapshot = snapshot,
          savePositionOnQuit = playerPreferences.savePositionOnQuit.get(),
          watchedThreshold = browserPreferences.watchedThreshold.get(),
        )
      playbackStateRepository.upsert(playbackState)
      PlaybackStateEvents.notifyChanged(identifier)
    }.onFailure { error ->
      Log.e(TAG, "Error saving playback state from service", error)
    }
  }

  private fun capturePlaybackStateSnapshot(
    identifier: String,
    oldState: PlaybackStateEntity?,
  ): PlaybackStateSnapshot? {
    if (identifier.isBlank()) return null

    return PlaybackStateSnapshot(
      mediaIdentifier = identifier,
      mediaTitle = mediaTitle.ifBlank { identifier },
      currentPosition = readMpvIntSeconds("time-pos", currentPositionSeconds.toInt()),
      duration = readMpvIntSeconds("duration", mediaDurationSeconds.toInt()),
      playbackSpeed = readMpvDouble("speed", oldState?.playbackSpeed ?: DEFAULT_PLAYBACK_STATE_SPEED),
      videoZoom = readMpvDouble("video-zoom", oldState?.videoZoom?.toDouble() ?: 0.0).toFloat(),
      sid = readMpvTrackId("sid", oldState?.sid ?: -1),
      secondarySid = readMpvTrackId("secondary-sid", oldState?.secondarySid ?: -1),
      subDelayMs =
        (readMpvDouble(
          "sub-delay",
          (oldState?.subDelay ?: 0) / PLAYBACK_STATE_MILLISECONDS_TO_SECONDS.toDouble(),
        ) * PLAYBACK_STATE_MILLISECONDS_TO_SECONDS).toInt(),
      subSpeed = readMpvDouble("sub-speed", oldState?.subSpeed ?: DEFAULT_PLAYBACK_STATE_SUB_SPEED),
      aid = readMpvTrackId("aid", oldState?.aid ?: -1),
      audioDelayMs =
        (readMpvDouble(
          "audio-delay",
          (oldState?.audioDelay ?: 0) / PLAYBACK_STATE_MILLISECONDS_TO_SECONDS.toDouble(),
        ) * PLAYBACK_STATE_MILLISECONDS_TO_SECONDS).toInt(),
      externalSubtitles = oldState?.externalSubtitles.orEmpty(),
    )
  }

  private fun readMpvIntSeconds(
    property: String,
    fallback: Int,
  ): Int =
    runCatching {
      MPVLib.getPropertyDouble(property)?.toInt()
        ?: MPVLib.getPropertyInt(property)
        ?: fallback
    }.getOrDefault(fallback)

  private fun readMpvDouble(
    property: String,
    fallback: Double,
  ): Double =
    runCatching {
      MPVLib.getPropertyDouble(property) ?: fallback
    }.getOrDefault(fallback)

  private fun readMpvTrackId(
    property: String,
    fallback: Int,
  ): Int =
    runCatching {
      when (val value = MPVLib.getPropertyString(property)) {
        null -> fallback
        "no" -> -1
        else -> value.toIntOrNull() ?: fallback
      }
    }.getOrDefault(fallback)

  override fun onDestroy() {
    try {
      Log.d(TAG, "Service destroyed")

      isServiceRunning = false
      savePlaybackStateBlocking()

      try {
        MPVLib.removeObserver(this)
      } catch (e: Exception) {
        Log.e(TAG, "Error removing MPV observer", e)
      }

      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
          @Suppress("DEPRECATION")
          stopForeground(true)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error stopping foreground", e)
      }

      try {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
      } catch (e: Exception) {
        Log.e(TAG, "Error canceling notification", e)
      }

      try {
        mediaSession.isActive = false
        mediaSession.release()
      } catch (e: Exception) {
        Log.e(TAG, "Error releasing media session", e)
      }

      thumbnail = null
      lastPaletteThumbnail = null
      serviceScope.cancel()

      Log.d(TAG, "Service cleanup completed")
      super.onDestroy()
    } catch (e: Exception) {
      Log.e(TAG, "Error in onDestroy", e)
      super.onDestroy()
    }
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    Log.d(TAG, "Task removed - killing playback and cleaning up service")
    try {
      try {
        MPVLib.command("quit")
        Log.d(TAG, "MPV quit command sent")
      } catch (e: Exception) {
        Log.e(TAG, "Error sending quit command to MPV", e)
      }

      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
          @Suppress("DEPRECATION")
          stopForeground(true)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error stopping foreground in onTaskRemoved", e)
      }

      try {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
      } catch (e: Exception) {
        Log.e(TAG, "Error canceling notification in onTaskRemoved", e)
      }

      thumbnail = null

      stopSelf()

      android.os.Process.killProcess(android.os.Process.myPid())
    } catch (e: Exception) {
      Log.e(TAG, "Error in onTaskRemoved", e)
      android.os.Process.killProcess(android.os.Process.myPid())
    }
    super.onTaskRemoved(rootIntent)
  }
}

