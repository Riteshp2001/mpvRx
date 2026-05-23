package app.gyrolet.mpvrx.exoplayer.feature.player.extensions

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

private const val MEDIA_METADATA_POSITION_KEY = "media_metadata_position"
private const val MEDIA_METADATA_PLAYBACK_SPEED_KEY = "media_metadata_playback_speed"
private const val MEDIA_METADATA_AUDIO_TRACK_INDEX_KEY = "audio_track_index"
private const val MEDIA_METADATA_SUBTITLE_TRACK_INDEX_KEY = "subtitle_track_index"
private const val MEDIA_METADATA_VIDEO_ZOOM_KEY = "media_metadata_video_zoom"
private const val MEDIA_METADATA_SUBTITLE_DELAY_KEY = "media_metadata_subtitle_delay"
private const val MEDIA_METADATA_SUBTITLE_SPEED_KEY = "media_metadata_subtitle_speed"
private const val MEDIA_METADATA_VIDEO_WIDTH_KEY = "media_metadata_video_width"
private const val MEDIA_METADATA_VIDEO_HEIGHT_KEY = "media_metadata_video_height"
private const val MEDIA_METADATA_VIDEO_ROTATION_KEY = "media_metadata_video_rotation"
private const val MEDIA_METADATA_HAS_RENDERED_FIRST_FRAME_KEY = "media_metadata_has_rendered_first_frame"
private const val MEDIA_METADATA_APPROXIMATE_SEEK_ENABLED_KEY = "media_metadata_approximate_seek_enabled"
private const val MEDIA_METADATA_VIDEO_EFFECTS_AVAILABLE_KEY = "media_metadata_video_effects_available"
private const val MEDIA_METADATA_REQUEST_HEADERS_PREFIX = "media_metadata_request_header_"
private const val MEDIA_METADATA_REMOTE_SERVER_ID_KEY = "media_metadata_remote_server_id"
private const val MEDIA_METADATA_REMOTE_FILE_PATH_KEY = "media_metadata_remote_file_path"
private const val MEDIA_METADATA_REMOTE_PROTOCOL_KEY = "media_metadata_remote_protocol"
private const val MEDIA_METADATA_LOCAL_PARENT_PATH_KEY = "media_metadata_local_parent_path"
private const val MEDIA_METADATA_REMOTE_DIRECTORY_PATH_KEY = "media_metadata_remote_directory_path"

internal fun Bundle.setExtras(
    positionMs: Long?,
    videoScale: Float?,
    playbackSpeed: Float?,
    audioTrackIndex: Int?,
    subtitleTrackIndex: Int?,
    subtitleDelayMilliseconds: Long? = null,
    subtitleSpeed: Float? = null,
    videoWidth: Int? = null,
    videoHeight: Int? = null,
    videoRotation: Int? = null,
    hasRenderedFirstFrame: Boolean? = null,
    isApproximateSeekEnabled: Boolean? = null,
    isVideoEffectsAvailable: Boolean? = null,
    remoteServerId: Long? = null,
    remoteFilePath: String? = null,
    remoteProtocol: String? = null,
    localParentPath: String? = null,
    remoteDirectoryPath: String? = null,
) = apply {
    positionMs?.let { putLong(MEDIA_METADATA_POSITION_KEY, it) }
    videoScale?.let { putFloat(MEDIA_METADATA_VIDEO_ZOOM_KEY, it) }
    playbackSpeed?.let { putFloat(MEDIA_METADATA_PLAYBACK_SPEED_KEY, it) }
    audioTrackIndex?.let { putInt(MEDIA_METADATA_AUDIO_TRACK_INDEX_KEY, it) }
    subtitleTrackIndex?.let { putInt(MEDIA_METADATA_SUBTITLE_TRACK_INDEX_KEY, it) }
    subtitleDelayMilliseconds?.let { putLong(MEDIA_METADATA_SUBTITLE_DELAY_KEY, it) }
    subtitleSpeed?.let { putFloat(MEDIA_METADATA_SUBTITLE_SPEED_KEY, it) }
    videoWidth?.let { putInt(MEDIA_METADATA_VIDEO_WIDTH_KEY, it) }
    videoHeight?.let { putInt(MEDIA_METADATA_VIDEO_HEIGHT_KEY, it) }
    videoRotation?.let { putInt(MEDIA_METADATA_VIDEO_ROTATION_KEY, it) }
    hasRenderedFirstFrame?.let { putBoolean(MEDIA_METADATA_HAS_RENDERED_FIRST_FRAME_KEY, it) }
    isApproximateSeekEnabled?.let { putBoolean(MEDIA_METADATA_APPROXIMATE_SEEK_ENABLED_KEY, it) }
    isVideoEffectsAvailable?.let { putBoolean(MEDIA_METADATA_VIDEO_EFFECTS_AVAILABLE_KEY, it) }
    remoteServerId?.let { putLong(MEDIA_METADATA_REMOTE_SERVER_ID_KEY, it) }
    remoteFilePath?.let { putString(MEDIA_METADATA_REMOTE_FILE_PATH_KEY, it) }
    remoteProtocol?.let { putString(MEDIA_METADATA_REMOTE_PROTOCOL_KEY, it) }
    localParentPath?.let { putString(MEDIA_METADATA_LOCAL_PARENT_PATH_KEY, it) }
    remoteDirectoryPath?.let { putString(MEDIA_METADATA_REMOTE_DIRECTORY_PATH_KEY, it) }
}

