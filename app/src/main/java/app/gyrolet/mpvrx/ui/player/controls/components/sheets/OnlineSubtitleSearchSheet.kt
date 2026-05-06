package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitle
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleSearchMode
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.utils.media.MediaInfoParser
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

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
  subtitleSearchMode: OnlineSubtitleSearchMode = OnlineSubtitleSearchMode.HYBRID,
  onSearchModeChange: (OnlineSubtitleSearchMode) -> Unit = {},
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
  val parsedMediaInfo = remember(mediaTitle) { MediaInfoParser.parse(mediaTitle) }
  val activeEpisodeLabel = remember(selectedSeason, selectedEpisode, parsedMediaInfo) {
    val season = selectedSeason?.season_number ?: selectedEpisode?.season_number ?: parsedMediaInfo.season
    val episode = selectedEpisode?.episode_number ?: parsedMediaInfo.episode
    if (season != null && episode != null) {
      "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
    } else {
      null
    }
  }

  val items = remember(searchResults, isSearching, isOnlineSectionExpanded, activeEpisodeLabel) {
    val list = mutableListOf<OnlineSubtitleItem>()
    
    // Online Search Results section
    if (searchResults.isNotEmpty() || isSearching) {
        val hashMatches = searchResults.count { it.isHashMatch }
        val headerText =
          when {
            hashMatches > 0 -> "Verified Matches ($hashMatches) + Others"
            activeEpisodeLabel != null -> "$activeEpisodeLabel Results (${searchResults.size})"
            else -> "Online Results (${searchResults.size})"
          }
        list.add(OnlineSubtitleItem.Header(headerText))
        if (isOnlineSectionExpanded) {
            list.addAll(searchResults.map { OnlineSubtitleItem.OnlineTrack(it) })
        }
    }

    list.toImmutableList()
  }

  GenericTracksSheet(
    tracks = items,
    onDismissRequest = onDismissRequest,
    header = {
      val keyboardController = LocalSoftwareKeyboardController.current
      val mediaInfo = parsedMediaInfo
      var searchQuery by remember { mutableStateOf(mediaInfo.title) }

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
        OutlinedTextField(
          value = searchQuery,
          onValueChange = { 
            searchQuery = it
          },
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
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
              if (isSearching || isDownloading || isSearchingMedia) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
              }
              IconButton(onClick = {
                val q = if (searchQuery.isNotBlank()) searchQuery else mediaInfo.title
                searchQuery = q
                onSearchMedia(q)
                keyboardController?.hide()
              }) {
                Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary)
              }
            }
          },
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = {
            val q = if (searchQuery.isNotBlank()) searchQuery else mediaInfo.title
            searchQuery = q
            onSearchMedia(q)
            keyboardController?.hide()
          }),
          shape = RoundedCornerShape(12.dp),
          colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
          )
        )

        SearchModeSelector(
          mode = subtitleSearchMode,
          onModeChange = { mode ->
            onSearchModeChange(mode)
            val q = if (searchQuery.isNotBlank()) searchQuery else mediaInfo.title
            if (q.isNotBlank()) onSearchMedia(q)
          },
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.medium)
            .padding(top = MaterialTheme.spacing.extraSmall, bottom = MaterialTheme.spacing.smaller)
        )

        if (activeEpisodeLabel != null) {
          EpisodeScopePill(
            text = "$activeEpisodeLabel locked",
            modifier = Modifier
              .padding(horizontal = MaterialTheme.spacing.medium)
              .padding(bottom = MaterialTheme.spacing.smaller)
          )
        }

        // Autocomplete Results
        if (mediaSearchResults.isNotEmpty()) {
          Card(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = MaterialTheme.spacing.medium)
              .heightIn(max = 200.dp),
            shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
          ) {
            androidx.compose.foundation.lazy.LazyColumn {
              items(mediaSearchResults.size) { index ->
                val result = mediaSearchResults[index]
                TmdbResultRow(
                  result = result,
                  onClick = { 
                    searchQuery = result.title
                    onSelectMedia(result)
                    keyboardController?.hide()
                  }
                )
                if (index < mediaSearchResults.size - 1) {
                  HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
              }
            }
          }
        }

        // Series / Season / Episode Selection UI
        if (selectedTvShow != null) {
          SeriesDetailsSection(
            tvShow = selectedTvShow,
            isFetchingSeasons = isFetchingTvDetails,
            selectedSeason = selectedSeason,
            onSelectSeason = onSelectSeason,
            isFetchingEpisodes = isFetchingEpisodes,
            episodes = seasonEpisodes,
            selectedEpisode = selectedEpisode,
            onSelectEpisode = onSelectEpisode,
            onClose = onClearMediaSelection
          )
        }
      }
      if (isSearching) {
        LinearProgressIndicator(
          modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.medium).height(2.dp),
          color = MaterialTheme.colorScheme.primary
        )
      }
    },
    track = { item ->
      when (item) {
        is OnlineSubtitleItem.OnlineTrack -> {
            OnlineSubtitleRow(
                subtitle = item.subtitle,
                onDownload = { onDownloadOnline(item.subtitle) },
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.small, vertical = 2.dp)
            )
        }
        is OnlineSubtitleItem.Header -> {
            val isOnlineHeader =
              item.title.startsWith("Online Results") || item.title.startsWith("Verified Matches")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isOnlineHeader) Modifier.clickable { onToggleOnlineSection() } else Modifier)
                    .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
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
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        OnlineSubtitleItem.Divider -> {
            HorizontalDivider(
              modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small),
              color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
      }
    },
    modifier = modifier,
  )
}

