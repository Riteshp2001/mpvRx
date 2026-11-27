package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.VideoSwipeAction
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.AppPickerSheet
import app.gyrolet.mpvrx.ui.browser.cards.containerColor
import app.gyrolet.mpvrx.ui.browser.cards.contentColor
import app.gyrolet.mpvrx.ui.browser.cards.icon
import app.gyrolet.mpvrx.ui.browser.cards.labelRes
import app.gyrolet.mpvrx.ui.components.themedSegmentedButtonColors
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.ui.utils.rememberAdjustmentHaptics
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import app.gyrolet.mpvrx.ui.components.AppSlider as Slider

@Serializable
object VideoSwipePreferencesScreen : ScrollableSettingsScreen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val preferences = koinInject<BrowserPreferences>()
    val separateAudio by preferences.separateAudioSwipeActions.collectAsState()
    var audioTabSelected by rememberSaveable { mutableStateOf(false) }
    val editingAudio = separateAudio && audioTabSelected
    val rightPreference = if (editingAudio) preferences.audioSwipeRight else preferences.videoSwipeRight
    val leftPreference = if (editingAudio) preferences.audioSwipeLeft else preferences.videoSwipeLeft
    val rightZonePreference =
      if (editingAudio) preferences.audioSwipeRightZonePercent else preferences.videoSwipeRightZonePercent
    val leftZonePreference =
      if (editingAudio) preferences.audioSwipeLeftZonePercent else preferences.videoSwipeLeftZonePercent
    val right by rightPreference.collectAsState()
    val left by leftPreference.collectAsState()
    val rightZonePercent by rightZonePreference.collectAsState()
    val leftZonePercent by leftZonePreference.collectAsState()
    val backStack = LocalBackStack.current
    val haptics = rememberAppHaptics()
    var editingRight by rememberSaveable { mutableStateOf<Boolean?>(null) }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              stringResource(R.string.pref_video_swipe_title),
              style = MaterialTheme.typography.titleLarge,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          },
          navigationIcon = {
            IconButton(onClick = { backStack.popSafely() }) { Icon(Icons.RoundedFilled.ArrowBack, null) }
          },
        )
      },
    ) { padding ->
      val (listState, highlight) =
        rememberSettingsSearchList(VideoSwipePreferencesScreen, MaterialTheme.colorScheme.primary)
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(padding).then(highlight),
        contentPadding =
          settingsContentPadding(
            androidx.compose.foundation.layout
              .PaddingValues(horizontal = 16.dp, vertical = 12.dp),
          ),
        verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
      ) {
        item(key = "separate-audio") {
          Surface(
            shape = rememberConnectedShape(0, 3, ConnectedLayout.Vertical),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
          ) {
            Column {
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_video_swipe_separate_audio),
                value = separateAudio,
                onValueChange = { enabled ->
                  if (enabled && !preferences.separateAudioSwipeActions.get()) {
                    // Start the audio set from the current video set so enabling it changes nothing yet.
                    preferences.audioSwipeRight.set(preferences.videoSwipeRight.get())
                    preferences.audioSwipeLeft.set(preferences.videoSwipeLeft.get())
                    preferences.audioSwipeRightZonePercent.set(preferences.videoSwipeRightZonePercent.get())
                    preferences.audioSwipeLeftZonePercent.set(preferences.videoSwipeLeftZonePercent.get())
                  }
                  preferences.separateAudioSwipeActions.set(enabled)
                },
                title = { Text(stringResource(R.string.pref_video_swipe_separate_audio)) },
                summary = { Text(stringResource(R.string.pref_video_swipe_separate_audio_summary)) },
              )
              if (separateAudio) {
                SingleChoiceSegmentedButtonRow(
                  modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                ) {
                  listOf(false, true).forEachIndexed { index, audio ->
                    SegmentedButton(
                      selected = audioTabSelected == audio,
                      onClick = {
                        if (audioTabSelected != audio) {
                          audioTabSelected = audio
                          haptics.selection(true)
                        }
                      },
                      shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                      colors = themedSegmentedButtonColors(),
                      icon = {
                        Icon(
                          if (audio) Icons.RoundedFilled.Audiotrack else Icons.RoundedFilled.PlayArrow,
                          null,
                          modifier = Modifier.size(16.dp),
                        )
                      },
                    ) {
                      Text(
                        stringResource(
                          if (audio) R.string.pref_video_swipe_target_audio else R.string.pref_video_swipe_target_video,
                        ),
                      )
                    }
                  }
                }
              }
            }
          }
        }
        item(key = "right-swipe") {
          Surface(
            shape = rememberConnectedShape(1, 3, ConnectedLayout.Vertical),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
          ) {
            SwipeDirectionPreference(
              right = true,
              action = right,
              zonePercent = rightZonePercent,
              onZonePercentChange = { rightZonePreference.set(it) },
              isAudio = editingAudio,
              modifier = Modifier.settingsSearchTarget(R.string.pref_video_swipe_right),
              onChange = { editingRight = true },
            )
          }
        }
        item(key = "left-swipe") {
          Surface(
            shape = rememberConnectedShape(2, 3, ConnectedLayout.Vertical),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
          ) {
            SwipeDirectionPreference(
              right = false,
              action = left,
              zonePercent = leftZonePercent,
              onZonePercentChange = { leftZonePreference.set(it) },
              isAudio = editingAudio,
              modifier = Modifier.settingsSearchTarget(R.string.pref_video_swipe_left),
              onChange = { editingRight = false },
            )
          }
        }
      }
    }

    editingRight?.let { isRight ->
      val selectedAction = if (isRight) right else left
      AppPickerSheet(
        onDismissRequest = { editingRight = null },
        title =
          stringResource(
            if (isRight) R.string.pref_video_swipe_choose_right else R.string.pref_video_swipe_choose_left,
          ),
        actions = {
          TextButton(onClick = { editingRight = null }) {
            Text(stringResource(R.string.generic_cancel))
          }
        },
      ) {
        Column(Modifier.selectableGroup()) {
          VideoSwipeAction.entries.forEach { action ->
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .selectable(selected = action == selectedAction, role = Role.RadioButton) {
                    if (action != selectedAction) {
                      if (isRight) rightPreference.set(action) else leftPreference.set(action)
                      haptics.selection(true)
                    }
                    editingRight = null
                  }.heightIn(min = 56.dp)
                  .padding(vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              RadioButton(selected = action == selectedAction, onClick = null)
              Icon(action.icon(), null, modifier = Modifier.size(24.dp))
              Text(stringResource(action.labelRes(null)), modifier = Modifier.weight(1f))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SwipeDirectionPreference(
  right: Boolean,
  action: VideoSwipeAction,
  zonePercent: Int,
  onZonePercentChange: (Int) -> Unit,
  modifier: Modifier = Modifier,
  isAudio: Boolean = false,
  onChange: () -> Unit,
) {
  val zoneRange = BrowserPreferences.VIDEO_SWIPE_ZONE_RANGE
  val configuredZonePercent = zonePercent.coerceIn(zoneRange)
  val zoneEnabled = action != VideoSwipeAction.None
  val effectiveZonePercent = if (zoneEnabled) configuredZonePercent else 0
  val zoneLabel = stringResource(R.string.pref_video_swipe_zone_width)
  val zoneValue = stringResource(R.string.update_progress_percent, effectiveZonePercent)
  val zoneSteps = zoneRange.last - zoneRange.first - 1
  val haptics =
    rememberAdjustmentHaptics(
      zoneRange.first.toFloat(),
      zoneRange.last.toFloat(),
      zoneSteps,
      listOf(BrowserPreferences.DEFAULT_VIDEO_SWIPE_ZONE_PERCENT.toFloat()),
    )
  Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Column(Modifier.weight(1f)) {
        Text(
          stringResource(if (right) R.string.pref_video_swipe_right else R.string.pref_video_swipe_left),
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          stringResource(action.labelRes(null)),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(onClick = onChange) {
        Icon(Icons.RoundedFilled.Edit, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.pref_video_swipe_change))
      }
    }
    Spacer(Modifier.height(16.dp))
    SwipeActionPreview(right, action, effectiveZonePercent, isAudio)
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(zoneLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
      Text(zoneValue, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
    Slider(
      value = configuredZonePercent.toFloat(),
      onValueChange = { value ->
        val updatedPercent = value.roundToInt().coerceIn(zoneRange)
        if (updatedPercent != configuredZonePercent) {
          onZonePercentChange(updatedPercent)
          haptics.move(configuredZonePercent.toFloat(), updatedPercent.toFloat())
        }
      },
      modifier =
        Modifier.fillMaxWidth().semantics {
          contentDescription = zoneLabel
          stateDescription = zoneValue
        },
      valueRange = zoneRange.first.toFloat()..zoneRange.last.toFloat(),
      steps = zoneSteps,
      enabled = zoneEnabled,
    )
  }
}

@Composable
private fun SwipeActionPreview(
  right: Boolean,
  action: VideoSwipeAction,
  zonePercent: Int,
  isAudio: Boolean,
) {
  val density = LocalDensity.current
  val offset by animateFloatAsState(
    targetValue = if (action == VideoSwipeAction.None) 0f else with(density) { 76.dp.toPx() } * if (right) 1f else -1f,
    animationSpec = AppMotion.spatial(AppMotion.Spatial.ExpressiveFast, snap()),
    label = "swipePreviewOffset",
  )
  val background by animateColorAsState(
    targetValue = action.containerColor(),
    animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
    label = "swipePreviewColor",
  )
  val zoneColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
    Text(
      text = stringResource(R.string.pref_video_swipe_zone_percent, zonePercent),
      modifier =
        Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceContainerHigh)
          .drawBehind {
            val zoneWidth = size.width * zonePercent / 100f
            drawRect(
              color = zoneColor,
              topLeft = Offset(if (right) 0f else size.width - zoneWidth, 0f),
              size = Size(zoneWidth, size.height),
            )
          }.padding(horizontal = 12.dp, vertical = 8.dp),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = if (right) TextAlign.Left else TextAlign.Right,
    )
    Box(
      Modifier
        .fillMaxWidth()
        .height(88.dp)
        .background(background)
        .clearAndSetSemantics { },
    ) {
      Crossfade(
        targetState = action,
        animationSpec = AppMotion.spatial(AppMotion.Effect.Alpha, snap()),
        modifier =
          Modifier
            .align(if (right) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight)
            .width(76.dp),
        label = "swipePreviewAction",
      ) { previewAction ->
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          Icon(previewAction.icon(), null, tint = previewAction.contentColor(), modifier = Modifier.size(28.dp))
        }
      }
      Row(
        modifier =
          Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = offset }
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Box(
          Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            if (isAudio) Icons.RoundedFilled.Audiotrack else Icons.RoundedFilled.PlayArrow,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Box(Modifier.fillMaxWidth(0.85f).height(8.dp).background(MaterialTheme.colorScheme.outlineVariant))
          Box(
            Modifier
              .fillMaxWidth(0.55f)
              .height(6.dp)
              .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
          )
        }
      }
    }
  }
}
