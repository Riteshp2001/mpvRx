package app.gyrolet.mpvrx.exoplayer.core.data.repository

import kotlinx.coroutines.flow.Flow

interface SearchHistoryRepository {

    val searchHistory: Flow<List<String>>

    suspend fun addSearchQuery(query: String)

    suspend fun removeSearchQuery(query: String)

    suspend fun clearHistory()
}
