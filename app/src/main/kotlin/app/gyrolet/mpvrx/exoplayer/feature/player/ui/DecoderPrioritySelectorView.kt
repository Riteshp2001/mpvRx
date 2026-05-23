package app.gyrolet.mpvrx.exoplayer.feature.player.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.exoplayer.core.model.DecoderPriority
import app.gyrolet.mpvrx.R

@Composable
fun BoxScope.DecoderPrioritySelectorView(
    modifier: Modifier = Modifier,
    shouldShow: Boolean,
    currentDecoderPriority: DecoderPriority,
    onDecoderPriorityClick: (DecoderPriority) -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayView(
        modifier = modifier,
        shouldShow = shouldShow,
        title = stringResource(R.string.decoder_priority),
        testTag = "panel_decoder_priority",
    ) {
        DecoderPrioritySelectorContent(
            currentDecoderPriority = currentDecoderPriority,
            onDecoderPriorityClick = onDecoderPriorityClick,
            onDismiss = onDismiss,
        )
    }
}

@Composable
fun DecoderPrioritySelectorContent(
    currentDecoderPriority: DecoderPriority,
    onDecoderPriorityClick: (DecoderPriority) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
            .padding(horizontal = 24.dp)
            .selectableGroup(),
    ) {
        DecoderPriority.entries.forEach { decoderPriority ->
            RadioButtonRow(
                isSelected = decoderPriority == currentDecoderPriority,
                text = decoderPriority.shortName(),
                testTag = "btn_decoder_${decoderPriority.logSuffix()}",
                onClick = {
                    onDecoderPriorityClick(decoderPriority)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun DecoderPriority.shortName(): String = when (this) {
    DecoderPriority.AUTOMATIC -> stringResource(R.string.auto_hw_decoder)
    DecoderPriority.AUTOMATIC_PREFER_DEVICE -> stringResource(R.string.auto_hw_plus_decoder)
    DecoderPriority.DEVICE_ONLY -> stringResource(R.string.hw_decoder)
    DecoderPriority.PREFER_DEVICE -> stringResource(R.string.hw_plus_decoder)
    DecoderPriority.PREFER_APP -> stringResource(R.string.sw_decoder)
}

private fun DecoderPriority.logSuffix(): String = when (this) {
    DecoderPriority.AUTOMATIC -> "auto_hw"
    DecoderPriority.AUTOMATIC_PREFER_DEVICE -> "auto_hw_plus"
    DecoderPriority.DEVICE_ONLY -> "hw"
    DecoderPriority.PREFER_DEVICE -> "hw_plus"
    DecoderPriority.PREFER_APP -> "sw"
}
