package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.core.preferences.DarkMode
import app.gyrolet.mpvrx.core.preferences.ResumePlaybackMode
import app.gyrolet.mpvrx.core.preferences.SeekbarStyle
import app.gyrolet.mpvrx.core.util.formatDuration
import app.gyrolet.mpvrx.ui.browser.LibraryViewModel
import app.gyrolet.mpvrx.ui.components.FilledIconCircle
import app.gyrolet.mpvrx.ui.components.ScreenHeader
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.navigation.LocalNavigator
import app.gyrolet.mpvrx.ui.navigation.Screen
import app.gyrolet.mpvrx.ui.navigation.SettingsPage
import app.gyrolet.mpvrx.ui.platform.LocalAppDependencies
import app.gyrolet.mpvrx.ui.theme.AppTheme
import app.gyrolet.mpvrx.ui.util.collectAsState
import kotlinx.coroutines.launch

private fun SettingsPage.icon() =
  when (this) {
    SettingsPage.Appearance -> Icons.Palette
    SettingsPage.Player -> Icons.PlayCircle
    SettingsPage.Decoder -> Icons.Memory
    SettingsPage.Subtitles -> Icons.Subtitles
    SettingsPage.Audio -> Icons.Headphones
    SettingsPage.Library -> Icons.Folder
    SettingsPage.Advanced -> Icons.Code
    SettingsPage.Shortcuts -> Icons.Terminal
    SettingsPage.About -> Icons.Info
  }

private fun SettingsPage.summary() =
  when (this) {
    SettingsPage.Appearance -> "Theme, palette, dark mode"
    SettingsPage.Player -> "Resume, seeking, controls, autoplay"
    SettingsPage.Decoder -> "Hardware decoding, video filters"
    SettingsPage.Subtitles -> "Languages, font, colours, position"
    SettingsPage.Audio -> "Languages, volume boost, pitch"
    SettingsPage.Library -> "Folders scanned for media"
    SettingsPage.Advanced -> "mpv.conf, config folder"
    SettingsPage.Shortcuts -> "Player keyboard and mouse controls"
    SettingsPage.About -> "Version and engine information"
  }

/** Profile tab: the Android app keeps settings and history under the profile destination. */
@Composable
fun ProfileScreen(bottomPadding: PaddingValues) {
  val deps = LocalAppDependencies.current
  val navigator = LocalNavigator.current
  val history by deps.history.records.collectAsState()
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottomPadding.calculateBottomPadding() + 16.dp)) {
    ScreenHeader("Profile")
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
      ) { Icon(Icons.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp)) }
      Spacer(Modifier.width(20.dp))
      Column {
        Text("mpvRx for ${deps.platform.platformName}", style = MaterialTheme.typography.titleLarge)
        val watched = history.count { it.isFinished }
        val total = history.sumOf { it.positionSeconds }
        Text(
          "${history.size} played · $watched finished · ${formatDuration(total)} watched",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    PreferenceGroup(null) {
      PreferenceItem("History", summary = "Recently played media", icon = Icons.History, onClick = { navigator.push(Screen.History) })
    }
    PreferenceGroup("Settings") {
      SettingsPage.entries.forEach { page ->
        PreferenceItem(page.title, summary = page.summary(), icon = page.icon(), onClick = { navigator.push(Screen.Settings(page)) })
      }
    }
  }
}

