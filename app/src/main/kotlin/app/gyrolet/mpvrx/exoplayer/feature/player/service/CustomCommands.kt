package app.gyrolet.mpvrx.exoplayer.feature.player.service

import android.net.Uri
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import kotlinx.coroutines.guava.await
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences

enum class CustomCommands(val customAction: String) {
    ADD_SUBTITLE_TRACK(customAction = "ADD_SUBTITLE_TRACK"),
    PRECISE_SEEK_TO(customAction = "PRECISE_SEEK_TO"),
    SET_SKIP_SILENCE_ENABLED(customAction = "SET_SKIP_SILENCE_ENABLED"),
    GET_SKIP_SILENCE_ENABLED(customAction = "GET_SKIP_SILENCE_ENABLED"),
    SET_IS_SCRUBBING_MODE_ENABLED(customAction = "SET_IS_SCRUBBING_MODE_ENABLED"),
    SET_PERSISTENT_PLAYBACK_SPEED(customAction = "SET_PERSISTENT_PLAYBACK_SPEED"),
    SET_TRANSIENT_PLAYBACK_SPEED(customAction = "SET_TRANSIENT_PLAYBACK_SPEED"),
    GET_SUBTITLE_DELAY(customAction = "GET_SUBTITLE_DELAY"),
    SET_SUBTITLE_DELAY(customAction = "SET_SUBTITLE_DELAY"),
    GET_SUBTITLE_SPEED(customAction = "GET_SUBTITLE_SPEED"),
    SET_SUBTITLE_SPEED(customAction = "SET_SUBTITLE_SPEED"),
    STOP_PLAYER_SESSION(customAction = "STOP_PLAYER_SESSION"),
    IS_LOUDNESS_GAIN_SUPPORTED(customAction = "IS_LOUDNESS_GAIN_SUPPORTED"),
    SET_LOUDNESS_GAIN(customAction = "SET_LOUDNESS_GAIN"),
    GET_LOUDNESS_GAIN(customAction = "GET_LOUDNESS_GAIN"),
    PREVIEW_VIDEO_FILTERS(customAction = "PREVIEW_VIDEO_FILTERS"),
    SET_SCREEN_ASPECT_RATIO(customAction = "SET_SCREEN_ASPECT_RATIO"),
    ;

    val sessionCommand = SessionCommand(customAction, Bundle.EMPTY)

    companion object {
        fun fromSessionCommand(sessionCommand: SessionCommand): CustomCommands? = entries.find { it.customAction == sessionCommand.customAction }

        fun asSessionCommands(): List<SessionCommand> = entries.map { it.sessionCommand }

        const val SUBTITLE_TRACK_URI_KEY = "subtitle_track_uri"
        const val SEEK_POSITION_MS_KEY = "seek_position_ms"
        const val SKIP_SILENCE_ENABLED_KEY = "skip_silence_enabled"
        const val IS_SCRUBBING_MODE_ENABLED_KEY = "is_scrubbing_mode_enabled"
        const val PLAYBACK_SPEED_KEY = "playback_speed"
        const val SUBTITLE_DELAY_KEY = "subtitle_delay"
        const val SUBTITLE_SPEED_KEY = "subtitle_speed"
        const val LOUDNESS_GAIN_KEY = "loudness_gain"
        const val IS_LOUDNESS_GAIN_SUPPORTED_KEY = "is_loudness_gain_supported"
        const val SHOULD_APPLY_VIDEO_FILTERS_KEY = "should_apply_video_filters"
        const val IS_VIDEO_BRIGHTNESS_FILTER_ENABLED_KEY = "is_video_brightness_filter_enabled"
        const val VIDEO_BRIGHTNESS_KEY = "video_brightness"
        const val IS_VIDEO_CONTRAST_FILTER_ENABLED_KEY = "is_video_contrast_filter_enabled"
        const val VIDEO_CONTRAST_KEY = "video_contrast"
        const val IS_VIDEO_SATURATION_FILTER_ENABLED_KEY = "is_video_saturation_filter_enabled"
        const val VIDEO_SATURATION_KEY = "video_saturation"
        const val IS_VIDEO_HUE_FILTER_ENABLED_KEY = "is_video_hue_filter_enabled"
        const val VIDEO_HUE_KEY = "video_hue"
        const val IS_VIDEO_GAMMA_FILTER_ENABLED_KEY = "is_video_gamma_filter_enabled"
        const val VIDEO_GAMMA_KEY = "video_gamma"
        const val IS_VIDEO_SHARPENING_FILTER_ENABLED_KEY = "is_video_sharpening_filter_enabled"
        const val VIDEO_SHARPENING_KEY = "video_sharpening"
        const val SCREEN_ASPECT_RATIO_KEY = "screen_aspect_ratio"
        const val IS_AMBIENCE_MODE_ENABLED_KEY = "is_ambience_mode_enabled"
    }
}

