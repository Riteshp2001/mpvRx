/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.AudiobookSortType
import app.gyrolet.mpvrx.preferences.FolderSortType
import app.gyrolet.mpvrx.preferences.FolderViewMode
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.NetworkSortType
import app.gyrolet.mpvrx.preferences.PlaylistSortType
import app.gyrolet.mpvrx.preferences.RecentSortType
import app.gyrolet.mpvrx.preferences.SortOrder
import app.gyrolet.mpvrx.preferences.VideoSortType
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.browser.music.MusicSortField
import app.gyrolet.mpvrx.ui.browser.music.MusicSortOrder
import app.gyrolet.mpvrx.ui.browser.music.MusicViewMode
import app.gyrolet.mpvrx.ui.icons.Icons
import org.koin.compose.koinInject

@Composable
fun PlaylistSortDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  isLibrary: Boolean = false,
  isM3uPlaylist: Boolean = false,
  availableWidthDp: Int? = null,
) {
  if (!isOpen) return
  val preferences = koinInject<BrowserPreferences>()
  val viewPreferences = if (isLibrary) preferences.playlistView else preferences.playlistItemView
  val typePreference = if (isLibrary) preferences.playlistSortType else preferences.playlistItemSortType
  val orderPreference = if (isLibrary) preferences.playlistSortOrder else preferences.playlistItemSortOrder
  val thumbnailPreference = viewPreferences.showThumbnails
  val portraitColumnsPreference = viewPreferences.gridColumnsPortrait
  val landscapeColumnsPreference = viewPreferences.gridColumnsLandscape
  val sortType by typePreference.collectAsState()
  val sortOrder by orderPreference.collectAsState()
  val layoutMode by viewPreferences.layoutMode.collectAsState()
  val showThumbnails by thumbnailPreference.collectAsState()
  val showLocation by viewPreferences.showLocation.collectAsState()
  val showCategory by viewPreferences.showCategory.collectAsState()
  val showStreamDetails by viewPreferences.showStreamDetails.collectAsState()
  val showExtension by viewPreferences.showExtensionField.collectAsState()
  val showCount by viewPreferences.showItemCount.collectAsState()
  val fullNames by viewPreferences.unlimitedNameLines.collectAsState()
  val centerTitles by viewPreferences.centerGridTitles.collectAsState()
  val manualGrid by viewPreferences.manualGridColumnsEnabled.collectAsState()
  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val landscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val columnsPreference = if (landscape) landscapeColumnsPreference else portraitColumnsPreference
  val requestedColumns by columnsPreference.collectAsState()
  val maxColumns = app.gyrolet.mpvrx.ui.browser.playlist.playlistGridColumnLimit(
    availableWidthDp ?: configuration.screenWidthDp,
    isLibrary,
  )
  val columns = if (requestedColumns > 0) requestedColumns.coerceIn(1, maxColumns) else maxColumns
  val labels = mapOf(
    PlaylistSortType.Original to stringResource(R.string.playlist_sort_original),
    PlaylistSortType.Name to stringResource(R.string.ui_name),
    PlaylistSortType.Location to stringResource(R.string.playlist_location),
    PlaylistSortType.DateAdded to stringResource(R.string.playlist_date_added),
    PlaylistSortType.LastPlayed to stringResource(R.string.video_swipe_last_played),
    PlaylistSortType.ItemCount to stringResource(R.string.playlist_item_count),
    PlaylistSortType.Category to stringResource(R.string.playlist_category),
  )
  val types = buildList {
    add(PlaylistSortType.Original)
    add(PlaylistSortType.Name)
    add(PlaylistSortType.Location)
    add(PlaylistSortType.DateAdded)
    add(if (isLibrary) PlaylistSortType.ItemCount else PlaylistSortType.LastPlayed)
    if (isM3uPlaylist) add(PlaylistSortType.Category)
  }
  val ascendingLabel = stringResource(R.string.playlist_sort_ascending)
  val descendingLabel = stringResource(R.string.playlist_sort_descending)

  SortDialog(
    isOpen = isOpen,
    onDismiss = onDismiss,
    title = stringResource(R.string.sort_view_options),
    sortType = labels.getValue(sortType.takeIf { it in types } ?: PlaylistSortType.Original),
    onSortTypeChange = { selected -> types.firstOrNull { labels[it] == selected }?.let(typePreference::set) },
    sortOrderAsc = sortOrder.isAscending,
    onSortOrderChange = { orderPreference.set(if (it) SortOrder.Ascending else SortOrder.Descending) },
    types = types.map(labels::getValue),
    icons = types.map { type ->
      when (type) {
        PlaylistSortType.Original, PlaylistSortType.ItemCount -> Icons.RoundedFilled.PlaylistPlay
        PlaylistSortType.Name -> Icons.RoundedFilled.Title
        PlaylistSortType.Location, PlaylistSortType.Category -> Icons.RoundedFilled.Folder
        PlaylistSortType.DateAdded -> Icons.RoundedFilled.CalendarToday
        PlaylistSortType.LastPlayed -> Icons.RoundedFilled.AccessTime
      }
    },
    getLabelForType = { type, _ ->
      if (type == labels[PlaylistSortType.Name]) "A-Z" to "Z-A" else ascendingLabel to descendingLabel
    },
    layoutModeSelector = ViewModeSelector(
      label = stringResource(R.string.playlist_layout),
      firstOptionLabel = stringResource(R.string.playlist_view_list),
      secondOptionLabel = stringResource(R.string.playlist_view_grid),
      firstOptionIcon = Icons.RoundedFilled.ViewList,
      secondOptionIcon = Icons.RoundedFilled.GridView,
      isFirstOptionSelected = layoutMode == MediaLayoutMode.LIST,
      onViewModeChange = { viewPreferences.layoutMode.set(if (it) MediaLayoutMode.LIST else MediaLayoutMode.GRID) },
    ),
    visibilityToggles = buildList {
      add(
        VisibilityToggle(
          stringResource(R.string.pref_appearance_category_thumbnails), showThumbnails, thumbnailPreference::set,
        ),
      )
      add(
        VisibilityToggle(
          stringResource(R.string.playlist_full_names), fullNames, viewPreferences.unlimitedNameLines::set,
        ),
      )
      add(
        VisibilityToggle(stringResource(R.string.playlist_location), showLocation, viewPreferences.showLocation::set),
      )
      if (isLibrary) {
        add(
          VisibilityToggle(stringResource(R.string.playlist_item_count), showCount, viewPreferences.showItemCount::set),
        )
      } else if (!isM3uPlaylist) {
        add(
          VisibilityToggle(
            stringResource(R.string.playlist_extension), showExtension, viewPreferences.showExtensionField::set,
          ),
        )
      }
      if (isM3uPlaylist) {
        add(
          VisibilityToggle(stringResource(R.string.playlist_category), showCategory, viewPreferences.showCategory::set),
        )
        add(
          VisibilityToggle(
            stringResource(R.string.playlist_stream_details), showStreamDetails, viewPreferences.showStreamDetails::set,
          ),
        )
      }
      if (layoutMode == MediaLayoutMode.GRID) {
        add(
          VisibilityToggle(
            stringResource(R.string.playlist_center_titles), centerTitles, viewPreferences.centerGridTitles::set,
          ),
        )
      }
    },
    manualGridToggle = if (layoutMode == MediaLayoutMode.GRID) {
      VisibilityToggle(
        label = stringResource(R.string.playlist_manual_grid),
        checked = manualGrid,
        onCheckedChange = { enabled ->
          if (enabled && requestedColumns <= 0) columnsPreference.set(columns)
          viewPreferences.manualGridColumnsEnabled.set(enabled)
        },
      )
    } else null,
    videoGridColumnSelector = if (layoutMode == MediaLayoutMode.GRID && manualGrid && maxColumns > 1) {
      GridColumnSelector(
        label = stringResource(
          if (landscape) R.string.playlist_columns_landscape else R.string.playlist_columns_portrait,
        ),
        currentValue = columns,
        onValueChange = columnsPreference::set,
        valueRange = 1f..maxColumns.toFloat(),
        steps = maxColumns - 2,
      )
    } else null,
  )
}

