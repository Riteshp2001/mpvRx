/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.clip

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import app.gyrolet.mpvrx.ui.utils.performAppHapticFeedback
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
import app.gyrolet.mpvrx.ui.components.AppRangeSlider as RangeSlider
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.AppComboBox
import app.gyrolet.mpvrx.presentation.components.RepeatingIconButton
import app.gyrolet.mpvrx.ui.components.IconSwitch
import app.gyrolet.mpvrx.ui.icons.Icon as AppIcon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.Panels
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.controls.components.panels.DraggablePanel
import app.gyrolet.mpvrx.ui.player.controls.components.panels.DraggablePanelHeader
import app.gyrolet.mpvrx.ui.player.controls.components.panels.PanelConnectedTile
import app.gyrolet.mpvrx.ui.theme.AppConnectedControlShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.theme.MpvrxTheme
import app.gyrolet.mpvrx.ui.theme.spacing
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val MIN_CLIP_SECONDS = 0.05

private data class ClipPanelState(
  val clipDuration: String? = null,
  val startSeconds: Float = 0f,
  val endSeconds: Float? = null,
  val durationSeconds: Float = 0f,
  val crop: ClipCrop? = null,
  val canSave: Boolean = false,
  val exporting: Boolean = false,
  val cancelling: Boolean = false,
  val progress: Int = 0,
  val cropActive: Boolean = false,
  val audioTracks: List<ClipTrackOption> = emptyList(),
  val subtitleTracks: List<ClipTrackOption> = emptyList(),
  val selectedAudioId: Int? = null,
  val selectedSubtitleId: Int? = null,
  val includeSubtitles: Boolean = false,
)

/**
 * Player-local crop surface and state owner for Clip.
 *
 * The editor is rendered by the shared player panel system. This overlay contains only UI that must
 * sit directly over the video, and outside crop mode it does not consume input.
 */
