package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.getEnum
import app.gyrolet.mpvrx.preferences.preference.Preference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Preferences for the video browser (folder and video lists)
 */
class BrowserPreferences(
  preferenceStore: PreferenceStore,
  context: android.content.Context,
) {
  // Folder sorting preferences
  val folderSortType = preferenceStore.getEnum("folder_sort_type", FolderSortType.Title)
  val folderSortOrder = preferenceStore.getEnum("folder_sort_order", SortOrder.Ascending)

  // Video sorting preferences
  val videoSortType = preferenceStore.getEnum("video_sort_type", VideoSortType.Title)
  val videoSortOrder = preferenceStore.getEnum("video_sort_order", SortOrder.Ascending)

  val folderViewMode = preferenceStore.getEnum("folder_view_mode", FolderViewMode.AlbumView)
  val dualPaneForTablet = preferenceStore.getBoolean("dual_pane_for_tablet", true)

  private val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
  val maxColumns = if (isTablet) 8 else 4

  private val _folderGridColumnsPortrait = preferenceStore.getInt("folder_grid_columns_portrait", if (isTablet) 4 else 3)
  private val _folderGridColumnsLandscape = preferenceStore.getInt("folder_grid_columns_landscape", 5)

  private val _videoGridColumnsPortrait = preferenceStore.getInt("video_grid_columns_portrait", if (isTablet) 4 else 2)
  private val _videoGridColumnsLandscape = preferenceStore.getInt("video_grid_columns_landscape", 4)

  val folderGridColumnsPortrait: Preference<Int> = CoercedPreference(_folderGridColumnsPortrait, maxColumns)
  val folderGridColumnsLandscape: Preference<Int> = CoercedPreference(_folderGridColumnsLandscape, 8)

  val videoGridColumnsPortrait: Preference<Int> = CoercedPreference(_videoGridColumnsPortrait, maxColumns)
  val videoGridColumnsLandscape: Preference<Int> = CoercedPreference(_videoGridColumnsLandscape, 8)

  val showExtensionField = preferenceStore.getBoolean("show_extension_field", false)
  val showDurationField = preferenceStore.getBoolean("show_duration_field", true)

  // Visibility preferences for video card chips
  val showVideoThumbnails = preferenceStore.getBoolean("show_video_thumbnails", true)
  val thumbnailMode = preferenceStore.getEnum("thumbnail_mode", ThumbnailMode.Smart)
  val thumbnailQuality = preferenceStore.getEnum("thumbnail_quality", ThumbnailQuality.High)
  val thumbnailFramePosition = preferenceStore.getFloat("thumbnail_frame_position", 33f)
  val showSizeChip = preferenceStore.getBoolean("show_size_chip", true)
  // Metadata-dependent chips (disabled by default for better performance)
  val showResolutionChip = preferenceStore.getBoolean("show_resolution_chip", false)
  val showFramerateInResolution = preferenceStore.getBoolean("show_framerate_in_resolution", false)
  val showSubtitleIndicator = preferenceStore.getBoolean("show_subtitle_indicator", false)
  val showProgressBar = preferenceStore.getBoolean("show_progress_bar", true)
  val centerGridTitles = preferenceStore.getBoolean("center_grid_titles", true)
  val mediaLayoutMode = preferenceStore.getEnum("media_layout_mode", MediaLayoutMode.LIST)
  val manualGridColumnsEnabled = preferenceStore.getBoolean("manual_grid_columns_enabled", false)

  // Visibility preferences for folder card chips
  val showTotalVideosChip = preferenceStore.getBoolean("show_total_videos_chip", true)
  // Metadata-dependent chips (disabled by default for better performance)
  val showTotalDurationChip = preferenceStore.getBoolean("show_total_duration_chip", false)
  val showTotalSizeChip = preferenceStore.getBoolean("show_total_size_chip", true)
  val showDateChip = preferenceStore.getBoolean("show_date_chip", false)
  val showFolderPath = preferenceStore.getBoolean("show_folder_path", true)
  val showFolderThumbnails = preferenceStore.getBoolean("show_folder_thumbnails", false)

  // Auto-scroll to last played media preference (like MX Player)
  val autoScrollToLastPlayed = preferenceStore.getBoolean("auto_scroll_to_last_played", false)

  // Maximum single-child folder levels skipped in one Tree View navigation step.
  val treeFlattenDepth = preferenceStore.getEnum("tree_flatten_depth", TreeFlattenDepth.Unlimited)
  val includeAudio = preferenceStore.getBoolean("include_audio", false)
  val minimumAudioDuration = preferenceStore.getEnum("minimum_audio_duration", MinimumAudioDuration.Any)
  val includeAudioBrowser = preferenceStore.getBoolean("include_audio_browser", false)
  val minimumAudioDurationSeconds = preferenceStore.getInt("minimum_audio_duration_seconds", 0)
  val mediaLibraryType = preferenceStore.getEnum("media_library_type", MediaLibraryType.Video)

  // Watched threshold preference (percentage 1-100)
  val watchedThreshold = preferenceStore.getInt("watched_threshold", 95)

  // When deleting a folder, delete all files instead of only media files
  val deleteFolderAllContents = preferenceStore.getBoolean("delete_folder_all_contents", false)
}