@Composable
fun RecentSortDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  isAudioTab: Boolean,
  availableWidthDp: Int? = null,
) {
  if (!isOpen) return
  val preferences = koinInject<BrowserPreferences>()
  val viewPreferences = preferences.recentView
  val sortType by preferences.recentSortType.collectAsState()
  val sortOrder by preferences.recentSortOrder.collectAsState()
  val layoutMode by viewPreferences.layoutMode.collectAsState()
  val showThumbnails by viewPreferences.showThumbnails.collectAsState()
  val fullNames by viewPreferences.unlimitedNameLines.collectAsState()
  val showExtension by viewPreferences.showExtensionField.collectAsState()
  val showDuration by viewPreferences.showDurationField.collectAsState()
  val showCount by viewPreferences.showItemCount.collectAsState()
  val centerTitles by viewPreferences.centerGridTitles.collectAsState()
  val manualGrid by viewPreferences.manualGridColumnsEnabled.collectAsState()
  val audioCoverArtSize by viewPreferences.audioCoverArtSize.collectAsState()
  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val landscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val columnsPreference = if (landscape) viewPreferences.gridColumnsLandscape else viewPreferences.gridColumnsPortrait
  val requestedColumns by columnsPreference.collectAsState()
  val isTelevision =
    app.gyrolet.mpvrx.utils.device.DeviceFormFactor.isTelevision(androidx.compose.ui.platform.LocalContext.current)
  val maxColumns = app.gyrolet.mpvrx.ui.browser.recentlyplayed.recentGridColumns(
    availableWidthDp ?: configuration.screenWidthDp,
    isTelevision = isTelevision,
  )
  val columns = if (requestedColumns > 0) requestedColumns.coerceIn(1, maxColumns) else maxColumns
  val labels = mapOf(
    RecentSortType.LastPlayed to stringResource(R.string.video_swipe_last_played),
    RecentSortType.Name to stringResource(R.string.ui_name),
  )
  val ascendingLabel = stringResource(R.string.playlist_sort_ascending)
  val descendingLabel = stringResource(R.string.playlist_sort_descending)

  SortDialog(
    isOpen = isOpen,
    onDismiss = onDismiss,
    title = stringResource(R.string.sort_view_options),
    sortType = labels.getValue(sortType),
    onSortTypeChange = { selected ->
      RecentSortType.entries.firstOrNull { labels[it] == selected }?.let(preferences.recentSortType::set)
    },
    sortOrderAsc = sortOrder.isAscending,
    onSortOrderChange = { preferences.recentSortOrder.set(if (it) SortOrder.Ascending else SortOrder.Descending) },
    types = RecentSortType.entries.map(labels::getValue),
    icons = listOf(Icons.RoundedFilled.AccessTime, Icons.RoundedFilled.Title),
    getLabelForType = { type, _ ->
      if (type == labels[RecentSortType.Name]) "A-Z" to "Z-A" else ascendingLabel to descendingLabel
    },
    layoutModeSelector = ViewModeSelector(
      label = stringResource(R.string.playlist_layout),
      firstOptionLabel = stringResource(R.string.playlist_view_list),
      secondOptionLabel = stringResource(R.string.playlist_view_grid),
      firstOptionIcon = Icons.RoundedFilled.ViewList,
      secondOptionIcon = Icons.RoundedFilled.GridView,
      isFirstOptionSelected = layoutMode == MediaLayoutMode.LIST,
      onViewModeChange = { viewPreferences.layoutMode.set(if (it) MediaLayoutMode.LIST else MediaLayoutMode.GRID) },
    ),
    visibilityToggles = buildList {
      add(
        VisibilityToggle(
          stringResource(R.string.pref_appearance_category_thumbnails),
          showThumbnails,
          viewPreferences.showThumbnails::set,
        ),
      )
      add(
        VisibilityToggle(
          stringResource(R.string.playlist_full_names), fullNames, viewPreferences.unlimitedNameLines::set,
        ),
      )
      add(
        VisibilityToggle(
          stringResource(R.string.playlist_extension), showExtension, viewPreferences.showExtensionField::set,
        ),
      )
      add(
        VisibilityToggle(stringResource(R.string.ui_duration), showDuration, viewPreferences.showDurationField::set),
      )
      add(
        VisibilityToggle(stringResource(R.string.playlist_item_count), showCount, viewPreferences.showItemCount::set),
      )
      if (layoutMode == MediaLayoutMode.GRID) {
        add(
          VisibilityToggle(
            stringResource(R.string.playlist_center_titles), centerTitles, viewPreferences.centerGridTitles::set,
          ),
        )
      }
    },
    manualGridToggle = if (layoutMode == MediaLayoutMode.GRID) {
      VisibilityToggle(
        label = stringResource(R.string.playlist_manual_grid),
        checked = manualGrid,
        onCheckedChange = { enabled ->
          if (enabled && requestedColumns <= 0) columnsPreference.set(columns)
          viewPreferences.manualGridColumnsEnabled.set(enabled)
        },
      )
    } else null,
    videoGridColumnSelector =
      when {
        isAudioTab && layoutMode == MediaLayoutMode.LIST ->
          GridColumnSelector(
            label = stringResource(R.string.cover_art_size),
            currentValue = audioCoverArtSize,
            onValueChange = viewPreferences.audioCoverArtSize::set,
            valueRange = 56f..126f,
            steps = 40,
            unitSuffix = "dp",
          )
        layoutMode == MediaLayoutMode.GRID && manualGrid && maxColumns > 1 ->
          GridColumnSelector(
            label = stringResource(
              if (landscape) R.string.playlist_columns_landscape else R.string.playlist_columns_portrait,
            ),
            currentValue = columns,
            onValueChange = columnsPreference::set,
            valueRange = 1f..maxColumns.toFloat(),
            steps = maxColumns - 2,
          )
        else -> null
      },
  )
}

