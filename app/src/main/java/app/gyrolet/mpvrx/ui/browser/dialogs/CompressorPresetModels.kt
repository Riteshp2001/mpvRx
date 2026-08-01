/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.browser.dialogs

import android.content.SharedPreferences
import androidx.media3.common.MimeTypes
import org.json.JSONArray
import org.json.JSONObject

data class QualityPresetConfig(
  val resolutionShortSide: Int = 0, // 0 for original, or 1080, 720, 480
  val targetFps: Int = 0, // 0 for original, or 60, 30
  val sizeRatio: Float = 0.7f, // Target size ratio vs original (e.g. 0.7 = 70%)
  val audioBitrate: Int = 320_000, // 320000, 192000, 128000
)

val defaultHighPresetConfig =
  QualityPresetConfig(resolutionShortSide = 0, targetFps = 0, sizeRatio = 0.7f, audioBitrate = 320_000)
val defaultMediumPresetConfig =
  QualityPresetConfig(resolutionShortSide = 1080, targetFps = 30, sizeRatio = 0.4f, audioBitrate = 192_000)
val defaultLowPresetConfig =
  QualityPresetConfig(resolutionShortSide = 720, targetFps = 30, sizeRatio = 0.2f, audioBitrate = 128_000)

data class TargetSizePreset(
  val id: String,
  val sizeMb: Float,
  val label: String,
  val isCustom: Boolean = false,
)

val defaultTargetSizePresets =
  listOf(
    TargetSizePreset("discord", 10f, "Discord • GitHub"),
    TargetSizePreset("email", 25f, "Email"),
    TargetSizePreset("stories", 50f, "Stories • Nitro Basic"),
    TargetSizePreset("messenger", 100f, "Messenger • BlueSky"),
    TargetSizePreset("nitro", 500f, "Nitro • Reels"),
    TargetSizePreset("twitter", 512f, "Twitter/X"),
    TargetSizePreset("whatsapp", 2048f, "WhatsApp • Telegram"),
    TargetSizePreset("tg_premium", 4096f, "TG Premium • Feed"),
    TargetSizePreset("x_premium", 8192f, "X Premium"),
  )

data class DefaultVideoConfig(
  val defaultVideoCodec: String = MimeTypes.VIDEO_H265,
  val defaultTargetResolutionHeight: Int = 0, // 0 for original, or 1080, 720, 480
  val defaultTargetFps: Int = 0, // 0 for original, or 60, 30
  val defaultSizeRatio: Float = 0.7f, // Target size ratio vs original (e.g. 0.7 = 70%)
)

data class DefaultAudioConfig(
  val defaultAudioBitrate: Int = 128_000, // 128 kbps
  val defaultRemoveAudio: Boolean = false,
  val defaultAudioVolume: Float = 1.0f, // 100%
)

private const val KEY_PRESET_HIGH = "preset_high"
private const val KEY_PRESET_MEDIUM = "preset_medium"
private const val KEY_PRESET_LOW = "preset_low"
private const val KEY_TARGET_SIZE_PRESETS = "target_size_presets"
private const val KEY_DEFAULT_VIDEO_CONFIG = "default_video_config"
private const val KEY_DEFAULT_AUDIO_CONFIG = "default_audio_config"

fun saveQualityPresetConfig(
  prefs: SharedPreferences,
  key: String,
  config: QualityPresetConfig,
) {
  val obj =
    JSONObject().apply {
      put("resolutionShortSide", config.resolutionShortSide)
      put("targetFps", config.targetFps)
      put("sizeRatio", config.sizeRatio.toDouble())
      put("audioBitrate", config.audioBitrate)
    }
  prefs.edit().putString(key, obj.toString()).apply()
}

fun loadQualityPresetConfig(
  prefs: SharedPreferences,
  key: String,
  default: QualityPresetConfig,
): QualityPresetConfig {
  val str = prefs.getString(key, null) ?: return default
  return try {
    val obj = JSONObject(str)
    QualityPresetConfig(
      resolutionShortSide = obj.getInt("resolutionShortSide"),
      targetFps = obj.getInt("targetFps"),
      sizeRatio = obj.getDouble("sizeRatio").toFloat(),
      audioBitrate = obj.getInt("audioBitrate"),
    )
  } catch (_: Exception) {
    try {
      val parts = str.split(",")
      QualityPresetConfig(
        resolutionShortSide = parts[0].toInt(),
        targetFps = parts[1].toInt(),
        sizeRatio = parts[2].toFloat(),
        audioBitrate = parts[3].toInt(),
      )
    } catch (_: Exception) {
      default
    }
  }
}

fun loadHighPresetConfig(prefs: SharedPreferences): QualityPresetConfig =
  loadQualityPresetConfig(prefs, KEY_PRESET_HIGH, defaultHighPresetConfig)

fun loadMediumPresetConfig(prefs: SharedPreferences): QualityPresetConfig =
  loadQualityPresetConfig(prefs, KEY_PRESET_MEDIUM, defaultMediumPresetConfig)

fun loadLowPresetConfig(prefs: SharedPreferences): QualityPresetConfig =
  loadQualityPresetConfig(prefs, KEY_PRESET_LOW, defaultLowPresetConfig)

fun saveHighPresetConfig(
  prefs: SharedPreferences,
  config: QualityPresetConfig,
) = saveQualityPresetConfig(prefs, KEY_PRESET_HIGH, config)

fun saveMediumPresetConfig(
  prefs: SharedPreferences,
  config: QualityPresetConfig,
) = saveQualityPresetConfig(prefs, KEY_PRESET_MEDIUM, config)

