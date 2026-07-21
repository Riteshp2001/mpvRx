package app.gyrolet.mpvrx.domain.syncplay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SyncplayMessage(
    @SerialName("Hello") val hello: HelloMessage? = null,
    @SerialName("Set") val set: SetMessage? = null,
    @SerialName("State") val state: StateMessage? = null,
    @SerialName("Error") val error: ErrorMessage? = null,
    @SerialName("List") val list: Map<String, Map<String, SyncplayListUser>>? = null,
    @SerialName("Chat") val chat: ChatMessage? = null
)

@Serializable
data class HelloMessage(
    val username: String? = null,
    val room: Room? = null,
    val version: String? = null,
    val realversion: String? = null,
    val password: String? = null,
    val motd: String? = null,
    val features: JsonElement? = null
)

@Serializable
data class Room(
    val name: String
)

@Serializable
data class StateMessage(
    val ping: Ping? = null,
    val playstate: Playstate? = null,
    val ignoringOnTheFly: Map<String, Int>? = null
)

@Serializable
data class Playstate(
    val paused: Boolean? = null,
    val position: Double? = null,
    val doSeek: Boolean? = null,
    val setBy: String? = null
)

@Serializable
data class Ping(
    val clientLatencyCalculation: Double? = null,
    val clientRtt: Double? = null,
    val serverRtt: Double? = null,
    val latencyCalculation: Double? = null,
    val yourLatency: Double? = null,
    val senderLatency: Double? = null
)

@Serializable
data class SetMessage(
    val user: Map<String, UserEvent>? = null,
    val file: SyncplayFile? = null
)

@Serializable
data class UserEvent(
    val room: Room? = null,
    val event: Event? = null,
    val file: SyncplayFile? = null,
    val controller: Boolean? = null,
    val isReady: Boolean? = null,
    val features: JsonElement? = null
)

@Serializable
data class Event(
    val joined: Boolean? = null,
    val left: Boolean? = null
)

@Serializable
data class SyncplayFile(
    val duration: Double? = null,
    val name: String? = null,
    val size: JsonElement? = null
)

@Serializable
data class ErrorMessage(
    val message: String
)

@Serializable
data class SyncplayListUser(
    val position: Double? = null,
    val file: SyncplayFile? = null,
    val controller: Boolean? = null,
    val isReady: Boolean? = null,
    val features: JsonElement? = null
)

@Serializable
data class ChatMessage(
    val message: String
)

@Serializable
data class SyncplayPlaybackState(
    val position: Double,
    val paused: Boolean
)
