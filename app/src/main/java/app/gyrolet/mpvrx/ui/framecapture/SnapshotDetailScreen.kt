/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.framecapture

import android.app.Application
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.framecapture.VideoAvailability
import app.gyrolet.mpvrx.domain.framecapture.VideoSource
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.tv.TvEdgeToEdgeScreen
import app.gyrolet.mpvrx.ui.tv.TvPagerFocusArea
import app.gyrolet.mpvrx.ui.tv.tvOverscanPadding
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.imageviewer.ZoomableImage
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class SnapshotDetailScreen(
  val captures: List<SnapshotDetailItem>,
  val initialIndex: Int = 0,
) : Screen, TvEdgeToEdgeScreen {

  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()

    // Navigation3 gives back-stack entries no ViewModelStore, so viewModel() is keyed on the
    // Activity and reused by key. The key must cover everything that changes what the viewer shows,
    // or reopening after a deletion hands back a ViewModel holding the pre-deletion list.
    val listIdentity = remember(captures) { captures.map { it.id }.hashCode() }
    val viewModel: SnapshotDetailViewModel =
      viewModel(
        key = "SnapshotDetail_${listIdentity}_$initialIndex",
        factory =
          SnapshotDetailViewModel.factory(
            context.applicationContext as Application,
            captures,
            initialIndex,
          ),
      )

    DisposableEffect(viewModel) {
      viewModel.onViewerOpened()
      onDispose { viewModel.onViewerClosed() }
    }

    val currentIndex by viewModel.currentIndex.collectAsState()
    val images by viewModel.images.collectAsState()
    val deletedIds by viewModel.deletedIds.collectAsState()
    val rotations by viewModel.rotations.collectAsState()
    val sources by viewModel.sources.collectAsState()
    var overlayVisible by remember { mutableStateOf(true) }
    var pendingDelete by remember { mutableStateOf<SnapshotDetailItem?>(null) }
    val unavailableMessage = stringResource(R.string.snapshot_source_unavailable)
    val notConnectedMessage = stringResource(R.string.snapshot_source_not_connected)
    val checkConnectionMessage = stringResource(R.string.snapshot_source_check_connection)
    val connectingMessage = stringResource(R.string.playlist_connecting_network)

    val visibleItems = remember(captures, deletedIds) { captures.filterNot { it.id in deletedIds } }
    if (visibleItems.isEmpty()) {
      LaunchedEffect(Unit) { backStack.popSafely() }
      return
    }

    val pagerState =
      rememberPagerState(initialPage = initialIndex.coerceIn(0, visibleItems.lastIndex)) {
        visibleItems.size
      }
    LaunchedEffect(pagerState.currentPage, visibleItems) {
      val page = pagerState.currentPage.coerceIn(0, visibleItems.lastIndex)
      viewModel.setCurrentIndex(page)
      if (page != pagerState.currentPage) pagerState.scrollToPage(page)
    }

    // §6: a snapshot whose gallery file the user deleted should say so and offer to drop the record,
    // rather than showing a broken image forever.
    val currentItem = visibleItems.getOrNull(currentIndex)
    var imageMissing by remember { mutableStateOf(false) }
    LaunchedEffect(currentItem?.id) {
      val item = currentItem ?: return@LaunchedEffect
      imageMissing = !viewModel.isImageAvailable(item)
    }

    // The viewer keeps the system bars. §4.4 asked for an immersive full screen here, but hiding the
    // status bar on a still image is more disorienting than it is immersive — and the overlay already
    // pads past the bars, so nothing has to be repositioned for them.
    BackHandler { backStack.popSafely() }

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
        val item = visibleItems[page]
        LaunchedEffect(item.id) {
          viewModel.loadImage(item)
          viewModel.loadSource(item)
        }
        val bitmap = images[item.id]
        ZoomableImage(
          bitmap = bitmap,
          placeholder = null,
          isLoading = bitmap == null,
          error = null,
          onRetry = { viewModel.loadImage(item) },
          rotationDegrees = rotations[item.id] ?: 0f,
          onClick = { overlayVisible = !overlayVisible },
          modifier = Modifier.fillMaxSize(),
        )
      }

      // TV: Left/Right turn pages; Up and Down reach the top bar and the actions below.
      TvPagerFocusArea(pagerState)

      SnapshotDetailOverlay(
        item = currentItem,
        source = currentItem?.let { sources[it.id] },
        position = currentIndex + 1,
        totalCount = visibleItems.size,
        isVisible = overlayVisible,
        isImageMissing = imageMissing,
        onCloseClick = { backStack.popSafely() },
        onRotateClick = { currentItem?.let(viewModel::rotate) },
        onJumpToVideo = {
          val item = currentItem ?: return@SnapshotDetailOverlay
          scope.launch {
            // Checking a network share can take seconds; without a hint the tap looks ignored. The
            // delay keeps local files — where the check is a single stat — from ever showing it.
            val hintJob =
              launch {
                delay(CONNECT_HINT_DELAY_MS)
                Toast.makeText(context, connectingMessage, Toast.LENGTH_SHORT).show()
              }
            val availability = viewModel.videoAvailability(item)
            hintJob.cancel()

            // One prompt for every failure, in the playlist's shape: a Toast reading "「subject」reason".
            // The subject is the share the snapshot came from — "WebDAV HomeNAS" tells the user which
            // connection to go and fix, where a file name tells them nothing. Local media has no share,
            // so it falls back to the file name.
            //
            // A Snackbar would sit as a wide bar across the bottom of the viewer, over the very
            // buttons it is talking about, and it has no action to carry: removing a record is
            // already its own button here.
            val subject = shareLabel(sources[item.id]) ?: item.videoTitle
            val reportSourceProblem: (String) -> Unit = { reason ->
              Toast
                .makeText(
                  context,
                  context.getString(R.string.playlist_item_unavailable, subject, reason),
                  Toast.LENGTH_LONG,
                )
                .show()
            }

            when (availability) {
              VideoAvailability.AVAILABLE -> {
                MediaUtils.playFile(
                  source = item.videoUri,
                  context = context,
                  launchSource = "snapshot",
                  title = item.videoTitle,
                  startPositionSeconds = item.positionMs / 1000.0,
                )
                // The viewer deliberately stays on the back stack. Popping it here would animate the
                // Snapshots grid back into view before the player finishes coming up, so the user
                // sees their library flash between tapping and playback. Leaving it mounted lets the
                // player cover it, and returning from playback lands back on the frame they jumped
                // from.
              }

              VideoAvailability.NOT_CONNECTED -> reportSourceProblem(notConnectedMessage)

              // Open but not answering: the connection is what needs looking at, not the record.
              VideoAvailability.UNREACHABLE -> reportSourceProblem(checkConnectionMessage)

              VideoAvailability.UNAVAILABLE -> reportSourceProblem(unavailableMessage)
            }
          }
        },
        onDeleteClick = { pendingDelete = currentItem },
        modifier = Modifier.fillMaxSize(),
      )
    }

    pendingDelete?.let { target ->
      AlertDialog(
        onDismissRequest = { pendingDelete = null },
        title = { Text(stringResource(R.string.snapshot_delete_title)) },
        singleRowActions = true,
        text = { Text(stringResource(R.string.snapshot_delete_message)) },
        confirmButton = {
          app.gyrolet.mpvrx.presentation.components.DeleteSizeButton(
            paths = listOfNotNull(target.imageUri ?: target.imagePath),
            onClick = {
              pendingDelete = null
              scope.launch { viewModel.delete(target) }
            },
          )
        },
        dismissButton = {
          TextButton(onClick = { pendingDelete = null }) {
            Text(stringResource(R.string.generic_cancel))
          }
        },
      )
    }
  }
}