class ClipOverlayView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
  private data class ClipDraft(
    val itemId: String,
    var startSeconds: Double,
    var endSeconds: Double? = null,
    var crop: ClipCrop? = null,
    val audioTracks: List<ClipTrackOption> = emptyList(),
    val subtitleTracks: List<ClipTrackOption> = emptyList(),
    var audioId: Int? = null,
    var subtitleId: Int? = null,
    var includeSubtitles: Boolean = false,
  )

  private var panelState by mutableStateOf(ClipPanelState())

  private var draft: ClipDraft? = null
  private var cropView: CropSelectionView? = null
  private var cropControls: ComposeView? = null
  private var pausedBeforeCrop = true
  private var lastTerminalState: ClipExportState? = null
  private var bottomInset = 0
  // End of the clip while Preview is playing it; playback pauses there.
  private var previewEndSeconds: Double? = null
  // Set once playback is seen inside the clip, so the old position before the seek never counts.
  private var previewReachedClip = false

  private val pollState =
    object : Runnable {
      override fun run() {
        clearDraftIfMediaChanged()
        updateExportState()
        stopPreviewAtClipEnd()
        postDelayed(this, STATE_POLL_MS)
      }
    }

  init {
    isClickable = false
    isFocusable = false
    clipChildren = false
    clipToPadding = false

    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
      bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
      updateOverlayMargins()
      insets
    }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    removeCallbacks(pollState)
    post(pollState)
  }

  override fun onDetachedFromWindow() {
    removeCallbacks(pollState)
    if (cropView != null) exitCropMode(keepSelection = false)
    dismissClipPanel()
    draft = null
    ClipEditorUiState.clear()
    panelState = ClipPanelState()
    super.onDetachedFromWindow()
  }

  override fun onSizeChanged(
    w: Int,
    h: Int,
    oldw: Int,
    oldh: Int,
  ) {
    super.onSizeChanged(w, h, oldw, oldh)
    updateOverlayMargins()
  }

  override fun onTouchEvent(event: MotionEvent): Boolean =
    if (cropView != null) true else super.onTouchEvent(event)

  fun openClip(): Boolean = beginClip()

  @Composable
  internal fun EditorPanel(onDismissRequest: () -> Unit) {
    if (panelState.cropActive) return

    ClipEditorPanel(
      state = panelState,
      onRangeChange = ::updateClipRange,
      onStartTimeChange = ::updateClipStart,
      onEndTimeChange = ::updateClipEnd,
      onMarkStart = ::markClipStart,
      onMarkEnd = ::markClipEnd,
      onPreview = ::previewClip,
      onCrop = ::enterCropMode,
      onClearCrop = ::clearClipCrop,
      onAudioTrackChange = ::selectClipAudio,
      onSubtitleTrackChange = ::selectClipSubtitle,
      onIncludeSubtitlesChange = ::setClipIncludeSubtitles,
      onCancel = {
        if (cancelOrClose()) onDismissRequest()
      },
      onSave = ::saveOrCancelExport,
    )
  }

  private fun playerViewModel(): PlayerViewModel? {
    val owner = findViewTreeViewModelStoreOwner() ?: return null
    return ViewModelProvider(owner)[PlayerViewModel::class.java]
  }

  private fun beginClip(): Boolean {
    if (PlaybackSession.state.value.currentItem == null) {
      toast(R.string.clip_video_unavailable)
      return false
    }

    if (ClipExportManager.state.value is ClipExportState.Exporting) {
      updateExportState()
      return true
    }

    ensureDraft() ?: return false
    refreshDraftUi()
    return true
  }

  private fun ensureDraft(): ClipDraft? {
    val itemId = PlaybackSession.state.value.currentItem?.stableId ?: return null
    draft?.takeIf { it.itemId == itemId }?.let { return it }

    val duration = mediaDurationSeconds()
    var start = (currentPosition() ?: 0.0).coerceAtLeast(0.0)
    val end =
      if (duration > MIN_CLIP_SECONDS) {
        if (start >= duration - MIN_CLIP_SECONDS) {
          start = (duration - DEFAULT_CLIP_SECONDS).coerceAtLeast(0.0)
        }
        (start + DEFAULT_CLIP_SECONDS).coerceAtMost(duration)
      } else {
        start + DEFAULT_CLIP_SECONDS
      }

    val tracks = readClipTracks()
    val selectedAudio = PlaybackSession.getPropertyString("aid")?.toIntOrNull()
    return ClipDraft(
      itemId = itemId,
      startSeconds = start,
      endSeconds = end.coerceAtLeast(start + MIN_CLIP_SECONDS),
      audioTracks = tracks.filter { it.type == "audio" },
      subtitleTracks = tracks.filter { it.type == "sub" },
      audioId = selectedAudio,
      subtitleId = PlaybackSession.getPropertyString("sid")?.toIntOrNull()
        ?.takeIf { id -> tracks.any { it.type == "sub" && it.id == id } },
    ).also {
      draft = it
      refreshDraftUi()
    }
  }

  private fun readClipTracks(): List<ClipTrackOption> {
    val count = (PlaybackSession.getPropertyInt("track-list/count") ?: 0).coerceIn(0, 128)
    val viewModel = playerViewModel()
    var audioOrdinal = 0
    var subtitleOrdinal = 0
    return (0 until count).mapNotNull { index ->
      val type = PlaybackSession.getPropertyString("track-list/$index/type") ?: return@mapNotNull null
      if (type != "audio" && type != "sub") return@mapNotNull null
      val id = PlaybackSession.getPropertyInt("track-list/$index/id") ?: return@mapNotNull null
      val title = PlaybackSession.getPropertyString("track-list/$index/title").orEmpty()
      val language = PlaybackSession.getPropertyString("track-list/$index/lang").orEmpty()
      val external = PlaybackSession.getPropertyString("track-list/$index/external-filename")
        ?.takeIf { it.isNotBlank() }
      val isExternal = external != null || PlaybackSession.getPropertyBoolean("track-list/$index/external") == true
      // External files are separate inputs; never shift the embedded track's container ordinal.
      val ordinal = when {
        isExternal -> -1
        type == "audio" -> audioOrdinal++
        else -> subtitleOrdinal++
      }
      ClipTrackOption(
        id = id, type = type, ordinal = ordinal, title = title, language = language,
        externalFile = external?.let { viewModel?.clipSubtitleSource(it) ?: it },
        isExternal = isExternal,
        codec = PlaybackSession.getPropertyString("track-list/$index/codec").orEmpty().lowercase(),
        demuxId = PlaybackSession.getPropertyInt("track-list/$index/demux-id"),
      )
    }
  }

  private fun selectClipAudio(id: Int?) {
    ensureDraft()?.let { it.audioId = id; refreshDraftUi() }
  }

  private fun selectClipSubtitle(id: Int?) {
    ensureDraft()?.let {
      it.subtitleId = id
      it.includeSubtitles = id != null
      refreshDraftUi()
    }
  }

  private fun setClipIncludeSubtitles(enabled: Boolean) {
    ensureDraft()?.let {
      it.includeSubtitles = enabled && it.subtitleId != null
      refreshDraftUi()
    }
  }

  private fun updateClipRange(
    start: Float,
    end: Float,
    preview: Float,
  ) {
    val active = ensureDraft() ?: return
    val duration = mediaDurationSeconds().takeIf { it > MIN_CLIP_SECONDS }
    val maxEnd = duration?.toFloat() ?: maxOf(end, start + 0.05f)
    val safeStart = start.coerceIn(0f, (maxEnd - 0.05f).coerceAtLeast(0f))
    val safeEnd = end.coerceIn(safeStart + 0.05f, maxEnd)
    active.startSeconds = safeStart.toDouble()
    active.endSeconds = safeEnd.toDouble()
    refreshDraftUi()
    PlaybackSession.setPropertyDouble(
      "time-pos",
      preview.coerceIn(safeStart, safeEnd).toDouble(),
    )
    playerViewModel()?.autoHideControls()
  }

  private fun updateClipStart(seconds: Float) {
    val end = ensureDraft()?.endSeconds?.toFloat() ?: return
    updateClipRange(seconds, end, seconds)
  }

  private fun updateClipEnd(seconds: Float) {
    val start = ensureDraft()?.startSeconds?.toFloat() ?: return
    updateClipRange(start, seconds, seconds)
  }

  private fun markClipStart() {
    val current = currentPosition() ?: return
    val active = ensureDraft() ?: return
    active.startSeconds = current
    if ((active.endSeconds ?: Double.POSITIVE_INFINITY) <= current + MIN_CLIP_SECONDS) {
      active.endSeconds = null
    }
    refreshDraftUi()
    playerViewModel()?.autoHideControls()
  }

  private fun markClipEnd() {
    val active = ensureDraft() ?: return
    val current = currentPosition() ?: return
    if (current <= active.startSeconds + MIN_CLIP_SECONDS) {
      toast(R.string.clip_invalid_range)
      return
    }
    active.endSeconds = current
    refreshDraftUi()
    playerViewModel()?.autoHideControls()
  }

  /** Plays just the selection, from its start; [stopPreviewAtClipEnd] pauses at its end. */
  private fun previewClip() {
    val active = ensureDraft() ?: return
    val end = active.endSeconds ?: return
    PlaybackSession.setPropertyDouble("time-pos", active.startSeconds)
    PlaybackSession.setPropertyBoolean("pause", false)
    previewEndSeconds = end
    previewReachedClip = false
    playerViewModel()?.autoHideControls()
  }

  private fun stopPreviewAtClipEnd() {
    val end = previewEndSeconds ?: return
    // Pausing by hand ends the preview, so a later resume never stops on its own.
    if (PlaybackSession.getPropertyBoolean("pause") == true) {
      previewEndSeconds = null
      return
    }
    val position = currentPosition() ?: return
    if (position < end) {
      previewReachedClip = true
    } else if (previewReachedClip) {
      previewEndSeconds = null
      PlaybackSession.setPropertyBoolean("pause", true)
    }
  }

  private fun clearClipCrop() {
    val active = ensureDraft() ?: return
    active.crop = null
    refreshDraftUi()
  }

  private fun saveOrCancelExport() {
    val exportState = ClipExportManager.state.value
    if (exportState is ClipExportState.Exporting) {
      ClipExportManager.cancel()
      updateExportState()
      return
    }

    val active = draft ?: return
    val end = active.endSeconds
    val item = PlaybackSession.state.value.currentItem
    if (item == null || item.stableId != active.itemId) {
      closeDraft()
      toast(R.string.clip_video_unavailable)
      return
    }
    if (end == null || end <= active.startSeconds + MIN_CLIP_SECONDS) {
      toast(R.string.clip_invalid_range)
      return
    }

    val accepted =
      ClipExportManager.export(
        context,
        ClipRequest(
          item = item,
          startSeconds = active.startSeconds,
          endSeconds = end,
          crop = active.crop,
          audioTrack = active.audioTracks.firstOrNull { it.id == active.audioId },
          subtitleTrack = if (active.includeSubtitles) active.subtitleTracks.firstOrNull { it.id == active.subtitleId } else null,
          subtitleDelaySeconds = PlaybackSession.getPropertyDouble("sub-delay") ?: 0.0,
        ),
      )
    if (!accepted) toast(R.string.clip_export_busy)
    updateExportState()
  }

  private fun cancelOrClose(): Boolean {
    if (ClipExportManager.state.value is ClipExportState.Exporting) {
      ClipExportManager.cancel()
      updateExportState()
      return false
    } else {
      closeDraft()
      return true
    }
  }

  private fun closeDraft() {
    if (cropView != null) exitCropMode(keepSelection = false)
    draft = null
    ClipEditorUiState.clear()
    panelState = ClipPanelState()
  }

  private fun enterCropMode() {
    if (cropView != null) return
    val active = ensureDraft() ?: return
    val sourceWidth = PlaybackSession.getPropertyInt("video-params/w") ?: 0
    val sourceHeight = PlaybackSession.getPropertyInt("video-params/h") ?: 0
    if (sourceWidth <= 0 || sourceHeight <= 0) {
      toast(R.string.clip_video_unavailable)
      return
    }

    val rotation = ((PlaybackSession.getPropertyInt("video-params/rotate") ?: 0) % 360 + 360) % 360
    val outputWidth = PlaybackSession.getPropertyInt("video-out-params/dw") ?: 0
    val outputHeight = PlaybackSession.getPropertyInt("video-out-params/dh") ?: 0

    playerViewModel()?.hideControls()
    pausedBeforeCrop = PlaybackSession.getPropertyBoolean("pause") ?: true
    if (!pausedBeforeCrop) PlaybackSession.setPropertyBoolean("pause", true)

    panelState = panelState.copy(cropActive = true)
    val selector =
      CropSelectionView(
        context = context,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        displayWidth = outputWidth,
        displayHeight = outputHeight,
        rotation = rotation,
        initialCrop = active.crop,
        onSelectionChanged = ::positionCropControls,
      )
    cropView = selector
    addView(selector, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    val controls = buildCropControls(selector)
    cropControls = controls
    addView(
      controls,
      LayoutParams(
        dp(CROP_PILL_WIDTH_DP),
        dp(CROP_PILL_HEIGHT_DP),
        Gravity.START or Gravity.TOP,
      ),
    )
    controls.post { positionCropControls(selector.selectionBounds()) }
  }

  private fun buildCropControls(selector: CropSelectionView): ComposeView =
    ComposeView(context).apply {
      isClickable = true
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
      setContent {
        MpvrxTheme {
          ClipCropControls(
            onCancel = { exitCropMode(keepSelection = false) },
            onDone = {
              draft?.crop = selector.currentCrop()
              exitCropMode(keepSelection = true)
            },
          )
        }
      }
    }

  private fun exitCropMode(keepSelection: Boolean) {
    val selector = cropView ?: return
    if (!keepSelection) {
      // Cancelling crop intentionally keeps the previous crop selection, if one existed.
    }
    removeView(selector)
    cropView = null
    cropControls?.let { controls ->
      controls.disposeComposition()
      removeView(controls)
    }
    cropControls = null
    if (!pausedBeforeCrop) PlaybackSession.setPropertyBoolean("pause", false)
    panelState = panelState.copy(cropActive = false)
    refreshDraftUi()
  }

  private fun updateExportState() {
    when (val state = ClipExportManager.state.value) {
      ClipExportState.Idle -> {
        panelState =
          panelState.copy(
            exporting = false,
            cancelling = false,
            progress = 0,
          )
        lastTerminalState = null
      }
      is ClipExportState.Exporting -> {
        panelState =
          panelState.copy(
            exporting = true,
            cancelling = state.cancelling,
            progress = (state.progress * 100f).roundToInt().coerceIn(0, 100),
          )
      }
      is ClipExportState.Success -> {
        if (lastTerminalState !== state) {
          toast(context.getString(R.string.clip_saved, state.displayName))
          lastTerminalState = state
          draft = null
          ClipEditorUiState.clear()
          panelState = ClipPanelState(progress = 100)
          dismissClipPanel()
          ClipExportManager.consumeTerminalState()
        }
      }
      is ClipExportState.Error -> {
        if (lastTerminalState !== state) {
          toast(context.getString(R.string.clip_export_failed, state.message))
          lastTerminalState = state
          ClipExportManager.consumeTerminalState()
          panelState = panelState.copy(exporting = false, cancelling = false)
          refreshDraftUi()
        }
      }
    }
  }

  private fun clearDraftIfMediaChanged() {
    val active = draft ?: return
    if (ClipExportManager.state.value is ClipExportState.Exporting) return
    if (PlaybackSession.state.value.currentItem?.stableId == active.itemId) return

    draft = null
    if (cropView != null) exitCropMode(keepSelection = false)
    ClipEditorUiState.clear()
    panelState = ClipPanelState()
    dismissClipPanel()
  }

  private fun dismissClipPanel() {
    val viewModel = playerViewModel() ?: return
    if (viewModel.panelShown.value != Panels.Clip) return
    viewModel.panelShown.value = Panels.None
    viewModel.showControls()
  }

  private fun refreshDraftUi() {
    // Any edit to the clip ends a running preview instead of pausing at a stale end point.
    previewEndSeconds = null
    val active = draft
    if (active == null) {
      ClipEditorUiState.clear()
      panelState =
        panelState.copy(
          clipDuration = null,
          startSeconds = 0f,
          endSeconds = null,
          durationSeconds = 0f,
          crop = null,
          audioTracks = emptyList(),
          subtitleTracks = emptyList(),
          selectedAudioId = null,
          selectedSubtitleId = null,
          includeSubtitles = false,
          canSave = false,
        )
      return
    }

    val duration = mediaDurationSeconds().toFloat().coerceAtLeast(0f)
    ClipEditorUiState.publish(active.startSeconds, active.endSeconds)
    panelState =
      panelState.copy(
        clipDuration = active.endSeconds?.let { formatTime((it - active.startSeconds).coerceAtLeast(0.0)) },
        startSeconds = active.startSeconds.toFloat(),
        endSeconds = active.endSeconds?.toFloat(),
        durationSeconds = duration,
        crop = active.crop,
        audioTracks = active.audioTracks,
        subtitleTracks = active.subtitleTracks,
        selectedAudioId = active.audioId,
        selectedSubtitleId = active.subtitleId,
        includeSubtitles = active.includeSubtitles,
        canSave =
          active.endSeconds?.let { it > active.startSeconds + MIN_CLIP_SECONDS } == true &&
            ClipExportManager.state.value !is ClipExportState.Exporting,
      )
  }

  private fun updateOverlayMargins() {
    cropView?.selectionBounds()?.let(::positionCropControls)
  }

  private fun positionCropControls(bounds: RectF) {
    val controls = cropControls ?: return
    val pillWidth = dp(CROP_PILL_WIDTH_DP)
    val pillHeight = dp(CROP_PILL_HEIGHT_DP)
    val inset = dp(10).toFloat()
    val handleClearance = dp(38).toFloat()
    val edgeInset = dp(8).toFloat()

    val desiredX =
      if (bounds.width() >= pillWidth + handleClearance + inset) {
        bounds.right - pillWidth - handleClearance
      } else {
        bounds.centerX() - pillWidth / 2f
      }
    val desiredY =
      if (bounds.height() >= pillHeight + handleClearance + inset) {
        bounds.bottom - pillHeight - handleClearance
      } else {
        bounds.centerY() - pillHeight / 2f
      }
    val maxX = (width - pillWidth).toFloat().minus(edgeInset).coerceAtLeast(edgeInset)
    val maxY =
      (height - bottomInset - pillHeight).toFloat().minus(edgeInset).coerceAtLeast(edgeInset)
    controls.x = desiredX.coerceIn(edgeInset, maxX)
    controls.y = desiredY.coerceIn(edgeInset, maxY)
  }

  private fun mediaDurationSeconds(): Double =
    PlaybackSession.getPropertyDouble("duration")
      ?: 0.0

  private fun currentPosition(): Double? = PlaybackSession.getPropertyDouble("time-pos")

  private fun formatTime(seconds: Double): String {
    val totalTenths = (seconds.coerceAtLeast(0.0) * 10.0).roundToInt()
    val hours = totalTenths / 36_000
    val minutes = (totalTenths / 600) % 60
    val secs = (totalTenths / 10) % 60
    val tenths = totalTenths % 10
    return if (hours > 0) {
      "%d:%02d:%02d.%d".format(Locale.US, hours, minutes, secs, tenths)
    } else {
      "%02d:%02d.%d".format(Locale.US, minutes, secs, tenths)
    }
  }

  private fun toast(message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
  }

  private fun toast(messageRes: Int) {
    Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

  companion object {
    private const val OVERLAY_TAG = "mpvrx_clip_overlay"
    private const val STATE_POLL_MS = 250L
    private const val DEFAULT_CLIP_SECONDS = 10.0
    private const val CROP_PILL_WIDTH_DP = 97
    private const val CROP_PILL_HEIGHT_DP = 48

    /** Attaches the Clip editor to the player-owned overlay layer. */
    internal fun ensureAttached(activity: PlayerActivity): ClipOverlayView {
      val overlayHost = activity.findViewById<FrameLayout>(R.id.clip_overlay_host)
      overlayHost.bringToFront()
      overlayHost.findViewWithTag<ClipOverlayView>(OVERLAY_TAG)?.let { return it }

      return ClipOverlayView(activity).also { overlay ->
        overlay.tag = OVERLAY_TAG
        overlayHost.addView(
          overlay,
          ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
          ),
        )
      }
    }
  }
}

