/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.fab

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight

/** The main TV pager reserves less trailing overscan than a pushed screen. */
internal val LocalFabTrailingInset = compositionLocalOf { 0.dp }

/**
 * Screen-level FABs use the bottom-end of their host's safe bounds. The dock clearance is
 * local to that region and already zero if the navigation host reserved it outside the screen.
 *
 * Material 3 Scaffold adds 16.dp at the end and bottom. FloatingActionButtonMenu adds another
 * 16.dp on both axes inside its own bounds. Match that anchor for buttons without a menu.
 */
internal object FabPlacement {
  private val ScaffoldSpacing = 16.dp
  private val MenuSpacing = 16.dp

  @Composable
  fun scaffoldInsets(): WindowInsets =
    ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime.only(WindowInsetsSides.Bottom))

  @Composable
  fun scaffoldMenu(): Modifier = padding(extraSpacing = 0.dp)

  @Composable
  fun scaffoldButton(): Modifier = padding(extraSpacing = MenuSpacing)

  @Composable
  fun overlayMenu(): Modifier =
    Modifier.windowInsetsPadding(overlayInsets()).then(padding(extraSpacing = ScaffoldSpacing))

  @Composable
  fun overlayButton(clearance: Dp): Modifier =
    Modifier
      .windowInsetsPadding(overlayInsets())
      .then(padding(extraSpacing = ScaffoldSpacing + MenuSpacing, clearance = clearance))

  @Composable
  private fun overlayInsets(): WindowInsets =
    scaffoldInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

  @Composable
  private fun padding(
    extraSpacing: Dp,
    clearance: Dp = LocalNavigationBarHeight.current,
  ): Modifier =
    Modifier.padding(
      end = LocalFabTrailingInset.current + extraSpacing,
      bottom = clearance.coerceAtLeast(0.dp) + extraSpacing,
    )
}
