package app.gyrolet.mpvrx.exoplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SettingsBackup(
    val version: Int = CURRENT_VERSION,
    val applicationPreferences: ApplicationPreferences = ApplicationPreferences(),
    val playerPreferences: PlayerPreferences = PlayerPreferences(),
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

