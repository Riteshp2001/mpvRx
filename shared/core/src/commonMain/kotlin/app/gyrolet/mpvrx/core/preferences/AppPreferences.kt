package app.gyrolet.mpvrx.core.preferences

/** Keys match the Android app so a future shared settings import/export stays compatible. */

enum class DarkMode(val title: String) {
  Dark("Dark"),
  Light("Light"),
  System("Follow system"),
}

enum class ResumePlaybackMode(val title: String, val summary: String) {
  Always("Always", "Resume every video from where you left off"),
  MinimumDuration("Long videos only", "Resume only videos longer than the minimum duration"),
  Never("Never", "Always start from the beginning"),
}

enum class VideoAspect(val title: String) {
  Crop("Crop"),
  Fit("Fit"),
  Stretch("Stretch"),
}

enum class RepeatMode {
  OFF,
  ONE,
  ALL,
}

enum class SeekbarStyle {
  Standard,
  Thick,
  Slim,
}

enum class FolderSortOrder(val title: String) {
  Name("Name"),
  RecentlyModified("Recently modified"),
  Size("Size"),
  Count("Item count"),
}

enum class VideoSortOrder(val title: String) {
  Name("Name"),
  RecentlyModified("Recently modified"),
  Size("Size"),
  Duration("Duration"),
}

class AppearancePreferences(store: PreferenceStore) {
  val darkMode = store.getEnum("dark_mode", DarkMode.System)

  /** Stored by enum name; resolved against the UI theme catalogue. */
  val appTheme = store.getString("app_theme", "Default")
  val amoledMode = store.getBoolean("amoled_mode", false)
  val useSystemFont = store.getBoolean("use_system_font", false)
  val appUiScale = store.getFloat("app_ui_scale", 1f)
  val unlimitedNameLines = store.getBoolean("unlimited_name_lines", false)
  val seekbarStyle = store.getEnum("seekbar_style", SeekbarStyle.Thick)
  val showMusicTab = store.getBoolean("show_music_tab", true)
  val showNetworkTab = store.getBoolean("show_network_tab", true)
  val hidePlayerButtonsBackground = store.getBoolean("hide_player_buttons_background", false)
}

class PlayerPreferences(store: PreferenceStore) {
  val resumePlaybackMode = store.getEnum("resume_playback_mode", ResumePlaybackMode.Always)
  val minimumResumeDurationSeconds = store.getInt("resume_minimum_video_duration_seconds", 100)
  val savePositionOnQuit = store.getBoolean("save_position", true)
  val invertDuration = store.getBoolean("invert_duration", true)
  val defaultSpeed = store.getFloat("default_speed", 1f)
  val speedPresets =
    store.getStringSet(
      "default_speed_presets",
      setOf("0.25", "0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0", "2.5", "3.0"),
    )
  val showBufferedRange = store.getBoolean("show_buffered_range", true)
  val showChapterIndicators = store.getBoolean("show_chapter_indicators", true)
  val lastVideoAspect = store.getEnum("last_video_aspect", VideoAspect.Fit)
  val playerTimeToDisappear = store.getInt("player_time_to_disappear", 4000)
  val doubleTapSeekDuration = store.getInt("double_tap_seek_duration", 10)
  val customSkipDuration = store.getInt("custom_skip_duration", 90)
  val playlistMode = store.getBoolean("playlist_mode", true)
  val autoplayNextVideo = store.getBoolean("autoplay_next_video", true)
  val autoplayNextAudio = store.getBoolean("autoplay_next_audio", true)
  val repeatMode = store.getEnum("repeat_mode", RepeatMode.OFF)
  val shuffleEnabled = store.getBoolean("shuffle_enabled", false)
  val closeAfterReachingEndOfVideo = store.getBoolean("close_after_eof", false)
  val rememberVolume = store.getBoolean("remember_volume", true)
  val lastVolume = store.getInt("last_volume", 100)
  val showLoadingCircle = store.getBoolean("show_loading_circle", true)
  val alwaysOnTopWhilePlaying = store.getBoolean("always_on_top_while_playing", false)
  val openInFullscreen = store.getBoolean("open_in_fullscreen", false)
}

