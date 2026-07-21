package app.gyrolet.mpvrx.ui.player.controls.components.panels

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.panelCardsColors
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlCodecPreference
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpOptionSettings
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpOptionsBuilder
import app.gyrolet.mpvrx.ui.theme.spacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YtdlpPanel(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var isRunning by remember { mutableStateOf(false) }

  val ytdlPreferences = koinInject<YtdlPreferences>()
  val ytdlQuality by ytdlPreferences.ytdlQuality.collectAsState()
  val preferH264 by ytdlPreferences.preferH264.collectAsState()
  val codecPreference by ytdlPreferences.codecPreference.collectAsState()
  val writeSubs by ytdlPreferences.writeSubs.collectAsState()
  val writeAutoSubs by ytdlPreferences.writeAutoSubs.collectAsState()

  val ytdlDir = remember { YtdlpManager.getYtdlDir(context) }
  var hasYtdlp by remember { mutableStateOf(File(ytdlDir, "yt-dlp").exists()) }

  LaunchedEffect(isRunning) {
    if (!isRunning) {
      hasYtdlp = File(ytdlDir, "yt-dlp").exists()
    }
  }

  val qualityLabel = remember(ytdlQuality) {
    if (ytdlQuality == -1) "Any" else "${ytdlQuality}p"
  }

  DraggablePanel(
    modifier = modifier,
    header = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = MaterialTheme.spacing.medium)
          .padding(top = MaterialTheme.spacing.small, bottom = MaterialTheme.spacing.extraSmall),
      ) {
        Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_yt_dlp_manager),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold
        )
        IconButton(onClick = onDismissRequest) {
          Icon(Icons.RoundedFilled.Close, null, modifier = Modifier.size(24.dp))
        }
      }
    }
  ) {
    Column(
      modifier = Modifier
        .padding(MaterialTheme.spacing.medium)
        .animateContentSize(),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
      // Settings Panel
        
        // Compact Status Indicator
        Surface(
          color = if (hasYtdlp) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.errorContainer,
          shape = RoundedCornerShape(16.dp),
          tonalElevation = 0.dp,
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
          ) {
            Icon(
              if (hasYtdlp) Icons.RoundedFilled.CheckCircle else Icons.RoundedFilled.CloudDownload,
              contentDescription = null,
              modifier = Modifier.size(20.dp),
              tint = if (hasYtdlp) MaterialTheme.colorScheme.onPrimaryContainer
                     else MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
              text = if (hasYtdlp) "yt-dlp core is installed & active" else "yt-dlp core not installed",
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
              color = if (hasYtdlp) MaterialTheme.colorScheme.onPrimaryContainer
                      else MaterialTheme.colorScheme.onErrorContainer,
            )
          }
        }

        // Quick Quality Chip Panel
        val cardsColors = panelCardsColors()
        Surface(
          shape = MaterialTheme.shapes.large,
          color = cardsColors.containerColor,
          tonalElevation = 0.dp,
          border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
          ),
        ) {
          Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
          ) {
            Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_quick_quality_selection),
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = MaterialTheme.spacing.extraSmall),
            )
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
              modifier = Modifier.fillMaxWidth()
            ) {
              val quickQualities = listOf(-1 to "Any", 1080 to "1080p", 720 to "720p", 480 to "480p")
              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
              ) {
                quickQualities.forEach { (level, label) ->
                  FilterChip(
                    selected = ytdlQuality == level,
                    onClick = {
                      ytdlPreferences.ytdlQuality.set(level)
                      updateFormatString(ytdlPreferences)
                    },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = if (ytdlQuality == level) {
                      { Icon(Icons.RoundedFilled.Check, null, modifier = Modifier.size(14.dp)) }
                    } else null,
                  )
                }
              }
              
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(start = 4.dp)
              ) {
                Text(
                  text = qualityLabel,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.ExtraBold,
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
              }
            }
          }
        }

        Surface(
          shape = MaterialTheme.shapes.large,
          color = cardsColors.containerColor,
          tonalElevation = 0.dp,
          border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
          ),
        ) {
          Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
          ) {
            Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_codec_preset),
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = MaterialTheme.spacing.extraSmall),
            )
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              listOf(
                YtdlCodecPreference.AUTO,
                YtdlCodecPreference.H264,
                YtdlCodecPreference.VP9,
                YtdlCodecPreference.VP9_PROFILE2,
                YtdlCodecPreference.AV1,
              ).forEach { codec ->
                FilterChip(
                  selected = codecPreference == codec || (codec == YtdlCodecPreference.H264 && preferH264),
                  onClick = {
                    ytdlPreferences.codecPreference.set(codec)
                    ytdlPreferences.preferH264.set(codec == YtdlCodecPreference.H264)
                    updateFormatString(ytdlPreferences)
                  },
                  label = { Text(codec.title, style = MaterialTheme.typography.labelSmall) },
                  leadingIcon = if (codecPreference == codec) {
                    { Icon(Icons.RoundedFilled.Check, null, modifier = Modifier.size(14.dp)) }
                  } else null,
                )
              }
            }
          }
        }

        // Subtitles Switches Card
        Surface(
          shape = MaterialTheme.shapes.large,
          color = cardsColors.containerColor,
          tonalElevation = 0.dp,
          border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
          ),
        ) {
          Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_quick_subtitle_config),
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = MaterialTheme.spacing.extraSmall, bottom = 2.dp),
            )
            
            // Subtitle download toggle
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_download_subtitles), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_fetch_subs_from_stream_sources), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Switch(
                checked = writeSubs,
                onCheckedChange = { ytdlPreferences.writeSubs.set(it) }
              )
            }
            
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            
            // Auto subtitles toggle
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_auto_generated_captions), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_include_auto_captions_transcripts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Switch(
                checked = writeAutoSubs,
                onCheckedChange = { ytdlPreferences.writeAutoSubs.set(it) }
              )
            }
          }
        }

        // Installer Buttons
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)
        ) {
          Button(
            onClick = {
              scope.launch {
                isRunning = true
                YtdlpManager.runInstall(context) {}
                isRunning = false
              }
            },
            enabled = !isRunning,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.weight(1f)
          ) {
            Icon(Icons.RoundedFilled.CloudDownload, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_install_core))
          }

          OutlinedButton(
            onClick = {
              scope.launch {
                isRunning = true
                YtdlpManager.runUpdate(context) {}
                isRunning = false
              }
            },
            enabled = !isRunning && hasYtdlp,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.weight(1f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
          ) {
            Icon(Icons.RoundedFilled.Update, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_update_core))
          }
      }
    }
  }
}

private fun updateFormatString(prefs: YtdlPreferences) {
  prefs.ytdlFormat.set(
    YtdlpOptionsBuilder.buildFormat(
      YtdlpOptionSettings(
        codecPreference = prefs.codecPreference.get(),
        legacyPreferH264 = prefs.preferH264.get(),
        maxHeight = prefs.ytdlQuality.get(),
        maxFps = prefs.maxFps.get(),
        hdrPreference = prefs.hdrPreference.get(),
        containerPreference = prefs.containerPreference.get(),
        audioPreference = prefs.audioPreference.get(),
      ),
    ),
  )
}
