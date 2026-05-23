package app.gyrolet.mpvrx.exoplayer.core.data.repository

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

interface SubtitleFontRepository {
    val state: StateFlow<ExternalSubtitleFontState>

    val source: StateFlow<ExternalSubtitleFontSource?>

    suspend fun importFont(uri: Uri)

    suspend fun clearFont()
}

data class ExternalSubtitleFontState(
    val isAvailable: Boolean = false,
    val displayName: String = "",
)

data class ExternalSubtitleFontSource(
    val absolutePath: String,
)
