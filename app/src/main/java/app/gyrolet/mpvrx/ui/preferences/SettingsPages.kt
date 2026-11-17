/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.preferences

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.preferences.components.AppPreference
import app.gyrolet.mpvrx.ui.tv.isTelevision
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalCurrentScreen
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable

internal enum class SettingsRoot(@StringRes val titleRes: Int) {
  APPEARANCE(R.string.pref_appearance_title),
  PLAYER(R.string.pref_player),
  AUDIO(R.string.pref_audio),
  SUBTITLES(R.string.pref_subtitles),
  CONTROLS(R.string.pref_layout_title),
  GESTURES(R.string.pref_gesture),
  ADVANCED(R.string.pref_advanced),
  DECODER(R.string.pref_decoder),
  NETWORK(R.string.ui_network),
  AI(R.string.pref_section_ai_title),
  SERVERS(R.string.pref_media_servers_title);

  val screen: Screen
    get() = when (this) {
      APPEARANCE -> AppearancePreferencesScreen
      PLAYER -> PlayerPreferencesScreen
      AUDIO -> AudioPreferencesScreen
      SUBTITLES -> SubtitlesPreferencesScreen
      CONTROLS -> PlayerControlsPreferencesScreen
      GESTURES -> GesturePreferencesScreen
      ADVANCED -> AdvancedPreferencesScreen
      DECODER -> DecoderPreferencesScreen
      NETWORK -> NetworkConfigurationPreferencesScreen
      AI -> AiIntegrationScreen
      SERVERS -> MediaServersPreferencesScreen
    }
}