@Composable
private fun ClipEditorPanel(
  state: ClipPanelState,
  onRangeChange: (Float, Float, Float) -> Unit,
  onStartTimeChange: (Float) -> Unit,
  onEndTimeChange: (Float) -> Unit,
  onMarkStart: () -> Unit,
  onMarkEnd: () -> Unit,
  onPreview: () -> Unit,
  onCrop: () -> Unit,
  onClearCrop: () -> Unit,
  onAudioTrackChange: (Int?) -> Unit,
  onSubtitleTrackChange: (Int?) -> Unit,
  onIncludeSubtitlesChange: (Boolean) -> Unit,
  onCancel: () -> Unit,
  onSave: () -> Unit,
) {
  var startTimeValid by remember { mutableStateOf(true) }
  var endTimeValid by remember { mutableStateOf(true) }
  BackHandler(onBack = onCancel)

  DraggablePanel(
    header = {
      DraggablePanelHeader(
        title = stringResource(R.string.clip_action),
        onDismissRequest = onCancel,
        leadingContent = {
          AppIcon(
            imageVector = Icons.RoundedFilled.ContentCut,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
          )
        },
      )
    },
  ) {
    val editable = !state.exporting
    val end = state.endSeconds
    val duration = state.durationSeconds
    val maxTime = duration.takeIf { it > MIN_CLIP_SECONDS.toFloat() } ?: Float.MAX_VALUE
    val maxStart = ((end ?: maxTime) - MIN_CLIP_SECONDS.toFloat()).coerceAtLeast(0f)
    val minEnd = (state.startSeconds + MIN_CLIP_SECONDS.toFloat()).coerceAtMost(maxTime)
    val showSlider = end != null && duration > MIN_CLIP_SECONDS.toFloat()
    val startRow = if (showSlider) 1 else 0
    val rangeRows = startRow + 3
    val hasAudio = state.audioTracks.isNotEmpty()
    val hasSubtitles = state.subtitleTracks.isNotEmpty()
    val subtitleRow = if (hasAudio) 2 else 1
    val outputRows = 1 + (if (hasAudio) 1 else 0) + (if (hasSubtitles) 2 else 0)

    Column(
      modifier = Modifier.padding(MaterialTheme.spacing.medium),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
      ClipSummaryCard(
        length = state.clipDuration,
        start = state.startSeconds,
        end = end,
        canPreview = editable && end != null,
        onPreview = onPreview,
      )

      ClipSection(title = stringResource(R.string.clip_section_range)) {
        if (showSlider && end != null) {
          PanelConnectedTile(index = 0, itemCount = rangeRows) {
            val start = state.startSeconds.coerceIn(0f, duration)
            val safeEnd = end.coerceIn(start + MIN_CLIP_SECONDS.toFloat(), duration)
            val rangeDescription = stringResource(R.string.clip_options)
            RangeSlider(
              value = start..safeEnd,
              onValueChange = { range ->
                // Preview whichever handle moved, so the frame under the finger is the one shown.
                val startDelta = abs(range.start - start)
                val endDelta = abs(range.endInclusive - safeEnd)
                val preview = if (startDelta >= endDelta) range.start else range.endInclusive
                onRangeChange(range.start, range.endInclusive, preview)
              },
              valueRange = 0f..duration,
              enabled = editable,
              modifier =
                Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp, vertical = 8.dp)
                  .semantics { contentDescription = rangeDescription },
            )
          }
        }
        PanelConnectedTile(index = startRow, itemCount = rangeRows) {
          ClipPointRow(
            label = stringResource(R.string.clip_start_short),
            seconds = state.startSeconds,
            minSeconds = 0f,
            maxSeconds = maxStart,
            enabled = editable,
            onCommit = onStartTimeChange,
            onValidityChange = { startTimeValid = it },
            markIcon = Icons.RoundedFilled.SkipPrevious,
            markDescription = stringResource(R.string.clip_set_start_here),
            onMark = onMarkStart,
          )
        }
        PanelConnectedTile(index = startRow + 1, itemCount = rangeRows) {
          ClipPointRow(
            label = stringResource(R.string.clip_end_short),
            seconds = end,
            minSeconds = minEnd,
            maxSeconds = maxTime,
            enabled = editable,
            onCommit = onEndTimeChange,
            onValidityChange = { endTimeValid = it },
            markIcon = Icons.RoundedFilled.SkipNext,
            markDescription = stringResource(R.string.clip_set_end_here),
            onMark = onMarkEnd,
          )
        }
        PanelConnectedTile(index = startRow + 2, itemCount = rangeRows) {
          ClipLengthPresets(
            currentLength = end?.let { it - state.startSeconds },
            enabled = editable,
            onSelect = { length ->
              // Keep the requested length near the end of the video by moving the start back.
              var newStart = state.startSeconds
              var newEnd = newStart + length
              if (newEnd > maxTime) {
                newEnd = maxTime
                newStart = (maxTime - length).coerceAtLeast(0f)
              }
              onRangeChange(newStart, newEnd, newStart)
            },
          )
        }
      }

      ClipSection(title = stringResource(R.string.clip_section_output)) {
        PanelConnectedTile(index = 0, itemCount = outputRows) {
          ClipCropRow(
            crop = state.crop,
            enabled = editable,
            onCrop = onCrop,
            onClearCrop = onClearCrop,
          )
        }
        if (hasAudio) {
          PanelConnectedTile(index = 1, itemCount = outputRows) {
            ClipTrackPicker(
              title = stringResource(R.string.clip_audio_track),
              icon = Icons.RoundedFilled.Audiotrack,
              tracks = state.audioTracks,
              selectedId = state.selectedAudioId,
              onSelect = onAudioTrackChange,
              enabled = editable,
            )
          }
        }
        if (hasSubtitles) {
          PanelConnectedTile(index = subtitleRow, itemCount = outputRows) {
            ClipTrackPicker(
              title = stringResource(R.string.clip_subtitle_track),
              icon = Icons.RoundedFilled.Subtitles,
              tracks = state.subtitleTracks,
              selectedId = state.selectedSubtitleId,
              onSelect = onSubtitleTrackChange,
              enabled = editable,
            )
          }
          PanelConnectedTile(index = subtitleRow + 1, itemCount = outputRows) {
            ClipSwitchRow(
              title = stringResource(R.string.clip_include_subtitles),
              checked = state.includeSubtitles,
              enabled = editable && state.selectedSubtitleId != null,
              onCheckedChange = onIncludeSubtitlesChange,
            )
          }
        }
      }

      if (state.exporting) {
        Column(verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing)) {
          PanelConnectedTile(index = 0, itemCount = 2) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              LinearProgressIndicator(
                progress = { state.progress / 100f },
                modifier = Modifier.fillMaxWidth(),
              )
              Text(
                text =
                  if (state.cancelling) {
                    stringResource(R.string.clip_cancelling)
                  } else {
                    stringResource(R.string.clip_exporting, state.progress)
                  },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          // While exporting, Save is the cancel action of ClipOverlayView.
          FilledTonalButton(
            onClick = onSave,
            enabled = !state.cancelling,
            shape = rememberConnectedShape(1, 2, ConnectedLayout.Vertical),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
          ) {
            AppIcon(Icons.RoundedFilled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.clip_cancel))
          }
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(AppConnectedControlShapeTokens.spacing),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          FilledTonalButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            shape = rememberConnectedShape(0, 2, ConnectedLayout.Horizontal, tokens = AppConnectedControlShapeTokens),
          ) {
            Text(stringResource(R.string.clip_cancel), maxLines = 1)
          }
          Button(
            onClick = onSave,
            enabled = state.canSave && startTimeValid && endTimeValid,
            modifier = Modifier.weight(1.4f).heightIn(min = 52.dp),
            shape = rememberConnectedShape(1, 2, ConnectedLayout.Horizontal, tokens = AppConnectedControlShapeTokens),
          ) {
            AppIcon(Icons.RoundedFilled.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.clip_save), maxLines = 1)
          }
        }
      }
    }
  }
}

