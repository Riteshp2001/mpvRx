/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import app.gyrolet.mpvrx.ui.player.PlaybackSession

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.data.lyrics.LyricsLanguageOptions
import app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType
import app.gyrolet.mpvrx.domain.lyrics.SyncedLine
import app.gyrolet.mpvrx.domain.lyrics.SyncedWord
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.LyricsTranslationDisplayMode
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.LyricsTranslateDialog
import app.gyrolet.mpvrx.ui.theme.fontFamilyForText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LyricsView(
  viewModel: PlayerViewModel,
  onOpenProviderSheet: () -> Unit,
  modifier: Modifier = Modifier,
  showTitleHeader: Boolean = false,
  isLyricsFullscreen: Boolean = false,
  onTap: (() -> Unit)? = null,
) {
  val audioPreferences = koinInject<AudioPreferences>()
  val translationDisplayMode by audioPreferences.lyricsTranslationDisplayMode.collectAsState()
  val state by viewModel.lyricsUiState.collectAsState()
  val playbackState by PlaybackSession.state.collectAsState()
  val isAudiobook = playbackState.currentItem?.audiobook != null
  val precisePosition by viewModel.precisePosition.collectAsState()
  val listState = rememberLazyListState()
  val density = LocalDensity.current
  var lyricsViewportPx by remember { mutableIntStateOf(0) }
  var showTranslateDialog by remember { mutableStateOf(false) }

  val currentPosMs = remember(precisePosition, state.syncOffsetMs) {
    (precisePosition * 1000).toLong() + state.syncOffsetMs
  }
  val paused by PlaybackSession.propBoolean["pause"].collectAsState()
  val playbackSpeed by PlaybackSession.propFloat["speed"].collectAsState()
  // Position polls arrive every 50-500ms; per-letter animation needs a per-frame clock.
  val smoothPositionMs = rememberSmoothedPositionMs(currentPosMs, paused == false, playbackSpeed ?: 1f)

  // Keep the active lyric centered without snapping as playback advances.
  LaunchedEffect(state.activeLineIndex, isLyricsFullscreen, lyricsViewportPx, state.lyrics) {
    val target = state.activeLineIndex
    val syncedLineCount = state.lyrics?.synced?.size ?: 0
    if (target !in 0 until syncedLineCount || lyricsViewportPx <= 0) return@LaunchedEffect
    try {
      withFrameNanos {}
      var item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
      if (item == null) {
        val layoutInfo = listState.layoutInfo
        val viewportCenter =
          (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
        val estimatedItemHeight =
          layoutInfo.visibleItemsInfo
            .map { it.size }
            .average()
            .takeIf(Double::isFinite)
            ?.toInt()
            ?: with(density) { 48.dp.roundToPx() }
        val centeredOffset = (estimatedItemHeight / 2f - viewportCenter).roundToInt()
        listState.animateScrollToItem(target, scrollOffset = centeredOffset)
        item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
      }

      item?.let { targetItem ->
        val layoutInfo = listState.layoutInfo
        val viewportCenter =
          (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
        val itemCenter = targetItem.offset + targetItem.size / 2f
        val centerDelta = itemCenter - viewportCenter
        if (kotlin.math.abs(centerDelta) > 0.5f) {
          listState.animateScrollBy(
            centerDelta,
            animationSpec = spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessLow),
          )
        }
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      // Lyrics can be replaced while a target line is being laid out.
    }
  }

  val hasEmbedded = state.embeddedLyrics != null && state.embeddedLyrics?.isValid() == true

  Surface(
    modifier = modifier
      .fillMaxSize()
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
      ) { onTap?.invoke() },
    color = Color.Transparent,
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 4.dp, vertical = 4.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // Optional Header
      if (showTitleHeader) {
        val mediaTitle by PlaybackSession.propString["media-title"].collectAsState()
        val artistName by PlaybackSession.propString["metadata/by-key/Artist"].collectAsState()
        val displayTitle = mediaTitle?.takeIf { it.isNotBlank() } ?: "Current Track"
        val displayArtist = artistName?.takeIf { it.isNotBlank() } ?: ""

        Text(
          text = displayTitle,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.ExtraBold,
          fontFamily = fontFamilyForText(displayTitle),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
        )
        if (displayArtist.isNotBlank()) {
          Text(
            text = displayArtist,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            fontFamily = fontFamilyForText(displayArtist),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
      }

      // Source Switcher Row (Show ONLY IF embedded/local lyrics are present)
      if (hasEmbedded) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          FilterChip(
            selected = state.selectedSource == LyricsSourceType.EMBEDDED || state.selectedSource == LyricsSourceType.LOCAL,
            onClick = { viewModel.switchLyricsSource(LyricsSourceType.EMBEDDED) },
            label = {
              Text(
                if (state.embeddedLyrics?.sourceType == LyricsSourceType.LOCAL) stringResource(R.string.lyrics_source_local) else stringResource(R.string.lyrics_source_embedded),
                fontWeight = FontWeight.Bold,
              )
            },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
          )

          if (!isAudiobook) FilterChip(
            selected = state.selectedSource == LyricsSourceType.ONLINE,
            onClick = { viewModel.switchLyricsSource(LyricsSourceType.ONLINE) },
            label = {
              Text(stringResource(R.string.lyrics_source_online), fontWeight = FontWeight.Bold)
            },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
          )

          if (state.isLoading) {
            Spacer(modifier = Modifier.width(6.dp))
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }

      // Which online database is being read, and the menu to ask another one.
      if (state.onlineEnabled && (hasEmbedded || state.onlineLyrics != null || state.isLoading)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          LyricsProviderPicker(
            preferredProvider = state.preferredProvider,
            onlineProvider = state.onlineProvider,
            onOpenSheet = onOpenProviderSheet,
          )
        }
      }

      if (hasEmbedded || (state.onlineEnabled && state.onlineLyrics != null)) {
        Spacer(modifier = Modifier.height(14.dp))
      }

      // Edge-to-Edge Synced Lyrics Scroll Area with bottom 33% gradient fade
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .onSizeChanged { lyricsViewportPx = it.height },
        contentAlignment = Alignment.Center,
      ) {
        val activeLyrics = state.lyrics
        when {
          state.isLoading && activeLyrics == null -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
              Spacer(modifier = Modifier.height(12.dp))
              Text(
                text = "Fetching synced lyrics...",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          activeLyrics != null && !activeLyrics.synced.isNullOrEmpty() -> {
            val centerPadding = with(density) { (lyricsViewportPx / 2f).toDp() }
            LazyColumn(
              state = listState,
              modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                  drawContent()
                  drawRect(
                    brush = Brush.verticalGradient(
                      0.0f to Color.Black,
                      0.50f to Color.Black,
                      1.0f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                  )
                },
              verticalArrangement = Arrangement.spacedBy(18.dp),
              contentPadding = PaddingValues(vertical = centerPadding),
            ) {
              itemsIndexed(
                items = activeLyrics.synced,
                key = { index, line -> "${line.time}_${index}" },
                contentType = { _, _ -> "lyric_synced_line" },
              ) { index, line ->
                val isActiveLine = index == state.activeLineIndex
                val (ogText, transText) = remember(line.line, line.translation, translationDisplayMode) {
                  val rawTrans = line.translation?.trim()
                  if (translationDisplayMode == LyricsTranslationDisplayMode.Replace && !rawTrans.isNullOrBlank()) {
                    Pair(rawTrans, null)
                  } else if (!rawTrans.isNullOrBlank() && !rawTrans.equals(line.line.trim(), ignoreCase = true)) {
                    Pair(line.line.trim(), rawTrans)
                  } else if (line.line.contains("\n")) {
                    val parts = line.line.split("\n", limit = 2)
                    Pair(parts[0].trim(), parts.getOrNull(1)?.trim())
                  } else if (line.line.contains(" / ")) {
                    val parts = line.line.split(" / ", limit = 2)
                    Pair(parts[0].trim(), parts.getOrNull(1)?.trim())
                  } else if (line.line.contains(" | ")) {
                    val parts = line.line.split(" | ", limit = 2)
                    Pair(parts[0].trim(), parts.getOrNull(1)?.trim())
                  } else {
                    Pair(line.line.trim(), null)
                  }
                }

                val isBlankLine = ogText.isBlank()
                val displayText = if (isBlankLine) ". . ." else ogText
                val hasTranslation = !transText.isNullOrBlank()

                val distanceFromActive =
                  if (state.activeLineIndex >= 0) kotlin.math.abs(index - state.activeLineIndex) else 0

                val lineAlpha by animateFloatAsState(
                  targetValue =
                    when {
                      isActiveLine -> 1.0f
                      distanceFromActive == 1 -> 0.74f
                      distanceFromActive == 2 -> 0.52f
                      else -> 0.34f
                    },
                  animationSpec = tween(durationMillis = if (isActiveLine) 320 else 460, easing = FastOutSlowInEasing),
                  label = "LineAlpha",
                )

                val lineScale by animateFloatAsState(
                  targetValue = if (isActiveLine) 1.025f else 0.985f,
                  animationSpec = spring(dampingRatio = 0.86f, stiffness = 220f),
                  label = "LineScale",
                )

                val lineBlur by animateDpAsState(
                  targetValue =
                    when {
                      isActiveLine || state.activeLineIndex < 0 -> 0.dp
                      distanceFromActive == 1 -> 0.6.dp
                      distanceFromActive == 2 -> 1.0.dp
                      else -> 0.dp
                    },
                  animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                  label = "LineBlur",
                )

                val activeColor = MaterialTheme.colorScheme.onSurface
                val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)

                val lineColor by animateColorAsState(
                  targetValue = if (isActiveLine) activeColor else inactiveColor,
                  animationSpec = tween(durationMillis = 250),
                  label = "LineColor",
                )

                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .then(
                      if (lineBlur > 0.dp) {
                        Modifier.blur(
                          lineBlur,
                          edgeTreatment = BlurredEdgeTreatment(RoundedCornerShape(8.dp)),
                        )
                      } else {
                        Modifier
                      },
                    )
                    .graphicsLayer {
                      alpha = lineAlpha
                      scaleX = lineScale
                      scaleY = lineScale
                      transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                      onTap?.invoke()
                      if (!isLyricsFullscreen) {
                        val targetSeconds = line.time / 1000f
                        PlaybackSession.command("seek", targetSeconds.toString(), "absolute+exact")
                      }
                    }
                    .padding(vertical = 4.dp, horizontal = 6.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                  if (isActiveLine && !isBlankLine && !line.words.isNullOrEmpty()) {
                    FlowRow(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.Center,
                      verticalArrangement = Arrangement.Center,
                    ) {
                      line.words.forEachIndexed { wordIndex, word ->
                        val wordStartMs = word.time.toLong()
                        val wordEndMs =
                          line.words.getOrNull(wordIndex + 1)?.time?.toLong()
                            ?.takeIf { it > wordStartMs }
                            ?: activeLyrics.synced.getOrNull(index + 1)?.time?.toLong()
                              ?.coerceAtMost(line.time.toLong() + 8_000L)
                              ?.takeIf { it > wordStartMs }
                            ?: (wordStartMs + 600L)
                        AnimatedLyricWord(
                          word = word,
                          endTimeMs = wordEndMs,
                          positionMs = smoothPositionMs,
                          activeColor = activeColor,
                          inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                          fontSize = if (isLyricsFullscreen) 30.sp else 26.sp,
                        )
                      }
                    }
                  } else {
                    Text(
                      text = displayText,
                      color = lineColor,
                      fontSize = when {
                        isActiveLine && isLyricsFullscreen -> 30.sp
                        isActiveLine -> 26.sp
                        isLyricsFullscreen -> 24.sp
                        else -> 22.sp
                      },
                      fontWeight = if (isActiveLine) FontWeight.Black else FontWeight.ExtraBold,
                      fontFamily = fontFamilyForText(displayText),
                      textAlign = TextAlign.Center,
                      modifier = Modifier.fillMaxWidth(),
                    )
                  }

                  // Render Translation if present (Smaller font size, highlighted together with original when active)
                  if (hasTranslation) {
                    val translationColor by animateColorAsState(
                      targetValue = if (isActiveLine) activeColor.copy(alpha = 0.85f) else inactiveColor.copy(alpha = 0.70f),
                      animationSpec = tween(durationMillis = 250),
                      label = "TranslationColor",
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                      text = transText.orEmpty(),
                      color = translationColor,
                      fontSize = if (isActiveLine) 18.sp else 16.sp,
                      fontWeight = FontWeight.Bold,
                      fontFamily = fontFamilyForText(transText.orEmpty()),
                      textAlign = TextAlign.Center,
                      modifier = Modifier.fillMaxWidth(),
                    )
                  }
                }
              }
            }
          }

          activeLyrics != null && !activeLyrics.plain.isNullOrEmpty() -> {
            LazyColumn(
              modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                  drawContent()
                  drawRect(
                    brush = Brush.verticalGradient(
                      0.0f to Color.Black,
                      0.50f to Color.Black,
                      1.0f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                  )
                },
              verticalArrangement = Arrangement.spacedBy(14.dp),
              contentPadding = PaddingValues(top = 16.dp, bottom = 220.dp),
            ) {
              itemsIndexed(
                items = activeLyrics.plain,
                key = { index, _ -> index },
                contentType = { _, _ -> "lyric_plain_line" },
              ) { _, lineText ->
                val textToDisplay = if (lineText.isBlank()) ". . ." else lineText
                Text(
                  text = textToDisplay,
                  color = MaterialTheme.colorScheme.onSurface,
                  fontSize = 24.sp,
                  fontWeight = FontWeight.Black,
                  fontFamily = fontFamilyForText(textToDisplay),
                  textAlign = TextAlign.Center,
                  modifier = Modifier.fillMaxWidth(),
                )
              }
            }
          }

          else -> {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center,
            ) {
              Text(
                text = if (isAudiobook) stringResource(R.string.audiobook_no_text) else "No lyrics available for this track.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(8.dp))
              TextButton(
                onClick = {
                  if (isAudiobook) {
                    viewModel.loadLyricsForCurrentTrack(forceRefresh = true)
                  } else {
                    viewModel.searchLyricsOnline()
                  }
                },
              ) {
                val searchLabel =
                  if (isAudiobook) R.string.audiobook_retry else R.string.lyrics_search_online
                Text(text = stringResource(searchLabel), fontWeight = FontWeight.Bold)
              }
            }
          }
        }
      }

      state.errorMessage?.let { message ->
        Text(
          text = message,
          style = MaterialTheme.typography.bodySmall,
          fontFamily = fontFamilyForText(message),
          color = MaterialTheme.colorScheme.error,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
      }

      // Bottom Bar: Translate Button on Left & Sync Timing Button on Right (Expanding into remaining space)
      AnimatedVisibility(visible = state.lyrics?.synced?.isNotEmpty() == true) {
        var isSyncExpanded by rememberSaveable { mutableStateOf(false) }

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Translate button on Left (40.dp square with rounded corners)
          Surface(
            onClick = { showTranslateDialog = true },
            shape = RoundedCornerShape(12.dp),
            color = if (state.isTranslationActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(40.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              if (state.isTranslating) {
                CircularProgressIndicator(
                  modifier = Modifier.size(20.dp),
                  strokeWidth = 2.5.dp,
                  color = MaterialTheme.colorScheme.primary,
                )
              } else {
                Icon(
                  imageVector = Icons.RoundedFilled.Translate,
                  contentDescription = stringResource(R.string.lyrics_translate_title),
                  tint = if (state.isTranslationActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(22.dp),
                )
              }
            }
          }

          Spacer(modifier = Modifier.width(8.dp))

          // Sync Section on the Right (pinned to right when collapsed, expands leftwards across remaining space)
          Box(
            modifier = Modifier
              .weight(1f)
              .height(40.dp),
            contentAlignment = Alignment.CenterEnd,
          ) {
            // When expanded, the sync control bar slides in from right to left, filling the remaining space
            androidx.compose.animation.AnimatedVisibility(
              visible = isSyncExpanded,
              enter = fadeIn(animationSpec = tween(220)) +
                slideInHorizontally(initialOffsetX = { it }, animationSpec = spring(dampingRatio = 0.85f, stiffness = 350f)) +
                expandHorizontally(expandFrom = Alignment.End, animationSpec = spring(dampingRatio = 0.85f, stiffness = 350f)),
              exit = fadeOut(animationSpec = tween(180)) +
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(180)) +
                shrinkHorizontally(shrinkTowards = Alignment.End, animationSpec = tween(180)),
            ) {
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier
                  .fillMaxWidth()
                  .height(40.dp),
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  SyncOffsetButton(
                    label = "-0.5s",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.adjustLyricsSyncOffset(-500) },
                  )
                  SyncOffsetButton(
                    label = "-0.1s",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.adjustLyricsSyncOffset(-100) },
                  )
                  SyncOffsetButton(
                    label = "${if (state.syncOffsetMs >= 0) "+" else ""}${state.syncOffsetMs / 1000f}s",
                    modifier = Modifier.weight(1.2f),
                    emphasized = true,
                    onClick = { viewModel.resetLyricsSyncOffset() },
                  )
                  SyncOffsetButton(
                    label = "+0.1s",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.adjustLyricsSyncOffset(100) },
                  )
                  SyncOffsetButton(
                    label = "+0.5s",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.adjustLyricsSyncOffset(500) },
                  )
                  // Collapse close button
                  Box(
                    modifier = Modifier
                      .clip(RoundedCornerShape(8.dp))
                      .clickable { isSyncExpanded = false }
                      .padding(horizontal = 6.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                  ) {
                    Icon(
                      imageVector = Icons.RoundedFilled.Close,
                      contentDescription = stringResource(R.string.ui_close),
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.size(16.dp),
                    )
                  }
                }
              }
            }

            // When collapsed, the sync icon button sits on the far right
            androidx.compose.animation.AnimatedVisibility(
              visible = !isSyncExpanded,
              enter = fadeIn(animationSpec = tween(220)) +
                expandHorizontally(expandFrom = Alignment.End, animationSpec = spring(dampingRatio = 0.85f, stiffness = 350f)),
              exit = fadeOut(animationSpec = tween(180)) +
                shrinkHorizontally(shrinkTowards = Alignment.End, animationSpec = tween(180)),
            ) {
              Surface(
                onClick = { isSyncExpanded = true },
                shape = RoundedCornerShape(12.dp),
                color = if (state.syncOffsetMs != 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(40.dp),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Timer,
                    contentDescription = "Sync timing",
                    tint = if (state.syncOffsetMs != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                  )
                }
              }
            }
          }
        }
      }
    }
  }

  if (showTranslateDialog) {
    LyricsTranslateDialog(
      viewModel = viewModel,
      onDismiss = { showTranslateDialog = false },
    )
  }
}