fun MediaController.addSubtitleTrack(uri: Uri) {
    val args = Bundle().apply {
        putString(CustomCommands.SUBTITLE_TRACK_URI_KEY, uri.toString())
    }
    sendCustomCommand(CustomCommands.ADD_SUBTITLE_TRACK.sessionCommand, args)
}

fun MediaController.preciseSeekTo(positionMs: Long) {
    val args = Bundle().apply {
        putLong(CustomCommands.SEEK_POSITION_MS_KEY, positionMs)
    }
    sendCustomCommand(CustomCommands.PRECISE_SEEK_TO.sessionCommand, args)
}

suspend fun MediaController.setSkipSilenceEnabled(isEnabled: Boolean) {
    val args = Bundle().apply {
        putBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY, isEnabled)
    }
    sendCustomCommand(CustomCommands.SET_SKIP_SILENCE_ENABLED.sessionCommand, args).await()
}

fun MediaController.setMediaControllerIsScrubbingModeEnabled(isEnabled: Boolean) {
    val args = Bundle().apply {
        putBoolean(CustomCommands.IS_SCRUBBING_MODE_ENABLED_KEY, isEnabled)
    }
    sendCustomCommand(CustomCommands.SET_IS_SCRUBBING_MODE_ENABLED.sessionCommand, args)
}

fun MediaController.setPersistentPlaybackSpeed(speed: Float) {
    val args = Bundle().apply {
        putFloat(CustomCommands.PLAYBACK_SPEED_KEY, speed)
    }
    sendCustomCommand(CustomCommands.SET_PERSISTENT_PLAYBACK_SPEED.sessionCommand, args)
}

fun MediaController.setTransientPlaybackSpeed(speed: Float) {
    val args = Bundle().apply {
        putFloat(CustomCommands.PLAYBACK_SPEED_KEY, speed)
    }
    sendCustomCommand(CustomCommands.SET_TRANSIENT_PLAYBACK_SPEED.sessionCommand, args)
}

suspend fun MediaController.isSkipSilenceEnabled(): Boolean {
    val result = sendCustomCommand(CustomCommands.GET_SKIP_SILENCE_ENABLED.sessionCommand, Bundle.EMPTY)
    return result.await().extras.getBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY, false)
}

fun MediaController.setSubtitleDelayMilliseconds(delayMillis: Long) {
    val args = Bundle().apply {
        putLong(CustomCommands.SUBTITLE_DELAY_KEY, delayMillis)
    }
    sendCustomCommand(CustomCommands.SET_SUBTITLE_DELAY.sessionCommand, args)
}

suspend fun MediaController.getSubtitleDelayMilliseconds(): Long {
    val result = sendCustomCommand(CustomCommands.GET_SUBTITLE_DELAY.sessionCommand, Bundle.EMPTY)
    return result.await().extras.getLong(CustomCommands.SUBTITLE_DELAY_KEY, 0L)
}

fun MediaController.setSubtitleSpeed(speed: Float) {
    val args = Bundle().apply {
        putFloat(CustomCommands.SUBTITLE_SPEED_KEY, speed)
    }
    sendCustomCommand(CustomCommands.SET_SUBTITLE_SPEED.sessionCommand, args)
}

