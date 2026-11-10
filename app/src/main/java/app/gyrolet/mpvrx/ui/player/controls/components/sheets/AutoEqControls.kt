package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.AppPickerSheet
import app.gyrolet.mpvrx.presentation.components.AppTextField
import app.gyrolet.mpvrx.ui.components.IconSwitch
import app.gyrolet.mpvrx.ui.components.themedSegmentedButtonColors
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.AutoEq
import app.gyrolet.mpvrx.ui.player.AutoEqEntry
import app.gyrolet.mpvrx.ui.player.EqTargetPoint
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.log10

@Composable
internal fun AutoEqControls(enabled: Boolean) {
  val context = LocalContext.current
  val preferences = koinInject<AudioPreferences>()
  val savedProfile by preferences.autoEqProfile.collectAsState()
  val correctionEnabled by preferences.autoEqEnabled.collectAsState()
  val profile = remember(savedProfile) { AutoEq.restore(savedProfile) }
  val unavailable by PlaybackSession.autoEqUnavailable.collectAsState()
  val title = stringResource(R.string.autoeq_title)
  var showPicker by rememberSaveable { mutableStateOf(false) }
  var showReference by rememberSaveable { mutableStateOf(false) }
  var indexAttempt by remember { mutableIntStateOf(0) }
  var loading by remember { mutableStateOf(false) }
  var failed by remember { mutableStateOf(false) }
  var downloadJob by remember { mutableStateOf<Job?>(null) }
  val scope = rememberCoroutineScope()

  Row(
    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(stringResource(R.string.autoeq_headphone_correction), style = MaterialTheme.typography.titleMedium)
      Text(
        stringResource(
          when {
            !correctionEnabled || profile == null -> R.string.player_sheets_off
            !enabled -> R.string.autoeq_correction_bypassed
            else -> R.string.autoeq_correction_enabled
          },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    IconSwitch(
      checked = correctionEnabled && profile != null,
      enabled = enabled,
      onCheckedChange = { checked ->
        if (checked && profile == null) {
          failed = false
          showPicker = true
        } else preferences.autoEqEnabled.set(checked)
      },
      modifier = Modifier.semantics { contentDescription = title },
    )
  }
  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
  Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)
      .clickable { failed = false; showPicker = true }
      .padding(vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(Icons.RoundedFilled.Search, contentDescription = null, modifier = Modifier.size(22.dp))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        profile?.entry?.name ?: stringResource(R.string.autoeq_choose_headphones),
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        profile?.entry?.measurementSource ?: stringResource(R.string.autoeq_no_headphones),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Icon(Icons.RoundedFilled.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
  }
  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
  profile?.let {
    Text(
      stringResource(R.string.autoeq_preset_details, it.preampDb, it.bands.size),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
  if (enabled && correctionEnabled && (unavailable || (savedProfile.isNotBlank() && profile == null))) {
    Text(
      stringResource(R.string.autoeq_unavailable),
      color = MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
  TextButton(onClick = { showReference = true }, modifier = Modifier.padding(top = 12.dp)) {
    Text(stringResource(R.string.autoeq_reference_curves))
    Icon(Icons.RoundedFilled.ChevronRight, null, modifier = Modifier.size(18.dp).padding(start = 4.dp))
  }

  if (showReference) {
    AppPickerSheet(
      title = stringResource(R.string.autoeq_reference_target),
      usePlayerSheet = true,
      onDismissRequest = { showReference = false },
    ) { HarmanTargetReference() }
  }

  if (showPicker) {
    val entries by produceState<List<AutoEqEntry>?>(null, context, indexAttempt) {
      value = try {
        AutoEq.entries(context)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        failed = true
        emptyList()
      }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val matches by produceState<List<AutoEqEntry>?>(null, query, entries) {
      value = null
      val available = entries ?: return@produceState
      delay(100)
      val search = query.trim()
      value = withContext(Dispatchers.Default) {
        available.filter { it.name.contains(search, ignoreCase = true) || it.measurementSource.contains(search, ignoreCase = true) }
      }
    }
    AppPickerSheet(
      title = stringResource(R.string.autoeq_choose_headphones),
      usePlayerSheet = true,
      scrollContent = false,
      onDismissRequest = {
        downloadJob?.cancel()
        loading = false
        showPicker = false
      },
    ) {
      AppTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        enabled = !loading,
        label = { Text(stringResource(R.string.autoeq_search)) },
        modifier = Modifier.fillMaxWidth(),
      )
      if (failed) {
        Text(
          stringResource(R.string.autoeq_download_failed),
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(vertical = 8.dp),
        )
        TextButton(onClick = { failed = false; indexAttempt++ }) {
          Text(stringResource(R.string.ui_retry))
        }
      }
      if (loading || entries == null || matches == null) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          Text(stringResource(if (loading) R.string.autoeq_loading_preset else R.string.autoeq_searching), style = MaterialTheme.typography.bodyMedium)
        }
      }
      if (!failed && matches?.isEmpty() == true) {
        Text(stringResource(R.string.autoeq_no_matches), modifier = Modifier.padding(vertical = 12.dp))
      }
      LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).selectableGroup()) {
        items(matches.orEmpty(), key = { it.url }) { entry ->
          ListItem(
            headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(entry.measurementSource) },
            trailingContent = { RadioButton(selected = profile?.entry?.url == entry.url, onClick = null, enabled = !loading) },
            modifier = Modifier.selectable(
              selected = profile?.entry?.url == entry.url,
              enabled = !loading,
              role = Role.RadioButton,
              onClick = {
                loading = true
                failed = false
                downloadJob = scope.launch {
                  try {
                    val downloaded = AutoEq.download(entry)
                    ensureActive()
                    preferences.autoEqProfile.set(downloaded.saved())
                    preferences.autoEqEnabled.set(true)
                    showPicker = false
                  } catch (cancellation: CancellationException) {
                    throw cancellation
                  } catch (_: Exception) {
                    failed = true
                  } finally {
                    loading = false
                  }
                }
              },
            ),
          )
        }
      }
    }
  }
}

@Composable
private fun HarmanTargetReference() {
  val context = LocalContext.current
  val preferences = koinInject<AudioPreferences>()
  val inEar by preferences.eqTargetInEar.collectAsState()
  val points by produceState(emptyList<EqTargetPoint>(), inEar) {
    value = try {
      AutoEq.target(context, inEar)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      emptyList()
    }
  }
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
    listOf(false, true).forEachIndexed { index, targetInEar ->
      SegmentedButton(
        selected = inEar == targetInEar,
        onClick = { preferences.eqTargetInEar.set(targetInEar) },
        shape = SegmentedButtonDefaults.itemShape(index, 2),
        colors = themedSegmentedButtonColors(),
      ) {
        Text(stringResource(if (targetInEar) R.string.autoeq_harman_in_ear else R.string.autoeq_harman_over_ear))
      }
    }
  }
  val label = stringResource(if (inEar) R.string.autoeq_harman_in_ear else R.string.autoeq_harman_over_ear)
  val lineColor = MaterialTheme.colorScheme.primary
  val gridColor = MaterialTheme.colorScheme.outlineVariant
  Canvas(
    Modifier.fillMaxWidth().height(160.dp).padding(vertical = 16.dp)
      .semantics { contentDescription = label },
  ) {
    if (points.size < 2) return@Canvas
    val low = minOf(0.0, points.minOf { it.response })
    val high = maxOf(1.0, points.maxOf { it.response })
    fun x(frequency: Double) = ((log10(frequency.coerceIn(20.0, 20000.0)) - log10(20.0)) / 3.0 * size.width).toFloat()
    fun y(response: Double) = ((high - response) / (high - low) * size.height).toFloat()
    drawLine(gridColor, Offset(0f, y(0.0)), Offset(size.width, y(0.0)))
    val path = Path()
    points.filter { it.frequency in 20.0..20000.0 }.forEachIndexed { index, point ->
      if (index == 0) path.moveTo(x(point.frequency), y(point.response))
      else path.lineTo(x(point.frequency), y(point.response))
    }
    drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
  }
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text("20 Hz", style = MaterialTheme.typography.labelSmall)
    Text("20 kHz", style = MaterialTheme.typography.labelSmall)
  }
}