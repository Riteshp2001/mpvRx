/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.MpvConfigControlledFeatures
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.AmbientStyle
import app.gyrolet.mpvrx.ui.player.AudiobookPlayback
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.ui.player.PlaybackPhase
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.components.VideoAmbientBackground
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.koin.compose.koinInject

/**
 * Album art for audio switched to the video layout. PlayerActivity keeps it as a window layer
 * between YouTube Ambient and mpv's OSD layer, so statistics and subtitles draw on top of it.
 * [onBackdrop] receives its recording: the video layout's Liquid Glass samples that instead of
 * mpv's empty video output.
 */
@Composable
@Suppress("ViewModelForwarding")
internal fun PresentationArtwork(
  viewModel: PlayerViewModel,
  onBackdrop: (LayerBackdrop?) -> Unit,
) {
  val presentation by viewModel.playerPresentation.collectAsStateWithLifecycle()
  val defaultAudioLayout by viewModel.defaultAudioLayout.collectAsStateWithLifecycle()
  // Audio opens in the music player, so only an explicit switch puts it in the video layout.
  if (presentation != false || defaultAudioLayout != true) return

  val session by PlaybackSession.state.collectAsStateWithLifecycle()
  val queue by PlaybackSession.queue.collectAsStateWithLifecycle()
  val scene = rememberLayerBackdrop()
  DisposableEffect(scene) {
    onBackdrop(scene)
    onDispose { onBackdrop(null) }
  }
  key(session.generation) {
    AudioArtworkVideoBackground(
      viewModel = viewModel,
      item = session.currentItem ?: queue.currentItem,
      modifier = Modifier.layerBackdrop(scene),
    )
  }
}

@Composable
private fun AudioArtworkVideoBackground(
  viewModel: PlayerViewModel,
  item: PlaybackItem?,
  modifier: Modifier = Modifier,
) {
  val session by PlaybackSession.state.collectAsStateWithLifecycle()
  val path by PlaybackSession.propString["path"].collectAsStateWithLifecycle()
  val streamPath by PlaybackSession.propString["stream-open-filename"].collectAsStateWithLifecycle()
  val book by AudiobookPlayback.book.collectAsStateWithLifecycle()
  val source =
    item?.originalUri?.takeIf { it.isNotBlank() }
      ?: item?.playableUri?.takeIf { it.isNotBlank() }
      ?: path?.takeIf { it.isNotBlank() }
      ?: streamPath
  val artworkUri =
    book?.takeIf { it.book.id == item?.audiobook?.bookId }?.book?.coverUri?.takeIf { it.isNotBlank() }
      ?: item?.artworkUri?.takeIf { it.isNotBlank() }
  val metadata = rememberAudioPresentationMetadata(source, artworkUri)
  val artwork = metadata?.artwork
  val mediaTitle by PlaybackSession.propString["media-title"].collectAsStateWithLifecycle()
  val rawArtist by PlaybackSession.propString["metadata/by-key/Artist"].collectAsStateWithLifecycle()
  val rawArtistAlt by PlaybackSession.propString["metadata/artist"].collectAsStateWithLifecycle()
  val rawAlbumArtist by PlaybackSession.propString["metadata/by-key/album_artist"].collectAsStateWithLifecycle()
  val rawPerformer by PlaybackSession.propString["metadata/by-key/PERFORMER"].collectAsStateWithLifecycle()
  val artist =
    sequenceOf(item?.artist, rawArtist, rawArtistAlt, rawAlbumArtist, rawPerformer, metadata?.artist)
      .filterNotNull()
      .firstOrNull { it.isNotBlank() }
  val highQualityEnabled by koinInject<AudioPreferences>().highQualityArtwork.collectAsStateWithLifecycle()
  val query =
    rememberArtworkQuery(
      enabled = highQualityEnabled && item?.audiobook == null,
      phase = session.phase,
      generation = session.generation,
      itemTitle = item?.title,
      mediaTitle = mediaTitle,
      artistFallback = artist,
    )
  val highQualityCover = rememberHighQualityArtwork(query)
  val hasMpvAlbumArt by viewModel.hasAlbumArt.collectAsStateWithLifecycle()
  val mpvArtwork =
    rememberMpvAlbumArt(
      enabled = highQualityCover == null && artwork == null && hasMpvAlbumArt && session.phase == PlaybackPhase.READY,
      key = session.generation,
    )
  val bitmap = highQualityCover ?: artwork ?: mpvArtwork
  val statisticsPage by PlaybackSession.visibleStatisticsPage.collectAsStateWithLifecycle()
  val osdLayer by PlaybackSession.osdOverlayAttached.collectAsStateWithLifecycle()
  // Without FongMi's OSD layer, mpv draws statistics only into the video Surface underneath.
  if (statisticsPage != 0 && !osdLayer) return

  val ambientEnabled by viewModel.isAmbientEnabled.collectAsStateWithLifecycle()
  val ambientStyle by viewModel.ambientStyle.collectAsStateWithLifecycle()
  val edgeBlend by viewModel.ambientYouTubeEdgeBlend.collectAsStateWithLifecycle()
  var viewport by remember { mutableStateOf(IntSize.Zero) }
  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .onSizeChanged { viewport = it },
    contentAlignment = Alignment.Center,
  ) {
    if (bitmap != null) {
      val image = remember(bitmap) { bitmap.asImageBitmap() }
      if (ambientEnabled &&
        ambientStyle == AmbientStyle.YouTube &&
        !MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.AMBIENT) &&
        viewport.width > 0 &&
        viewport.height > 0
      ) {
        // Audio has artwork instead of a video frame that PixelCopy can sample.
        val scale = minOf(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
        val x = (viewport.width - image.width * scale) / 2f
        val y = (viewport.height - image.height * scale) / 2f
        if (x >= 1f || y >= 1f) {
          VideoAmbientBackground(
            frame = image,
            baseColor = null,
            accentColor = null,
            videoBounds = Rect(x, y, viewport.width - x, viewport.height - y),
            edgeBlend = edgeBlend,
            frameModifier = Modifier.blur(48.dp),
          )
        }
      }
      Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Icon(
        imageVector = Icons.RoundedFilled.Audiotrack,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(96.dp),
      )
    }
  }
}
