package app.gyrolet.mpvrx.core.player

import app.gyrolet.mpvrx.core.preferences.AppSettings
import app.gyrolet.mpvrx.core.preferences.Preference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Mirrors user preferences onto live mpv properties, the shared equivalent of the Android
 * app's MPV option setup. Works with any [PlayerEngine] backed by mpv (desktop now, iOS later).
 */
class MpvSettingsBinder(
  private val engine: PlayerEngine,
  private val settings: AppSettings,
  private val scope: CoroutineScope,
  /** Copy-back modes are required when frames are rendered in software (desktop render API). */
  private val hardwareDecodingMode: String = "auto-safe",
) {
  fun bind() {
    val decoder = settings.decoder
    val subs = settings.subtitles
    val audio = settings.audio

    decoder.tryHWDecoding.bindTo { engine.setProperty("hwdec", if (it) hardwareDecodingMode else "no") }
    decoder.brightnessFilter.bindTo { engine.setProperty("brightness", it.toString()) }
    decoder.contrastFilter.bindTo { engine.setProperty("contrast", it.toString()) }
    decoder.saturationFilter.bindTo { engine.setProperty("saturation", it.toString()) }
    decoder.gammaFilter.bindTo { engine.setProperty("gamma", it.toString()) }
    decoder.hueFilter.bindTo { engine.setProperty("hue", it.toString()) }

    subs.preferredLanguages.bindTo { engine.setProperty("slang", it) }
    subs.autoEnableSubtitles.bindTo { engine.setProperty("sid", if (it) "auto" else "no") }
    subs.fontSize.bindTo { engine.setProperty("sub-font-size", it.toString()) }
    subs.subScale.bindTo { engine.setProperty("sub-scale", it.toString()) }
    subs.borderSize.bindTo { engine.setProperty("sub-border-size", it.toString()) }
    subs.bold.bindTo { engine.setProperty("sub-bold", it.yesNo()) }
    subs.italic.bindTo { engine.setProperty("sub-italic", it.yesNo()) }
    subs.textColor.bindTo { engine.setProperty("sub-color", it.toMpvColor()) }
    subs.borderColor.bindTo { engine.setProperty("sub-border-color", it.toMpvColor()) }
    subs.subPos.bindTo { engine.setProperty("sub-pos", it.toString()) }
    subs.overrideAssSubs.bindTo { engine.setProperty("sub-ass-override", if (it) "force" else "scale") }
    subs.font.bindTo { if (it.isNotBlank()) engine.setProperty("sub-font", it) }
    subs.defaultSubDelay.bindTo { engine.setProperty("sub-delay", (it / 1000.0).toString()) }

    audio.preferredLanguages.bindTo { engine.setProperty("alang", it) }
    audio.volumeBoostCap.bindTo { engine.setProperty("volume-max", (100 + it).toString()) }
    audio.audioPitchCorrection.bindTo { engine.setProperty("audio-pitch-correction", it.yesNo()) }
    audio.defaultAudioDelay.bindTo { engine.setProperty("audio-delay", (it / 1000.0).toString()) }
  }

  private fun <T> Preference<T>.bindTo(apply: (T) -> Unit) {
    changes().onEach { runCatching { apply(it) } }.launchIn(scope)
  }

  private fun Boolean.yesNo() = if (this) "yes" else "no"

  /** ARGB int -> mpv "#AARRGGBB". */
  private fun Int.toMpvColor(): String = "#" + toUInt().toString(16).padStart(8, '0').uppercase()
}
