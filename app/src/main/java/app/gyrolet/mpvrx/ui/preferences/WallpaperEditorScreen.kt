/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.theme.CustomThemeDefinition
import app.gyrolet.mpvrx.ui.theme.WallpaperDerivedThemeName
import app.gyrolet.mpvrx.ui.theme.WallpaperImage
import app.gyrolet.mpvrx.ui.theme.WallpaperPreset
import app.gyrolet.mpvrx.ui.theme.drawWallpaperPreset
import app.gyrolet.mpvrx.ui.preferences.components.WallpaperPresetCard
import app.gyrolet.mpvrx.ui.theme.WallpaperScaleMode
import app.gyrolet.mpvrx.ui.theme.extractThemeFromWallpaper
import app.gyrolet.mpvrx.ui.theme.loadWallpaperBitmap
import app.gyrolet.mpvrx.ui.theme.saveWallpaperCopy
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusGroup
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
data class WallpaperEditorScreen(
  val sourceUri: String = "",
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val preferences = koinInject<AppearancePreferences>()
    val resolvedSource = remember(sourceUri) { sourceUri.ifBlank { preferences.customWallpaperUri.get() } }
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    val screenConfig = LocalConfiguration.current
    val deviceRatio =
      minOf(screenConfig.screenWidthDp, screenConfig.screenHeightDp).toFloat() /
        maxOf(screenConfig.screenWidthDp, screenConfig.screenHeightDp).toFloat()
    BackHandler(enabled = isSaving) { }
    val isEditingCurrent = sourceUri.isBlank() || sourceUri == preferences.customWallpaperUri.get()
    var zoom by rememberSaveable(sourceUri) {
      mutableStateOf(if (isEditingCurrent) preferences.customWallpaperZoom.get() else 1f)
    }
    var offsetX by rememberSaveable(sourceUri) {
      mutableStateOf(if (isEditingCurrent) preferences.customWallpaperOffsetX.get() else 0f)
    }
    var offsetY by rememberSaveable(sourceUri) {
      mutableStateOf(if (isEditingCurrent) preferences.customWallpaperOffsetY.get() else 0f)
    }
    var scaleMode by rememberSaveable(sourceUri) {
      mutableStateOf(if (isEditingCurrent) preferences.customWallpaperScaleMode.get() else WallpaperScaleMode.Fit)
    }
    var blur by rememberSaveable(sourceUri) {
      mutableStateOf(if (isEditingCurrent) preferences.customWallpaperBlur.get() else 0f)
    }
    var alpha by rememberSaveable(sourceUri) {
      mutableStateOf(if (isEditingCurrent) preferences.customWallpaperAlpha.get() else 1f)
    }
    var useColors by rememberSaveable(sourceUri) {
      mutableStateOf(
        isEditingCurrent &&
          preferences.customWallpaperUseColors.get() &&
          preferences.selectedCustomThemeName.get() == WallpaperDerivedThemeName,
      )
    }
    var previewLocked by rememberSaveable(sourceUri) { mutableStateOf(false) }
    var previewAspect by rememberSaveable(sourceUri) { mutableStateOf<Float?>(null) }
    var showHomePreview by rememberSaveable(sourceUri) { mutableStateOf(false) }
    // "" = no wallpaper, "preset:<id>" = code-drawn preset, anything else = user picked image.
    val selectedSourceSaver =
      remember {
        Saver<String, String>(
          save = { source -> source.takeUnless { it.startsWith("data:", ignoreCase = true) } },
          restore = { it },
        )
      }
    var selectedSource by rememberSaveable(sourceUri, stateSaver = selectedSourceSaver) { mutableStateOf(resolvedSource) }
    val loadedWallpaper =
      produceState<Pair<String, Bitmap?>?>(initialValue = null, selectedSource) {
        val source = selectedSource
        val loaded = if (source.isBlank()) null else withContext(Dispatchers.IO) { loadWallpaperBitmap(context, source) }
        value = source to loaded
      }.value
    // Ignore a bitmap that belongs to the previously selected source while the new one is loading.
    val bitmap = loadedWallpaper?.takeIf { it.first == selectedSource }?.second
    val isNoneSelected = selectedSource.isBlank()
    val isCustomSelected = selectedSource.isNotBlank() && !WallpaperPreset.isPresetUri(selectedSource)
    val customPicker =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
          runCatching {
            context.contentResolver.takePersistableUriPermission(
              uri,
              android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
          }
          selectedSource = uri.toString()
          zoom = 1f
          offsetX = 0f
          offsetY = 0f
          scaleMode = WallpaperScaleMode.Fit
        }
      }
    DisposableEffect(bitmap) {
      val displayedBitmap = bitmap
      onDispose {
        if (displayedBitmap != null) {
          Handler(Looper.getMainLooper()).postDelayed(
            { if (!displayedBitmap.isRecycled) displayedBitmap.recycle() },
            WALLPAPER_EDITOR_RECYCLE_DELAY_MS,
          )
        }
      }
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text(stringResource(R.string.pref_appearance_custom_wallpaper_crop_title)) },
          navigationIcon = {
            IconButton(enabled = !isSaving, onClick = { backStack.popSafely() }) {
              Icon(Icons.RoundedFilled.ArrowBack, contentDescription = null)
            }
          },
          actions = {
            TextButton(
              enabled = (bitmap != null || isNoneSelected) && !isSaving,
              onClick = {
                isSaving = true
                scope.launch {
                  try {
                    if (isNoneSelected) {
                      // Same as "Clear" on the appearance screen.
                      preferences.customWallpaperUri.set("")
                      preferences.customWallpaperZoom.set(1f)
                      preferences.customWallpaperOffsetX.set(0f)
                      preferences.customWallpaperOffsetY.set(0f)
                      preferences.customWallpaperScaleMode.set(WallpaperScaleMode.Fit)
                      preferences.customWallpaperBlur.set(0f)
                      preferences.customWallpaperAlpha.set(1f)
                      preferences.customWallpaperUseColors.set(false)
                      val remainingThemes =
                        CustomThemeDefinition
                          .parseCollection(preferences.customTheme.get())
                          .filterNot { it.name == WallpaperDerivedThemeName }
                      preferences.customTheme.set(CustomThemeDefinition.serializeCollection(remainingThemes))
                      if (preferences.selectedCustomThemeName.get() == WallpaperDerivedThemeName) {
                        preferences.selectedCustomThemeName.set("")
                      }
                      backStack.popSafely()
                      return@launch
                    }
                    val savedUri = saveWallpaperCopy(context, selectedSource)
                    preferences.customWallpaperZoom.set(zoom)
                    preferences.customWallpaperOffsetX.set(offsetX)
                    preferences.customWallpaperOffsetY.set(offsetY)
                    preferences.customWallpaperScaleMode.set(scaleMode)
                    preferences.customWallpaperBlur.set(blur)
                    preferences.customWallpaperAlpha.set(alpha)
                    preferences.customWallpaperUri.set(savedUri)

                    if (useColors) {
                      val extracted =
                        bitmap?.let { loaded -> withContext(Dispatchers.Default) { extractThemeFromWallpaper(loaded) } }
                      if (extracted != null) {
                        val otherThemes =
                          CustomThemeDefinition
                            .parseCollection(preferences.customTheme.get())
                            .filterNot { it.name == WallpaperDerivedThemeName }
                        preferences.customTheme.set(
                          CustomThemeDefinition.serializeCollection(otherThemes + extracted),
                        )
                        preferences.selectedCustomThemeName.set(extracted.name)
                        preferences.customWallpaperUseColors.set(true)
                      } else {
                        preferences.customWallpaperUseColors.set(false)
                        android.widget.Toast
                          .makeText(
                            context,
                            R.string.pref_appearance_custom_wallpaper_colors_failed,
                            android.widget.Toast.LENGTH_LONG,
                          )
                          .show()
                      }
                    } else {
                      preferences.customWallpaperUseColors.set(false)
                      if (preferences.selectedCustomThemeName.get() == WallpaperDerivedThemeName) {
                        preferences.selectedCustomThemeName.set("")
                      }
                    }

                    backStack.popSafely()
                  } catch (error: CancellationException) {
                    throw error
                  } catch (_: Exception) {
                    android.widget.Toast
                      .makeText(context, R.string.wallpaper_save_failed, android.widget.Toast.LENGTH_LONG)
                      .show()
                  } finally {
                    isSaving = false
                  }
                }
              },
            ) {
              Text(stringResource(R.string.pref_appearance_custom_wallpaper_save))
            }
          },
        )
      },
    ) { padding ->
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).tvFocusGroup(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          WallpaperScaleModeToggle(
            selected = scaleMode,
            onSelect = { mode ->
              scaleMode = mode
              zoom = 1f
              offsetX = 0f
              offsetY = 0f
            },
            modifier = Modifier.padding(vertical = 4.dp),
          )
        }
        item {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            IconButton(
              onClick = { previewLocked = !previewLocked },
              modifier =
                Modifier
                  .background(
                    if (previewLocked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    CircleShape,
                  ).tvFocusHighlight(CircleShape, focusedScale = 1.05f),
            ) {
              Icon(
                if (previewLocked) Icons.RoundedFilled.Lock else Icons.RoundedFilled.LockOpen,
                contentDescription = stringResource(
                  if (previewLocked) {
                    R.string.pref_appearance_custom_wallpaper_preview_locked
                  } else {
                    R.string.pref_appearance_custom_wallpaper_preview_unlocked
                  },
                ),
                tint =
                  if (previewLocked) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                  },
              )
            }
            LazyRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.tvFocusGroup(),
            ) {
              items(ASPECT_RATIO_PRESETS, key = { it.label }) { preset ->
                FilterChip(
                  selected = previewAspect == preset.ratio,
                  onClick = { previewAspect = preset.ratio },
                  label = {
                    Text(
                      if (preset.ratio == null) {
                        stringResource(R.string.wallpaper_aspect_device)
                      } else {
                        preset.label
                      },
                    )
                  },
                  modifier = Modifier.tvFocusHighlight(RoundedCornerShape(8.dp)),
                )
              }
            }
          }
        }
        item {
          BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // Preview is shaped like the real screen (or the chosen ratio), so it shows the wallpaper
            // exactly as the app will draw it. Fit/Fill/zoom/position all match 1:1.
            val ratio = previewAspect ?: deviceRatio
            val maxPreviewHeight = 340.dp
            val fitsByHeight = maxPreviewHeight * ratio <= maxWidth
            val frameHeight = if (fitsByHeight) maxPreviewHeight else maxWidth / ratio
            val frameWidth = if (fitsByHeight) maxPreviewHeight * ratio else maxWidth
            val frameShape = RoundedCornerShape(22.dp)
            Box(
              modifier =
                Modifier
                  .align(Alignment.Center)
                  .width(frameWidth)
                  .height(frameHeight)
                  .shadow(6.dp, frameShape)
                  .clip(frameShape)
                  .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                  .border(3.dp, MaterialTheme.colorScheme.outline, frameShape)
                  .pointerInput(sourceUri, previewLocked) {
                    if (previewLocked) return@pointerInput
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                      zoom = (zoom * gestureZoom).coerceIn(1f, 3f)
                      if (size.width > 0 && size.height > 0) {
                        offsetX = (offsetX + pan.x / (size.width * 0.35f)).coerceIn(-1f, 1f)
                        offsetY = (offsetY + pan.y / (size.height * 0.35f)).coerceIn(-1f, 1f)
                      }
                    }
                  },
            ) {
              bitmap?.let { loaded ->
                WallpaperImage(
                  bitmap = loaded,
                  zoom = zoom,
                  offsetX = offsetX,
                  offsetY = offsetY,
                  scaleMode = scaleMode,
                  blurRadius = blur,
                  imageAlpha = alpha,
                  modifier = Modifier.fillMaxSize(),
                )
              }
              if (isNoneSelected) {
                Text(
                  text = stringResource(R.string.wallpaper_none_hint),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.align(Alignment.Center),
                )
              }
            }
            FloatingActionButton(
              onClick = { showHomePreview = true },
              modifier =
                Modifier
                  .align(Alignment.BottomEnd)
                  .tvFocusHighlight(RoundedCornerShape(16.dp), focusedScale = 1.05f),
            ) {
              Icon(
                Icons.RoundedFilled.Visibility,
                contentDescription = stringResource(R.string.pref_appearance_custom_wallpaper_home_preview),
              )
            }
          }
        }
        item {
          Text(
            text = stringResource(R.string.pref_appearance_custom_wallpaper_gesture_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        item {
          Column {
            Text(
              text = stringResource(R.string.wallpaper_presets_label),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            LazyRow(
              modifier = Modifier.fillMaxWidth().tvFocusGroup(),
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              item(key = "wallpaper_none") {
                WallpaperPresetCard(
                  label = stringResource(R.string.wallpaper_preset_none),
                  isSelected = isNoneSelected,
                  onClick = {
                    selectedSource = ""
                    zoom = 1f
                    offsetX = 0f
                    offsetY = 0f
                  },
                ) {
                  Icon(
                    Icons.RoundedFilled.Block,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center).size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              items(WallpaperPreset.entries, key = { it.id }) { wallpaperPreset ->
                WallpaperPresetCard(
                  label = stringResource(wallpaperPreset.labelRes),
                  isSelected = selectedSource == wallpaperPreset.uri,
                  onClick = {
                    selectedSource = wallpaperPreset.uri
                    zoom = 1f
                    offsetX = 0f
                    offsetY = 0f
                    scaleMode = WallpaperScaleMode.Fit
                  },
                ) {
                  Canvas(modifier = Modifier.fillMaxSize()) { drawWallpaperPreset(wallpaperPreset) }
                }
              }
              item(key = "wallpaper_custom") {
                WallpaperPresetCard(
                  label = stringResource(R.string.wallpaper_preset_custom),
                  isSelected = isCustomSelected,
                  onClick = { customPicker.launch(arrayOf("image/*")) },
                ) {
                  if (isCustomSelected && bitmap != null) {
                    Image(
                      bitmap = bitmap.asImageBitmap(),
                      contentDescription = null,
                      contentScale = ContentScale.Crop,
                      modifier = Modifier.fillMaxSize(),
                    )
                    // Tapping the selected custom image picks a new one, so say so.
                    Row(
                      modifier =
                        Modifier
                          .align(Alignment.BottomCenter)
                          .fillMaxWidth()
                          .background(Color.Black.copy(alpha = 0.55f))
                          .padding(vertical = 5.dp),
                      horizontalArrangement = Arrangement.Center,
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Icon(
                        Icons.RoundedFilled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color.White,
                      )
                      Spacer(modifier = Modifier.width(4.dp))
                      Text(
                        text = stringResource(R.string.pref_appearance_custom_wallpaper_replace_action),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                      )
                    }
                  } else {
                    Icon(
                      Icons.RoundedFilled.Add,
                      contentDescription = null,
                      modifier = Modifier.align(Alignment.Center).size(34.dp),
                      tint = MaterialTheme.colorScheme.primary,
                    )
                  }
                }
              }
            }
          }
        }
        if (!isNoneSelected) {
          item {
            Text(
              text = stringResource(R.string.pref_appearance_custom_wallpaper_crop_title),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
          }
          item {
            PreferenceCard {
              SwitchPreference(
                value = useColors,
                onValueChange = { useColors = it },
                title = { Text(stringResource(R.string.pref_appearance_custom_wallpaper_use_colors)) },
                summary = { Text(stringResource(R.string.pref_appearance_custom_wallpaper_use_colors_summary)) },
                icon = {
                  Icon(
                    Icons.RoundedFilled.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                },
              )
              PreferenceDivider()
              WallpaperSlider(
                icon = Icons.RoundedFilled.ZoomIn,
                label = stringResource(R.string.pref_appearance_custom_wallpaper_zoom),
                value = zoom,
                valueRange = 1f..3f,
                onValueChange = { zoom = it },
                valueText = { "${(it * 100).roundToInt()}%" },
              )
              WallpaperSlider(
                icon = Icons.RoundedFilled.Tune,
                label = stringResource(R.string.pref_appearance_custom_wallpaper_horizontal),
                value = offsetX,
                valueRange = -1f..1f,
                onValueChange = { offsetX = it },
                valueText = { "${(it * 100).roundToInt()}%" },
              )
              WallpaperSlider(
                icon = Icons.RoundedFilled.SwapVert,
                label = stringResource(R.string.pref_appearance_custom_wallpaper_vertical),
                value = offsetY,
                valueRange = -1f..1f,
                onValueChange = { offsetY = it },
                valueText = { "${(it * 100).roundToInt()}%" },
              )
              WallpaperSlider(
                icon = Icons.RoundedFilled.BlurOn,
                label = stringResource(R.string.pref_appearance_custom_wallpaper_blur),
                value = blur,
                valueRange = 0f..40f,
                onValueChange = { blur = it },
                valueText = { "${it.roundToInt()}" },
              )
              WallpaperSlider(
                icon = Icons.RoundedFilled.Opacity,
                label = stringResource(R.string.pref_appearance_custom_wallpaper_transparency),
                value = alpha,
                valueRange = 0f..1f,
                onValueChange = { alpha = it },
                valueText = { "${(it * 100).roundToInt()}%" },
                isLast = true,
              )
            }
          }
        }
        item {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
          ) {
            OutlinedButton(
              onClick = {
                zoom = 1f
                offsetX = 0f
                offsetY = 0f
                scaleMode = WallpaperScaleMode.Fit
                blur = 0f
                alpha = 1f
                useColors = false
              },
              modifier = Modifier.tvFocusHighlight(RoundedCornerShape(12.dp), focusedScale = 1.03f),
            ) {
              Icon(Icons.RoundedFilled.Restore, contentDescription = null)
              Text(
                text = stringResource(R.string.pref_appearance_custom_wallpaper_reset),
                modifier = Modifier.padding(start = 8.dp),
              )
            }
          }
        }
      }
    }

    if (showHomePreview) {
      WallpaperHomePreviewDialog(
        bitmap = bitmap,
        zoom = zoom,
        offsetX = offsetX,
        offsetY = offsetY,
        scaleMode = scaleMode,
        blur = blur,
        alpha = alpha,
        onDismiss = { showHomePreview = false },
      )
    }
  }
}

@Composable
private fun WallpaperSlider(
  icon: AppIcon,
  label: String,
  value: Float,
  valueRange: ClosedFloatingPointRange<Float>,
  onValueChange: (Float) -> Unit,
  valueText: (Float) -> String,
  isLast: Boolean = false,
) {
  Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        icon,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 12.dp).weight(1f),
      )
      Text(
        text = valueText(value),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    Slider(
      value = value,
      onValueChange = onValueChange,
      valueRange = valueRange,
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(start = 32.dp)
          .tvFocusHighlight(RoundedCornerShape(12.dp), focusedScale = 1.01f),
    )
  }
  if (!isLast) PreferenceDivider()
}

/**
 * Fit / Fill switch. The selected side is a solid primary pill with a check mark, the other one an
 * outlined pill, so the current mode is obvious at a glance.
 */
@Composable
private fun WallpaperScaleModeToggle(
  selected: WallpaperScaleMode,
  onSelect: (WallpaperScaleMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val options =
    listOf(
      WallpaperScaleMode.Fit to stringResource(R.string.pref_appearance_custom_wallpaper_fit),
      WallpaperScaleMode.Fill to stringResource(R.string.pref_appearance_custom_wallpaper_fill),
    )
  Row(
    modifier = modifier.fillMaxWidth().tvFocusGroup(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    options.forEach { (mode, label) ->
      val isSelected = selected == mode
      val shape = RoundedCornerShape(50)
      val container by animateColorAsState(
        targetValue = if (isSelected) colors.primary else colors.surfaceContainerHighest,
        label = "wallpaperScaleContainer",
      )
      val content by animateColorAsState(
        targetValue = if (isSelected) colors.onPrimary else colors.onSurfaceVariant,
        label = "wallpaperScaleContent",
      )
      Row(
        modifier =
          Modifier
            .weight(1f)
            .height(48.dp)
            .tvFocusHighlight(shape, focusedScale = 1.03f)
            .clip(shape)
            .background(container)
            .then(if (isSelected) Modifier else Modifier.border(1.dp, colors.outlineVariant, shape))
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(mode) }),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (isSelected) {
          Icon(
            Icons.RoundedFilled.Check,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = content,
          )
          Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
          text = label,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
          color = content,
        )
      }
    }
  }
}

private data class AspectPreset(
  val label: String,
  val ratio: Float?,
)

private val ASPECT_RATIO_PRESETS =
  listOf(
    AspectPreset("Free", null),
    AspectPreset("9:16", 9f / 16f),
    AspectPreset("9:19.5", 9f / 19.5f),
    AspectPreset("3:4", 3f / 4f),
    AspectPreset("1:1", 1f),
    AspectPreset("16:9", 16f / 9f),
  )

private const val WALLPAPER_EDITOR_RECYCLE_DELAY_MS = 120L
