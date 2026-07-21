package app.gyrolet.mpvrx.ui.browser.dialogs

import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.FolderSortType
import app.gyrolet.mpvrx.preferences.FolderViewMode
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.MinimumAudioDuration
import app.gyrolet.mpvrx.preferences.SortOrder
import app.gyrolet.mpvrx.preferences.VideoSortType
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.repository.MediaFileRepository
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import app.gyrolet.mpvrx.ui.icons.Icons
import org.koin.compose.koinInject

@Composable
fun FolderSortDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  sortType: FolderSortType,
  sortOrder: SortOrder,
  onSortTypeChange: (FolderSortType) -> Unit,
  onSortOrderChange: (SortOrder) -> Unit,
  isDualPane: Boolean = false,
) {
  val browserPreferences = koinInject<BrowserPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val showTotalVideosChip by browserPreferences.showTotalVideosChip.collectAsState()
  val showTotalDurationChip by browserPreferences.showTotalDurationChip.collectAsState()
  val showTotalSizeChip by browserPreferences.showTotalSizeChip.collectAsState()
  val showDateChip by browserPreferences.showDateChip.collectAsState()
  val showFolderPath by browserPreferences.showFolderPath.collectAsState()
  val showFolderThumbnails by browserPreferences.showFolderThumbnails.collectAsState()
  val dualPaneForTablet by browserPreferences.dualPaneForTablet.collectAsState()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val centerGridTitles by browserPreferences.centerGridTitles.collectAsState()
  val folderViewMode by browserPreferences.folderViewMode.collectAsState()
  val folderViewFolderLayoutMode by browserPreferences.folderViewFolderLayoutMode.collectAsState()
  val folderViewVideoLayoutMode by browserPreferences.folderViewVideoLayoutMode.collectAsState()
  val separateFolderVideoLayout by browserPreferences.separateFolderVideoLayout.collectAsState()
  val mediaLayoutMode by browserPreferences.mediaLayoutMode.collectAsState()
  val manualGridColumnsEnabled by browserPreferences.manualGridColumnsEnabled.collectAsState()
  val folderGridColumnsPortrait by browserPreferences.folderGridColumnsPortrait.collectAsState()
  val folderGridColumnsLandscape by browserPreferences.folderGridColumnsLandscape.collectAsState()
  val videoGridColumnsPortrait by browserPreferences.videoGridColumnsPortrait.collectAsState()
  val videoGridColumnsLandscape by browserPreferences.videoGridColumnsLandscape.collectAsState()

  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val isTablet = configuration.smallestScreenWidthDp >= 600
  val maxColumns = if (isTablet || isLandscape) 8 else 4

  val folderGridColumns = if (isLandscape) folderGridColumnsLandscape else folderGridColumnsPortrait
  val videoGridColumns = if (isLandscape) videoGridColumnsLandscape else videoGridColumnsPortrait

  val screenWidthDp = configuration.screenWidthDp.dp
  val contentHorizontalPadding = 8.dp
  val itemSpacing = 2.dp
  val folderPaneWidth = if (isDualPane) screenWidthDp * 0.4f else screenWidthDp
  val videoPaneWidth = if (isDualPane) screenWidthDp * 0.6f else screenWidthDp

  val usableFolderWidth = folderPaneWidth - (contentHorizontalPadding * 2) - itemSpacing
  val usableVideoWidth = videoPaneWidth - (contentHorizontalPadding * 2) - itemSpacing

  val folderMinWidth = 100.dp
  val videoMinWidth = 130.dp
  val dynamicFolderColumns = (usableFolderWidth / folderMinWidth).toInt().coerceIn(1, maxColumns)
  val dynamicVideoColumns = (usableVideoWidth / videoMinWidth).toInt().coerceIn(1, maxColumns)

  val isAlbumView = folderViewMode == FolderViewMode.AlbumView
  val activeLayoutMode = if (isAlbumView) folderViewFolderLayoutMode else mediaLayoutMode

  val folderGridColumnSelector = if (activeLayoutMode == MediaLayoutMode.GRID && manualGridColumnsEnabled) {
    GridColumnSelector(
      label = "Folder Grid Columns (${if (isLandscape) "Landscape" else "Portrait"})",
      currentValue = folderGridColumns.coerceIn(1, maxColumns),
      onValueChange = {
        if (isLandscape) browserPreferences.folderGridColumnsLandscape.set(it)
        else browserPreferences.folderGridColumnsPortrait.set(it)
      },
      valueRange = 1f..maxColumns.toFloat(),
      steps = maxColumns - 2,
    )
  } else null

  val videoGridColumnSelector = if (activeLayoutMode == MediaLayoutMode.GRID && manualGridColumnsEnabled) {
    GridColumnSelector(
      label = "Video Grid Columns (${if (isLandscape) "Landscape" else "Portrait"})",
      currentValue = videoGridColumns.coerceIn(1, maxColumns),
      onValueChange = {
        if (isLandscape) browserPreferences.videoGridColumnsLandscape.set(it)
        else browserPreferences.videoGridColumnsPortrait.set(it)
      },
      valueRange = 1f..maxColumns.toFloat(),
      steps = maxColumns - 2,
    )
  } else null

  SortDialog(
    isOpen = isOpen,
    onDismiss = onDismiss,
    title = if (isAlbumView) stringResource(R.string.sort_view_options) else stringResource(R.string.ui_view_options),
    sortType = sortType.displayName,
    onSortTypeChange = { typeName ->
      FolderSortType.entries
        .find { it.displayName == typeName }
        ?.let(onSortTypeChange)
    },
    sortOrderAsc = sortOrder.isAscending,
    onSortOrderChange = { isAsc ->
      onSortOrderChange(if (isAsc) SortOrder.Ascending else SortOrder.Descending)
    },
    types = listOf(
      FolderSortType.Title.displayName,
      FolderSortType.Date.displayName,
      FolderSortType.Size.displayName,
    ),
    icons = listOf(
      Icons.RoundedFilled.Title,
      Icons.RoundedFilled.CalendarToday,
      Icons.RoundedFilled.SwapVert,
    ),
    getLabelForType = { type, _ ->
      when (type) {
        FolderSortType.Title.displayName -> Pair("A-Z", "Z-A")
        FolderSortType.Date.displayName -> Pair("Oldest", "Newest")
        FolderSortType.Size.displayName -> Pair("Smallest", "Largest")
        else -> Pair("Asc", "Desc")
      }
    },
    showSortOptions = isAlbumView,
    viewModeSelector = MultiViewModeSelector(
      label = "View Mode",
      options = listOf(
        ViewModeOption(
          label = "Folder",
          icon = Icons.RoundedFilled.ViewModule,
          isSelected = folderViewMode == FolderViewMode.AlbumView,
          onClick = { browserPreferences.folderViewMode.set(FolderViewMode.AlbumView) }
        ),
        ViewModeOption(
          label = "Tree",
          icon = Icons.RoundedFilled.AccountTree,
          isSelected = folderViewMode == FolderViewMode.FileManager,
          onClick = { browserPreferences.folderViewMode.set(FolderViewMode.FileManager) }
        ),
        ViewModeOption(
          label = "Library",
          icon = Icons.RoundedFilled.VideoLibrary,
          isSelected = folderViewMode == FolderViewMode.MediaLibrary,
          onClick = { browserPreferences.folderViewMode.set(FolderViewMode.MediaLibrary) }
        ),
      )
    ),
    layoutModeSelector = ViewModeSelector(
      label = "Layout",
      firstOptionLabel = "List",
      secondOptionLabel = "Grid",
      firstOptionIcon = Icons.RoundedFilled.ViewList,
      secondOptionIcon = Icons.RoundedFilled.GridView,
      isFirstOptionSelected = activeLayoutMode == MediaLayoutMode.LIST,
      onViewModeChange = { isFirstOption ->
        val newLayout = if (isFirstOption) MediaLayoutMode.LIST else MediaLayoutMode.GRID
        if (isAlbumView) {
          browserPreferences.folderViewFolderLayoutMode.set(newLayout)
          if (!separateFolderVideoLayout) {
            browserPreferences.folderViewVideoLayoutMode.set(newLayout)
          }
        } else {
          browserPreferences.mediaLayoutMode.set(newLayout)
        }
      },
      checkboxLabel = if (isAlbumView) "Only for folder list" else null,
      isCheckboxChecked = separateFolderVideoLayout,
      onCheckboxChange = if (isAlbumView) {
        { checked ->
          browserPreferences.separateFolderVideoLayout.set(checked)
          if (!checked) {
            browserPreferences.folderViewVideoLayoutMode.set(browserPreferences.folderViewFolderLayoutMode.get())
          }
        }
      } else null,
    ),
    visibilityToggles = buildList {
      add(
        VisibilityToggle(
          label = "Full Name",
          checked = unlimitedNameLines,
          onCheckedChange = { appearancePreferences.unlimitedNameLines.set(it) },
        )
      )
      add(
        VisibilityToggle(
          label = "Path",
          checked = showFolderPath,
          onCheckedChange = { browserPreferences.showFolderPath.set(it) },
        )
      )
      add(
        VisibilityToggle(
          label = "Total Media",
          checked = showTotalVideosChip,
          onCheckedChange = { browserPreferences.showTotalVideosChip.set(it) },
        )
      )
      add(
        VisibilityToggle(
          label = "Total Duration",
          checked = showTotalDurationChip,
          onCheckedChange = { browserPreferences.showTotalDurationChip.set(it) },
        )
      )
      add(
        VisibilityToggle(
          label = "Folder Size",
          checked = showTotalSizeChip,
          onCheckedChange = { browserPreferences.showTotalSizeChip.set(it) },
        )
      )
      add(
        VisibilityToggle(
          label = "Date",
          checked = showDateChip,
          onCheckedChange = { browserPreferences.showDateChip.set(it) },
        )
      )
      if (activeLayoutMode == MediaLayoutMode.GRID) {
        add(
          VisibilityToggle(
            label = "Manual Grid Columns",
            checked = manualGridColumnsEnabled,
            onCheckedChange = { enabled ->
              if (enabled) {
                if (isLandscape) {
                  browserPreferences.folderGridColumnsLandscape.set(dynamicFolderColumns)
                  browserPreferences.videoGridColumnsLandscape.set(dynamicVideoColumns)
                } else {
                  browserPreferences.folderGridColumnsPortrait.set(dynamicFolderColumns)
                  browserPreferences.videoGridColumnsPortrait.set(dynamicVideoColumns)
                }
              }
              browserPreferences.manualGridColumnsEnabled.set(enabled)
            },
          )
        )
        add(
          VisibilityToggle(
            label = "Folder Thumbnails",
            checked = showFolderThumbnails,
            onCheckedChange = { browserPreferences.showFolderThumbnails.set(it) },
          )
        )
        add(
          VisibilityToggle(
            label = "Center Titles",
            checked = centerGridTitles,
            onCheckedChange = { browserPreferences.centerGridTitles.set(it) },
          )
        )
      }
    },
    folderGridColumnSelector = folderGridColumnSelector,
    videoGridColumnSelector = videoGridColumnSelector,
  )
}

