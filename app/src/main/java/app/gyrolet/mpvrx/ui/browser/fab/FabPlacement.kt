/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.fab

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight
import app.gyrolet.mpvrx.ui.theme.AppMotion

/** True when ScreenNavigation has already reserved the global bottom overlay outside this entry. */
internal val LocalFabHostInsetsApplied = compositionLocalOf { false }

/**
 * Single source of truth for where browser FABs float.
 *
 * [LocalNavigationBarHeight] describes everything floating along the bottom edge of the current
 * region, excluding system insets: the navigation pill on the main tabs, selection actions, and the
 * mini player, whether it sits above the pill, beside it on wide screens, alone at the bottom of a
 * pushed screen such as a folder, or over only the right pane in dual-pane mode. FABs float one
 * spacing above that stack. Keep the home-screen resting position even when a folder has no bar.
 */
object FabPlacement {
  /** Material 3 Scaffold adds this spacing, plus the system bar inset, below its FAB slot. */
  val ScaffoldFabSpacing: Dp = 16.dp

  /** Match the horizontal padding built into Material 3 FloatingActionButtonMenu on Home. */
  val StandaloneEndPadding: Dp = 16.dp

  /** Expanded home pill (68.dp), its bottom margin and the gap above it (12.dp each). */
  val RestingBottomClearance: Dp = 92.dp

  @Composable
  private fun bottomClearance(): Dp =
    if (LocalFabHostInsetsApplied.current) 0.dp
    else maxOf(RestingBottomClearance, LocalNavigationBarHeight.current)

  /** Bottom padding for a FAB hosted in a Scaffold `floatingActionButton` slot. */
  @Composable
  fun scaffoldBottomPadding(): Dp =
    // Scaffold owns its built-in 16.dp FAB spacing. When the navigation host has already
    // reserved the bottom overlay, bottomClearance() is zero; subtracting ScaffoldFabSpacing
    // here used to produce -16.dp and crash Compose during layout on inset-by-host screens.
    animatedPadding((bottomClearance() - ScaffoldFabSpacing).coerceAtLeast(0.dp))

  /**
   * Bottom padding for a FAB drawn as an overlay at the bottom of a full-height region rather than
   * in a Scaffold slot. Apply it inside [overlayInsets] so it lines up with Scaffold-hosted FABs.
   */
  @Composable
  fun overlayBottomPadding(): Dp =
    animatedPadding(bottomClearance().coerceAtLeast(0.dp))

  /** The system bars Scaffold keeps its FAB clear of. */
  val overlayInsets: WindowInsets
    @Composable get() = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

  @Composable
  private fun animatedPadding(target: Dp): Dp {
    val padding by animateDpAsState(
      targetValue = target.coerceAtLeast(0.dp),
      animationSpec = AppMotion.spatial<Dp>(AppMotion.noBounce(Spring.StiffnessMedium), snap()),
      label = "fab_bottom_padding",
    )
    return padding.coerceAtLeast(0.dp)
  }
}
