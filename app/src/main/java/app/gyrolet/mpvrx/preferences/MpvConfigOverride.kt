/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

/**
 * Categories of mpv options that can be owned by mpv.conf instead of the app.
 *
 * Category checkboxes provide a convenient select-all action while each option remains independently
 * selectable. Compound app features use [MpvConfigControlledFeatures] to disable themselves when any
 * required option is ceded. Android surface, lifecycle, certificate, cookie, and app IPC options are
 * intentionally absent because the player cannot safely disable the infrastructure that owns them.
 */
enum class MpvConfigOverride(
  val preferenceKey: String,
  val optionNames: Set<String>,
) {
  RENDERER(
    preferenceKey = "renderer",
    optionNames =
      setOf(
        "profile",
        "gpu-api",
        "gpu-context",
      ),
  ),
  DECODER(
    preferenceKey = "decoder",
    optionNames =
      setOf(
        "hwdec",
        "hwdec-codecs",
        "vd-lavc-dr",
        "vd-lavc-queue",
        "vd-lavc-film-grain",
      ),
  ),
  HDR_AND_SHADERS(
    preferenceKey = "hdr_and_shaders",
    optionNames =
      setOf(
        "target-prim",
        "target-trc",
        "target-peak",
        "target-colorspace-hint",
        "target-colorspace-hint-mode",
        "inverse-tone-mapping",
        "tone-mapping",
        "tone-mapping-visualize",
        "gamut-mapping-mode",
        "hdr-compute-peak",
        "hdr-reference-white",
        "glsl-shaders",
        "glsl-shader-opts",
        "opengl-pbo",
        "opengl-early-flush",
        "video-scale-x",
        "video-scale-y",
      ),
  ),
  VIDEO_FILTERS(
    preferenceKey = "video_filters",
    optionNames =
      setOf(
        "vf",
        "brightness",
        "contrast",
        "saturation",
        "gamma",
        "hue",
        "sharpen",
        "deband",
        "deband-iterations",
        "deband-threshold",
        "deband-range",
        "deband-grain",
      ),
  ),
  VIDEO_GEOMETRY(
    preferenceKey = "video_geometry",
    optionNames =
      setOf(
        "video-zoom",
        "video-pan-x",
        "video-pan-y",
        "video-aspect-override",
        "panscan",
      ),
  ),
  AUDIO_OUTPUT(
    preferenceKey = "audio_output",
    optionNames =
      setOf(
        "alang",
        "audio-display",
        "audio-delay",
        "audio-normalize-downmix",
        "volume-max",
      ),
  ),
  AUDIO_FILTERS(
    preferenceKey = "audio_filters",
    optionNames = setOf("af", "audio-channels"),
  ),
  SUBTITLE_LOADING(
    preferenceKey = "subtitle_loading",
    optionNames =
      setOf(
        "slang",
        "sub-auto",
        "sub-file-paths",
        "subs-fallback",
        "sub-fonts-dir",
        "sub-codepage",
        "embeddedfonts",
        "sub-font-provider",
      ),
  ),
  SUBTITLE_STYLE(
    preferenceKey = "subtitle_style",
    optionNames =
      setOf(
        "sub-delay",
        "sub-speed",
        "sub-font",
        "sub-font-size",
        "sub-bold",
        "sub-italic",
        "sub-justify",
        "sub-color",
        "sub-back-color",
        "sub-border-color",
        "sub-shadow-color",
        "sub-border-size",
        "sub-outline-size",
        "sub-border-style",
        "sub-shadow-offset",
        "sub-scale",
        "sub-pos",
        "sub-margin-x",
        "sub-scale-by-window",
        "sub-use-margins",
        "sub-ass-override",
        "sub-ass-justify",
        "secondary-sub-delay",
        "secondary-sub-speed",
        "secondary-sub-font",
        "secondary-sub-font-size",
        "secondary-sub-bold",
        "secondary-sub-italic",
        "secondary-sub-justify",
        "secondary-sub-color",
        "secondary-sub-back-color",
        "secondary-sub-border-color",
        "secondary-sub-shadow-color",
        "secondary-sub-border-size",
        "secondary-sub-outline-size",
        "secondary-sub-border-style",
        "secondary-sub-shadow-offset",
        "secondary-sub-scale",
        "secondary-sub-pos",
        "secondary-sub-margin-x",
        "secondary-sub-scale-by-window",
        "secondary-sub-use-margins",
        "secondary-sub-ass-override",
        "blend-subtitles",
      ),
  ),
  PLAYBACK_TIMING(
    preferenceKey = "playback_timing",
    optionNames =
      setOf(
        "speed",
        "audio-pitch-correction",
        "hr-seek",
        "hr-seek-framedrop",
        "video-sync",
        "framedrop",
      ),
  ),
  NETWORK_BUFFERING(
    preferenceKey = "network_buffering",
    optionNames =
      setOf(
        "hls-bitrate",
        "http-allow-redirect",
        "cache",
        "cache-pause",
        "cache-pause-wait",
        "demuxer-max-bytes",
        "demuxer-lavf-o",
      ),
  ),
  YTDLP(
    preferenceKey = "ytdlp",
    optionNames =
      setOf(
        "ytdl",
        "ytdl-path",
        "ytdl-format",
        "ytdl-raw-options",
        "script-opts-append",
      ),
  ),
  OSD(
    preferenceKey = "osd",
    optionNames =
      setOf(
        "osd-margin-x",
        "osd-margin-y",
      ),
  ),
  ;

  companion object {
    private val byPreferenceKey = entries.associateBy(MpvConfigOverride::preferenceKey)
    val allOptionNames: Set<String> = entries.flatMapTo(linkedSetOf()) { it.optionNames }

    fun resolveOptionNames(storedValues: Set<String>): Set<String> =
      storedValues.flatMapTo(linkedSetOf()) { value ->
        byPreferenceKey[value]?.optionNames ?: if (value in allOptionNames) setOf(value) else emptySet()
      }

    fun groupsContaining(optionNames: Set<String>): Set<MpvConfigOverride> =
      entries.filterTo(linkedSetOf()) { group -> group.optionNames.any(optionNames::contains) }
  }
}

