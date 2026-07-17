package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import android.widget.Toast
import app.gyrolet.mpvrx.preferences.AiPreferences
import app.gyrolet.mpvrx.repository.ai.AiService
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitle
import app.gyrolet.mpvrx.repository.subtitle.subdlGroupEpisodeRange
import app.gyrolet.mpvrx.repository.subtitle.withSelectedSubdlGroupEpisode
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.utils.media.MediaInfoParser
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

sealed class OnlineSubtitleItem {
  data class OnlineTrack(val subtitle: OnlineSubtitle) : OnlineSubtitleItem()
  data class Header(val title: String) : OnlineSubtitleItem()
  object Divider : OnlineSubtitleItem()
}

@Composable
fun OnlineSubtitleSearchSheet(
  onDismissRequest: () -> Unit,
  onDownloadOnline: (OnlineSubtitle) -> Unit,
  isSearching: Boolean = false,
  isDownloading: Boolean = false,
  searchResults: ImmutableList<OnlineSubtitle> = emptyList<OnlineSubtitle>().toImmutableList(),
  isOnlineSectionExpanded: Boolean = true,
  onToggleOnlineSection: () -> Unit = {},
  modifier: Modifier = Modifier,
  mediaTitle: String = "",
  showWyzieSelection: Boolean = true,
  // Autocomplete & Series Selection
  mediaSearchResults: ImmutableList<app.gyrolet.mpvrx.repository.wyzie.WyzieTmdbResult> = emptyList<app.gyrolet.mpvrx.repository.wyzie.WyzieTmdbResult>().toImmutableList(),
  isSearchingMedia: Boolean = false,
  onSearchMedia: (String) -> Unit = {},
  onSelectMedia: (app.gyrolet.mpvrx.repository.wyzie.WyzieTmdbResult) -> Unit = {},
  selectedTvShow: app.gyrolet.mpvrx.repository.wyzie.WyzieTvShowDetails? = null,
  isFetchingTvDetails: Boolean = false,
  selectedSeason: app.gyrolet.mpvrx.repository.wyzie.WyzieSeason? = null,
  onSelectSeason: (app.gyrolet.mpvrx.repository.wyzie.WyzieSeason) -> Unit = {},
  seasonEpisodes: ImmutableList<app.gyrolet.mpvrx.repository.wyzie.WyzieEpisode> = emptyList<app.gyrolet.mpvrx.repository.wyzie.WyzieEpisode>().toImmutableList(),
  isFetchingEpisodes: Boolean = false,
  selectedEpisode: app.gyrolet.mpvrx.repository.wyzie.WyzieEpisode? = null,
  onSelectEpisode: (app.gyrolet.mpvrx.repository.wyzie.WyzieEpisode) -> Unit = {},
  onClearMediaSelection: () -> Unit = {}
) {
  val items = remember(searchResults, isSearching, isOnlineSectionExpanded) {
    val list = mutableListOf<OnlineSubtitleItem>()
    
    // Online Search Results section
    if (searchResults.isNotEmpty() || isSearching) {
        val hashMatches = searchResults.count { it.isHashMatch }
        val headerText =
          if (hashMatches > 0) {
            "Verified Matches ($hashMatches) + Others"
          } else {
            "Online Results (${searchResults.size})"
          }
        list.add(OnlineSubtitleItem.Header(headerText))
        if (isOnlineSectionExpanded) {
            list.addAll(searchResults.map { OnlineSubtitleItem.OnlineTrack(it) })
        }
    }

    list.toImmutableList()
  }

  PlayerSheet(onDismissRequest) {
    Column(modifier) {
      val keyboardController = LocalSoftwareKeyboardController.current
      val context = LocalContext.current
      val mediaInfo = remember(mediaTitle) { MediaInfoParser.parse(mediaTitle) }
      var searchQuery by remember { mutableStateOf(mediaInfo.title) }
      val aiPreferences = koinInject<AiPreferences>()
      val aiService = koinInject<AiService>()
      val scope = rememberCoroutineScope()
      var isAiFormatting by remember { mutableStateOf(false) }

      // Build the detected info string for display
      val detectedInfo = remember(mediaInfo) {
        buildString {
          append(mediaInfo.title)
          if (mediaInfo.season != null || mediaInfo.episode != null) {
            append(" • ")
            if (mediaInfo.season != null) append("S${String.format("%02d", mediaInfo.season)}")
            if (mediaInfo.episode != null) append("E${String.format("%02d", mediaInfo.episode)}")
          }
          mediaInfo.year?.let { append(" ($it)") }
        }
      }

      // Auto-trigger search on open
      LaunchedEffect(mediaInfo) {
        if (mediaInfo.title.isNotBlank()) {
          onSearchMedia(mediaInfo.title)
        }
      }

      fun runSearch() {
        val q = if (searchQuery.isNotBlank()) searchQuery else mediaInfo.title
        searchQuery = q
        onSearchMedia(q)
        keyboardController?.hide()
      }

      fun formatWithAi() {
        val input = if (searchQuery.isNotBlank()) searchQuery else mediaInfo.title
        if (input.isBlank()) {
          Toast.makeText(context, "Search query is empty", Toast.LENGTH_SHORT).show()
          return
        }
        scope.launch {
          isAiFormatting = true
          aiService.formatTitleForSubtitleSearch(input)
            .onSuccess { formatted ->
              searchQuery = formatted
              onSearchMedia(formatted)
              keyboardController?.hide()
            }
            .onFailure { e ->
              Toast.makeText(context, "AI format failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
          isAiFormatting = false
        }
      }
      
      Column(
        modifier = Modifier.padding(top = MaterialTheme.spacing.medium)
      ) {
        // Detected info chip
        if (detectedInfo.isNotBlank() && mediaInfo.title.isNotBlank()) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = MaterialTheme.spacing.medium)
              .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              Icons.Default.AutoFixHigh,
              contentDescription = null,
              modifier = Modifier.size(14.dp),
              tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
            Spacer(Modifier.width(4.dp))
            Text(
              text = detectedInfo,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
              maxLines = 1,
              modifier = Modifier.basicMarquee()
            )
          }
        }


        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
          OutlinedTextField(
            value = searchQuery,
            onValueChange = {
              searchQuery = it
            },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.pref_subtitles_search_online)) },
            leadingIcon = {
              IconButton(onClick = {
                searchQuery = mediaInfo.title
                onSearchMedia(mediaInfo.title)
              }) {
                Icon(Icons.Default.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary)
              }
            },
            trailingIcon = {
              Row(verticalAlignment = Alignment.CenterVertically) {
                if (searchQuery.isNotEmpty()) {
                  IconButton(onClick = {
                    searchQuery = ""
                    onClearMediaSelection()
                  }) {
                    Icon(Icons.Default.Close, null)
                  }
                }
                if (aiPreferences.enabled.get() && aiPreferences.subtitleFormatWithAi.get()) {
                  if (isAiFormatting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(4.dp))
                  } else {
                    IconButton(onClick = { formatWithAi() }) {
                      Icon(Icons.Default.AutoAwesome, "Format with AI", tint = MaterialTheme.colorScheme.tertiary)
                    }
                  }
                }
                if (isSearching || isDownloading || isSearchingMedia) {
                  CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                  Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = { runSearch() }) {
                  Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary)
                }
              }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
              focusedContainerColor = Color.Transparent,
              unfocusedContainerColor = Color.Transparent,
              focusedIndicatorColor = MaterialTheme.colorScheme.primary,
              unfocusedIndicatorColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )
          )

          if (showWyzieSelection && selectedTvShow != null) {
            SeriesSelectionControls(
              tvShow = selectedTvShow,
              selectedSeason = selectedSeason,
              onSelectSeason = onSelectSeason,
              isFetchingEpisodes = isFetchingEpisodes,
              episodes = seasonEpisodes,
              selectedEpisode = selectedEpisode,
              onSelectEpisode = onSelectEpisode,
              onClose = onClearMediaSelection,
            )
          }
        }

        // Autocomplete Results - Horizontal Scrollable
        if (showWyzieSelection && mediaSearchResults.isNotEmpty()) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = MaterialTheme.spacing.medium)
          ) {
            Text(
              text = "Found ${mediaSearchResults.size}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.outline,
              modifier = Modifier
                .padding(bottom = MaterialTheme.spacing.small)
                .padding(start = MaterialTheme.spacing.small)
            )
            androidx.compose.foundation.lazy.LazyRow(
              horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
              modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
              contentPadding = PaddingValues(horizontal = MaterialTheme.spacing.small)
            ) {
              items(count = mediaSearchResults.size, key = { index -> mediaSearchResults[index].id }) { index ->
                val result = mediaSearchResults[index]
                TmdbMediaCard(
                  result = result,
                  onClick = { 
                    searchQuery = result.title
                    onSelectMedia(result)
                    keyboardController?.hide()
                  }
                )
              }
            }
          }
        }

      }
      if (isSearching) {
        LinearProgressIndicator(
          modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.medium).height(2.dp),
          color = MaterialTheme.colorScheme.primary
        )
      }

      LazyColumn {
        items(items) { item ->
          when (item) {
            is OnlineSubtitleItem.OnlineTrack -> {
              OnlineSubtitleRow(
                subtitle = item.subtitle,
                onDownload = onDownloadOnline,
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.small, vertical = 2.dp),
              )
            }
            is OnlineSubtitleItem.Header -> {
              val isOnlineHeader =
                item.title.startsWith("Online Results") || item.title.startsWith("Verified Matches")
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .then(if (isOnlineHeader) Modifier.clickable { onToggleOnlineSection() } else Modifier)
                    .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  text = item.title,
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.Bold,
                )
                if (isOnlineHeader) {
                  Icon(
                    imageVector = if (isOnlineSectionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                  )
                }
              }
            }
            OnlineSubtitleItem.Divider -> {
              HorizontalDivider(
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
              )
            }
          }
        }
      }
    }
  }
}