/** Original item ranges retain the existing search catalog's section addresses. */
@Serializable
internal enum class SettingsPage(
  val root: SettingsRoot,
  @StringRes val titleRes: Int,
  val firstItem: Int,
  val lastItem: Int = firstItem + 1,
) {
  THEME(SettingsRoot.APPEARANCE, R.string.settings_theme_display, 0),
  LIBRARY_DISPLAY(SettingsRoot.APPEARANCE, R.string.settings_library_display, 2),
  THUMBNAILS(SettingsRoot.APPEARANCE, R.string.pref_appearance_category_thumbnails, 4),
  NAVIGATION(SettingsRoot.APPEARANCE, R.string.pref_appearance_category_navigation, 6, 9),
  PROFILE(SettingsRoot.APPEARANCE, R.string.profile_layout_title, 7, 7),
  ANIMATIONS(SettingsRoot.APPEARANCE, R.string.pref_section_animations, 10),
  PLAYBACK(SettingsRoot.PLAYER, R.string.settings_playback_behavior, 0),
  SEEKING(SettingsRoot.PLAYER, R.string.pref_player_seeking_title, 2),
  DISPLAY(SettingsRoot.PLAYER, R.string.pref_section_display_controls, 4),
  SCREENSHOTS(SettingsRoot.PLAYER, R.string.pref_screenshots_section, 6),
  OVERLAYS(SettingsRoot.PLAYER, R.string.pref_section_overlays, 8),
  MUSIC(SettingsRoot.AUDIO, R.string.settings_music_player, 0, 5),
  AUDIO_OUTPUT(SettingsRoot.AUDIO, R.string.settings_audio_output, 6),
  LYRICS(SettingsRoot.AUDIO, R.string.player_lyrics_title, 8, 11),
  SUBTITLE_BEHAVIOR(SettingsRoot.SUBTITLES, R.string.settings_subtitle_behavior, 0),
  SUBTITLE_FONTS(SettingsRoot.SUBTITLES, R.string.pref_section_subtitle_fonts, 2),
  SUBTITLE_SEARCH(SettingsRoot.SUBTITLES, R.string.pref_section_subtitle_search, 4),
  BUTTON_LAYOUTS(SettingsRoot.CONTROLS, R.string.settings_button_layouts, 0, 3),
  SEEKBAR(SettingsRoot.CONTROLS, R.string.pref_section_seekbar_style, 6),
  CONTROL_APPEARANCE(SettingsRoot.CONTROLS, R.string.pref_section_player_controls_appearance, 8),
  SWIPES(SettingsRoot.GESTURES, R.string.pref_section_swipe_speed, 0),
  TAPS(SettingsRoot.GESTURES, R.string.pref_gesture_double_tap_title, 2),
  MEDIA_KEYS(SettingsRoot.GESTURES, R.string.pref_gesture_media_title, 4),
  BACKUPS(SettingsRoot.ADVANCED, R.string.pref_section_backup_restore, 0),
  CONFIG_STORAGE(SettingsRoot.ADVANCED, R.string.pref_section_storage_root, 2),
  MPV_CONFIG(SettingsRoot.ADVANCED, R.string.pref_section_mpv_config, 4),
  SCRIPTS(SettingsRoot.ADVANCED, R.string.pref_section_scripts, 6),
  APP(SettingsRoot.ADVANCED, R.string.settings_app_preferences, 8),
  HISTORY_CACHE(SettingsRoot.ADVANCED, R.string.settings_history_cache, 10),
  LOGGING(SettingsRoot.ADVANCED, R.string.pref_section_logging, 12),
  RENDERER(SettingsRoot.DECODER, R.string.settings_video_renderer, 0),
  ENHANCEMENT(SettingsRoot.DECODER, R.string.settings_video_enhancements, 0),
  UPSCALING(SettingsRoot.DECODER, R.string.settings_upscaling, 0),
  MOTION(SettingsRoot.DECODER, R.string.motion_title, 2),
  YTDLP(SettingsRoot.NETWORK, R.string.ui_yt_dlp_manager, 0),
  COOKIES(SettingsRoot.NETWORK, R.string.ytdlp_cookies_title, 0),
  P2P(SettingsRoot.NETWORK, R.string.pref_section_p2p_streaming, 1),
  AI_FEATURES(SettingsRoot.AI, R.string.pref_ai_features_section, 0),
  AI_PROVIDER(SettingsRoot.AI, R.string.settings_ai_provider, 2, 7),
  AI_SPEECH(SettingsRoot.AI, R.string.pref_stt_section, 10),
  AI_TRANSLATION(SettingsRoot.AI, R.string.pref_translation_section, 12),
  AI_PROMPTS(SettingsRoot.AI, R.string.pref_custom_prompt_section, 14),
  JELLYFIN(SettingsRoot.SERVERS, R.string.pref_jellyfin_title, 0),
  SEERR(SettingsRoot.SERVERS, R.string.pref_seerr_title, 2),
  NAVIDROME(SettingsRoot.SERVERS, R.string.pref_navidrome_title, 4),
  AUDIOBOOKSHELF(SettingsRoot.SERVERS, R.string.pref_audiobookshelf_title, 6),
  ALL_APPEARANCE(SettingsRoot.APPEARANCE, R.string.pref_appearance_title, -1),
  ALL_PLAYER(SettingsRoot.PLAYER, R.string.pref_player, -1),
  ALL_AUDIO(SettingsRoot.AUDIO, R.string.pref_audio, -1),
  ALL_SUBTITLES(SettingsRoot.SUBTITLES, R.string.pref_subtitles, -1),
  ALL_CONTROLS(SettingsRoot.CONTROLS, R.string.pref_layout_title, -1),
  ALL_GESTURES(SettingsRoot.GESTURES, R.string.pref_gesture, -1),
  ALL_ADVANCED(SettingsRoot.ADVANCED, R.string.pref_advanced, -1),
  ALL_DECODER(SettingsRoot.DECODER, R.string.pref_decoder, -1),
  ALL_NETWORK(SettingsRoot.NETWORK, R.string.ui_network, -1),
  ALL_AI(SettingsRoot.AI, R.string.pref_section_ai_title, -1),
  ALL_SERVERS(SettingsRoot.SERVERS, R.string.pref_media_servers_title, -1);

  val isAll: Boolean get() = firstItem < 0
  val searchKey: String get() = "page:$name"
  val link: SettingsPageLink get() = SettingsPageLink(titleRes, SettingsPageScreen(this))
  val availableInBuild: Boolean
    get() = when (this) {
      MOTION -> BuildConfig.MPV_SUPPORTS_MOTION_INTERPOLATION
      YTDLP, COOKIES -> BuildConfig.ENABLE_YTDLP
      P2P -> BuildConfig.ENABLE_TORRENT
      ALL_NETWORK -> BuildConfig.ENABLE_YTDLP || BuildConfig.ENABLE_TORRENT
      else -> root != SettingsRoot.AI || !BuildConfig.IS_PLAY_STORE_BUILD
    }
}