/** Clip length at a glance, the selected span, and a button that plays the clip from its start. */
@Composable
private fun ClipSummaryCard(
  length: String?,
  start: Float,
  end: Float?,
  canPreview: Boolean,
  onPreview: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      AppIcon(Icons.RoundedFilled.Timer, contentDescription = null, modifier = Modifier.size(28.dp))
      Spacer(Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.clip_length),
          style = MaterialTheme.typography.labelMedium,
          color = LocalContentColor.current.copy(alpha = 0.8f),
        )
        Text(
          text = length ?: "--:--",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
        )
        Text(
          text = formatClipPoint(start) + " – " + (end?.let(::formatClipPoint) ?: "--:--"),
          style = MaterialTheme.typography.bodySmall,
          color = LocalContentColor.current.copy(alpha = 0.8f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      FilledIconButton(
        onClick = onPreview,
        enabled = canPreview,
        modifier = Modifier.size(48.dp),
      ) {
        AppIcon(
          Icons.RoundedFilled.PlayArrow,
          contentDescription = stringResource(R.string.clip_preview),
          modifier = Modifier.size(24.dp),
        )
      }
    }
  }
}

@Composable
private fun ClipSection(
  title: String,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing)) {
    Text(
      text = title,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
    content()
  }
}

/**
 * A clip point: its time field, fine steps of [CLIP_NUDGE_SECONDS] that repeat while held, and a
 * button that moves the point to the current playback position.
 */