@Composable
fun OnlineSubtitleRow(
    subtitle: OnlineSubtitle,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable { onDownload() },
        shape = MaterialTheme.shapes.medium,
        color =
          if (subtitle.isHashMatch) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
          } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
          }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (subtitle.isHashMatch) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Verified Sync",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = subtitle.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee()
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = subtitle.displayLanguage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    subtitle.source?.let { Text(text = " • $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                    subtitle.format?.let { Text(text = " • ${it.uppercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                    if (subtitle.isHashMatch) {
                        Text(
                            text = " • PERFECT SYNC",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            IconButton(onClick = onDownload) {
                Icon(imageVector = Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SearchModeSelector(
    mode: OnlineSubtitleSearchMode,
    onModeChange: (OnlineSubtitleSearchMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        OnlineSubtitleSearchMode.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = mode == option,
                onClick = { onModeChange(option) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = OnlineSubtitleSearchMode.entries.size,
                ),
                icon = {},
            ) {
                Text(
                    text = option.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EpisodeScopePill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun TmdbResultRow(
    result: app.gyrolet.mpvrx.repository.wyzie.WyzieTmdbResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${result.mediaType.uppercase()} ${result.releaseYear ?: ""}".trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SeriesDetailsSection(
    tvShow: app.gyrolet.mpvrx.repository.wyzie.WyzieTvShowDetails,
    isFetchingSeasons: Boolean,
    selectedSeason: app.gyrolet.mpvrx.repository.wyzie.WyzieSeason?,
    onSelectSeason: (app.gyrolet.mpvrx.repository.wyzie.WyzieSeason) -> Unit,
    isFetchingEpisodes: Boolean,
    episodes: ImmutableList<app.gyrolet.mpvrx.repository.wyzie.WyzieEpisode>,
    selectedEpisode: app.gyrolet.mpvrx.repository.wyzie.WyzieEpisode?,
    onSelectEpisode: (app.gyrolet.mpvrx.repository.wyzie.WyzieEpisode) -> Unit,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.medium)
            .padding(bottom = MaterialTheme.spacing.small),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = tvShow.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Season Dropdown
                val seasonDropdownExpanded = remember { mutableStateOf(false) }
                Box {
                  FilledTonalButton(
                      onClick = { seasonDropdownExpanded.value = true },
                      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                      modifier = Modifier.height(38.dp),
                      shape = RoundedCornerShape(8.dp)
                  ) {
                      Text(
                          text = selectedSeason?.let { "S${it.season_number}" } ?: "Season",
                          style = MaterialTheme.typography.labelLarge,
                          fontWeight = FontWeight.Bold,
                          maxLines = 1
                      )
                      Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
                  }
                  DropdownMenu(
                      expanded = seasonDropdownExpanded.value,
                      onDismissRequest = { seasonDropdownExpanded.value = false },
                      modifier = Modifier.heightIn(max = 300.dp),
                      shape = RoundedCornerShape(12.dp),
                      containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                  ) {
                      tvShow.seasons.forEach { season ->
                          DropdownMenuItem(
                              text = { 
                                Text(
                                  "Season ${season.season_number}",
                                  style = MaterialTheme.typography.bodyLarge
                                ) 
                              },
                              onClick = {
                                  onSelectSeason(season)
                                  seasonDropdownExpanded.value = false
                              }
                          )
                      }
                  }
                }

                // Episode Dropdown
                val episodeDropdownExpanded = remember { mutableStateOf(false) }
                Box {
                  FilledTonalButton(
                      onClick = { episodeDropdownExpanded.value = true },
                      enabled = selectedSeason != null && !isFetchingEpisodes,
                      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                      modifier = Modifier.height(38.dp),
                      shape = RoundedCornerShape(8.dp)
                  ) {
                      if (isFetchingEpisodes) {
                          CircularProgressIndicator(
                            modifier = Modifier.size(16.dp), 
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                          )
                          Spacer(Modifier.width(6.dp))
                      }
                      Text(
                          text = selectedEpisode?.let { "E${it.episode_number}" } ?: "Ep",
                          style = MaterialTheme.typography.labelLarge,
                          fontWeight = FontWeight.Bold,
                          maxLines = 1
                      )
                      Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
                  }
                  DropdownMenu(
                      expanded = episodeDropdownExpanded.value,
                      onDismissRequest = { episodeDropdownExpanded.value = false },
                      modifier = Modifier.heightIn(max = 300.dp).widthIn(min = 200.dp),
                      shape = RoundedCornerShape(12.dp),
                      containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                  ) {
                      episodes.forEach { episode ->
                          DropdownMenuItem(
                              text = { 
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                  Text(
                                    "Ep ${episode.episode_number}", 
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                  )
                                  episode.name?.let { 
                                    Text(
                                      it, 
                                      style = MaterialTheme.typography.bodySmall,
                                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                                      maxLines = 1,
                                      modifier = Modifier.basicMarquee()
                                    ) 
                                  }
                                }
                              },
                              onClick = {
                                  onSelectEpisode(episode)
                                  episodeDropdownExpanded.value = false
                              }
                          )
                      }
                  }
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}