fun MediaMetadata.Builder.setMetadataExtras(
    positionMs: Long? = null,
    videoScale: Float? = null,
    playbackSpeed: Float? = null,
    audioTrackIndex: Int? = null,
    subtitleTrackIndex: Int? = null,
    subtitleDelayMilliseconds: Long? = null,
    subtitleSpeed: Float? = null,
    videoWidth: Int? = null,
    videoHeight: Int? = null,
    videoRotation: Int? = null,
    hasRenderedFirstFrame: Boolean? = null,
    isApproximateSeekEnabled: Boolean? = null,
    isVideoEffectsAvailable: Boolean? = null,
    requestHeaders: Map<String, String> = emptyMap(),
    remoteServerId: Long? = null,
    remoteFilePath: String? = null,
    remoteProtocol: String? = null,
    localParentPath: String? = null,
    remoteDirectoryPath: String? = null,
): MediaMetadata.Builder = setExtras(
    Bundle().setExtras(
        positionMs = positionMs,
        videoScale = videoScale,
        playbackSpeed = playbackSpeed,
        audioTrackIndex = audioTrackIndex,
        subtitleTrackIndex = subtitleTrackIndex,
        subtitleDelayMilliseconds = subtitleDelayMilliseconds,
        subtitleSpeed = subtitleSpeed,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        videoRotation = videoRotation,
        hasRenderedFirstFrame = hasRenderedFirstFrame,
        isApproximateSeekEnabled = isApproximateSeekEnabled,
        isVideoEffectsAvailable = isVideoEffectsAvailable,
        remoteServerId = remoteServerId,
        remoteFilePath = remoteFilePath,
        remoteProtocol = remoteProtocol,
        localParentPath = localParentPath,
        remoteDirectoryPath = remoteDirectoryPath,
    ).apply {
        requestHeaders.forEach { (key, value) ->
            putString("$MEDIA_METADATA_REQUEST_HEADERS_PREFIX$key", value)
        }
    },
)

val MediaMetadata.positionMs: Long?
    get() = extras?.run {
        getLong(MEDIA_METADATA_POSITION_KEY)
            .takeIf { containsKey(MEDIA_METADATA_POSITION_KEY) }
    }

val MediaMetadata.playbackSpeed: Float?
    get() = extras?.run {
        getFloat(MEDIA_METADATA_PLAYBACK_SPEED_KEY)
            .takeIf { containsKey(MEDIA_METADATA_PLAYBACK_SPEED_KEY) }
    }

val MediaMetadata.audioTrackIndex: Int?
    get() = extras?.run {
        getInt(MEDIA_METADATA_AUDIO_TRACK_INDEX_KEY)
            .takeIf { containsKey(MEDIA_METADATA_AUDIO_TRACK_INDEX_KEY) }
    }

val MediaMetadata.subtitleTrackIndex: Int?
    get() = extras?.run {
        getInt(MEDIA_METADATA_SUBTITLE_TRACK_INDEX_KEY)
            .takeIf { containsKey(MEDIA_METADATA_SUBTITLE_TRACK_INDEX_KEY) }
    }

val MediaMetadata.videoZoom: Float?
    get() = extras?.run {
        getFloat(MEDIA_METADATA_VIDEO_ZOOM_KEY)
            .takeIf { containsKey(MEDIA_METADATA_VIDEO_ZOOM_KEY) }
    }

val MediaMetadata.subtitleDelayMilliseconds: Long?
    get() = extras?.run {
        getLong(MEDIA_METADATA_SUBTITLE_DELAY_KEY)
            .takeIf { containsKey(MEDIA_METADATA_SUBTITLE_DELAY_KEY) }
    }

val MediaMetadata.subtitleSpeed: Float?
    get() = extras?.run {
        getFloat(MEDIA_METADATA_SUBTITLE_SPEED_KEY)
            .takeIf { containsKey(MEDIA_METADATA_SUBTITLE_SPEED_KEY) }
    }

val MediaMetadata.videoWidth: Int?
    get() = extras?.run {
        getInt(MEDIA_METADATA_VIDEO_WIDTH_KEY)
            .takeIf { containsKey(MEDIA_METADATA_VIDEO_WIDTH_KEY) }
    }

val MediaMetadata.videoHeight: Int?
    get() = extras?.run {
        getInt(MEDIA_METADATA_VIDEO_HEIGHT_KEY)
            .takeIf { containsKey(MEDIA_METADATA_VIDEO_HEIGHT_KEY) }
    }

val MediaMetadata.videoRotation: Int?
    get() = extras?.run {
        getInt(MEDIA_METADATA_VIDEO_ROTATION_KEY)
            .takeIf { containsKey(MEDIA_METADATA_VIDEO_ROTATION_KEY) }
    }