@Composable
private fun ClipPointRow(
  label: String,
  seconds: Float?,
  minSeconds: Float,
  maxSeconds: Float,
  enabled: Boolean,
  onCommit: (Float) -> Unit,
  onValidityChange: (Boolean) -> Unit,
  markIcon: app.gyrolet.mpvrx.ui.icons.AppIcon,
  markDescription: String,
  onMark: () -> Unit,
) {
  fun nudge(delta: Float) {
    val current = seconds ?: return
    onCommit((current + delta).coerceIn(minSeconds, maxSeconds))
  }

  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    ClipTimeField(
      label = label,
      seconds = seconds,
      minSeconds = minSeconds,
      maxSeconds = maxSeconds,
      enabled = enabled,
      onCommit = onCommit,
      onValidityChange = onValidityChange,
      modifier = Modifier.weight(1f),
    )
    RepeatingIconButton(
      onClick = { nudge(-CLIP_NUDGE_SECONDS) },
      enabled = enabled && seconds != null,
      modifier = Modifier.size(40.dp),
    ) {
      AppIcon(
        Icons.RoundedFilled.Remove,
        contentDescription = stringResource(R.string.clip_nudge_back),
        modifier = Modifier.size(18.dp),
      )
    }
    RepeatingIconButton(
      onClick = { nudge(CLIP_NUDGE_SECONDS) },
      enabled = enabled && seconds != null,
      modifier = Modifier.size(40.dp),
    ) {
      AppIcon(
        Icons.RoundedFilled.Add,
        contentDescription = stringResource(R.string.clip_nudge_forward),
        modifier = Modifier.size(18.dp),
      )
    }
    FilledTonalIconButton(
      onClick = onMark,
      enabled = enabled,
      modifier = Modifier.size(40.dp),
    ) {
      AppIcon(markIcon, contentDescription = markDescription, modifier = Modifier.size(18.dp))
    }
  }
}

/** One-tap clip lengths from the current start; the active length is highlighted. */
@Composable
private fun ClipLengthPresets(
  currentLength: Float?,
  enabled: Boolean,
  onSelect: (Float) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = stringResource(R.string.clip_quick_length),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(AppConnectedControlShapeTokens.spacing),
    ) {
      ClipLengthPresetSeconds.forEachIndexed { index, length ->
        val selected = currentLength != null && abs(currentLength - length) < 0.05f
        Button(
          onClick = { onSelect(length.toFloat()) },
          enabled = enabled,
          colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
          shape =
            rememberConnectedShape(
              index,
              ClipLengthPresetSeconds.size,
              ConnectedLayout.Horizontal,
              tokens = AppConnectedControlShapeTokens,
            ),
          contentPadding = PaddingValues(horizontal = 4.dp),
          modifier = Modifier.weight(1f).heightIn(min = 40.dp),
        ) {
          Text(
            text = stringResource(R.string.clip_length_seconds, length),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
          )
        }
      }
    }
  }
}

/** Crop summary; opens the crop editor, and resets to the full frame when a crop is set. */
@Composable
private fun ClipCropRow(
  crop: ClipCrop?,
  enabled: Boolean,
  onCrop: () -> Unit,
  onClearCrop: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(enabled = enabled, role = Role.Button, onClick = onCrop)
        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.secondaryContainer,
      contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
      Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        AppIcon(Icons.RoundedFilled.AspectRatio, contentDescription = null, modifier = Modifier.size(22.dp))
      }
    }
    Spacer(Modifier.width(12.dp))
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text =
          crop?.let { stringResource(R.string.clip_crop_size, it.width, it.height) }
            ?: stringResource(R.string.clip_crop_full_frame),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = stringResource(R.string.clip_select_crop),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (crop != null) {
      IconButton(onClick = onClearCrop, enabled = enabled) {
        AppIcon(
          Icons.RoundedFilled.Close,
          contentDescription = stringResource(R.string.clip_crop_reset),
          modifier = Modifier.size(20.dp),
        )
      }
    }
    AppIcon(
      Icons.RoundedFilled.ChevronRight,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(end = 8.dp).size(24.dp),
    )
  }
}

/** Track choice as the shared combobox; the first option keeps mpv's automatic choice. */
@Composable
private fun ClipTrackPicker(
  title: String,
  icon: app.gyrolet.mpvrx.ui.icons.AppIcon,
  tracks: List<ClipTrackOption>,
  selectedId: Int?,
  onSelect: (Int?) -> Unit,
  enabled: Boolean,
) {
  val automatic = stringResource(R.string.clip_track_auto_off)
  val options = remember(tracks) { listOf<ClipTrackOption?>(null) + tracks }
  val selected = tracks.firstOrNull { it.id == selectedId }
  AppComboBox(
    options = options,
    selected = selected,
    onSelect = { onSelect(it?.id) },
    optionLabel = { it?.displayLabel ?: automatic },
    label = title,
    valueText = selected?.displayLabel ?: automatic,
    enabled = enabled,
    leadingIcon = { AppIcon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
  )
}

@Composable
private fun ClipSwitchRow(
  title: String,
  checked: Boolean,
  enabled: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        .heightIn(min = 56.dp)
        .padding(start = 16.dp, end = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.bodyLarge,
      color =
        if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
      modifier = Modifier.weight(1f),
    )
    IconSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
  }
}

private const val CLIP_NUDGE_SECONDS = 0.1f
private val ClipLengthPresetSeconds = listOf(5, 10, 15, 30, 60)

private fun formatClipPoint(seconds: Float): String {
  val totalTenths = (seconds.coerceAtLeast(0f) * 10f).roundToInt()
  val hours = totalTenths / 36_000
  val minutes = (totalTenths / 600) % 60
  val secs = (totalTenths / 10) % 60
  val tenths = totalTenths % 10
  return if (hours > 0) {
    "%d:%02d:%02d.%d".format(Locale.US, hours, minutes, secs, tenths)
  } else {
    "%02d:%02d.%d".format(Locale.US, minutes, secs, tenths)
  }
}

