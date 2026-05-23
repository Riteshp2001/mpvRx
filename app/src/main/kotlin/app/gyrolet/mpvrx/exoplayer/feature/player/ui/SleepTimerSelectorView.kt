package app.gyrolet.mpvrx.exoplayer.feature.player.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.feature.player.state.SleepTimerState

@Composable
fun BoxScope.SleepTimerSelectorView(
    modifier: Modifier = Modifier,
    shouldShow: Boolean,
    sleepTimerState: SleepTimerState,
    onDismiss: () -> Unit,
) {
    OverlayView(
        modifier = modifier,
        shouldShow = shouldShow,
        title = stringResource(R.string.sleep_timer),
        testTag = "panel_sleep_timer",
    ) {
        SleepTimerSelectorContent(
            sleepTimerState = sleepTimerState,
            onDismiss = onDismiss,
        )
    }
}

@Composable
fun SleepTimerSelectorContent(
    sleepTimerState: SleepTimerState,
    onDismiss: () -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val initialMinutes = if (sleepTimerState.isActive) {
        (sleepTimerState.remainingMillis / 60_000f).coerceAtLeast(1f)
    } else {
        30f
    }
    var sliderValue by remember { mutableFloatStateOf(initialMinutes) }
    val displayMinutes = sliderValue.toInt()
    val displayHours = displayMinutes / 60
    val displayRemainderMinutes = displayMinutes % 60

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
            .padding(horizontal = 24.dp),
    ) {
        if (sleepTimerState.isActive) {
            val remainMin = (sleepTimerState.remainingMillis / 60_000L).toInt()
            val remainSec = ((sleepTimerState.remainingMillis % 60_000L) / 1000L).toInt()
            Text(
                text = "${stringResource(R.string.sleep_timer_remaining)}: ${String.format("%d:%02d", remainMin, remainSec)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }

        Text(
            text = when {
                displayMinutes == 0 -> stringResource(R.string.sleep_timer_off)
                displayHours > 0 -> String.format("%dh %02dmin", displayHours, displayRemainderMinutes)
                else -> stringResource(R.string.sleep_timer_minutes, displayMinutes)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )

        Slider(
            modifier = Modifier.testTag("slider_sleep_timer"),
            value = sliderValue,
            onValueChange = {
                sliderValue = it
                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            },
            valueRange = 0f..300f,
        )

        Spacer(modifier = Modifier.size(16.dp))

        FilledTonalButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("btn_sleep_timer_confirm"),
            onClick = {
                if (displayMinutes > 0) {
                    sleepTimerState.start(displayMinutes)
                } else {
                    sleepTimerState.cancel()
                }
                onDismiss()
            },
        ) {
            Text(text = stringResource(R.string.generic_ok))
        }

        if (sleepTimerState.isActive) {
            Spacer(modifier = Modifier.size(8.dp))
            FilledTonalButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_sleep_timer_off"),
                onClick = {
                    sleepTimerState.cancel()
                    onDismiss()
                },
            ) {
                Text(text = stringResource(R.string.sleep_timer_off))
            }
        }
    }
}
