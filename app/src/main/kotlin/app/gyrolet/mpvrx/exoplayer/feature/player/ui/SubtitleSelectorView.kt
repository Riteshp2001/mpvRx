package app.gyrolet.mpvrx.exoplayer.feature.player.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlin.math.roundToLong
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.core.ui.components.ListSectionTitle
import app.gyrolet.mpvrx.exoplayer.core.ui.components.SubtitleStylePanel
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.getName
import app.gyrolet.mpvrx.exoplayer.feature.player.state.SubtitleOptionsEvent
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberSubtitleOptionsState
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberTracksState

@OptIn(UnstableApi::class)
@Composable
fun BoxScope.SubtitleSelectorView(
    modifier: Modifier = Modifier,
    shouldShow: Boolean,
    player: Player,
    onSelectSubtitleClick: () -> Unit,
    onAddOnlineSubtitleClick: (String) -> Unit,
    preferences: PlayerPreferences,
    onPreferencesChange: (PlayerPreferences) -> Unit,
    onEvent: (SubtitleOptionsEvent) -> Unit = {},
    onDismiss: () -> Unit,
) {
    OverlayView(
        modifier = modifier,
        shouldShow = shouldShow,
        title = stringResource(R.string.select_subtitle_track),
        testTag = "panel_subtitle_selector",
    ) {
        SubtitleSelectorContent(
            player = player,
            onSelectSubtitleClick = onSelectSubtitleClick,
            onAddOnlineSubtitleClick = onAddOnlineSubtitleClick,
            preferences = preferences,
            onPreferencesChange = onPreferencesChange,
            onEvent = onEvent,
            onDismiss = onDismiss,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun SubtitleSelectorContent(
    player: Player,
    onSelectSubtitleClick: () -> Unit,
    onAddOnlineSubtitleClick: (String) -> Unit,
    preferences: PlayerPreferences,
    onPreferencesChange: (PlayerPreferences) -> Unit,
    onEvent: (SubtitleOptionsEvent) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val subtitleTracksState = rememberTracksState(player, C.TRACK_TYPE_TEXT)
    val subtitleOptionsState = rememberSubtitleOptionsState(player, onEvent)
    var isOnlineSubtitleDialogVisible by remember { mutableStateOf(false) }
    var onlineSubtitleUrl by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
            .padding(horizontal = 24.dp)
            .selectableGroup(),
    ) {
        subtitleTracksState.tracks.forEachIndexed { index, track ->
            RadioButtonRow(
                isSelected = track.isSelected,
                text = track.mediaTrackGroup.getName(C.TRACK_TYPE_TEXT, index),
                testTag = "item_subtitle_$index",
                onClick = {
                    subtitleTracksState.switchTrack(index)
                    onDismiss()
                },
            )
        }
        RadioButtonRow(
            isSelected = subtitleTracksState.tracks.none { it.isSelected },
            text = stringResource(R.string.disable),
            testTag = "item_subtitle_disable",
            onClick = {
                subtitleTracksState.switchTrack(-1)
                onDismiss()
            },
        )
        Spacer(modifier = Modifier.size(16.dp))
        FilledTonalButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("btn_open_subtitle_picker"),
            onClick = {
                onSelectSubtitleClick()
                onDismiss()
            },
        ) {
            Text(text = stringResource(R.string.open_subtitle))
        }
        Spacer(modifier = Modifier.size(16.dp))
        FilledTonalButton(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("btn_add_online_subtitle"),
            onClick = {
                isOnlineSubtitleDialogVisible = true
            },
        ) {
            Text(text = stringResource(R.string.add_online_subtitle))
        }
        Spacer(modifier = Modifier.size(16.dp))
        DelayInput(
            value = subtitleOptionsState.delayMilliseconds,
            onValueChange = { subtitleOptionsState.setDelay(it) },
        )
        Spacer(modifier = Modifier.size(16.dp))
        SpeedInput(
            value = subtitleOptionsState.speedMultiplier,
            onValueChange = { subtitleOptionsState.setSpeed(it) },
        )
        Spacer(modifier = Modifier.size(16.dp))
        ListSectionTitle(text = stringResource(id = R.string.subtitle_appearance))
        SubtitleStylePanel(
            preferences = preferences,
            onPreferencesChange = onPreferencesChange,
        )
    }

    if (isOnlineSubtitleDialogVisible) {
        AlertDialog(
            modifier = Modifier.testTag("dialog_online_subtitle"),
            onDismissRequest = { isOnlineSubtitleDialogVisible = false },
            title = {
                Text(text = stringResource(R.string.add_online_subtitle))
            },
            text = {
                OutlinedTextField(
                    value = onlineSubtitleUrl,
                    onValueChange = { onlineSubtitleUrl = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_online_subtitle_url"),
                    label = {
                        Text(text = stringResource(R.string.online_subtitle_url))
                    },
                    placeholder = {
                        Text(text = "https://example.com/subtitle.srt")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("btn_confirm_online_subtitle"),
                    enabled = onlineSubtitleUrl.isNotBlank(),
                    onClick = {
                        onAddOnlineSubtitleClick(onlineSubtitleUrl.trim())
                        onlineSubtitleUrl = ""
                        isOnlineSubtitleDialogVisible = false
                        onDismiss()
                    },
                ) {
                    Text(text = stringResource(R.string.online_subtitle_add))
                }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag("btn_cancel_online_subtitle"),
                    onClick = { isOnlineSubtitleDialogVisible = false },
                ) {
                    Text(text = stringResource(R.string.generic_cancel))
                }
            },
        )
    }
}

@Composable
private fun DelayInput(
    value: Long,
    onValueChange: (Long) -> Unit,
) {
    var valueString by remember {
        mutableStateOf(if (value == 0L) "0" else "%.2f".format(value / 1000.0))
    }

    LaunchedEffect(value) {
        val currentValue = valueString.toDoubleOrNull() ?: 0.0
        if (currentValue == (value / 1000.0)) return@LaunchedEffect
        valueString = if (value == 0L) "0" else "%.2f".format(value / 1000.0)
    }

    NumberChooserInput(
        title = stringResource(R.string.delay),
        value = valueString,
        textFieldTestTag = "input_subtitle_delay",
        decrementButtonTestTag = "btn_subtitle_delay_decrement",
        incrementButtonTestTag = "btn_subtitle_delay_increment",
        suffix = { Text(text = "sec") },
        onValueChange = { newValue ->
            if (newValue.isBlank()) {
                valueString = ""
                onValueChange(0)
                return@NumberChooserInput
            }

            val cleanedValue = newValue.trimStart()

            if (cleanedValue == "-" || cleanedValue == ".") {
                valueString = cleanedValue
                return@NumberChooserInput
            }

            val decimalPattern = "^-?\\d*\\.?\\d{0,2}$".toRegex()
            if (!cleanedValue.matches(decimalPattern)) {
                return@NumberChooserInput
            }

            valueString = cleanedValue

            runCatching {
                val doubleValue = cleanedValue.toDoubleOrNull() ?: 0.0
                val milliseconds = (doubleValue * 1000).roundToLong()
                onValueChange(milliseconds)
            }
        },
        onIncrement = { onValueChange(value + 100) },
        onDecrement = { onValueChange(value - 100) },
    )
}

@Composable
private fun SpeedInput(
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    var valueString by remember {
        mutableStateOf(if (value == 1f) "1" else "%.2f".format(value))
    }

    LaunchedEffect(value) {
        val currentValue = valueString.toFloatOrNull() ?: 0.0
        if (currentValue == value) return@LaunchedEffect
        valueString = if (value == 1f) "1" else "%.2f".format(value)
    }

    NumberChooserInput(
        title = stringResource(R.string.speed),
        value = valueString,
        textFieldTestTag = "input_subtitle_speed",
        decrementButtonTestTag = "btn_subtitle_speed_decrement",
        incrementButtonTestTag = "btn_subtitle_speed_increment",
        suffix = { Text(text = "x") },
        onValueChange = { newValue ->
            if (newValue.isBlank()) {
                valueString = ""
                onValueChange(1f)
                return@NumberChooserInput
            }

            val cleanedValue = newValue.trimStart()

            if (cleanedValue == ".") {
                valueString = cleanedValue
                return@NumberChooserInput
            }

            val decimalPattern = "^\\d*\\.?\\d{0,2}$".toRegex()
            if (!cleanedValue.matches(decimalPattern)) {
                return@NumberChooserInput
            }

            valueString = cleanedValue

            runCatching {
                val floatValue = cleanedValue.toFloatOrNull() ?: 1f
                onValueChange(floatValue)
            }
        },
        onIncrement = { onValueChange(value + 0.1f) },
        onDecrement = { onValueChange(value - 0.1f) },
    )
}

@Composable
private fun NumberChooserInput(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    textFieldTestTag: String? = null,
    decrementButtonTestTag: String? = null,
    incrementButtonTestTag: String? = null,
    onValueChange: (String) -> Unit,
    onIncrement: () -> Unit = {},
    onDecrement: () -> Unit = {},
    suffix: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FilledTonalIconButton(
            onClick = { },
            modifier = Modifier
                .then(decrementButtonTestTag?.let(Modifier::testTag) ?: Modifier)
                .repeatingClickable(onClick = onDecrement),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_remove),
                contentDescription = null,
            )
        }
        OutlinedTextField(
            label = { Text(text = title) },
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .then(textFieldTestTag?.let(Modifier::testTag) ?: Modifier),
            suffix = suffix,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
            ),
        )
        FilledTonalIconButton(
            onClick = { },
            modifier = Modifier
                .then(incrementButtonTestTag?.let(Modifier::testTag) ?: Modifier)
                .repeatingClickable(onClick = onIncrement),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
            )
        }
    }
}

private fun Modifier.repeatingClickable(
    isEnabled: Boolean = true,
    maxDelayMillis: Long = 200,
    minDelayMillis: Long = 5,
    delayDecayFactor: Float = .20f,
    onClick: () -> Unit,
): Modifier = composed {
    val updatedOnClick by rememberUpdatedState(onClick)

    this.pointerInput(isEnabled) {
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val heldButtonJob = launch {
                    var currentDelayMillis = maxDelayMillis
                    while (isEnabled && down.pressed) {
                        updatedOnClick()
                        delay(currentDelayMillis)
                        val nextMillis = currentDelayMillis - (currentDelayMillis * delayDecayFactor)
                        currentDelayMillis = nextMillis.toLong().coerceAtLeast(minDelayMillis)
                    }
                }
                waitForUpOrCancellation()
                heldButtonJob.cancel()
            }
        }
    }
}