class DecoderPreferences(store: PreferenceStore) {
  val tryHWDecoding = store.getBoolean("try_hw_dec", true)
  val profile = store.getString("mpv_profile", "fast")
  val gpuNext = store.getBoolean("gpu_next", false)
  val brightnessFilter = store.getInt("filter_brightness")
  val saturationFilter = store.getInt("filter_saturation")
  val gammaFilter = store.getInt("filter_gamma")
  val contrastFilter = store.getInt("filter_contrast")
  val hueFilter = store.getInt("filter_hue")
}

class SubtitlesPreferences(store: PreferenceStore) {
  val preferredLanguages = store.getString("sub_preferred_languages")
  val autoloadMatchingSubtitles = store.getBoolean("sub_autoload_enabled", true)
  val autoEnableSubtitles = store.getBoolean("sub_auto_enable", true)
  val font = store.getString("sub_font", "")
  val fontSize = store.getInt("sub_font_size", 55)
  val subScale = store.getFloat("sub_scale", 1f)
  val borderSize = store.getInt("sub_border_size", 3)
  val bold = store.getBoolean("sub_bold", false)
  val italic = store.getBoolean("sub_italic", false)
  val textColor = store.getInt("sub_color_text", 0xFFFFFFFF.toInt())
  val borderColor = store.getInt("sub_color_border", 0xFF000000.toInt())
  val subPos = store.getInt("sub_pos", 100)
  val overrideAssSubs = store.getBoolean("sub_override_ass")
  val defaultSubDelay = store.getInt("sub_default_delay")
}

class AudioPreferences(store: PreferenceStore) {
  val preferredLanguages = store.getString("audio_preferred_languages")
  val volumeBoostCap = store.getInt("audio_volume_boost_cap", 30)
  val audioPitchCorrection = store.getBoolean("audio_pitch_correction", true)
  val defaultAudioDelay = store.getInt("audio_default_delay")
}

class BrowserPreferences(store: PreferenceStore) {
  /** Absolute folder paths scanned for media; empty means "use platform defaults". */
  val libraryFolders = store.getStringSet("library_folders", emptySet())
  val folderSortOrder = store.getEnum("folder_sort_order", FolderSortOrder.Name)
  val folderSortAscending = store.getBoolean("folder_sort_ascending", true)
  val videoSortOrder = store.getEnum("video_sort_order", VideoSortOrder.Name)
  val videoSortAscending = store.getBoolean("video_sort_ascending", true)
  val gridLayout = store.getBoolean("browser_grid_layout", false)
  val showHiddenFiles = store.getBoolean("show_hidden_files", false)
  val showThumbnails = store.getBoolean("show_video_thumbnails", true)
  val recentNetworkUrls = store.getObject(
    key = "recent_network_urls",
    defaultValue = emptyList<String>(),
    serializer = { it.joinToString("\n") },
    deserializer = { raw -> raw.lines().filter(String::isNotBlank) },
  )
}

class AdvancedPreferences(store: PreferenceStore) {
  /** Raw mpv.conf contents applied before initialization. */
  val mpvConf = store.getString("mpv_conf", "")
  val verboseLogging = store.getBoolean("verbose_logging", false)
}

/** One object per platform app; screens receive only the group they need. */
class AppSettings(store: PreferenceStore) {
  val appearance = AppearancePreferences(store)
  val player = PlayerPreferences(store)
  val decoder = DecoderPreferences(store)
  val subtitles = SubtitlesPreferences(store)
  val audio = AudioPreferences(store)
  val browser = BrowserPreferences(store)
  val advanced = AdvancedPreferences(store)
}
