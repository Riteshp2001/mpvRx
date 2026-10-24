/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.Preference
import app.gyrolet.mpvrx.preferences.preference.getEnum
import app.gyrolet.mpvrx.ui.background.BackgroundType
import app.gyrolet.mpvrx.ui.background.RandomInterval
import app.gyrolet.mpvrx.ui.theme.AppTheme
import app.gyrolet.mpvrx.ui.theme.CustomThemeDefinition
import app.gyrolet.mpvrx.ui.theme.DarkMode
import app.gyrolet.mpvrx.ui.theme.WallpaperScaleMode
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusGroup
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.utils.device.DevicePerformance
import kotlinx.collections.immutable.ImmutableList

enum class LiquidGlassHighlightStyle { Component, Default, Ambient, Plain }

class AppearancePreferences(
  preferenceStore: PreferenceStore,
) {
  companion object {
    const val CUSTOM_WALLPAPER_URI_KEY = "custom_wallpaper_uri"
  }

  val darkMode = preferenceStore.getEnum("dark_mode", DarkMode.System)
  val appTheme = preferenceStore.getEnum("app_theme", AppTheme.Dynamic)
  val customTheme = preferenceStore.getString("custom_theme", "")
  val selectedCustomThemeName = preferenceStore.getString("selected_custom_theme_name", "")
  val customWallpaperUri = preferenceStore.getString(CUSTOM_WALLPAPER_URI_KEY, "")
  val customWallpaperZoom = preferenceStore.getFloat("custom_wallpaper_zoom", 1f)
  val customWallpaperOffsetX = preferenceStore.getFloat("custom_wallpaper_offset_x", 0f)
  val customWallpaperOffsetY = preferenceStore.getFloat("custom_wallpaper_offset_y", 0f)
  val customWallpaperScaleMode = preferenceStore.getEnum("custom_wallpaper_scale_mode", WallpaperScaleMode.Fit)
  val customWallpaperBlur = preferenceStore.getFloat("custom_wallpaper_blur", 0f)
  val customWallpaperAlpha = preferenceStore.getFloat("custom_wallpaper_alpha", 1f)
  val customWallpaperUseColors = preferenceStore.getBoolean("custom_wallpaper_use_colors", false)
  val amoledMode = preferenceStore.getBoolean("amoled_mode", false)
  // Glass records the whole screen behind it every frame and moves player video to a TextureView;
  // low-end devices start without it (and without the progressive blur) unless the user opts in.
  val liquidGlassEnabled = preferenceStore.getBoolean("liquid_glass_enabled", !DevicePerformance.isLowEnd)
  val liquidGlassInHdr = preferenceStore.getBoolean("liquid_glass_in_hdr", false)
  val liquidGlassOpacity = preferenceStore.getFloat("liquid_glass_opacity", 1f)
  val liquidGlassBlur = preferenceStore.getFloat("liquid_glass_blur", 1f)
  val liquidGlassRefractionHeight = preferenceStore.getFloat("liquid_glass_refraction_height", 1f)
  val liquidGlassRefractionAmount = preferenceStore.getFloat("liquid_glass_refraction_amount", 1f)
  val liquidGlassDepthEffect = preferenceStore.getBoolean("liquid_glass_depth_effect", false)
  val liquidGlassChromaticAberration = preferenceStore.getBoolean("liquid_glass_chromatic_aberration", true)
  val liquidGlassVibrancy = preferenceStore.getBoolean("liquid_glass_vibrancy", true)
  val liquidGlassSaturation = preferenceStore.getFloat("liquid_glass_saturation", 1f)
  val liquidGlassBrightness = preferenceStore.getFloat("liquid_glass_brightness", 0f)
  val liquidGlassContrast = preferenceStore.getFloat("liquid_glass_contrast", 1f)
  val liquidGlassHighlightStyle = preferenceStore.getEnum("liquid_glass_highlight_style", LiquidGlassHighlightStyle.Component)
  val liquidGlassHighlightStrength = preferenceStore.getFloat("liquid_glass_highlight_strength", 1f)
  val liquidGlassHighlightWidth = preferenceStore.getFloat("liquid_glass_highlight_width", 1f)
  val liquidGlassHighlightBlur = preferenceStore.getFloat("liquid_glass_highlight_blur", 1f)
  val navigationBarGlow = preferenceStore.getBoolean("navigation_bar_glow", true)
  val legacyNavigationBar = preferenceStore.getBoolean("legacy_navigation_bar", false)
  val progressiveNavigationBlur = preferenceStore.getBoolean("progressive_navigation_blur", !DevicePerformance.isLowEnd)
  val useSystemFont = preferenceStore.getBoolean("use_system_font", false)
  val googleFontFamily = preferenceStore.getString("google_font_family", "")
  val googleFontRevision = preferenceStore.getInt("google_font_revision", 0)
  val appUiScale = preferenceStore.getFloat("app_ui_scale", 0.95f)
  val unlimitedNameLines = preferenceStore.getBoolean("unlimited_name_lines", false)
  val hidePlayerButtonsBackground = preferenceStore.getBoolean("hide_player_buttons_background", false)
  val forceDarkPlayerButtonsBackground = preferenceStore.getBoolean("force_dark_player_buttons_background", false)
  val showUnplayedOldVideoLabel = preferenceStore.getBoolean("show_unplayed_old_video_label", true)
  val unplayedOldVideoDays = preferenceStore.getInt("unplayed_old_video_days", 7)
  val showNetworkThumbnails = preferenceStore.getBoolean("show_network_thumbnails", false)
  val seekbarStyle = preferenceStore.getEnum("seekbar_style", SeekbarStyle.Thick)
  val portraitPlaybackControlsPosition =
    preferenceStore.getEnum("portrait_playback_controls_position", PlaybackControlsPosition.Center)
  val landscapePlaybackControlsPosition =
    preferenceStore.getEnum("landscape_playback_controls_position", PlaybackControlsPosition.Center)
  val portraitSeekbarPlacement =
    preferenceStore.getEnum("portrait_seekbar_placement", SeekbarPlacement.AboveButtons)
  val landscapeSeekbarPlacement =
    preferenceStore.getEnum("landscape_seekbar_placement", SeekbarPlacement.BelowButtons)
  // Absolute background level: 0.55 keeps each button's designed tint, 1 is opaque, 0 is clear.
  // A new key, because "player_buttons_opacity" stored a multiplier whose 100% kept buttons translucent.
  val playerButtonsBackgroundOpacity = preferenceStore.getFloat("player_buttons_background_opacity", 0.55f)
  val showMusicTab = preferenceStore.getBoolean("show_music_tab", true)
  val showProfileTab = preferenceStore.getBoolean("show_profile_tab", true)
  val profileName = preferenceStore.getString("profile_name", "")
  val profileImagePath = preferenceStore.getString("profile_image_path", "")
  val showNetworkTab = preferenceStore.getBoolean("show_network_tab", false)
  val showJellyfinTab = preferenceStore.getBoolean("show_jellyfin_tab", false)
  /** Persistent visual order of bottom navigation tabs. Home remains the root destination. */
  val bottomNavigationOrder = preferenceStore.getObject(
    key = "bottom_navigation_tab_order",
    defaultValue = listOf("HOME", "MUSIC", "NETWORK", "JELLYFIN", "PROFILE"),
    serializer = { order -> order.joinToString(",") },
    deserializer = { stored ->
      val defaults = listOf("HOME", "MUSIC", "NETWORK", "JELLYFIN", "PROFILE")
      val parsed = stored.split(",").map(String::trim).filter { it in defaults }.distinct()
      listOf("HOME") + parsed.filterNot { it == "HOME" } + defaults.drop(1).filterNot { it in parsed }
    },
  )
  val showQuickPlayFab = preferenceStore.getBoolean("show_quick_play_fab", true)
  val quickPlayFabDirect = preferenceStore.getBoolean("quick_play_fab_direct", false)

  // Background animation (see ui/background)
  val backgroundType = preferenceStore.getEnum("background_type", BackgroundType.NONE)
  val backgroundParallax = preferenceStore.getBoolean("background_parallax", true)
  val backgroundOverWallpaper = preferenceStore.getBoolean("background_over_wallpaper", true)
  val seasonalThemes = preferenceStore.getBoolean("seasonal_themes", true)
  val randomBackgroundInterval = preferenceStore.getEnum("random_background_interval", RandomInterval.EACH_LAUNCH)
  val lastRandomBackground = preferenceStore.getString("last_random_background", "")
  val lastRandomBackgroundDay = preferenceStore.getLong("last_random_background_day", 0L)

  fun resetLiquidGlassOptions() {
    listOf<Preference<*>>(
      liquidGlassOpacity, liquidGlassBlur, liquidGlassRefractionHeight, liquidGlassRefractionAmount,
      liquidGlassDepthEffect, liquidGlassChromaticAberration, liquidGlassVibrancy,
      liquidGlassSaturation, liquidGlassBrightness, liquidGlassContrast,
      liquidGlassHighlightStyle, liquidGlassHighlightStrength, liquidGlassHighlightWidth, liquidGlassHighlightBlur,
    ).forEach { it.delete() }
  }

  val topLeftControls =
    preferenceStore.getString(
      "top_left_controls",
      "BACK_ARROW,VIDEO_TITLE",
    )

  val topRightControls =
    preferenceStore.getString(
      "top_right_controls",
      "CAST,CURRENT_CHAPTER,DECODER,AUDIO_TRACK,SUBTITLES,MORE_OPTIONS",
    )

  val bottomRightControls =
    preferenceStore.getString(
      "bottom_right_controls",
      "FRAME_NAVIGATION,CLIP,SCOPES,VIDEO_ZOOM,PICTURE_IN_PICTURE,ASPECT_RATIO",
    )

  val bottomLeftControls =
    preferenceStore.getString(
      "bottom_left_controls",
      "BACKGROUND_PLAYBACK,LOCK_CONTROLS,SCREEN_ROTATION,PLAYBACK_SPEED,REPEAT_MODE,SHUFFLE,AB_LOOP",
    )

  val portraitBottomControls =
    preferenceStore.getString(
      "portrait_bottom_controls",
      "CAST,SCREEN_ROTATION,DECODER,AUDIO_TRACK,SUBTITLES,BOOKMARKS_CHAPTERS,PLAYBACK_SPEED,BACKGROUND_PLAYBACK,REPEAT_MODE,SHUFFLE,VIDEO_ZOOM,FRAME_NAVIGATION,CLIP,SCOPES,ASPECT_RATIO,PICTURE_IN_PICTURE,LOCK_CONTROLS,MORE_OPTIONS",
    )

  private val castButtonMigrationComplete =
    preferenceStore.getBoolean("cast_button_migration_complete", false)
  private val clipButtonMigrationComplete =
    preferenceStore.getBoolean("clip_button_migration_complete", false)

  init {
    // The advanced Liquid Glass optics rely on modern Android rendering primitives. A restored
    // preference must not re-enable the expensive glass path on Android 11 or below.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && liquidGlassEnabled.get()) {
      liquidGlassEnabled.set(false)
    }

    if (selectedCustomThemeName.get().isBlank()) {
      CustomThemeDefinition.parse(customTheme.get())?.let { legacyTheme ->
        selectedCustomThemeName.set(legacyTheme.name)
      }
    }

    if (!castButtonMigrationComplete.get()) {
      val landscapeButtons =
        listOf(
          topLeftControls.get(),
          topRightControls.get(),
          bottomRightControls.get(),
          bottomLeftControls.get(),
        ).flatMap { it.split(',') }
          .map { it.trim().uppercase() }
      if ("CAST" !in landscapeButtons) {
        topRightControls.set("CAST,${topRightControls.get()}")
      }
      val portraitButtons = portraitBottomControls.get().split(',').map { it.trim().uppercase() }
      if ("CAST" !in portraitButtons) {
        portraitBottomControls.set("CAST,${portraitBottomControls.get()}")
      }
      castButtonMigrationComplete.set(true)
    }

    if (!clipButtonMigrationComplete.get()) {
      val landscapeButtons =
        listOf(
          topLeftControls.get(),
          topRightControls.get(),
          bottomRightControls.get(),
          bottomLeftControls.get(),
        ).flatMap { it.split(',') }
          .map { it.trim().uppercase() }
      if ("CLIP" !in landscapeButtons) {
        bottomRightControls.set("${bottomRightControls.get()},CLIP")
      }
      val portraitButtons = portraitBottomControls.get().split(',').map { it.trim().uppercase() }
      if ("CLIP" !in portraitButtons) {
        portraitBottomControls.set("${portraitBottomControls.get()},CLIP")
      }
      clipButtonMigrationComplete.set(true)
    }
  }

  fun parseButtons(
    csv: String,
    usedButtons: MutableSet<PlayerButton>,
  ): List<PlayerButton> =
    csv
      .splitToSequence(',')
      .map { it.trim().uppercase() }
      .mapNotNull { name ->
        try {
          PlayerButton.valueOf(name)
        } catch (_: IllegalArgumentException) {
          null
        }
      }.filter { it != PlayerButton.NONE }
      .filter { usedButtons.add(it) }
      .toList()
}

