/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.browser.dialogs

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.media.metrics.LogSessionId
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.utils.media.CopyPasteOps
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

enum class VideoCompressionPreset {
  HIGH,
  MEDIUM,
  LOW,
  CUSTOM,
}

enum class VideoCompressorSaveMode {
  CURRENT_FOLDER,
  MOVIES_COMPRESSOR,
}

data class VideoCompressorUiState(
  val sourceVideo: Video? = null,
  val sourceUri: Uri? = null,
  val originalSize: Long = 0L,
  val originalWidth: Int = 0,
  val originalHeight: Int = 0,
  val originalBitrate: Int = 0,
  val originalAudioBitrate: Int = 0,
  val originalFps: Float = 30f,
  val durationMs: Long = 0L,
  val originalDate: Long? = null,
  val originalName: String? = null,
  val isCompressing: Boolean = false,
  val progress: Float = 0f,
  val currentItemProgress: Float = 0f,
  val progressAvailable: Boolean = false,
  val compressedUri: Uri? = null,
  val compressedSize: Long = 0L,
  val currentOutputSize: Long = 0L,
  val error: String? = null,
  val errorLog: String? = null,
  val queueSize: Int = 1,
  val currentQueueIndex: Int = 0,
  val completedCount: Int = 0,
  val saveMode: VideoCompressorSaveMode = VideoCompressorSaveMode.CURRENT_FOLDER,
  val destinationDisplayPath: String = "",
  val savedOutputPaths: List<String> = emptyList(),
  val activePreset: VideoCompressionPreset = VideoCompressionPreset.HIGH,
  val targetSizeMb: Float = 10f,
  val videoCodec: String = MimeTypes.VIDEO_H265,
  val targetResolutionHeight: Int = 0,
  val targetFps: Int = 0,
  val supportedCodecs: List<String> = emptyList(),
  val appInfoVersion: String = BuildConfig.VERSION_NAME,
  val showBitrate: Boolean = false,
  val useMbps: Boolean = false,
  val preserveMetadata: Boolean = false,
  val removeAudio: Boolean = false,
  val audioBitrate: Int = 128_000,
  val audioVolume: Float = 1.0f,
  val warnings: List<String> = emptyList(),
  val totalSavedBytes: Long = 0L,
  val showStorageSaved: Boolean = true,
  val showTargetSizePreset: Boolean = true,
  val targetSizePresets: List<TargetSizePreset> = defaultTargetSizePresets,
  val highPresetConfig: QualityPresetConfig = defaultHighPresetConfig,
  val mediumPresetConfig: QualityPresetConfig = defaultMediumPresetConfig,
  val lowPresetConfig: QualityPresetConfig = defaultLowPresetConfig,
  val defaultVideoConfig: DefaultVideoConfig = DefaultVideoConfig(),
  val defaultAudioConfig: DefaultAudioConfig = DefaultAudioConfig(),
) {
  private val minBitrate: Long
    get() {
      val height = if (targetResolutionHeight > 0) targetResolutionHeight else originalHeight
      var base =
        when {
          height >= 2160 -> 4_000_000L
          height >= 1440 -> 2_500_000L
          height >= 1080 -> 1_500_000L
          height >= 720 -> 1_000_000L
          height >= 480 -> 500_000L
          height >= 360 -> 350_000L
          else -> 200_000L
        }

      when (videoCodec) {
        MimeTypes.VIDEO_H265 -> base = (base * 0.7f).toLong()
        MimeTypes.VIDEO_AV1 -> base = (base * 0.6f).toLong()
      }

      val fpsValue = if (targetFps > 0) targetFps.toFloat() else originalFps
      val multiplier = if (fpsValue > 45f) 1.5f else 1f
      return (base * multiplier).toLong()
    }

  val minimumSizeMb: Float
    get() {
      if (durationMs <= 0L) return 0.1f
      val seconds = durationMs / 1000f
      val audioBits =
        if (removeAudio) {
          0f
        } else {
          val rate = if (audioBitrate == 0) 256_000f else audioBitrate.toFloat()
          rate * seconds
        }
      val totalBits = (minBitrate * seconds) + audioBits
      return (totalBits / 8f) / (1024f * 1024f)
    }

  val estimatedSize: String
    get() = String.format(Locale.US, "%.1f MB", targetSizeMb.coerceAtLeast(minimumSizeMb))

  val targetBitrate: Int
    get() {
      val durationSec = if (durationMs > 0L) durationMs / 1000.0 else 0.0
      if (durationSec <= 0.0) return 2_000_000

      val targetBits = targetSizeMb.toDouble() * 8.0 * 1024.0 * 1024.0
      val audioBits =
        if (removeAudio) {
          0.0
        } else {
          val rate = if (audioBitrate == 0) 256_000.0 else audioBitrate.toDouble()
          rate * durationSec
        }
      val overheadBits = (targetBits * 0.02) + (50 * 1024 * 8)
      val videoBits = (targetBits - audioBits - overheadBits).coerceAtLeast(targetBits * 0.1)
      val calculated = (videoBits / durationSec).toLong()
      val original = if (originalBitrate > 0) originalBitrate.toLong() else Long.MAX_VALUE
      return calculated.coerceAtLeast(minBitrate).coerceAtMost(original).toInt()
    }

  val formattedBitrate: String
    get() {
      if (!showBitrate) return ""
      return if (useMbps) {
        String.format(Locale.US, "%.1f Mbps", targetBitrate / 1_000_000f)
      } else {
        "${targetBitrate / 1000} kbps"
      }
    }

  val formattedOriginalBitrate: String
    get() {
      if (!showBitrate || originalBitrate <= 0) return ""
      return if (useMbps) {
        String.format(Locale.US, "%.1f Mbps", originalBitrate / 1_000_000f)
      } else {
        "${originalBitrate / 1000} kbps"
      }
    }

  val formattedOriginalSize: String
    get() = formatCompressionFileSize(originalSize)

  val formattedCompressedSize: String
    get() = formatCompressionFileSize(compressedSize)

  val formattedCurrentOutputSize: String
    get() = formatCompressionFileSize(currentOutputSize)

  val formattedTotalSaved: String
    get() = formatCompressionFileSize(totalSavedBytes)

  val isBatch: Boolean
    get() = queueSize > 1

  fun autoAdjust(
    targetMb: Float,
    lockAudioBitrate: Boolean = false,
    allowUpward: Boolean = true,
  ): VideoCompressorUiState {
    var state = this
    var attempts = 0
    val maxAttempts = 20

    while (state.minimumSizeMb > targetMb && attempts < maxAttempts) {
      attempts++
      val effectiveFps = if (state.targetFps > 0) state.targetFps else state.originalFps.toInt()

      if (effectiveFps > 30) {
        state = state.copy(targetFps = 30)
        continue
      }

      if (!lockAudioBitrate) {
        if (state.audioBitrate > 128_000) {
          state = state.copy(audioBitrate = 128_000)
          continue
        }

        if (state.audioBitrate > 64_000 && state.minimumSizeMb > targetMb * 1.5f) {
          state = state.copy(audioBitrate = 64_000)
          continue
        }
      }

      val currentHeight = if (state.targetResolutionHeight > 0) state.targetResolutionHeight else state.originalHeight
      val nextHeight =
        when {
          currentHeight > 2160 -> 2160
          currentHeight > 1440 -> 1440
          currentHeight > 1080 -> 1080
          currentHeight > 720 -> 720
          currentHeight > 480 -> 480
          currentHeight > 360 -> 360
          else -> 240
        }

      if (nextHeight < currentHeight) {
        state = state.copy(targetResolutionHeight = nextHeight)
        continue
      }

      if (effectiveFps > 24) {
        state = state.copy(targetFps = 24)
        continue
      }

      break
    }

    if (allowUpward) {
      attempts = 0
      while (attempts < maxAttempts) {
        attempts++
        var changed = false

        val currentHeight = if (state.targetResolutionHeight > 0) state.targetResolutionHeight else state.originalHeight
        if (currentHeight < state.originalHeight) {
          val nextHeight =
            when {
              currentHeight < 360 -> 360
              currentHeight < 480 -> 480
              currentHeight < 720 -> 720
              currentHeight < 1080 -> 1080
              currentHeight < 1440 -> 1440
              currentHeight < 2160 -> 2160
              else -> state.originalHeight
            }.coerceAtMost(state.originalHeight)
          val candidate = state.copy(targetResolutionHeight = if (nextHeight >= state.originalHeight) 0 else nextHeight)
          if (candidate.minimumSizeMb <= targetMb) {
            state = candidate
            changed = true
            continue
          }
        }

        val currentFps = if (state.targetFps > 0) state.targetFps else state.originalFps.toInt()
        if (currentFps < state.originalFps.toInt()) {
          val nextFps = if (currentFps < 30) 30 else state.originalFps.toInt()
          val candidate = state.copy(targetFps = if (nextFps >= state.originalFps.toInt()) 0 else nextFps)
          if (candidate.minimumSizeMb <= targetMb) {
            state = candidate
            changed = true
            continue
          }
        }

        if (!lockAudioBitrate) {
          val maxAudio = if (state.originalAudioBitrate > 0) state.originalAudioBitrate else 320_000
          if (state.audioBitrate < maxAudio) {
            val nextAudio =
              when {
                state.audioBitrate < 64_000 -> 64_000
                state.audioBitrate < 128_000 -> 128_000
                state.audioBitrate < 192_000 -> 192_000
                state.audioBitrate < 320_000 -> 320_000
                else -> maxAudio
              }.coerceAtMost(maxAudio)
            val candidate = state.copy(audioBitrate = nextAudio)
            if (candidate.minimumSizeMb <= targetMb) {
              state = candidate
              changed = true
              continue
            }
          }
        }

        if (!changed) break
      }
    }

    return state
  }
}

