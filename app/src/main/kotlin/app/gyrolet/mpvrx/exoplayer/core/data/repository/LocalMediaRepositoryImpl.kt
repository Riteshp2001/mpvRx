package app.gyrolet.mpvrx.exoplayer.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.exoplayer.core.data.models.RemotePlaybackInfo
import app.gyrolet.mpvrx.exoplayer.core.data.models.VideoState
import app.gyrolet.mpvrx.exoplayer.core.model.Folder
import app.gyrolet.mpvrx.exoplayer.core.model.Video
import app.gyrolet.mpvrx.repository.MediaFileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

class LocalMediaRepositoryImpl(
    private val context: Context,
    private val playbackStateRepository: PlaybackStateRepository,
) : MediaRepository {

    override fun getVideosFlow(): Flow<List<Video>> = flow {
        // Implementation for listing all videos if needed for ExoPlayer specific screens
        emit(emptyList())
    }

    override fun getVideosFlowFromFolderPath(folderPath: String): Flow<List<Video>> = flow {
        val mpvVideos = runCatching { MediaFileRepository.getVideosInFolder(context, folderPath) }.getOrElse { emptyList() }
        emit(mpvVideos.map { it.toExoVideo() })
    }

    override fun getRecycleBinVideosFlow(): Flow<List<Video>> = flow {
        emit(emptyList())
    }

    override fun getFoldersFlow(): Flow<List<Folder>> = flow {
        emit(emptyList())
    }

    override suspend fun getVideoByUri(uri: String): Video? {
        // This is tricky because MpvRx doesn't have a direct "getVideoByUri" for any URI
        // But we can try to resolve it if it's a file
        val parsedUri = Uri.parse(uri)
        if (parsedUri.scheme == "file") {
            val file = File(parsedUri.path ?: return null)
            if (file.exists()) {
                // We'd need to scan it or have it in cache
                return Video(
                    id = uri.hashCode().toLong(),
                    path = file.absolutePath,
                    parentPath = file.parent ?: "",
                    duration = 0,
                    uriString = uri,
                    nameWithExtension = file.name,
                    width = 0,
                    height = 0,
                    size = file.length()
                )
            }
        }
        return null
    }

    override suspend fun getVideoState(uri: String): VideoState? {
        val mediaTitle = getMediaTitleFromUri(uri)
        val state = playbackStateRepository.getVideoDataByTitle(mediaTitle) ?: return null
        return state.toExoVideoState(uri)
    }

    override suspend fun getVideoState(uris: List<String>): VideoState? {
        for (uri in uris) {
            val state = getVideoState(uri)
            if (state != null) return state
        }
        return null
    }

    override suspend fun getCanonicalMediaUri(uri: String): String = uri

    override suspend fun getRemotePlaybackStates(stateKeys: List<String>): Map<String, RemotePlaybackInfo> {
        return emptyMap()
    }

    override suspend fun updateMediumLastPlayedTime(uri: String, lastPlayedTime: Long) {
        // MpvRx doesn't track last played time per video in PlaybackStateEntity yet, 
        // but it does in RecentlyPlayedEntity. 
        // For simplicity, we can ignore this or add a column to PlaybackStateEntity.
    }

    override suspend fun updateMediumPosition(uri: String, position: Long) {
        val mediaTitle = getMediaTitleFromUri(uri)
        val existing = playbackStateRepository.getVideoDataByTitle(mediaTitle)
        val entity = existing?.copy(
            lastPosition = (position / 1000).toInt()
        ) ?: PlaybackStateEntity(
            mediaTitle = mediaTitle,
            lastPosition = (position / 1000).toInt(),
            playbackSpeed = 1.0,
            sid = -1,
            subDelay = 0,
            subSpeed = 1.0,
            aid = -1,
            audioDelay = 0
        )
        playbackStateRepository.upsert(entity)
    }

    override suspend fun updateMediumPlaybackSpeed(uri: String, playbackSpeed: Float) {
        val mediaTitle = getMediaTitleFromUri(uri)
        val existing = playbackStateRepository.getVideoDataByTitle(mediaTitle)
        if (existing != null) {
            playbackStateRepository.upsert(existing.copy(playbackSpeed = playbackSpeed.toDouble()))
        }
    }

    override suspend fun updateMediumAudioTrack(uri: String, audioTrackIndex: Int) {
        val mediaTitle = getMediaTitleFromUri(uri)
        val existing = playbackStateRepository.getVideoDataByTitle(mediaTitle)
        if (existing != null) {
            playbackStateRepository.upsert(existing.copy(aid = audioTrackIndex))
        }
    }

    override suspend fun updateMediumSubtitleTrack(uri: String, subtitleTrackIndex: Int) {
        val mediaTitle = getMediaTitleFromUri(uri)
        val existing = playbackStateRepository.getVideoDataByTitle(mediaTitle)
        if (existing != null) {
            playbackStateRepository.upsert(existing.copy(sid = subtitleTrackIndex))
        }
    }

    override suspend fun updateMediumZoom(uri: String, zoom: Float) {
        val mediaTitle = getMediaTitleFromUri(uri)
        val existing = playbackStateRepository.getVideoDataByTitle(mediaTitle)
        if (existing != null) {
            playbackStateRepository.upsert(existing.copy(videoZoom = zoom))
        }
    }

    override suspend fun addExternalSubtitleToMedium(uri: String, subtitleUri: Uri) {
        val mediaTitle = getMediaTitleFromUri(uri)
        val existing = playbackStateRepository.getVideoDataByTitle(mediaTitle)
        if (existing != null) {
            val subs = if (existing.externalSubtitles.isBlank()) {
                subtitleUri.toString()
            } else {
                existing.externalSubtitles + "," + subtitleUri.toString()
            }
            playbackStateRepository.upsert(existing.copy(externalSubtitles = subs))
        }
    }

    override suspend fun updateExternalSubs(uri: String, externalSubs: List<Uri>) {
        val mediaTitle = getMediaTitleFromUri(uri)
        val existing = playbackStateRepository.getVideoDataByTitle(mediaTitle)
        if (existing != null) {
            playbackStateRepository.upsert(existing.copy(externalSubtitles = externalSubs.joinToString(",") { it.toString() }))
        }
    }

    override suspend fun updateSubtitleDelay(uri: String, delay: Long) {
        val mediaTitle = getMediaTitleFromUri(uri)
        val existing = playbackStateRepository.getVideoDataByTitle(mediaTitle)
        if (existing != null) {
            playbackStateRepository.upsert(existing.copy(subDelay = delay.toInt()))
        }
    }

    override suspend fun updateSubtitleSpeed(uri: String, speed: Float) {
        val mediaTitle = getMediaTitleFromUri(uri)
        val existing = playbackStateRepository.getVideoDataByTitle(mediaTitle)
        if (existing != null) {
            playbackStateRepository.upsert(existing.copy(subSpeed = speed.toDouble()))
        }
    }

    override suspend fun moveVideosToRecycleBin(uris: List<String>) {}

    override suspend fun moveVideosToFolder(
        uris: List<String>,
        targetFolderPath: String,
        shouldCancel: () -> Boolean,
        onProgress: (Int) -> Unit
    ): MediaMoveSummary = MediaMoveSummary()

    override suspend fun moveFoldersToFolder(
        folderPaths: List<String>,
        targetFolderPath: String,
        shouldCancel: () -> Boolean,
        onProgress: (Int) -> Unit
    ): MediaMoveSummary = MediaMoveSummary()

    override suspend fun restoreVideosFromRecycleBin(uris: List<String>) {}

    private fun getMediaTitleFromUri(uriString: String): String {
        val uri = Uri.parse(uriString)
        return uri.lastPathSegment ?: uriString
    }

    private fun app.gyrolet.mpvrx.domain.media.model.Video.toExoVideo(): Video {
        return Video(
            id = this.id,
            path = this.path,
            parentPath = File(this.path).parent ?: "",
            duration = this.duration,
            uriString = this.uri.toString(),
            nameWithExtension = this.displayName,
            width = this.width,
            height = this.height,
            size = this.size
        )
    }

    private fun PlaybackStateEntity.toExoVideoState(uri: String): VideoState {
        return VideoState(
            path = uri,
            position = this.lastPosition * 1000L,
            audioTrackIndex = if (this.aid >= 0) this.aid else null,
            subtitleTrackIndex = if (this.sid >= 0) this.sid else null,
            playbackSpeed = this.playbackSpeed.toFloat(),
            externalSubs = if (this.externalSubtitles.isBlank()) emptyList() else this.externalSubtitles.split(",").map { it.toUri() },
            videoScale = this.videoZoom,
            subtitleDelayMilliseconds = this.subDelay.toLong(),
            subtitleSpeed = this.subSpeed.toFloat()
        )
    }
}
