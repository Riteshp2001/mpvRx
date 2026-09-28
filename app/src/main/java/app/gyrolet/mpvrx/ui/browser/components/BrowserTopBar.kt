/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.FrozenSurfaceStyle
import app.gyrolet.mpvrx.presentation.components.frozenSurface
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.DarkMode
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import app.gyrolet.mpvrx.ui.theme.LocalThemeTransitionState
import app.gyrolet.mpvrx.ui.theme.LocalAppWallpaperActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private fun Modifier.browserTopBarFocus(enabled: Boolean = true): Modifier =
  tvFocusHighlight(CircleShape, enabled = enabled, focusedScale = 1.06f)

/**
 * Unified top bar for browser screens that switches between normal and selection modes
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserTopBar(
  title: String,
  isInSelectionMode: Boolean,
  selectedCount: Int,
  totalCount: Int,
  onCancelSelection: () -> Unit,
  modifier: Modifier = Modifier,
  onBackClick: (() -> Unit)? = null,
  onSortClick: (() -> Unit)? = null,
  onSearchClick: (() -> Unit)? = null,
  onRequestClick: (() -> Unit)? = null,
  onSettingsClick: (() -> Unit)? = null,
  onDeleteClick: (() -> Unit)? = null,
  onRenameClick: (() -> Unit)? = null,
  isSingleSelection: Boolean = false,
  onInfoClick: (() -> Unit)? = null,
  onShareClick: (() -> Unit)? = null,
  onPlayClick: (() -> Unit)? = null,
  onBlacklistClick: (() -> Unit)? = null,
  onSelectAll: (() -> Unit)? = null,
  onInvertSelection: (() -> Unit)? = null,
  onDeselectAll: (() -> Unit)? = null,
  preSearchActions: @Composable RowScope.() -> Unit = { },
  postSearchActions: @Composable RowScope.() -> Unit = { },
  additionalActions: @Composable RowScope.() -> Unit = { },
  titleTrailing: (@Composable RowScope.() -> Unit)? = null,
  onTitleLongPress: (() -> Unit)? = null,
  onTitleDoubleTap: (() -> Unit)? = null,
  onMoveToSecureClick: (() -> Unit)? = null,
  useRemoveIcon: Boolean = false,
  onRestoreClick: (() -> Unit)? = null,
  colors: TopAppBarColors? = null,
  forceHeadlineSmall: Boolean = false,
  showBetaBadge: Boolean = false,
) {
  val reducedMotion = AppMotion.shouldReduceMotion()
  val haptics = rememberAppHaptics()
  val wallpaperActive = LocalAppWallpaperActive.current
  val defaultContainerColor =
    if (wallpaperActive) {
      Color.Transparent
    } else if (MaterialTheme.colorScheme.background == Color.Black) {
      Color.Black
    } else {
      MaterialTheme.colorScheme.surfaceContainer
    }
  val resolvedColors = colors ?: TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
  AnimatedContent(
    targetState = isInSelectionMode,
    modifier =
      modifier
        .fillMaxWidth()
        .frozenSurface(
          shape = RectangleShape,
          style = FrozenSurfaceStyle.TopBar,
          fallbackColor = defaultContainerColor,
        ),
    transitionSpec = {
      val enter = fadeIn(tween(if (reducedMotion) 0 else 180))
      val exit = fadeOut(tween(if (reducedMotion) 0 else 100))
      if (reducedMotion) {
        (enter togetherWith exit).using(null)
      } else {
        ((enter + slideInVertically(tween(180)) { it / 10 }) togetherWith exit).using(null)
      }
    },
    label = "browserToolbarMode",
  ) { selectionMode ->
    val outgoing = selectionMode != isInSelectionMode
    val toolbarModifier =
      if (outgoing) {
        Modifier.clearAndSetSemantics { }
          .onPreviewKeyEvent { true }
          .pointerInput(Unit) {
            awaitPointerEventScope {
              while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
          }
      } else {
        Modifier
      }
    if (selectionMode) {
      SelectionTopBar(
        selectedCount = selectedCount,
        totalCount = totalCount,
        onCancel = {
          onCancelSelection()
          if (selectedCount > 0) haptics.selection(false)
        },
        onDelete = onDeleteClick,
        onRename = onRenameClick,
        isSingleSelection = isSingleSelection,
        onInfo = onInfoClick,
        onShare = onShareClick,
        onPlay = onPlayClick,
        onBlacklist = onBlacklistClick,
        onSelectAll = onSelectAll,
        onInvertSelection = onInvertSelection,
        onDeselectAll = onDeselectAll,
        onMoveToSecure = onMoveToSecureClick,
        onRestore = onRestoreClick,
        modifier = toolbarModifier,
        useRemoveIcon = useRemoveIcon,
        colors = resolvedColors,
        additionalActions = additionalActions,
      )
    } else {
      NormalTopBar(
        title = title,
        onBackClick = onBackClick,
        onSortClick = onSortClick,
        onSearchClick = onSearchClick,
        onRequestClick = onRequestClick,
        onSettingsClick = onSettingsClick,
        preSearchActions = preSearchActions,
        postSearchActions = postSearchActions,
        additionalActions = additionalActions,
        titleTrailing = titleTrailing,
        modifier = toolbarModifier,
        onTitleLongPress = onTitleLongPress,
        onTitleDoubleTap = onTitleDoubleTap,
        colors = resolvedColors,
        forceHeadlineSmall = forceHeadlineSmall,
        showBetaBadge = showBetaBadge,
      )
    }
  }
}

/**
 * Normal mode top bar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NormalTopBar(
  title: String,
  onBackClick: (() -> Unit)?,
  onSortClick: (() -> Unit)?,
  onSearchClick: (() -> Unit)?,
  onRequestClick: (() -> Unit)? = null,
  onSettingsClick: (() -> Unit)?,
  preSearchActions: @Composable RowScope.() -> Unit = { },
  postSearchActions: @Composable RowScope.() -> Unit = { },
  additionalActions: @Composable RowScope.() -> Unit,
  titleTrailing: (@Composable RowScope.() -> Unit)? = null,
  modifier: Modifier = Modifier,
  onTitleLongPress: (() -> Unit)?,
  onTitleDoubleTap: (() -> Unit)? = null,
  colors: TopAppBarColors? = null,
  forceHeadlineSmall: Boolean = false,
  showBetaBadge: Boolean = false,
) {
  val preferences = koinInject<AppearancePreferences>()
  val wallpaperActive = LocalAppWallpaperActive.current
  val darkMode by preferences.darkMode.collectAsState()
  val darkTheme = isSystemInDarkTheme()
  val themeTransition = LocalThemeTransitionState.current
  val coroutineScope = rememberCoroutineScope()

  // Track title bounds for animation position
  val titleBounds = remember { mutableStateOf(Rect.Zero) }

  // Helper function to toggle dark mode
  fun toggleDarkMode() {
    when (darkMode) {
      DarkMode.System ->
        if (darkTheme) {
          preferences.darkMode.set(DarkMode.Light)
        } else {
          preferences.darkMode.set(DarkMode.Dark)
        }
      DarkMode.Light ->
        if (darkTheme) {
          preferences.darkMode.set(DarkMode.System)
        } else {
          preferences.darkMode.set(DarkMode.Dark)
        }
      DarkMode.Dark ->
        if (darkTheme) {
          preferences.darkMode.set(DarkMode.Light)
        } else {
          preferences.darkMode.set(DarkMode.System)
        }
    }
  }

  TopAppBar(
    colors =
      colors ?: TopAppBarDefaults.topAppBarColors(
        containerColor =
          if (wallpaperActive) {
            Color.Transparent
          } else if (MaterialTheme.colorScheme.background == Color.Black) {
            Color.Black
          } else {
            MaterialTheme.colorScheme.surfaceContainer
          },
      ),
    title = {
      val betaBadgeSuffix =
        if (showBetaBadge) {
          stringResource(R.string.ui_beta_badge_suffix)
        } else {
          ""
        }
      val titleModifier =
        Modifier
          .onGloballyPositioned { coordinates ->
            titleBounds.value = coordinates.boundsInWindow()
          }.pointerInput(onTitleLongPress, onTitleDoubleTap) {
            detectTapGestures(
              onTap = { localOffset ->
                // Don't allow theme change if animation is in progress
                if (themeTransition?.isAnimating == true) return@detectTapGestures

                // Calculate window position for circular reveal
                val windowOffset =
                  Offset(
                    titleBounds.value.left + localOffset.x,
                    titleBounds.value.top + localOffset.y,
                  )
                themeTransition?.startTransition(windowOffset)
                // Delay theme change to allow overlay to display first
                coroutineScope.launch {
                  delay(50)
                  toggleDarkMode()
                }
              },
              onDoubleTap =
                if (onTitleDoubleTap != null) {
                  { onTitleDoubleTap() }
                } else {
                  null
                },
              onLongPress =
                if (onTitleLongPress != null) {
                  { onTitleLongPress() }
                } else {
                  null
                },
            )
          }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
          if (onBackClick == null) {
            Modifier.padding(start = 8.dp)
          } else {
            Modifier
          },
      ) {
        Text(
          buildAnnotatedString {
            append(title)
            if (showBetaBadge) {
              withStyle(
                SpanStyle(
                  fontSize = MaterialTheme.typography.labelSmall.fontSize,
                  fontWeight = FontWeight.SemiBold,
                  baselineShift = BaselineShift.Superscript,
                ),
              ) {
                append(betaBadgeSuffix)
              }
            }
          },
          style =
            if (forceHeadlineSmall || onBackClick != null) {
              MaterialTheme.typography.headlineSmall
            } else {
              MaterialTheme.typography.headlineMedium
            },
          fontWeight = FontWeight.ExtraBold,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = titleModifier,
        )
        if (titleTrailing != null) {
          titleTrailing()
        }
      }
    },
    navigationIcon = {
      if (onBackClick != null) {
        IconButton(
          onClick = onBackClick,
          modifier = Modifier.padding(horizontal = 2.dp).browserTopBarFocus(),
        ) {
          Icon(
            Icons.RoundedFilled.ArrowBack,
            contentDescription = stringResource(R.string.back),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }
    },
    actions = {
      preSearchActions()
      if (onRequestClick != null) {
        IconButton(
          onClick = onRequestClick,
          modifier = Modifier.padding(horizontal = 2.dp).browserTopBarFocus(),
        ) {
          Icon(
            Icons.RoundedFilled.Seerr,
            contentDescription =
              androidx.compose.ui.res.stringResource(
                app.gyrolet.mpvrx.R.string.seerr_discover,
              ),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }
      if (onSearchClick != null) {
        IconButton(
          onClick = onSearchClick,
          modifier = Modifier.padding(horizontal = 2.dp).browserTopBarFocus(),
        ) {
          Icon(
            Icons.RoundedFilled.Search,
            contentDescription =
              androidx.compose.ui.res.stringResource(
                app.gyrolet.mpvrx.R.string.settings_search_title,
              ),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }
      postSearchActions()
      if (onSortClick != null) {
        IconButton(
          onClick = onSortClick,
          modifier = Modifier.padding(horizontal = 2.dp).browserTopBarFocus(),
        ) {
          Icon(
            Icons.RoundedFilled.SortByAlpha,
            contentDescription = stringResource(R.string.sort),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }
      additionalActions()
      if (onSettingsClick != null) {
        IconButton(
          onClick = onSettingsClick,
          modifier = Modifier.padding(horizontal = 2.dp).browserTopBarFocus(),
        ) {
          Icon(
            Icons.RoundedFilled.Settings,
            contentDescription =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_settings),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }
    },
    modifier = modifier,
  )
}

/**
 * Selection mode top bar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
  selectedCount: Int,
  totalCount: Int,
  onCancel: () -> Unit,
  onDelete: (() -> Unit)?,
  onRename: (() -> Unit)?,
  isSingleSelection: Boolean,
  onInfo: (() -> Unit)?,
  onShare: (() -> Unit)?,
  onPlay: (() -> Unit)?,
  onBlacklist: (() -> Unit)?,
  onSelectAll: (() -> Unit)?,
  onInvertSelection: (() -> Unit)?,
  onDeselectAll: (() -> Unit)?,
  modifier: Modifier = Modifier,
  useRemoveIcon: Boolean = false,
  onMoveToSecure: (() -> Unit)? = null,
  onRestore: (() -> Unit)? = null,
  colors: TopAppBarColors? = null,
  additionalActions: @Composable RowScope.() -> Unit = { },
) {
  var showDropdown by remember { mutableStateOf(false) }
  val wallpaperActive = LocalAppWallpaperActive.current
  val haptics = rememberAppHaptics()
  val reducedMotion = AppMotion.shouldReduceMotion()

  TopAppBar(
    colors =
      colors ?: TopAppBarDefaults.topAppBarColors(
        containerColor =
          if (wallpaperActive) {
            Color.Transparent
          } else if (MaterialTheme.colorScheme.background == Color.Black) {
            Color.Black
          } else {
            MaterialTheme.colorScheme.surfaceContainer
          },
      ),
    title = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
          Modifier
            .clip(RoundedCornerShape(8.dp))
            .tvFocusHighlight(RoundedCornerShape(8.dp), focusedScale = 1.02f)
            .clickable { showDropdown = true },
      ) {
        Box(Modifier.weight(1f, fill = false)) {
          Text(
            stringResource(R.string.selected_items, totalCount, totalCount),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(0f).clearAndSetSemantics { },
          )
          AnimatedContent(
            targetState = selectedCount,
            transitionSpec = {
              if (reducedMotion) {
                (fadeIn(tween(0)) togetherWith fadeOut(tween(0))).using(null)
              } else {
                val direction = if (targetState > initialState) 1 else -1
                ((fadeIn(tween(160)) + slideInVertically(tween(160)) { it * direction / 3 }) togetherWith
                  (fadeOut(tween(100)) + slideOutVertically(tween(100)) { -it * direction / 3 })).using(null)
              }
            },
            label = "selectionCount",
          ) { count ->
            Text(
              stringResource(R.string.selected_items, count, totalCount),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        Icon(
          Icons.RoundedFilled.ArrowDropDown,
          contentDescription = stringResource(R.string.selection_options),
          modifier = Modifier.size(24.dp),
          tint = MaterialTheme.colorScheme.primary,
        )

        DropdownMenu(
          expanded = showDropdown,
          onDismissRequest = { showDropdown = false },
        ) {
          if (onSelectAll != null) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.select_all)) },
              onClick = {
                onSelectAll()
                if (selectedCount != totalCount) haptics.selection(true)
                showDropdown = false
              },
            )
          }
          if (onInvertSelection != null) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.invert_selection)) },
              onClick = {
                onInvertSelection()
                if (totalCount > 0) haptics.confirm()
                showDropdown = false
              },
            )
          }
          if (onDeselectAll != null) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.deselect_all)) },
              onClick = {
                onDeselectAll()
                if (selectedCount > 0) haptics.selection(false)
                showDropdown = false
              },
            )
          }
        }
      }
    },
    navigationIcon = {
      IconButton(
        onClick = onCancel,
        modifier = Modifier.padding(horizontal = 2.dp).browserTopBarFocus(),
      ) {
        Icon(
          Icons.RoundedFilled.Close,
          contentDescription = stringResource(R.string.generic_cancel),
          modifier = Modifier.size(28.dp),
          tint = MaterialTheme.colorScheme.secondary,
        )
      }
    },
    actions = {
      additionalActions()
      if (onRestore != null) {
        IconButton(
          onClick = onRestore,
          modifier = Modifier.padding(horizontal = 1.dp).browserTopBarFocus(),
        ) {
          Icon(
            Icons.RoundedFilled.Restore,
            contentDescription = stringResource(R.string.secure_folder_restore),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }
      // Play icon
      if (onPlay != null) {
        IconButton(
          onClick = onPlay,
          modifier = Modifier.padding(horizontal = 1.dp).browserTopBarFocus(),
        ) {
          Icon(
            Icons.RoundedFilled.PlayArrow,
            contentDescription =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_play),
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
          )
        }
      }

      // Rename icon
      if (onRename != null) {
        IconButton(
          onClick = onRename,
          enabled = isSingleSelection,
          modifier = Modifier.padding(horizontal = 1.dp).browserTopBarFocus(enabled = isSingleSelection),
        ) {
          Icon(
            Icons.RoundedFilled.DriveFileRenameOutline,
            contentDescription = stringResource(R.string.rename),
            modifier = Modifier.size(24.dp),
            tint =
              if (isSingleSelection) {
                MaterialTheme.colorScheme.secondary
              } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
              },
          )
        }
      }

      // Info icon
      if (onInfo != null) {
        IconButton(
          onClick = onInfo,
          enabled = isSingleSelection,
          modifier = Modifier.padding(horizontal = 1.dp).browserTopBarFocus(enabled = isSingleSelection),
        ) {
          Icon(
            Icons.RoundedFilled.Info,
            contentDescription = stringResource(R.string.info),
            modifier = Modifier.size(24.dp),
            tint =
              if (isSingleSelection) {
                MaterialTheme.colorScheme.secondary
              } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
              },
          )
        }
      }

      // Share icon
      if (onShare != null) {
        IconButton(
          onClick = onShare,
          modifier = Modifier.padding(horizontal = 1.dp).browserTopBarFocus(),
        ) {
          Icon(
            Icons.RoundedFilled.Share,
            contentDescription = stringResource(R.string.generic_share),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }


      // Move to Secure Folder icon
      if (onMoveToSecure != null) {
        IconButton(
          onClick = onMoveToSecure,
          modifier = Modifier.padding(horizontal = 1.dp).browserTopBarFocus(),
        ) {
          Icon(
            Icons.RoundedFilled.Lock,
            contentDescription = stringResource(R.string.secure_folder_move_to),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }

      // Blacklist icon
      if (onBlacklist != null) {
        IconButton(
          onClick = onBlacklist,
          modifier = Modifier.padding(horizontal = 1.dp).browserTopBarFocus(),
        ) {
          Icon(
            Icons.RoundedFilled.Block,
            contentDescription = stringResource(R.string.pref_folders_blacklist),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.secondary,
          )
        }
      }

      // Delete/Remove icon
      if (onDelete != null) {
        IconButton(
          onClick = onDelete,
          modifier = Modifier.padding(horizontal = 2.dp).browserTopBarFocus(),
        ) {
          Icon(
            imageVector = if (useRemoveIcon) Icons.RoundedFilled.RemoveCircle else Icons.RoundedFilled.Delete,
            contentDescription = stringResource(R.string.delete),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.error,
          )
        }
      }
    },
    modifier =
      modifier.clip(
        RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 28.dp, bottomEnd = 28.dp),
      ),
  )
}