@Composable
private fun ClipTimeField(
  label: String,
  seconds: Float?,
  minSeconds: Float,
  maxSeconds: Float,
  enabled: Boolean,
  onCommit: (Float) -> Unit,
  onValidityChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  var text by remember { mutableStateOf(seconds?.let(::formatEditableTime).orEmpty()) }
  var focused by remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current

  LaunchedEffect(seconds, minSeconds, maxSeconds, focused) {
    if (!focused) {
      text = seconds?.let(::formatEditableTime).orEmpty()
      onValidityChange(seconds?.let { it in minSeconds..maxSeconds } == true)
    }
  }

  fun commit() {
    val parsed = parseClipTime(text)?.takeIf { it in minSeconds..maxSeconds }
    if (parsed != null) onCommit(parsed)
    text = parsed?.let(::formatEditableTime) ?: seconds?.let(::formatEditableTime).orEmpty()
    onValidityChange(parsed != null || seconds?.let { it in minSeconds..maxSeconds } == true)
  }

  OutlinedTextField(
    value = text,
    onValueChange = { candidate ->
      if (candidate.length <= 12 && candidate.all { it.isDigit() || it == ':' || it == '.' }) {
        text = candidate
        val parsed = parseClipTime(candidate)?.takeIf { it in minSeconds..maxSeconds }
        onValidityChange(parsed != null)
        parsed?.let(onCommit)
      }
    },
    enabled = enabled,
    label = { Text(label) },
    placeholder = { Text("00:00.000") },
    textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
    singleLine = true,
    isError =
      text.isNotBlank() &&
        parseClipTime(text)?.let { it in minSeconds..maxSeconds } != true,
    keyboardOptions =
      KeyboardOptions(
        keyboardType = KeyboardType.Ascii,
        imeAction = ImeAction.Done,
      ),
    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
    modifier =
      modifier.onFocusChanged { state ->
        val lostFocus = focused && !state.isFocused
        focused = state.isFocused
        if (lostFocus) commit()
      },
  )
}

private fun parseClipTime(value: String): Float? {
  val parts = value.trim().split(':')
  if (parts.size !in 1..3 || parts.any(String::isBlank)) return null
  val numbers =
    parts.mapIndexed { index, part ->
      if (index == parts.lastIndex) {
        part.toDoubleOrNull() ?: return null
      } else {
        part.toLongOrNull()?.toDouble() ?: return null
      }
    }
  if (numbers.any { it < 0.0 || !it.isFinite() }) return null

  val seconds =
    when (numbers.size) {
      1 -> numbers[0]
      2 -> {
        if (numbers[1] >= 60.0) return null
        numbers[0] * 60.0 + numbers[1]
      }
      else -> {
        if (numbers[1] >= 60.0 || numbers[2] >= 60.0) return null
        numbers[0] * 3600.0 + numbers[1] * 60.0 + numbers[2]
      }
    }
  return seconds.takeIf { it <= Float.MAX_VALUE }?.toFloat()
}

private fun formatEditableTime(seconds: Float): String {
  val totalMillis = (seconds.coerceAtLeast(0f) * 1000f).roundToLong()
  val hours = totalMillis / 3_600_000L
  val minutes = (totalMillis / 60_000L) % 60L
  val wholeSeconds = (totalMillis / 1000L) % 60L
  val millis = totalMillis % 1000L
  return if (hours > 0) {
    "%d:%02d:%02d.%03d".format(Locale.US, hours, minutes, wholeSeconds, millis)
  } else {
    "%02d:%02d.%03d".format(Locale.US, minutes, wholeSeconds, millis)
  }
}

@Composable
private fun ClipCropControls(
  onCancel: () -> Unit,
  onDone: () -> Unit,
) {
  BackHandler(onBack = onCancel)

  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = 6.dp,
    shadowElevation = 8.dp,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
  ) {
    Row(
      modifier = Modifier.height(48.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(
        onClick = onCancel,
        modifier = Modifier.size(48.dp),
      ) {
        AppIcon(
          imageVector = Icons.RoundedFilled.Close,
          contentDescription = stringResource(R.string.clip_cancel),
          modifier = Modifier.size(21.dp),
        )
      }
      Box(
        Modifier
          .width(1.dp)
          .height(24.dp)
          .background(MaterialTheme.colorScheme.outlineVariant),
      )
      IconButton(
        onClick = onDone,
        modifier = Modifier.size(48.dp),
      ) {
        AppIcon(
          imageVector = Icons.RoundedFilled.Check,
          contentDescription = stringResource(R.string.clip_done),
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(22.dp),
        )
      }
    }
  }
}

