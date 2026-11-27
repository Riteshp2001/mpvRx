/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.imageviewer

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.tv.TvEdgeToEdgeScreen
import app.gyrolet.mpvrx.ui.tv.TvPagerFocusArea
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable

@Serializable
data class ImageViewerItem(
  val path: String,
  val name: String,
  val lastModified: Long = 0L,
)

@Serializable
data class ImageViewerScreen(
  val connectionId: Long,
  val folderPath: String,
  val items: List<ImageViewerItem>,
  val initialIndex: Int = 0,
) : Screen,
  TvEdgeToEdgeScreen {
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current

    val images =
      remember(items) {
        items.map { item ->
          NetworkFile(
            name = item.name,
            path = item.path,
            size = 0L,
            isDirectory = false,
            lastModified = item.lastModified,
            mimeType = null,
          )
        }
      }

    // The ViewModel outlives this screen — Navigation3 gives back-stack entries no ViewModelStore
    // of their own — so anything that changes what the list *contains* must be part of the key.
    // Without the list identity, re-opening the same index after changing the sort order or the
    // search filter hands back the previous ViewModel, whose captured list is stale: the rotate
    // action then records itself against a different image than the one on screen.
    val listIdentity = remember(images) { images.map { it.path }.hashCode() }

    val viewModel: ImageViewerViewModel =
      viewModel(
        key = "ImageViewer_${connectionId}_${folderPath}_${listIdentity}_$initialIndex",
        factory =
          ImageViewerViewModel.factory(
            context.applicationContext as Application,
            connectionId,
            images,
            initialIndex,
          ),
      )

    // A configuration change disposes this composition without the user having left the viewer;
    // clearing there would drop their rotation and force every thumbnail to reload.
    val hostActivity = LocalContext.current as? android.app.Activity
    DisposableEffect(Unit) {
      viewModel.onViewerOpened()
      onDispose {
        if (hostActivity?.isChangingConfigurations != true) viewModel.onViewerClosed()
      }
    }

    val currentIndex by viewModel.currentIndex.collectAsState()
    val rotations by viewModel.rotations.collectAsState()
    val imageStates by viewModel.imageStates.collectAsState()
    var overlayVisible by rememberSaveable { mutableStateOf(true) }

    val pagerState = rememberPagerState(initialPage = initialIndex) { images.size }

    LaunchedEffect(pagerState.currentPage) {
      viewModel.setCurrentIndex(pagerState.currentPage)
    }

    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(Color.Black),
    ) {
      HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
      ) { page ->
        val file = images[page]
        // A page with no state yet is one about to load, so start it in the loading state. The
        // default (false) would flash the "no image" glyph for the frame before loadImage runs.
        val state = imageStates[file.path] ?: ImageViewerItemState(isLoading = true)

        LaunchedEffect(file.path) {
          viewModel.loadImage(file)
        }

        ZoomableImage(
          bitmap = state.bitmap,
          placeholder = state.thumbnail,
          isLoading = state.isLoading,
          error = state.error,
          onRetry = { viewModel.retryImage(file) },
          rotationDegrees = rotations[file.path] ?: 0f,
          onClick = { overlayVisible = !overlayVisible },
          modifier = Modifier.fillMaxSize(),
        )
      }

      // TV: Left/Right turn pages; the overlay stays reachable with Up.
      TvPagerFocusArea(pagerState)

      ImageViewerOverlay(
        title = images.getOrNull(currentIndex)?.name ?: "",
        currentIndex = currentIndex,
        totalCount = images.size,
        isVisible = overlayVisible,
        onCloseClick = { backstack.popSafely() },
        onRotateClick = { viewModel.rotateCurrentImage() },
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}
