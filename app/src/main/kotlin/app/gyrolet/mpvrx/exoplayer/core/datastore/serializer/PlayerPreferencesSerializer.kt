package app.gyrolet.mpvrx.exoplayer.core.datastore.serializer

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerIconStyle
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences

object PlayerPreferencesSerializer : Serializer<PlayerPreferences> {

    private const val LEGACY_DEFAULT_MAX_INITIAL_PLAYER_VOLUME_PERCENTAGE = 100

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val videoFilterEnabledKeys = setOf(
        "isVideoBrightnessFilterEnabled",
        "isVideoContrastFilterEnabled",
        "isVideoSaturationFilterEnabled",
        "isVideoHueFilterEnabled",
        "isVideoGammaFilterEnabled",
        "isVideoSharpeningFilterEnabled",
    )
    private val legacyKeys = setOf(
        "applyEmbeddedStyles",
        "autoBackgroundPlay",
        "autoPip",
        "autoplay",
        "enableBrightnessSwipeGesture",
        "enablePanGesture",
        "enableVolumeBoost",
        "enableVolumeSwipeGesture",
        "hidePlayerButtonsBackground",
        "pauseOnHeadsetDisconnect",
        "rememberPlayerBrightness",
        "rememberSelections",
        "requireAudioFocus",
        "shouldUseLibass",
        "showSystemVolumePanel",
        "subtitleBackground",
        "subtitleTextBold",
        "useLongPressControls",
        "useSeekControls",
        "useSwipeControls",
        "useSystemCaptionStyle",
        "useZoomControls",
    )

    override val defaultValue: PlayerPreferences
        get() = PlayerPreferences()

    override suspend fun readFrom(input: InputStream): PlayerPreferences {
        val serializedPreferences = input.readBytes().decodeToString()

        if (serializedPreferences.containsLegacyPlayerPreferences()) {
            throw CorruptionException(
                message = "Cannot read datastore",
                cause = IllegalStateException("Legacy player preferences format is unsupported"),
            )
        }

        try {
            val preferences = jsonFormat.decodeFromString(
                deserializer = PlayerPreferences.serializer(),
                string = serializedPreferences,
            )
            return preferences.upgradeLegacyDefaults(serializedPreferences)
        } catch (exception: SerializationException) {
            throw CorruptionException("Cannot read datastore", exception)
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun writeTo(t: PlayerPreferences, output: OutputStream) {
        output.write(
            jsonFormat.encodeToString(
                serializer = PlayerPreferences.serializer(),
                value = t,
            ).encodeToByteArray(),
        )
    }

    private fun PlayerPreferences.upgradeLegacyDefaults(serializedPreferences: String): PlayerPreferences {
        val root = runCatching { jsonFormat.parseToJsonElement(serializedPreferences).jsonObject }.getOrNull() ?: return this
        val persistedInitialVolumeLimit = root["maxInitialPlayerVolumePercentage"]?.jsonPrimitive?.content?.toIntOrNull()
        var upgradedPreferences = if (persistedInitialVolumeLimit == LEGACY_DEFAULT_MAX_INITIAL_PLAYER_VOLUME_PERCENTAGE) {
            copy(maxInitialPlayerVolumePercentage = PlayerPreferences.DEFAULT_MAX_INITIAL_PLAYER_VOLUME_PERCENTAGE)
        } else {
            this
        }

        if ("shouldApplyVideoFilters" !in root && hasAdjustedVideoFilters()) {
            upgradedPreferences = upgradedPreferences.copy(shouldApplyVideoFilters = true)
        }
        if ("playerIconStyle" !in root && root["shouldUseClassicPlayerIcons"]?.jsonPrimitive?.content == "true") {
            upgradedPreferences = upgradedPreferences.copy(playerIconStyle = PlayerIconStyle.CLASSIC)
        }
        if (!root.keys.containsAll(videoFilterEnabledKeys)) {
            upgradedPreferences = upgradedPreferences.copy(
                isVideoBrightnessFilterEnabled = upgradedPreferences.isVideoBrightnessFilterEnabled.takeIf {
                    "isVideoBrightnessFilterEnabled" in root
                } ?: (upgradedPreferences.videoBrightness != PlayerPreferences.DEFAULT_VIDEO_BRIGHTNESS),
                isVideoContrastFilterEnabled = upgradedPreferences.isVideoContrastFilterEnabled.takeIf {
                    "isVideoContrastFilterEnabled" in root
                } ?: (upgradedPreferences.videoContrast != PlayerPreferences.DEFAULT_VIDEO_CONTRAST),
                isVideoSaturationFilterEnabled = upgradedPreferences.isVideoSaturationFilterEnabled.takeIf {
                    "isVideoSaturationFilterEnabled" in root
                } ?: (upgradedPreferences.videoSaturation != PlayerPreferences.DEFAULT_VIDEO_SATURATION),
                isVideoHueFilterEnabled = upgradedPreferences.isVideoHueFilterEnabled.takeIf {
                    "isVideoHueFilterEnabled" in root
                } ?: (upgradedPreferences.videoHue != PlayerPreferences.DEFAULT_VIDEO_HUE),
                isVideoGammaFilterEnabled = upgradedPreferences.isVideoGammaFilterEnabled.takeIf {
                    "isVideoGammaFilterEnabled" in root
                } ?: (upgradedPreferences.videoGamma != PlayerPreferences.DEFAULT_VIDEO_GAMMA),
                isVideoSharpeningFilterEnabled = upgradedPreferences.isVideoSharpeningFilterEnabled.takeIf {
                    "isVideoSharpeningFilterEnabled" in root
                } ?: (upgradedPreferences.videoSharpening != PlayerPreferences.DEFAULT_VIDEO_SHARPENING),
            )
        }

        return upgradedPreferences
    }

    private fun PlayerPreferences.hasAdjustedVideoFilters(): Boolean = videoBrightness != PlayerPreferences.DEFAULT_VIDEO_BRIGHTNESS ||
        videoContrast != PlayerPreferences.DEFAULT_VIDEO_CONTRAST ||
        videoSaturation != PlayerPreferences.DEFAULT_VIDEO_SATURATION ||
        videoHue != PlayerPreferences.DEFAULT_VIDEO_HUE ||
        videoGamma != PlayerPreferences.DEFAULT_VIDEO_GAMMA ||
        videoSharpening != PlayerPreferences.DEFAULT_VIDEO_SHARPENING

    private fun String.containsLegacyPlayerPreferences(): Boolean {
        val root = runCatching { jsonFormat.parseToJsonElement(this).jsonObject }.getOrNull() ?: return false
        if (root.keys.any(legacyKeys::contains)) return true

        val subtitleTextSize = root["subtitleTextSize"]?.jsonPrimitive ?: return false
        return subtitleTextSize.content.toIntOrNull() == null
    }
}
