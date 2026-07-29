/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.cast

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CastMediaSnapshot(
  val source: Uri,
  val title: String,
  val mimeType: String?,
  val durationMs: Long,
  val positionMs: Long,
  val isPlaying: Boolean,
)

class CastPlaybackController(
  private val activity: AppCompatActivity,
  private val currentMedia: () -> CastMediaSnapshot?,
  private val pauseLocal: () -> Unit,
  private val restoreLocal: (positionMs: Long, play: Boolean) -> Unit,
  private val notifyUser: (String) -> Unit,
) {
  init {
    instance = this
  }

  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private val _castState = MutableStateFlow(CastSessionState())
  val castState: StateFlow<CastSessionState> = _castState.asStateFlow()

  private var castContext: CastContext? = null
  private var castSession: CastSession? = null
  private var remoteMediaClient: RemoteMediaClient? = null
  private var released = false
  private var localWasPlaying = false
  private var lastRemotePositionMs = 0L
  private var remoteWasPlaying = false
  private var capturedRemoteEndState = false
  private var transferredByThisController = false
  private var positionPollingJob: Job? = null
  private var volumeDebounceJob: Job? = null

  private val sessionListener =
    object : SessionManagerListener<CastSession> {
      override fun onSessionStarted(
        session: CastSession,
        sessionId: String,
      ) {
        onSessionReady(session)
        loadCurrentMedia(session)
      }

      override fun onSessionResumed(
        session: CastSession,
        wasSuspended: Boolean,
      ) {
        onSessionReady(session)
        val remote = session.remoteMediaClient
        if (remote?.mediaInfo != null) {
          transferredByThisController = true
          localWasPlaying = currentMedia()?.isPlaying == true
          pauseLocal()
          startPositionPolling()
        } else {
          loadCurrentMedia(session)
        }
      }

      override fun onSessionEnding(session: CastSession) {
        session.remoteMediaClient?.let { remote ->
          lastRemotePositionMs = remote.approximateStreamPosition
          remoteWasPlaying = remote.isPlaying
          capturedRemoteEndState = true
        }
      }

      override fun onSessionEnded(
        session: CastSession,
        error: Int,
      ) {
        CastMediaServer.stop()
        stopPositionPolling()
        if (transferredByThisController) {
          restoreLocal(
            lastRemotePositionMs,
            if (capturedRemoteEndState) remoteWasPlaying else localWasPlaying,
          )
        }
        capturedRemoteEndState = false
        transferredByThisController = false
        remoteMediaClient = null
        castSession = null
        _castState.value = CastSessionState()
      }

      override fun onSessionStartFailed(
        session: CastSession,
        error: Int,
      ) {
        CastMediaServer.stop()
        notifyUser("Could not connect to Cast device")
      }

      override fun onSessionResumeFailed(
        session: CastSession,
        error: Int,
      ) {
        CastMediaServer.stop()
      }

      override fun onSessionStarting(session: CastSession) = Unit

      override fun onSessionResuming(
        session: CastSession,
        sessionId: String,
      ) = Unit

      override fun onSessionSuspended(
        session: CastSession,
        reason: Int,
      ) = Unit
    }

  private val remoteMediaClientCallback =
    object : RemoteMediaClient.Callback() {
      override fun onStatusUpdated() {
        updatePositionFromRemote()
      }
    }

  private fun onSessionReady(session: CastSession) {
    castSession = session
    remoteMediaClient = session.remoteMediaClient
    remoteMediaClient?.registerCallback(remoteMediaClientCallback)
    _castState.update {
      it.copy(
        isConnected = true,
        deviceName = session.castDevice?.friendlyName,
        volume = session.volume,
        isMuted = session.isMute,
      )
    }
  }

  fun start() {
    released = false
    try {
      CastContext
        .getSharedInstance(activity.applicationContext, ContextCompat.getMainExecutor(activity))
        .addOnSuccessListener { context ->
          if (released) return@addOnSuccessListener
          castContext = context
          context.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
          context.sessionManager.currentCastSession
            ?.takeIf { it.isConnected }
            ?.let { session -> sessionListener.onSessionResumed(session, false) }
        }.addOnFailureListener { exception ->
          Log.w(TAG, "Google Cast is unavailable; continuing with local playback", exception)
        }
    } catch (exception: RuntimeException) {
      Log.w(TAG, "Google Cast initialization failed; continuing with local playback", exception)
    }
  }

  fun release() {
    released = true
    stopPositionPolling()
    volumeDebounceJob?.cancel()
    val context = castContext
    castContext = null
    remoteMediaClient?.unregisterCallback(remoteMediaClientCallback)
    remoteMediaClient = null
    castSession = null
    context?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
    if (context?.sessionManager?.currentCastSession?.isConnected != true) {
      CastMediaServer.stop()
    }
  }

  fun play() {
    remoteMediaClient?.play()?.setResultCallback { result ->
      if (!result.status.isSuccess) {
        Log.w(TAG, "Cast play failed: ${result.status.statusCode}")
      }
    }
  }

  fun pause() {
    remoteMediaClient?.pause()?.setResultCallback { result ->
      if (!result.status.isSuccess) {
        Log.w(TAG, "Cast pause failed: ${result.status.statusCode}")
      }
    }
  }

  fun seekTo(positionMs: Long) {
    remoteMediaClient?.seek(
      MediaSeekOptions.Builder().setPosition(positionMs.coerceAtLeast(0L)).build(),
    )
  }

  fun setVolume(volume: Double) {
    volumeDebounceJob?.cancel()
    volumeDebounceJob =
      scope.launch {
        delay(300)
        try {
          castSession?.volume = volume.coerceIn(0.0, 1.0)
          _castState.update { it.copy(volume = volume.coerceIn(0.0, 1.0)) }
        } catch (e: Exception) {
          Log.w(TAG, "Failed to set cast volume", e)
        }
      }
  }

  fun setPlaybackSpeed(speed: Float) {
    remoteMediaClient?.setPlaybackRate(speed.toDouble())
    _castState.update { it.copy(playbackSpeed = speed) }
  }

  fun disconnect() {
    scope.launch {
      try {
        stopPositionPolling()
        castContext?.sessionManager?.endCurrentSession(true)
      } catch (e: Exception) {
        Log.w(TAG, "Error disconnecting cast", e)
      }
    }
  }

  fun openRemoteController() {
    activity.startActivity(Intent(activity, CastRemoteControllerActivity::class.java))
  }

  private fun loadCurrentMedia(session: CastSession) {
    val snapshot = currentMedia()
    if (snapshot == null) {
      notifyUser("Media is not ready to cast")
      castContext?.sessionManager?.endCurrentSession(true)
      return
    }

    val contentUrl = resolveContentUrl(snapshot)
    if (contentUrl == null) {
      notifyUser("This media source cannot be reached by the Cast device")
      castContext?.sessionManager?.endCurrentSession(true)
      return
    }

    val contentType = snapshot.mimeType ?: inferMimeType(snapshot.source)
    val metadataType =
      if (contentType.startsWith("audio/")) {
        MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
      } else {
        MediaMetadata.MEDIA_TYPE_MOVIE
      }
    val metadata =
      MediaMetadata(metadataType).apply {
        putString(MediaMetadata.KEY_TITLE, snapshot.title)
      }
    val mediaInfo =
      MediaInfo
        .Builder(contentUrl)
        .setStreamType(
          if (snapshot.durationMs > 0L) MediaInfo.STREAM_TYPE_BUFFERED else MediaInfo.STREAM_TYPE_LIVE,
        ).setContentType(contentType)
        .setMetadata(metadata)
        .setStreamDuration(snapshot.durationMs.coerceAtLeast(0L))
        .build()
    val request =
      MediaLoadRequestData
        .Builder()
        .setMediaInfo(mediaInfo)
        .setAutoplay(snapshot.isPlaying)
        .setCurrentTime(snapshot.positionMs.coerceAtLeast(0L))
        .build()
    val remote =
      session.remoteMediaClient ?: run {
        notifyUser("Cast receiver is not ready")
        return
      }

    remote.load(request).setResultCallback { result ->
      activity.runOnUiThread {
        if (result.status.isSuccess) {
          localWasPlaying = snapshot.isPlaying
          lastRemotePositionMs = snapshot.positionMs
          remoteWasPlaying = snapshot.isPlaying
          capturedRemoteEndState = false
          transferredByThisController = true
          _castState.update {
            it.copy(
              title = snapshot.title,
              duration = snapshot.durationMs.coerceAtLeast(0L),
              currentPosition = snapshot.positionMs.coerceAtLeast(0L),
            )
          }
          pauseLocal()
          startPositionPolling()
          openRemoteController()
        } else {
          CastMediaServer.stop()
          notifyUser(result.status.statusMessage ?: "Unable to play this media on the Cast device")
        }
      }
    }
  }

  private fun startPositionPolling() {
    stopPositionPolling()
    positionPollingJob =
      scope.launch {
        while (true) {
          delay(1_000)
          updatePositionFromRemote()
        }
      }
  }

  private fun stopPositionPolling() {
    positionPollingJob?.cancel()
    positionPollingJob = null
  }

  private fun updatePositionFromRemote() {
    try {
      val client = remoteMediaClient ?: return
      val position = client.approximateStreamPosition
      if (position >= 0) {
        _castState.update { it.copy(currentPosition = position) }
      }

      val mediaStatus = client.mediaStatus
      if (mediaStatus != null) {
        val isPlaying = mediaStatus.playerState == MediaStatus.PLAYER_STATE_PLAYING
        val isPaused = mediaStatus.playerState == MediaStatus.PLAYER_STATE_PAUSED
        val isBuffering = mediaStatus.playerState == MediaStatus.PLAYER_STATE_BUFFERING
        val duration = mediaStatus.mediaInfo?.streamDuration ?: 0L

        _castState.update {
          it.copy(
            isPlaying = isPlaying,
            isPaused = isPaused,
            isBuffering = isBuffering,
            duration = if (duration > 0) duration else it.duration,
          )
        }
      }

      val session = castSession
      if (session != null) {
        _castState.update {
          it.copy(volume = session.volume, isMuted = session.isMute)
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error polling cast position", e)
    }
  }

  private fun resolveContentUrl(snapshot: CastMediaSnapshot): String? {
    val scheme = snapshot.source.scheme?.lowercase()
    if (scheme == "http" || scheme == "https") {
      val host = snapshot.source.host.orEmpty()
      if (host != "127.0.0.1" && host != "localhost" && host != "0.0.0.0") {
        return snapshot.source.toString()
      }
      return null
    }
    if (scheme == "content" || scheme == "file") {
      return CastMediaServer.expose(
        context = activity,
        source = snapshot.source,
        mimeType = snapshot.mimeType ?: inferMimeType(snapshot.source),
      )
    }
    return null
  }

  private fun inferMimeType(uri: Uri): String {
    activity.contentResolver.getType(uri)?.let { return it }
    val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "video/mp4"
  }

  companion object {
    const val TAG = "CastPlaybackController"

    @Volatile
    var instance: CastPlaybackController? = null
  }
}
