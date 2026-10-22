package app.gyrolet.mpvrx.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import app.gyrolet.mpvrx.core.media.MediaFile
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.platform.LocalAppDependencies
import app.gyrolet.mpvrx.ui.navigation.MainTab

/** Floating expressive pill navigation, the same visual language as the Android bottom bar. */
@Composable
fun ExpressivePillNavigationBar(
  tabs: List<MainTab>,
  selected: MainTab,
  onSelect: (MainTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.shadow(12.dp, CircleShape, clip = false),
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tonalElevation = 3.dp,
  ) {
    Row(
      modifier = Modifier.padding(6.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      tabs.forEach { tab ->
        val isSelected = tab == selected
        val container by animateColorAsState(
          if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
          label = "navContainer",
        )
        val content by animateColorAsState(
          if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
          label = "navContent",
        )
        val horizontal by animateDpAsState(if (isSelected) 20.dp else 14.dp, label = "navPadding")
        Row(
          modifier =
            Modifier
              .clip(CircleShape)
              .background(container)
              .clickable { onSelect(tab) }
              .padding(horizontal = horizontal, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(tab.icon(), contentDescription = tab.label, tint = content, modifier = Modifier.size(24.dp))
          if (isSelected) {
            Spacer(Modifier.width(8.dp))
            Text(tab.label, color = content, style = MaterialTheme.typography.labelLarge)
          }
        }
      }
    }
  }
}

fun MainTab.icon(): ImageVector =
  when (this) {
    MainTab.Home -> Icons.Home
    MainTab.Music -> Icons.QueueMusic
    MainTab.Network -> Icons.Language
    MainTab.Profile -> Icons.Person
  }

@Composable
fun InfoChip(
  text: String,
  modifier: Modifier = Modifier,
  highlighted: Boolean = false,
) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    modifier =
      modifier
        .clip(RoundedCornerShape(6.dp))
        .background(
          if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        ).padding(horizontal = 8.dp, vertical = 3.dp),
  )
}

/** Large expressive page title row used at the top of each tab. */
@Composable
fun ScreenHeader(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  onBack: (() -> Unit)? = null,
  actions: @Composable RowScope.() -> Unit = {},
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (onBack != null) {
      FilledIconCircle(Icons.ArrowBack, "Back", onClick = onBack)
      Spacer(Modifier.width(12.dp))
    } else {
      Spacer(Modifier.width(8.dp))
    }
    Column(Modifier.weight(1f)) {
      Text(
        title,
        style = if (onBack == null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (subtitle != null) {
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
  }
}

@Composable
fun FilledIconCircle(
  icon: ImageVector,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  size: Dp = 44.dp,
  container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
  content: Color = MaterialTheme.colorScheme.onSurface,
) {
  Box(
    modifier = modifier.size(size).clip(CircleShape).background(container).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = contentDescription, tint = content, modifier = Modifier.size(size * 0.5f))
  }
}

@Composable
fun HeaderAction(
  icon: ImageVector,
  contentDescription: String,
  onClick: () -> Unit,
) {
  IconButton(onClick = onClick) { Icon(icon, contentDescription = contentDescription) }
}

@Composable
fun EmptyState(
  icon: ImageVector,
  title: String,
  message: String,
  modifier: Modifier = Modifier,
  action: (@Composable () -> Unit)? = null,
) {
  Column(
    modifier = modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Box(
      Modifier.size(96.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(48.dp))
    }
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(6.dp))
    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (action != null) {
      Spacer(Modifier.height(20.dp))
      action()
    }
  }
}

/** Lazily loaded frame thumbnail with the Android library's rounded 16:9 card styling. */
@Composable
fun MediaThumbnail(
  file: MediaFile,
  modifier: Modifier = Modifier,
  progress: Float? = null,
  badge: String? = null,
) {
  val deps = LocalAppDependencies.current
  val widthPx = with(LocalDensity.current) { 240.dp.roundToPx() }
  var image by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }
  val showThumbnails = deps.settings.browser.showThumbnails.get()
  LaunchedEffect(file.path, showThumbnails) {
    if (showThumbnails && !file.isAudio) image = deps.mediaInfo.thumbnail(file.path, widthPx)
  }
  Box(
    modifier = modifier.clip(RoundedCornerShape(12.dp)).background(
      Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.tertiaryContainer),
      ),
    ),
    contentAlignment = Alignment.Center,
  ) {
    val bitmap = image
    if (bitmap != null) {
      Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
      Icon(
        if (file.isAudio) Icons.MusicNote else Icons.Movie,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
        modifier = Modifier.size(32.dp),
      )
    }
    if (badge != null) {
      Text(
        badge,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        modifier =
          Modifier
            .align(Alignment.BottomEnd)
            .padding(6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
      )
    }
    if (progress != null && progress > 0f) {
      LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = Color.Black.copy(alpha = 0.4f),
        drawStopIndicator = {},
        gapSize = 0.dp,
      )
    }
  }
}
