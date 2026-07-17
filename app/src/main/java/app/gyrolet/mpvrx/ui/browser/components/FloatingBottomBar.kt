package app.gyrolet.mpvrx.ui.browser.components

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.icons.AppIcon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration

private data class BarLayoutParams(
  val buttonSize: androidx.compose.ui.unit.Dp,
  val iconSize: androidx.compose.ui.unit.Dp,
  val spacing: androidx.compose.ui.unit.Dp,
  val rowPaddingHorizontal: androidx.compose.ui.unit.Dp,
  val rowPaddingVertical: androidx.compose.ui.unit.Dp,
  val surfacePaddingHorizontal: androidx.compose.ui.unit.Dp,
  val surfacePaddingVertical: androidx.compose.ui.unit.Dp
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BrowserBottomBar(
  isSelectionMode: Boolean,
  onCopyClick: () -> Unit,
  onMoveClick: () -> Unit,
  onDownscaleClick: () -> Unit = {},
  onRenameClick: () -> Unit,
  onDeleteClick: () -> Unit,
  onAddToPlaylistClick: () -> Unit,
  modifier: Modifier = Modifier,
  showCopy: Boolean = true,
  showMove: Boolean = true,
  showDownscale: Boolean = false,
  showRename: Boolean = true,
  showDelete: Boolean = true,
  showAddToPlaylist: Boolean = true,
) {
  val configuration = LocalConfiguration.current
  val isTablet = configuration.smallestScreenWidthDp >= 600
  val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

  AnimatedVisibility(
    visible = isSelectionMode,
    modifier = modifier,
    enter = fadeIn(),
    exit = fadeOut(),
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
      val availableWidth = maxWidth
      val visibleCount = listOf(showCopy, showMove, showDownscale, showRename, showAddToPlaylist, showDelete).count { it }

      val layoutParams = when {
        visibleCount <= 1 -> {
          val buttonSize = if (isTablet) 64.dp else 56.dp
          val iconSize = if (isTablet) 32.dp else 28.dp
          val surfPadHoriz = if (availableWidth < 360.dp) 12.dp else 24.dp
          val surfPadVert = if (isTablet) 16.dp else if (isLandscape) 6.dp else 12.dp
          val rowPadHoriz = if (availableWidth < 360.dp) 8.dp else 16.dp
          val rowPadVert = if (isTablet) 8.dp else if (isLandscape) 4.dp else 8.dp
          BarLayoutParams(
            buttonSize = buttonSize,
            iconSize = iconSize,
            spacing = 0.dp,
            rowPaddingHorizontal = rowPadHoriz,
            rowPaddingVertical = rowPadVert,
            surfacePaddingHorizontal = surfPadHoriz,
            surfacePaddingVertical = surfPadVert
          )
        }
        isTablet -> {
          val options = listOf(
            BarLayoutParams(64.dp, 32.dp, 24.dp, 20.dp, 8.dp, 32.dp, 16.dp), // Large
            BarLayoutParams(56.dp, 28.dp, 16.dp, 16.dp, 6.dp, 24.dp, 12.dp), // Medium
            BarLayoutParams(48.dp, 24.dp, 12.dp, 12.dp, 6.dp, 16.dp, 10.dp)  // Small
          )
          options.firstOrNull { opt ->
            val totalWidth = (opt.buttonSize * visibleCount) + (opt.spacing * (visibleCount - 1)) + (opt.rowPaddingHorizontal * 2) + (opt.surfacePaddingHorizontal * 2)
            totalWidth <= availableWidth
          } ?: options.last()
        }
        isLandscape -> {
          val options = listOf(
            BarLayoutParams(56.dp, 28.dp, 12.dp, 10.dp, 4.dp, 16.dp, 6.dp), // Large (Compact vertical)
            BarLayoutParams(48.dp, 24.dp, 10.dp, 8.dp, 4.dp, 12.dp, 6.dp),  // Medium (Compact vertical)
            BarLayoutParams(42.dp, 22.dp, 8.dp, 6.dp, 2.dp, 8.dp, 4.dp),    // Small (Compact vertical)
            BarLayoutParams(36.dp, 18.dp, 6.dp, 4.dp, 2.dp, 6.dp, 4.dp)     // Tiny (Compact vertical)
          )
          options.firstOrNull { opt ->
            val totalWidth = (opt.buttonSize * visibleCount) + (opt.spacing * (visibleCount - 1)) + (opt.rowPaddingHorizontal * 2) + (opt.surfacePaddingHorizontal * 2)
            totalWidth <= availableWidth
          } ?: options.last()
        }
        else -> {
          val options = listOf(
            BarLayoutParams(56.dp, 28.dp, 12.dp, 10.dp, 8.dp, 16.dp, 12.dp), // Large
            BarLayoutParams(48.dp, 24.dp, 10.dp, 8.dp, 6.dp, 12.dp, 10.dp),  // Medium
            BarLayoutParams(42.dp, 22.dp, 8.dp, 6.dp, 4.dp, 8.dp, 8.dp),     // Small
            BarLayoutParams(36.dp, 18.dp, 6.dp, 4.dp, 4.dp, 6.dp, 6.dp)      // Tiny
          )
          options.firstOrNull { opt ->
            val totalWidth = (opt.buttonSize * visibleCount) + (opt.spacing * (visibleCount - 1)) + (opt.rowPaddingHorizontal * 2) + (opt.surfacePaddingHorizontal * 2)
            totalWidth <= availableWidth
          } ?: options.last()
        }
      }

      Surface(
        modifier = Modifier
          .windowInsetsPadding(WindowInsets.systemBars)
          .align(Alignment.BottomCenter)
          .padding(horizontal = layoutParams.surfacePaddingHorizontal, vertical = layoutParams.surfacePaddingVertical),
        shape = RoundedCornerShape(percent = 100),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp
      ) {
        Row(
          modifier = Modifier.padding(horizontal = layoutParams.rowPaddingHorizontal, vertical = layoutParams.rowPaddingVertical),
          horizontalArrangement = Arrangement.spacedBy(layoutParams.spacing),
          verticalAlignment = Alignment.CenterVertically
        ) {
          BrowserBottomBarButton(showCopy, onCopyClick, Icons.RoundedFilled.ContentCopy, "Copy", layoutParams.buttonSize, layoutParams.iconSize)
          BrowserBottomBarButton(showMove, onMoveClick, Icons.RoundedFilled.DriveFileMove, "Move", layoutParams.buttonSize, layoutParams.iconSize)
          BrowserBottomBarButton(showDownscale, onDownscaleClick, Icons.RoundedFilled.FitScreen, "Compressor", layoutParams.buttonSize, layoutParams.iconSize)
          BrowserBottomBarButton(showRename, onRenameClick, Icons.RoundedFilled.DriveFileRenameOutline, "Rename", layoutParams.buttonSize, layoutParams.iconSize)
          BrowserBottomBarButton(showAddToPlaylist, onAddToPlaylistClick, Icons.RoundedFilled.PlaylistAdd, "Add to Playlist", layoutParams.buttonSize, layoutParams.iconSize)
          BrowserBottomBarButton(showDelete, onDeleteClick, Icons.RoundedFilled.Delete, "Delete", layoutParams.buttonSize, layoutParams.iconSize, tint = MaterialTheme.colorScheme.error)
        }
      }
    }
  }
}


@Composable
private fun BrowserBottomBarButton(
  show: Boolean,
  onClick: () -> Unit,
  icon: AppIcon,
  contentDescription: String,
  buttonSize: androidx.compose.ui.unit.Dp,
  iconSize: androidx.compose.ui.unit.Dp,
  tint: Color = MaterialTheme.colorScheme.primary,
) {
  if (show) {
    IconButton(
      onClick = onClick,
      modifier = Modifier.size(buttonSize)
    ) {
      Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = Modifier.size(iconSize),
        tint = tint,
      )
    }
  }
}
