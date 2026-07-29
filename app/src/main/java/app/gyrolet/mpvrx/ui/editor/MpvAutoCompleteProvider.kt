/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.editor

import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem

enum class MpvCompletionMode {
  SCRIPT,
  MPV_CONF,
  INPUT_CONF,
}

/**
 * Provides autocompletion for mpv scripts and config files.
 * Uses sora-editor 0.24.5 API.
 */
object MpvAutoCompleteProvider {
  private const val MAX_COMPLETIONS = 64

  private data class CompletionDef(
    val label: String,
    val signature: String,
    val desc: String,
    val kind: CompletionItemKind = CompletionItemKind.Function,
    val commitSuffix: String = "",
  )

  fun provideCompletion(
    prefix: String,
    mode: MpvCompletionMode,
    publisher: CompletionPublisher,
  ) {
    val query = prefix.trimStart('-')
    if (query.isEmpty()) return

    val suggestions =
      completionsFor(mode)
        .asSequence()
        .distinctBy { it.label }
        .filter { it.matchRank(query) < Int.MAX_VALUE }
        .sortedWith(
          compareBy<CompletionDef> { it.matchRank(query) }
            .thenBy { it.label.length }
            .thenBy { it.label.lowercase() },
        ).take(MAX_COMPLETIONS)
        .map { def ->
          val commitText = def.label + def.commitSuffix
          SimpleCompletionItem(def.label, def.desc, prefix.length, commitText).apply {
            kind(def.kind)
            def.detailText()?.let { detail = it }
          }
        }.toList()

    publisher.addItems(suggestions)
  }

  private fun createDef(
    label: String,
    signature: String,
    desc: String,
    kind: CompletionItemKind = CompletionItemKind.Function,
    commitSuffix: String = "",
  ) = CompletionDef(label, signature, desc, kind, commitSuffix)

  private fun completionsFor(mode: MpvCompletionMode): List<CompletionDef> =
    when (mode) {
      MpvCompletionMode.SCRIPT -> mpvCompletions
      MpvCompletionMode.MPV_CONF -> mpvConfigCompletions
      MpvCompletionMode.INPUT_CONF -> inputConfCompletions
    }

  private fun CompletionDef.matchRank(query: String): Int {
    val label = this.label.lowercase()
    val normalizedQuery = query.lowercase()
    val nqDot = normalizedQuery.replace('=', '.')
    return when {
      label == normalizedQuery -> 0
      label.startsWith(normalizedQuery) -> 1
      label == nqDot -> 0
      label.startsWith(nqDot) -> 1
      label.contains(".$normalizedQuery") ||
        label.contains("-$normalizedQuery") ||
        label.contains("/$normalizedQuery") ||
        label.contains("=$normalizedQuery") -> 2
      label.contains(normalizedQuery) -> 3
      else -> Int.MAX_VALUE
    }
  }

  private fun CompletionDef.detailText(): String? {
    val signatureSuffix = signature.removePrefix(label)
    return when {
      signatureSuffix.isNotBlank() -> " $signatureSuffix"
      signature != label -> " $signature"
      else -> null
    }
  }

  private fun configOption(
    label: String,
    value: String,
    desc: String,
  ) = createDef(label, "$label=$value", desc, CompletionItemKind.Property, commitSuffix = "=")

  private fun inputKey(
    label: String,
    desc: String,
  ) = createDef(label, "input.conf key", desc, CompletionItemKind.Keyword)