@Composable
fun SettingsPageScreen(
  page: SettingsPage,
  library: LibraryViewModel,
) {
  val navigator = LocalNavigator.current
  Column(Modifier.fillMaxSize()) {
    ScreenHeader(page.title, onBack = { navigator.pop() })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
      Column(Modifier.widthIn(max = 920.dp).align(Alignment.CenterHorizontally)) {
        when (page) {
          SettingsPage.Appearance -> AppearanceSettings()
          SettingsPage.Player -> PlayerSettings()
          SettingsPage.Decoder -> DecoderSettings()
          SettingsPage.Subtitles -> SubtitleSettings()
          SettingsPage.Audio -> AudioSettings()
          SettingsPage.Library -> LibrarySettings(library)
          SettingsPage.Advanced -> AdvancedSettings()
          SettingsPage.Shortcuts -> ShortcutsPage()
          SettingsPage.About -> AboutPage()
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSettings() {
  val prefs = LocalAppDependencies.current.settings.appearance
  val selectedTheme by prefs.appTheme.collectAsState()
  PreferenceGroup("Theme") {
    ListPreference(prefs.darkMode, "Dark mode", DarkMode.entries, { it.title })
    SwitchPreference(prefs.amoledMode, "Pure black dark mode", "Use true black backgrounds in dark mode")
  }
  PreferenceGroup("Palette") {
    FlowRow(
      Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      AppTheme.entries.forEach { theme ->
        val selected = theme.name == selectedTheme
        Column(
          Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable { prefs.appTheme.set(theme.name) }
            .padding(6.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Box(
            Modifier
              .size(76.dp)
              .clip(RoundedCornerShape(20.dp))
              .background(theme.backgroundDark)
              .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(20.dp),
              ),
            contentAlignment = Alignment.Center,
          ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              listOf(theme.primaryDark, theme.secondaryDark, theme.tertiaryDark).forEach { color ->
                Box(Modifier.size(14.dp).clip(CircleShape).background(color))
              }
            }
            if (selected) {
              Icon(
                Icons.Check,
                null,
                tint = theme.primaryDark,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp),
              )
            }
          }
          Spacer(Modifier.height(6.dp))
          Text(theme.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
  }
  PreferenceGroup("Interface") {
    SwitchPreference(prefs.useSystemFont, "Use system font", "Replace Google Sans Flex with the platform font")
    SwitchPreference(prefs.unlimitedNameLines, "Show full file names", "Do not truncate long names in lists")
    ListPreference(prefs.seekbarStyle, "Seekbar style", SeekbarStyle.entries, { it.name })
    SwitchPreference(prefs.hidePlayerButtonsBackground, "Hide player button backgrounds")
    SwitchPreference(prefs.showMusicTab, "Show Music tab")
    SwitchPreference(prefs.showNetworkTab, "Show Network tab")
  }
}

@Composable
private fun PlayerSettings() {
  val prefs = LocalAppDependencies.current.settings.player
  val resumeMode by prefs.resumePlaybackMode.collectAsState()
  PreferenceGroup("Resume") {
    ListPreference(prefs.resumePlaybackMode, "Resume playback", ResumePlaybackMode.entries, { it.title }, { it.summary })
    if (resumeMode == ResumePlaybackMode.MinimumDuration) {
      IntSliderPreference(prefs.minimumResumeDurationSeconds, "Minimum duration", 10..1800, 10) { formatDuration(it.toDouble()) }
    }
    SwitchPreference(prefs.savePositionOnQuit, "Save position on quit")
  }
  PreferenceGroup("Controls") {
    IntSliderPreference(prefs.doubleTapSeekDuration, "Seek step (arrow keys)", 1..60) { "${it}s" }
    IntSliderPreference(prefs.customSkipDuration, "Skip button duration", 10..300, 5) { "${it}s" }
    IntSliderPreference(prefs.playerTimeToDisappear, "Hide controls after", 1000..10000, 500) { "${it / 1000.0}s" }
    SwitchPreference(prefs.invertDuration, "Show remaining time", "Display time left instead of total duration")
    SwitchPreference(prefs.showChapterIndicators, "Chapter markers on seekbar")
    SwitchPreference(prefs.showBufferedRange, "Show buffered range")
    SwitchPreference(prefs.showLoadingCircle, "Show loading indicator")
  }
  PreferenceGroup("Playback") {
    SwitchPreference(prefs.playlistMode, "Playlist mode", "Queue the rest of the folder when opening a file")
    SwitchPreference(prefs.autoplayNextVideo, "Autoplay next video")
    SwitchPreference(prefs.autoplayNextAudio, "Autoplay next audio")
    SwitchPreference(prefs.closeAfterReachingEndOfVideo, "Close player at end", "Leave the player when nothing else is queued")
    SwitchPreference(prefs.rememberVolume, "Remember volume")
    val speed by prefs.defaultSpeed.collectAsState()
    SliderPreference("Default speed", speed, { prefs.defaultSpeed.set((it * 20).toInt() / 20f) }, 0.25f..4f, valueLabel = { "${(it * 20).toInt() / 20f}x" })
  }
  PreferenceGroup("Window") {
    SwitchPreference(prefs.openInFullscreen, "Open player in fullscreen")
    SwitchPreference(prefs.alwaysOnTopWhilePlaying, "Keep window on top while playing")
  }
}

@Composable
private fun DecoderSettings() {
  val prefs = LocalAppDependencies.current.settings.decoder
  PreferenceGroup("Decoding") {
    SwitchPreference(prefs.tryHWDecoding, "Hardware decoding", "Use D3D11VA / NVDEC / VA-API when available (auto-safe)")
  }
  PreferenceGroup("Video filters") {
    IntSliderPreference(prefs.brightnessFilter, "Brightness", -100..100)
    IntSliderPreference(prefs.contrastFilter, "Contrast", -100..100)
    IntSliderPreference(prefs.saturationFilter, "Saturation", -100..100)
    IntSliderPreference(prefs.gammaFilter, "Gamma", -100..100)
    IntSliderPreference(prefs.hueFilter, "Hue", -100..100)
  }
}

@Composable
private fun SubtitleSettings() {
  val prefs = LocalAppDependencies.current.settings.subtitles
  PreferenceGroup("General") {
    TextPreference(prefs.preferredLanguages, "Preferred languages", "Comma separated, e.g. en,eng,ja")
    SwitchPreference(prefs.autoloadMatchingSubtitles, "Autoload matching subtitles", "Load .srt/.ass files next to the video")
    SwitchPreference(prefs.autoEnableSubtitles, "Enable subtitles automatically")
    SwitchPreference(prefs.overrideAssSubs, "Override ASS styling", "Force the style below on styled subtitles")
  }
  PreferenceGroup("Style") {
    TextPreference(prefs.font, "Font", "Installed font family name; empty for default")
    IntSliderPreference(prefs.fontSize, "Font size", 20..120)
    IntSliderPreference(prefs.borderSize, "Border size", 0..10)
    SwitchPreference(prefs.bold, "Bold")
    SwitchPreference(prefs.italic, "Italic")
    ColorPreference(prefs.textColor, "Text colour")
    ColorPreference(prefs.borderColor, "Border colour")
    IntSliderPreference(prefs.subPos, "Vertical position", 0..150)
  }
}

@Composable
private fun AudioSettings() {
  val prefs = LocalAppDependencies.current.settings.audio
  PreferenceGroup("Audio") {
    TextPreference(prefs.preferredLanguages, "Preferred languages", "Comma separated, e.g. ja,jpn,en")
    IntSliderPreference(prefs.volumeBoostCap, "Volume boost", 0..200, 10) { "${100 + it}%" }
    SwitchPreference(prefs.audioPitchCorrection, "Pitch correction", "Keep voice pitch natural when changing speed")
    IntSliderPreference(prefs.defaultAudioDelay, "Default audio delay", -2000..2000, 50) { "${it}ms" }
  }
}

@Composable
private fun LibrarySettings(library: LibraryViewModel) {
  val deps = LocalAppDependencies.current
  val scope = rememberCoroutineScope()
  val folders by deps.settings.browser.libraryFolders.collectAsState()
  val effective = folders.toList().ifEmpty { deps.scanner.defaultLibraryRoots() }
  PreferenceGroup(if (folders.isEmpty()) "Default folders" else "Library folders") {
    effective.forEach { path ->
      PreferenceItem(
        title = path.substringAfterLast('\\').substringAfterLast('/').ifEmpty { path },
        summary = path,
        icon = Icons.Folder,
        trailing = {
          if (folders.isNotEmpty()) {
            IconButton(onClick = { library.removeFolder(path) }) { Icon(Icons.Delete, "Remove") }
          }
        },
      )
    }
  }
  Row(Modifier.padding(horizontal = 32.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Button(onClick = { scope.launch { deps.platform.pickFolder()?.let(library::addFolder) } }) {
      Icon(Icons.Add, null)
      Spacer(Modifier.width(8.dp))
      Text("Add folder")
    }
    FilledTonalButton(onClick = { library.refresh() }) { Text("Rescan") }
  }
  PreferenceGroup("Browsing") {
    SwitchPreference(deps.settings.browser.showHiddenFiles, "Show hidden files")
    SwitchPreference(deps.settings.browser.showThumbnails, "Video thumbnails")
  }
}

@Composable
private fun AdvancedSettings() {
  val deps = LocalAppDependencies.current
  val conf by deps.settings.advanced.mpvConf.collectAsState()
  var draft by remember(conf) { mutableStateOf(conf) }
  PreferenceGroup("mpv.conf") {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp)) {
      Text(
        "Options applied when the player starts. Restart the app after editing.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(12.dp))
      OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
        placeholder = { Text("profile=gpu-hq\nscale=ewa_lanczossharp") },
      )
      Spacer(Modifier.height(12.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { deps.settings.advanced.mpvConf.set(draft) }, enabled = draft != conf) { Text("Save") }
        FilledTonalButton(onClick = { deps.platform.openConfigFolder() }) { Text("Open config folder") }
      }
    }
  }
  PreferenceGroup("Diagnostics") {
    SwitchPreference(deps.settings.advanced.verboseLogging, "Verbose mpv logging", "Write mpv logs to the config folder")
    PreferenceItem("Clear playback history", summary = "Removes resume positions and recents", onClick = { deps.history.clear() })
  }
}

private val ShortcutRows =
  listOf(
    "Space / K" to "Play / pause",
    "← / →" to "Seek backward / forward",
    "Shift + ← / →" to "Seek by 1 minute",
    "↑ / ↓ · Mouse wheel" to "Volume up / down",
    "M" to "Mute",
    "F · Double-click" to "Toggle fullscreen",
    "Esc" to "Exit fullscreen / close player",
    "N / P · PgDn / PgUp" to "Next / previous in queue",
    "[ / ] · Backspace" to "Speed down / up · reset",
    "J / Shift + J" to "Cycle subtitles / audio",
    "Z / X" to "Subtitle delay − / +",
    ". / ," to "Frame step forward / back",
    "L" to "A-B loop",
    "S" to "Screenshot",
    "A" to "Cycle aspect ratio",
    "T" to "Toggle always on top",
    "I" to "Picture-in-picture mini player",
    "0 – 9" to "Jump to 0% – 90%",
  )

@Composable
private fun ShortcutsPage() {
  PreferenceGroup("Player") {
    ShortcutRows.forEach { (keys, action) ->
      PreferenceItem(action, trailing = {
        Text(
          keys,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
          modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 10.dp, vertical = 4.dp),
        )
      })
    }
  }
}

@Composable
private fun AboutPage() {
  val platform = LocalAppDependencies.current.platform
  Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    FilledIconCircle(Icons.PlayCircle, "mpvRx", {}, size = 96.dp, container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
    Spacer(Modifier.height(16.dp))
    Text("mpvRx", style = MaterialTheme.typography.displaySmall)
    Text("Version ${platform.appVersion} · ${platform.platformName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  PreferenceGroup("Engine") {
    PreferenceItem("Playback engine", summary = platform.nativeEngineStatus, icon = Icons.Memory)
  }
  PreferenceGroup("Project") {
    PreferenceItem("Source code", summary = "github.com/Riteshp2001/mpvRx", icon = Icons.Code, onClick = { platform.openUrl("https://github.com/Riteshp2001/mpvRx") })
    PreferenceItem("License", summary = "AGPL-3.0-or-later", icon = Icons.Info)
  }
}

@Composable
private fun TextPreference(
  preference: app.gyrolet.mpvrx.core.preferences.Preference<String>,
  title: String,
  hint: String,
) {
  val value by preference.collectAsState()
  var draft by remember(value) { mutableStateOf(value) }
  Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 20.dp, vertical = 12.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
      value = draft,
      onValueChange = {
        draft = it
        preference.set(it.trim())
      },
      placeholder = { Text(hint) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

private val ColorSwatches =
  listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFEB3B, 0xFFFFC107, 0xFF4FC3F7, 0xFF81C784, 0xFFF48FB1, 0xFFE8B5EF, 0xFFFF5252, 0xFF9E9E9E,
  ).map { it.toInt() }

@Composable
private fun ColorPreference(
  preference: app.gyrolet.mpvrx.core.preferences.Preference<Int>,
  title: String,
) {
  val value by preference.collectAsState()
  Row(
    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 20.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      ColorSwatches.forEach { argb ->
        Box(
          Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(if (argb == value) 3.dp else 1.dp, if (argb == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
            .clickable { preference.set(argb) },
        )
      }
    }
  }
}
