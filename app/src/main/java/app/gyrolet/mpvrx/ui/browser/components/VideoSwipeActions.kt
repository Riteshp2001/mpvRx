package app.gyrolet.mpvrx.ui.browser.components

import android.util.Log
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.repository.VideoMetadataCacheRepository
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.VideoSwipeAction
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.repository.MediaFileRepository
import app.gyrolet.mpvrx.ui.browser.dialogs.AddToPlaylistDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.toPlaylistCandidates
import app.gyrolet.mpvrx.ui.browser.videolist.VideoWithPlaybackInfo
import app.gyrolet.mpvrx.ui.browser.videolist.buildVideoWithPlaybackInfo
import app.gyrolet.mpvrx.ui.browser.videolist.videoPlaybackIdentifiers
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import app.gyrolet.mpvrx.utils.media.PlaybackStateOps
import app.gyrolet.mpvrx.utils.permission.PermissionUtils.StorageOps
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import java.nio.file.Files

internal class VideoSwipeActions(
  val video: (Video, Boolean, VideoSwipeAction) -> Unit,
  val folder: (VideoFolder, VideoSwipeAction) -> Unit,
)

private data class SwipeTarget(val videos: List<Video>, val folderName: String? = null)

@Composable
internal fun rememberSwipePlaybackInfo(videos: List<Video>): Map<String, VideoWithPlaybackInfo> {
  val preferences = koinInject<BrowserPreferences>()
  val appearance = koinInject<AppearancePreferences>()
  val repository = koinInject<PlaybackStateRepository>()
  val threshold by preferences.watchedThreshold.collectAsState()
  val newDays by appearance.unplayedOldVideoDays.collectAsState()
  var playbackInfo by remember { mutableStateOf(emptyMap<String, VideoWithPlaybackInfo>()) }
  LaunchedEffect(videos, threshold, newDays) {
    // Playback saves arrive every few seconds while anything plays and name a single item. Rebuilding
    // the whole list for each one recomputed every song or video; only matching entries change.
    var videosByIdentifier: Map<String, List<Video>>? = null
    PlaybackStateEvents.changes.onStart { emit("") }.collectLatest { identifier ->
      val index = videosByIdentifier
      if (identifier.isNotBlank() && index != null) {
        val affected = index[identifier] ?: return@collectLatest
        val current = playbackInfo
        playbackInfo = withContext(Dispatchers.IO) {
          val now = System.currentTimeMillis()
          current + affected.associate { video ->
            video.path to buildVideoWithPlaybackInfo(
              video = video,
              playbackState = videoPlaybackIdentifiers(video).firstNotNullOfOrNull { repository.getVideoDataByTitle(it) },
              currentTimeMillis = now,
              newLabelDays = newDays,
              watchedThreshold = threshold,
            )
          }
        }
        return@collectLatest
      }
      val (rebuiltInfo, rebuiltIndex) = withContext(Dispatchers.IO) {
        val states = repository.getAllPlaybackStates().associateBy { it.mediaTitle }
        val now = System.currentTimeMillis()
        val byIdentifier = HashMap<String, MutableList<Video>>()
        val info =
          videos.associate { video ->
            val identifiers = videoPlaybackIdentifiers(video)
            identifiers.forEach { byIdentifier.getOrPut(it) { ArrayList(1) }.add(video) }
            video.path to buildVideoWithPlaybackInfo(
              video = video,
              playbackState = identifiers.firstNotNullOfOrNull(states::get),
              currentTimeMillis = now,
              newLabelDays = newDays,
              watchedThreshold = threshold,
            )
          }
        info to byIdentifier
      }
      videosByIdentifier = rebuiltIndex
      playbackInfo = rebuiltInfo
    }
  }
  return playbackInfo
}

