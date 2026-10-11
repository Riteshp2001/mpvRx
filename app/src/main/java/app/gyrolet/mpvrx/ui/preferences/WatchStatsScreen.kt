package app.gyrolet.mpvrx.ui.preferences

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.repository.WatchMediaStats
import app.gyrolet.mpvrx.repository.WatchStatsRepository
import app.gyrolet.mpvrx.repository.WatchStatsSnapshot
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import java.io.File
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileWatchStatistics() {
  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val isTablet = configuration.smallestScreenWidthDp >= 600
  val repository = koinInject<WatchStatsRepository>()
  val scope = rememberCoroutineScope()
  val revision by repository.revision.collectAsStateWithLifecycle()
  val stats by produceState(WatchStatsSnapshot(), repository, revision) { value = repository.snapshot() }
  var confirmReset by rememberSaveable { mutableStateOf(false) }
  var mediaFilter by rememberSaveable { mutableStateOf(0) }

  val horizontalPadding = if (isTablet) 24.dp else 16.dp

  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.weight(1f),
      ) {
        Text(
          text = stringResource(R.string.watch_stats_title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      TextButton(
        enabled = stats.totalSeconds > 0 || stats.sessions > 0 || stats.media.isNotEmpty(),
        onClick = { confirmReset = true },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
      ) {
        Text(
          text = stringResource(R.string.watch_stats_reset),
          style = MaterialTheme.typography.labelMedium,
        )
      }
    }

    // Bento Grid Section
    WatchTimeBentoCard(stats)
    WatchSplitBentoCard(stats)
    WeeklyActivityBentoCard(stats.days)

    val topMedia =
      remember(stats.media, mediaFilter) {
        stats.media.entries
          .filter { it.value.seconds > 0 && (mediaFilter == 0 || it.value.isAudio == (mediaFilter == 2)) }
          .sortedByDescending { it.value.seconds }
          .take(8)
          .map { it.value }
      }

    TopMediaLeaderboardBentoCard(
      topMedia = topMedia,
      mediaFilter = mediaFilter,
      onFilterChange = { mediaFilter = it },
    )
  }

  if (confirmReset) {
    AlertDialog(
      onDismissRequest = { confirmReset = false },
      title = { Text(stringResource(R.string.watch_stats_reset)) },
      text = { Text(stringResource(R.string.watch_stats_reset_confirm)) },
      confirmButton = {
        TextButton(
          onClick = {
            confirmReset = false
            scope.launch { repository.clear() }
          },
        ) {
          Text(
            stringResource(R.string.watch_stats_reset),
            color = MaterialTheme.colorScheme.error,
          )
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmReset = false }) {
          Text(stringResource(R.string.generic_cancel))
        }
      },
    )
  }
}

@Composable
private fun BentoCard(
  modifier: Modifier = Modifier,
  shape: RoundedCornerShape = AppShapeScale.large,
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
  border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
  content: @Composable () -> Unit,
) {
  Surface(
    modifier = modifier,
    shape = shape,
    color = containerColor,
    border = border,
    content = content,
  )
}