@Composable
fun OnlineSubtitleRow(
    subtitle: OnlineSubtitle,
    onDownload: (OnlineSubtitle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groupEpisodes = remember(subtitle) { subtitle.subdlGroupEpisodeRange()?.toList().orEmpty() }
    var selectedGroupEpisode by remember(subtitle.id, subtitle.url, groupEpisodes.firstOrNull()) {
        mutableStateOf(groupEpisodes.firstOrNull())
    }
    val subtitleForDownload =
        selectedGroupEpisode?.let { subtitle.withSelectedSubdlGroupEpisode(it) } ?: subtitle

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onDownload(subtitleForDownload) },
        shape = MaterialTheme.shapes.medium,
        color =
          if (subtitle.isHashMatch) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
          } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
          }
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Verification badge
            if (subtitle.isHashMatch) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Verified Sync",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Main subtitle info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Title row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = subtitle.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // Download count badge
                    subtitle.downloadCount?.let { count ->
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .wrapContentWidth()
                                .height(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (count >= 1000) "${(count / 1000f).toInt()}k" else "$count",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Metadata row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                ) {
                    // Language
                    Text(
                        text = subtitle.displayLanguage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )

                    // Source
                    subtitle.source?.let { source ->
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Text(
                            text = source,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Format
                    subtitle.format?.let { format ->
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Text(
                            text = format.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Perfect sync indicator
                    if (subtitle.isHashMatch) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Text(
                            text = "SYNC",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (groupEpisodes.isNotEmpty()) {
                SubdlEpisodeDropdown(
                    episodes = groupEpisodes,
                    selectedEpisode = selectedGroupEpisode ?: groupEpisodes.first(),
                    onEpisodeSelected = { selectedGroupEpisode = it },
                )
            }

            // Download button
            IconButton(
                onClick = { onDownload(subtitleForDownload) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Download",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun SubdlEpisodeDropdown(
    episodes: List<Int>,
    selectedEpisode: Int,
    onEpisodeSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        FilledTonalButton(
            onClick = { expanded = true },
            modifier = Modifier
                .height(32.dp)
                .widthIn(min = 74.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = "Ep $selectedEpisode",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 300.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            episodes.forEach { episode ->
                DropdownMenuItem(
                    text = { Text("Episode $episode") },
                    onClick = {
                        onEpisodeSelected(episode)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun TmdbMediaCard(
    result: app.gyrolet.mpvrx.repository.wyzie.WyzieTmdbResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val posterUrl = tmdbPosterUrl(result.poster, "w154")

    Surface(
        modifier = modifier
            .width(110.dp)
            .height(160.dp)
            .clickable { onClick() }
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            ),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Box {
            // Poster image
            if (posterUrl != null) {
                RemoteImage(
                    url = posterUrl,
                    contentDescription = result.title,
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                    alpha = 0.9f
                )
            } else {
                Icon(
                    Icons.Default.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp)
                )
            }

            // Gradient overlay for text
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.8f)
                            )
                        )
                    )
            )

            // Title + year
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 10.sp
                )
                result.releaseYear?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 8.sp
                    )
                }
            }
        }
    }
}

@Composable
fun TmdbResultRow(
    result: app.gyrolet.mpvrx.repository.wyzie.WyzieTmdbResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val posterUrl = tmdbPosterUrl(result.poster, "w92")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Poster image (2:3 aspect ratio)
        if (posterUrl != null) {
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(72.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                RemoteImage(
                    url = posterUrl,
                    contentDescription = result.title,
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                    alpha = 0.9f
                )
            }
        } else {
            // Placeholder when no poster
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(72.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Text info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${result.mediaType.uppercase()} ${result.releaseYear ?: ""}".trim(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

private fun tmdbPosterUrl(
    path: String?,
    size: String,
): String? {
    val value = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true) -> value
        value.startsWith("/") -> "https://image.tmdb.org/t/p/$size$value"
        else -> "https://image.tmdb.org/t/p/$size/$value"
    }
}

@Composable
private fun SeriesSelectionControls(
    tvShow: app.gyrolet.mpvrx.repository.wyzie.WyzieTvShowDetails,
    selectedSeason: app.gyrolet.mpvrx.repository.wyzie.WyzieSeason?,
    onSelectSeason: (app.gyrolet.mpvrx.repository.wyzie.WyzieSeason) -> Unit,
    isFetchingEpisodes: Boolean,
    episodes: ImmutableList<app.gyrolet.mpvrx.repository.wyzie.WyzieEpisode>,
    selectedEpisode: app.gyrolet.mpvrx.repository.wyzie.WyzieEpisode?,
    onSelectEpisode: (app.gyrolet.mpvrx.repository.wyzie.WyzieEpisode) -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val seasonDropdownExpanded = remember { mutableStateOf(false) }
        Box {
            FilledTonalButton(
                onClick = { seasonDropdownExpanded.value = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(48.dp).widthIn(min = 68.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = selectedSeason?.let { "S${it.season_number}" } ?: "Season",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(
                expanded = seasonDropdownExpanded.value,
                onDismissRequest = { seasonDropdownExpanded.value = false },
                modifier = Modifier.heightIn(max = 300.dp),
                shape = RoundedCornerShape(12.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                tvShow.seasons.forEach { season ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Season ${season.season_number}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        onClick = {
                            onSelectSeason(season)
                            seasonDropdownExpanded.value = false
                        },
                    )
                }
            }
        }

        val episodeDropdownExpanded = remember { mutableStateOf(false) }
        Box {
            FilledTonalButton(
                onClick = { episodeDropdownExpanded.value = true },
                enabled = selectedSeason != null && !isFetchingEpisodes,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(48.dp).widthIn(min = 68.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                if (isFetchingEpisodes) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = selectedEpisode?.let { "E${it.episode_number}" } ?: "Ep",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(
                expanded = episodeDropdownExpanded.value,
                onDismissRequest = { episodeDropdownExpanded.value = false },
                modifier = Modifier.heightIn(max = 300.dp).widthIn(min = 200.dp),
                shape = RoundedCornerShape(12.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                episodes.forEach { episode ->
                    DropdownMenuItem(
                        text = {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    "Ep ${episode.episode_number}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                episode.name?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        modifier = Modifier.basicMarquee(),
                                    )
                                }
                            }
                        },
                        onClick = {
                            onSelectEpisode(episode)
                            episodeDropdownExpanded.value = false
                        },
                    )
                }
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
        }
    }
}




