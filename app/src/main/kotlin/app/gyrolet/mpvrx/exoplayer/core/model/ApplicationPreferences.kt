package app.gyrolet.mpvrx.exoplayer.core.model

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

@Serializable
data class ApplicationPreferences(
    val appLanguage: String = "",
    val sortBy: Sort.By = Sort.By.TITLE,
    val sortOrder: Sort.Order = Sort.Order.ASCENDING,
    val themeConfig: ThemeConfig = ThemeConfig.SYSTEM,
    val shouldUseDynamicColors: Boolean = true,
    val shouldNavigateHomeOnTitleLongPress: Boolean = false,
    val shouldPreventScreenshots: Boolean = false,
    val shouldHideInRecents: Boolean = false,
    val shouldMarkLastPlayedMedia: Boolean = true,
    val shouldRestoreLastPlayedMediaInFolders: Boolean = false,
    val shouldIgnoreNoMediaFiles: Boolean = false,
    val isRecycleBinEnabled: Boolean = false,
    val excludeFolders: List<String> = emptyList(),
    val localFolderLastPlayedMediaUris: Map<String, String> = emptyMap(),
    val remoteFolderLastPlayedMediaPaths: Map<String, String> = emptyMap(),
    val mediaViewMode: MediaViewMode = MediaViewMode.FOLDERS,
    val mediaLayoutMode: MediaLayoutMode = MediaLayoutMode.LIST,
    val mediaLayoutScale: Float = DEFAULT_MEDIA_LAYOUT_SCALE,

    // 字段显示
    val shouldShowDurationField: Boolean = true,
    val shouldShowExtensionField: Boolean = false,
    val shouldShowPathField: Boolean = true,
    val shouldShowResolutionField: Boolean = false,
    val shouldShowSizeField: Boolean = false,
    val shouldShowThumbnailField: Boolean = true,
    val shouldShowPlayedProgress: Boolean = true,

    // 缩略图生成
    val thumbnailGenerationStrategy: ThumbnailGenerationStrategy = ThumbnailGenerationStrategy.FRAME_AT_PERCENTAGE,
    val thumbnailFramePosition: Float = DEFAULT_THUMBNAIL_FRAME_POSITION,
    val shouldCheckForUpdatesOnStartup: Boolean = false,
    val manualVideoPaths: List<String> = emptyList(),
    val pendingExternalVideoPaths: List<String> = emptyList(),
) {

    fun isPathExcluded(path: String): Boolean {
        if (path.isBlank()) return false

        return excludeFolders.any { excludedPath ->
            path == excludedPath || path.startsWith("$excludedPath/")
        }
    }

    fun normalizedMediaLayoutScale(): Float = mediaLayoutScale
        .coerceIn(MIN_MEDIA_LAYOUT_SCALE, MAX_MEDIA_LAYOUT_SCALE)
        .roundToStep(MEDIA_LAYOUT_SCALE_STEP)

    fun withMediaLayoutScale(scale: Float): ApplicationPreferences = copy(
        mediaLayoutScale = scale
            .coerceIn(MIN_MEDIA_LAYOUT_SCALE, MAX_MEDIA_LAYOUT_SCALE)
            .roundToStep(MEDIA_LAYOUT_SCALE_STEP),
    )

    companion object {
        const val DEFAULT_THUMBNAIL_FRAME_POSITION = 0.5f
        const val DEFAULT_MEDIA_LAYOUT_SCALE = 1f
        const val MIN_MEDIA_LAYOUT_SCALE = 0.75f
        const val MAX_MEDIA_LAYOUT_SCALE = 1.5f
        const val MEDIA_LAYOUT_SCALE_STEP = 0.05f
    }
}

private fun Float.roundToStep(step: Float): Float = (this / step).roundToInt() * step