val MediaMetadata.hasRenderedFirstFrame: Boolean
    get() = extras?.getBoolean(MEDIA_METADATA_HAS_RENDERED_FIRST_FRAME_KEY, false) == true

val MediaMetadata.isApproximateSeekEnabled: Boolean
    get() = extras?.getBoolean(MEDIA_METADATA_APPROXIMATE_SEEK_ENABLED_KEY, false) == true

val MediaMetadata.isVideoEffectsAvailable: Boolean
    get() = extras?.getBoolean(MEDIA_METADATA_VIDEO_EFFECTS_AVAILABLE_KEY, true) != false

val MediaMetadata.requestHeaders: Map<String, String>
    get() = extras?.keySet()
        ?.filter { it.startsWith(MEDIA_METADATA_REQUEST_HEADERS_PREFIX) }
        ?.associate { key ->
            key.removePrefix(MEDIA_METADATA_REQUEST_HEADERS_PREFIX) to extras?.getString(key).orEmpty()
        }
        ?.filterValues { it.isNotEmpty() }
        ?: emptyMap()

val MediaMetadata.remoteServerId: Long?
    get() = extras?.run {
        getLong(MEDIA_METADATA_REMOTE_SERVER_ID_KEY)
            .takeIf { containsKey(MEDIA_METADATA_REMOTE_SERVER_ID_KEY) }
    }

val MediaMetadata.remoteFilePath: String?
    get() = extras?.getString(MEDIA_METADATA_REMOTE_FILE_PATH_KEY)
        ?.takeIf(String::isNotBlank)

val MediaMetadata.remoteProtocol: String?
    get() = extras?.getString(MEDIA_METADATA_REMOTE_PROTOCOL_KEY)
        ?.takeIf(String::isNotBlank)

val MediaMetadata.localParentPath: String?
    get() = extras?.getString(MEDIA_METADATA_LOCAL_PARENT_PATH_KEY)
        ?.takeIf(String::isNotBlank)

val MediaMetadata.remoteDirectoryPath: String?
    get() = extras?.getString(MEDIA_METADATA_REMOTE_DIRECTORY_PATH_KEY)
        ?.takeIf(String::isNotBlank)

fun MediaItem.copy(
    positionMs: Long? = this.mediaMetadata.positionMs,
    durationMs: Long? = this.mediaMetadata.durationMs,
    videoZoom: Float? = this.mediaMetadata.videoZoom,
    playbackSpeed: Float? = this.mediaMetadata.playbackSpeed,
    audioTrackIndex: Int? = this.mediaMetadata.audioTrackIndex,
    subtitleTrackIndex: Int? = this.mediaMetadata.subtitleTrackIndex,
    subtitleDelayMilliseconds: Long? = this.mediaMetadata.subtitleDelayMilliseconds,
    subtitleSpeed: Float? = this.mediaMetadata.subtitleSpeed,
    videoWidth: Int? = this.mediaMetadata.videoWidth,
    videoHeight: Int? = this.mediaMetadata.videoHeight,
    videoRotation: Int? = this.mediaMetadata.videoRotation,
    hasRenderedFirstFrame: Boolean? = this.mediaMetadata.hasRenderedFirstFrame,
    isApproximateSeekEnabled: Boolean? = this.mediaMetadata.isApproximateSeekEnabled,
    isVideoEffectsAvailable: Boolean? = this.mediaMetadata.isVideoEffectsAvailable,
    requestHeaders: Map<String, String> = this.mediaMetadata.requestHeaders,
    remoteServerId: Long? = this.mediaMetadata.remoteServerId,
    remoteFilePath: String? = this.mediaMetadata.remoteFilePath,
    remoteProtocol: String? = this.mediaMetadata.remoteProtocol,
    localParentPath: String? = this.mediaMetadata.localParentPath,
    remoteDirectoryPath: String? = this.mediaMetadata.remoteDirectoryPath,
): MediaItem = buildUpon().setMediaMetadata(
    mediaMetadata.buildUpon()
        .setMetadataExtras(
            positionMs = positionMs,
            videoScale = videoZoom,
            playbackSpeed = playbackSpeed,
            audioTrackIndex = audioTrackIndex,
            subtitleTrackIndex = subtitleTrackIndex,
            subtitleDelayMilliseconds = subtitleDelayMilliseconds,
            subtitleSpeed = subtitleSpeed,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            videoRotation = videoRotation,
            hasRenderedFirstFrame = hasRenderedFirstFrame,
            isApproximateSeekEnabled = isApproximateSeekEnabled,
            isVideoEffectsAvailable = isVideoEffectsAvailable,
            remoteServerId = remoteServerId,
            remoteFilePath = remoteFilePath,
            remoteProtocol = remoteProtocol,
            localParentPath = localParentPath,
            remoteDirectoryPath = remoteDirectoryPath,
        ).apply {
            requestHeaders.forEach { (key, value) ->
                // Builder doesn't have putString, we must use setExtras with a Bundle
                // This is already handled by setMetadataExtras above
            }
        }.build(),
).build()