private class CropSelectionView(
  context: Context,
  private val sourceWidth: Int,
  private val sourceHeight: Int,
  displayWidth: Int,
  displayHeight: Int,
  private val rotation: Int,
  private val initialCrop: ClipCrop?,
  private val onSelectionChanged: (RectF) -> Unit,
) : View(context) {
  private enum class DragMode {
    NONE,
    MOVE,
    LEFT,
    TOP,
    RIGHT,
    BOTTOM,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
  }

  private val density = resources.displayMetrics.density
  private val videoBounds = RectF()
  private val selection = RectF()
  private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
  private val borderPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      style = Paint.Style.STROKE
      strokeWidth = dpF(2f)
    }
  private val cornerHandlePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      style = Paint.Style.STROKE
      strokeWidth = dpF(4f)
      strokeCap = Paint.Cap.ROUND
      strokeJoin = Paint.Join.ROUND
    }
  private val edgeHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
  private val gridPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = 0x99FFFFFF.toInt()
      strokeWidth = dpF(1f)
    }
  private val labelPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = spF(12f)
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
  private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDD111111.toInt() }
  private val contentAspectWidth: Int
  private val contentAspectHeight: Int
  private val orientedWidth: Int
  private val orientedHeight: Int

  private var dragMode = DragMode.NONE
  private var activePointerId = MotionEvent.INVALID_POINTER_ID
  private var lastX = 0f
  private var lastY = 0f
  private var selectionInitialized = false

  init {
    val quarterTurns = ((rotation % 360 + 360) % 360) == 90 || ((rotation % 360 + 360) % 360) == 270
    orientedWidth = if (quarterTurns) sourceHeight else sourceWidth
    orientedHeight = if (quarterTurns) sourceWidth else sourceHeight
    contentAspectWidth = displayWidth.takeIf { it > 0 } ?: orientedWidth
    contentAspectHeight = displayHeight.takeIf { it > 0 } ?: orientedHeight
    isClickable = true
    contentDescription = resources.getString(R.string.clip_select_crop)
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
  }

  override fun onSizeChanged(
    w: Int,
    h: Int,
    oldw: Int,
    oldh: Int,
  ) {
    super.onSizeChanged(w, h, oldw, oldh)
    val normalizedSelection =
      if (selectionInitialized && !videoBounds.isEmpty) {
        floatArrayOf(
          (selection.left - videoBounds.left) / videoBounds.width(),
          (selection.top - videoBounds.top) / videoBounds.height(),
          (selection.right - videoBounds.left) / videoBounds.width(),
          (selection.bottom - videoBounds.top) / videoBounds.height(),
        )
      } else {
        null
      }
    updateVideoBounds(w.toFloat(), h.toFloat())
    if (normalizedSelection != null && !videoBounds.isEmpty) {
      selection.set(
        videoBounds.left + videoBounds.width() * normalizedSelection[0],
        videoBounds.top + videoBounds.height() * normalizedSelection[1],
        videoBounds.left + videoBounds.width() * normalizedSelection[2],
        videoBounds.top + videoBounds.height() * normalizedSelection[3],
      )
      onSelectionChanged(RectF(selection))
    } else {
      initializeSelectionIfNeeded()
    }
    invalidate()
  }

  private fun updateVideoBounds(
    availableWidth: Float,
    availableHeight: Float,
  ) {
    if (availableWidth <= 0f || availableHeight <= 0f) return
    val aspect = contentAspectWidth.toFloat() / contentAspectHeight.toFloat().coerceAtLeast(1f)
    val viewAspect = availableWidth / availableHeight
    if (viewAspect > aspect) {
      val videoWidth = availableHeight * aspect
      val left = (availableWidth - videoWidth) / 2f
      videoBounds.set(left, 0f, left + videoWidth, availableHeight)
    } else {
      val videoHeight = availableWidth / aspect
      val top = (availableHeight - videoHeight) / 2f
      videoBounds.set(0f, top, availableWidth, top + videoHeight)
    }
  }

  private fun initializeSelectionIfNeeded() {
    if (selectionInitialized || videoBounds.isEmpty) return
    val existing = initialCrop?.takeIf { it.rotation == rotation && it.width > 0 && it.height > 0 }
    if (existing != null) {
      val left = videoBounds.left + videoBounds.width() * existing.x / orientedWidth.toFloat()
      val top = videoBounds.top + videoBounds.height() * existing.y / orientedHeight.toFloat()
      val right = videoBounds.left + videoBounds.width() * (existing.x + existing.width) / orientedWidth.toFloat()
      val bottom = videoBounds.top + videoBounds.height() * (existing.y + existing.height) / orientedHeight.toFloat()
      selection.set(left, top, right, bottom)
    } else {
      val horizontalInset = videoBounds.width() * 0.08f
      val verticalInset = videoBounds.height() * 0.08f
      selection.set(
        videoBounds.left + horizontalInset,
        videoBounds.top + verticalInset,
        videoBounds.right - horizontalInset,
        videoBounds.bottom - verticalInset,
      )
    }
    selectionInitialized = true
    onSelectionChanged(RectF(selection))
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    initializeSelectionIfNeeded()
    if (selection.isEmpty) return

    val outside =
      Path().apply {
        fillType = Path.FillType.EVEN_ODD
        addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        addRoundRect(selection, dpF(4f), dpF(4f), Path.Direction.CW)
      }
    canvas.drawPath(outside, dimPaint)
    for (step in 1..2) {
      val x = selection.left + selection.width() * step / 3f
      val y = selection.top + selection.height() * step / 3f
      canvas.drawLine(x, selection.top, x, selection.bottom, gridPaint)
      canvas.drawLine(selection.left, y, selection.right, y, gridPaint)
    }
    canvas.drawRoundRect(selection, dpF(4f), dpF(4f), borderPaint)
    drawResizeHandles(canvas)

    val crop = currentCrop()
    val text = "${crop.width} × ${crop.height} px"
    val maxLabelWidth = (width.toFloat() - dpF(8f)).coerceAtLeast(1f)
    val horizontalPadding = min(dpF(10f), maxLabelWidth * 0.15f)
    val maxTextWidth = (maxLabelWidth - horizontalPadding * 2f).coerceAtLeast(1f)
    labelPaint.textSize = spF(12f)
    val naturalTextWidth = labelPaint.measureText(text).coerceAtLeast(1f)
    labelPaint.textSize *= (maxTextWidth / naturalTextWidth).coerceAtMost(1f)
    val textWidth = labelPaint.measureText(text)
    val textHeight = labelPaint.fontMetrics.run { bottom - top }
    val verticalPadding = dpF(6f)
    val labelWidth = textWidth + horizontalPadding * 2f
    val labelHeight = textHeight + verticalPadding * 2f
    val preferredTop = selection.top + dpF(10f)
    val maxLabelTop = (height.toFloat() - labelHeight).coerceAtLeast(0f)
    val maxInsideTop = (selection.bottom - labelHeight - dpF(8f)).coerceAtLeast(selection.top)
    val labelTop = preferredTop.coerceAtMost(maxInsideTop).coerceIn(0f, maxLabelTop)
    val maxLabelLeft = (width.toFloat() - labelWidth).coerceAtLeast(0f)
    val labelLeft = (selection.centerX() - labelWidth / 2f).coerceIn(0f, maxLabelLeft)
    val labelRect = RectF(labelLeft, labelTop, labelLeft + labelWidth, labelTop + labelHeight)
    canvas.drawRoundRect(labelRect, dpF(12f), dpF(12f), labelBackgroundPaint)
    val baseline = labelRect.top + verticalPadding - labelPaint.fontMetrics.top
    canvas.drawText(text, labelRect.left + horizontalPadding, baseline, labelPaint)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (!isEnabled) return false
    initializeSelectionIfNeeded()
    if (selection.isEmpty) return false
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        dragMode = hitTest(event.x, event.y)
        if (dragMode == DragMode.NONE) return false
        activePointerId = event.getPointerId(0)
        performAppHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        lastX = event.x
        lastY = event.y
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidate()
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (dragMode == DragMode.NONE) return false
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) return false
        val pointerX = event.getX(pointerIndex)
        val pointerY = event.getY(pointerIndex)
        val dx = pointerX - lastX
        val dy = pointerY - lastY
        updateSelection(dx, dy)
        lastX = pointerX
        lastY = pointerY
        invalidate()
        return true
      }
      MotionEvent.ACTION_POINTER_UP -> {
        val releasedIndex = event.actionIndex
        if (event.getPointerId(releasedIndex) == activePointerId) {
          val replacementIndex = if (releasedIndex == 0) 1 else 0
          if (replacementIndex < event.pointerCount) {
            activePointerId = event.getPointerId(replacementIndex)
            lastX = event.getX(replacementIndex)
            lastY = event.getY(replacementIndex)
          }
        }
        return true
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        if (dragMode != DragMode.NONE) {
          dragMode = DragMode.NONE
          activePointerId = MotionEvent.INVALID_POINTER_ID
          parent?.requestDisallowInterceptTouchEvent(false)
          invalidate()
          performClick()
          return true
        }
      }
    }
    return super.onTouchEvent(event)
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }

  fun currentCrop(): ClipCrop {
    val normalizedLeft = ((selection.left - videoBounds.left) / videoBounds.width()).coerceIn(0f, 1f)
    val normalizedTop = ((selection.top - videoBounds.top) / videoBounds.height()).coerceIn(0f, 1f)
    val normalizedRight = ((selection.right - videoBounds.left) / videoBounds.width()).coerceIn(0f, 1f)
    val normalizedBottom = ((selection.bottom - videoBounds.top) / videoBounds.height()).coerceIn(0f, 1f)

    var x = floor(normalizedLeft * orientedWidth).toInt().coerceIn(0, (orientedWidth - 2).coerceAtLeast(0))
    var y = floor(normalizedTop * orientedHeight).toInt().coerceIn(0, (orientedHeight - 2).coerceAtLeast(0))
    var right = ceil(normalizedRight * orientedWidth).toInt().coerceIn(x + 1, orientedWidth)
    var bottom = ceil(normalizedBottom * orientedHeight).toInt().coerceIn(y + 1, orientedHeight)

    x = evenFloor(x)
    y = evenFloor(y)
    right = evenFloor(right).coerceAtLeast(x + 2).coerceAtMost(evenFloor(orientedWidth))
    bottom = evenFloor(bottom).coerceAtLeast(y + 2).coerceAtMost(evenFloor(orientedHeight))
    if (right <= x) right = min(evenFloor(orientedWidth), x + 2)
    if (bottom <= y) bottom = min(evenFloor(orientedHeight), y + 2)

    return ClipCrop(
      x = x,
      y = y,
      width = (right - x).coerceAtLeast(2),
      height = (bottom - y).coerceAtLeast(2),
      rotation = rotation,
    )
  }

  fun selectionBounds(): RectF = RectF(selection)

  private fun hitTest(
    x: Float,
    y: Float,
  ): DragMode {
    val threshold = dpF(32f)
    val leftDistance = kotlin.math.abs(x - selection.left)
    val rightDistance = kotlin.math.abs(x - selection.right)
    val topDistance = kotlin.math.abs(y - selection.top)
    val bottomDistance = kotlin.math.abs(y - selection.bottom)
    val nearLeft = leftDistance <= threshold
    val nearRight = rightDistance <= threshold
    val nearTop = topDistance <= threshold
    val nearBottom = bottomDistance <= threshold
    val withinHorizontal = x in (selection.left - threshold)..(selection.right + threshold)
    val withinVertical = y in (selection.top - threshold)..(selection.bottom + threshold)

    if ((nearLeft || nearRight) && (nearTop || nearBottom)) {
      val useLeft = leftDistance <= rightDistance
      val useTop = topDistance <= bottomDistance
      return when {
        useLeft && useTop -> DragMode.TOP_LEFT
        !useLeft && useTop -> DragMode.TOP_RIGHT
        useLeft -> DragMode.BOTTOM_LEFT
        else -> DragMode.BOTTOM_RIGHT
      }
    }

    val verticalEdgeDistance = minOf(leftDistance, rightDistance)
    val horizontalEdgeDistance = minOf(topDistance, bottomDistance)
    val canResizeVertically = withinHorizontal && horizontalEdgeDistance <= threshold
    val canResizeHorizontally = withinVertical && verticalEdgeDistance <= threshold

    return when {
      canResizeHorizontally && (!canResizeVertically || verticalEdgeDistance <= horizontalEdgeDistance) ->
        if (leftDistance <= rightDistance) DragMode.LEFT else DragMode.RIGHT
      canResizeVertically -> if (topDistance <= bottomDistance) DragMode.TOP else DragMode.BOTTOM
      selection.contains(x, y) -> DragMode.MOVE
      videoBounds.contains(x, y) && x < selection.left && y < selection.top -> DragMode.TOP_LEFT
      videoBounds.contains(x, y) && x > selection.right && y < selection.top -> DragMode.TOP_RIGHT
      videoBounds.contains(x, y) && x < selection.left && y > selection.bottom -> DragMode.BOTTOM_LEFT
      videoBounds.contains(x, y) && x > selection.right && y > selection.bottom -> DragMode.BOTTOM_RIGHT
      videoBounds.contains(x, y) && x < selection.left -> DragMode.LEFT
      videoBounds.contains(x, y) && x > selection.right -> DragMode.RIGHT
      videoBounds.contains(x, y) && y < selection.top -> DragMode.TOP
      videoBounds.contains(x, y) && y > selection.bottom -> DragMode.BOTTOM
      else -> DragMode.NONE
    }
  }

  private fun updateSelection(
    dx: Float,
    dy: Float,
  ) {
    val minWidth = min(dpF(48f), videoBounds.width() * 0.5f).coerceAtLeast(2f)
    val minHeight = min(dpF(48f), videoBounds.height() * 0.5f).coerceAtLeast(2f)
    when (dragMode) {
      DragMode.MOVE -> {
        var moveX = dx
        var moveY = dy
        if (selection.left + moveX < videoBounds.left) moveX = videoBounds.left - selection.left
        if (selection.right + moveX > videoBounds.right) moveX = videoBounds.right - selection.right
        if (selection.top + moveY < videoBounds.top) moveY = videoBounds.top - selection.top
        if (selection.bottom + moveY > videoBounds.bottom) moveY = videoBounds.bottom - selection.bottom
        selection.offset(moveX, moveY)
      }
      DragMode.NONE -> Unit
      else -> {
        if (dragMode == DragMode.LEFT || dragMode == DragMode.TOP_LEFT || dragMode == DragMode.BOTTOM_LEFT) {
          selection.left = (selection.left + dx).coerceIn(videoBounds.left, selection.right - minWidth)
        }
        if (dragMode == DragMode.RIGHT || dragMode == DragMode.TOP_RIGHT || dragMode == DragMode.BOTTOM_RIGHT) {
          selection.right = (selection.right + dx).coerceIn(selection.left + minWidth, videoBounds.right)
        }
        if (dragMode == DragMode.TOP || dragMode == DragMode.TOP_LEFT || dragMode == DragMode.TOP_RIGHT) {
          selection.top = (selection.top + dy).coerceIn(videoBounds.top, selection.bottom - minHeight)
        }
        if (dragMode == DragMode.BOTTOM || dragMode == DragMode.BOTTOM_LEFT || dragMode == DragMode.BOTTOM_RIGHT) {
          selection.bottom = (selection.bottom + dy).coerceIn(selection.top + minHeight, videoBounds.bottom)
        }
      }
    }
    onSelectionChanged(RectF(selection))
  }

  private fun drawResizeHandles(canvas: Canvas) {
    val leg = min(dpF(22f), min(selection.width(), selection.height()) * 0.28f)
    val edgeLength = min(dpF(28f), min(selection.width(), selection.height()) * 0.3f)
    val edgeThickness = dpF(5f)
    val edgeRadius = edgeThickness / 2f

    canvas.drawLine(selection.left, selection.top, selection.left + leg, selection.top, cornerHandlePaint)
    canvas.drawLine(selection.left, selection.top, selection.left, selection.top + leg, cornerHandlePaint)
    canvas.drawLine(selection.right - leg, selection.top, selection.right, selection.top, cornerHandlePaint)
    canvas.drawLine(selection.right, selection.top, selection.right, selection.top + leg, cornerHandlePaint)
    canvas.drawLine(selection.left, selection.bottom, selection.left + leg, selection.bottom, cornerHandlePaint)
    canvas.drawLine(selection.left, selection.bottom - leg, selection.left, selection.bottom, cornerHandlePaint)
    canvas.drawLine(selection.right - leg, selection.bottom, selection.right, selection.bottom, cornerHandlePaint)
    canvas.drawLine(selection.right, selection.bottom - leg, selection.right, selection.bottom, cornerHandlePaint)

    val centerX = selection.centerX()
    val centerY = selection.centerY()
    canvas.drawRoundRect(
      centerX - edgeLength / 2f,
      selection.top - edgeThickness / 2f,
      centerX + edgeLength / 2f,
      selection.top + edgeThickness / 2f,
      edgeRadius,
      edgeRadius,
      edgeHandlePaint,
    )
    canvas.drawRoundRect(
      centerX - edgeLength / 2f,
      selection.bottom - edgeThickness / 2f,
      centerX + edgeLength / 2f,
      selection.bottom + edgeThickness / 2f,
      edgeRadius,
      edgeRadius,
      edgeHandlePaint,
    )
    canvas.drawRoundRect(
      selection.left - edgeThickness / 2f,
      centerY - edgeLength / 2f,
      selection.left + edgeThickness / 2f,
      centerY + edgeLength / 2f,
      edgeRadius,
      edgeRadius,
      edgeHandlePaint,
    )
    canvas.drawRoundRect(
      selection.right - edgeThickness / 2f,
      centerY - edgeLength / 2f,
      selection.right + edgeThickness / 2f,
      centerY + edgeLength / 2f,
      edgeRadius,
      edgeRadius,
      edgeHandlePaint,
    )
  }

  private fun evenFloor(value: Int): Int = value and -2

  private fun dpF(value: Float): Float = value * density

  private fun spF(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
