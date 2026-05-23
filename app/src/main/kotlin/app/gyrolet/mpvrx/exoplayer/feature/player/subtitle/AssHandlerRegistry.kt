package app.gyrolet.mpvrx.exoplayer.feature.player.subtitle

import io.github.peerless2012.ass.media.AssHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AssHandlerRegistry {

    private val currentHandler = MutableStateFlow<AssHandler?>(null)

    val handler: StateFlow<AssHandler?> = currentHandler.asStateFlow()

    fun register(handler: AssHandler) {
        currentHandler.value = handler
    }

    fun unregister(handler: AssHandler) {
        if (currentHandler.value === handler) {
            currentHandler.value = null
        }
    }
}
