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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.data.lyrics.LyricsTranslationService
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.domain.thumbnail.getArtwork
import app.gyrolet.mpvrx.domain.torrent.TorrentStreamingEngine
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.MpvConfigControlledFeatures
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.repository.lyrics.LyricsRepository
import app.gyrolet.mpvrx.ui.components.AppToast
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.floating.FloatingLyricsController
import app.gyrolet.mpvrx.utils.logging.Log
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Background playback service for mpv with MediaSession integration, modelled on mpv-android's
 * BackgroundPlaybackService: a mediaPlayback foreground service whose notification is a plain
 * MediaStyle card tied to an active, fully described MediaSession. Android shows exactly that kind
 * of notification without the POST_NOTIFICATIONS permission.
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

    // A fresh channel: the old one may have been switched off by the user or an OEM shade while
    // older notification styles were tested, and an app cannot switch a channel back on.
    private const val NOTIFICATION_CHANNEL_ID = "mpvrx_media_playback"
    private const val LEGACY_NOTIFICATION_CHANNEL_ID = "mpvrx_playback_channel"
    private const val EXTRA_TRACKED_START = "app.gyrolet.mpvrx.extra.TRACKED_FOREGROUND_START"
    private const val PLAYBACK_STATE_SAVE_INTERVAL_MS = 5000L
    private const val MEDIA_NOTIFICATION_UPDATE_INTERVAL_MS = 1000L
    // Scrubbing jumps the position on every step. Jumps republish at most this often, the last one late.
    private const val POSITION_JUMP_PUBLISH_INTERVAL_MS = 500L
    private const val MEDIA_SESSION_DRIFT_TOLERANCE_SECONDS = 2.0
    private const val MAX_MEDIA_SESSION_QUEUE_ITEMS = 200
    private const val VIDEO_CONTENT_INTENT_REQUEST_CODE = 1100
    private const val AUDIO_CONTENT_INTENT_REQUEST_CODE = 1101
    const val ACTION_OPEN_PLAYER = "app.gyrolet.mpvrx.action.OPEN_PLAYER_FROM_NOTIFICATION"
    const val ACTION_NOTIFICATION_PREVIOUS = "app.gyrolet.mpvrx.action.NOTIFICATION_PREVIOUS"
    const val ACTION_NOTIFICATION_PLAY_PAUSE = "app.gyrolet.mpvrx.action.NOTIFICATION_PLAY_PAUSE"
    const val ACTION_NOTIFICATION_NEXT = "app.gyrolet.mpvrx.action.NOTIFICATION_NEXT"
    const val ACTION_NOTIFICATION_SHUFFLE = "app.gyrolet.mpvrx.action.NOTIFICATION_SHUFFLE"
    const val ACTION_NOTIFICATION_FAVORITE = "app.gyrolet.mpvrx.action.NOTIFICATION_FAVORITE"
    const val ACTION_NOTIFICATION_MEDIA_FAVORITE = "app.gyrolet.mpvrx.action.NOTIFICATION_MEDIA_FAVORITE"
    const val ACTION_NOTIFICATION_CLOSE = "app.gyrolet.mpvrx.action.NOTIFICATION_CLOSE"
    const val ACTION_NOTIFICATION_STOP = "app.gyrolet.mpvrx.action.NOTIFICATION_STOP"
    const val EXTRA_EXTERNAL_DISPLAY_ACTIVE = "external_display_active"

    @Volatile
    internal var thumbnail: Bitmap? = null

    @Volatile
    private var activeInstance: MediaPlaybackService? = null

    // Main thread only. Android kills the app when a service started with startForegroundService()
    // is stopped before it called startForeground(), so a stop that races a pending start waits
    // for that start's onStartCommand, which always posts the notification first.
    private var trackedStartsPending = 0
    private var stopAfterTrackedStarts = false

    /** Starts the service in the foreground; the caller must still be allowed to start one. */
    internal fun requestForegroundStart(
      context: Context,
      intent: Intent,
    ) {
      ContextCompat.startForegroundService(context, Intent(intent).putExtra(EXTRA_TRACKED_START, true))
      trackedStartsPending++
      stopAfterTrackedStarts = false
    }

    /** Removes the notification now and stops the service once no foreground start is pending. */
    internal fun requestStop(context: Context) {
      activeInstance?.stopForegroundNotification("the player ended playback")
      if (trackedStartsPending > 0) {
        stopAfterTrackedStarts = true
      } else {
        runCatching { context.stopService(Intent(context, MediaPlaybackService::class.java)) }
          .onFailure { error -> Log.e(TAG, "Could not stop the playback service", error) }
      }
    }

    /** A foreground start is on its way and will post the notification (no stop waits behind it). */
    internal fun isForegroundStartPending(): Boolean = trackedStartsPending > 0 && !stopAfterTrackedStarts

    private val _foregroundActive = MutableStateFlow(false)
    internal val foregroundActive = _foregroundActive.asStateFlow()

    private fun publishForegroundActive() {
      _foregroundActive.value = isForegroundActive()
    }

    fun isForegroundActive(): Boolean =
      activeInstance?.let { service ->
        service.foregroundReady && !activityForeground && !service.handingBackToActivity
      } == true

    /** True once the service owns the foreground notification, independent of Activity focus. */
    internal fun isNotificationOwnerReady(): Boolean = activeInstance?.foregroundReady == true

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
          service.updatePlaybackWakeLock()
        }
        publishForegroundActive()
      }

    internal fun takeAudioOwnershipForDetachedPlayback(): Boolean = activeInstance?.takeAudioOwnership() == true

    /** A play action by the user: takes focus back even from an app that only borrowed it. */
    internal fun reclaimAudioFocusForPlay(): Boolean = activeInstance?.takeAudioOwnership(reclaim = true) == true

    // In-app full player transport. It shares the detached owner's queue handling, notification
    // updates and forced state saves, exactly like the notification and media-session controls.
    internal fun skipToNextFromApp(): Boolean = activeInstance?.playNextFromSession() == true

    internal fun skipToPreviousFromApp() {
      activeInstance?.playPreviousFromSession()
    }

    internal fun playQueueItemFromApp(index: Int): Boolean = activeInstance?.playQueueItemFromSession(index) == true

    internal fun setExternalDisplayActive(active: Boolean) {
      activeInstance?.updateExternalDisplayWakeLock(active)
    }

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
        service.updatePlaybackWakeLock()
      }
    }

    /**
     * Only the window between a handoff request and the Activity reaching the foreground. Once the
     * player is on screen it owns audio focus again, and a focus loss there (another app playing)
     * must pause it instead of being mistaken for the service's handoff.
     */
    internal fun isActivityHandoffInProgress(): Boolean =
      !activityForeground && activeInstance?.handingBackToActivity == true

    /** Releases every service-owned MPV access before an Activity destroys the global core. */
    internal fun prepareForMpvShutdown() {
      activeInstance?.let { service ->
        runCatching { service.releaseMpvAccessBeforeShutdown() }
          .onFailure { error -> Log.e(TAG, "Error preparing service for MPV shutdown", error) }
      }
    }

    internal fun stopForTerminalDismissal() {
      val service = activeInstance
      if (service != null && !service.mpvAccessReleased) {
        // The dismissed picture-in-picture player is already closing itself.
        service.stopPlaybackAndService(force = true, closeVisiblePlayer = false)
      } else {
        PlaybackSession.stop(clearQueue = true)
      }
    }

    private fun createNotificationChannel(context: Context) {
      val manager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      runCatching { manager.deleteNotificationChannel(LEGACY_NOTIFICATION_CHANNEL_ID) }
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
          setSound(null, null)
        }
      manager.createNotificationChannel(channel)
    }
  }

  private val binder = MediaPlaybackBinder()
  private lateinit var mediaSession: MediaSessionCompat
  private val playerPreferences: PlayerPreferences by inject()
  private val audioPreferences: AudioPreferences by inject()
  private val browserPreferences: BrowserPreferences by inject()
  private val thumbnailRepository: ThumbnailRepository by inject()
  private val gesturePreferences: GesturePreferences by inject()
  private val playlistRepository: PlaylistRepository by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()
  private val torrentStreamingEngine: TorrentStreamingEngine by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val lyricsRepository: LyricsRepository by inject()
  private val lyricsTranslationService: LyricsTranslationService by inject()
  private var floatingLyrics: FloatingLyricsController? = null

  // Audio decision for loads this service drives on its own (detached playback), using the
  // same TrackSelector rules as the Activity path.
  private val detachedTrackSelector: TrackSelector by lazy {
    TrackSelector(audioPreferences, subtitlesPreferences)
  }

  @Volatile
  private var detachedRestoreGeneration: Long = 0L
  private var detachedRestoreJob: Job? = null

  private var mediaIdentifier = ""
  private var mediaTitle = ""
  private var mediaArtist = ""
  private var mediaAlbum = ""
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

  // What the MediaSession last told the system, which extrapolates position from it.
  @Volatile
  private var sessionPositionSeconds = 0.0
  @Volatile
  private var sessionPositionUpdatedAt = 0L
  @Volatile
  private var sessionPositionSpeed = 0f

  // Transport actions never change; creating a PendingIntent is a binder call per notification.
  private val transportIntents = java.util.concurrent.ConcurrentHashMap<String, PendingIntent>()

  // Timeline and chapter state published through the MediaSession and the media notification.
  private var chapters: List<ChapterNode> = emptyList()
  private var currentChapterIndex: Int = -1
  @Volatile
  private var currentPositionSeconds: Double = 0.0
  private var mediaDurationSeconds: Double = 0.0
  private var lastThumbnailSource: WeakReference<Bitmap>? = null
  // Replaced when a released instance is re-armed for new playback (rearmAfterMpvRelease).
  private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var playbackStateSaveJob: Job? = null
  // Only touched from the libmpv event thread.
  private var positionJumpPublishJob: Job? = null
  private var favoriteStateJob: Job? = null
  private var favoriteActionJob: Job? = null
  private val mediaFavoriteActionMutex = Mutex()
  @Volatile private var mpvAccessReleased = false
  // Detached during an mpv shutdown; kept to attach again when the instance is re-armed.
  private var sessionCallback: MediaSessionCompat.Callback? = null
  @Volatile private var isCurrentFavorite = false
  private var usesAudioBackgroundPlayback = false
    set(value) {
      if (field != value) {
        field = value
        floatingLyrics?.onPlaybackKindChanged()
      }
    }
  private var externalDisplayWakeLock: PowerManager.WakeLock? = null
  private var playbackWakeLock: PowerManager.WakeLock? = null
  private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

  // Mutated from the framework's audio-focus callback thread as well as serviceScope and the
  // Activity's main thread; stale reads here strand focus and permanently duck other apps.
  @Volatile private var audioFocusRequest: AudioFocusRequest? = null

  @Volatile private var ownsAudioFocus = false

  @Volatile private var hasAudioFocus = false

  @Volatile
  private var handingBackToActivity = false
    set(value) {
      field = value
      publishForegroundActive()
    }

  @Volatile private var resumeAfterFocusGain = false

  @Volatile private var volumeBeforeDuck: Double? = null
  private var noisyReceiverRegistered = false
  private var playbackObserversStarted = false
  private var handledEofGeneration: Long? = null
  @Volatile
  private var foregroundReady = false
    set(value) {
      field = value
      updatePlaybackWakeLock()
      publishForegroundActive()
    }
  private val audioFocusChangeListener =
    AudioManager.OnAudioFocusChangeListener { change ->
      if (mpvAccessReleased || handingBackToActivity || audioPreferences.ignoreAudioFocus.get()) {
        return@OnAudioFocusChangeListener
      }
      when (change) {
        AudioManager.AUDIOFOCUS_LOSS -> {
          resumeAfterFocusGain = false
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
        // Headphone disconnection still pauses detached playback when focus handling is disabled.
        if (canHandleDetachedTransport() &&
          intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY
        ) {
          PlaybackSession.setPropertyBoolean("pause", true)
        }
      }
    }

  inner class MediaPlaybackBinder : Binder() {
    fun getService() = this@MediaPlaybackService
  }

  fun isForegroundReady(): Boolean = foregroundReady

  // The bound full player, told when the notification is up and when its Close action ends playback.
  private var playerActivity: WeakReference<PlayerActivity>? = null

  internal fun attachPlayerActivity(activity: PlayerActivity) {
    playerActivity = WeakReference(activity)
    if (foregroundReady) activity.onPlaybackServiceForegroundReady()
  }

  internal fun detachPlayerActivity(activity: PlayerActivity) {
    if (playerActivity?.get() === activity) playerActivity = null
  }

  private fun updateExternalDisplayWakeLock(active: Boolean) {
    if (active) {
      if (externalDisplayWakeLock?.isHeld != true) {
        externalDisplayWakeLock =
          (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:external-display")
            .apply { acquire() }
      }
    } else {
      externalDisplayWakeLock?.let { if (it.isHeld) it.release() }
      externalDisplayWakeLock = null
    }
  }

  /** The foreground notification alone does not keep the CPU running with the screen off. */
  @Synchronized
  private fun updatePlaybackWakeLock() {
    val state = PlaybackSession.state.value
    val shouldHold = foregroundReady && !mpvAccessReleased && !activityForeground &&
      !handingBackToActivity && !state.paused && state.currentItem != null &&
      state.phase in setOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
    runCatching {
      if (shouldHold) {
        val wakeLock = playbackWakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
          .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:background-playback")
          .apply { setReferenceCounted(false) }
          .also { playbackWakeLock = it }
        // A movie can outlive any fixed timeout; pause/stop/handoff/shutdown release this lock.
        if (!wakeLock.isHeld) wakeLock.acquire()
      } else {
        playbackWakeLock?.let { if (it.isHeld) it.release() }
      }
    }.onFailure { error -> Log.e(TAG, "Could not update background playback wake lock", error) }
  }

  override fun onCreate() {
    super.onCreate()
    Log.d(TAG, "Service created")

    activeInstance = this
    mpvAccessReleased = false
    handingBackToActivity = false

    // The channel must exist before startForeground(). Like mpv-android, nothing is posted here:
    // onStartCommand fills the session first, so the very first card is already a complete media
    // notification. A bind alone (the Activity attaching) never posts anything.
    createNotificationChannel(this)
    setupMediaSession()
  }

  private fun startPlaybackObservers() {
    if (playbackObserversStarted) return
    playbackObserversStarted = true
    serviceScope.launch {
      audioPreferences.floatingLyricsEnabled.changes().distinctUntilChanged().collect { wanted ->
        if (!wanted || app.gyrolet.mpvrx.utils.device.DeviceFormFactor.isTelevision(this@MediaPlaybackService)) {
          stopFloatingLyrics()
          return@collect
        }
        if (floatingLyrics != null) return@collect
        val controller = FloatingLyricsController(
          context = this@MediaPlaybackService,
          preferences = audioPreferences,
          playerPreferences = playerPreferences,
          lyricsRepository = lyricsRepository,
          translations = lyricsTranslationService,
          thumbnails = thumbnailRepository,
          scope = serviceScope,
          isAudio = { resolveNotificationIsAudio(it, usesAudioBackgroundPlayback) },
          openPlayer = {
            if (!mpvAccessReleased) {
              try {
                buildContentIntent().send()
              } catch (error: PendingIntent.CanceledException) {
                Log.e(TAG, "Could not open the player from floating lyrics", error)
                AppToast.makeText(this@MediaPlaybackService, R.string.floating_lyrics_unavailable, AppToast.LENGTH_LONG).show()
              }
            }
          },
          togglePlay = {
            if (!mpvAccessReleased && ::mediaSession.isInitialized) {
              if (PlaybackSession.state.value.paused) mediaSession.controller.transportControls.play()
              else mediaSession.controller.transportControls.pause()
            }
          },
          previous = {
            if (!mpvAccessReleased && ::mediaSession.isInitialized) mediaSession.controller.transportControls.skipToPrevious()
          },
          next = {
            if (!mpvAccessReleased && ::mediaSession.isInitialized) mediaSession.controller.transportControls.skipToNext()
          },
          seek = {
            if (!mpvAccessReleased && ::mediaSession.isInitialized) mediaSession.controller.transportControls.seekTo(it)
          },
        )
        floatingLyrics = controller
        controller.start()
      }
    }

    ContextCompat.registerReceiver(
      this,
      noisyReceiver,
      IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    noisyReceiverRegistered = true

    serviceScope.launch {
      // Includes pause, EOF, error, stop and native shutdown; no periodic wake-up timer.
      PlaybackSession.state.collect { updatePlaybackWakeLock() }
    }

    serviceScope.launch {
      // The media card mirrors the session: a file that finishes loading, buffers, or restores a
      // paused position does not always emit a separate pause event. A stopped or failed detached
      // session takes the notification down instead of leaving a dead card behind.
      PlaybackSession.state
        .map { Triple(it.generation, it.phase, it.paused to it.bufferingReason) }
        .distinctUntilChanged()
        .collect { (_, phase, _) ->
          if (!foregroundReady) return@collect
          val state = PlaybackSession.state.value
          val ended =
            phase == PlaybackPhase.STOPPING || phase == PlaybackPhase.UNINITIALIZED ||
              (phase == PlaybackPhase.IDLE && state.currentItem == null) ||
              (phase == PlaybackPhase.ERROR && canHandleDetachedTransport())
          if (ended) {
            stopForegroundNotification("playback ended (phase=$phase, item=${state.currentItem != null})")
            stopSelf()
            return@collect
          }
          paused = state.paused
          updateMediaSessionPlaybackState()
          updateNotification()
        }
    }

    serviceScope.launch {
      audioPreferences.ignoreAudioFocus.changes().distinctUntilChanged().collect { ignore ->
        if (mpvAccessReleased) return@collect
        if (ignore) {
          abandonAudioOwnership()
        } else if (canHandleDetachedTransport() &&
          PlaybackSession.getPropertyBoolean("pause") == false && !takeAudioOwnership()
        ) {
          PlaybackSession.setPropertyBoolean("pause", true)
        }
      }
    }

    serviceScope.launch {
      // Audio always keeps playing in the background. Turning the video switch off ends a video
      // that is already playing there.
      playerPreferences.videoBackgroundPlayback.changes().distinctUntilChanged().drop(1).collect { enabled ->
        val item = PlaybackSession.state.value.currentItem
        val isAudio =
          item != null && (item.audiobook != null || resolveNotificationIsAudio(item, usesAudioBackgroundPlayback))
        // Only a video that already plays detached; a player on screen or a projected video keeps it.
        if (!enabled && !isAudio && canHandleDetachedTransport() && externalDisplayWakeLock?.isHeld != true) {
          Log.d(TAG, "Video background playback disabled; stopping service")
          stopDetachedPlaybackIfNeeded()
          stopForegroundNotification("video background playback was switched off")
          stopSelf()
        }
      }
    }

    serviceScope.launch {
      PlaybackSession.queue.collect(::syncQueueState)
    }

    serviceScope.launch {
      // Detached loads (queue playback with no foreground Activity) have no Activity
      // restore path: without this collector nothing would re-add the file's external
      // audio, restore its saved manual selection, or correct mpv's automatic pick.
      // One restore runs per load generation, and only while this service — not an
      // Activity — owns playback, so the two paths never fight over the same load.
      PlaybackSession.state.collect { session ->
        if (session.phase != PlaybackPhase.READY && session.phase != PlaybackPhase.BACKGROUND) {
          return@collect
        }
        val generation = session.generation
        if (generation <= 0L || generation == detachedRestoreGeneration) return@collect
        if (!canHandleDetachedTransport()) return@collect
        val item = session.currentItem ?: return@collect
        detachedRestoreGeneration = generation
        detachedRestoreJob?.cancel()
        detachedRestoreJob =
          serviceScope.launch(Dispatchers.IO) {
            restoreDetachedAudioSelection(item.stableId, generation)
          }
      }
    }

    serviceScope.launch {
      thumbnailRepository.cacheRevision.drop(1).collect {
        replaceOwnedThumbnail(null)
        updateMediaSessionMetadata()
        updateNotification()
        MediaPlayerWidget.clearArtworkCache()
        MediaPlayerWidget.requestUpdate(this@MediaPlaybackService)
        PlaybackSession.queue.value.currentItem?.let(::loadSessionArtwork)
      }
    }

    // Only add MPV observer if MPV is initialized
    try {
      PlaybackSession.addObserver(this)
      PlaybackSession.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
      PlaybackSession.observeProperty("media-title", MPVLib.MpvFormat.MPV_FORMAT_STRING)
      PlaybackSession.observeProperty("metadata/by-key/artist", MPVLib.MpvFormat.MPV_FORMAT_STRING)
      PlaybackSession.observeProperty("metadata/by-key/album", MPVLib.MpvFormat.MPV_FORMAT_STRING)
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

  override fun onBind(intent: Intent): IBinder? {
    setupMediaSession()
    return if (intent.action == MediaBrowserServiceCompat.SERVICE_INTERFACE) super.onBind(intent) else binder
  }

  @SuppressLint("ForegroundServiceType")
  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    Log.d(TAG, "Service starting with startId: $startId action=${intent?.action}")
    if (intent?.getBooleanExtra(EXTRA_TRACKED_START, false) == true && trackedStartsPending > 0) {
      trackedStartsPending--
    }
    val stopRequested = stopAfterTrackedStarts && trackedStartsPending == 0
    if (stopRequested) stopAfterTrackedStarts = false

    setupMediaSession()
    val session = PlaybackSession.state.value
    val sessionHasMedia =
      PlaybackSession.isInitialized && session.currentItem != null &&
        session.phase in setOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
    // The closing player stops the session in the same turn as it releases this instance, so media
    // found here is a new player's, not the released one's.
    if (mpvAccessReleased && !stopRequested && sessionHasMedia) rearmAfterMpvRelease()
    val hasPlayback = !stopRequested && !mpvAccessReleased && sessionHasMedia

    if (hasPlayback) {
      // Observers apply the current queue item to the session synchronously (Main.immediate), so
      // title, artist, album, duration and artwork are in place before anything is posted.
      startPlaybackObservers()
      intent?.let(::applyStartExtras)
      refreshPlaybackSnapshot()

      // Only take audio ownership for truly detached playback. A player on screen owns focus, and
      // requesting it from here during a handoff would make that player see a focus loss.
      val detached =
        !session.paused && !session.surfaceAttached && !activityForeground && !handingBackToActivity
      if (detached && !takeAudioOwnership()) {
        paused = true
        PlaybackSession.setPropertyBoolean("pause", true)
      }
    }

    // Every start reaches startForeground(), even one that ends at once: Android kills an app whose
    // startForegroundService() is never answered. As in mpv-android, the session is active and
    // fully described before the MediaStyle notification that points to it is posted, which is
    // what lets Android show it without the notification permission.
    publishSession(active = hasPlayback)
    if (!startForegroundNotification()) {
      stopForegroundNotification("startForeground failed")
      stopSelf(startId)
      return START_NOT_STICKY
    }
    if (!hasPlayback) {
      // stopSelf(startId) keeps the service for a start that is already queued behind this one.
      val reason =
        "no playback to show (stopRequested=$stopRequested, released=$mpvAccessReleased, " +
          "initialized=${PlaybackSession.isInitialized}, item=${session.currentItem != null}, phase=${session.phase})"
      Log.d(TAG, "Ending the service: $reason")
      stopForegroundNotification(reason)
      stopSelf(startId)
      return START_NOT_STICKY
    }

    val becameReady = !foregroundReady
    foregroundReady = true
    if (becameReady) {
      Log.d(TAG, "Media notification posted for ${session.currentItem?.stableId}")
      playerActivity?.get()?.onPlaybackServiceForegroundReady()
    }
    intent?.takeIf { it.action != null }?.let(::dispatchStartAction)
    return START_NOT_STICKY
  }

  /** Widget, notification and mini player buttons arrive as start intents with an action. */
  private fun dispatchStartAction(intent: Intent) {
    when (intent.action) {
      ACTION_NOTIFICATION_PREVIOUS -> playPreviousFromSession()
      ACTION_NOTIFICATION_PLAY_PAUSE -> togglePlaybackFromNotification()
      ACTION_NOTIFICATION_NEXT -> playNextFromSession()
      ACTION_NOTIFICATION_SHUFFLE -> {
        if (canHandleTransportAction()) {
          PlaybackSession.setShuffleEnabled(!PlaybackSession.queue.value.shuffleEnabled)
          refreshTransportControls()
        }
      }
      ACTION_NOTIFICATION_FAVORITE -> toggleCurrentItemFavorite()
      ACTION_NOTIFICATION_MEDIA_FAVORITE -> toggleCurrentMediaNotificationFavorite()
      ACTION_NOTIFICATION_CLOSE,
      ACTION_NOTIFICATION_STOP,
      -> stopPlaybackAndService(force = true)
      else -> MediaButtonReceiver.handleIntent(mediaSession, intent)
    }
  }

  /** Details the player sent with a start, used only while they describe the current item. */
  private fun applyStartExtras(intent: Intent) {
    if (intent.hasExtra(EXTRA_EXTERNAL_DISPLAY_ACTIVE)) {
      updateExternalDisplayWakeLock(intent.getBooleanExtra(EXTRA_EXTERNAL_DISPLAY_ACTIVE, false))
    }
    val identifier = intent.getStringExtra("media_identifier")?.takeIf { it.isNotBlank() } ?: return
    val current = PlaybackSession.state.value.currentItem ?: return
    if (identifier != current.stableId) return
    mediaIdentifier = identifier
    intent.getStringExtra("media_uri")?.takeIf { it.isNotBlank() }?.let { mediaUri = it }
    intent.getStringExtra("media_title")?.takeIf { it.isNotBlank() }?.let {
      mediaTitle = FileTypeUtils.stripExtension(it)
    }
    intent.getStringExtra("media_artist")?.takeIf { it.isNotBlank() }?.let { mediaArtist = it }
    if (intent.hasExtra("audio_background_playback")) {
      val isAudio = intent.getBooleanExtra("audio_background_playback", false)
      usesAudioBackgroundPlayback = isAudio
      notificationIsAudio = isAudio
    }
  }

  /** Reads the loaded file's state; main-thread reads use the session's cached properties. */
  private fun refreshPlaybackSnapshot() {
    val state = PlaybackSession.state.value
    paused = state.paused
    if (mediaTitle.isBlank()) {
      mediaTitle = FileTypeUtils.stripExtension(PlaybackSession.getPropertyString("media-title").orEmpty())
    }
    if (mediaArtist.isBlank()) {
      mediaArtist = PlaybackSession.getPropertyString("metadata/by-key/artist").orEmpty()
    }
    if (mediaAlbum.isBlank()) {
      mediaAlbum = PlaybackSession.getPropertyString("metadata/by-key/album").orEmpty()
    }
    if (state.phase == PlaybackPhase.READY || state.phase == PlaybackPhase.BACKGROUND) {
      playbackSpeed =
        PlaybackSession.getPropertyDouble("speed")?.toFloat()?.takeIf { it.isFinite() && it > 0f } ?: playbackSpeed
      PlaybackSession.getPropertyDouble("duration")?.takeIf { it.isFinite() && it > 0.0 }?.let { mediaDurationSeconds = it }
      PlaybackSession.getPropertyDouble("time-pos")?.takeIf { it.isFinite() && it >= 0.0 }?.let { currentPositionSeconds = it }
      currentChapterIndex = PlaybackSession.getPropertyInt("chapter")?.takeIf { it in chapters.indices } ?: currentChapterIndex
    }
  }

  /** Metadata and state first, then activity: the order mpv-android's PlaybackStateCache uses. */
  private fun publishSession(active: Boolean) {
    updateMediaSessionMetadata()
    updateMediaSessionPlaybackState()
    if (::mediaSession.isInitialized) mediaSession.isActive = active
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
      // A late sync from the player (artwork decoding, a fetched stream title) must never label
      // the item that the queue has moved on to.
      val currentId = PlaybackSession.state.value.currentItem?.stableId
      if (resolvedIdentifier != null && currentId != null && resolvedIdentifier != currentId) return@launch
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
    // Notifications are rebuilt from current state on every post, so a recycled bitmap is never
    // parcelled again.
    previous?.takeIf { it !== ownedCopy && !it.isRecycled }?.recycle()
    return previous !== ownedCopy
  }

  fun setPlaylistInfo(isAudio: Boolean) {
    notificationIsAudio = isAudio
    usesAudioBackgroundPlayback = isAudio
  }

  /**
   * Takes audio focus so other apps pause while mpvRx plays. [reclaim] is for a play action by the
   * user: it asks again even while another app has only borrowed focus (a short sound or the
   * assistant), so mpvRx wins. During a phone call the request fails and playback waits.
   */
  fun takeAudioOwnership(reclaim: Boolean = false): Boolean {
    if (audioPreferences.ignoreAudioFocus.get()) {
      abandonAudioOwnership()
      return true
    }
    // During a foreground handoff, returning true means "ownership is intentionally not needed".
    // Do not request focus and bounce it back to PlayerActivity; that focus ping-pong is exactly
    // what can turn a notification tap into an unexpected pause.
    if (activityForeground || handingBackToActivity) return true
    if (ownsAudioFocus && (hasAudioFocus || !reclaim)) {
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
    if (hasAudioFocus) {
      ownsAudioFocus = true
      resumeAfterFocusGain = false
    } else if (ownsAudioFocus) {
      // Still queued behind the borrower (a call); resume when it hands focus back.
      resumeAfterFocusGain = true
    }
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
    if (!canHandleTransportAction()) return false
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
    if (!canHandleTransportAction()) return
    schedulePlaybackStateSave(force = true)
    PlaybackSession.playPrevious()?.let(::applySessionItem) ?: refreshTransportControls()
  }

  private fun playQueueItemFromSession(index: Int): Boolean {
    if (!canHandleTransportAction()) return false
    schedulePlaybackStateSave(force = true)
    val item = PlaybackSession.playQueueItem(index)
    if (item == null) {
      refreshTransportControls()
      return false
    }
    applySessionItem(item)
    return true
  }

  private fun handleMediaPlayAction(shouldPlay: Boolean) {
    when (gesturePreferences.mediaPlayGesture.get()) {
      SingleActionGesture.PlayPause -> {
        if (shouldPlay && !PlaybackSession.state.value.surfaceAttached && !takeAudioOwnership(reclaim = true)) return
        PlaybackSession.setPropertyBoolean("pause", !shouldPlay)
        refreshTransportControls()
      }
      SingleActionGesture.Custom -> PlaybackSession.command("keypress", CustomKeyCodes.MediaPlay.keyCode)
      SingleActionGesture.Seek,
      SingleActionGesture.None,
      -> Unit
    }
  }

  private fun applySessionItem(item: PlaybackItem) {
    val itemChanged = mediaIdentifier != item.stableId || mediaUri != item.originalUri
    val artworkChanged = activeArtworkUri != item.artworkUri
    val isAudio = resolveNotificationIsAudio(item, notificationIsAudio)
    notificationIsAudio = isAudio
    usesAudioBackgroundPlayback = isAudio
    mediaIdentifier = item.stableId
    mediaTitle = FileTypeUtils.stripExtension(item.title.orEmpty()).ifBlank { getString(R.string.player_unknown_video) }
    mediaArtist = item.artist.orEmpty()
    if (itemChanged) mediaAlbum = ""
    mediaUri = item.originalUri
    activeArtworkUri = item.artworkUri
    currentPositionSeconds = 0.0
    lastPublishedPositionSeconds = 0.0
    mediaDurationSeconds = item.durationSeconds?.takeIf { it > 0 }?.toDouble() ?: 0.0
    paused = PlaybackSession.state.value.paused
    // Speed is core-wide and may remain unchanged across files, so no new property event is
    // guaranteed. Preserve the actual 2x/etc. rate used by Android's notification timeline.
    playbackSpeed =
      readMpvDouble("speed", playbackSpeed.toDouble())
        .toFloat().takeIf { it.isFinite() && it > 0f } ?: 1.0f
    isCurrentFavorite = false
    if (itemChanged || artworkChanged) {
      chapters = emptyList()
      currentChapterIndex = -1
      replaceOwnedThumbnail(null)
    }
    updateMediaSessionMetadata()
    updateMediaSessionPlaybackState()
    updateNotification()
    loadSessionArtwork(item)
    refreshFavoriteState(item)
  }

  private fun refreshFavoriteState(item: PlaybackItem) {
    favoriteStateJob?.cancel()
    val expectedIdentifier = item.stableId
    val favoritePath = item.originalUri.ifBlank { item.playableUri }
    val isAudio = resolveNotificationIsAudio(item, notificationIsAudio)
    if (favoritePath.isBlank()) {
      if (isCurrentFavorite) {
        isCurrentFavorite = false
        refreshTransportControls()
      }
      return
    }
    favoriteStateJob =
      serviceScope.launch {
        playlistRepository.observeIsFavorite(favoritePath, isAudio).collect { favorite ->
          if (mediaIdentifier == expectedIdentifier) {
            if (isCurrentFavorite != favorite) {
              isCurrentFavorite = favorite
              refreshTransportControls()
            }
          }
        }
      }
  }

  /** The home-screen widget's favorite toggle. */
  private fun toggleCurrentItemFavorite() {
    if (favoriteActionJob?.isActive == true) return
    val item = PlaybackSession.queue.value.currentItem ?: return
    val favoritePath = item.originalUri.ifBlank { item.playableUri }
    val favoriteName = item.title?.takeIf { it.isNotBlank() } ?: mediaTitle
    val isAudio = resolveNotificationIsAudio(item, notificationIsAudio)
    favoriteActionJob =
      serviceScope.launch {
        runCatching {
          withContext(Dispatchers.IO) {
            playlistRepository.toggleFavorite(favoritePath, favoriteName, isAudio)
          }
        }.onFailure { error ->
          Log.e(TAG, "Failed to toggle favorite from notification", error)
        }
      }
  }

  /** Media notification favorite path: serialize taps and publish the resulting state immediately. */
  private fun toggleCurrentMediaNotificationFavorite() {
    val item = PlaybackSession.queue.value.currentItem ?: return
    val favoritePath = item.originalUri.ifBlank { item.playableUri }
    if (favoritePath.isBlank()) return
    val favoriteName = item.title?.takeIf { it.isNotBlank() } ?: mediaTitle
    val isAudio = resolveNotificationIsAudio(item, notificationIsAudio)
    val expectedIdentifier = item.stableId

    serviceScope.launch {
      runCatching {
        withContext(Dispatchers.IO) {
          mediaFavoriteActionMutex.withLock {
            playlistRepository.toggleFavorite(favoritePath, favoriteName, isAudio)
          }
        }
      }.onSuccess { favorite ->
        if (mediaIdentifier == expectedIdentifier && isCurrentFavorite != favorite) {
          isCurrentFavorite = favorite
          refreshTransportControls()
        }
      }.onFailure { error ->
        Log.e(TAG, "Failed to toggle favorite from media notification", error)
      }
    }
  }

  private fun loadSessionArtwork(item: PlaybackItem) {
    val revision = thumbnailRepository.cacheRevision.value
    val expectedIdentifier = item.stableId
    serviceScope.launch {
      val loaded = thumbnailRepository.getArtwork(
        source = item.originalUri,
        artworkUri = item.artworkUri,
        isAudio = resolveNotificationIsAudio(item, usesAudioBackgroundPlayback),
        widthPx = 480,
      ) ?: return@launch
      if (mediaIdentifier != expectedIdentifier || revision != thumbnailRepository.cacheRevision.value) {
        return@launch
      }
      val changed = replaceOwnedThumbnail(loaded)
      if (changed) {
        updateMediaSessionMetadata()
        updateNotification()
      }
    }
  }

  private fun resolveNotificationIsAudio(
    item: PlaybackItem,
    fallback: Boolean,
  ): Boolean =
    when (item.declaredMediaKind()) {
      DeclaredPlaybackMediaKind.AUDIO -> true
      DeclaredPlaybackMediaKind.VIDEO -> false
      DeclaredPlaybackMediaKind.UNKNOWN -> fallback
    }

  private fun syncQueueState(queueState: PlaybackQueueState) {
    if (!::mediaSession.isInitialized) return
    publishMediaSessionQueue(queueState)
    // Repeat mode remains a player/queue feature, but is intentionally not published through
    // MediaSession so the Media notification cannot expose Repeat / Repeat One / Repeat All.
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
    MediaPlayerWidget.requestUpdate(this)
  }

  private fun togglePlaybackFromNotification() {
    if (!canHandleTransportAction()) return
    val shouldPlay = PlaybackSession.getPropertyBoolean("pause") != false
    if (shouldPlay && !PlaybackSession.state.value.surfaceAttached && !takeAudioOwnership(reclaim = true)) return
    paused = !shouldPlay
    PlaybackSession.setPropertyBoolean("pause", paused)
    refreshTransportControls()
  }

  private fun stopPlaybackAndService(
    force: Boolean = false,
    closeVisiblePlayer: Boolean = true,
  ) {
    if (!force && !canHandleTransportAction()) return
    handingBackToActivity = false
    schedulePlaybackStateSave(force = true)
    torrentStreamingEngine.stopStream()
    PlaybackSession.stop(clearQueue = true)
    paused = true
    mediaSession.setPlaybackState(
      PlaybackStateCompat
        .Builder()
        .setActions(0L)
        .setState(PlaybackStateCompat.STATE_STOPPED, sanitizedPositionMs(), 0f, SystemClock.elapsedRealtime())
        .build(),
    )
    abandonAudioOwnership()
    stopForegroundNotification("playback was closed")
    stopSelf()
    // Close from the notification shade while the full player is on screen closes that player too,
    // instead of leaving it on an emptied session.
    if (closeVisiblePlayer && activityForeground) playerActivity?.get()?.onPlaybackClosedFromMediaControls()
  }

  private fun stopDetachedPlaybackIfNeeded(expectedGeneration: Long? = null) {
    // Never kill the shared PlaybackSession media while an Activity is taking it back over.
    // A fresh launch may already have destroyed and recreated the shared session before this
    // service's asynchronous onDestroy() callback reaches here.
    if ((mpvAccessReleased && expectedGeneration == null) || activityForeground || handingBackToActivity ||
      activeInstance !== this
    ) return
    PlaybackSession.stopIfDetached(
      expectedGeneration = expectedGeneration ?: PlaybackSession.state.value.generation,
      ownerIsActive = { activeInstance === this && !activityForeground && !handingBackToActivity },
      beforeStop = { torrentStreamingEngine.stopStream() },
    )
  }

  private fun handleDetachedEndOfFile(expectedGeneration: Long, naturalEnd: Boolean = false) {
    // A retained TextureView, mini player or external display is not an Activity playback owner.
    // Only the foreground handoff decides whether the Activity or service advances the queue.
    if (!canHandleDetachedTransport() || !foregroundReady) return
    val state = PlaybackSession.state.value
    if (state.generation != expectedGeneration || handledEofGeneration == expectedGeneration) return
    if (state.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND) &&
      !(naturalEnd && state.phase == PlaybackPhase.IDLE)
    ) return
    if (!naturalEnd) {
      if (PlaybackSession.getPropertyBoolean("eof-reached") != true) return
      // Read the live timeline here; the notification's throttled/coroutine-updated fields can
      // still describe the previous position when the EOF property event arrives.
      val duration = PlaybackSession.getPropertyDouble("duration") ?: mediaDurationSeconds
      val position = PlaybackSession.getPropertyDouble("time-pos") ?: currentPositionSeconds
      if (duration > 0.0 && position < duration - 2.0) return
    }
    handledEofGeneration = expectedGeneration
    if (AudiobookPlayback.handleEndOfFile()) return
    val queueState = PlaybackSession.queue.value
    val autoplay = queueState.currentItem?.audiobook != null ||
      if (notificationIsAudio) playerPreferences.autoplayNextAudio.get() else playerPreferences.autoplayNextVideo.get()
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
    if (::mediaSession.isInitialized) return
    mediaSession =
      MediaSessionCompat(this, TAG).apply {
        setCallback(
          object : MediaSessionCompat.Callback() {
            override fun onPlay() {
              if (!canHandleTransportAction()) return
              Log.d(TAG, "onPlay called")
              handleMediaPlayAction(shouldPlay = true)
            }

            override fun onPause() {
              if (!canHandleTransportAction()) return
              Log.d(TAG, "onPause called")
              handleMediaPlayAction(shouldPlay = false)
            }

            override fun onStop() {
              if (!canHandleTransportAction()) return
              Log.d(TAG, "onStop called")
              // A controller's stop (car, watch, headset) pauses a player that is on screen; it
              // ends only playback that runs without one. The notification's Close always ends.
              if (activityForeground) {
                PlaybackSession.setPropertyBoolean("pause", true)
                refreshTransportControls()
              } else {
                stopPlaybackAndService()
              }
            }

            override fun onSkipToNext() {
              if (!canHandleTransportAction()) return
              Log.d(TAG, "onSkipToNext called")
              playNextFromSession()
            }

            override fun onSkipToPrevious() {
              if (!canHandleTransportAction()) return
              Log.d(TAG, "onSkipToPrevious called")
              playPreviousFromSession()
            }

            override fun onSkipToQueueItem(id: Long) {
              if (!canHandleTransportAction()) return
              val index = publishedQueueIndexes[id] ?: return
              schedulePlaybackStateSave(force = true)
              PlaybackSession.playQueueItem(index)?.let(::applySessionItem)
            }

            override fun onSeekTo(pos: Long) {
              if (!canHandleTransportAction()) return
              Log.d(TAG, "onSeekTo called: $pos")
              val duration = sanitizedDurationMs()
              val resolvedPosition = pos.coerceIn(0L, duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
              currentPositionSeconds = resolvedPosition / 1000.0
              PlaybackSession.setPropertyDouble("time-pos", currentPositionSeconds)
              refreshTransportControls()
            }

            override fun onSetShuffleMode(shuffleMode: Int) {
              if (!canHandleDetachedTransport()) return
              when (shuffleMode) {
                PlaybackStateCompat.SHUFFLE_MODE_NONE -> PlaybackSession.setShuffleEnabled(false)
                PlaybackStateCompat.SHUFFLE_MODE_ALL -> PlaybackSession.setShuffleEnabled(true)
              }
            }

            override fun onCustomAction(
              action: String?,
              extras: android.os.Bundle?,
            ) {
              if (!canHandleTransportAction()) return
              when (action) {
                ACTION_NOTIFICATION_MEDIA_FAVORITE -> toggleCurrentMediaNotificationFavorite()
                ACTION_NOTIFICATION_CLOSE -> stopPlaybackAndService(force = true)
              }
            }
          }.also { sessionCallback = it },
        )

        setPlaybackToLocal(AudioManager.STREAM_MUSIC)
        isActive = false
      }
    sessionToken = mediaSession.sessionToken
  }

  private fun canHandleDetachedTransport(): Boolean =
    foregroundReady && !mpvAccessReleased && !activityForeground && !handingBackToActivity

  private fun canHandleTransportAction(): Boolean =
    foregroundReady && !mpvAccessReleased && (activityForeground || !handingBackToActivity)

  private fun updateMediaSessionMetadata() {
    if (!::mediaSession.isInitialized) return
    try {
      val title = mediaTitle.ifBlank { getString(R.string.player_unknown_video) }
      val duration = sanitizedDurationMs()
      val metadataBuilder =
        MediaMetadataCompat
          .Builder()
          .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaIdentifier)
          .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
          .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
          .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, mediaArtist)
          .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, mediaAlbum)
          .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, notificationText())
          // -1 is the documented "unknown" length; 0 makes some shades draw an empty timeline.
          .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration.takeIf { it > 0L } ?: -1L)

      // System media controls read METADATA_KEY_ART first, as mpv-android publishes it.
      thumbnail?.takeUnless { it.isRecycled }?.let {
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
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
    if (!::mediaSession.isInitialized) return
    try {
      val duration = sanitizedDurationMs()
      // Like mpv-android, both play and pause are always offered alongside play/pause.
      var actions =
        PlaybackStateCompat.ACTION_PLAY_PAUSE or
          PlaybackStateCompat.ACTION_PLAY or
          PlaybackStateCompat.ACTION_PAUSE or
          PlaybackStateCompat.ACTION_STOP
      if (duration > 0L) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
      if (PlaybackSession.hasPrevious()) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
      if (PlaybackSession.hasNext()) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT

      val session = PlaybackSession.state.value
      val state =
        when (session.phase) {
          PlaybackPhase.LOADING, PlaybackPhase.INITIALIZING -> PlaybackStateCompat.STATE_BUFFERING
          PlaybackPhase.UNINITIALIZED -> PlaybackStateCompat.STATE_NONE
          PlaybackPhase.IDLE, PlaybackPhase.STOPPING -> PlaybackStateCompat.STATE_STOPPED
          PlaybackPhase.ERROR -> PlaybackStateCompat.STATE_ERROR
          PlaybackPhase.READY, PlaybackPhase.BACKGROUND ->
            when {
              paused -> PlaybackStateCompat.STATE_PAUSED
              session.isBuffering -> PlaybackStateCompat.STATE_BUFFERING
              else -> PlaybackStateCompat.STATE_PLAYING
            }
        }
      if (
        state == PlaybackStateCompat.STATE_STOPPED ||
        state == PlaybackStateCompat.STATE_NONE ||
        state == PlaybackStateCompat.STATE_ERROR
      ) {
        actions = 0L
      }
      val stateSpeed = if (state == PlaybackStateCompat.STATE_PLAYING) playbackSpeed else 0f
      val positionMs = sanitizedPositionMs()
      val updatedAt = SystemClock.elapsedRealtime()
      val stateBuilder =
        PlaybackStateCompat
          .Builder()
          .setActions(actions)
          .setActiveQueueItemId(activeQueueItemId)
          .setState(state, positionMs, stateSpeed, updatedAt)
      if (actions != 0L) {
        val favoriteLabel = favoriteActionLabel()
        stateBuilder.addCustomAction(
          PlaybackStateCompat.CustomAction
            .Builder(ACTION_NOTIFICATION_MEDIA_FAVORITE, favoriteLabel, favoriteActionIcon())
            .build(),
        )
        stateBuilder.addCustomAction(
          PlaybackStateCompat.CustomAction
            .Builder(ACTION_NOTIFICATION_CLOSE, getString(R.string.notification_close), Icons.Platform.Close)
            .build(),
        )
      }
      mediaSession.setPlaybackState(stateBuilder.build())
      sessionPositionSeconds = positionMs / 1000.0
      sessionPositionSpeed = stateSpeed
      sessionPositionUpdatedAt = updatedAt
    } catch (e: Exception) {
      Log.e(TAG, "Error updating MediaSession playback state", e)
    }
  }

  /** Android 13+ media controls render and advance Media-style progress from the session. */
  private fun systemAdvancesMediaPosition(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

  /** True once the system's extrapolated position is off, e.g. after a stall or a speed change. */
  private fun sessionPositionDrifted(now: Long): Boolean {
    val elapsedSeconds = (now - sessionPositionUpdatedAt).coerceAtLeast(0L) / 1000.0
    val extrapolated = sessionPositionSeconds + elapsedSeconds * sessionPositionSpeed
    return abs(currentPositionSeconds - extrapolated) > MEDIA_SESSION_DRIFT_TOLERANCE_SECONDS
  }

  @SuppressLint("MissingPermission") // MediaStyle + session token is exempt from POST_NOTIFICATIONS.
  private fun updateNotification() {
    if (!foregroundReady) return

    try {
      val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      // MediaStyle tied to the active session: exempt from POST_NOTIFICATIONS, as in mpv-android.
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
    // Queue selection publishes the incoming title before native loadfile replaces the old file.
    // Never put the outgoing position (or a cached position) under the incoming notification.
    currentPositionSeconds = PlaybackSession.readLoadedPlaybackState(mediaIdentifier) { position, _ -> position } ?: 0.0
    val seconds = currentPositionSeconds.takeIf { it.isFinite() && it > 0.0 } ?: return 0L
    return (seconds * 1000.0)
      .coerceAtMost(Long.MAX_VALUE.toDouble())
      .toLong()
      .coerceAtMost(sanitizedDurationMs().takeIf { it > 0L } ?: Long.MAX_VALUE)
  }

  // ==================== Notification Builders ====================

  private fun buildContentIntent(): PendingIntent {
    val currentItem = PlaybackSession.queue.value.currentItem
    val isAudio =
      notificationIsAudio || currentItem?.declaredMediaKind() == DeclaredPlaybackMediaKind.AUDIO
    val targetUri = currentItem?.originalUri?.takeIf { it.isNotBlank() } ?: mediaUri
    val targetTitle = currentItem?.title?.takeIf { it.isNotBlank() } ?: mediaTitle
    val targetIdentifier = currentItem?.stableId?.takeIf { it.isNotBlank() } ?: mediaIdentifier
    val contentIntent =
      Intent(this, PlayerActivity::class.java).apply {
        action = ACTION_OPEN_PLAYER
        type = currentItem?.mimeType ?: "audio/*".takeIf { isAudio }
        targetUri?.let { putExtra("uri", it) }
        putExtra("title", targetTitle)
        putExtra("media_identifier", targetIdentifier)
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
        putExtra("is_audio", isAudio)
        putExtra("media_library_audio", isAudio)
        currentItem?.audiobook?.let { book ->
          putExtra(AudiobookPlayback.EXTRA_BOOK_ID, book.bookId)
          putExtra(AudiobookPlayback.EXTRA_TRACK_ID, book.trackId)
        }
        flags =
          Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_NEW_TASK
      }

    return PendingIntent.getActivity(
      this,
      if (isAudio) AUDIO_CONTENT_INTENT_REQUEST_CODE else VIDEO_CONTENT_INTENT_REQUEST_CODE,
      contentIntent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  private fun buildTransportIntent(
    action: String,
    requestCode: Int,
  ): PendingIntent =
    transportIntents.getOrPut("$action#$requestCode") {
      PendingIntent.getService(
        this,
        requestCode,
        Intent(this, MediaPlaybackService::class.java).apply {
          this.action = action
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    }

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

  /** Media notification favorite action. */
  private fun mediaFavoriteAction() =
    NotificationCompat.Action(
      favoriteActionIcon(),
      favoriteActionLabel(),
      buildTransportIntent(ACTION_NOTIFICATION_MEDIA_FAVORITE, 1008),
    )

  private fun favoriteActionIcon(): Int =
    if (isCurrentFavorite) Icons.Platform.Favorite else Icons.Platform.FavoriteBorder

  private fun favoriteActionLabel(): String =
    getString(
      if (isCurrentFavorite) R.string.notification_saved_to_favorites else R.string.notification_add_to_favorites,
    )

  private fun closeAction() =
    NotificationCompat.Action(
      Icons.Platform.Close,
      getString(R.string.notification_close),
      buildTransportIntent(ACTION_NOTIFICATION_CLOSE, 1006),
    )

  /** Chapter name, else "artist · album" (mpv-android's formatArtistAlbum), else nothing. */
  private fun notificationText(): String? {
    val chapterName =
      if (currentChapterIndex >= 0) {
        chapters.getOrNull(currentChapterIndex)?.title?.takeIf { it.isNotBlank() }
      } else {
        null
      }
    return chapterName
      ?: listOf(mediaArtist, mediaAlbum).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotEmpty() }
  }

  /**
   * The same notification mpv-android posts: a public, low-priority, ongoing MediaStyle card tied
   * to the active session token, with previous/play-pause/next actions. Android exempts exactly this
   * from POST_NOTIFICATIONS, so it shows without the permission. Nothing else is added on purpose:
   * no silent group (setSilent), progress bar, sub text, category, delete intent or colour, which
   * OEM shades may treat as an ordinary notification and hide while notifications are off.
   */
  private fun buildNotification(): Notification {
    val builder =
      NotificationCompat
        .Builder(this, NOTIFICATION_CHANNEL_ID)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentTitle(mediaTitle.ifBlank { getString(R.string.player_unknown_video) })
        .setContentText(notificationText())
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentIntent(buildContentIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

    // Android 13+ media controls draw the session artwork; like mpv-android, the card carries no
    // bitmap of its own there. Older templates still draw the large icon.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      thumbnail?.takeUnless { it.isRecycled }?.let { artwork -> builder.setLargeIcon(artwork) }
    }

    val compactActions = ArrayList<Int>(3)
    var actionCount = 0
    if (PlaybackSession.hasPrevious()) {
      builder.addAction(prevAction())
      compactActions += actionCount++
    }
    builder.addAction(playPauseAction())
    compactActions += actionCount++
    if (PlaybackSession.hasNext()) {
      builder.addAction(nextAction())
      compactActions += actionCount++
    }
    builder.addAction(mediaFavoriteAction())
    builder.addAction(closeAction())

    val style =
      MediaStyle()
        .setMediaSession(mediaSession.sessionToken)
        .setShowActionsInCompactView(*compactActions.toIntArray())
    return builder.setStyle(style).build()
  }

  private fun stopForegroundNotification(reason: String) {
    // Every removal is logged with its cause, so a missing notification can be traced in Debug logs.
    if (foregroundReady) Log.d(TAG, "Media notification removed: $reason")
    foregroundReady = false
    if (::mediaSession.isInitialized) mediaSession.isActive = false
    runCatching {
      ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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

  // ==================== MPV Event Observers ====================

  // A property mpv reports without a value (a tag the next file lacks) clears that field.
  override fun eventProperty(property: String) {
    if (property == "metadata/by-key/artist" || property == "metadata/by-key/album") eventProperty(property, "")
  }

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
          // Any start of detached playback, including a new song that starts on its own, takes
          // audio focus so other apps pause. Without it (a call) playback waits instead.
          if (!value && canHandleDetachedTransport() && !hasAudioFocus && !takeAudioOwnership()) {
            paused = true
            PlaybackSession.setPropertyBoolean("pause", true)
          }
          updateMediaSessionPlaybackState()
          updateNotification()
          schedulePlaybackStateSave(force = true)
        }
      }
      "eof-reached" -> {
        // keep-open never emits a natural END_FILE, so detached advance must key off this flag.
        val generation = PlaybackSession.state.value.generation
        serviceScope.launch {
          if (value) {
            handleDetachedEndOfFile(generation)
          } else if (handledEofGeneration == generation) {
            // Repeat-one and a user seek can reach EOF again within the same media generation.
            handledEofGeneration = null
          }
        }
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
      "metadata/by-key/artist" -> {
        serviceScope.launch {
          // The queue item's artist (music library) stays when the file carries no tag.
          val artist = value.ifBlank { PlaybackSession.state.value.currentItem?.artist.orEmpty() }
          if (mediaArtist == artist) return@launch
          mediaArtist = artist
          updateMediaSessionMetadata()
          updateNotification()
        }
      }
      "metadata/by-key/album" -> {
        serviceScope.launch {
          if (mediaAlbum == value) return@launch
          mediaAlbum = value
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
        val updateInterval = MEDIA_NOTIFICATION_UPDATE_INTERVAL_MS
        val elapsedSinceUpdate = (currentTime - lastNotificationUpdateTime).coerceAtLeast(0L)
        val expectedAdvance = elapsedSinceUpdate / 1000.0 * playbackSpeed.coerceAtLeast(0f)
        val positionJump = abs(currentPositionSeconds - lastPublishedPositionSeconds) > expectedAdvance + 3.0
        if (positionJump && elapsedSinceUpdate < POSITION_JUMP_PUBLISH_INTERVAL_MS) {
          // A scrub jumps on every step. Republishing each jump rebuilt the notification and the
          // MediaSession state on the main thread many times a second, right while the finger moved.
          schedulePositionJumpPublish(POSITION_JUMP_PUBLISH_INTERVAL_MS - elapsedSinceUpdate)
          return
        }
        if (elapsedSinceUpdate >= updateInterval || positionJump) {
          positionJumpPublishJob?.cancel()
          positionJumpPublishJob = null
          lastNotificationUpdateTime = currentTime
          lastPublishedPositionSeconds = currentPositionSeconds
          // Android 13+ draws Media-style progress from the MediaSession and advances it itself.
          // Republishing every second repeated binder calls and a bitmap parcel on the main thread
          // during playback, so there it only happens when the system's estimate has drifted.
          val republish = positionJump || !systemAdvancesMediaPosition() || sessionPositionDrifted(currentTime)
          serviceScope.launch {
            schedulePlaybackStateSave()
            if (republish) {
              updateMediaSessionPlaybackState()
              updateNotification()
            }
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
      if (PlaybackSession.isNaturalEndFile(data)) {
        val generation = PlaybackSession.state.value.generation
        serviceScope.launch { handleDetachedEndOfFile(generation, naturalEnd = true) }
      }

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

  /**
   * The detached counterpart of PlayerActivity's restore, audio side only: re-add the
   * file's stored external audio tracks, apply its saved manual selection, and — only
   * when no manual selection applied — run the automatic correction, in the same order
   * and under the same mpv.conf ownership rule as the foreground path.
   */
  private suspend fun restoreDetachedAudioSelection(identifier: String, generation: Long) {
    if (identifier.isBlank()) return
    if (!PlaybackSession.isCurrentGeneration(generation) || !canHandleDetachedTransport()) return
    ExternalAudioDiscovery.autoload(this@MediaPlaybackService, generation)
    if (!PlaybackSession.isCurrentGeneration(generation) || !canHandleDetachedTransport()) return
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AUDIO_TRACK_SELECTION)) return

    val state = runCatching { playbackStateRepository.getVideoDataByTitle(identifier) }.getOrNull()
    if (!PlaybackSession.isCurrentGeneration(generation) || !canHandleDetachedTransport()) return

    if (state != null && state.externalAudio.isNotBlank()) {
      for (audioUri in state.externalAudio.split("|").filter { it.isNotBlank() }) {
        if (!PlaybackSession.isCurrentGeneration(generation)) return
        val uri = Uri.parse(audioUri)
        val path = runCatching { uri.resolveUri(this@MediaPlaybackService) }.getOrNull() ?: audioUri
        // "auto": present but not force-selected; the manual restore below decides.
        ExternalAudioDiscovery.addIfAbsent(path, generation)
      }
    }
    if (!PlaybackSession.isCurrentGeneration(generation)) return

    if (state != null && detachedTrackSelector.applySavedAudioSelection(state, generation)) return
    detachedTrackSelector.applyAutomaticAudioSelection(generation)
  }

  /** Publishes the newest position once the jump window closes, so a scrub's final spot still lands. */
  private fun schedulePositionJumpPublish(delayMillis: Long) {
    if (positionJumpPublishJob?.isActive == true) return
    positionJumpPublishJob =
      serviceScope.launch {
        delay(delayMillis)
        lastNotificationUpdateTime = SystemClock.elapsedRealtime()
        lastPublishedPositionSeconds = currentPositionSeconds
        schedulePlaybackStateSave()
        updateMediaSessionPlaybackState()
        updateNotification()
      }
  }

  private fun schedulePlaybackStateSave(force: Boolean = false) {
    val identifier = mediaIdentifier
    if (identifier.isBlank()) return

    val now = SystemClock.elapsedRealtime()
    if (!force && now - lastPlaybackStateSaveTime < PLAYBACK_STATE_SAVE_INTERVAL_MS) return
    lastPlaybackStateSaveTime = now

    playbackStateSaveJob?.cancel()
    playbackStateSaveJob =
      serviceScope.launch(Dispatchers.IO) {
        // The capture is several synchronous libmpv reads; doing them here keeps this periodic
        // save from stalling the main thread every few seconds while media plays.
        val snapshot = capturePlaybackStateSnapshot(identifier, oldState = null) ?: return@launch
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

  private fun savePlaybackStateBeforeTaskRemoval() {
    val identifier = mediaIdentifier
    if (identifier.isBlank()) return
    val snapshot = capturePlaybackStateSnapshot(identifier, oldState = null) ?: return

    val pendingSave = playbackStateSaveJob
    runBlocking(Dispatchers.IO) {
      pendingSave?.cancelAndJoin()
      persistPlaybackState(identifier, snapshot)
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

    return PlaybackSession.readLoadedPlaybackState(identifier) { position, duration ->
      PlaybackStateSnapshot(
        mediaIdentifier = identifier,
        mediaTitle = mediaTitle.ifBlank { identifier },
        currentPosition = position.toInt(),
        duration = duration.toInt(),
        isPositionRestorePending =
          PlaybackSession.isPositionRestorePending(PlaybackSession.state.value.activeGeneration),
        isLive = PlaybackSession.isLiveMedia(),
        playbackSpeed = readMpvDouble("speed", oldState?.playbackSpeed ?: DEFAULT_PLAYBACK_STATE_SPEED),
        videoZoom = PlaybackSession.videoZoom.value,
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
        sessionManualAudioId = captureSessionManualAudioId(identifier),
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
  }

  /**
   * Same rule as PlayerActivity's capture: a manual decision is persisted only when the
   * user picked a track by hand for this file and the current track is still that pick
   * (readMpvTrackId reports "no" as -1; the manual convention uses 0 for audio off).
   * External audio needs no field here: the snapshot leaves it blank and buildEntity
   * keeps the stored list, exactly like externalSubtitles on this path.
   */
  private fun captureSessionManualAudioId(identifier: String): Int? {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AUDIO_TRACK_SELECTION)) return null
    val marked = ManualAudioSelectionTracker.markedTrackId(identifier) ?: return null
    val current = readMpvTrackId("aid", -1)
    val currentSelectionId = if (current < 0) 0 else current
    return marked.takeIf { it == currentSelectionId }
  }

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
    stopFloatingLyrics()
    mpvAccessReleased = true
    updatePlaybackWakeLock()

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

  private fun stopFloatingLyrics() {
    floatingLyrics?.stop()
    floatingLyrics = null
  }

  /**
   * Undoes [releaseMpvAccessBeforeShutdown] for new playback. A closing player releases this
   * instance before it stops the core, and a player that binds or starts right after (a new file,
   * a restarted script runtime) can keep that same instance alive. It used to refuse every start
   * from then on, so that player's media played without any notification.
   */
  private fun rearmAfterMpvRelease() {
    Log.d(TAG, "Re-arming the playback service for new playback")
    mpvAccessReleased = false
    serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // startPlaybackObservers() adds the mpv observer, receivers and collectors again.
    playbackObserversStarted = false
    if (noisyReceiverRegistered) {
      runCatching { unregisterReceiver(noisyReceiver) }
      noisyReceiverRegistered = false
    }
    detachedRestoreGeneration = 0L
    if (::mediaSession.isInitialized) sessionCallback?.let { mediaSession.setCallback(it) }
    updatePlaybackWakeLock()
  }

  override fun onDestroy() {
    val detachedGeneration =
      if (mpvAccessReleased || activityForeground || handingBackToActivity || activeInstance !== this) {
        null
      } else {
        PlaybackSession.state.value.takeUnless { it.surfaceAttached }?.generation
      }
    try {
      Log.d(TAG, "Service destroyed")

      updateExternalDisplayWakeLock(false)
      releaseMpvAccessBeforeShutdown()
      foregroundReady = false
      playerActivity = null
      abandonAudioOwnership()
      if (noisyReceiverRegistered) {
        runCatching { unregisterReceiver(noisyReceiver) }
        noisyReceiverRegistered = false
      }

      try {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
      } catch (e: Exception) {
        Log.e(TAG, "Error stopping foreground", e)
      }

      try {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
      } catch (e: Exception) {
        Log.e(TAG, "Error canceling notification", e)
      }

      if (::mediaSession.isInitialized) {
        try {
          mediaSession.isActive = false
          mediaSession.release()
        } catch (e: Exception) {
          Log.e(TAG, "Error releasing media session", e)
        }
      }

      thumbnail?.let {
        if (!it.isRecycled) it.recycle()
      }
      thumbnail = null

      Log.d(TAG, "Service cleanup completed")
    } catch (e: Exception) {
      Log.e(TAG, "Error in onDestroy", e)
    } finally {
      if (detachedGeneration != null) {
        runCatching { stopDetachedPlaybackIfNeeded(detachedGeneration) }
          .onFailure { Log.e(TAG, "Error stopping detached playback after service destruction", it) }
      }
      if (activeInstance === this) {
        activeInstance = null
      }
      handingBackToActivity = false
      super.onDestroy()
    }
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    // A player still on screen (picture-in-picture lives in its own task) keeps playing when
    // another mpvRx card is cleared.
    if (activityForeground) {
      super.onTaskRemoved(rootIntent)
      return
    }
    // Clearing mpvRx from Recents ends its background playback, music as well as video.
    Log.d(TAG, "Task removed - ending playback")
    savePlaybackStateBeforeTaskRemoval()
    if (::mediaSession.isInitialized && !mpvAccessReleased) {
      stopPlaybackAndService(force = true)
    } else {
      torrentStreamingEngine.stopStream()
      PlaybackSession.stop(clearQueue = true)
      stopForegroundNotification("the task was removed from Recents")
      stopSelf()
    }
    super.onTaskRemoved(rootIntent)
  }
}

