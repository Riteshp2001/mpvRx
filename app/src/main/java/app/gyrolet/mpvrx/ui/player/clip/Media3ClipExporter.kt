/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.clip

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.View
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToLong

/**
 * Clip exporter backed by Media3 Transformer.
 *
 * The player itself remains libmpv, but Clip export deliberately does not create a second mpv core.
 * Media3 uses Android MediaCodec for decoding/encoding and OpenGL for crop effects, avoiding
 * libmpv builds that do not expose encoding mode and fail during mpv_initialize().
 */
@UnstableApi
internal object Media3ClipExporter {
  private const val PROGRESS_POLL_MS = 150L

  suspend fun export(
    context: Context,
    source: String,
    output: String,
    startSeconds: Double,
    endSeconds: Double,
    crop: ClipCrop?,
    cropFrameWidth: Int,
    cropFrameHeight: Int,
    headers: Map<String, String>,
    audioTrack: ClipTrackOption?,
    subtitleCues: List<ClipSubtitleCue>?,
    onProgress: (Double) -> Unit,
  ): String? =
    withContext(Dispatchers.Main.immediate) {
      coroutineScope {
        File(output).delete()

        val completion = CompletableDeferred<String?>()
        val transformer = buildTransformer(context, headers, audioTrack, completion)

        val mediaItem =
          MediaItem
            .Builder()
            .setUri(clipMediaUri(source))
            .setClippingConfiguration(
              MediaItem.ClippingConfiguration
                .Builder()
                .setStartPositionMs((startSeconds.coerceAtLeast(0.0) * 1000.0).roundToLong())
                .setEndPositionMs((endSeconds.coerceAtLeast(startSeconds) * 1000.0).roundToLong())
                .build(),
            ).build()

        val editedMediaItem =
          EditedMediaItem
            .Builder(mediaItem)
            .setEffects(buildEffects(context, crop, cropFrameWidth, cropFrameHeight, subtitleCues))
            .build()

        var progressJob: Job? = null
        var finished = false
        try {
          transformer.start(editedMediaItem, output)
          progressJob =
            launch {
              val holder = ProgressHolder()
              while (completion.isActive) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                  onProgress((holder.progress / 100.0).coerceIn(0.0, 1.0))
                }
                delay(PROGRESS_POLL_MS)
              }
            }
          completion.await().also { finished = true }
        } finally {
          if (!finished) transformer.cancel()
          progressJob?.cancelAndJoin()
        }
      }
    }

  private fun buildTransformer(
    context: Context,
    headers: Map<String, String>,
    audioTrack: ClipTrackOption?,
    completion: CompletableDeferred<String?>,
  ): Transformer {
    val dataSourceFactory = clipDataSourceFactory(context, headers)
    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
    val decoderFactory =
      DefaultDecoderFactory
        .Builder(context)
        .setEnableDecoderFallback(true)
        .build()
    // The export has its own selector; playback's audio choice is not modified.
    val assetLoaderFactory =
      if (audioTrack != null) {
        val selectorFactory =
          TrackSelector.Factory { selectorContext ->
            DefaultTrackSelector(selectorContext).apply {
              val parameters = buildUponParameters()
              audioTrack.language
                .takeIf { it.isNotBlank() }
                ?.let(parameters::setPreferredAudioLanguage)
              audioTrack.title
                .takeIf { it.isNotBlank() }
                ?.let { parameters.setPreferredAudioLabels(it) }
              setParameters(parameters)
            }
          }
        ExoPlayerAssetLoader.Factory(
          context,
          decoderFactory,
          Clock.DEFAULT,
          mediaSourceFactory,
          selectorFactory,
          null,
          null,
        )
      } else {
        ExoPlayerAssetLoader.Factory(
          context,
          decoderFactory,
          Clock.DEFAULT,
          mediaSourceFactory,
        )
      }

    val listener =
      object : Transformer.Listener {
        override fun onCompleted(
          composition: Composition,
          exportResult: ExportResult,
        ) {
          completion.complete(null)
        }

        override fun onError(
          composition: Composition,
          exportResult: ExportResult,
          exportException: ExportException,
        ) {
          completion.complete(
            exportException.message?.takeIf { it.isNotBlank() }
              ?: "Media3 clip export failed",
          )
        }
      }

    return Transformer
      .Builder(context)
      .setAssetLoaderFactory(assetLoaderFactory)
      .setVideoMimeType(MimeTypes.VIDEO_H264)
      .setAudioMimeType(MimeTypes.AUDIO_AAC)
      .addListener(listener)
      .build()
  }

  private fun buildEffects(
    context: Context,
    crop: ClipCrop?,
    frameWidth: Int,
    frameHeight: Int,
    subtitleCues: List<ClipSubtitleCue>?,
  ): Effects {
    val videoEffects = mutableListOf<Effect>()
    if (crop != null && crop.width > 0 && crop.height > 0 && frameWidth > 0 && frameHeight > 0) {
      // CropSelectionView and Media3 effects both use the frame after container rotation.
      val width = frameWidth.toFloat()
      val height = frameHeight.toFloat()
      val left = (-1f + 2f * crop.x / width).coerceIn(-1f, 1f)
      val right = (-1f + 2f * (crop.x + crop.width) / width).coerceIn(-1f, 1f)
      val top = (1f - 2f * crop.y / height).coerceIn(-1f, 1f)
      val bottom = (1f - 2f * (crop.y + crop.height) / height).coerceIn(-1f, 1f)
      if (right > left && top > bottom) videoEffects += Crop(left, right, bottom, top)
    }
    if (!subtitleCues.isNullOrEmpty()) {
      videoEffects += OverlayEffect(listOf(TimedClipSubtitleOverlay(context.applicationContext, subtitleCues)))
    }
    return if (videoEffects.isEmpty()) Effects.EMPTY else Effects(emptyList(), videoEffects)
  }
}