@Composable
fun VideoSortDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  sortType: VideoSortType,
  sortOrder: SortOrder,
  onSortTypeChange: (VideoSortType) -> Unit,
  onSortOrderChange: (SortOrder) -> Unit,
  isDualPane: Boolean = false,
  isFolderView: Boolean = true,
) {
  val browserPreferences = koinInject<BrowserPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val showThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
  val showSizeChip by browserPreferences.showSizeChip.collectAsState()
  val showResolutionChip by browserPreferences.showResolutionChip.collectAsState()
  val showFramerateInResolution by browserPreferences.showFramerateInResolution.collectAsState()
  val showProgressBar by browserPreferences.showProgressBar.collectAsState()
  val showDateChip by browserPreferences.showDateChip.collectAsState()
  val showSubtitleIndicator by browserPreferences.showSubtitleIndicator.collectAsState()
  val showExtensionField by browserPreferences.showExtensionField.collectAsState()
  val showDurationField by browserPreferences.showDurationField.collectAsState()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val folderViewVideoLayoutMode by browserPreferences.folderViewVideoLayoutMode.collectAsState()
  val folderViewFolderLayoutMode by browserPreferences.folderViewFolderLayoutMode.collectAsState()
  val separateFolderVideoLayout by browserPreferences.separateFolderVideoLayout.collectAsState()
  val mediaLayoutMode by browserPreferences.mediaLayoutMode.collectAsState()
  val folderViewMode by browserPreferences.folderViewMode.collectAsState()
  val centerGridTitles by browserPreferences.centerGridTitles.collectAsState()
  val manualGridColumnsEnabled by browserPreferences.manualGridColumnsEnabled.collectAsState()
  val folderGridColumnsPortrait by browserPreferences.folderGridColumnsPortrait.collectAsState()
  val folderGridColumnsLandscape by browserPreferences.folderGridColumnsLandscape.collectAsState()
  val videoGridColumnsPortrait by browserPreferences.videoGridColumnsPortrait.collectAsState()
  val videoGridColumnsLandscape by browserPreferences.videoGridColumnsLandscape.collectAsState()

  val activeLayoutMode = if (isFolderView) folderViewVideoLayoutMode else mediaLayoutMode

  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val isTablet = configuration.smallestScreenWidthDp >= 600
  val maxColumns = if (isTablet || isLandscape) 8 else 4

  val folderGridColumns = if (isLandscape) folderGridColumnsLandscape else folderGridColumnsPortrait
  val videoGridColumns = if (isLandscape) videoGridColumnsLandscape else videoGridColumnsPortrait

  val screenWidthDp = configuration.screenWidthDp.dp
  val contentHorizontalPadding = 8.dp
  val itemSpacing = 2.dp
  val folderPaneWidth = if (isDualPane) screenWidthDp * 0.4f else screenWidthDp
  val videoPaneWidth = if (isDualPane) screenWidthDp * 0.6f else screenWidthDp

  val usableFolderWidth = folderPaneWidth - (contentHorizontalPadding * 2) - itemSpacing
  val usableVideoWidth = videoPaneWidth - (contentHorizontalPadding * 2) - itemSpacing

  val folderMinWidth = 100.dp
  val videoMinWidth = 130.dp
  val dynamicFolderColumns = (usableFolderWidth / folderMinWidth).toInt().coerceIn(1, maxColumns)
  val dynamicVideoColumns = (usableVideoWidth / videoMinWidth).toInt().coerceIn(1, maxColumns)

  val folderGridColumnSelector = if (activeLayoutMode == MediaLayoutMode.GRID && manualGridColumnsEnabled) {
    GridColumnSelector(
      label = "Folder Grid Columns (${if (isLandscape) "Landscape" else "Portrait"})",
      currentValue = folderGridColumns.coerceIn(1, maxColumns),
      onValueChange = {
        if (isLandscape) browserPreferences.folderGridColumnsLandscape.set(it)
        else browserPreferences.folderGridColumnsPortrait.set(it)
      },
      valueRange = 1f..maxColumns.toFloat(),
      steps = maxColumns - 2,
    )
  } else null

  val videoGridColumnSelector = if (activeLayoutMode == MediaLayoutMode.GRID && manualGridColumnsEnabled) {
    GridColumnSelector(
      label = "Video Grid Columns (${if (isLandscape) "Landscape" else "Portrait"})",
      currentValue = videoGridColumns.coerceIn(1, maxColumns),
      onValueChange = {
        if (isLandscape) browserPreferences.videoGridColumnsLandscape.set(it)
        else browserPreferences.videoGridColumnsPortrait.set(it)
      },
      valueRange = 1f..maxColumns.toFloat(),
      steps = maxColumns - 2,
    )
  } else null

  SortDialog(
    isOpen = isOpen,
    onDismiss = onDismiss,
    title = stringResource(R.string.sort_view_options),
    sortType = sortType.displayName,
    onSortTypeChange = { typeName ->
      VideoSortType.entries.find { it.displayName == typeName }?.let(onSortTypeChange)
    },
    sortOrderAsc = sortOrder.isAscending,
    onSortOrderChange = { isAsc ->
      onSortOrderChange(if (isAsc) SortOrder.Ascending else SortOrder.Descending)
    },
    types =
      listOf(
        VideoSortType.Title.displayName,
        VideoSortType.Duration.displayName,
        VideoSortType.Date.displayName,
        VideoSortType.Size.displayName,
      ),
    icons =
      listOf(
        Icons.RoundedFilled.Title,
        Icons.RoundedFilled.AccessTime,
        Icons.RoundedFilled.CalendarToday,
        Icons.RoundedFilled.SwapVert,
      ),
    getLabelForType = { type, _ ->
      when (type) {
        VideoSortType.Title.displayName -> Pair("A-Z", "Z-A")
        VideoSortType.Duration.displayName -> Pair("Shortest", "Longest")
        VideoSortType.Date.displayName -> Pair("Oldest", "Newest")
        VideoSortType.Size.displayName -> Pair("Smallest", "Biggest")
        else -> Pair("Asc", "Desc")
      }
    },
    viewModeSelector = MultiViewModeSelector(
      label = "View Mode",
      options = listOf(
        ViewModeOption(
          label = "Folder",
          icon = Icons.RoundedFilled.ViewModule,
          isSelected = folderViewMode == FolderViewMode.AlbumView,
          onClick = { browserPreferences.folderViewMode.set(FolderViewMode.AlbumView) },
        ),
        ViewModeOption(
          label = "Tree",
          icon = Icons.RoundedFilled.AccountTree,
          isSelected = folderViewMode == FolderViewMode.FileManager,
          onClick = { browserPreferences.folderViewMode.set(FolderViewMode.FileManager) },
        ),
        ViewModeOption(
          label = "Library",
          icon = Icons.RoundedFilled.VideoLibrary,
          isSelected = folderViewMode == FolderViewMode.MediaLibrary,
          onClick = { browserPreferences.folderViewMode.set(FolderViewMode.MediaLibrary) },
        ),
      ),
    ),
    layoutModeSelector = ViewModeSelector(
      label = "Layout",
      firstOptionLabel = "List",
      secondOptionLabel = "Grid",
      firstOptionIcon = Icons.RoundedFilled.ViewList,
      secondOptionIcon = Icons.RoundedFilled.GridView,
      isFirstOptionSelected = activeLayoutMode == MediaLayoutMode.LIST,
      onViewModeChange = { isFirstOption ->
        val newLayout = if (isFirstOption) MediaLayoutMode.LIST else MediaLayoutMode.GRID
        if (isFolderView) {
          browserPreferences.folderViewVideoLayoutMode.set(newLayout)
          if (!separateFolderVideoLayout) {
            browserPreferences.folderViewFolderLayoutMode.set(newLayout)
          }
        } else {
          browserPreferences.mediaLayoutMode.set(newLayout)
        }
      },
      checkboxLabel = if (isFolderView) "Only for video list" else null,
      isCheckboxChecked = separateFolderVideoLayout,
      onCheckboxChange = if (isFolderView) {
        { checked ->
          browserPreferences.separateFolderVideoLayout.set(checked)
          if (!checked) {
            browserPreferences.folderViewFolderLayoutMode.set(browserPreferences.folderViewVideoLayoutMode.get())
          }
        }
      } else null,
    ),
    visibilityToggles =
      buildList {
        add(
          VisibilityToggle(
            label = "Thumbnails",
            checked = showThumbnails,
            onCheckedChange = { browserPreferences.showVideoThumbnails.set(it) },
          )
        )
        add(
          VisibilityToggle(
            label = "Extension",
            checked = showExtensionField,
            onCheckedChange = { browserPreferences.showExtensionField.set(it) },
          )
        )
        add(
          VisibilityToggle(
            label = "Duration",
            checked = showDurationField,
            onCheckedChange = { browserPreferences.showDurationField.set(it) },
          )
        )
        add(
          VisibilityToggle(
            label = "Subtitle Indicator",
            checked = showSubtitleIndicator,
            onCheckedChange = { browserPreferences.showSubtitleIndicator.set(it) },
          )
        )
        add(
          VisibilityToggle(
            label = "Full Name",
            checked = unlimitedNameLines,
            onCheckedChange = { appearancePreferences.unlimitedNameLines.set(it) },
          )
        )
        add(
          VisibilityToggle(
            label = "Size",
            checked = showSizeChip,
            onCheckedChange = { browserPreferences.showSizeChip.set(it) },
          )
        )
        add(
          VisibilityToggle(
            label = "Resolution",
            checked = showResolutionChip,
            onCheckedChange = { browserPreferences.showResolutionChip.set(it) },
          )
        )
        add(
          VisibilityToggle(
            label = "Framerate",
            checked = showFramerateInResolution,
            onCheckedChange = { browserPreferences.showFramerateInResolution.set(it) },
          )
        )
        add(
          VisibilityToggle(
            label = "Date",
            checked = showDateChip,
            onCheckedChange = { browserPreferences.showDateChip.set(it) },
          )
        )
        add(
          VisibilityToggle(
            label = "Progress Bar",
            checked = showProgressBar,
            onCheckedChange = { browserPreferences.showProgressBar.set(it) },
          )
        )
        if (mediaLayoutMode == MediaLayoutMode.GRID) {
          add(
            VisibilityToggle(
              label = "Manual Grid Columns",
              checked = manualGridColumnsEnabled,
              onCheckedChange = { enabled ->
                if (enabled) {
                  if (isLandscape) {
                    browserPreferences.folderGridColumnsLandscape.set(dynamicFolderColumns)
                    browserPreferences.videoGridColumnsLandscape.set(dynamicVideoColumns)
                  } else {
                    browserPreferences.folderGridColumnsPortrait.set(dynamicFolderColumns)
                    browserPreferences.videoGridColumnsPortrait.set(dynamicVideoColumns)
                  }
                }
                browserPreferences.manualGridColumnsEnabled.set(enabled)
              },
            )
          )
          add(
            VisibilityToggle(
              label = "Center Titles",
              checked = centerGridTitles,
              onCheckedChange = { browserPreferences.centerGridTitles.set(it) },
            )
          )
        }
      },
    folderGridColumnSelector = folderGridColumnSelector,
    videoGridColumnSelector = videoGridColumnSelector,
  )
}