@Composable
internal fun rememberVideoSwipeActions(
  onDeleted: suspend (List<Video>) -> Unit = {},
  audioOnly: Boolean = false,
  onChanged: () -> Unit,
): VideoSwipeActions {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val haptics = rememberAppHaptics()
  val metadataCache = koinInject<VideoMetadataCacheRepository>()
  val preferences = koinInject<BrowserPreferences>()
  val foldersPreferences = koinInject<app.gyrolet.mpvrx.preferences.FoldersPreferences>()
  val currentOnChanged by rememberUpdatedState(onChanged)
  val currentOnDeleted by rememberUpdatedState(onDeleted)
  var deleteTarget by remember { mutableStateOf<SwipeTarget?>(null) }
  var playlistTarget by remember { mutableStateOf<SwipeTarget?>(null) }
  var busy by remember { mutableStateOf(false) }
  var collecting by remember { mutableStateOf(false) }
  var scanJob by remember { mutableStateOf<Job?>(null) }

  if (collecting) {
    AlertDialog(
      onDismissRequest = { scanJob?.cancel() },
      title = { Text(stringResource(if (audioOnly) R.string.audio_swipe_loading else R.string.video_swipe_loading)) },
      text = {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(Modifier.size(24.dp))
          Text(stringResource(R.string.video_swipe_folder_videos))
        }
      },
      confirmButton = {
        TextButton(onClick = { scanJob?.cancel() }) { Text(stringResource(R.string.generic_cancel)) }
      },
    )
  }

  deleteTarget?.let { target ->
    val isAudio = target.videos.all { it.isAudio }
    AlertDialog(
      onDismissRequest = { if (!busy) deleteTarget = null },
      icon = { Icon(Icons.RoundedFilled.Delete, null, tint = MaterialTheme.colorScheme.error) },
      title = { Text(stringResource(if (isAudio) R.string.audio_swipe_delete_title else R.string.video_swipe_delete_title)) },
      text = {
        Text(
          if (target.folderName != null) {
            stringResource(
              if (isAudio) R.string.audio_swipe_delete_folder_message else R.string.video_swipe_delete_folder_message,
              target.videos.size,
              target.folderName,
            )
          } else {
            stringResource(R.string.video_swipe_delete_message, target.videos.single().displayName)
          },
        )
      },
      confirmButton = {
        TextButton(
          enabled = !busy,
          onClick = {
            if (!busy) {
              busy = true
              scope.launch {
                try {
                  val confirmedDeletes = mutableListOf<Video>()
                  val (deleted, failed) = StorageOps.deleteVideos(context, target.videos) { confirmedDeletes += it }
                  if (deleted > 0) {
                    metadataCache.invalidateVideos(confirmedDeletes.map { it.path })
                    val deletedVideos = if (app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY) {
                      confirmedDeletes
                    } else {
                      withContext(Dispatchers.IO) {
                        confirmedDeletes.filter { video ->
                          runCatching { Files.notExists(File(video.path).toPath()) }.getOrDefault(false)
                        }
                      }
                    }
                    currentOnDeleted(deletedVideos)
                    haptics.confirm()
                    currentOnChanged()
                  }
                  val message = context.getString(R.string.video_swipe_delete_result, deleted, failed)
                  Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                } catch (cancelled: CancellationException) {
                  throw cancelled
                } catch (error: Exception) {
                  Log.e("VideoSwipeActions", "Unable to delete video", error)
                  Toast.makeText(context, R.string.ui_failed_to_delete, Toast.LENGTH_SHORT).show()
                } finally {
                  deleteTarget = null
                  busy = false
                }
              }
            }
          },
        ) {
          if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
          else Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(enabled = !busy, onClick = { deleteTarget = null }) { Text(stringResource(R.string.generic_cancel)) }
      },
    )
  }

  playlistTarget?.let { target ->
    AddToPlaylistDialog(
      isOpen = true,
      candidates = target.videos.toPlaylistCandidates(),
      onDismiss = { playlistTarget = null },
      onSuccess = { currentOnChanged() },
      onItemsAdded = haptics::confirm,
    )
  }

  val perform: (SwipeTarget, VideoSwipeAction) -> Unit = { target, action ->
    when (action) {
      VideoSwipeAction.None -> Unit
      VideoSwipeAction.Delete -> deleteTarget = target
      VideoSwipeAction.AddToPlaylist -> playlistTarget = target
      VideoSwipeAction.PlayNext, VideoSwipeAction.AddToQueue -> {
        val insertion = if (action == VideoSwipeAction.PlayNext) QueueInsertion.PlayNext else QueueInsertion.AddToEnd
        if (addVideosToPlaybackQueue(context, target.videos, insertion)) haptics.confirm()
      }
      VideoSwipeAction.ToggleWatched,
      VideoSwipeAction.MarkNew,
      VideoSwipeAction.LastPlayed,
      VideoSwipeAction.Finished,
      VideoSwipeAction.ClearHistory,
      -> {
        busy = true
        scope.launch {
          try {
            when (action) {
              VideoSwipeAction.ToggleWatched -> {
                val watched = !PlaybackStateOps.areAllWatched(target.videos, preferences.watchedThreshold.get())
                PlaybackStateOps.setWatched(target.videos, watched)
                haptics.selection(watched)
              }
              VideoSwipeAction.Finished -> {
                PlaybackStateOps.setWatched(target.videos, true)
                haptics.confirm()
              }
              VideoSwipeAction.MarkNew, VideoSwipeAction.ClearHistory -> {
                PlaybackStateOps.resetWatchHistory(target.videos, markAsNew = action == VideoSwipeAction.MarkNew)
                haptics.confirm()
              }
              VideoSwipeAction.LastPlayed -> {
                if (PlaybackStateOps.markLastPlayed(target.videos)) {
                  haptics.confirm()
                } else {
                  Toast.makeText(context, R.string.video_swipe_recent_disabled, Toast.LENGTH_SHORT).show()
                }
              }
            }
            currentOnChanged()
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            Log.e("VideoSwipeActions", "Unable to update video marks", error)
            Toast.makeText(context, R.string.generic_unknown_error, Toast.LENGTH_SHORT).show()
          } finally {
            busy = false
          }
        }
      }
    }
  }

  return VideoSwipeActions(
    video = { video, _, action ->
      if (!busy && !collecting && deleteTarget == null && playlistTarget == null &&
        (video.path.startsWith('/') ||
          (app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY &&
            video.uri.scheme == "content" &&
            android.provider.DocumentsContract.isDocumentUri(context, video.uri)))) {
        perform(SwipeTarget(listOf(video)), action)
      }
    },
    folder = { folder, action ->
      val isScopedDirectory = app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY
      if (!busy && !collecting && deleteTarget == null && playlistTarget == null &&
        action != VideoSwipeAction.None && !(isScopedDirectory && action == VideoSwipeAction.Delete)
      ) {
        collecting = true
        scanJob = scope.launch {
          try {
            val videos = withContext(Dispatchers.IO) {
              if (app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY &&
                app.gyrolet.mpvrx.utils.media.ScopedHiddenFolderScanner.isScopedFolder(folder.bucketId)
              ) {
                // SAF folder buckets are document-tree URIs, not File paths.
                return@withContext app.gyrolet.mpvrx.utils.media.ScopedHiddenFolderScanner
                  .getVideosRecursively(
                    context = context,
                    bucketId = folder.bucketId,
                    grantedTreeUris = foldersPreferences.scopedHiddenFolderUris.get(),
                    includeAudio = audioOnly || preferences.includeAudioBrowser.get(),
                    audioOnly = audioOnly,
                  )
              }
              if (app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY) {
                val folders = if (audioOnly) {
                  MediaFileRepository.getAllAudioFolders(context, preferences.minimumAudioDurationSeconds.get())
                } else {
                  MediaFileRepository.getAllVideoFolders(context, includeAudioOverride = preferences.includeAudioBrowser.get())
                }
                val buckets = folders.filter {
                  app.gyrolet.mpvrx.utils.storage.isMediaPathInFolder(it.path, folder.path)
                }.mapTo(mutableSetOf()) { it.bucketId }
                return@withContext MediaFileRepository.getVideosForBuckets(
                  context, buckets, includeAudioOverride = audioOnly || preferences.includeAudioBrowser.get(),
                ).filter { !audioOnly || it.isAudio }
              }
              val root = File(folder.path).canonicalFile
              require(root.isDirectory && root.canRead())
              val coroutineContext = currentCoroutineContext()
              val files = root.walkTopDown()
                .onEnter { directory ->
                  coroutineContext.ensureActive()
                  !Files.isSymbolicLink(directory.toPath())
                }
                .onFail { _, error -> throw error }
                .filter { file ->
                  coroutineContext.ensureActive()
                  file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                    (if (audioOnly) FileTypeUtils.isAudioFile(file) else FileTypeUtils.isVideoFile(file))
                }.toList()
              val resolved = MediaFileRepository.getVideosFromFiles(context, files).let { videos ->
                if (audioOnly) videos.map { it.copy(isAudio = true) } else videos.filterNot { it.isAudio }
              }
              coroutineContext.ensureActive()
              check(resolved.size == files.size)
              resolved.distinctBy { it.path }.sortedBy { it.path.lowercase() }
            }
            currentCoroutineContext().ensureActive()
            if (videos.isEmpty()) {
              Toast.makeText(context, if (audioOnly) R.string.audio_swipe_folder_empty else R.string.video_swipe_folder_empty,
                Toast.LENGTH_SHORT).show()
            } else {
              perform(SwipeTarget(videos, folder.name), action)
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            Log.e("VideoSwipeActions", "Unable to collect folder videos", error)
            Toast.makeText(context, R.string.generic_unknown_error, Toast.LENGTH_SHORT).show()
          } finally {
            collecting = false
            scanJob = null
          }
        }
      }
    },
  )
}