/**
 * Sort order options
 */
enum class SortOrder {
  Ascending,
  Descending,
  ;

  val isAscending: Boolean
    get() = this == Ascending
}

/**
 * Folder sorting options
 */
enum class FolderSortType {
  Title,
  Date,
  Size,
  VideoCount,
  ;

  val displayName: String
    get() =
      when (this) {
        Title -> "Title"
        Date -> "Date"
        Size -> "Size"
        VideoCount -> "Count"
      }
}

/**
 * Video sorting options
 */
enum class VideoSortType {
  Title,
  Duration,
  Date,
  Size,
  ;

  val displayName: String
    get() =
      when (this) {
        Title -> "Title"
        Duration -> "Duration"
        Date -> "Date"
        Size -> "Size"
      }
}

/**
 * Folder view mode options
 */
enum class FolderViewMode {
  AlbumView,
  FileManager,
  MediaLibrary,
  ;

  val displayName: String
    get() =
      when (this) {
        AlbumView -> "Folder View"
        FileManager -> "Tree View"
        MediaLibrary -> "Media Library"
      }
}

enum class TreeFlattenDepth(
  val maxLevels: Int,
  val displayName: String,
) {
  Off(0, "Off (show every folder)"),
  One(1, "1 level"),
  Two(2, "2 levels"),
  Three(3, "3 levels"),
  Four(4, "4 levels"),
  Five(5, "5 levels"),
  Unlimited(-1, "Unlimited"),
}

enum class MinimumAudioDuration(
  val seconds: Int,
  val displayName: String,
) {
  Any(0, "Any"),
  FifteenSeconds(15, "15 sec"),
  ThirtySeconds(30, "30 sec"),
  OneMinute(60, "1 min"),
}

enum class MediaLayoutMode {
  LIST,
  GRID,
  ;

  val displayName:  String
    get() = when (this) {
      LIST -> "List"
      GRID -> "Grid"
    }
}

enum class MediaLibraryType {
  Video,
  Audio,
}

enum class ThumbnailMode {
  Smart,
  FirstFrame,
  FrameAtPosition,
  EmbeddedThumbnail,
  ;

  val displayName: String
    get() =
      when (this) {
        Smart -> "Smart (embedded + 33%)"
        FirstFrame -> "First frame"
        FrameAtPosition -> "Frame position"
        EmbeddedThumbnail -> "Embedded thumbnail"
      }
}

enum class ThumbnailQuality(
  val maxSizePx: Int,
  val displayName: String,
) {
  Balanced(720, "Balanced (720p)"),
  High(1080, "High (1080p)"),
  Ultra(1440, "Ultra (1440p)"),
}

internal class CoercedPreference(
  private val delegate: Preference<Int>,
  private val maxVal: Int,
) : Preference<Int> {
  override fun key(): String = delegate.key()
  override fun get(): Int = delegate.get().coerceIn(1, maxVal)
  override fun set(value: Int) = delegate.set(value.coerceIn(1, maxVal))
  override fun isSet(): Boolean = delegate.isSet()
  override fun delete() = delegate.delete()
  override fun defaultValue(): Int = delegate.defaultValue().coerceIn(1, maxVal)

  override fun changes(): Flow<Int> =
    delegate.changes().map { it.coerceIn(1, maxVal) }

  override fun stateIn(scope: kotlinx.coroutines.CoroutineScope): StateFlow<Int> =
    changes().stateIn(scope, SharingStarted.Eagerly, get())
}
