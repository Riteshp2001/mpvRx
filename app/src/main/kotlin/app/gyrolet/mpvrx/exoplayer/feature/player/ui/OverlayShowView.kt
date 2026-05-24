package app.gyrolet.mpvrx.exoplayer.feature.player.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import app.gyrolet.mpvrx.exoplayer.core.model.DecoderPriority
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences
import app.gyrolet.mpvrx.exoplayer.core.model.VideoContentScale
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.noRippleClickable
import app.gyrolet.mpvrx.exoplayer.feature.player.state.SleepTimerState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.SubtitleOptionsEvent
import app.gyrolet.mpvrx.exoplayer.core.ui.components.VideoFiltersPanel

@Composable
fun BoxScope.OverlayShowView(
    player: Player,
    overlayView: OverlayView?,
    videoContentScale: VideoContentScale,
    playerPreferences: PlayerPreferences,
    sleepTimerState: SleepTimerState,
    onDismiss: () -> Unit = {},
    onSelectSubtitleClick: () -> Unit = {},
    onAddOnlineSubtitleClick: (String) -> Unit = {},
    onSubtitleOptionEvent: (SubtitleOptionsEvent) -> Unit = {},
    onSubtitleStyleChanged: (PlayerPreferences) -> Unit = {},
    onVideoContentScaleChanged: (VideoContentScale) -> Unit = {},
    onPreviewVideoFilters: (PlayerPreferences) -> Unit = {},
    onConfirmVideoFilters: (PlayerPreferences) -> Unit = {},
    onCloseVideoFilters: () -> Unit = {},
    onShowVideoFilters: () -> Unit = {},
    onDecoderPriorityChanged: (DecoderPriority) -> Unit = {},
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .then(
                if (overlayView != null) {
                    Modifier.noRippleClickable(onClick = onDismiss)
                } else {
                    Modifier
                },
            ),
    )

    AudioTrackSelectorView(
        shouldShow = overlayView == OverlayView.AUDIO_SELECTOR,
        player = player,
        onDismiss = onDismiss,
    )

    SubtitleSelectorView(
        shouldShow = overlayView == OverlayView.SUBTITLE_SELECTOR,
        player = player,
        onSelectSubtitleClick = onSelectSubtitleClick,
        onAddOnlineSubtitleClick = onAddOnlineSubtitleClick,
        preferences = playerPreferences,
        onPreferencesChange = onSubtitleStyleChanged,
        onEvent = onSubtitleOptionEvent,
        onDismiss = onDismiss,
    )

    PlaybackSpeedSelectorView(
        shouldShow = overlayView == OverlayView.PLAYBACK_SPEED,
        player = player,
    )

    VideoContentScaleSelectorView(
        shouldShow = overlayView == OverlayView.VIDEO_CONTENT_SCALE,
        videoContentScale = videoContentScale,
        onVideoContentScaleChanged = onVideoContentScaleChanged,
        onShowVideoFilters = onShowVideoFilters,
        onDismiss = onDismiss,
    )

    VideoFilterOverlayView(
        shouldShow = overlayView == OverlayView.VIDEO_FILTERS,
        preferences = playerPreferences,
        onDismissRequest = onCloseVideoFilters,
        onPreviewPreferences = onPreviewVideoFilters,
        onConfirmPreferences = onConfirmVideoFilters,
    )

    PlaylistView(
        shouldShow = overlayView == OverlayView.PLAYLIST,
        player = player,
    )

    SleepTimerSelectorView(
        shouldShow = overlayView == OverlayView.SLEEP_TIMER,
        sleepTimerState = sleepTimerState,
        onDismiss = onDismiss,
    )

    DecoderPrioritySelectorView(
        shouldShow = overlayView == OverlayView.DECODER_PRIORITY,
        currentDecoderPriority = playerPreferences.decoderPriority,
        onDecoderPriorityClick = onDecoderPriorityChanged,
        onDismiss = onDismiss,
    )
}

@Composable
private fun BoxScope.VideoFilterOverlayView(
    shouldShow: Boolean,
    preferences: PlayerPreferences,
    onDismissRequest: () -> Unit,
    onPreviewPreferences: (PlayerPreferences) -> Unit,
    onConfirmPreferences: (PlayerPreferences) -> Unit,
) {
    OverlayView(
        shouldShow = shouldShow,
        title = stringResource(R.string.video_filters),
        testTag = "panel_video_filters",
    ) {
        VideoFiltersPanel(
            preferences = preferences,
            onDismissRequest = onDismissRequest,
            onPreviewPreferences = onPreviewPreferences,
            onConfirmPreferences = onConfirmPreferences,
        )
    }
}

enum class OverlayView {
    AUDIO_SELECTOR,
    SUBTITLE_SELECTOR,
    PLAYBACK_SPEED,
    VIDEO_CONTENT_SCALE,
    VIDEO_FILTERS,
    PLAYLIST,
    SLEEP_TIMER,
    DECODER_PRIORITY,
}
