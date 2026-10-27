/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.database.repository.NetworkStreamEntryRepository
import app.gyrolet.mpvrx.domain.torrent.isTorrentSource
import app.gyrolet.mpvrx.domain.torrent.normalizeTorrentSource
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
import app.gyrolet.mpvrx.utils.media.MediaInfoParser
import app.gyrolet.mpvrx.utils.media.MediaUtils
import app.gyrolet.mpvrx.utils.media.SharedUrlExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayLinkSheet(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  onPlayLink: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (!isOpen) return

  var linkInputUrl by remember { mutableStateOf("") }
  var isLinkInputUrlValid by remember { mutableStateOf(true) }
  var isSubmitting by remember { mutableStateOf(false) }
  val coroutineScope = rememberCoroutineScope()
  val context = LocalContext.current
  val ytdlPreferences = koinInject<YtdlPreferences>()
  val streamEntryRepository = koinInject<NetworkStreamEntryRepository>()

  val handleDismiss = { onDismiss() }

  val normalizedInput = SharedUrlExtractor.normalizeInput(linkInputUrl)
  val isPlaylistInput =
    isLinkInputUrlValid &&
      YtdlpManager.isPotentialPlaylistUrl(normalizedInput) &&
      YtdlpManager.requiresYtdlp(normalizedInput)

  val handleConfirm = {
    val url = normalizedInput
    if (url.isNotBlank() && MediaUtils.isURLValid(url)) {
      val playableSource = normalizeTorrentSource(url) ?: url
      isSubmitting = true
      coroutineScope.launch {
        try {
          val extractedPlaylist =
            if (isPlaylistInput) {
              YtdlpManager.extractPlaylist(context, playableSource, ytdlPreferences).getOrNull()
            } else {
              null
            }
          val firstEntry = extractedPlaylist?.entries?.firstOrNull()
          val selectedSource = firstEntry?.url ?: playableSource
          val selectedName = firstEntry?.title ?: MediaInfoParser.parseStreamTitle(playableSource)
          val selectedUri = runCatching { android.net.Uri.parse(selectedSource) }.getOrNull()
          val artworkUrl =
            firstEntry?.thumbnailUrl
              ?: if (
                app.gyrolet.mpvrx.utils.media.HttpUtils.isMusicStreamingUrl(selectedUri) ||
                  app.gyrolet.mpvrx.utils.media.HttpUtils.isYouTubeUrl(selectedUri)
              ) {
                app.gyrolet.mpvrx.utils.media.HttpUtils.fetchMusicStreamingArtwork(selectedSource)
              } else {
                null
              }
          if (!isTorrentSource(selectedSource)) {
            try {
              RecentlyPlayedOps.addRecentlyPlayed(
                filePath = selectedSource,
                fileName = selectedName,
                launchSource = "play_link",
                artworkUrl = artworkUrl,
              )
              streamEntryRepository.saveNormalEntry(
                canonicalSourceUri = selectedSource,
                fileName = selectedName,
                posterUrl = firstEntry?.thumbnailUrl,
                backdropUrl = firstEntry?.thumbnailUrl,
              )

              val uri = selectedUri
              if (firstEntry == null && app.gyrolet.mpvrx.utils.media.HttpUtils.isYouTubeUrl(uri)) {
                val ytMeta = app.gyrolet.mpvrx.utils.media.HttpUtils.fetchYouTubeMetadata(playableSource)
                if (ytMeta != null && ytMeta.title.isNotBlank()) {
                  RecentlyPlayedOps.updateVideoMetadata(
                    filePath = selectedSource,
                    videoTitle = ytMeta.title,
                    duration = 0L,
                    fileSize = 0L,
                    width = 0,
                    height = 0,
                  )
                  streamEntryRepository.saveNormalEntry(
                    canonicalSourceUri = selectedSource,
                    fileName = ytMeta.title,
                    posterUrl = ytMeta.thumbnailUrl,
                    backdropUrl = ytMeta.thumbnailUrl,
                  )
                }
              }
            } catch (cancellation: CancellationException) {
              throw cancellation
            } catch (_: Exception) {
              // Playback must still open even if optional history persistence fails.
            }
          }
          if (extractedPlaylist != null) {
            YtdlpManager.playPlaylist(context, extractedPlaylist, "play_link")
          } else {
            onPlayLink(playableSource)
          }
          onDismiss()
        } finally {
          isSubmitting = false
        }
      }
    }
  }

  val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

  ModalBottomSheet(
    onDismissRequest = handleDismiss,
    sheetState = sheetState,
    dragHandle = { BottomSheetDefaults.DragHandle() },
    modifier = modifier,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Title
      Text(
        text =
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_play_link),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
      )

      // URL Input
      Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(
          value = linkInputUrl,
          onValueChange = { newValue ->
            linkInputUrl = newValue
            val normalizedInput = SharedUrlExtractor.normalizeInput(newValue)
            isLinkInputUrlValid = newValue.isBlank() || MediaUtils.isURLValid(normalizedInput)
          },
          modifier = Modifier.fillMaxWidth(),
          label = {
            Text(
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_enter_url),
            )
          },
          placeholder = { Text("https://example.com/video.mp4") },
          singleLine = true,
          enabled = !isSubmitting,
          isError = linkInputUrl.isNotBlank() && !isLinkInputUrlValid,
          trailingIcon = {
            if (linkInputUrl.isNotBlank()) {
              ValidationIcon(isValid = isLinkInputUrlValid)
            }
          },
        )

        if (linkInputUrl.isNotBlank() && !isLinkInputUrlValid) {
          Text(
            text =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_unsupported_url_protocol),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
          )
        }
      }

      // Buttons
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
      ) {
        TextButton(onClick = handleDismiss) {
          Text(
            text =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.generic_cancel),
            fontWeight = FontWeight.Medium,
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        val linkDownloadCoordinator = koinInject<app.gyrolet.mpvrx.domain.download.LinkDownloadCoordinator>()
        OutlinedButton(
          onClick = {
            val url = normalizedInput
            if (url.isNotBlank() && MediaUtils.isURLValid(url)) {
              val playableSource = normalizeTorrentSource(url) ?: url
              if (isTorrentSource(playableSource)) {
                // Torrents download through the torrent flow.
                onPlayLink(playableSource)
              } else {
                when (linkDownloadCoordinator.enqueue(playableSource, MediaInfoParser.parseStreamTitle(playableSource))) {
                  app.gyrolet.mpvrx.domain.download.LinkDownloadCoordinator.Route.UNSUPPORTED ->
                    app.gyrolet.mpvrx.ui.components.AppToast
                      .makeText(context, app.gyrolet.mpvrx.R.string.downloads_location_invalid, app.gyrolet.mpvrx.ui.components.AppToast.LENGTH_SHORT)
                      .show()
                  else ->
                    app.gyrolet.mpvrx.ui.components.AppToast
                      .makeText(context, app.gyrolet.mpvrx.R.string.downloads_started, app.gyrolet.mpvrx.ui.components.AppToast.LENGTH_SHORT)
                      .show()
                }
              }
              onDismiss()
            }
          },
          enabled = linkInputUrl.isNotBlank() && isLinkInputUrlValid && !isSubmitting,
        ) {
          Text(
            text =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.downloads_download),
            fontWeight = FontWeight.Medium,
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
          onClick = handleConfirm,
          enabled = linkInputUrl.isNotBlank() && isLinkInputUrlValid && !isSubmitting,
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
          if (isSubmitting) {
            CircularProgressIndicator(
              modifier = Modifier.height(18.dp).width(18.dp),
              strokeWidth = 2.dp,
            )
          } else {
            Text(
              text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_play),
              fontWeight = FontWeight.SemiBold,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))
    }
  }
}

@Composable
private fun ValidationIcon(isValid: Boolean) {
  if (isValid) {
    Icon(
      Icons.RoundedFilled.Check,
      contentDescription =
        androidx.compose.ui.res
          .stringResource(app.gyrolet.mpvrx.R.string.ui_valid_url),
      tint = MaterialTheme.colorScheme.primary,
    )
  } else {
    Icon(
      Icons.RoundedFilled.Close,
      contentDescription =
        androidx.compose.ui.res
          .stringResource(app.gyrolet.mpvrx.R.string.ui_invalid_url),
      tint = MaterialTheme.colorScheme.error,
    )
  }
}