fun saveLowPresetConfig(
  prefs: SharedPreferences,
  config: QualityPresetConfig,
) = saveQualityPresetConfig(prefs, KEY_PRESET_LOW, config)

fun clearSavedQualityPresets(prefs: SharedPreferences) {
  prefs.edit().remove(KEY_PRESET_HIGH).remove(KEY_PRESET_MEDIUM).remove(KEY_PRESET_LOW).apply()
}

fun saveTargetSizePresets(
  prefs: SharedPreferences,
  list: List<TargetSizePreset>,
) {
  val array = JSONArray()
  for (preset in list) {
    val obj =
      JSONObject().apply {
        put("id", preset.id)
        put("sizeMb", preset.sizeMb.toDouble())
        put("label", preset.label)
        put("isCustom", preset.isCustom)
      }
    array.put(obj)
  }
  prefs.edit().putString(KEY_TARGET_SIZE_PRESETS, array.toString()).apply()
}

fun loadTargetSizePresets(prefs: SharedPreferences): List<TargetSizePreset> {
  val str = prefs.getString(KEY_TARGET_SIZE_PRESETS, null)
    ?: return defaultTargetSizePresets.sortedBy { it.sizeMb }
  return try {
    if (str.startsWith("[")) {
      val array = JSONArray(str)
      val list = mutableListOf<TargetSizePreset>()
      for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        list.add(
          TargetSizePreset(
            id = obj.getString("id"),
            sizeMb = obj.getDouble("sizeMb").toFloat(),
            label = obj.getString("label"),
            isCustom = obj.optBoolean("isCustom", false),
          ),
        )
      }
      list.ifEmpty { defaultTargetSizePresets }.sortedBy { it.sizeMb }
    } else {
      // Legacy split parser fallback
      str.split(";\n", ";").mapNotNull { itemStr ->
        val parts = itemStr.trim().split("|")
        if (parts.size >= 4) {
          TargetSizePreset(
            id = parts[0],
            sizeMb = parts[1].toFloat(),
            label = parts[2],
            isCustom = parts[3].trim().toBoolean(),
          )
        } else {
          null
        }
      }.ifEmpty { defaultTargetSizePresets }.sortedBy { it.sizeMb }
    }
  } catch (_: Exception) {
    defaultTargetSizePresets.sortedBy { it.sizeMb }
  }
}

fun clearSavedTargetSizePresets(prefs: SharedPreferences) {
  prefs.edit().remove(KEY_TARGET_SIZE_PRESETS).apply()
}

fun saveDefaultVideoConfig(
  prefs: SharedPreferences,
  config: DefaultVideoConfig,
) {
  val obj =
    JSONObject().apply {
      put("defaultVideoCodec", config.defaultVideoCodec)
      put("defaultTargetResolutionHeight", config.defaultTargetResolutionHeight)
      put("defaultTargetFps", config.defaultTargetFps)
      put("defaultSizeRatio", config.defaultSizeRatio.toDouble())
    }
  prefs.edit().putString(KEY_DEFAULT_VIDEO_CONFIG, obj.toString()).apply()
}

fun loadDefaultVideoConfig(prefs: SharedPreferences): DefaultVideoConfig {
  val str = prefs.getString(KEY_DEFAULT_VIDEO_CONFIG, null) ?: return DefaultVideoConfig()
  return try {
    val obj = JSONObject(str)
    DefaultVideoConfig(
      defaultVideoCodec = obj.optString("defaultVideoCodec", MimeTypes.VIDEO_H265),
      defaultTargetResolutionHeight = obj.optInt("defaultTargetResolutionHeight", 0),
      defaultTargetFps = obj.optInt("defaultTargetFps", 0),
      defaultSizeRatio = obj.optDouble("defaultSizeRatio", 0.7).toFloat(),
    )
  } catch (_: Exception) {
    DefaultVideoConfig()
  }
}

fun clearSavedDefaultVideoConfig(prefs: SharedPreferences) {
  prefs.edit().remove(KEY_DEFAULT_VIDEO_CONFIG).apply()
}

fun saveDefaultAudioConfig(
  prefs: SharedPreferences,
  config: DefaultAudioConfig,
) {
  val obj =
    JSONObject().apply {
      put("defaultAudioBitrate", config.defaultAudioBitrate)
      put("defaultRemoveAudio", config.defaultRemoveAudio)
      put("defaultAudioVolume", config.defaultAudioVolume.toDouble())
    }
  prefs.edit().putString(KEY_DEFAULT_AUDIO_CONFIG, obj.toString()).apply()
}

fun loadDefaultAudioConfig(prefs: SharedPreferences): DefaultAudioConfig {
  val str = prefs.getString(KEY_DEFAULT_AUDIO_CONFIG, null) ?: return DefaultAudioConfig()
  return try {
    val obj = JSONObject(str)
    DefaultAudioConfig(
      defaultAudioBitrate = obj.optInt("defaultAudioBitrate", 128_000),
      defaultRemoveAudio = obj.optBoolean("defaultRemoveAudio", false),
      defaultAudioVolume = obj.optDouble("defaultAudioVolume", 1.0).toFloat(),
    )
  } catch (_: Exception) {
    DefaultAudioConfig()
  }
}

fun clearSavedDefaultAudioConfig(prefs: SharedPreferences) {
  prefs.edit().remove(KEY_DEFAULT_AUDIO_CONFIG).apply()
}
