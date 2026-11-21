/* SPDX-License-Identifier: AGPL-3.0-or-later */

package app.gyrolet.mpvrx.ui.torrent

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.download.AppDownloadManager
import app.gyrolet.mpvrx.domain.download.AppDownloadStatus
import app.gyrolet.mpvrx.domain.download.DownloadSources
import app.gyrolet.mpvrx.domain.torrent.canonicalInfoHash
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TorrentDownloadButton(
  source: String,
  fileIndex: Int?,
  fileName: String,
  fileSize: Long,
  title: String,
  posterUrl: String?,
  modifier: Modifier = Modifier,
  showLabel: Boolean = false,
) {
  if (!BuildConfig.ENABLE_TORRENT || fileIndex == null || fileSize <= 0) return
  val hash = canonicalInfoHash(source) ?: return
  val manager = koinInject<AppDownloadManager>()
  val downloads by manager.downloads.collectAsState()
  val key = AppDownloadManager.torrentDownloadKey(hash, fileIndex)
  val download = downloads.firstOrNull { it.entity.source == DownloadSources.TORRENT && it.entity.sourceUrl == key }
  val active = download?.isActive == true
  val completed = download?.isPlayable == true
  val retry = download?.status == AppDownloadStatus.FAILED || download?.status == AppDownloadStatus.CANCELLED
  val label = when {
    completed -> stringResource(R.string.downloads_downloaded)
    download?.status == AppDownloadStatus.RUNNING ->
      stringResource(R.string.torrent_picker_download_progress, download.entity.progress)
    active -> stringResource(R.string.downloads_queued)
    retry -> stringResource(R.string.downloads_retry)
    else -> stringResource(R.string.downloads_download)
  }
  val enabled = !active && !completed
  val onClick = {
    if (retry && download != null) manager.retry(download.id)
    else manager.enqueueTorrent(source, fileIndex, fileName, fileSize, title, posterUrl)
  }
  val indicator: @Composable () -> Unit = {
    when {
      active -> {
        if (download?.status == AppDownloadStatus.RUNNING && download.entity.progress > 0) {
          CircularProgressIndicator(
            progress = { download.entity.progress / 100f },
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
          )
        } else {
          CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
      }
      else -> Icon(
        if (completed) Icons.RoundedFilled.Check else Icons.RoundedFilled.Download,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
      )
    }
  }
  if (showLabel) {
    FilledTonalButton(
      onClick = onClick,
      enabled = enabled,
      shape = MaterialTheme.shapes.large,
      modifier = modifier.heightIn(min = 52.dp).tvFocusHighlight(MaterialTheme.shapes.large, enabled),
    ) {
      indicator()
      Spacer(Modifier.width(8.dp))
      Text(label)
    }
  } else {
    FilledTonalIconButton(
      onClick = onClick,
      enabled = enabled,
      shapes = IconButtonDefaults.shapes(),
      modifier = modifier.size(48.dp).tvFocusHighlight(MaterialTheme.shapes.large, enabled)
        .semantics { contentDescription = label },
    ) {
      indicator()
    }
  }
}
