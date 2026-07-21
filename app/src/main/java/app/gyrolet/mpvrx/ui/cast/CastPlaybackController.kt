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
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener

data class CastMediaSnapshot(
  val source: Uri,
  val title: String,
  val mimeType: String?,
  val durationMs: Long,
  val positionMs: Long,
  val isPlaying: Boolean,
)

/** Transfers the active mpv item to the Default Media Receiver and back. */
class CastPlaybackController(
  private val activity: AppCompatActivity,
  private val currentMedia: () -> CastMediaSnapshot?,
  private val pauseLocal: () -> Unit,
  private val restoreLocal: (positionMs: Long, play: Boolean) -> Unit,
  private val notifyUser: (String) -> Unit,
) {
  private var castContext: CastContext? = null
  private var released = false
  private var localWasPlaying = false
  private var lastRemotePositionMs = 0L
  private var remoteWasPlaying = false
  private var capturedRemoteEndState = false
  private var transferredByThisController = false

  private val sessionListener =
    object : SessionManagerListener<CastSession> {
      override fun onSessionStarted(session: CastSession, sessionId: String) {
        loadCurrentMedia(session)
      }

      override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
        val remote = session.remoteMediaClient
        if (remote?.mediaInfo != null) {
          transferredByThisController = true
          localWasPlaying = currentMedia()?.isPlaying == true
          pauseLocal()
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

      override fun onSessionEnded(session: CastSession, error: Int) {
        CastMediaServer.stop()
        if (transferredByThisController) {
          restoreLocal(
            lastRemotePositionMs,
            if (capturedRemoteEndState) remoteWasPlaying else localWasPlaying,
          )
        }
        capturedRemoteEndState = false
        transferredByThisController = false
      }

      override fun onSessionStartFailed(session: CastSession, error: Int) {
        CastMediaServer.stop()
        notifyUser("Could not connect to Cast device")
      }

      override fun onSessionResumeFailed(session: CastSession, error: Int) {
        CastMediaServer.stop()
      }

      override fun onSessionStarting(session: CastSession) = Unit
      override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
      override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
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
    val context = castContext
    castContext = null
    context?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
    if (context?.sessionManager?.currentCastSession?.isConnected != true) {
      CastMediaServer.stop()
    }
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
    val metadata = MediaMetadata(metadataType).apply {
      putString(MediaMetadata.KEY_TITLE, snapshot.title)
    }
    val mediaInfo =
      MediaInfo.Builder(contentUrl)
        .setStreamType(
          if (snapshot.durationMs > 0L) MediaInfo.STREAM_TYPE_BUFFERED else MediaInfo.STREAM_TYPE_LIVE,
        )
        .setContentType(contentType)
        .setMetadata(metadata)
        .setStreamDuration(snapshot.durationMs.coerceAtLeast(0L))
        .build()
    val request =
      MediaLoadRequestData.Builder()
        .setMediaInfo(mediaInfo)
        .setAutoplay(snapshot.isPlaying)
        .setCurrentTime(snapshot.positionMs.coerceAtLeast(0L))
        .build()
    val remote = session.remoteMediaClient ?: run {
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
          pauseLocal()
          activity.startActivity(Intent(activity, CastExpandedControlsActivity::class.java))
        } else {
          CastMediaServer.stop()
          notifyUser(result.status.statusMessage ?: "Unable to play this media on the Cast device")
        }
      }
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

  private companion object {
    const val TAG = "CastPlaybackController"
  }
}