  private val mpvConfigCompletions by lazy {
    listOf(
      configOption("profile", "<name>", "Apply an mpv profile by name."),
      configOption("include", "<path>", "Include another config file."),
      configOption("input-conf", "<path>", "Load key bindings from a file."),
      configOption("load-scripts", "<yes|no>", "Enable loading scripts at startup."),
      configOption("script", "<path>", "Load an additional script file."),
      configOption("script-opts", "<key=value,...>", "Pass options to mpv scripts."),
      configOption("config", "<yes|no>", "Enable mpv config file loading."),
      configOption("save-position-on-quit", "<yes|no>", "Resume from the last playback position."),
      configOption("keep-open", "<yes|no|always>", "Keep the player open after playback ends."),
      configOption("pause", "<yes|no>", "Start or keep playback paused."),
      configOption("volume", "<number>", "Set startup audio volume."),
      configOption("volume-max", "<number>", "Set maximum allowed volume."),
      configOption("mute", "<yes|no>", "Start with audio muted."),
      configOption("audio", "<auto|no|id>", "Select the audio track."),
      configOption("aid", "<auto|no|id>", "Select the audio track ID."),
      configOption("audio-delay", "<seconds>", "Shift audio timing."),
      configOption("sub", "<auto|no|id>", "Select the subtitle track."),
      configOption("sid", "<auto|no|id>", "Select the subtitle track ID."),
      configOption("sub-delay", "<seconds>", "Shift subtitle timing."),
      configOption("sub-pos", "<0-100>", "Set subtitle vertical position."),
      configOption("sub-scale", "<number>", "Scale subtitle size."),
      configOption("sub-visibility", "<yes|no>", "Show or hide subtitles."),
      configOption("sub-auto", "<no|exact|fuzzy|all>", "Auto-load external subtitles."),
      configOption("audio-file-auto", "<no|exact|fuzzy|all>", "Auto-load external audio files."),
      configOption("secondary-sid", "<auto|no|id>", "Select secondary subtitles."),
      configOption("osd-level", "<0-3>", "Choose OSD verbosity."),
      configOption("osd-duration", "<milliseconds>", "Set OSD message duration."),
      configOption("osd-font-size", "<number>", "Set OSD font size."),
      configOption("osd-scale", "<number>", "Scale OSD text and UI."),
      configOption("screenshot-format", "<png|jpg|webp>", "Choose screenshot image format."),
      configOption("screenshot-directory", "<path>", "Set screenshot save directory."),
      configOption("screenshot-template", "<template>", "Set screenshot filename template."),
      configOption("hwdec", "<auto|no|mode>", "Choose hardware decoding mode."),
      configOption("vo", "<driver>", "Choose video output driver."),
      configOption("ao", "<driver>", "Choose audio output driver."),
      configOption("gpu-api", "<auto|vulkan|opengl>", "Choose GPU API."),
      configOption("gpu-context", "<auto|context>", "Choose GPU context."),
      configOption("video-sync", "<mode>", "Choose video/audio sync mode."),
      configOption("interpolation", "<yes|no>", "Enable frame interpolation."),
      configOption("scale", "<filter>", "Choose video upscaler."),
      configOption("cscale", "<filter>", "Choose chroma scaler."),
      configOption("dscale", "<filter>", "Choose video downscaler."),
      configOption("tscale", "<filter>", "Choose temporal scaler."),
      configOption("deband", "<yes|no>", "Enable debanding."),
      configOption("deband-iterations", "<number>", "Set deband pass count."),
      configOption("deband-threshold", "<number>", "Set deband strength threshold."),
      configOption("sigmoid-upscaling", "<yes|no>", "Use sigmoid upscaling."),
      configOption("correct-downscaling", "<yes|no>", "Improve downscaling accuracy."),
      configOption("linear-downscaling", "<yes|no>", "Use linear-light downscaling."),
      configOption("dither-depth", "<auto|no|bits>", "Set output dithering depth."),
      configOption("target-prim", "<auto|bt.709|bt.2020>", "Set target color primaries."),
      configOption("target-trc", "<auto|bt.1886|pq|hlg>", "Set target transfer curve."),
      configOption("target-peak", "<nits>", "Set target display peak brightness."),
      configOption("tone-mapping", "<algorithm>", "Choose HDR tone mapping algorithm."),
      configOption("tone-mapping-param", "<number>", "Tune tone mapping strength."),
      configOption("tone-mapping-mode", "<auto|clip|mobius|reinhard>", "Choose tone mapping mode."),
      configOption("inverse-tone-mapping", "<yes|no>", "Enable inverse tone mapping."),
      configOption("gamut-mapping-mode", "<mode>", "Choose gamut mapping behavior."),
      configOption("hdr-compute-peak", "<yes|no>", "Estimate HDR peak brightness."),
      configOption("hdr-peak-percentile", "<number>", "Set HDR peak percentile."),
      configOption("loop-file", "<no|inf|count>", "Loop the current file."),
      configOption("loop-playlist", "<no|inf|count>", "Loop the playlist."),
      configOption("shuffle", "<yes|no>", "Shuffle playlist playback."),
      configOption("speed", "<number>", "Set playback speed."),
      configOption("ytdl", "<yes|no>", "Enable youtube-dl / yt-dlp hook."),
      configOption("ytdl-path", "<path>", "Path to youtube-dl / yt-dlp."),
      configOption("cache", "<yes|no|auto>", "Control stream cache usage."),
      configOption("demuxer-max-bytes", "<size>", "Limit demuxer cache size."),
      configOption("demuxer-max-back-bytes", "<size>", "Limit backward cache size."),
      configOption("vf", "<filter-list>", "Set the video filter chain."),
      configOption("af", "<filter-list>", "Set the audio filter chain."),
      configOption("input-default-bindings", "<yes|no>", "Enable default key bindings."),
      configOption("input-vo-keyboard", "<yes|no>", "Enable keyboard input through VO."),
      // ── Audio extras ──
      configOption("audio-pitch-correction", "<yes|no>", "Preserve pitch when changing speed."),
      configOption("audio-exclusive", "<yes|no>", "Exclusive audio output mode."),
      configOption("audio-buffer", "<seconds>", "Audio output buffer size."),
      configOption("audio-file-paths", "<path-list>", "Search paths for external audio files."),
      configOption("audio-format", "<auto|s16|s32|float>", "Audio sample format."),
      configOption("audio-samplerate", "<auto|Hz>", "Audio output sample rate."),
      configOption("audio-channels", "<auto|count>", "Audio output channel count."),
      // ── Video extras ──
      configOption("video-rotate", "<0|90|180|270>", "Force video rotation."),
      configOption("video-zoom", "<number>", "Video zoom level."),
      configOption("video-unscaled", "<no|yes|downscale-big>", "Disable video scaling."),
      configOption("video-aspect-override", "<ratio|no>", "Override aspect ratio."),
      configOption("video-align-x", "<-1..1>", "Horizontal video alignment."),
      configOption("video-align-y", "<-1..1>", "Vertical video alignment."),
      configOption("video-pan-x", "<number>", "Pan video horizontally."),
      configOption("video-pan-y", "<number>", "Pan video vertically."),
      configOption("deinterlace", "<yes|no|auto>", "Enable deinterlacing."),
      // ── Playback extras ──
      configOption("keep-open-pauses", "<yes|no>", "Pause on EOF instead of stopping."),
      configOption("hr-seek", "<no|yes|always>", "Use precise seeks."),
      configOption("hr-seek-framedrop", "<yes|no>", "Drop frames during hr-seek."),
      configOption("cache-pause", "<yes|no>", "Pause when cache runs low."),
      configOption("cache-pause-initial", "<yes|no>", "Pause until initial cache filled."),
      configOption("demuxer-max-duration", "<seconds>", "Max demuxer cache duration."),
      configOption("demuxer-max-back-duration", "<seconds>", "Max backward cache duration."),
      configOption("demuxer-seekable-cache", "<yes|no>", "Enable seeking in cached range."),
      configOption("ab-loop-a", "<time>", "Set A-B loop point A."),
      configOption("ab-loop-b", "<time>", "Set A-B loop point B."),
      // ── Subtitle extras ──
      configOption("sub-font", "<name>", "Subtitle font family."),
      configOption("sub-font-size", "<number>", "Subtitle font size."),
      configOption("sub-color", "<#RRGGBB[AA]>", "Subtitle text color."),
      configOption("sub-border-color", "<#RRGGBB[AA]>", "Subtitle border color."),
      configOption("sub-border-size", "<number>", "Subtitle border/outline size."),
      configOption("sub-shadow-color", "<#RRGGBB[AA]>", "Subtitle shadow color."),
      configOption("sub-shadow-offset", "<number>", "Subtitle shadow offset."),
      configOption("sub-bold", "<yes|no>", "Bold subtitle text."),
      configOption("sub-ass-override", "<no|yes|force|scale>", "Override ASS subtitle styles."),
      configOption("sub-justify", "<auto|left|center|right>", "Subtitle text justification."),
      configOption("sub-clear-on-seek", "<yes|no>", "Clear subs on seek."),
      // ── OSD extras ──
      configOption("osd-font", "<name>", "OSD font family."),
      configOption("osd-color", "<#RRGGBB[AA]>", "OSD text color."),
      configOption("osd-border-color", "<#RRGGBB[AA]>", "OSD border color."),
      configOption("osd-shadow-color", "<#RRGGBB[AA]>", "OSD shadow color."),
      configOption("osd-margin-x", "<number>", "Horizontal OSD margin."),
      configOption("osd-margin-y", "<number>", "Vertical OSD margin."),
      // ── Display extras ──
      configOption("ontop", "<yes|no>", "Keep window on top."),
      configOption("border", "<yes|no>", "Show window border."),
      configOption("cursor-autohide", "<time|no|always>", "Auto-hide cursor after inactivity."),
      // ── HDR extras ──
      configOption("tone-mapping-param", "<number>", "Tone-mapping algorithm parameter."),
      configOption("tone-mapping-mode", "<auto|rgb|luma>", "Tone-mapping mode."),
      configOption("gamut-mapping-mode", "<auto|clip|desaturate|warn>", "Gamut mapping behavior."),
      configOption("hdr-compute-peak", "<yes|no>", "Compute HDR peak per-frame."),
      configOption("hdr-peak-percentile", "<number>", "HDR peak percentile."),
    )
  }

