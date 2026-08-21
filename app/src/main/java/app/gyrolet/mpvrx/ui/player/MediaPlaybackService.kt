/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

@file:Suppress("DEPRECATION")

package app.gyrolet.mpvrx.ui.player

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.domain.thumbnail.EmbeddedArtworkResolver
import app.gyrolet.mpvrx.domain.torrent.TorrentStreamingEngine
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

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

    // Final state saves must outlive serviceScope, which is cancelled during teardown.
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val NOTIFICATION_ID = 1
    private const val NOTIFICATION_CHANNEL_ID = "mpvrx_playback_channel"
    private const val PLAYBACK_STATE_SAVE_INTERVAL_MS = 5000L
    private const val PROGRESS_NOTIFICATION_UPDATE_INTERVAL_MS = 2000L
    private const val MEDIA_NOTIFICATION_UPDATE_INTERVAL_MS = 5000L
    private const val MAX_MEDIA_SESSION_QUEUE_ITEMS = 200
    private val DEFAULT_ACCENT_COLOR = Color.rgb(214, 220, 228)
    const val ACTION_OPEN_PLAYER = "app.gyrolet.mpvrx.action.OPEN_PLAYER_FROM_NOTIFICATION"
    const val ACTION_NOTIFICATION_PREVIOUS = "app.gyrolet.mpvrx.action.NOTIFICATION_PREVIOUS"
    const val ACTION_NOTIFICATION_PLAY_PAUSE = "app.gyrolet.mpvrx.action.NOTIFICATION_PLAY_PAUSE"
    const val ACTION_NOTIFICATION_NEXT = "app.gyrolet.mpvrx.action.NOTIFICATION_NEXT"
    const val ACTION_NOTIFICATION_STOP = "app.gyrolet.mpvrx.action.NOTIFICATION_STOP"

    @Volatile
    internal var thumbnail: Bitmap? = null

    @Volatile
    private var isServiceRunning = false

    @Volatile
    private var activeInstance: MediaPlaybackService? = null

    /**
     * True only while playback is detached from the foreground Activity.
     *
     * PlayerActivity historically used this query in onStart() as a signal to destroy the
     * playback service. Keeping the physical service alive while the Activity owns the surface
     * avoids tearing down and recreating the MediaSession/foreground notification on every
     * notification tap. This mirrors the single long-lived notification-owner model used by
     * MediaSessionService-style players.
     */
    fun isRunning(): Boolean = isServiceRunning && !activityForeground

    fun isForegroundActive(): Boolean = activeInstance?.foregroundReady == true

    /**
     * True while a PlayerActivity is the active foreground owner of the shared playback session
     * (e.g. the full player is visible and playing, including audio-only media that has no
     * attached video surface). The service must not steal audio focus from it.
     *
     * Entering the foreground is an ownership handoff, not a service teardown: the service keeps
     * the MediaSession/notification alive but releases audio focus to PlayerActivity. Leaving the
     * foreground clears the handoff marker so detached playback can take ownership again.
     */
    @Volatile
    var activityForeground = false
      set(value) {
        field = value
        activeInstance?.let { service ->
          if (value) {
            service.handingBackToActivity = true
            service.abandonAudioOwnership()
          } else {
            service.handingBackToActivity = false
          }
        }
      }

    internal fun relinquishMediaSessionToActivity() {
      activeInstance?.deactivateMediaSession()
    }

    internal fun takeAudioOwnershipForDetachedPlayback(): Boolean = activeInstance?.takeAudioOwnership() == true

    /**
     * Marks that playback is being handed back to a foreground Activity (e.g. reopening the
     * player from the Mini Player / playback notification). Release the service-owned focus
     * immediately, but never mutate mpv's pause state: the foreground Activity will acquire
     * focus in its normal lifecycle. This makes the handoff lossless even when notification
     * re-entry and service teardown are delivered in different Android lifecycle turns.
     */
    internal fun prepareForActivityHandoff() {
      activeInstance?.let { service ->
        service.handingBackToActivity = true
        service.abandonAudioOwnership()
      }
    }

    internal fun isActivityHandoffInProgress(): Boolean = activeInstance?.handingBackToActivity == true

    /** Releases every service-owned MPV access before an Activity destroys the global core. */
    internal fun prepareForMpvShutdown() {
      activeInstance?.let { service ->
        runCatching { service.releaseMpvAccessBeforeShutdown() }
          .onFailure { error -> Log.e(TAG, "Error preparing service for MPV shutdown", error) }
      }
    }

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
  private val audioPreferences: AudioPreferences by inject()
  private val browserPreferences: BrowserPreferences by inject()
  private val gesturePreferences: GesturePreferences by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()
  private val torrentStreamingEngine: TorrentStreamingEngine by inject()

  private var mediaIdentifier = ""
  private var mediaTitle = ""
  private var mediaArtist = ""
  private var mediaUri: String? = null
  private var activeArtworkUri: String? = null
  private var paused = false
  private var playbackSpeed = 1.0f
  private var activeQueueItemId: Long = MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong()
  private var publishedQueueIndexes: Map<Long, Int> = emptyMap()

  // Playlist state — mirrored from PlayerActivity so the notification intent can restore it
  private var notificationIsAudio: Boolean = false
  @Volatile
  private var lastNotificationUpdateTime = 0L
  @Volatile
  private var lastPublishedPositionSeconds = 0.0
  @Volatile
  private var lastPlaybackStateSaveTime = 0L

  // Chapter & progress state for progress-centric notification
  private var chapters: List<ChapterNode> = emptyList()
  private var currentChapterIndex: Int = -1
  @Volatile
  private var currentPositionSeconds: Double = 0.0
  private var mediaDurationSeconds: Double = 0.0
  private var accentColor: Int = DEFAULT_ACCENT_COLOR
  private var accentColorDim: Int = ColorUtils.setAlphaComponent(DEFAULT_ACCENT_COLOR, 90)
  private var accentColorDone: Int = ColorUtils.blendARGB(DEFAULT_ACCENT_COLOR, Color.BLACK, 0.28f)
  private var lastPaletteThumbnail: Bitmap? = null
  private var lastThumbnailSource: WeakReference<Bitmap>? = null
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var playbackStateSaveJob: Job? = null
  private var mpvAccessReleased = false
  private var usesAudioBackgroundPlayback = false
  private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

  // Mutated from the framework's audio-focus callback thread as well as serviceScope and the
  // Activity's main thread; stale reads here strand focus and permanently duck other apps.
  @Volatile private var audioFocusRequest: AudioFocusRequest? = null

  @Volatile private var ownsAudioFocus = false

  @Volatile private var hasAudioFocus = false

  @Volatile
  private var handingBackToActivity = false

  @Volatile private var resumeAfterFocusGain = false

  @Volatile private var volumeBeforeDuck: Double? = null
  private var noisyReceiverRegistered = false
  @Volatile
  private var foregroundReady = false
  private val audioFocusChangeListener =
    AudioManager.OnAudioFocusChangeListener { change ->
      when (change) {
        AudioManager.AUDIOFOCUS_LOSS -> {
          resumeAfterFocusGain = false
          // A foreground Activity is taking over playback; do not pause the shared session.
          if (handingBackToActivity) {
            abandonAudioOwnership()
            return@OnAudioFocusChangeListener
          }
          PlaybackSession.setPropertyBoolean("pause", true)
          abandonAudioOwnership()
        }
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
          hasAudioFocus = false
          resumeAfterFocusGain =
            resumeAfterFocusGain || PlaybackSession.getPropertyBoolean("pause") == false
          PlaybackSession.setPropertyBoolean("pause", true)
        }
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
          if (volumeBeforeDuck == null) {
            PlaybackSession.getPropertyDouble("volume")?.let { volume ->
              volumeBeforeDuck = volume
              PlaybackSession.setPropertyDouble("volume", volume * 0.5)
            }
          }
        }
        AudioManager.AUDIOFOCUS_GAIN -> {
          if (!ownsAudioFocus) return@OnAudioFocusChangeListener
          hasAudioFocus = true
          restoreDuckedVolume()
          if (resumeAfterFocusGain) PlaybackSession.setPropertyBoolean("pause", false)
          resumeAfterFocusGain = false
        }
      }
    }
  private val noisyReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && ownsAudioFocus) {
          PlaybackSession.setPropertyBoolean("pause", true)
        }
      }
    }

  inner class MediaPlaybackBinder : Binder() {
    fun getService() = this@MediaPlaybackService
  }

  fun isForegroundReady(): Boolean = foregroundReady

  private fun deactivateMediaSession() {
    foregroundReady = false
    if (::mediaSession.isInitialized) mediaSession.isActive = false
  }

  override fun onCreate() {
    super.onCreate()
    Log.d(TAG, "Service created")

    activeInstance = this
    isServiceRunning = true
    mpvAccessReleased = false
    handingBackToActivity = false

    // Ensure notification channel exists before starting foreground service
    createNotificationChannel(this)

    setupMediaSession()
    ContextCompat.registerReceiver(
      this,
      noisyReceiver,
      IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    noisyReceiverRegistered = true

    serviceScope.launch {
      combine(
        audioPreferences.backgroundPlayback.changes(),
        audioPreferences.audioBackgroundPlayback.changes(),
      ) { videoEnabled, audioEnabled ->
        if (usesAudioBackgroundPlayback) audioEnabled else videoEnabled
      }.drop(1).collect { enabled ->
        if (!enabled) {
          Log.d(TAG, "Background playback disabled; stopping service")
          stopDetachedPlaybackIfNeeded()
          stopForegroundNotification()
          stopSelf()
        }
      }
    }

    serviceScope.launch {
      PlaybackSession.queue.collect(::syncQueueState)
    }

    serviceScope.launch {
      advancedPreferences.notificationStyle.changes().drop(1).collect {
        if (!notificationsEnabled()) {
          stopDetachedPlaybackIfNeeded()
          stopForegroundNotification()
          stopSelf()
        } else {
          updateMediaSessionPlaybackState()
          updateNotification()
        }
      }
    }

    // Only add MPV observer if MPV is initialized
    try {
      PlaybackSession.addObserver(this)
      PlaybackSession.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
      PlaybackSession.observeProperty("media-title", MPVLib.MpvFormat.MPV_FORMAT_STRING)
      PlaybackSession.observeProperty("metadata/artist", MPVLib.MpvFormat.MPV_FORMAT_STRING)
      PlaybackSession.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
      PlaybackSession.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
      PlaybackSession.observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
      PlaybackSession.observeProperty("chapter", MPVLib.MpvFormat.MPV_FORMAT_INT64)
      PlaybackSession.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
      Log.d(TAG, "MPV observer registered")
    } catch (e: Exception) {
      Log.e(TAG, "Error registering MPV observer", e)
    }
  }

  override fun onBind(intent: Intent): IBinder? =
    if (intent.action == MediaBrowserServiceCompat.SERVICE_INTERFACE) super.onBind(intent) else binder

  @SuppressLint("ForegroundServiceType")
  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    Log.d(TAG, "Service starting with startId: $startId")

    // MediaButtonReceiver launches us with startForegroundService(). Android 16 enforces the
    // promotion deadline even when there is no live playback session and this start will be
    // stopped immediately, so no validation or action branch may run before this call.
    if (!startForegroundNotification()) {
      stopSelf(startId)
      return START_NOT_STICKY
    }

    if (!PlaybackSession.isInitialized) {
      Log.w(TAG, "Ignoring playback service start without a live playback session")
      stopForegroundNotification()
      stopSelf(startId)
      return START_NOT_STICKY
    }

    // Handle media button events
    intent?.let {
      when (it.action) {
        ACTION_NOTIFICATION_PREVIOUS -> {
          playPreviousFromSession()
          if (foregroundReady) return START_NOT_STICKY
        }
        ACTION_NOTIFICATION_PLAY_PAUSE -> {
          togglePlaybackFromNotification()
          if (foregroundReady) return START_NOT_STICKY
        }
        ACTION_NOTIFICATION_NEXT -> {
          playNextFromSession()
          if (foregroundReady) return START_NOT_STICKY
        }
        ACTION_NOTIFICATION_STOP -> {
          stopPlaybackAndService()
          return START_NOT_STICKY
        }
        else -> MediaButtonReceiver.handleIntent(mediaSession, it)
      }

      val title = it.getStringExtra("media_title")
      val artist = it.getStringExtra("media_artist")
      val uri = it.getStringExtra("media_uri")
      val identifier = it.getStringExtra("media_identifier")
      if (it.hasExtra("audio_background_playback")) {
        usesAudioBackgroundPlayback = it.getBooleanExtra("audio_background_playback", false)
      }

      if (!title.isNullOrBlank()) {
        mediaTitle = FileTypeUtils.stripExtension(title)
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
      mediaTitle = FileTypeUtils.stripExtension(PlaybackSession.getPropertyString("media-title") ?: "")
      mediaArtist = PlaybackSession.getPropertyString("metadata/artist") ?: ""
    }

    paused = PlaybackSession.getPropertyBoolean("pause") == true
    playbackSpeed =
      PlaybackSession
        .getPropertyDouble("speed")
        ?.toFloat()
        ?.takeIf { it.isFinite() && it > 0f }
        ?: 1.0f
    mediaDurationSeconds = runCatching { PlaybackSession.getPropertyDouble("duration") }.getOrNull() ?: 0.0
    currentPositionSeconds = runCatching { PlaybackSession.getPropertyDouble("time-pos") }.getOrNull() ?: 0.0
    currentChapterIndex = runCatching { PlaybackSession.getPropertyInt("chapter") }.getOrNull() ?: -1

    // Only take audio ownership for a truly detached session. Notification re-entry first moves
    // the process-wide session back to READY and/or marks a handoff. Re-requesting focus from the
    // service in that window can make PlayerActivity receive AUDIOFOCUS_LOSS and pause the video.
    val sessionState = PlaybackSession.state.value
    val shouldTakeDetachedAudioFocus =
      sessionState.phase == PlaybackPhase.BACKGROUND &&
        !sessionState.surfaceAttached &&
        !activityForeground &&
        !handingBackToActivity
    if (shouldTakeDetachedAudioFocus && !takeAudioOwnership()) {
      PlaybackSession.setPropertyBoolean("pause", true)
    }

    refreshNotificationPalette()

    updateMediaSessionMetadata()
    updateMediaSessionPlaybackState()

    if (!notificationsEnabled()) {
      Log.d(TAG, "Notification style disabled, stopping playback service")
      stopDetachedPlaybackIfNeeded()
      stopForegroundNotification()
      stopSelf()
      return START_NOT_STICKY
    }

    if (!startForegroundNotification()) {
      foregroundReady = false
      mediaSession.isActive = false
      stopSelf(startId)
      return START_NOT_STICKY
    }
    foregroundReady = true
    mediaSession.isActive = true
    Log.d(TAG, "Foreground service started successfully")

    return START_NOT_STICKY
  }

  @SuppressLint("ForegroundServiceType")
  private fun startForegroundNotification(): Boolean =
    try {
      val type =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
          0
        }
      ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
      true
    } catch (error: Exception) {
      Log.e(TAG, "Error starting foreground service", error)
      false
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
      val resolvedTitle = FileTypeUtils.stripExtension(title)
      val resolvedIdentifier = identifier?.takeIf { it.isNotBlank() }
      // A metadata-only Activity sync must not erase artwork already resolved for this queue item.
      // Item changes are cleared explicitly in applySessionItem before the next cover is loaded.
      val artworkChanged = thumbnail?.let(::replaceOwnedThumbnail) ?: false
      val metadataChanged =
        mediaTitle != resolvedTitle ||
          mediaArtist != artist ||
          (uri != null && mediaUri != uri) ||
          (resolvedIdentifier != null && mediaIdentifier != resolvedIdentifier) ||
          artworkChanged

      mediaTitle = resolvedTitle
      mediaArtist = artist
      uri?.let { mediaUri = it }
      resolvedIdentifier?.let { mediaIdentifier = it }
      if (metadataChanged) {
        refreshNotificationPalette()
        updateMediaSessionMetadata()
      }
      updateMediaSessionPlaybackState()
      updateNotification()
    }
  }

  private fun replaceOwnedThumbnail(source: Bitmap?): Boolean {
    if (source == null && thumbnail == null) return false
    if (source != null && source === lastThumbnailSource?.get() && thumbnail?.isRecycled == false) return false
    val ownedCopy =
      source?.let { bitmap ->
        runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
      }
    val previous = thumbnail
    lastThumbnailSource = source?.let(::WeakReference)
    thumbnail = ownedCopy
    if (lastPaletteThumbnail === previous) lastPaletteThumbnail = null
    previous?.takeIf { it !== ownedCopy && !it.isRecycled }?.recycle()
    return previous !== ownedCopy
  }

  fun setPlaylistInfo(isAudio: Boolean) {
    notificationIsAudio = isAudio
  }

  fun takeAudioOwnership(): Boolean {
    // During a foreground handoff, returning true means "ownership is intentionally not needed".
    // Do not request focus and bounce it back to PlayerActivity; that focus ping-pong is exactly
    // what can turn a notification tap into an unexpected pause.
    if (activityForeground || handingBackToActivity) return true
    if (ownsAudioFocus) {
      if (!hasAudioFocus) resumeAfterFocusGain = true
      return hasAudioFocus
    }
    val request =
      audioFocusRequest ?: AudioFocusRequest
        .Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
          AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build(),
        ).setOnAudioFocusChangeListener(audioFocusChangeListener)
        .build()
        .also { audioFocusRequest = it }
    hasAudioFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    ownsAudioFocus = hasAudioFocus
    return hasAudioFocus
  }

  private fun abandonAudioOwnership() {
    restoreDuckedVolume()
    if (!ownsAudioFocus) {
      resumeAfterFocusGain = false
      return
    }
    val request = audioFocusRequest
    if (request != null) {
      audioManager.abandonAudioFocusRequest(request)
    } else {
      Log.w(TAG, "Owned audio focus without a request; focus may remain held")
    }
    audioFocusRequest = null
    ownsAudioFocus = false
    hasAudioFocus = false
    resumeAfterFocusGain = false
  }

  private fun restoreDuckedVolume() {
    volumeBeforeDuck?.let { volume -> PlaybackSession.setPropertyDouble("volume", volume) }
    volumeBeforeDuck = null
  }

  private fun playNextFromSession(): Boolean {
    schedulePlaybackStateSave(force = true)
    val item = PlaybackSession.playNext()
    if (item == null) {
      refreshTransportControls()
      return false
    }
    applySessionItem(item)
    return true
  }

  private fun playPreviousFromSession() {
    schedulePlaybackStateSave(force = true)
    PlaybackSession.playPrevious()?.let(::applySessionItem) ?: refreshTransportControls()
  }

  private fun handleMediaPreviousAction() {
    when (gesturePreferences.mediaPreviousGesture.get()) {
      SingleActionGesture.Seek -> seekByConfiguredInterval(direction = -1)
      SingleActionGesture.PlayPause -> togglePlaybackFromNotification()
      SingleActionGesture.Custom -> PlaybackSession.command("keypress", CustomKeyCodes.MediaPrevious.keyCode)
      SingleActionGesture.None -> Unit
    }
  }

  private fun handleMediaNextAction() {
    when (gesturePreferences.mediaNextGesture.get()) {
      SingleActionGesture.Seek -> seekByConfiguredInterval(direction = 1)
      SingleActionGesture.PlayPause -> togglePlaybackFromNotification()
      SingleActionGesture.Custom -> PlaybackSession.command("keypress", CustomKeyCodes.MediaNext.keyCode)
      SingleActionGesture.None -> Unit
    }
  }

  private fun handleMediaPlayAction(shouldPlay: Boolean) {
    when (gesturePreferences.mediaPlayGesture.get()) {
      SingleActionGesture.PlayPause -> {
        if (shouldPlay && !PlaybackSession.state.value.surfaceAttached && !takeAudioOwnership()) return
        PlaybackSession.setPropertyBoolean("pause", !shouldPlay)
        refreshTransportControls()
      }
      SingleActionGesture.Custom -> PlaybackSession.command("keypress", CustomKeyCodes.MediaPlay.keyCode)
      SingleActionGesture.Seek,
      SingleActionGesture.None,
      -> Unit
    }
  }

  private fun seekByConfiguredInterval(direction: Int) {
    val seconds = gesturePreferences.doubleTapToSeekDuration.get() * direction
    val seekMode =
      if (playerPreferences.usePreciseSeeking.get()) {
        "relative+exact"
      } else {
        "relative+keyframes"
      }
    PlaybackSession.command("seek", seconds.toString(), seekMode)
    refreshTransportControls()
  }

  private fun applySessionItem(item: PlaybackItem) {
    val itemChanged = mediaIdentifier != item.stableId || mediaUri != item.originalUri
    val artworkChanged = activeArtworkUri != item.artworkUri
    notificationIsAudio = resolveNotificationIsAudio(item, notificationIsAudio)
    mediaIdentifier = item.stableId
    mediaTitle = FileTypeUtils.stripExtension(item.title.orEmpty()).ifBlank { getString(R.string.player_unknown_video) }
    mediaArtist = item.artist.orEmpty()
    mediaUri = item.originalUri
    activeArtworkUri = item.artworkUri
    currentPositionSeconds = 0.0
    lastPublishedPositionSeconds = 0.0
    mediaDurationSeconds = 0.0
    paused = false
    playbackSpeed = 1.0f
    if (itemChanged || artworkChanged) {
      chapters = emptyList()
      currentChapterIndex = -1
      replaceOwnedThumbnail(null)
      refreshNotificationPalette()
    }
    updateMediaSessionMetadata()
    updateMediaSessionPlaybackState()
    updateNotification()
    loadSessionArtwork(item)
  }

  private fun loadSessionArtwork(item: PlaybackItem) {
    val artworkUri = item.artworkUri?.takeIf { it.isNotBlank() } ?: return
    val expectedIdentifier = item.stableId
    serviceScope.launch {
      val loaded =
        withContext(Dispatchers.IO) {
          EmbeddedArtworkResolver.decodeArtworkUri(this@MediaPlaybackService, artworkUri)
        } ?: return@launch
      if (mediaIdentifier != expectedIdentifier) {
        loaded.recycle()
        return@launch
      }
      val changed = replaceOwnedThumbnail(loaded)
      loaded.recycle()
      if (changed) {
        refreshNotificationPalette()
        updateMediaSessionMetadata()
        updateNotification()
      }
    }
  }

  private fun resolveNotificationIsAudio(
    item: PlaybackItem,
    fallback: Boolean,
  ): Boolean {
    val mimeType = item.mimeType.orEmpty()
    if (mimeType.startsWith("audio/", ignoreCase = true)) return true
    if (mimeType.startsWith("video/", ignoreCase = true)) return false

    val extension =
      item.originalUri
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
    return when (extension) {
      in FileTypeUtils.AUDIO_EXTENSIONS -> true
      in FileTypeUtils.VIDEO_EXTENSIONS -> false
      else -> fallback
    }
  }

  private fun syncQueueState(queueState: PlaybackQueueState) {
    if (!::mediaSession.isInitialized) return
    publishMediaSessionQueue(queueState)
    mediaSession.setRepeatMode(
      when (queueState.repeatMode) {
        RepeatMode.OFF -> PlaybackStateCompat.REPEAT_MODE_NONE
        RepeatMode.ONE -> PlaybackStateCompat.REPEAT_MODE_ONE
        RepeatMode.ALL -> PlaybackStateCompat.REPEAT_MODE_ALL
      },
    )
    mediaSession.setShuffleMode(
      if (queueState.shuffleEnabled) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE,
    )

    val currentItem = queueState.currentItem
    val currentTitle =
      currentItem?.title?.let { FileTypeUtils.stripExtension(it) }.orEmpty().ifBlank {
        getString(R.string.player_unknown_video)
      }
    if (currentItem != null &&
      (currentItem.stableId != mediaIdentifier ||
        currentItem.originalUri != mediaUri ||
        currentTitle != mediaTitle ||
        currentItem.artist.orEmpty() != mediaArtist ||
        currentItem.artworkUri != activeArtworkUri)
    ) {
      applySessionItem(currentItem)
    } else {
      refreshTransportControls()
    }
  }

  private fun publishMediaSessionQueue(queueState: PlaybackQueueState) {
    if (queueState.items.isEmpty()) {
      publishedQueueIndexes = emptyMap()
      activeQueueItemId = MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong()
      mediaSession.setQueue(emptyList<MediaSessionCompat.QueueItem>())
      return
    }

    val halfWindow = MAX_MEDIA_SESSION_QUEUE_ITEMS / 2
    val firstIndex =
      (queueState.currentIndex - halfWindow)
        .coerceAtLeast(0)
        .coerceAtMost((queueState.items.size - MAX_MEDIA_SESSION_QUEUE_ITEMS).coerceAtLeast(0))
    val lastExclusive = (firstIndex + MAX_MEDIA_SESSION_QUEUE_ITEMS).coerceAtMost(queueState.items.size)
    val usedIds = mutableSetOf<Long>()
    val indexesById = LinkedHashMap<Long, Int>(lastExclusive - firstIndex)
    val published =
      (firstIndex until lastExclusive).map { index ->
        val item = queueState.items[index]
        var queueId = stableQueueId(item)
        if (queueId == MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong()) queueId = 0L
        while (!usedIds.add(queueId)) {
          queueId++
          if (queueId == MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong()) queueId++
        }
        indexesById[queueId] = index
        MediaSessionCompat.QueueItem(
          MediaDescriptionCompat
            .Builder()
            .setMediaId(item.stableId)
            .setTitle(item.title?.takeIf { it.isNotBlank() } ?: getString(R.string.player_unknown_video))
            .setSubtitle(item.artist?.takeIf { it.isNotBlank() })
            .build(),
          queueId,
        )
      }

    publishedQueueIndexes = indexesById
    activeQueueItemId = indexesById.entries.firstOrNull { it.value == queueState.currentIndex }?.key
      ?: MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong()
    mediaSession.setQueue(published)
  }

  private fun stableQueueId(item: PlaybackItem): Long =
    item.stableId
      .substringAfterLast(':')
      .take(16)
      .toULongOrNull(16)
      ?.toLong()
      ?: item.stableId.hashCode().toLong()

  private fun refreshTransportControls() {
    updateMediaSessionPlaybackState()
    updateNotification()
  }

  private fun togglePlaybackFromNotification() {
    val shouldPlay = PlaybackSession.getPropertyBoolean("pause") != false
    if (shouldPlay && !PlaybackSession.state.value.surfaceAttached && !takeAudioOwnership()) return
    paused = !shouldPlay
    PlaybackSession.setPropertyBoolean("pause", paused)
    refreshTransportControls()
  }

  private fun stopPlaybackAndService() {
    handingBackToActivity = false
    schedulePlaybackStateSave(force = true)
    torrentStreamingEngine.stopStream()
    PlaybackSession.stop(clearQueue = false)
    paused = true
    mediaSession.setPlaybackState(
      PlaybackStateCompat
        .Builder()
        .setActions(0L)
        .setState(PlaybackStateCompat.STATE_STOPPED, sanitizedPositionMs(), 0f, SystemClock.elapsedRealtime())
        .build(),
    )
    abandonAudioOwnership()
    stopForegroundNotification()
    stopSelf()
  }

  private fun stopDetachedPlaybackIfNeeded() {
    // Never kill the shared PlaybackSession media while an Activity is taking it back over.
    if (handingBackToActivity) return
    if (PlaybackSession.state.value.surfaceAttached) return
    torrentStreamingEngine.stopStream()
    PlaybackSession.stop(clearQueue = false)
  }

  private fun handleDetachedEndOfFile() {
    if (PlaybackSession.state.value.surfaceAttached || !foregroundReady) return
    val queueState = PlaybackSession.queue.value
    val autoplay = if (notificationIsAudio) playerPreferences.autoplayNextAudio.get() else playerPreferences.autoplayNextVideo.get()
    when {
      queueState.repeatMode == RepeatMode.ONE -> {
        PlaybackSession.command("seek", "0", "absolute")
        PlaybackSession.setPropertyBoolean("pause", false)
      }
      autoplay || queueState.repeatMode == RepeatMode.ALL -> {
        if (!playNextFromSession()) stopPlaybackAndService()
      }
      else -> stopPlaybackAndService()
    }
  }

  fun setChapters(chapters: List<ChapterNode>) {
    serviceScope.launch {
      this@MediaPlaybackService.chapters =
        chapters
          .filter { it.time.isFinite() && it.time >= 0f }
          .distinctBy { it.time }
          .sortedBy { it.time }
      currentChapterIndex =
        runCatching { PlaybackSession.getPropertyInt("chapter") }
          .getOrNull()
          ?.takeIf { it in this@MediaPlaybackService.chapters.indices }
          ?: -1
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
              handleMediaPlayAction(shouldPlay = true)
            }

            override fun onPause() {
              Log.d(TAG, "onPause called")
              handleMediaPlayAction(shouldPlay = false)
            }

            override fun onStop() {
              Log.d(TAG, "onStop called")
              stopPlaybackAndService()
            }

            override fun onSkipToNext() {
              Log.d(TAG, "onSkipToNext called")
              handleMediaNextAction()
            }

            override fun onSkipToPrevious() {
              Log.d(TAG, "onSkipToPrevious called")
              handleMediaPreviousAction()
            }

            override fun onSkipToQueueItem(id: Long) {
              val index = publishedQueueIndexes[id] ?: return
              schedulePlaybackStateSave(force = true)
              PlaybackSession.playQueueItem(index)?.let(::applySessionItem)
            }

            override fun onSeekTo(pos: Long) {
              Log.d(TAG, "onSeekTo called: $pos")
              val duration = sanitizedDurationMs()
              val resolvedPosition = pos.coerceIn(0L, duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
              currentPositionSeconds = resolvedPosition / 1000.0
              PlaybackSession.setPropertyDouble("time-pos", currentPositionSeconds)
              refreshTransportControls()
            }

            override fun onSetRepeatMode(repeatMode: Int) {
              val resolvedMode =
                when (repeatMode) {
                  PlaybackStateCompat.REPEAT_MODE_NONE -> RepeatMode.OFF
                  PlaybackStateCompat.REPEAT_MODE_ONE -> RepeatMode.ONE
                  PlaybackStateCompat.REPEAT_MODE_ALL -> RepeatMode.ALL
                  else -> return
                }
              PlaybackSession.setRepeatMode(resolvedMode)
            }

            override fun onSetShuffleMode(shuffleMode: Int) {
              when (shuffleMode) {
                PlaybackStateCompat.SHUFFLE_MODE_NONE -> PlaybackSession.setShuffleEnabled(false)
                PlaybackStateCompat.SHUFFLE_MODE_ALL -> PlaybackSession.setShuffleEnabled(true)
              }
            }
          },
        )

        setPlaybackToLocal(AudioManager.STREAM_MUSIC)
        isActive = false
      }
    sessionToken = mediaSession.sessionToken
  }

  private fun currentNotificationStyle(): NotificationStyle =
    advancedPreferences.notificationStyle
      .get()
      .takeIf { it.isSupportedOn(Build.VERSION.SDK_INT) }
      ?: NotificationStyle.Media

  private fun notificationsEnabled(): Boolean = currentNotificationStyle() != NotificationStyle.None

  private fun useProgressNotification(): Boolean = currentNotificationStyle() == NotificationStyle.Progress

  private fun updateMediaSessionMetadata() {
    try {
      val title = mediaTitle.ifBlank { getString(R.string.player_unknown_video) }
      val metadataBuilder =
        MediaMetadataCompat
          .Builder()
          .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaIdentifier)
          .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
          .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, mediaArtist)
          .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
          .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, sanitizedDurationMs())

      thumbnail?.let {
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
      }
      mediaSession.setMetadata(metadataBuilder.build())
      mediaSession.setSessionActivity(buildContentIntent())
    } catch (e: Exception) {
      Log.e(TAG, "Error updating MediaSession metadata", e)
    }
  }

  private fun updateMediaSessionPlaybackState() {
    try {
      val duration = sanitizedDurationMs()
      var actions =
        PlaybackStateCompat.ACTION_PLAY_PAUSE or
          PlaybackStateCompat.ACTION_STOP
      actions = actions or if (paused) PlaybackStateCompat.ACTION_PLAY else PlaybackStateCompat.ACTION_PAUSE
      if (duration > 0L) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
      if (PlaybackSession.hasPrevious()) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
      if (PlaybackSession.hasNext()) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT

      val state =
        when (PlaybackSession.state.value.phase) {
          PlaybackPhase.LOADING, PlaybackPhase.INITIALIZING -> PlaybackStateCompat.STATE_BUFFERING
          PlaybackPhase.UNINITIALIZED -> PlaybackStateCompat.STATE_NONE
          PlaybackPhase.IDLE, PlaybackPhase.STOPPING -> PlaybackStateCompat.STATE_STOPPED
          PlaybackPhase.ERROR -> PlaybackStateCompat.STATE_ERROR
          PlaybackPhase.READY, PlaybackPhase.BACKGROUND ->
            if (paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
        }
      if (
        state == PlaybackStateCompat.STATE_STOPPED ||
        state == PlaybackStateCompat.STATE_NONE ||
        state == PlaybackStateCompat.STATE_ERROR
      ) {
        actions = 0L
      }
      val stateSpeed = if (state == PlaybackStateCompat.STATE_PLAYING) playbackSpeed else 0f

      mediaSession.setPlaybackState(
        PlaybackStateCompat
          .Builder()
          .setActions(actions)
          .setActiveQueueItemId(activeQueueItemId)
          .setState(state, sanitizedPositionMs(), stateSpeed, SystemClock.elapsedRealtime())
          .build(),
      )
    } catch (e: Exception) {
      Log.e(TAG, "Error updating MediaSession playback state", e)
    }
  }

  private fun updateNotification() {
    if (!notificationsEnabled()) {
      stopForegroundNotification()
      stopSelf()
      return
    }
    if (!foregroundReady) return

    try {
      val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.notify(NOTIFICATION_ID, buildNotification())
      lastNotificationUpdateTime = SystemClock.elapsedRealtime()
      lastPublishedPositionSeconds = currentPositionSeconds
    } catch (e: Exception) {
      Log.e(TAG, "Error updating notification", e)
    }
  }

  private fun sanitizedDurationMs(): Long {
    val seconds = mediaDurationSeconds.takeIf { it.isFinite() && it > 0.0 } ?: return 0L
    return (seconds * 1000.0).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
  }

  private fun sanitizedPositionMs(): Long {
    val seconds = currentPositionSeconds.takeIf { it.isFinite() && it > 0.0 } ?: return 0L
    return (seconds * 1000.0)
      .coerceAtMost(Long.MAX_VALUE.toDouble())
      .toLong()
      .coerceAtMost(sanitizedDurationMs().takeIf { it > 0L } ?: Long.MAX_VALUE)
  }

  // ==================== Notification Builders ====================

  private fun buildNotification(): Notification =
    if (useProgressNotification()) buildModernNotification() else buildLegacyNotification()

  private fun buildContentIntent(): PendingIntent =
    PendingIntent.getActivity(
      this,
      0,
      Intent(this, PlayerActivity::class.java).apply {
        action = ACTION_OPEN_PLAYER
        mediaUri?.let { putExtra("uri", it) }
        putExtra("title", mediaTitle)
        putExtra("media_identifier", mediaIdentifier)
        putExtra(
          "position",
          (currentPositionSeconds
            .takeIf { it.isFinite() && it > 0.0 }
            ?.toLong()
            ?: 0L)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt(),
        )
        putExtra("launch_source", "notification")
        putExtra("internal_launch", true)
        putExtra("is_audio", notificationIsAudio)
        putExtra("media_library_audio", notificationIsAudio)
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      },
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  private fun buildTransportIntent(
    action: String,
    requestCode: Int,
  ): PendingIntent =
    PendingIntent.getService(
      this,
      requestCode,
      Intent(this, MediaPlaybackService::class.java).apply {
        this.action = action
      },
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  private fun prevAction() =
    NotificationCompat.Action(
      Icons.Platform.Previous,
      "Previous",
      buildTransportIntent(ACTION_NOTIFICATION_PREVIOUS, 1001),
    )

  private fun playPauseAction() =
    NotificationCompat.Action(
      if (paused) Icons.Platform.Play else Icons.Platform.Pause,
      if (paused) "Play" else "Pause",
      buildTransportIntent(ACTION_NOTIFICATION_PLAY_PAUSE, 1002),
    )

  private fun nextAction() =
    NotificationCompat.Action(
      Icons.Platform.Next,
      "Next",
      buildTransportIntent(ACTION_NOTIFICATION_NEXT, 1003),
    )

  private fun stopAction() =
    NotificationCompat.Action(
      android.R.drawable.ic_menu_close_clear_cancel,
      "Stop",
      buildTransportIntent(ACTION_NOTIFICATION_STOP, 1004),
    )

  private fun chapterContentText(): String {
    val chapterName =
      if (currentChapterIndex >= 0) {
        chapters.getOrNull(currentChapterIndex)?.title?.takeIf { it.isNotBlank() }
      } else {
        null
      }
    return chapterName ?: mediaArtist.ifBlank { getString(R.string.notification_playing) }
  }

  private fun chapterLabel(): String {
    val chapterNumber = currentChapterIndex.takeIf { it in chapters.indices }?.plus(1)
    return if (chapterNumber != null) {
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
      var r = 0L
      var g = 0L
      var b = 0L
      var count = 0
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
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Android 16+ (API 36): Progress-centric notification with chapter segment indicators.
   * The MediaSession remains active so Bluetooth, lock-screen, Auto, and Wear controls keep
   * working even when the user chooses this alternative notification presentation.
   */
  private fun buildModernNotification(): Notification {
    val (maximum, position) = notificationProgress()
    val style = NotificationCompat.ProgressStyle().setStyledByProgress(false)

    if (maximum <= 0) {
      style.addProgressSegment(
        NotificationCompat.ProgressStyle
          .Segment(100)
          .setColor(accentColor),
      )
      style.setProgressIndeterminate(true)
    } else {
      val chapterBoundaries =
        buildList {
          add(0)
          if (maximum > 1) {
            chapters.forEach { chapter ->
              chapter.time
                .takeIf { it.isFinite() && it > 0f }
                ?.let { time -> add(time.toInt().coerceIn(1, maximum - 1)) }
            }
          }
          add(maximum)
        }.distinct().sorted()

      chapterBoundaries.zipWithNext().forEach { (start, end) ->
        val color =
          when {
            end <= position -> accentColorDone
            start <= position -> accentColor
            else -> accentColorDim
          }
        style.addProgressSegment(
          NotificationCompat.ProgressStyle.Segment(end - start).setColor(color),
        )
      }
      style.setProgress(position)
    }

    val builder =
      NotificationCompat
        .Builder(this, NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(mediaTitle.ifBlank { getString(R.string.player_unknown_video) })
        .setContentText(chapterContentText())
        .setSubText(chapterLabel())
        .setLargeIcon(thumbnail)
        .setContentIntent(buildContentIntent())
        .setDeleteIntent(buildTransportIntent(ACTION_NOTIFICATION_STOP, 1005))
        .setOngoing(!paused)
        .setRequestPromotedOngoing(true)
        .setAutoCancel(false)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        .setColor(DEFAULT_ACCENT_COLOR)
        .setColorized(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOnlyAlertOnce(true)
        .addAction(prevAction())
        .addAction(playPauseAction())
        .addAction(nextAction())
        .addAction(stopAction())

    // Set ProgressStyle — this sets the visual style to segmented progress
    if (!paused && maximum > 0) {
      val remainingMs = (sanitizedDurationMs() - sanitizedPositionMs()).coerceAtLeast(0L)
      val adjustedRemainingMs = (remainingMs / playbackSpeed.coerceAtLeast(0.01f)).toLong()
      builder.setWhen(System.currentTimeMillis() + adjustedRemainingMs)
      builder.setShowWhen(true)
    } else {
      builder.setShowWhen(false)
    }
    builder.setStyle(style)
    builder.setShortCriticalText(formatSeconds(currentPositionSeconds))

    return builder.build()
  }

  /**
   * Pre-Android 16: Classic MediaStyle notification with linear progress bar and chapter text.
   * Uses the system notification surface instead of forcing a colorized card tint.
   */
  private fun buildLegacyNotification(): Notification {
    val (maximum, position) = notificationProgress()

    return NotificationCompat
      .Builder(this, NOTIFICATION_CHANNEL_ID)
      .setContentTitle(mediaTitle.ifBlank { getString(R.string.player_unknown_video) })
      .setContentText(chapterContentText())
      .setSubText(playbackTimeText())
      .setSmallIcon(R.drawable.ic_launcher_monochrome)
      .setLargeIcon(thumbnail)
      .setContentIntent(buildContentIntent())
      .setDeleteIntent(buildTransportIntent(ACTION_NOTIFICATION_STOP, 1005))
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOnlyAlertOnce(true)
      .setOngoing(!paused)
      .setAutoCancel(false)
      .setSilent(true)
      .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
      .setColor(DEFAULT_ACCENT_COLOR)
      .setColorized(false)
      .addAction(prevAction())
      .addAction(playPauseAction())
      .addAction(nextAction())
      .addAction(stopAction())
      .setStyle(
        androidx.media.app.NotificationCompat
          .MediaStyle()
          .setMediaSession(mediaSession.sessionToken)
          .setShowActionsInCompactView(0, 1, 2),
      ).setProgress(maximum, position, maximum <= 0)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  /** Notification progress uses whole seconds to stay within the platform's Int-sized API. */
  private fun notificationProgress(): Pair<Int, Int> {
    val duration = mediaDurationSeconds.takeIf { it.isFinite() && it > 0.0 } ?: return 0 to 0
    val maximum = ceil(duration).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt().coerceAtLeast(1)
    val position =
      currentPositionSeconds
        .takeIf { it.isFinite() && it > 0.0 }
        ?.toLong()
        ?.coerceIn(0L, maximum.toLong())
        ?.toInt()
        ?: 0
    return maximum to position
  }

  private fun stopForegroundNotification() {
    foregroundReady = false
    if (::mediaSession.isInitialized) mediaSession.isActive = false
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
    val t = seconds.takeIf { it.isFinite() && it > 0.0 }?.toLong() ?: 0L
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) {
      String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
      String.format(Locale.US, "%02d:%02d", m, s)
    }
  }

  // ==================== MPV Event Observers ====================

  override fun eventProperty(property: String) {}

  override fun eventProperty(
    property: String,
    value: Long,
  ) {
    if (property == "chapter") {
      serviceScope.launch {
        currentChapterIndex = value.takeIf { it in 0L..chapters.lastIndex.toLong() }?.toInt() ?: -1
        updateNotification()
      }
    }
  }

  override fun eventProperty(
    property: String,
    value: Boolean,
  ) {
    when (property) {
      "pause" -> {
        serviceScope.launch {
          paused = value
          updateMediaSessionPlaybackState()
          updateNotification()
          schedulePlaybackStateSave(force = true)
        }
      }
      "eof-reached" -> {
        if (value) serviceScope.launch { handleDetachedEndOfFile() }
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
            if (mediaTitle == value) return@launch
            mediaTitle = value
            updateMediaSessionMetadata()
            updateNotification()
          }
        }
      }
      "metadata/artist" -> {
        serviceScope.launch {
          if (mediaArtist == value) return@launch
          mediaArtist = value
          updateMediaSessionMetadata()
          updateNotification()
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
        currentPositionSeconds = value.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val currentTime = SystemClock.elapsedRealtime()
        val updateInterval =
          if (useProgressNotification()) {
            PROGRESS_NOTIFICATION_UPDATE_INTERVAL_MS
          } else {
            MEDIA_NOTIFICATION_UPDATE_INTERVAL_MS
          }
        val elapsedSinceUpdate = (currentTime - lastNotificationUpdateTime).coerceAtLeast(0L)
        val expectedAdvance = elapsedSinceUpdate / 1000.0 * playbackSpeed.coerceAtLeast(0f)
        val positionJump = abs(currentPositionSeconds - lastPublishedPositionSeconds) > expectedAdvance + 3.0
        if (elapsedSinceUpdate >= updateInterval || positionJump) {
          lastNotificationUpdateTime = currentTime
          lastPublishedPositionSeconds = currentPositionSeconds
          serviceScope.launch {
            schedulePlaybackStateSave()
            updateMediaSessionPlaybackState()
            updateNotification()
          }
        }
      }
      "duration" -> {
        serviceScope.launch {
          val resolvedDuration = value.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
          if (mediaDurationSeconds == resolvedDuration) return@launch
          mediaDurationSeconds = resolvedDuration
          updateMediaSessionMetadata()
          updateMediaSessionPlaybackState()
          updateNotification()
        }
      }
      "speed" -> {
        serviceScope.launch {
          val resolvedSpeed = value.toFloat().takeIf { it.isFinite() && it > 0f } ?: 1.0f
          if (playbackSpeed == resolvedSpeed) return@launch
          playbackSpeed = resolvedSpeed
          updateMediaSessionPlaybackState()
          updateNotification()
        }
      }
    }
  }

  override fun eventProperty(
    property: String,
    value: MPVNode,
  ) {}

  override fun event(
    eventId: Int,
    data: MPVNode,
  ) {
    if (eventId == MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN) {
      Log.d(TAG, "MPV shutdown event received, stopping service")
      savePlaybackStateNow()
      stopSelf()
      return
    }

    if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
      // The current file has finished (or been quit). Release the static
      // thumbnail Bitmap reference now so it does not linger in the
      // companion object for the entire process lifetime — which can be
      // long if the service is killed by the system without onDestroy
      // being called. The next file loaded will set a fresh thumbnail
      // via setMediaInfo(). See issue 2.4 in the leak audit.
      // We null the companion field (not the local instance field) so
      // the next setMediaInfo call starts from a clean state.
      if (PlaybackSession.queue.value.currentItem == null) replaceOwnedThumbnail(null)
    }
  }

  private fun schedulePlaybackStateSave(force: Boolean = false) {
    val identifier = mediaIdentifier
    if (identifier.isBlank()) return

    val now = SystemClock.elapsedRealtime()
    if (!force && now - lastPlaybackStateSaveTime < PLAYBACK_STATE_SAVE_INTERVAL_MS) return
    lastPlaybackStateSaveTime = now
    val snapshot = capturePlaybackStateSnapshot(identifier, oldState = null) ?: return

    playbackStateSaveJob?.cancel()
    playbackStateSaveJob =
      serviceScope.launch(Dispatchers.IO) {
        persistPlaybackState(identifier, snapshot)
      }
  }

  private fun savePlaybackStateNow() {
    val identifier = mediaIdentifier
    if (identifier.isBlank()) return
    // Every libmpv read happens here, so the database write can safely outlive the service.
    val snapshot = capturePlaybackStateSnapshot(identifier, oldState = null) ?: return

    playbackStateSaveJob?.cancel()
    persistenceScope.launch {
      runCatching {
        persistPlaybackState(identifier, snapshot)
      }.onFailure { error ->
        Log.e(TAG, "Error force-saving playback state", error)
      }
    }
  }

  private suspend fun persistPlaybackState(
    identifier: String,
    capturedSnapshot: PlaybackStateSnapshot,
  ) {
    if (identifier.isBlank() || capturedSnapshot.mediaIdentifier != identifier) return

    runCatching {
      val oldState = playbackStateRepository.getVideoDataByTitle(identifier)
      val snapshot =
        if (capturedSnapshot.externalSubtitles.isBlank() && !oldState?.externalSubtitles.isNullOrBlank()) {
          capturedSnapshot.copy(externalSubtitles = oldState.externalSubtitles.orEmpty())
        } else {
          capturedSnapshot
        }
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

  /*
   * Capture every native value before the first database suspension. This prevents a delayed save
   * for item A from reading item B's libmpv properties and writing them under A's identifier.
   */
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
        (
          readMpvDouble(
            "sub-delay",
            (oldState?.subDelay ?: 0) / PLAYBACK_STATE_MILLISECONDS_TO_SECONDS.toDouble(),
          ) * PLAYBACK_STATE_MILLISECONDS_TO_SECONDS
        ).toInt(),
      subSpeed = readMpvDouble("sub-speed", oldState?.subSpeed ?: DEFAULT_PLAYBACK_STATE_SUB_SPEED),
      aid = readMpvTrackId("aid", oldState?.aid ?: -1),
      audioDelayMs =
        (
          readMpvDouble(
            "audio-delay",
            (oldState?.audioDelay ?: 0) / PLAYBACK_STATE_MILLISECONDS_TO_SECONDS.toDouble(),
          ) * PLAYBACK_STATE_MILLISECONDS_TO_SECONDS
        ).toInt(),
      externalSubtitles = oldState?.externalSubtitles.orEmpty(),
    )
  }

  private fun readMpvIntSeconds(
    property: String,
    fallback: Int,
  ): Int =
    runCatching {
      PlaybackSession.getPropertyDouble(property)?.toInt()
        ?: PlaybackSession.getPropertyInt(property)
        ?: fallback
    }.getOrDefault(fallback)

  private fun readMpvDouble(
    property: String,
    fallback: Double,
  ): Double =
    runCatching {
      PlaybackSession.getPropertyDouble(property) ?: fallback
    }.getOrDefault(fallback)

  private fun readMpvTrackId(
    property: String,
    fallback: Int,
  ): Int =
    runCatching {
      when (val value = PlaybackSession.getPropertyString(property)) {
        null -> fallback
        "no" -> -1
        else -> value.toIntOrNull() ?: fallback
      }
    }.getOrDefault(fallback)

  /**
   * Snapshots playback and unregisters callbacks while libmpv is still alive. This method is
   * idempotent because Activity teardown can prepare the service before stopService() later
   * delivers Service.onDestroy().
   */
  private fun releaseMpvAccessBeforeShutdown() {
    if (mpvAccessReleased) return
    mpvAccessReleased = true

    runCatching { savePlaybackStateNow() }
      .onFailure { error -> Log.e(TAG, "Error saving playback state before MPV shutdown", error) }
    runCatching { PlaybackSession.removeObserver(this) }
      .onFailure { error -> Log.e(TAG, "Error removing MPV observer", error) }
    runCatching { serviceScope.cancel() }
      .onFailure { error -> Log.e(TAG, "Error canceling playback service work", error) }
    if (::mediaSession.isInitialized) {
      runCatching {
        mediaSession.setCallback(null)
        mediaSession.isActive = false
      }.onFailure { error ->
        Log.e(TAG, "Error disabling MediaSession callbacks", error)
      }
    }
  }

  override fun onDestroy() {
    try {
      Log.d(TAG, "Service destroyed")

      releaseMpvAccessBeforeShutdown()
      foregroundReady = false
      abandonAudioOwnership()
      if (noisyReceiverRegistered) {
        runCatching { unregisterReceiver(noisyReceiver) }
        noisyReceiverRegistered = false
      }
      isServiceRunning = false

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

      thumbnail?.let {
        if (!it.isRecycled) it.recycle()
      }
      thumbnail = null
      lastPaletteThumbnail?.let {
        if (!it.isRecycled) it.recycle()
      }
      lastPaletteThumbnail = null

      Log.d(TAG, "Service cleanup completed")
    } catch (e: Exception) {
      Log.e(TAG, "Error in onDestroy", e)
    } finally {
      isServiceRunning = false
      if (activeInstance === this) activeInstance = null
      stopDetachedPlaybackIfNeeded()
      handingBackToActivity = false
      super.onDestroy()
    }
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    Log.d(TAG, "Task removed - keeping foreground background playback active")
    schedulePlaybackStateSave(force = true)
    super.onTaskRemoved(rootIntent)
  }
}