internal fun SettingsPage.includes(selected: SettingsPage?): Boolean = selected == null || selected == this

@Serializable
internal data class SettingsPageScreen(val page: SettingsPage) : Screen {
  @Composable
  override fun Content() {
    if (!page.availableInBuild) {
      SettingsCategoryMenu(page.titleRes, emptyList())
      return
    }
    val selected = page.takeUnless { it.isAll }
    when (page.root) {
      SettingsRoot.APPEARANCE -> AppearancePreferencesScreen.DetailsContent(selected)
      SettingsRoot.PLAYER -> PlayerPreferencesScreen.DetailsContent(selected)
      SettingsRoot.AUDIO -> AudioPreferencesScreen.DetailsContent(selected)
      SettingsRoot.SUBTITLES -> SubtitlesPreferencesScreen.DetailsContent(selected)
      SettingsRoot.CONTROLS -> PlayerControlsPreferencesScreen.DetailsContent(selected)
      SettingsRoot.GESTURES -> GesturePreferencesScreen.DetailsContent(selected)
      SettingsRoot.ADVANCED -> AdvancedPreferencesScreen.DetailsContent(selected)
      SettingsRoot.DECODER -> DecoderPreferencesScreen.DetailsContent(selected)
      SettingsRoot.NETWORK -> NetworkConfigurationPreferencesScreen.DetailsContent(selected)
      SettingsRoot.AI -> AiIntegrationScreen.DetailsContent(selected)
      SettingsRoot.SERVERS -> MediaServersPreferencesScreen.DetailsContent(selected)
    }
  }
}

internal fun Screen?.settingsSourceScreen(): Screen? =
  if (this is SettingsPageScreen) page.root.screen else this

internal data class SettingsPageLink(@StringRes val titleRes: Int, val screen: Screen)

@Composable
internal fun SettingsCategoryMenu(@StringRes titleRes: Int, links: List<SettingsPageLink>) {
  val backStack = LocalBackStack.current
  val television = isTelevision()
  val visibleLinks = links.filter {
    (!television || TvSettingsPolicy.supportsScreen(it.screen)) &&
      (it.screen !is SettingsPageScreen || it.screen.page.availableInBuild)
  }
  Scaffold(
    topBar = {
      SettingsTopAppBar(
        title = stringResource(titleRes),
        titleModifier = Modifier.settingsSearchTarget(titleRes),
        onBack = { backStack.popSafely() },
      )
    },
  ) { padding ->
    ProvideExpressivePreferenceLocals {
      val (state, highlight) = rememberSettingsSearchList(LocalCurrentScreen.current ?: PreferencesScreen)
      LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().padding(padding).then(highlight),
        contentPadding = PaddingValues(vertical = 12.dp),
      ) {
        item {
          if (visibleLinks.isEmpty()) {
            Text(
              stringResource(R.string.settings_unavailable),
              modifier = Modifier.padding(24.dp),
            )
          }
          if (visibleLinks.isNotEmpty()) PreferenceCard {
            visibleLinks.forEach { link ->
              ExpressivePreferenceItem(standalone = true) {
                AppPreference(
                  modifier = Modifier.settingsSearchTarget(link.titleRes),
                  title = { Text(stringResource(link.titleRes)) },
                  onClick = { backStack.navigateTo(link.screen) },
                )
              }
            }
          }
        }
      }
    }
  }
}