  private val inputConfCompletions by lazy {
    inputKeyCompletions +
      mpvCompletions.filter { it.kind == CompletionItemKind.Keyword || it.kind == CompletionItemKind.Property }
  }

  private val inputKeyCompletions =
    listOf(
      inputKey("SPACE", "Spacebar key."),
      inputKey("ENTER", "Enter key."),
      inputKey("ESC", "Escape key."),
      inputKey("TAB", "Tab key."),
      inputKey("BACKSPACE", "Backspace key."),
      inputKey("DEL", "Delete key."),
      inputKey("INS", "Insert key."),
      inputKey("HOME", "Home key."),
      inputKey("END", "End key."),
      inputKey("PGUP", "Page up key."),
      inputKey("PGDWN", "Page down key."),
      inputKey("UP", "Arrow up key."),
      inputKey("DOWN", "Arrow down key."),
      inputKey("LEFT", "Arrow left key."),
      inputKey("RIGHT", "Arrow right key."),
      inputKey("Ctrl+SPACE", "Control plus space."),
      inputKey("Ctrl+LEFT", "Control plus left arrow."),
      inputKey("Ctrl+RIGHT", "Control plus right arrow."),
      inputKey("Shift+LEFT", "Shift plus left arrow."),
      inputKey("Shift+RIGHT", "Shift plus right arrow."),
      inputKey("Alt+LEFT", "Alt plus left arrow."),
      inputKey("Alt+RIGHT", "Alt plus right arrow."),
      inputKey("MBTN_LEFT", "Left mouse button."),
      inputKey("MBTN_RIGHT", "Right mouse button."),
      inputKey("MBTN_MID", "Middle mouse button."),
      inputKey("WHEEL_UP", "Mouse wheel up."),
      inputKey("WHEEL_DOWN", "Mouse wheel down."),
      inputKey("MBTN_LEFT_DBL", "Left mouse button double-click."),
      inputKey("MBTN_RIGHT_DBL", "Right mouse button double-click."),
      inputKey("SHIFT+MBTN_LEFT", "Shift + left click."),
      inputKey("SHIFT+MBTN_RIGHT", "Shift + right click."),
      inputKey("Ctrl+MBTN_LEFT", "Control + left click."),
      inputKey("Ctrl+WHEEL_UP", "Control + wheel up (zoom)."),
      inputKey("Ctrl+WHEEL_DOWN", "Control + wheel down (zoom)."),
      inputKey("F1", "F1 function key."),
      inputKey("F2", "F2 function key."),
      inputKey("F3", "F3 function key."),
      inputKey("F4", "F4 function key."),
      inputKey("F5", "F5 function key."),
      inputKey("F6", "F6 function key."),
      inputKey("F7", "F7 function key."),
      inputKey("F8", "F8 function key."),
      inputKey("F9", "F9 function key."),
      inputKey("F10", "F10 function key."),
      inputKey("F11", "F11 function key."),
      inputKey("F12", "F12 function key."),
      inputKey("0", "The number 0 key."),
      inputKey("1", "The number 1 key."),
      inputKey("2", "The number 2 key."),
      inputKey("3", "The number 3 key."),
      inputKey("4", "The number 4 key."),
      inputKey("5", "The number 5 key."),
      inputKey("6", "The number 6 key."),
      inputKey("7", "The number 7 key."),
      inputKey("8", "The number 8 key."),
      inputKey("9", "The number 9 key."),
    )

