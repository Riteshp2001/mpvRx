package app.gyrolet.mpvrx.exoplayer.feature.player.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.listen
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.isVideoEffectsAvailable

@Composable
fun rememberMetadataState(player: Player): MetadataState {
    val metadataState = remember { MetadataState(player) }
    LaunchedEffect(player) { metadataState.observe() }
    return metadataState
}

@Stable
class MetadataState(private val player: Player) {
    var title: String? by mutableStateOf(null)
        private set

    var artworkData: ByteArray? by mutableStateOf(null)
        private set

    var isVideoEffectsAvailable: Boolean by mutableStateOf(true)
        private set

    suspend fun observe() {
        updateFromPlayer()
        player.listen { events ->
            if (events.containsAny(
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_TIMELINE_CHANGED,
                )
            ) {
                updateFromPlayer()
            }
        }
    }

    private fun updateFromPlayer() {
        title = player.mediaMetadata.title?.toString()
        artworkData = player.mediaMetadata.artworkData
        isVideoEffectsAvailable = player.mediaMetadata.isVideoEffectsAvailable
    }
}
