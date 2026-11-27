// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.ui.browser.components

import androidx.compose.animation.core.snap
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.gyrolet.mpvrx.ui.theme.AppMotion

/** Sorting and pinning move existing keyed items; scrolling never replays an entrance. */
@Composable
internal fun LazyItemScope.mediaItemMotion(): Modifier =
  Modifier.animateItem(
    fadeInSpec = null,
    placementSpec = AppMotion.spatial(AppMotion.Spatial.StandardOffset, snap()),
    fadeOutSpec = null,
  )

@Composable
internal fun LazyGridItemScope.mediaItemMotion(): Modifier =
  Modifier.animateItem(
    fadeInSpec = null,
    placementSpec = AppMotion.spatial(AppMotion.Spatial.StandardOffset, snap()),
    fadeOutSpec = null,
  )
