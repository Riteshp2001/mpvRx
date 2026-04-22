package app.gyrolet.mpvrx.ui.preferences

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.ui.player.ControlsAnimationStyle
import app.gyrolet.mpvrx.ui.player.NavigationAnimStyle
import app.gyrolet.mpvrx.ui.player.VideoOpenAnimation
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.toFixed
import app.gyrolet.mpvrx.preferences.MultiChoiceSegmentedButton
import app.gyrolet.mpvrx.preferences.ThumbnailMode
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.ConfirmDialog
import app.gyrolet.mpvrx.ui.preferences.components.ThemePicker
import app.gyrolet.mpvrx.ui.theme.DarkMode
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SliderPreference
import me.zhanghai.compose.preference.SwitchPreference
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Serializable
object AppearancePreferencesScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val preferences = koinInject<AppearancePreferences>()
        val browserPreferences = koinInject<BrowserPreferences>()
        val gesturePreferences = koinInject<GesturePreferences>()
        val playerPreferences = koinInject<PlayerPreferences>()
        val thumbnailRepository = koinInject<ThumbnailRepository>()
        val backstack = LocalBackStack.current
        val scope = rememberCoroutineScope()
        val systemDarkTheme = isSystemInDarkTheme()

        val darkMode by preferences.darkMode.collectAsState()
        val appTheme by preferences.appTheme.collectAsState()
        var pendingThumbnailMode by remember { mutableStateOf<ThumbnailMode?>(null) }
        var isThemeSectionExpanded by rememberSaveable { mutableStateOf(true) }
        val storedThumbnailMode by browserPreferences.thumbnailMode.collectAsState()
        val thumbnailFramePosition by browserPreferences.thumbnailFramePosition.collectAsState()

        LaunchedEffect(storedThumbnailMode) {
            when (storedThumbnailMode) {
                ThumbnailMode.OneThird -> {
                    browserPreferences.thumbnailFramePosition.set(33f)
                    browserPreferences.thumbnailMode.set(ThumbnailMode.FrameAtPosition)
                }
                ThumbnailMode.Halfway -> {
                    browserPreferences.thumbnailFramePosition.set(50f)
                    browserPreferences.thumbnailMode.set(ThumbnailMode.FrameAtPosition)
                }
                else -> Unit
            }
        }

        val thumbnailMode =
            when (storedThumbnailMode) {
                ThumbnailMode.OneThird, ThumbnailMode.Halfway -> ThumbnailMode.FrameAtPosition
                else -> storedThumbnailMode
            }

        // Determine if we're in dark mode for theme preview
        val isDarkMode = when (darkMode) {
            DarkMode.Dark -> true
            DarkMode.Light -> false
            DarkMode.System -> systemDarkTheme
        }

        if (pendingThumbnailMode != null) {
            ConfirmDialog(
                title = stringResource(R.string.pref_appearance_thumbnail_generation_change_title),
                subtitle = stringResource(R.string.pref_appearance_thumbnail_generation_change_summary),
                onConfirm = {
                    val selectedMode = pendingThumbnailMode
                    pendingThumbnailMode = null
                    if (selectedMode != null) {
                        browserPreferences.thumbnailMode.set(selectedMode)
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { thumbnailRepository.clearThumbnailCache() }
                            }
                            result
                                .onSuccess {
                                    Toast
                                        .makeText(
                                            context,
                                            context.getString(R.string.pref_thumbnail_cache_cleared),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }.onFailure { error ->
                                    Toast
                                        .makeText(
                                            context,
                                            context.getString(
                                                R.string.pref_thumbnail_cache_clear_failed,
                                                error.message ?: "Unknown error",
                                            ),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                }
                        }
                    }
                },
                onCancel = { pendingThumbnailMode = null },
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.pref_appearance_title),
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
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    item {
                        PreferenceSectionHeader(title = stringResource(id = R.string.pref_appearance_category_theme))
                    }

                    item {
                        PreferenceCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isThemeSectionExpanded = !isThemeSectionExpanded }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = stringResource(id = R.string.pref_appearance_category_theme),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "${stringResource(darkMode.titleRes)} · ${stringResource(appTheme.titleRes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                Icon(
                                    imageVector = if (isThemeSectionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            AnimatedVisibility(visible = isThemeSectionExpanded) {
                                Column {
                                    PreferenceDivider()

                                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                        MultiChoiceSegmentedButton(
                                            choices = DarkMode.entries.map { stringResource(it.titleRes) }.toImmutableList(),
                                            selectedIndices = persistentListOf(DarkMode.entries.indexOf(darkMode)),
                                            onClick = { preferences.darkMode.set(DarkMode.entries[it]) },
                                        )
                                    }

                                    PreferenceDivider()

                                    val amoledMode by preferences.amoledMode.collectAsState()
                                    ThemePicker(
                                        currentTheme = appTheme,
                                        isDarkMode = isDarkMode,
                                        onThemeSelected = { preferences.appTheme.set(it) },
                                        modifier = Modifier.padding(vertical = 8.dp),
                                    )

                                    PreferenceDivider()

                                    SwitchPreference(
                                        value = amoledMode,
                                        onValueChange = { newValue ->
                                            preferences.amoledMode.set(newValue)
                                        },
                                        title = { Text(text = stringResource(id = R.string.pref_appearance_amoled_mode_title)) },
                                        summary = {
                                            Text(
                                                text = stringResource(id = R.string.pref_appearance_amoled_mode_summary),
                                                color = MaterialTheme.colorScheme.outline,
                                            )
                                        },
                                        enabled = darkMode != DarkMode.Light
                                    )

                                    PreferenceDivider()

                                    val useSystemFont by preferences.useSystemFont.collectAsState()
                                    SwitchPreference(
                                        value = useSystemFont,
                                        onValueChange = preferences.useSystemFont::set,
                                        title = { Text(text = stringResource(id = R.string.pref_appearance_system_font_title)) },
                                        summary = {
                                            Text(
                                                text = stringResource(id = R.string.pref_appearance_system_font_summary),
                                                color = MaterialTheme.colorScheme.outline,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    item {
                        PreferenceSectionHeader(title = stringResource(id = R.string.pref_appearance_category_file_browser))
                    }

                    item {
                        PreferenceCard {
                            val unlimitedNameLines by preferences.unlimitedNameLines.collectAsState()
                            SwitchPreference(
                                value = unlimitedNameLines,
                                onValueChange = { preferences.unlimitedNameLines.set(it) },
                                title = {
                                    Text(
                                        text = stringResource(id = R.string.pref_appearance_unlimited_name_lines_title),
                                    )
                                },
                                summary = {
                                    Text(
                                        text = stringResource(id = R.string.pref_appearance_unlimited_name_lines_summary),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            )

                            PreferenceDivider()

                            val showUnplayedOldVideoLabel by preferences.showUnplayedOldVideoLabel.collectAsState()
                            SwitchPreference(
                                value = showUnplayedOldVideoLabel,
                                onValueChange = { preferences.showUnplayedOldVideoLabel.set(it) },
                                title = {
                                    Text(
                                        text = stringResource(id = R.string.pref_appearance_show_unplayed_old_video_label_title),
                                    )
                                },
                                summary = {
                                    Text(
                                        text = stringResource(id = R.string.pref_appearance_show_unplayed_old_video_label_summary),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            )

                            PreferenceDivider()

                            val unplayedOldVideoDays by preferences.unplayedOldVideoDays.collectAsState()
                            SliderPreference(
                                value = unplayedOldVideoDays.toFloat(),
                                onValueChange = { preferences.unplayedOldVideoDays.set(it.roundToInt()) },
                                title = { Text(text = stringResource(id = R.string.pref_appearance_unplayed_old_video_days_title)) },
                                valueRange = 1f..30f,
                                summary = {
                                    Text(
                                        text = stringResource(
                                            id = R.string.pref_appearance_unplayed_old_video_days_summary,
                                            unplayedOldVideoDays,
                                        ),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                },
                                onSliderValueChange = { preferences.unplayedOldVideoDays.set(it.roundToInt()) },
                                sliderValue = unplayedOldVideoDays.toFloat(),
                                enabled = showUnplayedOldVideoLabel
                            )

                            PreferenceDivider()

                            val autoScrollToLastPlayed by browserPreferences.autoScrollToLastPlayed.collectAsState()
                            SwitchPreference(
                                value = autoScrollToLastPlayed,
                                onValueChange = { browserPreferences.autoScrollToLastPlayed.set(it) },
                                title = {
                                    Text(text = stringResource(R.string.pref_appearance_auto_scroll_title))
                                },
                                summary = {
                                    Text(
                                        text = stringResource(R.string.pref_appearance_auto_scroll_summary),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            )

                            PreferenceDivider()

                            val watchedThreshold by browserPreferences.watchedThreshold.collectAsState()
                            SliderPreference(
                                value = watchedThreshold.toFloat(),
                                onValueChange = { browserPreferences.watchedThreshold.set(it.roundToInt()) },
                                sliderValue = watchedThreshold.toFloat(),
                                onSliderValueChange = { browserPreferences.watchedThreshold.set(it.roundToInt()) },
                                title = { Text(text = stringResource(id = R.string.pref_appearance_watched_threshold_title)) },
                                valueRange = 50f..100f,
                                valueSteps = 9,
                                summary = {
                                    Text(
                                        text = stringResource(
                                            id = R.string.pref_appearance_watched_threshold_summary,
                                            watchedThreshold,
                                        ),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                },
                            )
                        }
                    }

                    item {
                        PreferenceSectionHeader(title = stringResource(id = R.string.pref_appearance_category_thumbnails))
                    }

                    item {
                        PreferenceCard {
                            val showVideoThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
                            SwitchPreference(
                                value = showVideoThumbnails,
                                onValueChange = { browserPreferences.showVideoThumbnails.set(it) },
                                title = {
                                    Text(text = stringResource(id = R.string.pref_appearance_show_video_thumbnails_title))
                                },
                                summary = {
                                    Text(
                                        text = stringResource(id = R.string.pref_appearance_show_video_thumbnails_summary),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            )

                            PreferenceDivider()

                            ListPreference(
                                value = thumbnailMode,
                                onValueChange = { newMode ->
                                    if (newMode != thumbnailMode) {
                                        pendingThumbnailMode = newMode
                                    }
                                },
                                values = ThumbnailMode.entries.filter { it.isSelectable },
                                valueToText = { AnnotatedString(it.displayName) },
                                title = { Text(text = stringResource(id = R.string.pref_appearance_thumbnail_generation_title)) },
                                summary = {
                                    Text(
                                        text = when (thumbnailMode) {
                                            ThumbnailMode.FrameAtPosition ->
                                                "${thumbnailMode.displayName} (${thumbnailFramePosition.roundToInt()}%)"
                                            else -> thumbnailMode.displayName
                                        },
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                },
                                enabled = showVideoThumbnails,
                            )

                            if (thumbnailMode == ThumbnailMode.FrameAtPosition) {
                                PreferenceDivider()

                                SliderPreference(
                                    value = thumbnailFramePosition,
                                    onValueChange = { browserPreferences.thumbnailFramePosition.set(it) },
                                    sliderValue = thumbnailFramePosition,
                                    onSliderValueChange = { browserPreferences.thumbnailFramePosition.set(it) },
                                    title = {
                                        Text(text = stringResource(id = R.string.pref_appearance_thumbnail_position_title))
                                    },
                                    valueRange = 0f..100f,
                                    valueSteps = 99,
                                    summary = {
                                        Text(
                                            text = stringResource(
                                                id = R.string.pref_appearance_thumbnail_position_summary,
                                                thumbnailFramePosition.roundToInt(),
                                            ),
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    },
                                    enabled = showVideoThumbnails,
                                )
                            }

                            PreferenceDivider()

                            val tapThumbnailToSelect by gesturePreferences.tapThumbnailToSelect.collectAsState()
                            SwitchPreference(
                                value = tapThumbnailToSelect,
                                onValueChange = { gesturePreferences.tapThumbnailToSelect.set(it) },
                                title = {
                                    Text(
                                        text = stringResource(id = R.string.pref_gesture_tap_thumbnail_to_select_title),
                                    )
                                },
                                summary = {
                                    Text(
                                        text = stringResource(id = R.string.pref_gesture_tap_thumbnail_to_select_summary),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                },
                                enabled = showVideoThumbnails,
                            )

                            PreferenceDivider()

                            val showNetworkThumbnails by preferences.showNetworkThumbnails.collectAsState()
                            SwitchPreference(
                                value = showNetworkThumbnails,
                                onValueChange = { preferences.showNetworkThumbnails.set(it) },
                                title = {
                                    Text(
                                        text = stringResource(id = R.string.pref_appearance_show_network_thumbnails_title),
                                    )
                                },
                                summary = {
                                    Text(
                                        text = stringResource(id = R.string.pref_appearance_show_network_thumbnails_summary),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                },
                                enabled = showVideoThumbnails,
                            )
                        }
                    }

                    item {
                        PreferenceSectionHeader(title = stringResource(id = R.string.pref_appearance_category_navigation))
                    }

                    item {
                        PreferenceCard {
                            val showHomeTab by preferences.showHomeTab.collectAsState()
                            val showRecentsTab by preferences.showRecentsTab.collectAsState()
                            val showPlaylistsTab by preferences.showPlaylistsTab.collectAsState()
                            val showNetworkTab by preferences.showNetworkTab.collectAsState()

                            SwitchPreference(
                                value = showHomeTab,
                                onValueChange = preferences.showHomeTab::set,
                                title = { Text(text = stringResource(id = R.string.pref_nav_home_title)) },
                                summary = {
                                    Text(
                                        text = stringResource(id = R.string.pref_nav_home_summary),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                },
                            )

                            PreferenceDivider()

                            SwitchPreference(
                                value = showRecentsTab,
                                onValueChange = preferences.showRecentsTab::set,
                                title = { Text(text = stringResource(id = R.string.pref_nav_recents_title)) },
                                summary = {
                                    Text(
                                        text = stringResource(id = R.string.pref_nav_recents_summary),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                },
                            )

                            PreferenceDivider()

                            SwitchPreference(
                                value = showPlaylistsTab,
                                onValueChange = preferences.showPlaylistsTab::set,
                                title = { Text(text = stringResource(id = R.string.pref_nav_playlists_title)) },
                                summary = {
                                    Text(
                                        text = stringResource(id = R.string.pref_nav_playlists_summary),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                },
                            )

                            PreferenceDivider()

                            SwitchPreference(
                                value = showNetworkTab,
                                onValueChange = preferences.showNetworkTab::set,
                                title = { Text(text = stringResource(id = R.string.pref_nav_network_title)) },
                                summary = {
                                    Text(
                                        text = stringResource(id = R.string.pref_nav_network_summary),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                },
                            )
                        }
                    }

                    // ── Animations ────────────────────────────────────────
                    item {
                        PreferenceSectionHeader(title = "Animations")
                    }

                    item {
                        PreferenceCard {
                            val controlsAnimStyle by playerPreferences.controlsAnimStyle.collectAsState()
                            ListPreference(
                                value = controlsAnimStyle,
                                onValueChange = playerPreferences.controlsAnimStyle::set,
                                values = ControlsAnimationStyle.entries,
                                valueToText = { AnnotatedString(it.displayName) },
                                title = { Text("Controls animation style") },
                                summary = { Text(controlsAnimStyle.displayName, color = MaterialTheme.colorScheme.outline) },
                            )

                            PreferenceDivider()

                            val videoOpenAnim by playerPreferences.videoOpenAnimation.collectAsState()
                            ListPreference(
                                value = videoOpenAnim,
                                onValueChange = playerPreferences.videoOpenAnimation::set,
                                values = VideoOpenAnimation.entries,
                                valueToText = { AnnotatedString(it.displayName) },
                                title = { Text("Video opening animation") },
                                summary = { Text(videoOpenAnim.displayName, color = MaterialTheme.colorScheme.outline) },
                            )

                            PreferenceDivider()

                            val navAnimStyle by playerPreferences.navAnimStyle.collectAsState()
                            ListPreference(
                                value = navAnimStyle,
                                onValueChange = playerPreferences.navAnimStyle::set,
                                values = NavigationAnimStyle.entries,
                                valueToText = { AnnotatedString(it.displayName) },
                                title = { Text("Tab navigation style") },
                                summary = { Text(navAnimStyle.displayName, color = MaterialTheme.colorScheme.outline) },
                            )

                            PreferenceDivider()

                            val appNavStyle by playerPreferences.appNavStyle.collectAsState()
                            ListPreference(
                                value = appNavStyle,
                                onValueChange = playerPreferences.appNavStyle::set,
                                values = NavigationAnimStyle.entries,
                                valueToText = { AnnotatedString(it.displayName) },
                                title = { Text("Screen navigation style") },
                                summary = { Text(appNavStyle.displayName, color = MaterialTheme.colorScheme.outline) },
                            )

                            PreferenceDivider()

                            val animSpeed by playerPreferences.animationSpeed.collectAsState()
                            SliderPreference(
                                value = animSpeed,
                                onValueChange = { playerPreferences.animationSpeed.set(it) },
                                title = { Text("Animation speed") },
                                valueRange = 0.25f..2.5f,
                                summary = {
                                    val label = when {
                                        animSpeed < 0.6f -> "Very fast  (${"%.1f".format(animSpeed)}×)"
                                        animSpeed < 0.9f -> "Fast  (${"%.1f".format(animSpeed)}×)"
                                        animSpeed < 1.1f -> "Normal  (${"%.1f".format(animSpeed)}×)"
                                        animSpeed < 1.6f -> "Slow  (${"%.1f".format(animSpeed)}×)"
                                        else             -> "Very slow  (${"%.1f".format(animSpeed)}×)"
                                    }
                                    Text(label, color = MaterialTheme.colorScheme.outline)
                                },
                                onSliderValueChange = { playerPreferences.animationSpeed.set(it) },
                                sliderValue = animSpeed,
                            )
                        }
                    }
                }
            }
        }
    }
}
