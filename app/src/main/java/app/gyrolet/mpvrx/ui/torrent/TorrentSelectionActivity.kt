/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.torrent

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.gyrolet.mpvrx.database.repository.NetworkStreamEntryRepository
import app.gyrolet.mpvrx.domain.torrent.TorrentStreamingEngine
import app.gyrolet.mpvrx.repository.wyzie.WyzieSearchRepository
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.ui.theme.MpvrxTheme
import app.gyrolet.mpvrx.utils.media.MediaUtils
import org.koin.android.ext.android.inject

class TorrentSelectionActivity : AppCompatActivity() {
  private val torrentStreamingEngine: TorrentStreamingEngine by inject()
  private val streamEntryRepository: NetworkStreamEntryRepository by inject()
  private val wyzieSearchRepository: WyzieSearchRepository by inject()
  private val viewModel: TorrentSelectionViewModel by viewModels {
    TorrentSelectionViewModel.factory(
      torrentStreamingEngine = torrentStreamingEngine,
      streamEntryRepository = streamEntryRepository,
      wyzieSearchRepository = wyzieSearchRepository,
    )
  }

  private var playerLaunched = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val source = extractTorrentSource(intent)
    if (source.isNullOrBlank()) {
      finish()
      return
    }

    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
      navigationBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
    )

    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = closePicker()
      },
    )

    viewModel.initialize(
      TorrentSelectionInput(
        source = source,
        title =
          intent.getStringExtra(MediaUtils.EXTRA_MEDIA_TITLE)
            ?: intent.getStringExtra("title")
            ?: intent.getStringExtra("introdb_title"),
        description =
          intent.getStringExtra(MediaUtils.EXTRA_MEDIA_DESCRIPTION)
            ?: intent.getStringExtra("description")
            ?: intent.getStringExtra("overview"),
        posterUrl =
          intent.getStringExtra(MediaUtils.EXTRA_MEDIA_POSTER_URL)
            ?: intent.getStringExtra("poster")
            ?: intent.getStringExtra("poster_url"),
        backdropUrl =
          intent.getStringExtra(MediaUtils.EXTRA_MEDIA_BACKDROP_URL)
            ?: intent.getStringExtra("backdrop")
            ?: intent.getStringExtra("backdrop_url"),
      ),
    )

    setContent {
      val state by viewModel.uiState.collectAsState()
      LaunchedEffect(viewModel) {
        viewModel.launches.collect(::openPlayer)
      }
      MpvrxTheme {
        TorrentSelectionScreen(
          state = state,
          onBack = ::closePicker,
          onRetry = viewModel::retry,
          onSelect = viewModel::select,
        )
      }
    }
  }

  private fun openPlayer(request: TorrentSelectionLaunch) {
    if (playerLaunched || isFinishing) return
    playerLaunched = true
    val playbackIntent =
      Intent(intent).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse(request.source)
        setClass(this@TorrentSelectionActivity, PlayerActivity::class.java)
        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra("title", request.file.name)
        putExtra(MediaUtils.EXTRA_MEDIA_TITLE, request.file.name)
        putExtra(MediaUtils.EXTRA_TORRENT_SOURCE, request.source)
        putExtra(MediaUtils.EXTRA_TORRENT_FILE_INDEX, request.file.index)
        putExtra(MediaUtils.EXTRA_TORRENT_PREPARATION_ID, request.preparationId)
        putExtra("is_audio", request.file.mimeType.startsWith("audio/"))
      }
    startActivity(playbackIntent)
    finish()
  }

  private fun closePicker() {
    if (!playerLaunched) viewModel.cancel()
    finish()
  }

  private fun extractTorrentSource(intent: Intent?): String? {
    intent ?: return null
    intent.getStringExtra(MediaUtils.EXTRA_TORRENT_SOURCE)?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    intent.dataString?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    if (intent.action == Intent.ACTION_SEND) {
      val stream =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
          @Suppress("DEPRECATION")
          intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
      stream?.toString()?.takeIf(String::isNotBlank)?.let { return it }
      intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    }
    return null
  }
}
