package app.gyrolet.mpvrx.exoplayer.core.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.excludeNoMediaPaths
import app.gyrolet.mpvrx.exoplayer.core.common.hasManageExternalStorageAccess
import app.gyrolet.mpvrx.exoplayer.core.datastore.datasource.AppPreferencesDataSource
import app.gyrolet.mpvrx.exoplayer.core.datastore.datasource.PlayerPreferencesDataSource
import app.gyrolet.mpvrx.exoplayer.core.model.ApplicationPreferences
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences
import app.gyrolet.mpvrx.exoplayer.core.model.SettingsBackup

class LocalPreferencesRepository(
    private val appPreferencesDataSource: AppPreferencesDataSource,
    private val playerPreferencesDataSource: PlayerPreferencesDataSource,
    private val applicationScope: CoroutineScope,
) : PreferencesRepository {

    override val applicationPreferences: StateFlow<ApplicationPreferences> =
        appPreferencesDataSource.preferences
            .map(::sanitizeApplicationPreferences)
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = ApplicationPreferences(),
            )

    override val playerPreferences: StateFlow<PlayerPreferences> =
        playerPreferencesDataSource.preferences.stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = PlayerPreferences(),
        )

    override suspend fun updateApplicationPreferences(
        transform: suspend (ApplicationPreferences) -> ApplicationPreferences,
    ) {
        appPreferencesDataSource.update { currentPreferences ->
            val sanitizedCurrentPreferences = sanitizeApplicationPreferences(currentPreferences)
            sanitizeApplicationPreferences(transform(sanitizedCurrentPreferences))
        }
    }

    override suspend fun updatePlayerPreferences(
        transform: suspend (PlayerPreferences) -> PlayerPreferences,
    ) {
        playerPreferencesDataSource.update(transform)
    }

    override suspend fun exportSettings(): SettingsBackup = SettingsBackup(
        applicationPreferences = applicationPreferences.value,
        playerPreferences = playerPreferences.value,
    )

    override suspend fun importSettings(settingsBackup: SettingsBackup) {
        appPreferencesDataSource.update {
            sanitizeApplicationPreferences(settingsBackup.applicationPreferences)
        }
        playerPreferencesDataSource.update { settingsBackup.playerPreferences }
    }

    override suspend fun resetPreferences() {
        appPreferencesDataSource.update { ApplicationPreferences() }
        playerPreferencesDataSource.update { PlayerPreferences() }
    }

    private fun sanitizeApplicationPreferences(preferences: ApplicationPreferences): ApplicationPreferences {
        val sanitizedPreferences = if (!hasManageExternalStorageAccess()) {
            if (!preferences.shouldIgnoreNoMediaFiles && !preferences.isRecycleBinEnabled) {
                preferences
            } else {
                preferences.copy(
                    shouldIgnoreNoMediaFiles = false,
                    isRecycleBinEnabled = false,
                )
            }
        } else {
            preferences
        }
        if (sanitizedPreferences.shouldIgnoreNoMediaFiles) {
            return sanitizedPreferences
        }

        val visibleManualPaths = sanitizedPreferences.manualVideoPaths.excludeNoMediaPaths()
        val visiblePendingPaths = sanitizedPreferences.pendingExternalVideoPaths.excludeNoMediaPaths()
        if (visibleManualPaths.size == sanitizedPreferences.manualVideoPaths.size &&
            visiblePendingPaths.size == sanitizedPreferences.pendingExternalVideoPaths.size
        ) {
            return sanitizedPreferences
        }

        return sanitizedPreferences.copy(
            manualVideoPaths = visibleManualPaths,
            pendingExternalVideoPaths = visiblePendingPaths,
        )
    }
}