@Composable
private fun SnapshotDetailOverlay(
  item: SnapshotDetailItem?,
  source: VideoSource?,
  position: Int,
  totalCount: Int,
  isVisible: Boolean,
  isImageMissing: Boolean,
  onCloseClick: () -> Unit,
  onRotateClick: () -> Unit,
  onJumpToVideo: () -> Unit,
  onDeleteClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visible = isVisible,
    enter = fadeIn(),
    exit = fadeOut(),
    modifier = modifier,
  ) {
    val topScrim =
      remember {
        Brush.verticalGradient(
          colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent),
        )
      }
    val bottomScrim =
      remember {
        Brush.verticalGradient(
          colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
        )
      }

    Box(modifier = Modifier.fillMaxSize()) {
      // Back, the two-line caption, rotate — the caption sits beside the arrow rather than centred so
      // a long video title has the full remaining width to run into.
      Row(
        modifier =
          Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .background(topScrim)
            .windowInsetsPadding(WindowInsets.statusBars)
            .tvOverscanPadding(bottom = false)
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onCloseClick) {
          Icon(
            Icons.RoundedFilled.ArrowBack,
            contentDescription = stringResource(R.string.back),
            tint = Color.White,
          )
        }

        Column(
          modifier = Modifier.weight(1f).padding(start = 4.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Text(
            text = item?.videoTitle.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = sourceCaption(source),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        IconButton(onClick = onRotateClick) {
          Icon(
            Icons.RoundedFilled.ScreenRotation,
            contentDescription = stringResource(R.string.snapshot_rotate),
            tint = Color.White,
          )
        }
      }

      Column(
        modifier =
          Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(bottomScrim)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .tvOverscanPadding(top = false)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (isImageMissing) {
          Text(
            text = stringResource(R.string.snapshot_file_missing),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Button(onClick = onJumpToVideo) {
            Icon(Icons.RoundedFilled.PlayArrow, contentDescription = null)
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(stringResource(R.string.snapshot_jump_to_video))
          }

          if (totalCount > 1) {
            Text(
              text = "$position / $totalCount",
              style = MaterialTheme.typography.labelLarge,
              color = Color.White,
              modifier =
                Modifier
                  .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                  .padding(horizontal = 12.dp, vertical = 6.dp),
            )
          }

          TextButton(onClick = onDeleteClick) {
            Icon(
              Icons.RoundedFilled.Delete,
              contentDescription = stringResource(R.string.snapshot_remove),
              tint = Color.White,
            )
          }
        }
      }
    }
  }
}

/**
 * `WebDAV HomeNAS` for a saved share, null for local media or a connection that no longer exists.
 *
 * The caption and the failure prompt both name the source this way, so a user who is told to go and
 * connect something sees the same words here as on the Network tab.
 */
private fun shareLabel(source: VideoSource?): String? =
  source
    ?.takeIf { it.protocolLabel != null }
    ?.let { listOfNotNull(it.protocolLabel, it.connectionName).joinToString(" ") }
    ?.takeIf { it.isNotBlank() }

/**
 * `local • /storage/emulated/0/DCIM/a.mp4` on this device, `WebDAV MyNAS • /Movies/a.mp4` for a saved
 * share. The protocol and name are read from the connection rather than stored on the row, so renaming
 * a connection shows up here.
 */
@Composable
private fun sourceCaption(source: VideoSource?): String {
  val resolved = source ?: return ""
  val origin = shareLabel(resolved) ?: stringResource(R.string.snapshot_source_local)
  return "$origin • ${resolved.path}"
}

/** Only a source check slow enough to be felt earns a hint; a local file never reaches this. */
private const val CONNECT_HINT_DELAY_MS = 400L
