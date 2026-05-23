package app.gyrolet.mpvrx.exoplayer.feature.player.extensions

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.MappingTrackSelector

@UnstableApi
fun MappingTrackSelector.MappedTrackInfo.isRendererAvailable(
    type: @C.TrackType Int,
): Boolean {
    for (i in 0 until rendererCount) {
        if (getTrackGroups(i).length == 0) continue
        if (type == getRendererType(i)) return true
    }
    return false
}