@Composable
fun FolderSortDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  sortType: FolderSortType,
  sortOrder: SortOrder,
  onSortTypeChange: (FolderSortType) -> Unit,
  onSortOrderChange: (SortOrder) -> Unit,
  isDualPane: Boolean = false,
  embeddedAlbumView: Boolean = false,
  pickerMode: Boolean = false,
  availableWidthDp: Int? = null,
  fixedLayoutMode: MediaLayoutMode? = null,
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
  val folderGridColumnsDualPanePortrait by browserPreferences.folderGridColumnsDualPanePortrait.collectAsState()
  val folderGridColumnsDualPaneLandscape by browserPreferences.folderGridColumnsDualPaneLandscape.collectAsState()
  val videoGridColumnsDualPanePortrait by browserPreferences.videoGridColumnsDualPanePortrait.collectAsState()
  val videoGridColumnsDualPaneLandscape by browserPreferences.videoGridColumnsDualPaneLandscape.collectAsState()

  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val isTablet = configuration.smallestScreenWidthDp >= 600

  val screenWidthDp = (availableWidthDp ?: configuration.screenWidthDp).dp
  val contentHorizontalPadding = 8.dp
  val itemSpacing = 2.dp
  val folderPaneWidth = if (isDualPane) screenWidthDp * 0.4f else screenWidthDp
  val videoPaneWidth = if (isDualPane) screenWidthDp * 0.6f else screenWidthDp

  val usableFolderWidth = folderPaneWidth - (contentHorizontalPadding * 2) - itemSpacing
  val usableVideoWidth = videoPaneWidth - (contentHorizontalPadding * 2) - itemSpacing

  val isTelevision =
    app.gyrolet.mpvrx.utils.device.DeviceFormFactor.isTelevision(androidx.compose.ui.platform.LocalContext.current)
  val folderMinWidth = if (isTelevision) 160.dp else 90.dp
  val videoMinWidth = if (isTelevision) 240.dp else 130.dp
  val dynamicFolderColumns = (usableFolderWidth / folderMinWidth).toInt().coerceAtLeast(1)
  val dynamicVideoColumns = (usableVideoWidth / videoMinWidth).toInt().coerceAtLeast(1)

  val folderGridColumns =
    if (isDualPane) {
      val dualPref = if (isLandscape) folderGridColumnsDualPaneLandscape else folderGridColumnsDualPanePortrait
      if (dualPref > 0) dualPref else dynamicFolderColumns
    } else {
      val pref = if (isLandscape) folderGridColumnsLandscape else folderGridColumnsPortrait
      if (pref > 0) pref else dynamicFolderColumns
    }
  val videoGridColumns =
    if (isDualPane) {
      val dualPref = if (isLandscape) videoGridColumnsDualPaneLandscape else videoGridColumnsDualPanePortrait
      if (dualPref > 0) dualPref else dynamicVideoColumns
    } else {
      val pref = if (isLandscape) videoGridColumnsLandscape else videoGridColumnsPortrait
      if (pref > 0) pref else dynamicVideoColumns
    }

  val maxFolderColumns = if (pickerMode) {
    app.gyrolet.mpvrx.ui.browser.playlist.playlistGridColumnLimit(screenWidthDp.value.toInt(), true)
  } else maxOf(if (isTablet || isLandscape) 8 else 4, dynamicFolderColumns + 3).coerceIn(4, 16)
  val maxVideoColumns = if (pickerMode) {
    app.gyrolet.mpvrx.ui.browser.playlist.playlistGridColumnLimit(screenWidthDp.value.toInt())
  } else maxOf(if (isTablet || isLandscape) 8 else 4, dynamicVideoColumns + 3).coerceIn(4, 16)

  // The Music > Folders tab embeds the album-style folder list regardless of the Home screen's
  // selected folder view. Its controls must therefore read and update the album-view preferences,
  // and must not offer navigation modes that the embedded host cannot display.
  val isAlbumView = embeddedAlbumView || folderViewMode == FolderViewMode.AlbumView
  val activeLayoutMode = fixedLayoutMode ?: if (isAlbumView) folderViewFolderLayoutMode else mediaLayoutMode

  val folderGridColumnSelector =
    if (activeLayoutMode == MediaLayoutMode.GRID && manualGridColumnsEnabled && maxFolderColumns > 1) {
      GridColumnSelector(
        label = "Folder (${if (isLandscape) "Landscape" else "Portrait"})",
        currentValue = folderGridColumns.coerceIn(1, maxFolderColumns),
        onValueChange = {
          if (isDualPane) {
            if (isLandscape) {
              browserPreferences.folderGridColumnsDualPaneLandscape.set(it)
            } else {
              browserPreferences.folderGridColumnsDualPanePortrait.set(it)
            }
          } else {
            if (isLandscape) {
              browserPreferences.folderGridColumnsLandscape.set(it)
            } else {
              browserPreferences.folderGridColumnsPortrait.set(it)
            }
          }
        },
        valueRange = 1f..maxFolderColumns.toFloat(),
        steps = maxFolderColumns - 2,
      )
    } else {
      null
    }

  val videoGridColumnSelector =
    if (activeLayoutMode == MediaLayoutMode.GRID && manualGridColumnsEnabled && maxVideoColumns > 1) {
      GridColumnSelector(
        label = "Video (${if (isLandscape) "Landscape" else "Portrait"})",
        currentValue = videoGridColumns.coerceIn(1, maxVideoColumns),
        onValueChange = {
          if (isDualPane) {
            if (isLandscape) {
              browserPreferences.videoGridColumnsDualPaneLandscape.set(it)
            } else {
              browserPreferences.videoGridColumnsDualPanePortrait.set(it)
            }
          } else {
            if (isLandscape) {
              browserPreferences.videoGridColumnsLandscape.set(it)
            } else {
              browserPreferences.videoGridColumnsPortrait.set(it)
            }
          }
        },
        valueRange = 1f..maxVideoColumns.toFloat(),
        steps = maxVideoColumns - 2,
      )
    } else {
      null
    }

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
    types =
      listOf(
        FolderSortType.Title.displayName,
        FolderSortType.Date.displayName,
        FolderSortType.Size.displayName,
        FolderSortType.VideoCount.displayName,
      ),
    icons =
      listOf(
        Icons.RoundedFilled.Title,
        Icons.RoundedFilled.CalendarToday,
        Icons.RoundedFilled.SwapVert,
        Icons.RoundedFilled.VideoLibrary,
      ),
    getLabelForType = { type, _ ->
      when (type) {
        FolderSortType.Title.displayName -> Pair("A-Z", "Z-A")
        FolderSortType.Date.displayName -> Pair("Oldest", "Newest")
        FolderSortType.Size.displayName -> Pair("Smallest", "Largest")
        FolderSortType.VideoCount.displayName -> Pair("Fewest", "Most")
        else -> Pair("Asc", "Desc")
      }
    },
    showSortOptions = isAlbumView,
    viewModeSelector =
      if (embeddedAlbumView) {
        null
      } else {
        MultiViewModeSelector(
          label = "View Mode",
          options =
            listOf(
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
        )
      },
    layoutModeSelector = if (fixedLayoutMode == null) {
      ViewModeSelector(
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
        onCheckboxChange =
          if (isAlbumView) {
            { checked ->
              browserPreferences.separateFolderVideoLayout.set(checked)
              if (!checked) {
                browserPreferences.folderViewVideoLayoutMode.set(browserPreferences.folderViewFolderLayoutMode.get())
              }
            }
          } else {
            null
          },
      )
    } else null,
    visibilityToggles =
      buildList {
        add(
          VisibilityToggle(
            label = "Full Name",
            checked = unlimitedNameLines,
            onCheckedChange = { appearancePreferences.unlimitedNameLines.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Path",
            checked = showFolderPath,
            onCheckedChange = { browserPreferences.showFolderPath.set(it) },
            enabled = activeLayoutMode == MediaLayoutMode.LIST,
          ),
        )
        add(
          VisibilityToggle(
            label = "Total Media",
            checked = showTotalVideosChip,
            onCheckedChange = { browserPreferences.showTotalVideosChip.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Total Duration",
            checked = showTotalDurationChip,
            onCheckedChange = { browserPreferences.showTotalDurationChip.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Folder Size",
            checked = showTotalSizeChip,
            onCheckedChange = { browserPreferences.showTotalSizeChip.set(it) },
            enabled = activeLayoutMode == MediaLayoutMode.LIST,
          ),
        )
        add(
          VisibilityToggle(
            label = "Date",
            checked = showDateChip,
            onCheckedChange = { browserPreferences.showDateChip.set(it) },
            enabled = activeLayoutMode == MediaLayoutMode.LIST,
          ),
        )
        if (activeLayoutMode == MediaLayoutMode.GRID) {
          add(
            VisibilityToggle(
              label = "Folder Thumbnails",
              checked = showFolderThumbnails,
              onCheckedChange = { browserPreferences.showFolderThumbnails.set(it) },
            ),
          )
          add(
            VisibilityToggle(
              label = "Center Titles",
              checked = centerGridTitles,
              onCheckedChange = { browserPreferences.centerGridTitles.set(it) },
            ),
          )
        }
      },
    manualGridToggle =
      VisibilityToggle(
        label = "Manual Grid",
        checked = manualGridColumnsEnabled,
        onCheckedChange = { enabled ->
          if (enabled) {
            if (isDualPane) {
              if (isLandscape) {
                browserPreferences.folderGridColumnsDualPaneLandscape.set(dynamicFolderColumns)
                browserPreferences.videoGridColumnsDualPaneLandscape.set(dynamicVideoColumns)
              } else {
                browserPreferences.folderGridColumnsDualPanePortrait.set(dynamicFolderColumns)
                browserPreferences.videoGridColumnsDualPanePortrait.set(dynamicVideoColumns)
              }
            } else {
              if (isLandscape) {
                browserPreferences.folderGridColumnsLandscape.set(dynamicFolderColumns)
                browserPreferences.videoGridColumnsLandscape.set(dynamicVideoColumns)
              } else {
                browserPreferences.folderGridColumnsPortrait.set(dynamicFolderColumns)
                browserPreferences.videoGridColumnsPortrait.set(dynamicVideoColumns)
              }
            }
          } else {
            browserPreferences.folderGridColumnsPortrait.set(0)
            browserPreferences.folderGridColumnsLandscape.set(0)
            browserPreferences.videoGridColumnsPortrait.set(0)
            browserPreferences.videoGridColumnsLandscape.set(0)
            browserPreferences.folderGridColumnsDualPanePortrait.set(0)
            browserPreferences.folderGridColumnsDualPaneLandscape.set(0)
            browserPreferences.videoGridColumnsDualPanePortrait.set(0)
            browserPreferences.videoGridColumnsDualPaneLandscape.set(0)
          }
          browserPreferences.manualGridColumnsEnabled.set(enabled)
        },
        enabled = !pickerMode || activeLayoutMode == MediaLayoutMode.GRID,
      ),
    folderGridColumnSelector = folderGridColumnSelector,
    videoGridColumnSelector = videoGridColumnSelector.takeUnless { pickerMode },
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
  enableViewModeOptions: Boolean = true,
  enableLayoutModeOptions: Boolean = true,
  pickerMode: Boolean = false,
  availableWidthDp: Int? = null,
  fixedLayoutMode: MediaLayoutMode? = null,
) {
  val browserPreferences = koinInject<BrowserPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val showThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
  val showSizeChip by browserPreferences.showSizeChip.collectAsState()
  val showResolutionChip by browserPreferences.showResolutionChip.collectAsState()
  val showFramerateInResolution by browserPreferences.showFramerateInResolution.collectAsState()
  val showCodecSupportIndicator by browserPreferences.showCodecSupportIndicator.collectAsState()
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
  val folderGridColumnsDualPanePortrait by browserPreferences.folderGridColumnsDualPanePortrait.collectAsState()
  val folderGridColumnsDualPaneLandscape by browserPreferences.folderGridColumnsDualPaneLandscape.collectAsState()
  val videoGridColumnsDualPanePortrait by browserPreferences.videoGridColumnsDualPanePortrait.collectAsState()
  val videoGridColumnsDualPaneLandscape by browserPreferences.videoGridColumnsDualPaneLandscape.collectAsState()

  val activeLayoutMode = fixedLayoutMode ?: if (isFolderView) folderViewVideoLayoutMode else mediaLayoutMode

  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val isTablet = configuration.smallestScreenWidthDp >= 600

  val screenWidthDp = (availableWidthDp ?: configuration.screenWidthDp).dp
  val contentHorizontalPadding = 8.dp
  val itemSpacing = 2.dp
  val folderPaneWidth = if (isDualPane) screenWidthDp * 0.4f else screenWidthDp
  val videoPaneWidth = if (isDualPane) screenWidthDp * 0.6f else screenWidthDp

  val usableFolderWidth = folderPaneWidth - (contentHorizontalPadding * 2) - itemSpacing
  val usableVideoWidth = videoPaneWidth - (contentHorizontalPadding * 2) - itemSpacing

  val isTelevision =
    app.gyrolet.mpvrx.utils.device.DeviceFormFactor.isTelevision(androidx.compose.ui.platform.LocalContext.current)
  val folderMinWidth = if (isTelevision) 160.dp else 90.dp
  val videoMinWidth = if (isTelevision) 240.dp else 130.dp
  val dynamicFolderColumns = (usableFolderWidth / folderMinWidth).toInt().coerceAtLeast(1)
  val dynamicVideoColumns = (usableVideoWidth / videoMinWidth).toInt().coerceAtLeast(1)

  val folderGridColumns =
    if (isDualPane) {
      val dualPref = if (isLandscape) folderGridColumnsDualPaneLandscape else folderGridColumnsDualPanePortrait
      if (dualPref > 0) dualPref else dynamicFolderColumns
    } else {
      val pref = if (isLandscape) folderGridColumnsLandscape else folderGridColumnsPortrait
      if (pref > 0) pref else dynamicFolderColumns
    }
  val videoGridColumns =
    if (isDualPane) {
      val dualPref = if (isLandscape) videoGridColumnsDualPaneLandscape else videoGridColumnsDualPanePortrait
      if (dualPref > 0) dualPref else dynamicVideoColumns
    } else {
      val pref = if (isLandscape) videoGridColumnsLandscape else videoGridColumnsPortrait
      if (pref > 0) pref else dynamicVideoColumns
    }

  val maxFolderColumns = if (pickerMode) {
    app.gyrolet.mpvrx.ui.browser.playlist.playlistGridColumnLimit(screenWidthDp.value.toInt(), true)
  } else maxOf(if (isTablet || isLandscape) 8 else 4, dynamicFolderColumns + 3).coerceIn(4, 16)
  val maxVideoColumns = if (pickerMode) {
    app.gyrolet.mpvrx.ui.browser.playlist.playlistGridColumnLimit(screenWidthDp.value.toInt())
  } else maxOf(if (isTablet || isLandscape) 8 else 4, dynamicVideoColumns + 3).coerceIn(4, 16)

  val folderGridColumnSelector =
    if (activeLayoutMode == MediaLayoutMode.GRID && manualGridColumnsEnabled && maxFolderColumns > 1) {
      GridColumnSelector(
        label = "Folder (${if (isLandscape) "Landscape" else "Portrait"})",
        currentValue = folderGridColumns.coerceIn(1, maxFolderColumns),
        onValueChange = {
          if (isDualPane) {
            if (isLandscape) {
              browserPreferences.folderGridColumnsDualPaneLandscape.set(it)
            } else {
              browserPreferences.folderGridColumnsDualPanePortrait.set(it)
            }
          } else {
            if (isLandscape) {
              browserPreferences.folderGridColumnsLandscape.set(it)
            } else {
              browserPreferences.folderGridColumnsPortrait.set(it)
            }
          }
        },
        valueRange = 1f..maxFolderColumns.toFloat(),
        steps = maxFolderColumns - 2,
      )
    } else {
      null
    }

  val videoGridColumnSelector =
    if (activeLayoutMode == MediaLayoutMode.GRID && manualGridColumnsEnabled && maxVideoColumns > 1) {
      GridColumnSelector(
        label = "Video (${if (isLandscape) "Landscape" else "Portrait"})",
        currentValue = videoGridColumns.coerceIn(1, maxVideoColumns),
        onValueChange = {
          if (isDualPane) {
            if (isLandscape) {
              browserPreferences.videoGridColumnsDualPaneLandscape.set(it)
            } else {
              browserPreferences.videoGridColumnsDualPanePortrait.set(it)
            }
          } else {
            if (isLandscape) {
              browserPreferences.videoGridColumnsLandscape.set(it)
            } else {
              browserPreferences.videoGridColumnsPortrait.set(it)
            }
          }
        },
        valueRange = 1f..maxVideoColumns.toFloat(),
        steps = maxVideoColumns - 2,
      )
    } else {
      null
    }

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
    viewModeSelector =
      if (enableViewModeOptions)
        MultiViewModeSelector(
          label = "View Mode",
          options =
            listOf(
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
        )
      else null,
    layoutModeSelector =
      if (enableLayoutModeOptions && fixedLayoutMode == null)
        ViewModeSelector(
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
          onCheckboxChange =
            if (isFolderView) {
              { checked ->
                browserPreferences.separateFolderVideoLayout.set(checked)
                if (!checked) {
                  browserPreferences.folderViewFolderLayoutMode.set(browserPreferences.folderViewVideoLayoutMode.get())
                }
              }
            } else {
              null
            },
        )
      else null,
    visibilityToggles =
      buildList {
        add(
          VisibilityToggle(
            label = "Thumbnails",
            checked = showThumbnails,
            onCheckedChange = { browserPreferences.showVideoThumbnails.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Extension",
            checked = showExtensionField,
            onCheckedChange = { browserPreferences.showExtensionField.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Duration",
            checked = showDurationField,
            onCheckedChange = { browserPreferences.showDurationField.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Subtitle Indicator",
            checked = showSubtitleIndicator,
            onCheckedChange = { browserPreferences.showSubtitleIndicator.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Full Name",
            checked = unlimitedNameLines,
            onCheckedChange = { appearancePreferences.unlimitedNameLines.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Size",
            checked = showSizeChip,
            onCheckedChange = { browserPreferences.showSizeChip.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Resolution",
            checked = showResolutionChip,
            onCheckedChange = { browserPreferences.showResolutionChip.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Framerate",
            checked = showFramerateInResolution,
            onCheckedChange = { browserPreferences.showFramerateInResolution.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Codec support",
            checked = showCodecSupportIndicator,
            onCheckedChange = { browserPreferences.showCodecSupportIndicator.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Date",
            checked = showDateChip,
            onCheckedChange = { browserPreferences.showDateChip.set(it) },
          ),
        )
        if (!pickerMode) {
          add(
            VisibilityToggle(
              label = "Progress Bar",
              checked = showProgressBar,
              onCheckedChange = { browserPreferences.showProgressBar.set(it) },
            ),
          )
        }
        if (activeLayoutMode == MediaLayoutMode.GRID) {
          add(
            VisibilityToggle(
              label = "Center Titles",
              checked = centerGridTitles,
              onCheckedChange = { browserPreferences.centerGridTitles.set(it) },
            ),
          )
        }
      },
    manualGridToggle =
      VisibilityToggle(
        label = "Manual Grid",
        checked = manualGridColumnsEnabled,
        onCheckedChange = { enabled ->
          if (enabled) {
            if (isDualPane) {
              if (isLandscape) {
                browserPreferences.folderGridColumnsDualPaneLandscape.set(dynamicFolderColumns)
                browserPreferences.videoGridColumnsDualPaneLandscape.set(dynamicVideoColumns)
              } else {
                browserPreferences.folderGridColumnsDualPanePortrait.set(dynamicFolderColumns)
                browserPreferences.videoGridColumnsDualPanePortrait.set(dynamicVideoColumns)
              }
            } else {
              if (isLandscape) {
                browserPreferences.folderGridColumnsLandscape.set(dynamicFolderColumns)
                browserPreferences.videoGridColumnsLandscape.set(dynamicVideoColumns)
              } else {
                browserPreferences.folderGridColumnsPortrait.set(dynamicFolderColumns)
                browserPreferences.videoGridColumnsPortrait.set(dynamicVideoColumns)
              }
            }
          } else {
            browserPreferences.folderGridColumnsPortrait.set(0)
            browserPreferences.folderGridColumnsLandscape.set(0)
            browserPreferences.videoGridColumnsPortrait.set(0)
            browserPreferences.videoGridColumnsLandscape.set(0)
            browserPreferences.folderGridColumnsDualPanePortrait.set(0)
            browserPreferences.folderGridColumnsDualPaneLandscape.set(0)
            browserPreferences.videoGridColumnsDualPanePortrait.set(0)
            browserPreferences.videoGridColumnsDualPaneLandscape.set(0)
          }
          browserPreferences.manualGridColumnsEnabled.set(enabled)
        },
        enabled = !pickerMode || activeLayoutMode == MediaLayoutMode.GRID,
      ),
    folderGridColumnSelector = folderGridColumnSelector.takeUnless { pickerMode },
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
  val showCodecSupportIndicator by browserPreferences.showCodecSupportIndicator.collectAsState()
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

  val screenWidthDp = configuration.screenWidthDp.dp
  val contentHorizontalPadding = 8.dp
  val itemSpacing = 2.dp
  val usableWidth = screenWidthDp - (contentHorizontalPadding * 2) - itemSpacing

  val isTelevision =
    app.gyrolet.mpvrx.utils.device.DeviceFormFactor.isTelevision(androidx.compose.ui.platform.LocalContext.current)
  val folderMinWidth = if (isTelevision) 160.dp else 90.dp
  val videoMinWidth = if (isTelevision) 240.dp else 130.dp
  val dynamicFolderColumns = (usableWidth / folderMinWidth).toInt().coerceAtLeast(1)
  val dynamicVideoColumns = (usableWidth / videoMinWidth).toInt().coerceAtLeast(1)

  val folderPref = if (isLandscape) folderGridColumnsLandscape else folderGridColumnsPortrait
  val videoPref = if (isLandscape) videoGridColumnsLandscape else videoGridColumnsPortrait
  val folderGridColumns = if (folderPref > 0) folderPref else dynamicFolderColumns
  val videoGridColumns = if (videoPref > 0) videoPref else dynamicVideoColumns

  val maxFolderColumns = maxOf(if (isTablet || isLandscape) 8 else 4, dynamicFolderColumns + 3).coerceIn(4, 16)
  val maxVideoColumns = maxOf(if (isTablet || isLandscape) 8 else 4, dynamicVideoColumns + 3).coerceIn(4, 16)

  val folderGridColumnSelector =
    if (mediaLayoutMode == MediaLayoutMode.GRID && manualGridColumnsEnabled) {
      GridColumnSelector(
        label = "Folder (${if (isLandscape) "Landscape" else "Portrait"})",
        currentValue = folderGridColumns.coerceIn(1, maxFolderColumns),
        onValueChange = {
          if (isLandscape) {
            browserPreferences.folderGridColumnsLandscape.set(it)
          } else {
            browserPreferences.folderGridColumnsPortrait.set(it)
          }
        },
        valueRange = 1f..maxFolderColumns.toFloat(),
        steps = maxFolderColumns - 2,
      )
    } else {
      null
    }

  val videoGridColumnSelector =
    if (mediaLayoutMode == MediaLayoutMode.GRID && manualGridColumnsEnabled) {
      GridColumnSelector(
        label = "Video (${if (isLandscape) "Landscape" else "Portrait"})",
        currentValue = videoGridColumns.coerceIn(1, maxVideoColumns),
        onValueChange = {
          if (isLandscape) {
            browserPreferences.videoGridColumnsLandscape.set(it)
          } else {
            browserPreferences.videoGridColumnsPortrait.set(it)
          }
        },
        valueRange = 1f..maxVideoColumns.toFloat(),
        steps = maxVideoColumns - 2,
      )
    } else {
      null
    }

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
        if (isAsc) {
          SortOrder.Ascending
        } else {
          SortOrder.Descending
        },
      )
    },
    types =
      listOf(
        FolderSortType.Title.displayName,
        FolderSortType.Date.displayName,
        FolderSortType.Size.displayName,
      ),
    icons =
      listOf(
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
    viewModeSelector =
      MultiViewModeSelector(
        label = "View Mode",
        options =
          listOf(
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
    layoutModeSelector =
      ViewModeSelector(
        label = "Layout",
        firstOptionLabel = "List",
        secondOptionLabel = "Grid",
        firstOptionIcon = Icons.RoundedFilled.ViewList,
        secondOptionIcon = Icons.RoundedFilled.GridView,
        isFirstOptionSelected = mediaLayoutMode == MediaLayoutMode.LIST,
        onViewModeChange = { isFirstOption ->
          browserPreferences.mediaLayoutMode.set(
            if (isFirstOption) MediaLayoutMode.LIST else MediaLayoutMode.GRID,
          )
        },
      ),
    folderGridColumnSelector = folderGridColumnSelector,
    videoGridColumnSelector = videoGridColumnSelector,
    enableViewModeOptions = isAtRoot,
    enableLayoutModeOptions = true, // Enabled layout selection
    visibilityToggles =
      buildList {
        add(
          VisibilityToggle(
            label = "Video Thumbnails",
            checked = showVideoThumbnails,
            onCheckedChange = { browserPreferences.showVideoThumbnails.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Full Name",
            checked = unlimitedNameLines,
            onCheckedChange = { appearancePreferences.unlimitedNameLines.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Extension",
            checked = showExtensionField,
            onCheckedChange = { browserPreferences.showExtensionField.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Duration",
            checked = showDurationField,
            onCheckedChange = { browserPreferences.showDurationField.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Path",
            checked = showFolderPath,
            onCheckedChange = { browserPreferences.showFolderPath.set(it) },
            enabled = mediaLayoutMode == MediaLayoutMode.LIST,
          ),
        )
        add(
          VisibilityToggle(
            label = "Total Media",
            checked = showTotalVideosChip,
            onCheckedChange = { browserPreferences.showTotalVideosChip.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Folder Size",
            checked = showTotalSizeChip,
            onCheckedChange = { browserPreferences.showTotalSizeChip.set(it) },
            enabled = mediaLayoutMode == MediaLayoutMode.LIST,
          ),
        )
        add(
          VisibilityToggle(
            label = "Size",
            checked = showSizeChip,
            onCheckedChange = { browserPreferences.showSizeChip.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Resolution",
            checked = showResolutionChip,
            onCheckedChange = { browserPreferences.showResolutionChip.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Framerate",
            checked = showFramerateInResolution,
            onCheckedChange = { browserPreferences.showFramerateInResolution.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Codec support",
            checked = showCodecSupportIndicator,
            onCheckedChange = { browserPreferences.showCodecSupportIndicator.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Subtitle",
            checked = showSubtitleIndicator,
            onCheckedChange = { browserPreferences.showSubtitleIndicator.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Progress Bar",
            checked = showProgressBar,
            onCheckedChange = { browserPreferences.showProgressBar.set(it) },
          ),
        )
        if (mediaLayoutMode == MediaLayoutMode.GRID) {
          // Additional grid-specific fields if any
        }
      },
    manualGridToggle =
      VisibilityToggle(
        label = "Manual Grid",
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
          } else {
            browserPreferences.folderGridColumnsPortrait.set(0)
            browserPreferences.folderGridColumnsLandscape.set(0)
            browserPreferences.videoGridColumnsPortrait.set(0)
            browserPreferences.videoGridColumnsLandscape.set(0)
            browserPreferences.folderGridColumnsDualPanePortrait.set(0)
            browserPreferences.folderGridColumnsDualPaneLandscape.set(0)
            browserPreferences.videoGridColumnsDualPanePortrait.set(0)
            browserPreferences.videoGridColumnsDualPaneLandscape.set(0)
          }
          browserPreferences.manualGridColumnsEnabled.set(enabled)
        },
      ),
  )
}

@Composable
fun NetworkSortDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
) {
  val browserPreferences = koinInject<BrowserPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val networkSortType by browserPreferences.networkSortType.collectAsState()
  val networkSortOrder by browserPreferences.networkSortOrder.collectAsState()
  val networkLayoutMode by browserPreferences.networkLayoutMode.collectAsState()
  val showVideoThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
  val showSizeChip by browserPreferences.showSizeChip.collectAsState()
  val showDateChip by browserPreferences.showDateChip.collectAsState()
  val showExtensionField by browserPreferences.showExtensionField.collectAsState()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val centerGridTitles by browserPreferences.centerGridTitles.collectAsState()
  val includeImages by browserPreferences.includeImagesInBrowser.collectAsState()
  val manualGridColumnsEnabled by browserPreferences.manualGridColumnsEnabled.collectAsState()
  val videoGridColumnsPortrait by browserPreferences.videoGridColumnsPortrait.collectAsState()
  val videoGridColumnsLandscape by browserPreferences.videoGridColumnsLandscape.collectAsState()

  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val isTablet = configuration.smallestScreenWidthDp >= 600

  val screenWidthDp = configuration.screenWidthDp.dp
  val contentHorizontalPadding = 8.dp
  val itemSpacing = 2.dp
  val usableWidth = screenWidthDp - (contentHorizontalPadding * 2) - itemSpacing
  val isTelevision =
    app.gyrolet.mpvrx.utils.device.DeviceFormFactor.isTelevision(androidx.compose.ui.platform.LocalContext.current)
  val videoMinWidth = if (isTelevision) 240.dp else 130.dp
  val dynamicVideoColumns = (usableWidth / videoMinWidth).toInt().coerceAtLeast(1)

  val maxVideoColumns = maxOf(if (isTablet || isLandscape) 8 else 4, dynamicVideoColumns + 3).coerceIn(4, 16)

  val videoPref = if (isLandscape) videoGridColumnsLandscape else videoGridColumnsPortrait
  val videoGridColumns = if (videoPref > 0) videoPref else dynamicVideoColumns

  val videoGridColumnSelector =
    if (networkLayoutMode == MediaLayoutMode.GRID && manualGridColumnsEnabled) {
      GridColumnSelector(
        label = "Grid Columns (${if (isLandscape) "Landscape" else "Portrait"})",
        currentValue = videoGridColumns.coerceIn(1, maxVideoColumns),
        onValueChange = {
          if (isLandscape) {
            browserPreferences.videoGridColumnsLandscape.set(it)
          } else {
            browserPreferences.videoGridColumnsPortrait.set(it)
          }
        },
        valueRange = 1f..maxVideoColumns.toFloat(),
        steps = maxVideoColumns - 2,
      )
    } else {
      null
    }

  SortDialog(
    isOpen = isOpen,
    onDismiss = onDismiss,
    title = stringResource(R.string.sort_view_options),
    sortType = networkSortType.displayName,
    onSortTypeChange = { typeName ->
      NetworkSortType.entries.find { it.displayName == typeName }?.let {
        browserPreferences.networkSortType.set(it)
      }
    },
    sortOrderAsc = networkSortOrder.isAscending,
    onSortOrderChange = { isAsc ->
      browserPreferences.networkSortOrder.set(
        if (isAsc) SortOrder.Ascending else SortOrder.Descending,
      )
    },
    types =
      listOf(
        NetworkSortType.Title.displayName,
        NetworkSortType.Date.displayName,
        NetworkSortType.Size.displayName,
      ),
    icons =
      listOf(
        Icons.RoundedFilled.Title,
        Icons.RoundedFilled.CalendarToday,
        Icons.RoundedFilled.SwapVert,
      ),
    getLabelForType = { type, _ ->
      when (type) {
        NetworkSortType.Title.displayName -> Pair("A-Z", "Z-A")
        NetworkSortType.Date.displayName -> Pair("Oldest", "Newest")
        NetworkSortType.Size.displayName -> Pair("Smallest", "Largest")
        else -> Pair("Asc", "Desc")
      }
    },
    showSortOptions = true,
    layoutModeSelector =
      ViewModeSelector(
        label = "Layout",
        firstOptionLabel = "List",
        secondOptionLabel = "Grid",
        firstOptionIcon = Icons.RoundedFilled.ViewList,
        secondOptionIcon = Icons.RoundedFilled.GridView,
        isFirstOptionSelected = networkLayoutMode == MediaLayoutMode.LIST,
        onViewModeChange = { isFirstOption ->
          browserPreferences.networkLayoutMode.set(
            if (isFirstOption) MediaLayoutMode.LIST else MediaLayoutMode.GRID,
          )
        },
      ),
    videoGridColumnSelector = videoGridColumnSelector,
    enableLayoutModeOptions = true,
    mediaTypeToggles =
      listOf(
        VisibilityToggle(
          label = "Videos",
          checked = true,
          onCheckedChange = { },
          enabled = false,
        ),
        VisibilityToggle(
          label = "Images",
          checked = includeImages,
          onCheckedChange = { browserPreferences.includeImagesInBrowser.set(it) },
        ),
      ),
    visibilityToggles =
      buildList {
        add(
          VisibilityToggle(
            label = "Thumbnails",
            checked = showVideoThumbnails,
            onCheckedChange = { browserPreferences.showVideoThumbnails.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Full Name",
            checked = unlimitedNameLines,
            onCheckedChange = { appearancePreferences.unlimitedNameLines.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Extension",
            checked = showExtensionField,
            onCheckedChange = { browserPreferences.showExtensionField.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Size",
            checked = showSizeChip,
            onCheckedChange = { browserPreferences.showSizeChip.set(it) },
          ),
        )
        add(
          VisibilityToggle(
            label = "Date",
            checked = showDateChip,
            onCheckedChange = { browserPreferences.showDateChip.set(it) },
          ),
        )
        if (networkLayoutMode == MediaLayoutMode.GRID) {
          add(
            VisibilityToggle(
              label = "Center Titles",
              checked = centerGridTitles,
              onCheckedChange = { browserPreferences.centerGridTitles.set(it) },
            ),
          )
        }
      },
    manualGridToggle =
      VisibilityToggle(
        label = "Manual Grid",
        checked = manualGridColumnsEnabled,
        onCheckedChange = { enabled ->
          if (enabled) {
            if (isLandscape) {
              browserPreferences.videoGridColumnsLandscape.set(dynamicVideoColumns)
            } else {
              browserPreferences.videoGridColumnsPortrait.set(dynamicVideoColumns)
            }
          } else {
            browserPreferences.folderGridColumnsPortrait.set(0)
            browserPreferences.folderGridColumnsLandscape.set(0)
            browserPreferences.videoGridColumnsPortrait.set(0)
            browserPreferences.videoGridColumnsLandscape.set(0)
            browserPreferences.folderGridColumnsDualPanePortrait.set(0)
            browserPreferences.folderGridColumnsDualPaneLandscape.set(0)
            browserPreferences.videoGridColumnsDualPanePortrait.set(0)
            browserPreferences.videoGridColumnsDualPaneLandscape.set(0)
          }
          browserPreferences.manualGridColumnsEnabled.set(enabled)
        },
      ),
  )
}

@Composable
fun MusicSortDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  sortField: MusicSortField,
  sortOrder: MusicSortOrder,
  viewMode: MusicViewMode,
  onSortFieldChange: (MusicSortField) -> Unit,
  onSortOrderChange: (MusicSortOrder) -> Unit,
  onViewModeChange: (MusicViewMode) -> Unit,
  // Restricts which sort fields are offered. Callers backed by a store that can't persist
  // every MusicSortField (e.g. VideoSortType, which has no Artist/Album) should pass only the
  // subset they can actually honor, otherwise selecting an unsupported field silently falls
  // back to Title with no visible feedback.
  availableFields: List<MusicSortField> =
    listOf(
      MusicSortField.TITLE,
      MusicSortField.ARTIST,
      MusicSortField.ALBUM,
      MusicSortField.DURATION,
      MusicSortField.DATE_ADDED,
    ),
) {
  val browserPreferences = koinInject<BrowserPreferences>()
  val musicCoverArtSize by browserPreferences.musicCoverArtSize.collectAsState()
  val musicGridCoverArtSize by browserPreferences.musicGridCoverArtSize.collectAsState()

  val fieldIcon = { field: MusicSortField ->
    when (field) {
      MusicSortField.TITLE -> Icons.RoundedFilled.Title
      MusicSortField.ARTIST -> Icons.RoundedFilled.Mic
      MusicSortField.ALBUM -> Icons.RoundedFilled.Audiotrack
      MusicSortField.DURATION -> Icons.RoundedFilled.AccessTime
      MusicSortField.DATE_ADDED -> Icons.RoundedFilled.CalendarToday
      MusicSortField.TRACK_COUNT -> Icons.RoundedFilled.QueueMusic
      MusicSortField.YEAR -> Icons.RoundedFilled.CalendarToday
    }
  }

  SortDialog(
    isOpen = isOpen,
    onDismiss = onDismiss,
    title = stringResource(R.string.sort_view_options),
    sortType = sortField.displayName,
    onSortTypeChange = { typeName ->
      availableFields.find { it.displayName == typeName }?.let(onSortFieldChange)
    },
    sortOrderAsc = sortOrder == MusicSortOrder.ASCENDING,
    onSortOrderChange = { isAsc ->
      onSortOrderChange(if (isAsc) MusicSortOrder.ASCENDING else MusicSortOrder.DESCENDING)
    },
    types = availableFields.map { it.displayName },
    icons = availableFields.map(fieldIcon),
    getLabelForType = { type, _ ->
      when (type) {
        MusicSortField.TITLE.displayName,
        MusicSortField.ARTIST.displayName,
        MusicSortField.ALBUM.displayName -> Pair("A-Z", "Z-A")
        MusicSortField.DURATION.displayName -> Pair("Shortest", "Longest")
        MusicSortField.DATE_ADDED.displayName -> Pair("Oldest", "Newest")
        else -> Pair("Asc", "Desc")
      }
    },
    layoutModeSelector =
      ViewModeSelector(
        label = "Layout",
        firstOptionLabel = "List",
        secondOptionLabel = "Grid",
        firstOptionIcon = Icons.RoundedFilled.ViewList,
        secondOptionIcon = Icons.RoundedFilled.GridView,
        isFirstOptionSelected = viewMode == MusicViewMode.LIST,
        onViewModeChange = { isList ->
          onViewModeChange(if (isList) MusicViewMode.LIST else MusicViewMode.GRID)
        },
      ),
    videoGridColumnSelector =
      if (viewMode == MusicViewMode.LIST) {
        GridColumnSelector(
          label = "Cover Art Size",
          currentValue = musicCoverArtSize,
          onValueChange = { browserPreferences.musicCoverArtSize.set(it) },
          valueRange = 56f..126f,
          steps = 40,
          unitSuffix = "dp",
        )
      } else {
        GridColumnSelector(
          label = "Cover Art Size",
          currentValue = musicGridCoverArtSize,
          onValueChange = { browserPreferences.musicGridCoverArtSize.set(it) },
          valueRange = 100f..260f,
          steps = 31,
          unitSuffix = "dp",
        )
      },
  )
}

@Composable
fun JellyfinSortDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  sortBy: app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy,
  onSortByChange: (app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy) -> Unit,
  sortOrder: app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortOrder,
  onSortOrderChange: (app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortOrder) -> Unit,
  isUnplayedOnly: Boolean,
  onUnplayedOnlyChange: (Boolean) -> Unit,
  layoutMode: MediaLayoutMode = MediaLayoutMode.GRID,
  onLayoutModeChange: (MediaLayoutMode) -> Unit = {},
) {
  SortDialog(
    isOpen = isOpen,
    onDismiss = onDismiss,
    title = stringResource(R.string.sort_view_options),
    sortType = sortBy.displayName,
    onSortTypeChange = { typeName ->
      app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.entries
        .find { it.displayName == typeName }
        ?.let(onSortByChange)
    },
    sortOrderAsc = sortOrder == app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortOrder.ASCENDING,
    onSortOrderChange = { isAsc ->
      onSortOrderChange(
        if (isAsc) {
          app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortOrder.ASCENDING
        } else {
          app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortOrder.DESCENDING
        },
      )
    },
    types =
      listOf(
        app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.NAME.displayName,
        app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.DATE_ADDED.displayName,
        app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.PREMIERE_DATE.displayName,
        app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.RATING.displayName,
        app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.RUNTIME.displayName,
      ),
    icons =
      listOf(
        Icons.RoundedFilled.Title,
        Icons.RoundedFilled.CalendarToday,
        Icons.RoundedFilled.Movie,
        Icons.RoundedFilled.SwapVert,
        Icons.RoundedFilled.AccessTime,
      ),
    getLabelForType = { type, _ ->
      when (type) {
        app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.NAME.displayName -> Pair("A-Z", "Z-A")
        app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.DATE_ADDED.displayName -> Pair("Oldest", "Newest")
        app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.PREMIERE_DATE.displayName -> Pair("Oldest", "Newest")
        app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.RATING.displayName -> Pair("Lowest", "Highest")
        app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.RUNTIME.displayName -> Pair("Shortest", "Longest")
        else -> Pair("Asc", "Desc")
      }
    },
    visibilityToggles =
      listOf(
        VisibilityToggle(
          label = "Unplayed Only",
          checked = isUnplayedOnly,
          onCheckedChange = onUnplayedOnlyChange,
        ),
      ),
    layoutModeSelector =
      ViewModeSelector(
        label = "Layout",
        firstOptionLabel = "List",
        secondOptionLabel = "Grid",
        firstOptionIcon = Icons.RoundedFilled.ViewList,
        secondOptionIcon = Icons.RoundedFilled.GridView,
        isFirstOptionSelected = layoutMode == MediaLayoutMode.LIST,
        onViewModeChange = { isList ->
          onLayoutModeChange(if (isList) MediaLayoutMode.LIST else MediaLayoutMode.GRID)
        },
      ),
    showSortOptions = true,
  )
}

@Composable
fun AudiobookSortDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  sortType: AudiobookSortType,
  sortOrder: SortOrder,
  layoutMode: MediaLayoutMode,
  onSortTypeChange: (AudiobookSortType) -> Unit,
  onSortOrderChange: (SortOrder) -> Unit,
  onLayoutModeChange: (MediaLayoutMode) -> Unit,
) {
  SortDialog(
    isOpen = isOpen,
    onDismiss = onDismiss,
    title = stringResource(R.string.sort_view_options),
    sortType = sortType.displayName,
    onSortTypeChange = { typeName ->
      AudiobookSortType.entries.find { it.displayName == typeName }?.let(onSortTypeChange)
    },
    sortOrderAsc = sortOrder == SortOrder.Ascending,
    onSortOrderChange = { isAsc ->
      onSortOrderChange(if (isAsc) SortOrder.Ascending else SortOrder.Descending)
    },
    types = AudiobookSortType.entries.map { it.displayName },
    icons =
      listOf(
        Icons.RoundedFilled.Title,
        Icons.RoundedFilled.Person,
        Icons.RoundedFilled.AccessTime,
        Icons.RoundedFilled.AvTimer,
        Icons.RoundedFilled.History,
        Icons.RoundedFilled.CalendarToday,
      ),
    getLabelForType = { type, _ ->
      when (type) {
        AudiobookSortType.Title.displayName,
        AudiobookSortType.Author.displayName -> Pair("A-Z", "Z-A")
        AudiobookSortType.Duration.displayName -> Pair("Shortest", "Longest")
        AudiobookSortType.Progress.displayName -> Pair("Least", "Most")
        AudiobookSortType.LastPlayed.displayName -> Pair("Oldest", "Newest")
        AudiobookSortType.DateAdded.displayName -> Pair("Oldest", "Newest")
        else -> Pair("Asc", "Desc")
      }
    },
    layoutModeSelector =
      ViewModeSelector(
        label = "Layout",
        firstOptionLabel = "List",
        secondOptionLabel = "Grid",
        firstOptionIcon = Icons.RoundedFilled.ViewList,
        secondOptionIcon = Icons.RoundedFilled.GridView,
        isFirstOptionSelected = layoutMode == MediaLayoutMode.LIST,
        onViewModeChange = { isList ->
          onLayoutModeChange(if (isList) MediaLayoutMode.LIST else MediaLayoutMode.GRID)
        },
      ),
    showSortOptions = true,
  )
}


