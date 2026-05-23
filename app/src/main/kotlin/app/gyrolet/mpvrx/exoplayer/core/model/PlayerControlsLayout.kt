package app.gyrolet.mpvrx.exoplayer.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

@Serializable
enum class PlayerControlZone {
    TOP_RIGHT,
    BOTTOM_LEFT,
}

@Serializable
data class PlayerControlLayoutEntry(
    val control: PlayerControl,
    val zone: PlayerControlZone,
)

@Serializable(with = PlayerControlsLayoutSerializer::class)
class PlayerControlsLayout(
    entries: List<PlayerControlLayoutEntry> = defaultEntries(),
) {

    val entries: List<PlayerControlLayoutEntry> = normalizeEntries(entries)

    fun controlsIn(zone: PlayerControlZone): List<PlayerControl> = entries
        .filter { it.zone == zone }
        .map { it.control }

    fun move(
        control: PlayerControl,
        toZone: PlayerControlZone,
        toIndex: Int,
    ): PlayerControlsLayout {
        if (control !in customizableControls) return this

        val remainingEntries = entries.filterNot { it.control == control }.toMutableList()
        val targetIndexes = remainingEntries.withIndex()
            .filter { it.value.zone == toZone }
            .map { it.index }

        val insertAt = when {
            targetIndexes.isEmpty() -> remainingEntries.size
            toIndex <= 0 -> targetIndexes.first()
            toIndex >= targetIndexes.size -> targetIndexes.last() + 1
            else -> targetIndexes[toIndex]
        }

        remainingEntries.add(
            index = insertAt,
            element = PlayerControlLayoutEntry(
                control = control,
                zone = toZone,
            ),
        )
        return PlayerControlsLayout(remainingEntries)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlayerControlsLayout) return false
        return entries == other.entries
    }

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "PlayerControlsLayout(entries=$entries)"

    companion object {
        val topRightControls: List<PlayerControl> = listOf(
            PlayerControl.PLAYLIST,
            PlayerControl.PLAYBACK_SPEED,
            PlayerControl.AUDIO,
            PlayerControl.SUBTITLE,
        )

        val bottomLeftControls: List<PlayerControl> = listOf(
            PlayerControl.LOCK,
            PlayerControl.SCALE,
            PlayerControl.DECODER,
            PlayerControl.AMBIENCE_MODE,
            PlayerControl.VIDEO_FILTERS,
            PlayerControl.PIP,
            PlayerControl.SCREENSHOT,
            PlayerControl.BACKGROUND_PLAY,
            PlayerControl.LOOP,
            PlayerControl.SHUFFLE,
            PlayerControl.SLEEP_TIMER,
        )

        val customizableControls: Set<PlayerControl> =
            (topRightControls + bottomLeftControls).toSet()

        fun defaultEntries(): List<PlayerControlLayoutEntry> = buildList {
            addAll(
                topRightControls.map { control ->
                    PlayerControlLayoutEntry(
                        control = control,
                        zone = PlayerControlZone.TOP_RIGHT,
                    )
                },
            )
            addAll(
                bottomLeftControls.map { control ->
                    PlayerControlLayoutEntry(
                        control = control,
                        zone = PlayerControlZone.BOTTOM_LEFT,
                    )
                },
            )
        }

        private fun normalizeEntries(entries: List<PlayerControlLayoutEntry>): List<PlayerControlLayoutEntry> {
            val seenControls = mutableSetOf<PlayerControl>()
            val normalizedEntries = entries
                .filter { it.control in customizableControls }
                .filter { seenControls.add(it.control) }
                .toMutableList()

            defaultEntries().forEach { defaultEntry ->
                if (defaultEntry.control in seenControls) return@forEach
                normalizedEntries.add(defaultEntry)
            }
            return normalizedEntries
        }
    }
}

object PlayerControlsLayoutSerializer : KSerializer<PlayerControlsLayout> {
    private val entriesSerializer = ListSerializer(PlayerControlLayoutEntry.serializer())

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("one.only.player.core.model.PlayerControlsLayout") {
        element("entries", entriesSerializer.descriptor)
    }

    override fun serialize(
        encoder: Encoder,
        value: PlayerControlsLayout,
    ) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(
                descriptor = descriptor,
                index = 0,
                serializer = entriesSerializer,
                value = value.entries,
            )
        }
    }

    override fun deserialize(decoder: Decoder): PlayerControlsLayout {
        var entries = PlayerControlsLayout.defaultEntries()

        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> entries = decodeSerializableElement(
                        descriptor = descriptor,
                        index = index,
                        deserializer = entriesSerializer,
                    )
                }
            }
        }

        return PlayerControlsLayout(entries)
    }
}

