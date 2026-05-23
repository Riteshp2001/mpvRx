package app.gyrolet.mpvrx.exoplayer.core.datastore.serializer

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import app.gyrolet.mpvrx.exoplayer.core.model.ApplicationPreferences

object ApplicationPreferencesSerializer : Serializer<ApplicationPreferences> {

    private val jsonFormat = Json { ignoreUnknownKeys = true }
    private val legacyKeys = setOf(
        "ignoreNoMediaFiles",
        "markLastPlayedMedia",
        "showDurationField",
        "showExtensionField",
        "showPathField",
        "showPlayedProgress",
        "showResolutionField",
        "showSizeField",
        "showThumbnailField",
        "useDynamicColors",
    )

    override val defaultValue: ApplicationPreferences
        get() = ApplicationPreferences()

    override suspend fun readFrom(input: InputStream): ApplicationPreferences {
        val serializedPreferences = input.readBytes().decodeToString()

        if (serializedPreferences.containsLegacyApplicationPreferences()) {
            throw CorruptionException(
                message = "Cannot read datastore",
                cause = IllegalStateException("Legacy application preferences format is unsupported"),
            )
        }

        try {
            return jsonFormat.decodeFromString(
                deserializer = ApplicationPreferences.serializer(),
                string = serializedPreferences,
            )
        } catch (exception: SerializationException) {
            throw CorruptionException("Cannot read datastore", exception)
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun writeTo(t: ApplicationPreferences, output: OutputStream) {
        output.write(
            jsonFormat.encodeToString(
                serializer = ApplicationPreferences.serializer(),
                value = t,
            ).encodeToByteArray(),
        )
    }

    private fun String.containsLegacyApplicationPreferences(): Boolean {
        val root = runCatching { jsonFormat.parseToJsonElement(this).jsonObject }.getOrNull() ?: return false
        return root.keys.any(legacyKeys::contains)
    }
}