@Composable
private fun WatchTimeBentoCard(stats: WatchStatsSnapshot) {
  BentoCard(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(36.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.RoundedFilled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
            }
          }
          Column {
            Text(
              text = stringResource(R.string.watch_stats_total_time).uppercase(Locale.getDefault()),
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
              letterSpacing = 1.sp,
            )
            Text(
              text = "Overall Playback",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.surfaceContainerHighest,
          contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
          Text(
            text = stringResource(R.string.watch_stats_sessions, stats.sessions),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
          )
        }
      }

      Text(
        text = formatHumanDuration(stats.totalSeconds),
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

@Composable
private fun WatchSplitBentoCard(stats: WatchStatsSnapshot) {
  val total = stats.videoSeconds + stats.audioSeconds
  val videoFraction = if (total > 0) stats.videoSeconds.toFloat() / total.toFloat() else 0.5f
  val videoPercent = if (total > 0) ((stats.videoSeconds * 100f) / total).toInt() else 0
  val audioPercent = if (total > 0) 100 - videoPercent else 0

  BentoCard(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(36.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.RoundedFilled.SmartDisplay,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
            }
          }
          Text(
            text = "Media Breakdown",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
        }
      }

      // Dual segment capsule bar
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(14.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.surfaceContainerHighest),
      ) {
        if (total > 0) {
          Row(modifier = Modifier.fillMaxSize()) {
            if (videoFraction > 0f) {
              Box(
                modifier = Modifier
                  .weight(videoFraction.coerceAtLeast(0.01f))
                  .fillMaxHeight()
                  .background(MaterialTheme.colorScheme.primary),
              )
            }
            if (1f - videoFraction > 0f) {
              Box(
                modifier = Modifier
                  .weight((1f - videoFraction).coerceAtLeast(0.01f))
                  .fillMaxHeight()
                  .background(MaterialTheme.colorScheme.tertiary),
              )
            }
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
      ) {
        val videoShape =
          rememberConnectedShape(
            index = 0,
            itemCount = 2,
            layout = ConnectedLayout.Horizontal,
          )
        val audioShape =
          rememberConnectedShape(
            index = 1,
            itemCount = 2,
            layout = ConnectedLayout.Horizontal,
          )

        // Connected video tile
        Surface(
          modifier = Modifier.weight(1f),
          shape = videoShape,
          color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Box(
              modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            )
            Column {
              Text(
                text = stringResource(R.string.watch_stats_video),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  text = formatHumanDuration(stats.videoSeconds),
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                )
                if (total > 0) {
                  Text(
                    text = "($videoPercent%)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }

        // Connected audio tile
        Surface(
          modifier = Modifier.weight(1f),
          shape = audioShape,
          color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Box(
              modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiary),
            )
            Column {
              Text(
                text = stringResource(R.string.watch_stats_audio),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  text = formatHumanDuration(stats.audioSeconds),
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                )
                if (total > 0) {
                  Text(
                    text = "($audioPercent%)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun WeeklyActivityBentoCard(days: Map<String, Long>) {
  val today = LocalDate.now()
  val dates = remember(today) { (6 downTo 0).map { today.minusDays(it.toLong()) } }
  val values = dates.map { days[it.toString()] ?: 0L }
  val max = values.maxOrNull() ?: 0L
  val peakDayIndex = if (max > 0L) values.indexOfFirst { it == max }.takeIf { it in dates.indices } else null
  val peakDayName = peakDayIndex?.let { dates[it].dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
  val chartMax = max.coerceAtLeast(1L)

  val primaryColor = MaterialTheme.colorScheme.primary
  val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

  BentoCard(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(36.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.RoundedFilled.AccessTime,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
            }
          }
          Text(
            text = stringResource(R.string.watch_stats_last_seven_days),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
        }

        if (peakDayName != null) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ) {
            Text(
              text = "Peak: $peakDayName",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
          }
        }
      }

      // Bar Chart
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(130.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
      ) {
        dates.forEachIndexed { index, date ->
          val value = values[index]
          val isToday = index == 6
          val fraction = if (max > 0L) (value.toFloat() / chartMax.toFloat()).coerceIn(0f, 1f) else 0f

          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
          ) {
            // Track & Fill bar
            Box(
              modifier = Modifier
                .width(18.dp)
                .height(96.dp)
                .clip(CircleShape)
                .background(trackColor),
              contentAlignment = Alignment.BottomCenter,
            ) {
              if (value > 0L) {
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction.coerceAtLeast(0.12f))
                    .clip(CircleShape)
                    .background(
                      if (isToday) {
                        Brush.verticalGradient(
                          listOf(
                            MaterialTheme.colorScheme.tertiary,
                            MaterialTheme.colorScheme.primary,
                          ),
                        )
                      } else {
                        Brush.verticalGradient(
                          listOf(
                            primaryColor.copy(alpha = 0.85f),
                            primaryColor,
                          ),
                        )
                      },
                    ),
                )
              }
            }

            // Day label
            if (isToday) {
              Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Text(
                    text = date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                  )
                }
              }
            } else {
              Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center,
              ) {
                Text(
                  text = date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Medium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopMediaLeaderboardBentoCard(
  topMedia: List<WatchMediaStats>,
  mediaFilter: Int,
  onFilterChange: (Int) -> Unit,
) {
  BentoCard(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(36.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.RoundedFilled.Star,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
            }
          }
          Text(
            text = stringResource(R.string.watch_stats_top_media),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
        }
      }

      // Filter chips inside card
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        listOf(
          R.string.pref_all_sources,
          R.string.watch_stats_video,
          R.string.watch_stats_audio,
        ).forEachIndexed { index, labelRes ->
          FilterChip(
            selected = mediaFilter == index,
            onClick = { onFilterChange(index) },
            label = { Text(stringResource(labelRes)) },
            shape = CircleShape,
          )
        }
      }

      if (topMedia.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.watch_stats_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        val maxSeconds = topMedia.firstOrNull()?.seconds?.coerceAtLeast(1L) ?: 1L
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          topMedia.forEachIndexed { index, media ->
            LeaderboardMediaRow(
              rank = index + 1,
              media = media,
              maxSeconds = maxSeconds,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun LeaderboardMediaRow(
  rank: Int,
  media: WatchMediaStats,
  maxSeconds: Long,
) {
  val rankColor = when (rank) {
    1 -> Color(0xFFFFB300) // Gold
    2 -> Color(0xFF90A4AE) // Silver
    3 -> Color(0xFFCD7F32) // Bronze
    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
  }

  val rankContainerColor = when (rank) {
    1 -> Color(0xFFFFB300).copy(alpha = 0.18f)
    2 -> Color(0xFF90A4AE).copy(alpha = 0.18f)
    3 -> Color(0xFFCD7F32).copy(alpha = 0.18f)
    else -> MaterialTheme.colorScheme.surfaceContainerHighest
  }

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.padding(10.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Rank Pill
      Surface(
        shape = CircleShape,
        color = rankContainerColor,
        contentColor = rankColor,
        modifier = Modifier.size(28.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(
            text = "$rank",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
          )
        }
      }

      // Thumbnail
      WatchMediaArtwork(media)

      // Title and Progress
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = media.title,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
        )

        // Mini relative progress bar
        val progress = (media.seconds.toFloat() / maxSeconds.toFloat()).coerceIn(0f, 1f)
        LinearProgressIndicator(
          progress = { progress },
          modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape),
          color = if (rank == 1) Color(0xFFFFB300) else MaterialTheme.colorScheme.primary,
          trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
      }

      // Duration
      Text(
        text = formatHumanDuration(media.seconds),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

@Composable
private fun WatchMediaArtwork(media: WatchMediaStats) {
  val context = LocalContext.current
  val thumbnailRepository = koinInject<ThumbnailRepository>()
  val density = LocalDensity.current
  val isAudio = media.isAudio
  val widthPx = with(density) { (if (isAudio) 48.dp else 68.dp).roundToPx() }
  val heightPx = with(density) { 48.dp.roundToPx() }

  val generatedThumbnail by
    produceState<Bitmap?>(
      initialValue = null,
      media.sourceUri,
      media.title,
      media.isAudio,
      media.artworkUri,
      widthPx,
      heightPx,
    ) {
      if (!media.artworkUri.isNullOrBlank()) return@produceState
      val video =
        withContext(Dispatchers.IO) {
          resolveWatchStatsVideo(context, media)
        } ?: return@produceState
      value = thumbnailRepository.getThumbnail(video, widthPx, heightPx)
    }

  Box(
    modifier =
      Modifier
        .size(width = if (isAudio) 48.dp else 68.dp, height = 48.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    contentAlignment = Alignment.Center,
  ) {
    when {
      !media.artworkUri.isNullOrBlank() -> {
        RemoteImage(
          url = media.artworkUri,
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      }

      generatedThumbnail != null -> {
        Image(
          bitmap = generatedThumbnail!!.asImageBitmap(),
          contentDescription = null,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      }

      else -> {
        Icon(
          imageVector =
            if (media.isAudio) {
              Icons.RoundedFilled.Audiotrack
            } else {
              Icons.RoundedFilled.PlayArrow
            },
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(24.dp),
        )
      }
    }
  }
}

private fun resolveWatchStatsVideo(
  context: Context,
  media: WatchMediaStats,
): Video? {
  val source =
    media.sourceUri
      ?.takeIf(String::isNotBlank)
      ?: findLegacyMediaUri(context, media.title, media.isAudio)?.toString()
      ?: return null

  val parsed = runCatching { Uri.parse(source) }.getOrNull()
  val uri =
    if (parsed == null || parsed.scheme.isNullOrBlank()) {
      Uri.fromFile(File(source))
    } else {
      parsed
    }

  return Video(
    id = source.hashCode().toLong(),
    title = media.title,
    displayName = media.title,
    path = source,
    uri = uri,
    duration = 0L,
    durationFormatted = "",
    size = 0L,
    sizeFormatted = "",
    dateModified = 0L,
    dateAdded = 0L,
    mimeType = if (media.isAudio) "audio/*" else "video/*",
    bucketId = "",
    bucketDisplayName = "",
    width = 0,
    height = 0,
    fps = 0f,
    resolution = "",
    isAudio = media.isAudio,
  )
}

/**
 * Older watch_stats.json entries predate sourceUri. Recover a local video/audio by its display name so
 * those rows can get thumbnails immediately instead of waiting for the item to be played again.
 */
private fun findLegacyMediaUri(
  context: Context,
  title: String,
  isAudio: Boolean,
): Uri? {
  val displayName = title.substringAfterLast('/').substringAfterLast('\\').trim()
  if (displayName.isBlank()) return null

  val resolver = context.contentResolver
  val contentUri = if (isAudio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
  val projection =
    arrayOf(
      if (isAudio) MediaStore.Audio.Media._ID else MediaStore.Video.Media._ID,
      if (isAudio) MediaStore.Audio.Media.DISPLAY_NAME else MediaStore.Video.Media.DISPLAY_NAME,
    )

  fun query(selection: String, argument: String): Uri? =
    runCatching {
      resolver.query(
        contentUri,
        projection,
        selection,
        arrayOf(argument),
        null,
      )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val id = cursor.getLong(0)
        ContentUris.withAppendedId(contentUri, id)
      }
    }.getOrNull()

  query("${projection[1]} = ?", displayName)?.let { return it }

  val stem = displayName.substringBeforeLast('.', displayName)
  if (stem.isNotBlank()) {
    query("${projection[1]} LIKE ?", "$stem.%")?.let { return it }
  }
  return null
}

private fun formatHumanDuration(seconds: Long): String {
  if (seconds <= 0L) return "0m"
  val hours = seconds / 3600
  val minutes = (seconds % 3600) / 60
  val remainingSeconds = seconds % 60

  return when {
    hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
    hours > 0 -> "${hours}h"
    minutes > 0 -> "${minutes}m"
    else -> "${remainingSeconds}s"
  }
}
