/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
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
  folderMinWidth: Dp = 100.dp,
  videoMinWidth: Dp = 130.dp,
  contentHorizontalPadding: Dp = 8.dp,
  itemSpacing: Dp = 2.dp,
  isGridMode: Boolean = true,
): ResponsiveGridSpans {
  val browserPreferences = koinInject<BrowserPreferences>()
  val folderGridColumnsPortrait by browserPreferences.folderGridColumnsPortrait.collectAsState()
  val folderGridColumnsLandscape by browserPreferences.folderGridColumnsLandscape.collectAsState()
  val videoGridColumnsPortrait by browserPreferences.videoGridColumnsPortrait.collectAsState()
  val videoGridColumnsLandscape by browserPreferences.videoGridColumnsLandscape.collectAsState()
  val manualGridColumnsEnabled by browserPreferences.manualGridColumnsEnabled.collectAsState()

  if (!isGridMode) {
    return ResponsiveGridSpans(spans = 1, folderSpan = 1, videoSpan = 1)
  }

  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val folderGridColumnsPref = if (isLandscape) folderGridColumnsLandscape else folderGridColumnsPortrait
  val videoGridColumnsPref = if (isLandscape) videoGridColumnsLandscape else videoGridColumnsPortrait

  val maxFolders: Int
  val maxVideos: Int

  if (manualGridColumnsEnabled) {
    maxFolders = folderGridColumnsPref.coerceAtLeast(1)
    maxVideos = videoGridColumnsPref.coerceAtLeast(1)
  } else {
    val usableWidth = maxWidth - (contentHorizontalPadding * 2) - itemSpacing
    maxFolders = (usableWidth / folderMinWidth).toInt().coerceAtLeast(1)
    maxVideos = (usableWidth / videoMinWidth).toInt().coerceAtLeast(1)
  }

  val spans = lcm(maxFolders, maxVideos).coerceAtLeast(1)
  val folderSpan = spans / maxFolders
  val videoSpan = spans / maxVideos

  return ResponsiveGridSpans(spans = spans, folderSpan = folderSpan, videoSpan = videoSpan)
}
