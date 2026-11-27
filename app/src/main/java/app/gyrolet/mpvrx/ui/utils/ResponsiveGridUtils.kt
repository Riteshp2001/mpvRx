/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import org.koin.compose.koinInject
import kotlin.math.abs

fun lcm(
  a: Int,
  b: Int,
): Int = if (a == 0 || b == 0) 0 else abs(a * b) / gcd(a, b)

fun gcd(
  a: Int,
  b: Int,
): Int = if (b == 0) a else gcd(b, a % b)

data class ResponsiveGridSpans(
  val spans: Int,
  val folderSpan: Int,
  val videoSpan: Int,
)

@Composable
fun calculateResponsiveGridSpans(
  maxWidth: Dp,
  folderMinWidth: Dp = 90.dp,
  videoMinWidth: Dp = 130.dp,
  contentHorizontalPadding: Dp = 8.dp,
  itemSpacing: Dp = 2.dp,
  isGridMode: Boolean = true,
  isDualPane: Boolean = false,
): ResponsiveGridSpans {
  val browserPreferences = koinInject<BrowserPreferences>()
  val folderGridColumnsPortrait by browserPreferences.folderGridColumnsPortrait.collectAsState()
  val folderGridColumnsLandscape by browserPreferences.folderGridColumnsLandscape.collectAsState()
  val videoGridColumnsPortrait by browserPreferences.videoGridColumnsPortrait.collectAsState()
  val videoGridColumnsLandscape by browserPreferences.videoGridColumnsLandscape.collectAsState()
  val folderGridColumnsDualPanePortrait by browserPreferences.folderGridColumnsDualPanePortrait.collectAsState()
  val folderGridColumnsDualPaneLandscape by browserPreferences.folderGridColumnsDualPaneLandscape.collectAsState()
  val videoGridColumnsDualPanePortrait by browserPreferences.videoGridColumnsDualPanePortrait.collectAsState()
  val videoGridColumnsDualPaneLandscape by browserPreferences.videoGridColumnsDualPaneLandscape.collectAsState()
  val manualGridColumnsEnabled by browserPreferences.manualGridColumnsEnabled.collectAsState()

  if (!isGridMode) {
    return ResponsiveGridSpans(spans = 1, folderSpan = 1, videoSpan = 1)
  }

  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val usableWidth = maxWidth - (contentHorizontalPadding * 2) - itemSpacing
  val isTelevision =
    app.gyrolet.mpvrx.utils.device.DeviceFormFactor
      .isTelevision(androidx.compose.ui.platform.LocalContext.current)
  val minimumFolderWidth = if (isTelevision) maxOf(folderMinWidth, 160.dp) else folderMinWidth
  val minimumVideoWidth = if (isTelevision) maxOf(videoMinWidth, 240.dp) else videoMinWidth
  val dynamicFolders = (usableWidth / minimumFolderWidth).toInt().coerceAtLeast(1)
  val dynamicVideos = (usableWidth / minimumVideoWidth).toInt().coerceAtLeast(1)

  val maxFolders: Int
  val maxVideos: Int

  if (manualGridColumnsEnabled) {
    // Restored phone column counts must not shrink TV cards below a readable D-pad target.
    val maxSafeFolders =
      if (isTelevision) dynamicFolders else maxOf(dynamicFolders + 3, (usableWidth / 70.dp).toInt()).coerceAtLeast(1)
    val maxSafeVideos =
      if (isTelevision) dynamicVideos else maxOf(dynamicVideos + 3, (usableWidth / 90.dp).toInt()).coerceAtLeast(1)

    if (isDualPane) {
      val dualFolderPref = if (isLandscape) folderGridColumnsDualPaneLandscape else folderGridColumnsDualPanePortrait
      val dualVideoPref = if (isLandscape) videoGridColumnsDualPaneLandscape else videoGridColumnsDualPanePortrait
      maxFolders = if (dualFolderPref > 0) dualFolderPref.coerceIn(1, maxSafeFolders) else dynamicFolders
      maxVideos = if (dualVideoPref > 0) dualVideoPref.coerceIn(1, maxSafeVideos) else dynamicVideos
    } else {
      val folderGridColumnsPref = if (isLandscape) folderGridColumnsLandscape else folderGridColumnsPortrait
      val videoGridColumnsPref = if (isLandscape) videoGridColumnsLandscape else videoGridColumnsPortrait
      maxFolders = if (folderGridColumnsPref > 0) folderGridColumnsPref.coerceIn(1, maxSafeFolders) else dynamicFolders
      maxVideos = if (videoGridColumnsPref > 0) videoGridColumnsPref.coerceIn(1, maxSafeVideos) else dynamicVideos
    }
  } else {
    maxFolders = dynamicFolders
    maxVideos = dynamicVideos
  }

  val spans = lcm(maxFolders, maxVideos).coerceAtLeast(1)
  val folderSpan = spans / maxFolders
  val videoSpan = spans / maxVideos

  return ResponsiveGridSpans(spans = spans, folderSpan = folderSpan, videoSpan = videoSpan)
}
