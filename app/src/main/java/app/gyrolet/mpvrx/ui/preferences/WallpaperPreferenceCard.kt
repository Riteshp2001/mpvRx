/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.WallpaperPreset
import app.gyrolet.mpvrx.ui.theme.drawWallpaperPreset
import app.gyrolet.mpvrx.ui.theme.loadWallpaperBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Wallpaper" section of the Appearance screen: shows what is currently applied and opens the
 * wallpaper editor, where the wallpaper is picked, replaced, adjusted or removed (None).
 */
@Composable
internal fun WallpaperPreferenceCard(
  wallpaperUri: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val preset = remember(wallpaperUri) { WallpaperPreset.fromUri(wallpaperUri) }
  val isNone = wallpaperUri.isBlank()
  val isCustom = !isNone && preset == null
  // Seeded from the cache so a returning screen paints the thumbnail on its first frame.
  val customThumbnail =
    produceState<ImageBitmap?>(
      initialValue = if (isCustom) WallpaperThumbnailCache.peek(wallpaperUri) else null,
      wallpaperUri,
    ) {
      value =
        if (isCustom) {
          WallpaperThumbnailCache.peek(wallpaperUri)
            ?: withContext(Dispatchers.IO) { WallpaperThumbnailCache.load(context, wallpaperUri) }
        } else {
          null
        }
    }.value
  val currentName =
    when {
      isNone -> stringResource(R.string.wallpaper_preset_none)
      preset != null -> stringResource(preset.labelRes)
      else -> stringResource(R.string.wallpaper_preset_custom)
    }
  val thumbShape = RoundedCornerShape(12.dp)

  PreferenceCard(modifier = modifier) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .clickable(onClick = onClick)
          .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier
            .size(width = 54.dp, height = 84.dp)
            .clip(thumbShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, thumbShape),
        contentAlignment = Alignment.Center,
      ) {
        when {
          isNone ->
            Icon(
              Icons.RoundedFilled.Block,
              contentDescription = null,
              modifier = Modifier.size(24.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          preset != null -> Canvas(modifier = Modifier.fillMaxSize()) { drawWallpaperPreset(preset) }
          customThumbnail != null ->
            Image(
              bitmap = customThumbnail,
              contentDescription = null,
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
        }
      }
      Column(
        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
      ) {
        Text(
          text = stringResource(R.string.pref_appearance_custom_wallpaper_title),
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          text = currentName,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = stringResource(R.string.pref_appearance_custom_wallpaper_summary),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.outline,
        )
      }
      Icon(
        Icons.RoundedFilled.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * Decoded thumbnails kept by wallpaper URI. The Appearance screen leaves composition whenever the
 * wallpaper editor is opened, so without this every Back re-decoded the wallpaper and the card
 * showed an empty frame until it finished (visible as the thumbnail popping in).
 */
internal object WallpaperThumbnailCache {
  private val cache = LruCache<String, ImageBitmap>(MAX_ENTRIES)

  // A saved custom wallpaper is a multi-megabyte data: URI; key on its hash instead of holding the string.
  private fun keyOf(wallpaperUri: String) = "${wallpaperUri.length}:${wallpaperUri.hashCode()}"

  fun peek(wallpaperUri: String): ImageBitmap? = if (wallpaperUri.isBlank()) null else cache.get(keyOf(wallpaperUri))

  /** Returns the cached thumbnail or decodes (and caches) it. Call off the main thread. */
  fun load(
    context: Context,
    wallpaperUri: String,
  ): ImageBitmap? {
    if (wallpaperUri.isBlank()) return null
    val key = keyOf(wallpaperUri)
    cache.get(key)?.let { return it }
    return loadWallpaperThumbnail(context, wallpaperUri)?.also { cache.put(key, it) }
  }

  private const val MAX_ENTRIES = 6
}

/** Small copy of the saved wallpaper, so the settings screen doesn't hold a full-size bitmap. */
private fun loadWallpaperThumbnail(
  context: Context,
  wallpaperUri: String,
): ImageBitmap? {
  val full = loadWallpaperBitmap(context, wallpaperUri) ?: return null
  val targetWidth = 240
  if (full.width <= targetWidth) return full.asImageBitmap()
  val targetHeight = (full.height * (targetWidth / full.width.toFloat())).toInt().coerceAtLeast(1)
  val scaled = Bitmap.createScaledBitmap(full, targetWidth, targetHeight, true)
  if (scaled !== full) full.recycle()
  return scaled.asImageBitmap()
}