// One equal-weight segment of the sync offset pill; `emphasized` marks the center reset/readout segment.
@Composable
private fun SyncOffsetButton(
  label: String,
  modifier: Modifier = Modifier,
  emphasized: Boolean = false,
  onClick: () -> Unit,
) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 6.dp, vertical = 6.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

/**
 * Interpolates the polled playback position with the display frame clock so letter reveals stay
 * fluid between position updates. Backward jumps (seeks) snap; forward extrapolation is capped so
 * a stalled poll cannot run ahead.
 */
@Composable
internal fun rememberSmoothedPositionMs(
  rawPositionMs: Long,
  isPlaying: Boolean,
  speed: Float,
): State<Long> {
  val smoothed = remember { mutableLongStateOf(rawPositionMs) }
  val latestRawPositionMs by rememberUpdatedState(rawPositionMs)

  LaunchedEffect(rawPositionMs, isPlaying) {
    if (!isPlaying) smoothed.longValue = rawPositionMs
  }

  LaunchedEffect(isPlaying, speed) {
    val playbackRate = speed.coerceIn(0.1f, 8f).toDouble()
    if (!isPlaying) {
      smoothed.longValue = latestRawPositionMs
      return@LaunchedEffect
    }

    var observedRawMs = latestRawPositionMs
    var anchorRawMs = observedRawMs.toDouble()
    var renderedMs =
      if (abs(smoothed.longValue - observedRawMs) > 400L) {
        observedRawMs.toDouble()
      } else {
        maxOf(smoothed.longValue.toDouble(), observedRawMs.toDouble())
      }
    var anchorFrameNanos = 0L
    var previousFrameNanos = 0L

    while (isActive) {
      withFrameNanos { frameNanos ->
        if (previousFrameNanos == 0L) {
          anchorFrameNanos = frameNanos
          previousFrameNanos = frameNanos
          smoothed.longValue = renderedMs.roundToLong()
          return@withFrameNanos
        }

        val rawMs = latestRawPositionMs
        var snappedToPoll = false
        if (rawMs != observedRawMs) {
          val anchorElapsedMs = (frameNanos - anchorFrameNanos) / 1_000_000.0
          val expectedRawMs = anchorRawMs + (anchorElapsedMs * playbackRate).coerceAtMost(800.0)
          if (rawMs < observedRawMs || abs(rawMs - expectedRawMs) > 400.0) {
            renderedMs = rawMs.toDouble()
            snappedToPoll = true
          }
          observedRawMs = rawMs
          anchorRawMs = rawMs.toDouble()
          anchorFrameNanos = frameNanos
        }

        val elapsedFromAnchorMs = (frameNanos - anchorFrameNanos) / 1_000_000.0
        val targetMs = anchorRawMs + (elapsedFromAnchorMs * playbackRate).coerceAtMost(800.0)
        val frameDeltaMs = ((frameNanos - previousFrameNanos) / 1_000_000.0).coerceIn(0.0, 50.0)
        val predictedMs = renderedMs + frameDeltaMs * playbackRate
        val correction = 1.0 - exp(-frameDeltaMs / 120.0)
        val correctedMs = predictedMs + (targetMs - predictedMs) * correction
        renderedMs = if (snappedToPoll) targetMs else maxOf(renderedMs, correctedMs)
        smoothed.longValue = renderedMs.roundToLong()
        previousFrameNanos = frameNanos
      }
    }
  }
  return smoothed
}

