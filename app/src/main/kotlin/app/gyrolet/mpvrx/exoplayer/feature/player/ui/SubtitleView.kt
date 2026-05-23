package app.gyrolet.mpvrx.exoplayer.feature.player.ui

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.accessibility.CaptioningManager
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat.getSystemService
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.widget.AssSubtitleView as AssMediaSubtitleView
import app.gyrolet.mpvrx.exoplayer.core.data.repository.ExternalSubtitleFontSource
import app.gyrolet.mpvrx.exoplayer.core.model.Font
import app.gyrolet.mpvrx.exoplayer.core.model.SubtitleColor
import app.gyrolet.mpvrx.exoplayer.core.model.SubtitleEdgeStyle
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.toTypeface
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberCuesState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberTracksState
import app.gyrolet.mpvrx.exoplayer.feature.player.subtitle.AssHandlerRegistry
import app.gyrolet.mpvrx.exoplayer.feature.player.subtitle.SubtitleFontPolicy
import app.gyrolet.mpvrx.exoplayer.feature.player.subtitle.decideSubtitleFontPolicy

@OptIn(UnstableApi::class)
@Composable
fun SubtitleView(
    modifier: Modifier = Modifier,
    player: Player,
    isInPictureInPictureMode: Boolean,
    configuration: SubtitleConfiguration,
) {
    val cuesState = rememberCuesState(player)
    val assHandler by AssHandlerRegistry.handler.collectAsState()
    val textTracksState = rememberTracksState(player = player, trackType = C.TRACK_TYPE_TEXT)
    val isAssSubtitleSelected = textTracksState.tracks.any { track ->
        track.isSelected &&
            (0 until track.mediaTrackGroup.length).any { index ->
                val format = track.mediaTrackGroup.getFormat(index)
                format.sampleMimeType == MimeTypes.TEXT_SSA || format.codecs == MimeTypes.TEXT_SSA
            }
    }

    val subtitleFontPolicy = decideSubtitleFontPolicy(
        isAssSubtitleSelected = isAssSubtitleSelected,
        shouldUseSystemCaptionStyle = configuration.shouldUseSystemCaptionStyle,
        hasExternalFont = configuration.externalSubtitleFontSource != null,
    )

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            SubtitleView(context).apply {
                applySubtitleStyle(
                    configuration = configuration,
                    subtitleFontPolicy = subtitleFontPolicy,
                )
                setApplyEmbeddedStyles(configuration.shouldApplyEmbeddedStyles)
                applySubtitlePosition(
                    configuration = configuration,
                    subtitleFontPolicy = subtitleFontPolicy,
                )
            }
        },
        update = { subtitleView ->
            if (isAssSubtitleSelected) {
                assHandler?.let(subtitleView::syncAssSupport)
                    ?: subtitleView.clearAssSupport()
                subtitleView.setCues(emptyList())
            } else {
                subtitleView.clearAssSupport()
                subtitleView.setCues(cuesState.cues)
            }

            subtitleView.applySubtitleStyle(
                configuration = configuration,
                subtitleFontPolicy = subtitleFontPolicy,
            )
            subtitleView.setApplyEmbeddedStyles(configuration.shouldApplyEmbeddedStyles)
            subtitleView.applySubtitlePosition(
                configuration = configuration,
                subtitleFontPolicy = subtitleFontPolicy,
            )

            if (isInPictureInPictureMode) {
                subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
            } else {
                subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, configuration.textSize.toFloat())
            }
        },
    )
}

@Stable
data class SubtitleConfiguration(
    val shouldUseSystemCaptionStyle: Boolean,
    val shouldShowBackground: Boolean,
    val font: Font,
    val textSize: Int,
    val shouldUseBoldText: Boolean,
    val color: SubtitleColor,
    val edgeStyle: SubtitleEdgeStyle,
    val bottomPaddingFraction: Float,
    val shouldApplyEmbeddedStyles: Boolean,
    val externalSubtitleFontSource: ExternalSubtitleFontSource?,
)

@OptIn(UnstableApi::class)
private fun SubtitleView.syncAssSupport(handler: AssHandler) {
    val assSupportView = findAssSupportView()
    val currentHandler = assSupportView?.tag as? AssHandler
    if (currentHandler === handler) return

    assSupportView?.let(::removeView)
    this.withAssSupport(handler)
    findAssSupportView()?.tag = handler
}

@OptIn(UnstableApi::class)
private fun SubtitleView.clearAssSupport() {
    findAssSupportView()?.let(::removeView)
}

@OptIn(UnstableApi::class)
private fun SubtitleView.applySubtitleStyle(
    configuration: SubtitleConfiguration,
    subtitleFontPolicy: SubtitleFontPolicy,
) {
    val context = context
    val captioningManager = getSystemService(context, CaptioningManager::class.java) ?: return
    when (subtitleFontPolicy) {
        SubtitleFontPolicy.Ass,
        SubtitleFontPolicy.SystemCaptionStyle,
        -> {
            val systemCaptionStyle = CaptionStyleCompat.createFromCaptionStyle(captioningManager.userStyle)
            setStyle(systemCaptionStyle)
        }

        SubtitleFontPolicy.ExternalOrFallback -> {
            val baseTypeface = runCatching {
                configuration.externalSubtitleFontSource
                    ?.absolutePath
                    ?.let(Typeface::createFromFile)
            }.getOrNull() ?: configuration.font.toTypeface()
            val userStyle = CaptionStyleCompat(
                configuration.color.toArgb(),
                Color.BLACK.takeIf { configuration.shouldShowBackground } ?: Color.TRANSPARENT,
                Color.TRANSPARENT,
                configuration.edgeStyle.toCaptionEdgeType(),
                Color.BLACK,
                Typeface.create(
                    baseTypeface,
                    Typeface.BOLD.takeIf { configuration.shouldUseBoldText } ?: Typeface.NORMAL,
                ),
            )
            setStyle(userStyle)
            setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, configuration.textSize.toFloat())
        }
    }
}

@OptIn(UnstableApi::class)
private fun SubtitleView.applySubtitlePosition(
    configuration: SubtitleConfiguration,
    subtitleFontPolicy: SubtitleFontPolicy,
) {
    val bottomPaddingFraction = when (subtitleFontPolicy) {
        SubtitleFontPolicy.ExternalOrFallback -> configuration.bottomPaddingFraction
        SubtitleFontPolicy.Ass,
        SubtitleFontPolicy.SystemCaptionStyle,
        -> SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
    }
    setBottomPaddingFraction(bottomPaddingFraction)
}

private fun SubtitleColor.toArgb(): Int = when (this) {
    SubtitleColor.WHITE -> Color.WHITE
    SubtitleColor.YELLOW -> Color.YELLOW
    SubtitleColor.CYAN -> Color.CYAN
    SubtitleColor.GREEN -> Color.GREEN
}

private fun SubtitleEdgeStyle.toCaptionEdgeType(): Int = when (this) {
    SubtitleEdgeStyle.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
    SubtitleEdgeStyle.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
    SubtitleEdgeStyle.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
}

@OptIn(UnstableApi::class)
private fun SubtitleView.findAssSupportView(): AssMediaSubtitleView? = (0 until childCount).firstNotNullOfOrNull { index ->
    getChildAt(index) as? AssMediaSubtitleView
}