  private val mpvCompletions =
    listOf(
      // ============================================================
      // mpv JavaScript globals / root objects
      // ============================================================
      createDef("mp", "mpv global object", "The main mpv API object.", CompletionItemKind.Variable),
      createDef(
        "mp.msg",
        "mpv logging namespace",
        "Functions for logging to the console.",
        CompletionItemKind.Module,
      ),
      createDef(
        "mp.utils",
        "mpv utility namespace",
        "Utility functions for file IO, subprocesses, etc.",
        CompletionItemKind.Module,
      ),
      createDef(
        "mp.options",
        "mpv script options namespace",
        "Functions for reading script configuration.",
        CompletionItemKind.Module,
      ),
      createDef(
        "mp.input",
        "mpv interactive input namespace",
        "Functions for user input interaction.",
        CompletionItemKind.Module,
      ),
      // ============================================================
      // Core mp JS API
      // ============================================================
      createDef("mp.command", "mp.command(commandString)", "Execute an mpv command from a flat string."),
      createDef(
        "mp.commandv",
        "mp.commandv(name, arg1, arg2, ...)",
        "Execute an mpv command with separated arguments.",
      ),
      createDef(
        "mp.command_native",
        "mp.command_native(table, def)",
        "Execute an mpv command using native JS object/array form.",
      ),
      createDef(
        "mp.command_native_async",
        "mp.command_native_async(table, callback)",
        "Execute native command asynchronously.",
      ),
      createDef("mp.abort_async_command", "mp.abort_async_command(id)", "Abort an asynchronous native command."),
      createDef("mp.get_property", "mp.get_property(name, def)", "Get property as string."),
      createDef("mp.get_property_osd", "mp.get_property_osd(name, def)", "Get property formatted for OSD."),
      createDef("mp.get_property_bool", "mp.get_property_bool(name, def)", "Get property as boolean."),
      createDef("mp.get_property_number", "mp.get_property_number(name, def)", "Get property as number."),
      createDef("mp.get_property_native", "mp.get_property_native(name, def)", "Get property as native JS value."),
      createDef("mp.set_property", "mp.set_property(name, value)", "Set property as string."),
      createDef("mp.set_property_bool", "mp.set_property_bool(name, value)", "Set property as boolean."),
      createDef("mp.set_property_number", "mp.set_property_number(name, value)", "Set property as number."),
      createDef("mp.set_property_native", "mp.set_property_native(name, value)", "Set property as native JS value."),
      createDef("mp.del_property", "mp.del_property(name)", "Delete/reset a property."),
      createDef("mp.observe_property", "mp.observe_property(name, type, callback)", "Observe property changes."),
      createDef("mp.unobserve_property", "mp.unobserve_property(callback)", "Stop observing a property."),
      createDef("mp.add_key_binding", "mp.add_key_binding(key, name, fn, flags)", "Add a script key binding."),
      createDef(
        "mp.add_forced_key_binding",
        "mp.add_forced_key_binding(key, name, fn, flags)",
        "Add forced key binding.",
      ),
      createDef("mp.remove_key_binding", "mp.remove_key_binding(name)", "Remove script key binding."),
      createDef("mp.register_event", "mp.register_event(name, fn)", "Register an mpv event callback."),
      createDef("mp.unregister_event", "mp.unregister_event(fn)", "Unregister an event callback."),
      createDef(
        "mp.register_script_message",
        "mp.register_script_message(name, fn)",
        "Register script-message callback.",
      ),
      createDef(
        "mp.unregister_script_message",
        "mp.unregister_script_message(name)",
        "Unregister script-message callback.",
      ),
      createDef("mp.add_hook", "mp.add_hook(type, priority, fn)", "Add lifecycle hook."),
      createDef("mp.register_idle", "mp.register_idle(fn)", "Register idle callback."),
      createDef("mp.unregister_idle", "mp.unregister_idle(fn)", "Unregister idle callback."),
      createDef("mp.osd_message", "mp.osd_message(text, duration)", "Show OSD message."),
      createDef("mp.create_osd_overlay", "mp.create_osd_overlay(format)", "Create ASS/text OSD overlay."),
      createDef("mp.get_osd_size", "mp.get_osd_size()", "Get OSD width, height, aspect."),
      createDef("mp.get_time", "mp.get_time()", "Get mpv time in seconds."),
      createDef("mp.get_time_ms", "mp.get_time_ms()", "Get mpv time in milliseconds."),
      createDef("mp.last_error", "mp.last_error()", "Return last mpv JS API error."),
      createDef("mp.enable_messages", "mp.enable_messages(level)", "Enable script message level."),
      createDef("mp.get_opt", "mp.get_opt(key)", "Get script option."),
      createDef("mp.get_script_name", "mp.get_script_name()", "Get current script name."),
      createDef("mp.get_script_directory", "mp.get_script_directory()", "Get current script directory."),
      createDef("mp.get_script_file", "mp.get_script_file()", "Get current script file."),
      createDef("mp.get_wakeup_pipe", "mp.get_wakeup_pipe()", "Get wakeup pipe, mostly advanced use."),
      // ============================================================
      // mp.msg logging
      // ============================================================
      createDef("mp.msg.log", "mp.msg.log(level, ...)", "Log at custom level."),
      createDef("mp.msg.fatal", "mp.msg.fatal(...)", "Fatal log."),
      createDef("mp.msg.error", "mp.msg.error(...)", "Error log."),
      createDef("mp.msg.warn", "mp.msg.warn(...)", "Warning log."),
      createDef("mp.msg.info", "mp.msg.info(...)", "Info log."),
      createDef("mp.msg.verbose", "mp.msg.verbose(...)", "Verbose log."),
      createDef("mp.msg.debug", "mp.msg.debug(...)", "Debug log."),
      createDef("mp.msg.trace", "mp.msg.trace(...)", "Trace log."),
      // ============================================================
      // mp.utils
      // ============================================================
      createDef("mp.utils.getcwd", "mp.utils.getcwd()", "Get current working directory."),
      createDef("mp.utils.readdir", "mp.utils.readdir(path, filter)", "Read directory contents."),
      createDef("mp.utils.file_info", "mp.utils.file_info(path)", "Get file info."),
      createDef("mp.utils.split_path", "mp.utils.split_path(path)", "Split path into directory and file."),
      createDef("mp.utils.join_path", "mp.utils.join_path(p1, p2)", "Join paths."),
      createDef("mp.utils.getenv", "mp.utils.getenv(name)", "Get environment variable."),
      createDef("mp.utils.get_env_list", "mp.utils.get_env_list()", "Get environment variable list."),
      createDef("mp.utils.getpid", "mp.utils.getpid()", "Get process ID."),
      createDef("mp.utils.get_user_path", "mp.utils.get_user_path(path)", "Expand mpv user/meta path."),
      createDef("mp.utils.read_file", "mp.utils.read_file(fname, max)", "Read text file."),
      createDef(
        "mp.utils.write_file",
        "mp.utils.write_file(fname, str)",
        "Write text file. fname should use file:// prefix.",
      ),
      createDef("mp.utils.append_file", "mp.utils.append_file(fname, str)", "Append text file."),
      createDef("mp.utils.subprocess", "mp.utils.subprocess(table)", "Run subprocess."),
      createDef("mp.utils.subprocess_detached", "mp.utils.subprocess_detached(table)", "Run detached subprocess."),
      createDef("mp.utils.compile_js", "mp.utils.compile_js(fname, content)", "Compile JS string into function."),
      // ============================================================
      // mp.options
      // ============================================================
      createDef(
        "mp.options.read_options",
        "mp.options.read_options(obj, identifier, on_update)",
        "Read script options into object.",
      ),
      // ============================================================
      // mp.input
      // ============================================================
      createDef("mp.input.get", "mp.input.get(obj)", "Request text input from user."),
      createDef("mp.input.select", "mp.input.select(obj)", "Show selectable list input."),
      createDef("mp.input.terminate", "mp.input.terminate()", "Terminate active input request."),
      createDef("mp.input.log", "mp.input.log(message, style)", "Append to latest input log."),
      createDef("mp.input.set_log", "mp.input.set_log(log)", "Replace latest input log."),
      // ============================================================
      // OSD overlay object methods
      // returned by mp.create_osd_overlay()
      // ============================================================
      createDef("overlay.data", "overlay.data", "Overlay text/ASS content.", CompletionItemKind.Property),
      createDef("overlay.res_x", "overlay.res_x", "Overlay virtual X resolution.", CompletionItemKind.Property),
      createDef("overlay.res_y", "overlay.res_y", "Overlay virtual Y resolution.", CompletionItemKind.Property),
      createDef("overlay.z", "overlay.z", "Overlay z-index.", CompletionItemKind.Property),
      createDef("overlay.hidden", "overlay.hidden", "Hide/show overlay.", CompletionItemKind.Property),
      createDef("overlay.update", "overlay.update()", "Update overlay after changing data."),
      createDef("overlay.remove", "overlay.remove()", "Remove overlay."),
      // ============================================================
      // Important mpv input commands
      // Use with mp.commandv("seek", "10", "relative")
      // ============================================================
      createDef("seek", "seek <target> [flags]", "Seek playback position.", CompletionItemKind.Keyword),
      createDef("revert-seek", "revert-seek [flags]", "Undo previous seek.", CompletionItemKind.Keyword),
      createDef("frame-step", "frame-step", "Step one frame forward.", CompletionItemKind.Keyword),
      createDef("frame-back-step", "frame-back-step", "Step one frame backward.", CompletionItemKind.Keyword),
      createDef("playlist-next", "playlist-next [flags]", "Go to next playlist entry.", CompletionItemKind.Keyword),
      createDef(
        "playlist-prev",
        "playlist-prev [flags]",
        "Go to previous playlist entry.",
        CompletionItemKind.Keyword,
      ),
      createDef(
        "playlist-play-index",
        "playlist-play-index <index>",
        "Play playlist entry by index.",
        CompletionItemKind.Keyword,
      ),
      createDef("playlist-shuffle", "playlist-shuffle", "Shuffle playlist.", CompletionItemKind.Keyword),
      createDef("playlist-unshuffle", "playlist-unshuffle", "Undo playlist shuffle.", CompletionItemKind.Keyword),
      createDef("playlist-remove", "playlist-remove <index>", "Remove playlist entry.", CompletionItemKind.Keyword),
      createDef(
        "playlist-move",
        "playlist-move <index1> <index2>",
        "Move playlist entry.",
        CompletionItemKind.Keyword,
      ),
      createDef("playlist-clear", "playlist-clear", "Clear playlist.", CompletionItemKind.Keyword),
      createDef(
        "loadfile",
        "loadfile <url> [flags] [index] [options]",
        "Load media file or URL.",
        CompletionItemKind.Keyword,
      ),
      createDef(
        "loadlist",
        "loadlist <url> [flags] [index]",
        "Load playlist file or URL.",
        CompletionItemKind.Keyword,
      ),
      createDef("sub-add", "sub-add <url> [flags] [title] [lang]", "Add subtitle track.", CompletionItemKind.Keyword),
      createDef(
        "audio-add",
        "audio-add <url> [flags] [title] [lang]",
        "Add external audio track.",
        CompletionItemKind.Keyword,
      ),
      createDef(
        "video-add",
        "video-add <url> [flags] [title] [lang]",
        "Add external video track.",
        CompletionItemKind.Keyword,
      ),
      createDef("sub-remove", "sub-remove [id]", "Remove subtitle track.", CompletionItemKind.Keyword),
      createDef("audio-remove", "audio-remove [id]", "Remove audio track.", CompletionItemKind.Keyword),
      createDef("video-remove", "video-remove [id]", "Remove video track.", CompletionItemKind.Keyword),
      createDef("sub-reload", "sub-reload [id]", "Reload subtitle track.", CompletionItemKind.Keyword),
      createDef("audio-reload", "audio-reload [id]", "Reload audio track.", CompletionItemKind.Keyword),
      createDef("video-reload", "video-reload [id]", "Reload video track.", CompletionItemKind.Keyword),
      createDef("set", "set <property> <value>", "Set property.", CompletionItemKind.Keyword),
      createDef("set-string", "set-string <property> <value>", "Set property as string.", CompletionItemKind.Keyword),
      createDef(
        "change-list",
        "change-list <property> <operation> <value>",
        "Modify list property.",
        CompletionItemKind.Keyword,
      ),
      createDef("add", "add <property> [value]", "Add number to property.", CompletionItemKind.Keyword),
      createDef("cycle", "cycle <property> [up|down]", "Cycle property value.", CompletionItemKind.Keyword),
      createDef(
        "cycle-values",
        "cycle-values <property> <value1> <value2> ...",
        "Cycle fixed values.",
        CompletionItemKind.Keyword,
      ),
      createDef("multiply", "multiply <property> <factor>", "Multiply numeric property.", CompletionItemKind.Keyword),
      createDef("expand-text", "expand-text <text>", "Expand property expressions.", CompletionItemKind.Keyword),
      createDef("show-text", "show-text <text> [duration] [level]", "Show text on OSD.", CompletionItemKind.Keyword),
      createDef("show-progress", "show-progress", "Show playback progress OSD.", CompletionItemKind.Keyword),
      createDef(
        "show-property-text",
        "show-property-text <text> [duration] [level]",
        "Show expanded property text.",
        CompletionItemKind.Keyword,
      ),
      createDef("screenshot", "screenshot [flags]", "Take screenshot.", CompletionItemKind.Keyword),
      createDef(
        "screenshot-to-file",
        "screenshot-to-file <filename> [flags]",
        "Save screenshot to file.",
        CompletionItemKind.Keyword,
      ),
      createDef(
        "screenshot-raw",
        "screenshot-raw [flags]",
        "Return screenshot image data.",
        CompletionItemKind.Keyword,
      ),
      createDef(
        "write-watch-later-config",
        "write-watch-later-config",
        "Write watch-later resume file.",
        CompletionItemKind.Keyword,
      ),
      createDef(
        "delete-watch-later-config",
        "delete-watch-later-config [filename]",
        "Delete watch-later config.",
        CompletionItemKind.Keyword,
      ),
      createDef("stop", "stop", "Stop playback.", CompletionItemKind.Keyword),
      createDef("quit", "quit [code]", "Quit mpv.", CompletionItemKind.Keyword),
      createDef(
        "quit-watch-later",
        "quit-watch-later [code]",
        "Quit and save playback position.",
        CompletionItemKind.Keyword,
      ),
      createDef(
        "script-message",
        "script-message <arg1> <arg2> ...",
        "Send message to scripts.",
        CompletionItemKind.Keyword,
      ),
      createDef(
        "script-message-to",
        "script-message-to <target> <arg1> ...",
        "Send message to target script.",
        CompletionItemKind.Keyword,
      ),
      createDef("script-binding", "script-binding <name>", "Invoke script key binding.", CompletionItemKind.Keyword),
      createDef("keypress", "keypress <name>", "Simulate key press.", CompletionItemKind.Keyword),
      createDef("keydown", "keydown <name>", "Simulate key down.", CompletionItemKind.Keyword),
      createDef("keyup", "keyup [name]", "Simulate key up.", CompletionItemKind.Keyword),
      createDef("run", "run <command> [args...]", "Run external command.", CompletionItemKind.Keyword),
      createDef("subprocess", "subprocess", "Run subprocess command using native args.", CompletionItemKind.Keyword),
      createDef("async", "async <command>", "Run command asynchronously.", CompletionItemKind.Keyword),
      createDef("ignore", "ignore", "Do nothing.", CompletionItemKind.Keyword),
      createDef("no-osd", "no-osd <command>", "Run command without OSD.", CompletionItemKind.Keyword),
      createDef("osd-bar", "osd-bar <command>", "Run command with OSD bar.", CompletionItemKind.Keyword),
      createDef("osd-msg", "osd-msg <command>", "Run command with OSD message.", CompletionItemKind.Keyword),
      createDef(
        "osd-msg-bar",
        "osd-msg-bar <command>",
        "Run command with OSD message and bar.",
        CompletionItemKind.Keyword,
      ),
      createDef("raw", "raw <command>", "Disable argument/property expansion.", CompletionItemKind.Keyword),
      createDef(
        "expand-properties",
        "expand-properties <command>",
        "Enable property expansion.",
        CompletionItemKind.Keyword,
      ),
      createDef("repeatable", "repeatable <command>", "Allow repeated key handling.", CompletionItemKind.Keyword),
      createDef("vf", "vf <operation> <filter>", "Modify video filter chain.", CompletionItemKind.Keyword),
      createDef("af", "af <operation> <filter>", "Modify audio filter chain.", CompletionItemKind.Keyword),
      createDef(
        "vf-command",
        "vf-command <label> <command> <argument>",
        "Send command to video filter.",
        CompletionItemKind.Keyword,
      ),
      createDef(
        "af-command",
        "af-command <label> <command> <argument>",
        "Send command to audio filter.",
        CompletionItemKind.Keyword,
      ),
      createDef("apply-profile", "apply-profile <name> [mode]", "Apply mpv profile.", CompletionItemKind.Keyword),
      createDef("load-script", "load-script <filename>", "Load script at runtime.", CompletionItemKind.Keyword),
      createDef(
        "load-input-conf",
        "load-input-conf <filename>",
        "Load input.conf at runtime.",
        CompletionItemKind.Keyword,
      ),
      createDef(
        "rescan-external-files",
        "rescan-external-files [mode]",
        "Rescan external subtitles/audio.",
        CompletionItemKind.Keyword,
      ),
      createDef("discnav", "discnav <command>", "DVD/Blu-ray navigation command.", CompletionItemKind.Keyword),
      createDef("mouse", "mouse <x> <y> [button] [mode]", "Send mouse event.", CompletionItemKind.Keyword),
      createDef(
        "overlay-add",
        "overlay-add <id> <x> <y> <file> <offset> <fmt> <w> <h> <stride>",
        "Add raw overlay.",
        CompletionItemKind.Keyword,
      ),
      createDef("overlay-remove", "overlay-remove <id>", "Remove raw overlay.", CompletionItemKind.Keyword),
      // ============================================================
      // Very useful properties for autocomplete
      // ============================================================
      createDef("pause", "pause", "Boolean pause state.", CompletionItemKind.Property),
      createDef("core-idle", "core-idle", "Whether playback core is idle.", CompletionItemKind.Property),
      createDef("idle-active", "idle-active", "Whether idle mode is active.", CompletionItemKind.Property),
      createDef("eof-reached", "eof-reached", "End of file reached.", CompletionItemKind.Property),
      createDef("seeking", "seeking", "Whether currently seeking.", CompletionItemKind.Property),
      createDef("playback-time", "playback-time", "Current playback time.", CompletionItemKind.Property),
      createDef("time-pos", "time-pos", "Current time position.", CompletionItemKind.Property),
      createDef("time-remaining", "time-remaining", "Remaining time.", CompletionItemKind.Property),
      createDef("duration", "duration", "Media duration.", CompletionItemKind.Property),
      createDef("percent-pos", "percent-pos", "Playback percentage.", CompletionItemKind.Property),
      createDef("estimated-vf-fps", "estimated-vf-fps", "Estimated video FPS.", CompletionItemKind.Property),
      createDef("speed", "speed", "Playback speed.", CompletionItemKind.Property),
      createDef("filename", "filename", "Current filename.", CompletionItemKind.Property),
      createDef(
        "filename/no-ext",
        "filename/no-ext",
        "Current filename without extension.",
        CompletionItemKind.Property,
      ),
      createDef("file-size", "file-size", "Current file size.", CompletionItemKind.Property),
      createDef("path", "path", "Current path or URL.", CompletionItemKind.Property),
      createDef("media-title", "media-title", "Media title.", CompletionItemKind.Property),
      createDef(
        "stream-open-filename",
        "stream-open-filename",
        "Opened stream filename.",
        CompletionItemKind.Property,
      ),
      createDef("file-format", "file-format", "Detected container format.", CompletionItemKind.Property),
      createDef("volume", "volume", "Audio volume.", CompletionItemKind.Property),
      createDef("volume-max", "volume-max", "Maximum volume.", CompletionItemKind.Property),
      createDef("mute", "mute", "Mute state.", CompletionItemKind.Property),
      createDef("audio", "audio", "Selected audio track.", CompletionItemKind.Property),
      createDef("aid", "aid", "Selected audio track ID.", CompletionItemKind.Property),
      createDef("audio-codec", "audio-codec", "Current audio codec.", CompletionItemKind.Property),
      createDef("audio-codec-name", "audio-codec-name", "Audio codec name.", CompletionItemKind.Property),
      createDef("audio-params", "audio-params", "Current audio parameters.", CompletionItemKind.Property),
      createDef("audio-out-params", "audio-out-params", "Audio output parameters.", CompletionItemKind.Property),
      createDef("audio-device", "audio-device", "Selected audio output device.", CompletionItemKind.Property),
      createDef("audio-delay", "audio-delay", "Audio delay.", CompletionItemKind.Property),
      createDef(
        "audio-speed-correction",
        "audio-speed-correction",
        "Audio speed correction.",
        CompletionItemKind.Property,
      ),
      createDef("video", "video", "Selected video track.", CompletionItemKind.Property),
      createDef("vid", "vid", "Selected video track ID.", CompletionItemKind.Property),
      createDef("video-codec", "video-codec", "Current video codec.", CompletionItemKind.Property),
      createDef("video-codec-name", "video-codec-name", "Video codec name.", CompletionItemKind.Property),
      createDef("video-params", "video-params", "Video parameters.", CompletionItemKind.Property),
      createDef("dwidth", "dwidth", "Display width.", CompletionItemKind.Property),
      createDef("dheight", "dheight", "Display height.", CompletionItemKind.Property),
      createDef("width", "width", "Video width.", CompletionItemKind.Property),
      createDef("height", "height", "Video height.", CompletionItemKind.Property),
      createDef("fps", "fps", "Video FPS.", CompletionItemKind.Property),
      createDef("container-fps", "container-fps", "Container FPS.", CompletionItemKind.Property),
      createDef(
        "video-speed-correction",
        "video-speed-correction",
        "Video speed correction.",
        CompletionItemKind.Property,
      ),
      createDef(
        "estimated-display-fps",
        "estimated-display-fps",
        "Estimated display FPS.",
        CompletionItemKind.Property,
      ),
      createDef("display-fps", "display-fps", "Display FPS.", CompletionItemKind.Property),
      createDef("hwdec", "hwdec", "Hardware decoding mode.", CompletionItemKind.Property),
      createDef("hwdec-current", "hwdec-current", "Current active hardware decoder.", CompletionItemKind.Property),
      createDef("sub", "sub", "Selected subtitle track.", CompletionItemKind.Property),
      createDef("sid", "sid", "Selected subtitle track ID.", CompletionItemKind.Property),
      createDef("secondary-sid", "secondary-sid", "Secondary subtitle track.", CompletionItemKind.Property),
      createDef("sub-delay", "sub-delay", "Subtitle delay.", CompletionItemKind.Property),
      createDef("sub-pos", "sub-pos", "Subtitle position.", CompletionItemKind.Property),
      createDef("sub-scale", "sub-scale", "Subtitle scale.", CompletionItemKind.Property),
      createDef("sub-visibility", "sub-visibility", "Subtitle visibility.", CompletionItemKind.Property),
      createDef("sub-text", "sub-text", "Current subtitle text.", CompletionItemKind.Property),
      createDef("fullscreen", "fullscreen", "Fullscreen state.", CompletionItemKind.Property),
      createDef("ontop", "ontop", "Window always-on-top.", CompletionItemKind.Property),
      createDef("border", "border", "Window border.", CompletionItemKind.Property),
      createDef("window-minimized", "window-minimized", "Window minimized state.", CompletionItemKind.Property),
      createDef("window-maximized", "window-maximized", "Window maximized state.", CompletionItemKind.Property),
      createDef("window-scale", "window-scale", "Window scale.", CompletionItemKind.Property),
      createDef("osd-level", "osd-level", "OSD level.", CompletionItemKind.Property),
      createDef("osd-width", "osd-width", "OSD width.", CompletionItemKind.Property),
      createDef("osd-height", "osd-height", "OSD height.", CompletionItemKind.Property),
      createDef("osd-par", "osd-par", "OSD pixel aspect ratio.", CompletionItemKind.Property),
      createDef("playlist", "playlist", "Playlist native array.", CompletionItemKind.Property),
      createDef("playlist-count", "playlist-count", "Playlist item count.", CompletionItemKind.Property),
      createDef("playlist-pos", "playlist-pos", "Current playlist position.", CompletionItemKind.Property),
      createDef(
        "playlist-pos-1",
        "playlist-pos-1",
        "Current playlist position, 1-based.",
        CompletionItemKind.Property,
      ),
      createDef(
        "playlist-playing-pos",
        "playlist-playing-pos",
        "Current playing playlist position.",
        CompletionItemKind.Property,
      ),
      createDef(
        "playlist-current-pos",
        "playlist-current-pos",
        "Current playlist position.",
        CompletionItemKind.Property,
      ),
      createDef(
        "playlist/N/filename",
        "playlist/N/filename",
        "Playlist entry filename.",
        CompletionItemKind.Property,
      ),
      createDef(
        "playlist/N/current",
        "playlist/N/current",
        "Whether playlist entry is current.",
        CompletionItemKind.Property,
      ),
      createDef(
        "playlist/N/playing",
        "playlist/N/playing",
        "Whether playlist entry is playing.",
        CompletionItemKind.Property,
      ),
      createDef("track-list", "track-list", "Native list of tracks.", CompletionItemKind.Property),
      createDef("track-list/count", "track-list/count", "Track count.", CompletionItemKind.Property),
      createDef("track-list/N/id", "track-list/N/id", "Track ID.", CompletionItemKind.Property),
      createDef("track-list/N/type", "track-list/N/type", "Track type.", CompletionItemKind.Property),
      createDef("track-list/N/src-id", "track-list/N/src-id", "Source track ID.", CompletionItemKind.Property),
      createDef("track-list/N/title", "track-list/N/title", "Track title.", CompletionItemKind.Property),
      createDef("track-list/N/lang", "track-list/N/lang", "Track language.", CompletionItemKind.Property),
      createDef(
        "track-list/N/selected",
        "track-list/N/selected",
        "Whether track is selected.",
        CompletionItemKind.Property,
      ),
      createDef(
        "track-list/N/external",
        "track-list/N/external",
        "Whether track is external.",
        CompletionItemKind.Property,
      ),
      createDef("track-list/N/codec", "track-list/N/codec", "Track codec.", CompletionItemKind.Property),
      createDef("chapter", "chapter", "Current chapter index.", CompletionItemKind.Property),
      createDef("chapters", "chapters", "Number of chapters.", CompletionItemKind.Property),
      createDef("chapter-list", "chapter-list", "Native chapter list.", CompletionItemKind.Property),
      createDef("chapter-metadata", "chapter-metadata", "Current chapter metadata.", CompletionItemKind.Property),
      createDef("edition", "edition", "Current edition.", CompletionItemKind.Property),
      createDef("editions", "editions", "Number of editions.", CompletionItemKind.Property),
      createDef("edition-list", "edition-list", "Native edition list.", CompletionItemKind.Property),
      createDef("metadata", "metadata", "File metadata.", CompletionItemKind.Property),
      createDef("filtered-metadata", "filtered-metadata", "Filtered file metadata.", CompletionItemKind.Property),
      createDef("vf", "vf", "Video filter list property.", CompletionItemKind.Property),
      createDef("af", "af", "Audio filter list property.", CompletionItemKind.Property),
      createDef("options", "options", "Runtime options map.", CompletionItemKind.Property),
      createDef("file-local-options", "file-local-options", "File-local options map.", CompletionItemKind.Property),
      createDef("option-info", "option-info/<name>", "Runtime option info.", CompletionItemKind.Property),
      createDef("property-list", "property-list", "List of top-level properties.", CompletionItemKind.Property),
      createDef("command-list", "command-list", "List of input commands.", CompletionItemKind.Property),
      createDef("input-bindings", "input-bindings", "List of active input bindings.", CompletionItemKind.Property),
      createDef("profile-list", "profile-list", "List of profiles.", CompletionItemKind.Property),
      createDef("cache-speed", "cache-speed", "Cache fill rate (bytes/sec).", CompletionItemKind.Property),
      createDef("cache-duration", "cache-duration", "Cache duration.", CompletionItemKind.Property),
      createDef("cache-used", "cache-used", "Used cache bytes.", CompletionItemKind.Property),
      createDef(
        "demuxer-cache-duration",
        "demuxer-cache-duration",
        "Demuxer cache duration.",
        CompletionItemKind.Property,
      ),
      createDef("demuxer-cache-idle", "demuxer-cache-idle", "Demuxer cache is idle.", CompletionItemKind.Property),
      createDef("demuxer-via-network", "demuxer-via-network", "Demuxer via network.", CompletionItemKind.Property),
      createDef("audio-device-list", "audio-device-list", "Available audio devices.", CompletionItemKind.Property),
      createDef("audio-bitrate", "audio-bitrate", "Audio bitrate.", CompletionItemKind.Property),
      createDef("video-bitrate", "video-bitrate", "Video bitrate.", CompletionItemKind.Property),
      createDef("display-names", "display-names", "Available display names.", CompletionItemKind.Property),
      createDef("display-width", "display-width", "Display width.", CompletionItemKind.Property),
      createDef("display-height", "display-height", "Display height.", CompletionItemKind.Property),
      createDef("play-dir", "play-dir", "Playback direction (1/-1).", CompletionItemKind.Property),
      createDef("drop-frame-count", "drop-frame-count", "Dropped frame count.", CompletionItemKind.Property),
      createDef("avsync", "avsync", "Audio/video sync difference.", CompletionItemKind.Property),
      createDef("display-sync-active", "display-sync-active", "Display sync active.", CompletionItemKind.Property),
      createDef("toggle", "toggle <property>", "Toggle boolean property.", CompletionItemKind.Keyword),
      createDef("speed-multiply", "speed-multiply <factor>", "Multiply playback speed.", CompletionItemKind.Keyword),
      createDef("speed-set", "speed-set <speed>", "Set absolute speed.", CompletionItemKind.Keyword),
      createDef("ab-loop", "ab-loop", "Toggle A-B loop.", CompletionItemKind.Keyword),
      // ============================================================
      // Common JS / MuJS globals
      // ============================================================
      createDef("setTimeout", "setTimeout(fn, ms)", "Run function after delay."),
      createDef("clearTimeout", "clearTimeout(id)", "Cancel timeout."),
      createDef("setInterval", "setInterval(fn, ms)", "Run function periodically."),
      createDef("clearInterval", "clearInterval(id)", "Cancel interval."),
      createDef("print", "print(...)", "Alias for mp.msg.info."),
      createDef("dump", "dump(obj)", "Dump object/array recursively."),
      createDef("exit", "exit()", "Exit current script."),
      createDef("require", "require(module)", "Load CommonJS module."),
      createDef("JSON", "JSON", "JSON object.", CompletionItemKind.Variable),
      createDef("JSON.parse", "JSON.parse(str)", "Parse JSON string."),
      createDef("JSON.stringify", "JSON.stringify(value)", "Convert value to JSON string."),
      createDef("Math", "Math", "Math object.", CompletionItemKind.Variable),
      createDef("Array", "Array", "Array constructor.", CompletionItemKind.Class),
      createDef("String", "String", "String constructor.", CompletionItemKind.Class),
      createDef("Number", "Number", "Number constructor.", CompletionItemKind.Class),
      createDef("Boolean", "Boolean", "Boolean constructor.", CompletionItemKind.Class),
      createDef("Object", "Object", "Object constructor.", CompletionItemKind.Class),
      createDef("Date", "Date", "Date constructor.", CompletionItemKind.Class),
      createDef("RegExp", "RegExp", "RegExp constructor.", CompletionItemKind.Class),
      createDef("Error", "Error", "Error constructor.", CompletionItemKind.Class),
      createDef("Error.stack", "Error.stack", "Stack trace string.", CompletionItemKind.Property),
    )
}