/** Smooth karaoke fill: a glowing active layer is revealed continuously from left to right. */
@Composable
internal fun AnimatedLyricWord(
  word: SyncedWord,
  endTimeMs: Long,
  positionMs: State<Long>,
  activeColor: Color,
  inactiveColor: Color,
  fontSize: TextUnit = 26.sp,
) {
  val text = "${word.word} "
  val textStyle =
    MaterialTheme.typography.headlineSmall.copy(
      fontSize = fontSize,
      fontWeight = FontWeight.Black,
      fontFamily = fontFamilyForText(text),
    )
  if (text.isEmpty()) {
    Text(text = " ", style = textStyle)
    return
  }

  val startTimeMs = word.time.toLong()
  val durationMs = (endTimeMs - startTimeMs).coerceAtLeast(1L)
  val activeStyle =
    textStyle.copy(
      shadow =
        Shadow(
          color = activeColor.copy(alpha = 0.42f),
          offset = Offset.Zero,
          blurRadius = 10f,
        ),
    )

  Box(contentAlignment = Alignment.CenterStart) {
    Text(text = text, color = inactiveColor, style = textStyle)
    Text(
      text = text,
      color = activeColor,
      style = activeStyle,
      modifier =
        Modifier.drawWithContent {
          val fillProgress =
            ((positionMs.value - startTimeMs).toFloat() / durationMs)
              .coerceIn(0f, 1f)
          clipRect(right = size.width * fillProgress) {
            this@drawWithContent.drawContent()
          }
        },
    )
  }
}