suspend fun MediaController.getSubtitleSpeed(): Float {
    val result = sendCustomCommand(CustomCommands.GET_SUBTITLE_SPEED.sessionCommand, Bundle.EMPTY)
    return result.await().extras.getFloat(CustomCommands.SUBTITLE_SPEED_KEY, 1f)
}

fun MediaController.stopPlayerSession() {
    sendCustomCommand(CustomCommands.STOP_PLAYER_SESSION.sessionCommand, Bundle.EMPTY)
}

fun MediaController.setLoudnessGain(gain: Int) {
    val args = Bundle().apply {
        putInt(CustomCommands.LOUDNESS_GAIN_KEY, gain)
    }
    sendCustomCommand(CustomCommands.SET_LOUDNESS_GAIN.sessionCommand, args)
}

fun MediaController.previewVideoFilters(preferences: PlayerPreferences) {
    val args = Bundle().apply {
        putBoolean(CustomCommands.SHOULD_APPLY_VIDEO_FILTERS_KEY, preferences.shouldApplyVideoFilters)
        putBoolean(CustomCommands.IS_AMBIENCE_MODE_ENABLED_KEY, preferences.isAmbienceModeEnabled)
        putBoolean(CustomCommands.IS_VIDEO_BRIGHTNESS_FILTER_ENABLED_KEY, preferences.isVideoBrightnessFilterEnabled)
        putFloat(CustomCommands.VIDEO_BRIGHTNESS_KEY, preferences.videoBrightness)
        putBoolean(CustomCommands.IS_VIDEO_CONTRAST_FILTER_ENABLED_KEY, preferences.isVideoContrastFilterEnabled)
        putFloat(CustomCommands.VIDEO_CONTRAST_KEY, preferences.videoContrast)
        putBoolean(CustomCommands.IS_VIDEO_SATURATION_FILTER_ENABLED_KEY, preferences.isVideoSaturationFilterEnabled)
        putFloat(CustomCommands.VIDEO_SATURATION_KEY, preferences.videoSaturation)
        putBoolean(CustomCommands.IS_VIDEO_HUE_FILTER_ENABLED_KEY, preferences.isVideoHueFilterEnabled)
        putFloat(CustomCommands.VIDEO_HUE_KEY, preferences.videoHue)
        putBoolean(CustomCommands.IS_VIDEO_GAMMA_FILTER_ENABLED_KEY, preferences.isVideoGammaFilterEnabled)
        putFloat(CustomCommands.VIDEO_GAMMA_KEY, preferences.videoGamma)
        putBoolean(CustomCommands.IS_VIDEO_SHARPENING_FILTER_ENABLED_KEY, preferences.isVideoSharpeningFilterEnabled)
        putFloat(CustomCommands.VIDEO_SHARPENING_KEY, preferences.videoSharpening)
    }
    sendCustomCommand(CustomCommands.PREVIEW_VIDEO_FILTERS.sessionCommand, args)
}

fun MediaController.setScreenAspectRatio(aspectRatio: Float) {
    val args = Bundle().apply {
        putFloat(CustomCommands.SCREEN_ASPECT_RATIO_KEY, aspectRatio)
    }
    sendCustomCommand(CustomCommands.SET_SCREEN_ASPECT_RATIO.sessionCommand, args)
}

suspend fun MediaController.getLoudnessGain(): Int {
    val result = sendCustomCommand(CustomCommands.GET_LOUDNESS_GAIN.sessionCommand, Bundle.EMPTY)
    return result.await().extras.getInt(CustomCommands.LOUDNESS_GAIN_KEY, 0)
}

suspend fun MediaController.isLoudnessGainSupported(): Boolean {
    val result = sendCustomCommand(CustomCommands.IS_LOUDNESS_GAIN_SUPPORTED.sessionCommand, Bundle.EMPTY)
    return result.await().extras.getBoolean(CustomCommands.IS_LOUDNESS_GAIN_SUPPORTED_KEY, false)
}
