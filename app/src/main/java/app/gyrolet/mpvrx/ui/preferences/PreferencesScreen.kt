package app.gyrolet.mpvrx.ui.preferences

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import org.koin.compose.koinInject
import androidx.compose.runtime.getValue
import app.gyrolet.mpvrx.exoplayer.settings.screens.medialibrary.ExoMediaLibraryPreferencesScreen
import app.gyrolet.mpvrx.exoplayer.settings.screens.player.ExoPlayerPreferencesScreen
import app.gyrolet.mpvrx.exoplayer.settings.screens.gesture.ExoGesturePreferencesScreen
import app.gyrolet.mpvrx.exoplayer.settings.screens.decoder.ExoDecoderPreferencesScreen
import app.gyrolet.mpvrx.exoplayer.settings.screens.audio.ExoAudioPreferencesScreen
import app.gyrolet.mpvrx.exoplayer.settings.screens.subtitle.ExoSubtitlePreferencesScreen
import app.gyrolet.mpvrx.exoplayer.settings.screens.general.ExoGeneralPreferencesScreen

@Serializable
object PreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val playerPreferences = koinInject<app.gyrolet.mpvrx.preferences.PlayerPreferences>()
    val enableExoPlayer by playerPreferences.enableExoPlayer.collectAsState()

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.pref_preferences),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            IconButton(onClick = { backstack.popSafely() }) {
              Icon(
                Icons.Outlined.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        LazyColumn(
          modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        ) {

          // ── Search bar ────────────────────────────────────────────────────
          item {
            Surface(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable { backstack.add(SettingsSearchScreen) },
              shape = RoundedCornerShape(28.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
              tonalElevation = 2.dp,
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = Icons.Outlined.Search,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.outline,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                  text = stringResource(R.string.settings_search_hint),
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.outline,
                )
              }
            }
          }

          // ── 1. Appearance ─────────────────────────────────────────────────
          if (!enableExoPlayer) {
            item { PreferenceSectionHeader(title = stringResource(R.string.pref_section_appearance)) }
            item {
              PreferenceCard {
                Preference(
                  title = { Text(stringResource(R.string.pref_appearance_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_appearance_summary),
                      color = MaterialTheme.colorScheme.outline
                    )
                  },
                  icon = {
                    Icon(
                      Icons.Outlined.Palette,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary
                    )
                  },
                  onClick = { backstack.add(AppearancePreferencesScreen) },
                )
              }
            }
          }

          // ── 2. Playback ───────────────────────────────────────────────────
          if (!enableExoPlayer) {
            item { PreferenceSectionHeader(title = stringResource(R.string.pref_section_playback)) }
            item {
              PreferenceCard {
                Preference(
                  title = { Text(stringResource(R.string.pref_player)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_player_summary),
                      color = MaterialTheme.colorScheme.outline
                    )
                  },
                  icon = {
                    Icon(
                      Icons.Default.Slideshow,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary
                    )
                  },
                  onClick = { backstack.add(PlayerPreferencesScreen) },
                )
                PreferenceDivider()
                Preference(
                  title = { Text(stringResource(R.string.pref_decoder)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_decoder_summary),
                      color = MaterialTheme.colorScheme.outline
                    )
                  },
                  icon = {
                    Icon(
                      Icons.Default.DeveloperBoard,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary
                    )
                  },
                  onClick = { backstack.add(DecoderPreferencesScreen) },
                )
                PreferenceDivider()
                Preference(
                  title = { Text(stringResource(R.string.pref_audio)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_audio_summary),
                      color = MaterialTheme.colorScheme.outline
                    )
                  },
                  icon = {
                    Icon(
                      Icons.Outlined.Audiotrack,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary
                    )
                  },
                  onClick = { backstack.add(AudioPreferencesScreen) },
                )
              }
            }
          }

          // ── 3. Gestures & Controls ────────────────────────────────────────
          if (!enableExoPlayer) {
            item { PreferenceSectionHeader(title = stringResource(R.string.pref_section_gestures_controls)) }
            item {
              PreferenceCard {
                Preference(
                  title = { Text(stringResource(R.string.pref_gesture)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_gesture_summary),
                      color = MaterialTheme.colorScheme.outline
                    )
                  },
                  icon = {
                    Icon(
                      Icons.Outlined.Gesture,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary
                    )
                  },
                  onClick = { backstack.add(GesturePreferencesScreen) },
                )
                PreferenceDivider()
                Preference(
                  title = { Text(stringResource(R.string.pref_layout_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_layout_summary),
                      color = MaterialTheme.colorScheme.outline
                    )
                  },
                  icon = {
                    Icon(
                      Icons.Default.GridView,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary
                    )
                  },
                  onClick = { backstack.add(PlayerControlsPreferencesScreen) },
                )
              }
            }
          }

          // ── 4. Subtitles ──────────────────────────────────────────────────
          if (!enableExoPlayer) {
            item { PreferenceSectionHeader(title = stringResource(R.string.pref_section_subtitles)) }
            item {
              PreferenceCard {
                Preference(
                  title = { Text(stringResource(R.string.pref_subtitles)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_subtitles_summary),
                      color = MaterialTheme.colorScheme.outline
                    )
                  },
                  icon = {
                    Icon(
                      Icons.Outlined.Subtitles,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary
                    )
                  },
                  onClick = { backstack.add(SubtitlesPreferencesScreen) },
                )
              }
            }
          }

          // ── 5. Storage ────────────────────────────────────────────────────
          if (!enableExoPlayer) {
            item { PreferenceSectionHeader(title = stringResource(R.string.pref_section_storage)) }
            item {
              PreferenceCard {
                Preference(
                  title = { Text(stringResource(R.string.pref_folders_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_section_storage_summary),
                      color = MaterialTheme.colorScheme.outline
                    )
                  },
                  icon = {
                    Icon(
                      Icons.Outlined.Folder,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary
                    )
                  },
                  onClick = { backstack.add(FoldersPreferencesScreen) },
                )
              }
            }
          }

          // ── 6. AI Integration ──────────────────────────────────────────────
          if (!enableExoPlayer) {
            item { PreferenceSectionHeader(title = stringResource(R.string.pref_section_ai)) }
            item {
              PreferenceCard {
                Preference(
                  title = { Text(stringResource(R.string.pref_section_ai_title)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_section_ai_summary),
                      color = MaterialTheme.colorScheme.outline
                    )
                  },
                  icon = {
                    Icon(
                      Icons.Default.AutoAwesome,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary
                    )
                  },
                  onClick = { backstack.add(AiIntegrationScreen) },
                )
              }
            }
          }

          // ── 7. Advanced ───────────────────────────────────────────────────
          if (!enableExoPlayer) {
            item { PreferenceSectionHeader(title = stringResource(R.string.pref_section_advanced)) }
            item {
              PreferenceCard {
                Preference(
                  title = { Text(stringResource(R.string.pref_advanced)) },
                  summary = {
                    Text(
                      stringResource(R.string.pref_advanced_summary),
                      color = MaterialTheme.colorScheme.outline
                    )
                  },
                  icon = {
                    Icon(
                      Icons.Alternatives.AdvancedSettings,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary
                    )
                  },
                  onClick = { backstack.add(AdvancedPreferencesScreen) },
                )
              }
            }
          }

          // ── 8. ExoPlayer ──────────────────────────────────────────────────
          item { PreferenceSectionHeader(title = "ExoPlayer") }
          item {
            PreferenceCard {
              me.zhanghai.compose.preference.SwitchPreference(
                value = enableExoPlayer,
                onValueChange = { playerPreferences.enableExoPlayer.set(it) },
                title = { Text("Enable ExoPlayer") },
                summary = { Text("Use ExoPlayer as the video engine instead of mpv") },
                icon = {
                  Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  )
                },
              )

              if (enableExoPlayer) {
                // Ported settings from One-Player
                Preference(
                  title = { Text("Media Library") },
                  summary = { Text("Ignore .nomedia, thumbnail generation", color = MaterialTheme.colorScheme.outline) },
                  icon = { Icon(Icons.Default.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                  onClick = { backstack.add(ExoMediaLibraryPreferencesScreen) },
                )
                PreferenceDivider()
                Preference(
                  title = { Text("Player") },
                  summary = { Text("Resume, speed, loop mode, orientation", color = MaterialTheme.colorScheme.outline) },
                  icon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                  onClick = { backstack.add(ExoPlayerPreferencesScreen) },
                )
                PreferenceDivider()
                Preference(
                  title = { Text("Gestures") },
                  summary = { Text("Double tap, seek, zoom, volume, brightness", color = MaterialTheme.colorScheme.outline) },
                  icon = { Icon(Icons.Outlined.Gesture, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                  onClick = { backstack.add(ExoGesturePreferencesScreen) },
                )
                PreferenceDivider()
                Preference(
                  title = { Text("Video Processing") },
                  summary = { Text("Decoder priority, video filters", color = MaterialTheme.colorScheme.outline) },
                  icon = { Icon(Icons.Default.DeveloperBoard, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                  onClick = { backstack.add(ExoDecoderPreferencesScreen) },
                )
                PreferenceDivider()
                Preference(
                  title = { Text("Audio") },
                  summary = { Text("Preferred languages, focus, volume memory", color = MaterialTheme.colorScheme.outline) },
                  icon = { Icon(Icons.Default.Audiotrack, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                  onClick = { backstack.add(ExoAudioPreferencesScreen) },
                )
                PreferenceDivider()
                Preference(
                  title = { Text("Subtitle") },
                  summary = { Text("Font, auto-load, encoding, appearance", color = MaterialTheme.colorScheme.outline) },
                  icon = { Icon(Icons.Default.Subtitles, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                  onClick = { backstack.add(ExoSubtitlePreferencesScreen) },
                )
                PreferenceDivider()
                Preference(
                  title = { Text("General") },
                  summary = { Text("Cache, backup, restore", color = MaterialTheme.colorScheme.outline) },
                  icon = { Icon(Icons.Outlined.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                  onClick = { backstack.add(ExoGeneralPreferencesScreen) },
                )
              }
            }
          }

          // ── 9. About ──────────────────────────────────────────────────────
          item { PreferenceSectionHeader(title = stringResource(R.string.pref_section_about)) }
          item {
            PreferenceCard {
              Preference(
                title = { Text(stringResource(R.string.pref_about_title)) },
                summary = { Text(stringResource(R.string.pref_about_summary), color = MaterialTheme.colorScheme.outline) },
                icon = { Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { backstack.add(AboutScreen) },
              )
            }
          }
        }
      }
    }
  }
}
