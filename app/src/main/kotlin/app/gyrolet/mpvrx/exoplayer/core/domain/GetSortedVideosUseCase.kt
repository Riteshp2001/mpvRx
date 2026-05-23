package app.gyrolet.mpvrx.exoplayer.core.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import app.gyrolet.mpvrx.exoplayer.core.data.repository.MediaRepository
import app.gyrolet.mpvrx.exoplayer.core.data.repository.PreferencesRepository
import app.gyrolet.mpvrx.exoplayer.core.model.Sort
import app.gyrolet.mpvrx.exoplayer.core.model.Video

class GetSortedVideosUseCase(
    private val mediaRepository: MediaRepository,
    private val preferencesRepository: PreferencesRepository,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    operator fun invoke(
        folderPath: String? = null,
        isRecycleBinOnly: Boolean = false,
    ): Flow<List<Video>> {
        val videosFlow = if (isRecycleBinOnly) {
            mediaRepository.getRecycleBinVideosFlow()
        } else if (folderPath != null) {
            mediaRepository.getVideosFlowFromFolderPath(folderPath)
        } else {
            mediaRepository.getVideosFlow()
        }

        return combine(
            videosFlow,
            preferencesRepository.applicationPreferences,
        ) { videoItems, preferences ->
            val visibleVideos = videoItems.filterNot { video ->
                (!isRecycleBinOnly && preferences.isPathExcluded(video.parentPath)) ||
                    (!isRecycleBinOnly && preferences.isRecycleBinEnabled && video.isInRecycleBin)
            }

            val sort = Sort(by = preferences.sortBy, order = preferences.sortOrder)
            visibleVideos.sortedWith(sort.videoComparator())
        }.flowOn(defaultDispatcher)
    }
}