/** Draw active cues after crop, using Media3's renderer for styled text and bitmap subtitles. */
@UnstableApi
private class TimedClipSubtitleOverlay(
  context: Context,
  cues: List<ClipSubtitleCue>,
) : CanvasOverlay(true) {
  private val ordered = cues.sortedBy { it.startUs }
  private val active = mutableListOf<ClipSubtitleCue>()
  private var nextCue = 0
  private var lastTimeUs = Long.MIN_VALUE

  // This view is never attached to a window. It is created and drawn only on the GL thread.
  private val subtitleView by lazy(LazyThreadSafetyMode.NONE) {
    SubtitleView(context).apply { setViewType(SubtitleView.VIEW_TYPE_CANVAS) }
  }

  override fun onDraw(
    canvas: Canvas,
    presentationTimeUs: Long,
  ) {
    // CanvasOverlay reuses its bitmap without clearing it between frames.
    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    var changed = false
    if (presentationTimeUs < lastTimeUs) {
      active.clear()
      nextCue = 0
      changed = true
    }
    lastTimeUs = presentationTimeUs
    if (active.removeAll { presentationTimeUs >= it.endUs }) changed = true
    while (nextCue < ordered.size && ordered[nextCue].startUs <= presentationTimeUs) {
      val cue = ordered[nextCue++]
      if (presentationTimeUs < cue.endUs) {
        active += cue
        changed = true
      }
    }
    if (changed) subtitleView.setCues(active.flatMap { it.cues })
    if (active.isEmpty()) return
    if (subtitleView.width != canvas.width || subtitleView.height != canvas.height) {
      subtitleView.measure(
        View.MeasureSpec.makeMeasureSpec(canvas.width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(canvas.height, View.MeasureSpec.EXACTLY),
      )
      subtitleView.layout(0, 0, canvas.width, canvas.height)
    }
    subtitleView.draw(canvas)
  }
}