@Composable
fun FileSystemSortDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  isAtRoot: Boolean = true,
) {
  val browserPreferences = koinInject<BrowserPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val folderViewMode by browserPreferences.folderViewMode.collectAsState()
  val folderSortType by browserPreferences.folderSortType.collectAsState()
  val folderSortOrder by browserPreferences.folderSortOrder.collectAsState()
  val showVideoThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
  val showTotalVideosChip by browserPreferences.showTotalVideosChip.collectAsState()
  val showTotalSizeChip by browserPreferences.showTotalSizeChip.collectAsState()
  val showFolderPath by browserPreferences.showFolderPath.collectAsState()
  val showSizeChip by browserPreferences.showSizeChip.collectAsState()
  val showResolutionChip by browserPreferences.showResolutionChip.collectAsState()
  val showFramerateInResolution by browserPreferences.showFramerateInResolution.collectAsState()
  val showProgressBar by browserPreferences.showProgressBar.collectAsState()
  val showSubtitleIndicator by browserPreferences.showSubtitleIndicator.collectAsState()
  val showExtensionField by browserPreferences.showExtensionField.collectAsState()
  val showDurationField by browserPreferences.showDurationField.collectAsState()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val mediaLayoutMode by browserPreferences.mediaLayoutMode.collectAsState()
  val manualGridColumnsEnabled by browserPreferences.manualGridColumnsEnabled.collectAsState()
  val folderGridColumnsPortrait by browserPreferences.folderGridColumnsPortrait.collectAsState()
  val folderGridColumnsLandscape by browserPreferences.folderGridColumnsLandscape.collectAsState()
  val videoGridColumnsPortrait by browserPreferences.videoGridColumnsPortrait.collectAsState()
  val videoGridColumnsLandscape by browserPreferences.videoGridColumnsLandscape.collectAsState()

  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val isTablet = configuration.smallestScreenWidthDp >= 600
  val maxColumns = if (isTablet || isLandscape) 8 else 4

  val folderGridColumns = if (isLandscape) folderGridColumnsLandscape else folderGridColumnsPortrait
  val videoGridColumns = if (isLandscape) videoGridColumnsLandscape else videoGridColumnsPortrait

  val screenWidthDp = configuration.screenWidthDp.dp
  val contentHorizontalPadding = 8.dp
  val itemSpacing = 2.dp
  val usableWidth = screenWidthDp - (contentHorizontalPadding * 2) - itemSpacing
  val folderMinWidth = 100.dp
  val videoMinWidth = 130.dp
  val dynamicFolderColumns = (usableWidth / folderMinWidth).toInt().coerceIn(1, maxColumns)
  val dynamicVideoColumns = (usableWidth / videoMinWidth).toInt().coerceIn(1, maxColumns)

  val folderGridColumnSelector = if (mediaLayoutMode == MediaLayoutMode.GRID && manualGridColumnsEnabled) {
    GridColumnSelector(
      label = "Folder Grid Columns (${if (isLandscape) "Landscape" else "Portrait"})",
      currentValue = folderGridColumns.coerceIn(1, maxColumns),
      onValueChange = {
        if (isLandscape) browserPreferences.folderGridColumnsLandscape.set(it)
        else browserPreferences.folderGridColumnsPortrait.set(it)
      },
      valueRange = 1f..maxColumns.toFloat(),
      steps = maxColumns - 2,
    )
  } else null

  val videoGridColumnSelector = if (mediaLayoutMode == MediaLayoutMode.GRID && manualGridColumnsEnabled) {
    GridColumnSelector(
      label = "Video Grid Columns (${if (isLandscape) "Landscape" else "Portrait"})",
      currentValue = videoGridColumns.coerceIn(1, maxColumns),
      onValueChange = {
        if (isLandscape) browserPreferences.videoGridColumnsLandscape.set(it)
        else browserPreferences.videoGridColumnsPortrait.set(it)
      },
      valueRange = 1f..maxColumns.toFloat(),
      steps = maxColumns - 2,
    )
  } else null

  SortDialog(
    isOpen = isOpen,
    onDismiss = onDismiss,
    title = stringResource(R.string.sort_view_options),
    sortType = folderSortType.displayName,
    onSortTypeChange = { typeName ->
      FolderSortType.entries.find { it.displayName == typeName }?.let {
        browserPreferences.folderSortType.set(it)
      }
    },
    sortOrderAsc = folderSortOrder.isAscending,
    onSortOrderChange = { isAsc ->
      browserPreferences.folderSortOrder.set(
        if (isAsc) SortOrder.Ascending
        else SortOrder.Descending,
      )
    },
    types = listOf(
      FolderSortType.Title.displayName,
      FolderSortType.Date.displayName,
      FolderSortType.Size.displayName,
    ),
    icons = listOf(
      Icons.RoundedFilled.Title,
      Icons.RoundedFilled.CalendarToday,
      Icons.RoundedFilled.SwapVert,
    ),
    getLabelForType = { type, _ ->
      when (type) {
        FolderSortType.Title.displayName -> Pair("A-Z", "Z-A")
        FolderSortType.Date.displayName -> Pair("Oldest", "Newest")
        FolderSortType.Size.displayName -> Pair("Smallest", "Largest")
        else -> Pair("Asc", "Desc")
      }
    },
    showSortOptions = true,
    viewModeSelector = MultiViewModeSelector(
      label = "View Mode",
      options = listOf(
        ViewModeOption(
          label = "Folder",
          icon = Icons.RoundedFilled.ViewModule,
          isSelected = folderViewMode == FolderViewMode.AlbumView,
          onClick = { browserPreferences.folderViewMode.set(FolderViewMode.AlbumView) }
        ),
        ViewModeOption(
          label = "Tree",
          icon = Icons.RoundedFilled.AccountTree,
          isSelected = folderViewMode == FolderViewMode.FileManager,
          onClick = { browserPreferences.folderViewMode.set(FolderViewMode.FileManager) }
        ),
        ViewModeOption(
          label = "Library",
          icon = Icons.RoundedFilled.VideoLibrary,
          isSelected = folderViewMode == FolderViewMode.MediaLibrary,
          onClick = { browserPreferences.folderViewMode.set(FolderViewMode.MediaLibrary) }
        ),
      )
    ),
    layoutModeSelector = ViewModeSelector(
      label = "Layout",
      firstOptionLabel = "List",
      secondOptionLabel = "Grid",
      firstOptionIcon = Icons.RoundedFilled.ViewList,
      secondOptionIcon = Icons.RoundedFilled.GridView,
      isFirstOptionSelected = mediaLayoutMode == MediaLayoutMode.LIST,
      onViewModeChange = { isFirstOption ->
        browserPreferences.mediaLayoutMode.set(
          if (isFirstOption) MediaLayoutMode.LIST else MediaLayoutMode.GRID
        )
      },
    ),
    folderGridColumnSelector = folderGridColumnSelector,
    videoGridColumnSelector = videoGridColumnSelector,
    enableViewModeOptions = isAtRoot,
    enableLayoutModeOptions = true, // Enabled layout selection
    visibilityToggles = buildList {
      add(
        VisibilityToggle(
          label = "Video Thumbnails",
          checked = showVideoThumbnails,
          onCheckedChange = { browserPreferences.showVideoThumbnails.set(it) },
        )
      )
      add(
        VisibilityToggle(
          label = "Full Name",
          checked = unlimitedNameLines,
          onCheckedChange = { appearancePreferences.unlimitedNameLines.set(it) },
        )
      )
      add(
        VisibilityToggle(
          label = "Extension",
          checked = showExtensionField,
          onCheckedChange = { browserPreferences.showExtensionField.set(it) },
        )
      )
      add(
        VisibilityToggle(
          label = "Duration",
          checked = showDurationField,
          onCheckedChange = { browserPreferences.showDurationField.set(it) },
        )
      )
      add(
        VisibilityToggle(
          label = "Path",
          checked = showFolderPath,
          onCheckedChange = { browserPreferences.showFolderPath.set(it) },
        )
      )
      add(
        VisibilityToggle(
          label = "Total Media",
          checked = showTotalVideosChip,
          onCheckedChange = { browserPreferences.showTotalVideosChip.set(it) },
        )
      )
      add(
        VisibilityToggle(
          label = "Folder Size",
          checked = showTotalSizeChip,
          onCheckedChange = { browserPreferences.showTotalSizeChip.set(it) },
        )
      )
      add(
        VisibilityToggle(
          label = "Size",
          checked = showSizeChip,
          onCheckedChange = { browserPreferences.showSizeChip.set(it) },
        )
      )
      add(
        VisibilityToggle(
          label = "Resolution",
          checked = showResolutionChip,
          onCheckedChange = { browserPreferences.showResolutionChip.set(it) },
        )
      )
      add(
        VisibilityToggle(
          label = "Framerate",
          checked = showFramerateInResolution,
          onCheckedChange = { browserPreferences.showFramerateInResolution.set(it) },
        )
      )
      add(
        VisibilityToggle(
          label = "Subtitle",
          checked = showSubtitleIndicator,
          onCheckedChange = { browserPreferences.showSubtitleIndicator.set(it) },
        )
      )
      add(
        VisibilityToggle(
          label = "Progress Bar",
          checked = showProgressBar,
          onCheckedChange = { browserPreferences.showProgressBar.set(it) },
        )
      )
      if (mediaLayoutMode == MediaLayoutMode.GRID) {
        add(
          VisibilityToggle(
            label = "Manual Grid Columns",
            checked = manualGridColumnsEnabled,
            onCheckedChange = { enabled ->
              if (enabled) {
                if (isLandscape) {
                  browserPreferences.folderGridColumnsLandscape.set(dynamicFolderColumns)
                  browserPreferences.videoGridColumnsLandscape.set(dynamicVideoColumns)
                } else {
                  browserPreferences.folderGridColumnsPortrait.set(dynamicFolderColumns)
                  browserPreferences.videoGridColumnsPortrait.set(dynamicVideoColumns)
                }
              }
              browserPreferences.manualGridColumnsEnabled.set(enabled)
            },
          )
        )
      }
    },
  )
}
