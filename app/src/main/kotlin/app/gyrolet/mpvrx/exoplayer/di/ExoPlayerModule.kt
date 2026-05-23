package app.gyrolet.mpvrx.exoplayer.di

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.gyrolet.mpvrx.exoplayer.core.data.repository.LocalMediaRepositoryImpl
import app.gyrolet.mpvrx.exoplayer.core.data.repository.LocalPreferencesRepository
import app.gyrolet.mpvrx.exoplayer.core.data.repository.MediaRepository
import app.gyrolet.mpvrx.exoplayer.core.data.repository.PreferencesRepository
import app.gyrolet.mpvrx.exoplayer.core.data.repository.SubtitleFontRepository
import app.gyrolet.mpvrx.exoplayer.core.data.repository.LocalSubtitleFontRepository
import app.gyrolet.mpvrx.exoplayer.core.data.repository.SubtitleFontFileValidator
import app.gyrolet.mpvrx.exoplayer.core.datastore.datasource.AppPreferencesDataSource
import app.gyrolet.mpvrx.exoplayer.core.datastore.datasource.PlayerPreferencesDataSource
import app.gyrolet.mpvrx.exoplayer.core.datastore.serializer.ApplicationPreferencesSerializer
import app.gyrolet.mpvrx.exoplayer.core.datastore.serializer.PlayerPreferencesSerializer
import app.gyrolet.mpvrx.exoplayer.core.datastore.serializer.SearchHistorySerializer
import app.gyrolet.mpvrx.exoplayer.core.domain.GetSortedPlaylistUseCase
import app.gyrolet.mpvrx.exoplayer.core.domain.GetSortedVideosUseCase
import app.gyrolet.mpvrx.exoplayer.feature.player.PlayerViewModel
import app.gyrolet.mpvrx.exoplayer.core.data.repository.OnlineSubtitleRepository
import app.gyrolet.mpvrx.exoplayer.MediaSynchronizer
import app.gyrolet.mpvrx.exoplayer.core.datastore.datasource.SearchHistoryDataSource
import app.gyrolet.mpvrx.exoplayer.core.data.repository.SearchHistoryRepository
import app.gyrolet.mpvrx.exoplayer.core.data.repository.LocalSearchHistoryRepository
import app.gyrolet.mpvrx.exoplayer.core.model.ApplicationPreferences
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences
import app.gyrolet.mpvrx.exoplayer.core.model.SearchHistory
import app.gyrolet.mpvrx.exoplayer.settings.screens.medialibrary.MediaLibraryPreferencesViewModel
import app.gyrolet.mpvrx.exoplayer.settings.screens.player.PlayerPreferencesViewModel
import app.gyrolet.mpvrx.exoplayer.settings.screens.gesture.GesturePreferencesViewModel
import app.gyrolet.mpvrx.exoplayer.settings.screens.decoder.DecoderPreferencesViewModel
import app.gyrolet.mpvrx.exoplayer.settings.screens.audio.AudioPreferencesViewModel
import app.gyrolet.mpvrx.exoplayer.settings.screens.subtitle.SubtitlePreferencesViewModel
import app.gyrolet.mpvrx.exoplayer.settings.screens.general.GeneralPreferencesViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

private const val APP_PREFERENCES_DATASTORE_FILE = "exo_app_preferences.json"
private const val PLAYER_PREFERENCES_DATASTORE_FILE = "exo_player_preferences.json"
private const val SEARCH_HISTORY_DATASTORE_FILE = "exo_search_history.json"

val exoPlayerModule = module {
    single { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    single(named("ioDispatcher")) { Dispatchers.IO }
    single(named("defaultDispatcher")) { Dispatchers.Default }

    single<DataStore<ApplicationPreferences>> {
        DataStoreFactory.create(
            serializer = ApplicationPreferencesSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { ApplicationPreferences() },
            scope = CoroutineScope(get<CoroutineScope>().coroutineContext + get<CoroutineDispatcher>(named("ioDispatcher"))),
            produceFile = { androidContext().dataStoreFile(APP_PREFERENCES_DATASTORE_FILE) },
        )
    }

    single<DataStore<PlayerPreferences>> {
        DataStoreFactory.create(
            serializer = PlayerPreferencesSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { PlayerPreferences() },
            scope = CoroutineScope(get<CoroutineScope>().coroutineContext + get<CoroutineDispatcher>(named("ioDispatcher"))),
            produceFile = { androidContext().dataStoreFile(PLAYER_PREFERENCES_DATASTORE_FILE) },
        )
    }

    single<DataStore<SearchHistory>> {
        DataStoreFactory.create(
            serializer = SearchHistorySerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { SearchHistory() },
            scope = CoroutineScope(get<CoroutineScope>().coroutineContext + get<CoroutineDispatcher>(named("ioDispatcher"))),
            produceFile = { androidContext().dataStoreFile(SEARCH_HISTORY_DATASTORE_FILE) },
        )
    }

    single { AppPreferencesDataSource(get()) }
    single { PlayerPreferencesDataSource(get()) }
    single { SearchHistoryDataSource(get()) }

    single<PreferencesRepository> {
        LocalPreferencesRepository(get(), get(), get())
    }

    single<MediaRepository> {
        LocalMediaRepositoryImpl(androidContext(), get())
    }

    single<SearchHistoryRepository> {
        LocalSearchHistoryRepository(get())
    }

    single { SubtitleFontFileValidator() }
    single<SubtitleFontRepository> { 
        LocalSubtitleFontRepository(androidContext(), get(), get(named("ioDispatcher")), get()) 
    }
    
    single { GetSortedVideosUseCase(get(), get(), get(named("defaultDispatcher"))) }
    single { GetSortedPlaylistUseCase(get(), get(), androidContext(), get(named("defaultDispatcher"))) }
    
    single { OnlineSubtitleRepository(androidContext()) }
    
    single<MediaSynchronizer> {
        object : MediaSynchronizer {
            override suspend fun refresh(path: String?): Boolean = true
            override suspend fun registerManualVideoPath(path: String) {}
            override fun startSync() {}
            override fun stopSync() {}
        }
    }

    viewModelOf(::PlayerViewModel)
    viewModelOf(::MediaLibraryPreferencesViewModel)
    viewModelOf(::PlayerPreferencesViewModel)
    viewModelOf(::GesturePreferencesViewModel)
    viewModelOf(::DecoderPreferencesViewModel)
    viewModelOf(::AudioPreferencesViewModel)
    viewModelOf(::SubtitlePreferencesViewModel)
    viewModelOf(::GeneralPreferencesViewModel)
}