object MpvConfigControlledFeatures {
  val HDR_OUTPUT =
    setOf(
      "gpu-api",
      "gpu-context",
      "target-colorspace-hint",
      "target-colorspace-hint-mode",
      "target-prim",
      "target-trc",
      "target-peak",
      "inverse-tone-mapping",
      "tone-mapping",
      "gamut-mapping-mode",
      "hdr-compute-peak",
      "hdr-reference-white",
      "tone-mapping-visualize",
      "glsl-shader-opts",
      "glsl-shaders",
    )

  val ANIME4K =
    setOf(
      "gpu-api",
      "gpu-context",
      "glsl-shaders",
      "opengl-pbo",
      "opengl-early-flush",
      "vd-lavc-dr",
    )

  val AMBIENT = setOf("glsl-shaders", "video-scale-x", "video-scale-y")

  val AUDIO_TRACK_SELECTION = setOf("alang")

  val SUBTITLE_TRACK_SELECTION = setOf("slang")

  val SUBTITLE_DISCOVERY = setOf("sub-auto", "sub-file-paths", "subs-fallback")

  val VIDEO_ZOOM = setOf("video-zoom")

  val VIDEO_ASPECT = setOf("video-aspect-override", "panscan")

  val HARDWARE_DECODER = setOf("hwdec", "gpu-api", "gpu-context")
}

/** The ownership snapshot used by the active libmpv core. */
object MpvConfigOverridePolicy {
  @Volatile
  private var overriddenOptionNames: Set<String> = emptySet()

  fun configure(storedValues: Set<String>) {
    overriddenOptionNames = MpvConfigOverride.resolveOptionNames(storedValues)
  }

  fun isOwnedByMpvConf(optionName: String): Boolean = optionName in overriddenOptionNames

  fun ownsAny(optionNames: Set<String>): Boolean = optionNames.any(::isOwnedByMpvConf)

  fun configurationKey(): String = overriddenOptionNames.sorted().joinToString(",")

  fun shouldSuppress(command: Array<out String>): Boolean =
    when (command.firstOrNull()) {
      "vf", "af" -> command.first().let(::isOwnedByMpvConf)
      "change-list" -> command.getOrNull(1)?.let(::isOwnedByMpvConf) == true
      "set", "set_property", "set_property_string" -> command.getOrNull(1)?.let(::isOwnedByMpvConf) == true
      else -> false
    }
}