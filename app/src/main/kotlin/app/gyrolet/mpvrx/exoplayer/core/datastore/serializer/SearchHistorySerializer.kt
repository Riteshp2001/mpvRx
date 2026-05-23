package app.gyrolet.mpvrx.exoplayer.core.datastore.serializer

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import app.gyrolet.mpvrx.exoplayer.core.model.SearchHistory

object SearchHistorySerializer : Serializer<SearchHistory> {

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    override val defaultValue: SearchHistory
        get() = SearchHistory()

    override suspend fun readFrom(input: InputStream): SearchHistory {
        try {
            return jsonFormat.decodeFromString(
                deserializer = SearchHistory.serializer(),
                string = input.readBytes().decodeToString(),
            )
        } catch (exception: SerializationException) {
            throw CorruptionException("Cannot read datastore", exception)
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun writeTo(t: SearchHistory, output: OutputStream) {
        output.write(
            jsonFormat.encodeToString(
                serializer = SearchHistory.serializer(),
                value = t,
            ).encodeToByteArray(),
        )
    }
}