enum class PlaybackControlsPosition(
  val displayName: String,
) {
  Center("Center of screen"),
  BelowSeekbar("Below seekbar"),
}

enum class SeekbarPlacement(
  val displayName: String,
) {
  AboveButtons("Above layout buttons"),
  BelowButtons("Below layout buttons"),
}

@Composable
fun MultiChoiceSegmentedButton(
  choices: ImmutableList<String>,
  selectedIndices: ImmutableList<Int>,
  onClick: (Int, Offset) -> Unit,
  modifier: Modifier = Modifier,
) {
  val initialFocusRequester =
    rememberTvInitialFocusRequester(
      enabled = choices.isNotEmpty(),
      requestKey = selectedIndices,
    )
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusGroup()
        .padding(MaterialTheme.spacing.medium),
    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
  ) {
    choices.forEachIndexed { index, choice ->
      var buttonCenter by remember(choice) { mutableStateOf(Offset.Zero) }
      ToggleButton(
        checked = selectedIndices.contains(index),
        onCheckedChange = { onClick(index, buttonCenter) },
        modifier =
          Modifier
            .weight(1f)
            .then(
              if (index == selectedIndices.firstOrNull()) {
                Modifier.tvInitialFocus(initialFocusRequester)
              } else {
                Modifier
              },
            ).tvFocusHighlight(MaterialTheme.shapes.medium, focusedScale = 1.03f)
            .defaultMinSize(minHeight = MaterialTheme.spacing.extraLarge)
            .onGloballyPositioned { buttonCenter = it.boundsInWindow().center }
            .semantics { role = Role.RadioButton },
        colors =
          ToggleButtonDefaults.colors(
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
          ),
        shapes =
          when (index) {
            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
            choices.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
          },
      ) {
        Text(text = choice)
      }
    }
  }
}