fun formatCompressionFileSize(size: Long): String {
  if (size <= 0L) return "0 MB"
  val mb = size / (1024.0 * 1024.0)
  return if (mb >= 1000) {
    String.format(Locale.US, "%.1f GB", mb / 1024)
  } else {
    String.format(Locale.US, "%.1f MB", mb)
  }
}

@OptIn(UnstableApi::class)
class VideoCompressorViewModel(
  application: Application,
) : AndroidViewModel(application) {
  private data class CompressionTemplate(
    val preset: VideoCompressionPreset,
    val targetSizeMb: Float,
    val videoCodec: String,
    val targetResolutionHeight: Int,
    val targetFps: Int,
    val removeAudio: Boolean,
    val audioBitrate: Int,
    val audioVolume: Float,
    val showBitrate: Boolean,
    val useMbps: Boolean,
    val preserveMetadata: Boolean,
    val saveMode: VideoCompressorSaveMode,
  )

  private val prefs: SharedPreferences by lazy {
    application.getSharedPreferences("video_compressor_overlay", Context.MODE_PRIVATE)
  }

  private val _uiState = MutableStateFlow(VideoCompressorUiState())
  val uiState = _uiState.asStateFlow()

  private var compressionJob: Job? = null
  private var activeTransformer: Transformer? = null
  private var queuedVideos: List<Video> = emptyList()

  init {
    viewModelScope.launch(Dispatchers.IO) {
      val showBitrate = prefs.getBoolean("show_bitrate", false)
      val useMbps = prefs.getBoolean("use_mbps", false)
      val preserveMetadata = prefs.getBoolean("preserve_metadata", false)
      val showStorageSaved = prefs.getBoolean("show_storage_saved", true)
      val showTargetSizePreset = prefs.getBoolean("show_target_size_preset", true)
      val totalSavedBytes = prefs.getLong("total_saved_bytes", 0L)

      _uiState.update {
        it.copy(
          showBitrate = showBitrate,
          useMbps = useMbps,
          preserveMetadata = preserveMetadata,
          showStorageSaved = showStorageSaved,
          showTargetSizePreset = showTargetSizePreset,
          totalSavedBytes = totalSavedBytes,
          highPresetConfig = loadHighPresetConfig(prefs),
          mediumPresetConfig = loadMediumPresetConfig(prefs),
          lowPresetConfig = loadLowPresetConfig(prefs),
          targetSizePresets = loadTargetSizePresets(prefs),
          defaultVideoConfig = loadDefaultVideoConfig(prefs),
          defaultAudioConfig = loadDefaultAudioConfig(prefs),
        )
      }
      checkSupportedCodecs()
    }
    clearCache()
  }

  fun loadVideos(
    context: Context,
    videos: List<Video>,
  ) {
    val uniqueVideos = videos.distinctBy { it.id }
    if (uniqueVideos.isEmpty()) return
    queuedVideos = uniqueVideos

    viewModelScope.launch(Dispatchers.IO) {
      val currentState = _uiState.value
      val firstVideo = uniqueVideos.first()
      val saveMode =
        currentState.saveMode.takeIf {
          it == VideoCompressorSaveMode.MOVIES_COMPRESSOR || File(firstVideo.path).parentFile != null
        } ?: defaultSaveModeFor(firstVideo)
      val loadedState =
        readVideoState(context, firstVideo).copy(
          queueSize = uniqueVideos.size,
          completedCount = 0,
          currentQueueIndex = 0,
          saveMode = saveMode,
          destinationDisplayPath = resolveDestinationDisplayPath(saveMode, firstVideo),
        )
      _uiState.value = loadedState
    }
  }

  fun loadVideo(
    context: Context,
    video: Video,
  ) = loadVideos(context, listOf(video))

  private suspend fun readVideoState(
    context: Context,
    video: Video,
  ): VideoCompressorUiState {
    var size = video.size
    var width = video.width
    var height = video.height
    var bitrate = 0
    var audioBitrate = getAudioBitrate(context, video.uri)
    var fps = video.fps.takeIf { it > 0f } ?: 30f
    var duration = video.duration
    var originalDate: Long? = null
    var originalName: String? = video.displayName

    try {
      context.contentResolver.openFileDescriptor(video.uri, "r")?.use {
        if (it.statSize > 0L) size = it.statSize
      }

      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(context, video.uri)

        width =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            ?: width
        height =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            ?: height

        val rotation =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
            ?: 0
        if (rotation == 90 || rotation == 270) {
          val temp = width
          width = height
          height = temp
        }

        bitrate =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
            ?: bitrate
        duration =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: duration
        fps =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            ?: fps

        val dateString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        if (dateString != null) {
          val formats =
            listOf(
              "yyyyMMdd'T'HHmmss.SSS'Z'",
              "yyyyMMdd'T'HHmmss'Z'",
              "yyyy-MM-dd HH:mm:ss",
            )
          for (format in formats) {
            runCatching {
              val sdf = java.text.SimpleDateFormat(format, Locale.US)
              sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
              originalDate = sdf.parse(dateString)?.time
            }
            if (originalDate != null) break
          }
        }

        context.contentResolver.query(video.uri, null, null, null, null)?.use { cursor ->
          val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
          if (cursor.moveToFirst() && nameIndex != -1) {
            originalName = cursor.getString(nameIndex) ?: originalName
          }
        }
      } finally {
        runCatching { retriever.release() }
      }
    } catch (_: Exception) {
    }

    val currentState = _uiState.value
    val videoConfig = currentState.defaultVideoConfig
    val audioConfig = currentState.defaultAudioConfig

    val defaultTargetMb =
      if (size > 0L) {
        (size / (1024.0 * 1024.0) * videoConfig.defaultSizeRatio).toFloat().coerceAtLeast(0.1f)
      } else {
        10f
      }
    val defaultCodec =
      if (currentState.supportedCodecs.contains(videoConfig.defaultVideoCodec)) {
        videoConfig.defaultVideoCodec
      } else if (currentState.supportedCodecs.contains(MimeTypes.VIDEO_H265)) {
        MimeTypes.VIDEO_H265
      } else {
        MimeTypes.VIDEO_H264
      }
    val isVertical = height > width

    fun targetHeightFor(shortSide: Int): Int {
      if (width <= 0 || height <= 0) return height
      return if (isVertical) {
        val targetWidth = minOf(shortSide, width)
        (targetWidth.toDouble() * height / width).toInt()
      } else {
        minOf(shortSide, height)
      }
    }

    val targetHeight =
      if (videoConfig.defaultTargetResolutionHeight > 0) {
        targetHeightFor(videoConfig.defaultTargetResolutionHeight)
      } else {
        height
      }
    val targetFpsVal =
      if (videoConfig.defaultTargetFps > 0 && fps >= videoConfig.defaultTargetFps) {
        videoConfig.defaultTargetFps
      } else {
        0
      }

    return VideoCompressorUiState(
      sourceVideo = video,
      sourceUri = video.uri,
      originalSize = size,
      originalWidth = width,
      originalHeight = height,
      originalBitrate = bitrate,
      originalAudioBitrate = audioBitrate,
      originalFps = fps,
      durationMs = duration,
      originalDate = originalDate,
      originalName = originalName,
      targetSizeMb = defaultTargetMb,
      targetResolutionHeight = targetHeight,
      targetFps = targetFpsVal,
      activePreset = VideoCompressionPreset.HIGH,
      showBitrate = currentState.showBitrate,
      useMbps = currentState.useMbps,
      preserveMetadata = currentState.preserveMetadata,
      supportedCodecs = currentState.supportedCodecs,
      videoCodec = defaultCodec,
      audioBitrate = if (audioConfig.defaultAudioBitrate > 0) audioConfig.defaultAudioBitrate else 128_000,
      audioVolume = audioConfig.defaultAudioVolume,
      removeAudio = audioConfig.defaultRemoveAudio,
      queueSize = queuedVideos.size.coerceAtLeast(1),
    ).autoAdjust(defaultTargetMb)
  }

  private fun buildStateFromTemplate(
    baseState: VideoCompressorUiState,
    template: CompressionTemplate,
    queueIndex: Int,
    completedCount: Int,
    savedOutputPaths: List<String>,
  ): VideoCompressorUiState {
    val configuredState =
      when (template.preset) {
        VideoCompressionPreset.CUSTOM -> {
          baseState
            .copy(
              activePreset = VideoCompressionPreset.CUSTOM,
              targetSizeMb = template.targetSizeMb,
              targetResolutionHeight = template.targetResolutionHeight,
              targetFps = template.targetFps,
              videoCodec = template.videoCodec,
              removeAudio = template.removeAudio,
              audioBitrate = template.audioBitrate,
              audioVolume = template.audioVolume,
              showBitrate = template.showBitrate,
              useMbps = template.useMbps,
              preserveMetadata = template.preserveMetadata,
            ).autoAdjust(template.targetSizeMb)
        }

        else ->
          applyPresetToState(
            baseState.copy(
              activePreset = template.preset,
              videoCodec = template.videoCodec,
              showBitrate = template.showBitrate,
              useMbps = template.useMbps,
              preserveMetadata = template.preserveMetadata,
            ),
            template.preset,
          ).copy(
            videoCodec = template.videoCodec,
            preserveMetadata = template.preserveMetadata,
            audioVolume = template.audioVolume,
          )
      }

    return configuredState.copy(
      queueSize = queuedVideos.size.coerceAtLeast(1),
      currentQueueIndex = queueIndex,
      completedCount = completedCount,
      saveMode = template.saveMode,
      destinationDisplayPath = resolveDestinationDisplayPath(template.saveMode, baseState.sourceVideo),
      savedOutputPaths = savedOutputPaths,
    )
  }

  private fun defaultSaveModeFor(video: Video): VideoCompressorSaveMode =
    if (CopyPasteOps.canUseDirectFileOperations() && File(video.path).parentFile != null) {
      VideoCompressorSaveMode.CURRENT_FOLDER
    } else {
      VideoCompressorSaveMode.MOVIES_COMPRESSOR
    }

  private fun resolveDestinationDisplayPath(
    saveMode: VideoCompressorSaveMode,
    video: Video?,
  ): String =
    when (saveMode) {
      VideoCompressorSaveMode.CURRENT_FOLDER ->
        if (CopyPasteOps.canUseDirectFileOperations()) {
          File(video?.path.orEmpty()).parent ?: moviesCompressorPath()
        } else {
          moviesCompressorPath()
        }
      VideoCompressorSaveMode.MOVIES_COMPRESSOR -> moviesCompressorPath()
    }

  private fun applyPresetToState(
    state: VideoCompressorUiState,
    preset: VideoCompressionPreset,
  ): VideoCompressorUiState {
    if (preset == VideoCompressionPreset.CUSTOM) {
      return state.copy(activePreset = VideoCompressionPreset.CUSTOM)
    }

    val current = state
    val isVertical = current.originalHeight > current.originalWidth

    fun targetHeight(targetShortSide: Int): Int {
      if (current.originalWidth <= 0 || current.originalHeight <= 0) return current.originalHeight
      return if (isVertical) {
        val targetWidth = minOf(targetShortSide, current.originalWidth)
        (targetWidth.toDouble() * current.originalHeight / current.originalWidth).toInt()
      } else {
        minOf(targetShortSide, current.originalHeight)
      }
    }

    fun applyConfig(config: QualityPresetConfig): VideoCompressorUiState {
      val targetMb =
        if (config.sizeRatio > 0f) {
          (current.originalSize / (1024.0 * 1024.0) * config.sizeRatio).toFloat().coerceAtLeast(0.1f)
        } else {
          (current.originalSize / (1024.0 * 1024.0)).toFloat()
        }
      return current
        .copy(
          targetResolutionHeight =
            if (config.resolutionShortSide > 0) {
              targetHeight(config.resolutionShortSide)
            } else {
              current.originalHeight
            },
          targetFps =
            if (config.targetFps > 0 && current.originalFps >= config.targetFps) {
              config.targetFps
            } else {
              0
            },
          targetSizeMb = targetMb,
          audioBitrate = if (config.audioBitrate > 0) config.audioBitrate else current.audioBitrate,
          removeAudio = false,
        )
        .autoAdjust(targetMb, lockAudioBitrate = true, allowUpward = false)
    }

    return when (preset) {
      VideoCompressionPreset.HIGH ->
        applyConfig(current.highPresetConfig).copy(activePreset = VideoCompressionPreset.HIGH)

      VideoCompressionPreset.MEDIUM ->
        applyConfig(current.mediumPresetConfig).copy(activePreset = VideoCompressionPreset.MEDIUM)

      VideoCompressionPreset.LOW ->
        applyConfig(current.lowPresetConfig).copy(activePreset = VideoCompressionPreset.LOW)

      VideoCompressionPreset.CUSTOM -> current
    }
  }

  fun applyPreset(preset: VideoCompressionPreset) {
    _uiState.update { applyPresetToState(it, preset) }
  }

  fun setVideoCodec(codec: String) {
    _uiState.update {
      val next =
        it.copy(
          videoCodec = codec,
          activePreset = VideoCompressionPreset.CUSTOM,
        )
      next.autoAdjust(next.targetSizeMb)
    }
  }

  fun setSaveMode(mode: VideoCompressorSaveMode) {
    _uiState.update {
      it.copy(
        saveMode = mode,
        destinationDisplayPath = resolveDestinationDisplayPath(mode, it.sourceVideo),
      )
    }
  }

  fun toggleShowBitrate() {
    _uiState.update {
      val newValue = !it.showBitrate
      prefs.edit().putBoolean("show_bitrate", newValue).apply()
      it.copy(showBitrate = newValue)
    }
  }

  fun toggleBitrateUnit() {
    _uiState.update {
      val newValue = !it.useMbps
      prefs.edit().putBoolean("use_mbps", newValue).apply()
      it.copy(useMbps = newValue)
    }
  }

  fun togglePreserveMetadata() {
    _uiState.update {
      val newValue = !it.preserveMetadata
      prefs.edit().putBoolean("preserve_metadata", newValue).apply()
      it.copy(preserveMetadata = newValue)
    }
  }

  fun toggleRemoveAudio() {
    _uiState.update {
      val next = it.copy(removeAudio = !it.removeAudio, activePreset = VideoCompressionPreset.CUSTOM)
      if (next.removeAudio) next else next.autoAdjust(next.targetSizeMb)
    }
  }

  fun setAudioBitrate(bitrate: Int) {
    _uiState.update {
      val next = it.copy(audioBitrate = bitrate, activePreset = VideoCompressionPreset.CUSTOM)
      next.autoAdjust(next.targetSizeMb, lockAudioBitrate = true)
    }
  }

  fun setResolution(height: Int) {
    _uiState.update {
      val isVertical = it.originalHeight > it.originalWidth
      val mappedHeight =
        if (isVertical && it.originalWidth > 0 && it.originalHeight > 0 && height > 0) {
          (height.toLong() * it.originalHeight / it.originalWidth).toInt()
        } else {
          height
        }
      it.copy(targetResolutionHeight = mappedHeight, activePreset = VideoCompressionPreset.CUSTOM)
    }
  }

  fun setFps(fps: Int) {
    _uiState.update { it.copy(targetFps = fps, activePreset = VideoCompressionPreset.CUSTOM) }
  }

  fun updateAudioVolume(volume: Float) {
    _uiState.update {
      it.copy(audioVolume = volume.coerceIn(0f, 2f), activePreset = VideoCompressionPreset.CUSTOM)
    }
  }

  fun toggleShowStorageSaved() {
    _uiState.update {
      val newValue = !it.showStorageSaved
      prefs.edit().putBoolean("show_storage_saved", newValue).apply()
      it.copy(showStorageSaved = newValue)
    }
  }

  fun toggleShowTargetSizePreset() {
    _uiState.update {
      val newValue = !it.showTargetSizePreset
      prefs.edit().putBoolean("show_target_size_preset", newValue).apply()
      it.copy(showTargetSizePreset = newValue)
    }
  }

  fun setTargetSizePreset(sizeMb: Float) {
    _uiState.update {
      it.copy(targetSizeMb = sizeMb, activePreset = VideoCompressionPreset.CUSTOM)
        .autoAdjust(sizeMb, allowUpward = false)
    }
  }

  fun saveTargetSizePreset(targetSizePreset: TargetSizePreset) {
    viewModelScope.launch(Dispatchers.IO) {
      val updated = _uiState.value.targetSizePresets.toMutableList().apply {
        val existingIndex = indexOfFirst { it.id == targetSizePreset.id }
        if (existingIndex >= 0) {
          this[existingIndex] = targetSizePreset
        } else {
          add(targetSizePreset)
        }
      }
      saveTargetSizePresets(prefs, updated)
      _uiState.update { it.copy(targetSizePresets = updated) }
    }
  }

  fun deleteTargetSizePreset(targetSizePreset: TargetSizePreset) {
    viewModelScope.launch(Dispatchers.IO) {
      val updated = _uiState.value.targetSizePresets.filterNot { it.id == targetSizePreset.id }
      saveTargetSizePresets(prefs, updated)
      _uiState.update { it.copy(targetSizePresets = updated) }
    }
  }

  fun resetTargetSizePresets() {
    viewModelScope.launch(Dispatchers.IO) {
      clearSavedTargetSizePresets(prefs)
      _uiState.update { it.copy(targetSizePresets = defaultTargetSizePresets) }
    }
  }

  fun saveQualityPreset(preset: VideoCompressionPreset, config: QualityPresetConfig) {
    if (preset == VideoCompressionPreset.CUSTOM) return
    viewModelScope.launch(Dispatchers.IO) {
      when (preset) {
        VideoCompressionPreset.HIGH -> saveHighPresetConfig(prefs, config)
        VideoCompressionPreset.MEDIUM -> saveMediumPresetConfig(prefs, config)
        VideoCompressionPreset.LOW -> saveLowPresetConfig(prefs, config)
        VideoCompressionPreset.CUSTOM -> return@launch
      }
      _uiState.update {
        when (preset) {
          VideoCompressionPreset.HIGH -> it.copy(highPresetConfig = config)
          VideoCompressionPreset.MEDIUM -> it.copy(mediumPresetConfig = config)
          VideoCompressionPreset.LOW -> it.copy(lowPresetConfig = config)
          VideoCompressionPreset.CUSTOM -> it
        }
      }
    }
  }

  fun resetQualityPresets() {
    viewModelScope.launch(Dispatchers.IO) {
      clearSavedQualityPresets(prefs)
      _uiState.update {
        it.copy(
          highPresetConfig = defaultHighPresetConfig,
          mediumPresetConfig = defaultMediumPresetConfig,
          lowPresetConfig = defaultLowPresetConfig,
        )
      }
    }
  }

  fun saveDefaultVideoConfig(config: DefaultVideoConfig) {
    viewModelScope.launch(Dispatchers.IO) {
      saveDefaultVideoConfig(prefs, config)
      _uiState.update { it.copy(defaultVideoConfig = config) }
    }
  }

  fun resetDefaultVideoConfig() {
    viewModelScope.launch(Dispatchers.IO) {
      clearSavedDefaultVideoConfig(prefs)
      _uiState.update { it.copy(defaultVideoConfig = DefaultVideoConfig()) }
    }
  }

  fun saveDefaultAudioConfig(config: DefaultAudioConfig) {
    viewModelScope.launch(Dispatchers.IO) {
      saveDefaultAudioConfig(prefs, config)
      _uiState.update { it.copy(defaultAudioConfig = config) }
    }
  }

  fun resetDefaultAudioConfig() {
    viewModelScope.launch(Dispatchers.IO) {
      clearSavedDefaultAudioConfig(prefs)
      _uiState.update { it.copy(defaultAudioConfig = DefaultAudioConfig()) }
    }
  }

  fun setTargetSize(mb: Float) {
    _uiState.update {
      it.copy(targetSizeMb = mb, activePreset = VideoCompressionPreset.CUSTOM).autoAdjust(mb, allowUpward = false)
    }
  }

  fun cancelCompression() {
    activeTransformer?.cancel()
    compressionJob?.cancel()
    activeTransformer = null
    _uiState.update {
      it.copy(
        isCompressing = false,
        progress = 0f,
        currentItemProgress = 0f,
        progressAvailable = false,
      )
    }
  }

  fun resetSession() {
    cancelCompression()
    clearCache()
    queuedVideos = emptyList()
    val current = _uiState.value
    val defaultCodec =
      if (current.supportedCodecs.contains(MimeTypes.VIDEO_H265)) {
        MimeTypes.VIDEO_H265
      } else {
        MimeTypes.VIDEO_H264
      }
    _uiState.value =
      VideoCompressorUiState(
        supportedCodecs = current.supportedCodecs,
        showBitrate = current.showBitrate,
        useMbps = current.useMbps,
        preserveMetadata = current.preserveMetadata,
        videoCodec = defaultCodec,
        saveMode = current.saveMode,
        showStorageSaved = current.showStorageSaved,
        showTargetSizePreset = current.showTargetSizePreset,
        totalSavedBytes = current.totalSavedBytes,
        targetSizePresets = current.targetSizePresets,
        highPresetConfig = current.highPresetConfig,
        mediumPresetConfig = current.mediumPresetConfig,
        lowPresetConfig = current.lowPresetConfig,
        defaultVideoConfig = current.defaultVideoConfig,
        defaultAudioConfig = current.defaultAudioConfig,
      )
  }

  fun startCompression(context: Context) {
    val queue = queuedVideos.ifEmpty { _uiState.value.sourceVideo?.let { listOf(it) } ?: emptyList() }
    if (queue.isEmpty()) return

    val template =
      CompressionTemplate(
        preset = _uiState.value.activePreset,
        targetSizeMb = _uiState.value.targetSizeMb,
        videoCodec = _uiState.value.videoCodec,
        targetResolutionHeight = _uiState.value.targetResolutionHeight,
        targetFps = _uiState.value.targetFps,
        removeAudio = _uiState.value.removeAudio,
        audioBitrate = _uiState.value.audioBitrate,
        audioVolume = _uiState.value.audioVolume,
        showBitrate = _uiState.value.showBitrate,
        useMbps = _uiState.value.useMbps,
        preserveMetadata = _uiState.value.preserveMetadata,
        saveMode = _uiState.value.saveMode,
      )

    compressionJob?.cancel()
    compressionJob =
      viewModelScope.launch {
        val savedPaths = mutableListOf<String>()
        var lastFinalFile: File? = null
        var lastPreviewFile: File? = null
        val queueSize = queue.size

        _uiState.update {
          it.copy(
            isCompressing = true,
            progress = 0f,
            currentItemProgress = 0f,
            progressAvailable = false,
            currentOutputSize = 0L,
            error = null,
            errorLog = null,
            compressedUri = null,
            compressedSize = 0L,
            warnings = emptyList(),
            savedOutputPaths = emptyList(),
            completedCount = 0,
            currentQueueIndex = 0,
            queueSize = queueSize,
          )
        }

        for ((index, video) in queue.withIndex()) {
          val baseState = withContext(Dispatchers.IO) { readVideoState(context, video) }
          val itemState = buildStateFromTemplate(baseState, template, index, index, savedPaths.toList())
          _uiState.value =
            itemState.copy(
              isCompressing = true,
              progress = index / queueSize.toFloat(),
              currentItemProgress = 0f,
              progressAvailable = false,
              currentOutputSize = 0L,
              error = null,
              errorLog = null,
              compressedUri = null,
              compressedSize = 0L,
            )

          val compressedFile = compressSingleVideo(context, itemState, index, queueSize) ?: return@launch
          val finalFile =
            runCatching {
              withContext(Dispatchers.IO) {
                persistCompressedFile(context, compressedFile, itemState)
              }
            }.getOrElse { error ->
              _uiState.update {
                it.copy(
                  isCompressing = false,
                  error = error.message ?: "Failed to save compressed file.",
                  errorLog = error.stackTraceToString(),
                  progressAvailable = false,
                )
              }
              runCatching { compressedFile.delete() }
              return@launch
            }

          savedPaths += finalFile.absolutePath
          lastFinalFile = finalFile
          lastPreviewFile = compressedFile

          val savedBytes = (itemState.originalSize - finalFile.length()).coerceAtLeast(0L)
          val totalSavedBytes =
            _uiState.value.totalSavedBytes + savedBytes
          prefs.edit().putLong("total_saved_bytes", totalSavedBytes).apply()

          val warnings =
            buildList {
              addAll(_uiState.value.warnings)
              if (finalFile.length() > itemState.originalSize) {
                add("Compressed output is larger than the original for ${itemState.originalName ?: video.displayName}.")
              }
            }

          _uiState.update {
            it.copy(
              isCompressing = true,
              progress = (index + 1f) / queueSize.toFloat(),
              currentItemProgress = 1f,
              progressAvailable = true,
              compressedUri = Uri.fromFile(compressedFile),
              compressedSize = finalFile.length(),
              currentOutputSize = finalFile.length(),
              savedOutputPaths = savedPaths.toList(),
              completedCount = index + 1,
              warnings = warnings,
              totalSavedBytes = totalSavedBytes,
            )
          }
        }

        _uiState.update {
          it.copy(
            isCompressing = false,
            progress = 1f,
            currentItemProgress = 1f,
            progressAvailable = true,
            compressedUri = lastPreviewFile?.let(Uri::fromFile),
            compressedSize = lastFinalFile?.length() ?: 0L,
            currentOutputSize = lastFinalFile?.length() ?: 0L,
            savedOutputPaths = savedPaths.toList(),
            completedCount = queueSize,
            currentQueueIndex = (queueSize - 1).coerceAtLeast(0),
          )
        }
      }
  }

  private suspend fun compressSingleVideo(
    context: Context,
    state: VideoCompressorUiState,
    queueIndex: Int,
    queueSize: Int,
    forceRemoveAudio: Boolean = false,
  ): File? {
    val inputUri = state.sourceUri ?: return null
    val outputDir = File(context.cacheDir, "compressed_videos")
    val outputFile = File(outputDir, "compress_${UUID.randomUUID()}.mp4")
    outputDir.mkdirs()

    val targetBitrate = state.targetBitrate.toLong()
    val audioBitrateToUse =
      if (state.audioBitrate == 0) {
        val original = getAudioBitrate(context, inputUri)
        if (original > 0) original else 256_000
      } else {
        state.audioBitrate
      }

    val plan =
      buildCompressionPlan(
        inputUri = inputUri,
        videoCodec = state.videoCodec,
        targetResolution = state.targetResolutionHeight,
        targetFps = state.targetFps,
        removeAudio = state.removeAudio || forceRemoveAudio,
        sourceWidth = state.originalWidth,
        sourceHeight = state.originalHeight,
        sourceFps = state.originalFps,
      )
    val videoMimeType = plan.outputVideoMimeType

    _uiState.update { it.copy(warnings = plan.warnings) }
    plan.blockingError?.let { error ->
      _uiState.update {
        it.copy(
          isCompressing = false,
          error = error,
          errorLog = null,
          progressAvailable = false,
        )
      }
      return null
    }

    val decoderFactory =
      DefaultDecoderFactory
        .Builder(context)
        .setEnableDecoderFallback(true)
        .build()

    val cbrEncoderFactory =
      DefaultEncoderFactory
        .Builder(context)
        .setEnableFallback(true)
        .setRequestedVideoEncoderSettings(
          VideoEncoderSettings
            .Builder()
            .setBitrate(targetBitrate.toInt())
            .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            .build(),
        ).setRequestedAudioEncoderSettings(
          AudioEncoderSettings
            .Builder()
            .setBitrate(audioBitrateToUse)
            .build(),
        ).build()

    val vbrEncoderFactory =
      DefaultEncoderFactory
        .Builder(context)
        .setEnableFallback(true)
        .setRequestedVideoEncoderSettings(
          VideoEncoderSettings
            .Builder()
            .setBitrate(targetBitrate.toInt())
            .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            .build(),
        ).setRequestedAudioEncoderSettings(
          AudioEncoderSettings
            .Builder()
            .setBitrate(audioBitrateToUse)
            .build(),
        ).build()

    val isMediaTek = isMediaTekDeviceOrEncoder(videoMimeType)
    val primaryEncoderFactory = if (isMediaTek) vbrEncoderFactory else cbrEncoderFactory
    val fallbackEncoderFactory = if (isMediaTek) cbrEncoderFactory else vbrEncoderFactory

    val encoderFactory =
      object : Codec.EncoderFactory {
        override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec =
          primaryEncoderFactory.createForAudioEncoding(format, logSessionId)

        override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec {
          val targetFps = if (plan.outputFps > 0) plan.outputFps.toFloat() else state.originalFps
          var modifiedFormatBuilder = format.buildUpon()
          if (targetFps > 0f) {
            modifiedFormatBuilder.setFrameRate(targetFps)
          }
          if (format.colorInfo == null || !ColorInfo.isTransferHdr(format.colorInfo)) {
            modifiedFormatBuilder.setColorInfo(null)
          }
          val modifiedFormat = modifiedFormatBuilder.build()
          return try {
            primaryEncoderFactory.createForVideoEncoding(modifiedFormat, logSessionId)
          } catch (error: Exception) {
            fallbackEncoderFactory.createForVideoEncoding(modifiedFormat, logSessionId)
          }
        }

        override fun audioNeedsEncoding(): Boolean = primaryEncoderFactory.audioNeedsEncoding()
        override fun videoNeedsEncoding(): Boolean = primaryEncoderFactory.videoNeedsEncoding()
      }

    val videoEffects = mutableListOf<Effect>()
    if (plan.outputHeight > 0 && plan.outputHeight != state.originalHeight) {
      val aspectRatio =
        if (state.originalHeight > 0) {
          state.originalWidth.toFloat() / state.originalHeight
        } else {
          16f / 9f
        }
      var targetWidth = (plan.outputHeight * aspectRatio).toInt()
      var targetHeight = plan.outputHeight
      if (targetWidth % 2 != 0) targetWidth -= 1
      if (targetHeight % 2 != 0) targetHeight -= 1
      if (targetWidth > 0 && targetHeight > 0) {
        videoEffects +=
          Presentation.createForWidthAndHeight(targetWidth, targetHeight, Presentation.LAYOUT_SCALE_TO_FIT)
      }
    }

    if (plan.outputFps > 0 && state.originalFps > 0f && plan.outputFps != state.originalFps.toInt()) {
      videoEffects += FrameDropEffect.createSimpleFrameDropEffect(state.originalFps, plan.outputFps.toFloat())
    }

    val audioEffects =
      if (!plan.removeAudio && state.audioVolume != 1.0f) {
        listOf(GainProcessor(ConstantGainProvider(state.audioVolume)))
      } else {
        emptyList()
      }

    val editedMediaItem =
      EditedMediaItem
        .Builder(MediaItem.fromUri(inputUri))
        .setEffects(Effects(audioEffects, videoEffects))
        .setRemoveAudio(plan.removeAudio)
        .build()

    var hdrMode = Composition.HDR_MODE_KEEP_HDR
    if (Build.MANUFACTURER.equals("Google", ignoreCase = true) && Build.MODEL.contains("Pixel 10", ignoreCase = true)) {
      if (videoMimeType == MimeTypes.VIDEO_H265 || videoMimeType == MimeTypes.VIDEO_H264) {
        val hdrVideo = isHdr(context, inputUri)
        if (hdrVideo) {
          hdrMode = Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
          _uiState.update {
            it.copy(
              warnings =
                it.warnings + listOf("HDR video was tone-mapped to SDR to avoid encoder issues on this device."),
            )
          }
        }
      }
    }

    val composition =
      Composition
        .Builder(listOf(EditedMediaItemSequence.withAudioAndVideoFrom(listOf(editedMediaItem))))
        .setHdrMode(hdrMode)
        .build()

    return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
      val transformer =
        Transformer
          .Builder(context)
          .setVideoMimeType(videoMimeType)
          .setAudioMimeType(MimeTypes.AUDIO_AAC)
          .setAssetLoaderFactory(DefaultAssetLoaderFactory(context, decoderFactory, Clock.DEFAULT, null))
          .setEncoderFactory(encoderFactory)
          .addListener(
            object : Transformer.Listener {
              override fun onCompleted(
                composition: Composition,
                exportResult: ExportResult,
              ) {
                activeTransformer = null
                if (continuation.isActive) {
                  continuation.resume(outputFile)
                }
              }

              override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
              ) {
                activeTransformer = null
                val logStr = exportException.stackTraceToString()
                val isAudioDecoderError =
                  exportException.codecInfo?.isVideo == false ||
                    logStr.contains("AudioDecoder", ignoreCase = true) ||
                    logStr.contains("audio/eac3", ignoreCase = true) ||
                    logStr.contains("audio/ac3", ignoreCase = true) ||
                    logStr.contains("audio/dts", ignoreCase = true) ||
                    (exportException.message?.contains("AudioDecoder", ignoreCase = true) == true)

                if (!plan.removeAudio && isAudioDecoderError && !forceRemoveAudio) {
                  _uiState.update {
                    it.copy(
                      warnings = it.warnings + listOf("Audio decoding is not supported on this device. Retried compression with audio muted."),
                    )
                  }
                  if (continuation.isActive) {
                    viewModelScope.launch {
                      val retryResult = compressSingleVideo(
                        context = context,
                        state = state,
                        queueIndex = queueIndex,
                        queueSize = queueSize,
                        forceRemoveAudio = true,
                      )
                      continuation.resume(retryResult)
                    }
                  }
                  return
                }

                _uiState.update {
                  val isCodecError =
                    exportException.errorCode == ExportException.ERROR_CODE_DECODER_INIT_FAILED ||
                      exportException.errorCode == ExportException.ERROR_CODE_ENCODER_INIT_FAILED
                  val isMuxerError = exportException.errorCode == ExportException.ERROR_CODE_MUXING_FAILED
                  val errorMessage =
                    when {
                      isMuxerError && Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) ->
                        "This issue may be caused by your specific device. Try restarting the device."
                      isAudioDecoderError ->
                        "The audio codec of this video is not supported by your device hardware. Enable 'Remove Audio' to compress."
                      isCodecError ->
                        "This video codec is not supported by your device hardware."
                      else ->
                        exportException.localizedMessage ?: "Compression failed."
                    }
                  it.copy(
                    isCompressing = false,
                    error = errorMessage,
                    errorLog = exportException.stackTraceToString(),
                    progressAvailable = false,
                  )
                }
                if (continuation.isActive) {
                  continuation.resume(null)
                }
              }
            },
          ).build()

      activeTransformer = transformer
      val progressJob =
        viewModelScope.launch(Dispatchers.IO) {
          while (continuation.isActive) {
            val progressHolder = ProgressHolder()
            val progressState =
              runCatching {
                transformer.getProgress(progressHolder)
              }.getOrDefault(Transformer.PROGRESS_STATE_NOT_STARTED)
            val isAvailable = progressState == Transformer.PROGRESS_STATE_AVAILABLE
            val itemProgress =
              if (isAvailable) {
                (progressHolder.progress / 100f).coerceIn(
                  0f,
                  1f,
                )
              } else {
                _uiState.value.currentItemProgress
              }
            val overallProgress = ((queueIndex + itemProgress) / queueSize.toFloat()).coerceIn(0f, 0.999f)
            val currentSize = if (outputFile.exists()) outputFile.length() else 0L
            _uiState.update {
              it.copy(
                progress = overallProgress,
                currentItemProgress = itemProgress,
                progressAvailable = isAvailable,
                currentOutputSize = currentSize,
                currentQueueIndex = queueIndex,
              )
            }
            delay(200)
          }
        }

      continuation.invokeOnCancellation {
        progressJob.cancel()
        runCatching { transformer.cancel() }
        activeTransformer = null
      }

      runCatching {
        transformer.start(composition, outputFile.absolutePath)
      }.onFailure { error ->
        progressJob.cancel()
        activeTransformer = null
        val logStr = error.stackTraceToString()
        val isAudioDecoderError =
          logStr.contains("AudioDecoder", ignoreCase = true) ||
            logStr.contains("audio/eac3", ignoreCase = true) ||
            logStr.contains("audio/ac3", ignoreCase = true) ||
            logStr.contains("audio/dts", ignoreCase = true) ||
            (error.message?.contains("AudioDecoder", ignoreCase = true) == true)

        if (!plan.removeAudio && isAudioDecoderError && !forceRemoveAudio) {
          _uiState.update {
            it.copy(
              warnings = it.warnings + listOf("Audio decoding is not supported on this device. Retried compression with audio muted."),
            )
          }
          if (continuation.isActive) {
            viewModelScope.launch {
              val retryResult = compressSingleVideo(
                context = context,
                state = state,
                queueIndex = queueIndex,
                queueSize = queueSize,
                forceRemoveAudio = true,
              )
              continuation.resume(retryResult)
            }
          }
          return@suspendCancellableCoroutine
        }

        _uiState.update {
          it.copy(
            isCompressing = false,
            error = error.message ?: "Compression failed.",
            errorLog = error.stackTraceToString(),
            progressAvailable = false,
          )
        }
        if (continuation.isActive) {
          continuation.resume(null)
        }
      }
    }
  }

  private class ConstantGainProvider(private val gain: Float) : GainProcessor.GainProvider {
    override fun getGainFactorAtSamplePosition(samplePosition: Long, channelCount: Int): Float = gain

    override fun isUnityUntil(samplePosition: Long, channelCount: Int): Long =
      if (gain == 1f) C.TIME_END_OF_SOURCE else C.TIME_UNSET
  }

  private data class VideoTrackInfo(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val frameRate: Float,
  )

  private data class CompressionPlan(
    val outputVideoMimeType: String,
    val outputHeight: Int,
    val outputFps: Int,
    val removeAudio: Boolean,
    val warnings: List<String>,
    val blockingError: String?,
  )

  private fun getAudioTrackMimeType(context: Context, uri: Uri): String? {
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(context, uri, null)
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME)
        if (mime?.startsWith("audio/") == true) {
          return mime
        }
      }
      null
    } catch (_: Exception) {
      null
    } finally {
      extractor.release()
    }
  }

  private fun isAudioDecoderSupported(mimeType: String): Boolean {
    return runCatching {
      val list = MediaCodecList(MediaCodecList.ALL_CODECS)
      list.codecInfos.any { info ->
        if (info.isEncoder) return@any false
        info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
      }
    }.getOrDefault(false)
  }

  private fun getVideoTrackInfo(context: Context, uri: Uri): VideoTrackInfo? {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(context, uri, null)
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME)
        if (mime?.startsWith("video/") == true) {
          val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
          val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
          var frameRate = 0f
          if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            try {
              frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
            } catch (error: Exception) {
              try {
                frameRate = format.getFloat(MediaFormat.KEY_FRAME_RATE)
              } catch (ignored: Exception) {
              }
            }
          }
          return VideoTrackInfo(mime, width, height, frameRate)
        }
      }
    } catch (error: Exception) {
      error.printStackTrace()
    } finally {
      extractor.release()
    }
    return null
  }

  private fun buildCompressionPlan(
    inputUri: Uri,
    videoCodec: String,
    targetResolution: Int,
    targetFps: Int,
    removeAudio: Boolean,
    sourceWidth: Int,
    sourceHeight: Int,
    sourceFps: Float,
  ): CompressionPlan {
    var outputMime = videoCodec
    var outputHeight = targetResolution
    var outputFps = targetFps
    var effectiveRemoveAudio = removeAudio
    val warnings = mutableListOf<String>()

    if (!effectiveRemoveAudio) {
      val audioMime = getAudioTrackMimeType(getApplication(), inputUri)
      if (!audioMime.isNullOrBlank() && !isAudioDecoderSupported(audioMime)) {
        effectiveRemoveAudio = true
        val codecLabel = when (audioMime.lowercase(Locale.US)) {
          "audio/eac3" -> "E-AC-3 (Dolby Digital Plus)"
          "audio/ac3" -> "AC-3 (Dolby Digital)"
          "audio/ac4" -> "AC-4"
          "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
          else -> audioMime.substringAfter("/").uppercase(Locale.US)
        }
        warnings.add("Audio format ($codecLabel) is not supported for decoding on this device. Audio has been muted for compression.")
      }
    }

    val sourceInfo = getVideoTrackInfo(getApplication(), inputUri)
    val sourceMime = sourceInfo?.mimeType
    val resolvedWidth = sourceInfo?.width ?: sourceWidth
    val resolvedHeight = sourceInfo?.height ?: sourceHeight
    val resolvedFps = if ((sourceInfo?.frameRate ?: 0f) > 0f) sourceInfo!!.frameRate else sourceFps

    if (!sourceMime.isNullOrBlank() && resolvedWidth > 0 && resolvedHeight > 0) {
      val decoderSupported =
        isCodecConfigurationSupported(
          mimeType = sourceMime,
          width = resolvedWidth,
          height = resolvedHeight,
          fps = resolvedFps,
          encoder = false,
        )
      if (!decoderSupported) {
        return CompressionPlan(
          outputVideoMimeType = outputMime,
          outputHeight = outputHeight,
          outputFps = outputFps,
          removeAudio = effectiveRemoveAudio,
          warnings = warnings,
          blockingError =
            "This device cannot decode ${resolvedWidth}x${resolvedHeight}@${resolvedFps.toInt()}fps " +
              "${sourceMime.substringAfter("/").uppercase()} video.",
        )
      }
    }

    val attemptedConfigs = mutableListOf<Triple<String, Int, Int>>()
    fun isCurrentOutputSupported(mime: String, height: Int, fps: Int): Boolean {
      val safeHeight = if (height > 0) height else resolvedHeight
      val safeFps = if (fps > 0) fps else resolvedFps.toInt()
      val aspectRatio = if (resolvedHeight > 0) resolvedWidth.toFloat() / resolvedHeight else 16f / 9f
      var outputWidth = (safeHeight * aspectRatio).toInt().coerceAtLeast(2)
      var outputActualHeight = safeHeight.coerceAtLeast(2)
      if (outputWidth % 2 != 0) outputWidth -= 1
      if (outputActualHeight % 2 != 0) outputActualHeight -= 1
      attemptedConfigs.add(Triple(mime, outputActualHeight, safeFps))
      return isCodecConfigurationSupported(
        mimeType = mime,
        width = outputWidth,
        height = outputActualHeight,
        fps = safeFps.toFloat(),
        encoder = true,
      )
    }

    if (!isCurrentOutputSupported(outputMime, outputHeight, outputFps)) {
      if (outputMime != MimeTypes.VIDEO_H264 &&
        isCurrentOutputSupported(MimeTypes.VIDEO_H264, outputHeight, outputFps)
      ) {
        outputMime = MimeTypes.VIDEO_H264
        warnings.add("This device does not support the selected codec. Falling back to H.264.")
      } else {
        val fallbackHeights =
          listOf(1080, 720, 540, 480)
            .filter { it in 2..resolvedHeight }
            .ifEmpty { listOf(resolvedHeight.coerceAtLeast(2)) }
        val fallbackFps = listOf(30, 24)
        var supported = false

        for (heightCandidate in fallbackHeights) {
          for (fpsCandidate in fallbackFps) {
            if (isCurrentOutputSupported(MimeTypes.VIDEO_H264, heightCandidate, fpsCandidate)) {
              outputMime = MimeTypes.VIDEO_H264
              outputHeight = heightCandidate
              outputFps = fpsCandidate
              warnings.add(
                "Target quality is not supported on this device. Falling back to " +
                  "${heightCandidate}p @ ${fpsCandidate}fps.",
              )
              supported = true
              break
            }
          }
          if (supported) break
        }

        if (!supported) {
          val attempted =
            attemptedConfigs
              .joinToString(separator = ", ") { "${it.first.substringAfter("/")} ${it.second}p@${it.third}fps" }
          return CompressionPlan(
            outputVideoMimeType = outputMime,
            outputHeight = outputHeight,
            outputFps = outputFps,
            removeAudio = effectiveRemoveAudio,
            warnings = warnings,
            blockingError =
              "This device cannot encode any of the required configurations. " +
                "Attempted: $attempted.",
          )
        }
      }
    }

    return CompressionPlan(
      outputVideoMimeType = outputMime,
      outputHeight = outputHeight,
      outputFps = outputFps,
      removeAudio = effectiveRemoveAudio,
      warnings = warnings,
      blockingError = null,
    )
  }

  private fun isCodecConfigurationSupported(
    mimeType: String,
    width: Int,
    height: Int,
    fps: Float,
    encoder: Boolean,
  ): Boolean =
    try {
      val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
      val safeFps = kotlin.math.ceil(if (fps > 0f) fps.toDouble() else 30.0)
      codecList.codecInfos
        .asSequence()
        .filter { it.isEncoder == encoder }
        .filter { info -> info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
        .any { info ->
          try {
            val capabilities = info.getCapabilitiesForType(mimeType)
            val videoCaps = capabilities.videoCapabilities ?: return@any false
            videoCaps.areSizeAndRateSupported(width, height, safeFps) ||
              videoCaps.areSizeAndRateSupported(height, width, safeFps)
          } catch (error: Exception) {
            false
          }
        }
    } catch (error: Exception) {
      false
    }

  private fun isMediaTekDeviceOrEncoder(mimeType: String): Boolean {
    try {
      val hardware = Build.HARDWARE.lowercase()
      val board = Build.BOARD.lowercase()
      val manufacturer = Build.MANUFACTURER.lowercase()
      val soc =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          Build.SOC_MODEL.lowercase()
        } else {
          ""
        }

      if (hardware.contains("mediatek") ||
        board.contains("mediatek") ||
        manufacturer.contains("mediatek") ||
        soc.contains("mediatek") ||
        soc.contains("dimensity") ||
        hardware.matches(Regex(""".*mt\d{4}.*""")) ||
        board.matches(Regex(""".*mt\d{4}.*"""))
      ) {
        return true
      }

      val list = MediaCodecList(MediaCodecList.ALL_CODECS)
      for (info in list.codecInfos) {
        if (!info.isEncoder) continue
        if (info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
          val name = info.name.lowercase()
          if (name.contains("mtk") || name.contains("mediatek")) {
            return true
          }
        }
      }
    } catch (error: Exception) {
      error.printStackTrace()
    }
    return false
  }

  private fun persistCompressedFile(
    context: Context,
    compressedFile: File,
    state: VideoCompressorUiState,
  ): File {
    val destinationDir =
      when (state.saveMode) {
        VideoCompressorSaveMode.CURRENT_FOLDER -> {
          val currentFolder = state.sourceVideo?.path?.let { File(it).parentFile }
          if (CopyPasteOps.canUseDirectFileOperations() && currentFolder != null) {
            currentFolder
          } else {
            File(moviesCompressorPath())
          }
        }

        VideoCompressorSaveMode.MOVIES_COMPRESSOR -> File(moviesCompressorPath())
      }

    destinationDir.mkdirs()
    val outputName =
      state.originalName
        ?.substringBeforeLast(".")
        ?.let { "${it}_compressed.mp4" }
        ?: "Compressed_${System.currentTimeMillis()}.mp4"
    val finalFile = uniqueFile(File(destinationDir, outputName))
    compressedFile.copyTo(finalFile, overwrite = false)

    MediaScannerConnection.scanFile(context, arrayOf(finalFile.absolutePath), arrayOf("video/mp4"), null)
    MediaLibraryEvents.notifyChanged()
    return finalFile
  }

  private fun uniqueFile(file: File): File {
    if (!file.exists()) return file
    val baseName = file.nameWithoutExtension
    val extension = file.extension
    var index = 1
    while (true) {
      val candidate =
        File(
          file.parentFile,
          buildString {
            append(baseName)
            append("_")
            append(index)
            if (extension.isNotBlank()) {
              append(".")
              append(extension)
            }
          },
        )
      if (!candidate.exists()) return candidate
      index++
    }
  }

  private fun moviesCompressorPath(): String =
    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Compressor").absolutePath

  fun saveToUri(
    context: Context,
    targetUri: Uri,
  ) {
    val currentState = _uiState.value
    val compressedUri = currentState.compressedUri ?: return

    viewModelScope.launch(Dispatchers.IO) {
      try {
        val file = File(compressedUri.path ?: return@launch)
        if (!file.exists()) {
          _uiState.update { it.copy(error = "Compressed file was lost.") }
          return@launch
        }

        val outputStream =
          context.contentResolver.openOutputStream(targetUri)
            ?: throw IllegalStateException("Could not open destination stream.")
        outputStream.use { out ->
          file.inputStream().use { input -> input.copyTo(out) }
        }
      } catch (error: Exception) {
        _uiState.update { it.copy(error = "Save failed: ${error.message}") }
      }
    }
  }

  fun saveToGallery(context: Context) {
    val currentState = _uiState.value
    val compressedUri = currentState.compressedUri ?: return

    viewModelScope.launch(Dispatchers.IO) {
      try {
        val file = File(compressedUri.path ?: return@launch)
        if (!file.exists()) {
          _uiState.update { it.copy(error = "Compressed file was lost.") }
          return@launch
        }

        val outputName =
          currentState.originalName
            ?.substringBeforeLast(".")
            ?.let { "${it}_compressed.mp4" }
            ?: "Compressed_${System.currentTimeMillis()}.mp4"

        val values =
          ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outputName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")

            if (currentState.preserveMetadata) {
              applyPreservedMetadata(context, currentState, this)
            } else {
              put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            }

            if (!containsKey(MediaStore.Video.Media.DATE_ADDED)) {
              put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            }
            if (!containsKey(MediaStore.Video.Media.DATE_MODIFIED)) {
              put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
              currentState.preserveMetadata &&
              currentState.originalDate != null &&
              !containsKey(MediaStore.Video.Media.DATE_TAKEN)
            ) {
              put(MediaStore.Video.Media.DATE_TAKEN, currentState.originalDate)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              put(MediaStore.Video.Media.IS_PENDING, 1)
              put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Compressor")
            }
          }

        val collection =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
          } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
          }

        val itemUri = context.contentResolver.insert(collection, values)
        if (itemUri == null) {
          _uiState.update { it.copy(error = "Could not create gallery entry.") }
          return@launch
        }

        val outputStream =
          context.contentResolver.openOutputStream(itemUri)
            ?: throw IllegalStateException("Could not open gallery output stream.")
        outputStream.use { out ->
          file.inputStream().use { input -> input.copyTo(out) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          values.clear()
          values.put(MediaStore.Video.Media.IS_PENDING, 0)
          context.contentResolver.update(itemUri, values, null, null)
        }
      } catch (error: Exception) {
        _uiState.update { it.copy(error = "Save failed: ${error.message}") }
      }
    }
  }

  private fun checkSupportedCodecs() {
    val supported = mutableListOf(MimeTypes.VIDEO_H264)
    if (hasEncoder(MimeTypes.VIDEO_H265)) supported += MimeTypes.VIDEO_H265
    if (hasEncoder(MimeTypes.VIDEO_AV1)) supported += MimeTypes.VIDEO_AV1

    _uiState.update {
      val nextCodec =
        when {
          it.videoCodec == MimeTypes.VIDEO_H265 && !supported.contains(MimeTypes.VIDEO_H265) -> MimeTypes.VIDEO_H264
          supported.contains(it.videoCodec) -> it.videoCodec
          supported.contains(MimeTypes.VIDEO_H265) -> MimeTypes.VIDEO_H265
          else -> MimeTypes.VIDEO_H264
        }
      it.copy(
        supportedCodecs = supported,
        videoCodec = nextCodec,
      )
    }
  }

  private fun hasEncoder(mimeType: String): Boolean {
    return runCatching {
      val list = MediaCodecList(MediaCodecList.ALL_CODECS)
      list.codecInfos.any { info ->
        if (!info.isEncoder) return@any false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly) return@any false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
          info.name.lowercase().startsWith("c2.android")
        ) {
          return@any false
        }
        info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
      }
    }.getOrDefault(false)
  }

  private fun clearCache() {
    viewModelScope.launch(Dispatchers.IO) {
      runCatching {
        val outputDir = File(getApplication<Application>().cacheDir, "compressed_videos")
        if (outputDir.exists()) {
          outputDir.listFiles()?.forEach { runCatching { it.delete() } }
        }
      }
    }
  }

  private fun getAudioBitrate(
    context: Context,
    uri: Uri,
  ): Int {
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(context, uri, null)
      for (index in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(index)
        val mime = format.getString(MediaFormat.KEY_MIME)
        if (mime?.startsWith("audio/") == true && format.containsKey(MediaFormat.KEY_BIT_RATE)) {
          return format.getInteger(MediaFormat.KEY_BIT_RATE)
        }
      }
      0
    } catch (_: Exception) {
      0
    } finally {
      extractor.release()
    }
  }

  private fun isHdr(
    context: Context,
    uri: Uri,
  ): Boolean {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(context, uri)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val transfer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)
        transfer == "6" || transfer == "7"
      } else {
        false
      }
    } catch (_: Exception) {
      false
    } finally {
      runCatching { retriever.release() }
    }
  }

  private fun applyPreservedMetadata(
    context: Context,
    state: VideoCompressorUiState,
    values: ContentValues,
  ) {
    val sourceUri = state.sourceUri ?: return
    val projection =
      arrayOf(
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.DATE_TAKEN,
        MediaStore.Video.Media.TITLE,
        MediaStore.Video.Media.ARTIST,
        MediaStore.Video.Media.ALBUM,
        MediaStore.Video.Media.DESCRIPTION,
      )

    runCatching {
      context.contentResolver.query(sourceUri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use
        copyLongColumn(
          cursor,
          MediaStore.Video.Media.DATE_ADDED,
        )?.let { values.put(MediaStore.Video.Media.DATE_ADDED, it) }
        copyLongColumn(cursor, MediaStore.Video.Media.DATE_MODIFIED)?.let {
          values.put(MediaStore.Video.Media.DATE_MODIFIED, it)
        }
        copyLongColumn(
          cursor,
          MediaStore.Video.Media.DATE_TAKEN,
        )?.let { values.put(MediaStore.Video.Media.DATE_TAKEN, it) }
        copyStringColumn(cursor, MediaStore.Video.Media.TITLE)?.let { values.put(MediaStore.Video.Media.TITLE, it) }
        copyStringColumn(cursor, MediaStore.Video.Media.ARTIST)?.let { values.put(MediaStore.Video.Media.ARTIST, it) }
        copyStringColumn(cursor, MediaStore.Video.Media.ALBUM)?.let { values.put(MediaStore.Video.Media.ALBUM, it) }
        copyStringColumn(
          cursor,
          MediaStore.Video.Media.DESCRIPTION,
        )?.let { values.put(MediaStore.Video.Media.DESCRIPTION, it) }
      }
    }
  }

  private fun copyLongColumn(
    cursor: android.database.Cursor,
    column: String,
  ): Long? {
    val index = cursor.getColumnIndex(column)
    if (index == -1 || cursor.isNull(index)) return null
    return cursor.getLong(index)
  }

  private fun copyStringColumn(
    cursor: android.database.Cursor,
    column: String,
  ): String? {
    val index = cursor.getColumnIndex(column)
    if (index == -1 || cursor.isNull(index)) return null
    return cursor.getString(index)
  }

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = VideoCompressorViewModel(application) as T
      }
  }
}