internal fun reorganizedSettingsPreference(preference: SearchablePreference): SearchablePreference {
  val root = SettingsRoot.entries.firstOrNull { it.screen == preference.screen } ?: return preference
  val title = preference.targetRes ?: preference.titleRes
  if (title == root.titleRes) return preference
  if (root == SettingsRoot.CONTROLS && title == R.string.pref_persistent_status_title) {
    return preference.copy(screen = PersistentStatusBarPreferencesScreen, anchorItemIndex = 0)
  }
  if (root == SettingsRoot.CONTROLS && preference.title != null) {
    return preference.copy(
      screen = SettingsPageScreen(SettingsPage.SEEKBAR),
      targetRes = R.string.pref_section_seekbar_style,
      anchorItemIndex = 1,
    )
  }
  val originalIndex = originalSettingsAnchorIndex(preference)
  val explicitPage = when {
    root == SettingsRoot.APPEARANCE && title in setOf(
      R.string.profile_layout_title, R.string.profile_layout_hubs,
      R.string.profile_layout_sections, R.string.profile_layout_statistics,
    ) -> SettingsPage.PROFILE
    root == SettingsRoot.DECODER && title in setOf(
      R.string.pref_anime4k_title, R.string.pref_anime4k_in_4k_title, R.string.pref_anime4k_quality_title,
      R.string.pref_anime4k_darken_title, R.string.pref_anime4k_thin_title, R.string.pref_anime4k_deblur_title,
    ) -> SettingsPage.UPSCALING
    root == SettingsRoot.DECODER && title in setOf(
      R.string.pref_decoder_debanding_title, R.string.pref_decoder_yuv420p_title,
      R.string.pref_decoder_dolby_vision_direct_title, R.string.pref_decoder_dovi_p7_title,
    ) -> SettingsPage.ENHANCEMENT
    root == SettingsRoot.NETWORK && title == R.string.ytdlp_cookies_title -> SettingsPage.COOKIES
    root == SettingsRoot.NETWORK && title == R.string.pref_enable_p2p_streaming_title -> SettingsPage.P2P
    root == SettingsRoot.AI && originalIndex != null && originalIndex in 8..9 -> SettingsPage.AI_FEATURES
    else -> null
  }
  val page = explicitPage ?: SettingsPage.entries.firstOrNull {
    !it.isAll && it != SettingsPage.PROFILE && it.root == root &&
      originalIndex != null && originalIndex in it.firstItem..it.lastItem
  } ?: SettingsPage.entries.first { it.root == root && it.isAll }
  val anchor = when {
    page == SettingsPage.PROFILE -> 0
    page.isAll -> originalIndex
    page == SettingsPage.AI_PROVIDER -> originalIndex
    page == SettingsPage.AI_FEATURES -> originalIndex?.let { if (it >= 8) it - 6 else it }
    root == SettingsRoot.AI -> originalIndex?.minus(page.firstItem)?.plus(2)?.coerceAtLeast(0)
    else -> originalIndex?.minus(page.firstItem)?.coerceAtLeast(0)
  }
  return preference.copy(screen = SettingsPageScreen(page), anchorItemIndex = anchor, category = page.searchCategory)
}

private val SettingsPage.searchCategory: String
  get() = when (this) {
    SettingsPage.APP, SettingsPage.BACKUPS -> "General"
    SettingsPage.LIBRARY_DISPLAY, SettingsPage.THUMBNAILS, SettingsPage.HISTORY_CACHE -> "Storage"
    SettingsPage.BUTTON_LAYOUTS, SettingsPage.SEEKBAR, SettingsPage.CONTROL_APPEARANCE,
    SettingsPage.DISPLAY, SettingsPage.OVERLAYS -> "Controls"
    else -> when (root) {
      SettingsRoot.APPEARANCE -> "Appearance"
      SettingsRoot.PLAYER -> "Player"
      SettingsRoot.AUDIO -> "Audio"
      SettingsRoot.SUBTITLES -> "Subtitles"
      SettingsRoot.CONTROLS -> "Controls"
      SettingsRoot.GESTURES -> "Gestures"
      SettingsRoot.ADVANCED -> "Advanced"
      SettingsRoot.DECODER -> "Decoder"
      SettingsRoot.NETWORK -> "Network"
      SettingsRoot.AI -> "AI"
      SettingsRoot.SERVERS -> "Media Servers"
    }
  }

internal fun settingsPageSearchEntries(): List<SearchablePreference> =
  SettingsPage.entries.filter { !it.isAll && it.availableInBuild }.map {
    SearchablePreference(
      titleRes = it.titleRes,
      category = it.searchCategory,
      screen = SettingsPageScreen(it),
      targetKey = it.searchKey,
    )
  }
