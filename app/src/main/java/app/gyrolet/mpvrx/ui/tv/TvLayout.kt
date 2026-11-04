/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.components.AppToast
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor
import kotlin.math.abs

/** Android TV, Google TV and Fire TV: television UI mode or leanback hardware. */
@Composable
@ReadOnlyComposable
internal fun isTelevision(): Boolean = DeviceFormFactor.isTelevision(LocalContext.current)

/** Whether the device has an app for the picker [input] would open; many TVs (Fire TV) ship none. */
internal fun <I> ActivityResultLauncher<I>.canLaunch(
  context: Context,
  input: I,
): Boolean = runCatching { contract.createIntent(context, input).resolveActivity(context.packageManager) != null }
  .getOrDefault(false)

/** TVs open the system picker when they have one; phones, and TVs without one, use mpvRx's own. */
internal fun <I> ActivityResultLauncher<I>.launchOrInApp(
  context: Context,
  input: I,
  isTelevision: Boolean,
  inAppAvailable: Boolean,
  inApp: () -> Unit,
) {
  if (inAppAvailable && (!isTelevision || !canLaunch(context, input))) return inApp()
  launch(input)
}

internal fun shouldOfferTvPickerFallback(isTelevision: Boolean, requestCode: Int, intent: Intent): Boolean =
  isTelevision && requestCode >= 0 && intent.component == null && intent.action in TvPickerActivity.SUPPORTED_ACTIONS

internal inline fun Activity.startForResultOrToast(
  intent: Intent,
  requestCode: Int,
  start: (Intent) -> Unit,
) {
  val fallback =
    if (requestCode >= 0 && shouldOfferTvPickerFallback(DeviceFormFactor.isTelevision(this), requestCode, intent)) {
      Intent(intent).setClass(this, TvPickerActivity::class.java)
    } else {
      null
    }
  val target =
    if (fallback != null && !runCatching { intent.resolveActivity(packageManager) != null }.getOrDefault(false)) {
      fallback
    } else {
      intent
    }
  try {
    start(target)
  } catch (error: RuntimeException) {
    if (requestCode < 0 || (error !is ActivityNotFoundException && (error !is SecurityException || fallback == null))) {
      throw error
    }
    if (fallback != null && target !== fallback) {
      try {
        start(fallback)
        return
      } catch (_: ActivityNotFoundException) {
      } catch (_: SecurityException) {
      }
    }
    AppToast.warning(this, getString(R.string.no_app_for_action))
  }
}

/**
 * Overscan-safe margins from the Android TV design guidelines: 5% of the 960x540dp frame every TV
 * reports, so nothing focusable or readable sits where older sets crop the picture. Backgrounds
 * still run to the screen edge; only content is inset.
 */
internal object TvSafeArea {
  val Horizontal: Dp = 48.dp
  val Vertical: Dp = 27.dp
  val Padding: PaddingValues = PaddingValues(horizontal = Horizontal, vertical = Vertical)
}

/**
 * Screens that show full-bleed media, or lay out their own TV safe area (the main screen with its
 * side rail), opt out of the inset the screen host applies to every other screen on TV.
 */
interface TvEdgeToEdgeScreen

/**
 * TV-only overscan padding for controls drawn over full-bleed media (viewer top and bottom bars),
 * whose backgrounds should still reach the edge. Place after the background, before inner padding.
 */
internal fun Modifier.tvOverscanPadding(
  top: Boolean = true,
  bottom: Boolean = true,
): Modifier =
  composed {
    if (!isTelevision()) return@composed this
    this.padding(
      start = TvSafeArea.Horizontal,
      end = TvSafeArea.Horizontal,
      top = if (top) TvSafeArea.Vertical else 0.dp,
      bottom = if (bottom) TvSafeArea.Vertical else 0.dp,
    )
  }

/**
 * D-pad scrolling keeps the focused item about a third of the way into its list or row instead of
 * flush with the edge, so the next item is always visible and focus never parks in the overscan
 * area. Same keyline as Compose's leanback default, but applied to every TV form factor.
 */
internal object TvBringIntoViewSpec : BringIntoViewSpec {
  private const val KEYLINE_FRACTION = 0.3f

  override fun calculateScrollDistance(
    offset: Float,
    size: Float,
    containerSize: Float,
  ): Float {
    val itemSize = abs(size)
    val keyline = KEYLINE_FRACTION * containerSize
    // An item that fits but would overflow past the keyline is aligned to the trailing edge.
    val target =
      if (itemSize <= containerSize && containerSize - keyline < itemSize) {
        containerSize - itemSize
      } else {
        keyline
      }
    return offset - target
  }
}
