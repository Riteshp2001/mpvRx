package app.gyrolet.mpvrx.exoplayer.feature.player.extensions

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import app.gyrolet.mpvrx.exoplayer.core.common.Logger
import app.gyrolet.mpvrx.exoplayer.feature.player.service.preciseSeekTo
import app.gyrolet.mpvrx.exoplayer.feature.player.service.setMediaControllerIsScrubbingModeEnabled

fun Player.switchTrack(trackType: @C.TrackType Int, trackIndex: Int) {
    val trackTypeText = when (trackType) {
        C.TRACK_TYPE_AUDIO -> "audio"
        C.TRACK_TYPE_TEXT -> "subtitle"
        else -> throw IllegalArgumentException("Invalid track type: $trackType")
    }

    if (trackIndex < 0) {
        Logger.debug("Player", "Disabling $trackTypeText")
        trackSelectionParameters = trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(trackType, true)
            .build()
    } else {
        val tracks = currentTracks.groups.filter { it.type == trackType }

        if (tracks.isEmpty() || trackIndex >= tracks.size) {
            Logger.error("Player", "Operation failed: Invalid track index: $trackIndex")
            return
        }

        Logger.debug("Player", "Setting $trackTypeText track: $trackIndex")
        val selectedGroup = tracks[trackIndex]
        val format = selectedGroup.mediaTrackGroup.getFormat(0)
        Logger.debug(
            "Player",
            "Track format: mime=${format.sampleMimeType}, label=${format.label}, id=${format.id}",
        )
        val trackSelectionOverride = TrackSelectionOverride(tracks[trackIndex].mediaTrackGroup, 0)


        trackSelectionParameters = trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(trackSelectionOverride)
            .build()
    }
}

@UnstableApi
fun Player.getManuallySelectedTrackIndex(trackType: @C.TrackType Int): Int? {
    val isDisabled = trackSelectionParameters.disabledTrackTypes.contains(trackType)
    if (isDisabled) return -1

    val trackOverrides = trackSelectionParameters.overrides.values.map { it.mediaTrackGroup }
    val trackOverride = trackOverrides.firstOrNull { it.type == trackType } ?: return null
    val tracks = currentTracks.groups.filter { it.type == trackType }

    return tracks.indexOfFirst { it.mediaTrackGroup == trackOverride }.takeIf { it != -1 }
}

fun Player.addAdditionalSubtitleConfiguration(subtitle: MediaItem.SubtitleConfiguration) {
    val currentMediaItemLocal = currentMediaItem ?: return
    val existingSubConfigurations = currentMediaItemLocal.localConfiguration?.subtitleConfigurations ?: emptyList()

    if (existingSubConfigurations.any { it.id == subtitle.id }) {
        return
    }

    val updateMediaItem = currentMediaItemLocal
        .buildUpon()
        .setSubtitleConfigurations(existingSubConfigurations + listOf(subtitle))
        .build()

    val index = currentMediaItemIndex
    addMediaItem(index + 1, updateMediaItem)
    seekToDefaultPosition(index + 1)
    removeMediaItem(index)
}

fun Player.availableDurationMs(): Long {
    val playerDuration = duration
    if (playerDuration != C.TIME_UNSET && playerDuration > 0L) {
        return playerDuration
    }

    // currentMediaItem 的 metadata 和 player 顶层 metadata 可能不同步
    return currentMediaItem?.mediaMetadata?.durationMs?.takeIf { it > 0L }
        ?: mediaMetadata.durationMs?.takeIf { it > 0L }
        ?: C.TIME_UNSET
}

fun Player.canSeekCurrentMediaItem(): Boolean {
    if (availableDurationMs() == C.TIME_UNSET) return false

    return when (this) {
        is MediaController -> {
            isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) ||
                currentMediaItem?.mediaMetadata?.isApproximateSeekEnabled == true
        }

        else -> true
    }
}

fun Player.seekToRequestedPosition(positionMs: Long) {
    val duration = availableDurationMs()
    if (duration == C.TIME_UNSET) return

    val targetPosition = positionMs.coerceIn(0L, duration)
    if (this is MediaController) {
        // approximate source 的 seekTo 会从头顺序读取，必须走 preciseSeekTo 触发 source 升级
        if (currentMediaItem?.mediaMetadata?.isApproximateSeekEnabled == true) {
            preciseSeekTo(targetPosition)
            return
        }
    }

    seekTo(targetPosition)
}

fun Player.seekByRequestedOffset(offsetMs: Long) {
    val currentPosition = currentPosition.takeIf { it != C.TIME_UNSET } ?: 0L
    seekToRequestedPosition(currentPosition + offsetMs)
}

@OptIn(UnstableApi::class)
fun Player.setIsScrubbingModeEnabled(isEnabled: Boolean) {
    when (this) {
        is MediaController -> this.setMediaControllerIsScrubbingModeEnabled(isEnabled)
        is ExoPlayer -> this.isScrubbingModeEnabled = isEnabled
    }
}
