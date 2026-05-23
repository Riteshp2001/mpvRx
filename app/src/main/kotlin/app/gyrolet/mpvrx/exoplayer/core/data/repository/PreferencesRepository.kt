package app.gyrolet.mpvrx.exoplayer.core.data.repository

import kotlinx.coroutines.flow.StateFlow
import app.gyrolet.mpvrx.exoplayer.core.model.ApplicationPreferences
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences
import app.gyrolet.mpvrx.exoplayer.core.model.SettingsBackup

interface PreferencesRepository {

    val applicationPreferences: StateFlow<ApplicationPreferences>

    val playerPreferences: StateFlow<PlayerPreferences>

    suspend fun updateApplicationPreferences(
        transform: suspend (ApplicationPreferences) -> ApplicationPreferences,
    )

    suspend fun updatePlayerPreferences(transform: suspend (PlayerPreferences) -> PlayerPreferences)

    suspend fun exportSettings(): SettingsBackup

    suspend fun importSettings(settingsBackup: SettingsBackup)

    suspend fun resetPreferences()
}
