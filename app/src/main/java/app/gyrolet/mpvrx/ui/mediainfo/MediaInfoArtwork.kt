// SPDX-License-Identifier: AGPL-3.0-or-later

package app.gyrolet.mpvrx.ui.mediainfo

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.utils.logging.Log
import app.gyrolet.mpvrx.utils.media.MediaArtwork
import app.gyrolet.mpvrx.utils.media.MediaArtworkOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import app.gyrolet.mpvrx.ui.components.AppToast as Toast

private const val TAG = "MediaInfoArtwork"

@Composable
internal fun MediaArtworkPanel(
  artwork: MediaArtwork,
  fileName: String,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var saving by remember(artwork) { mutableStateOf(false) }
  val preview by produceState<Result<ImageBitmap>?>(null, artwork) {
    value =
      withContext(Dispatchers.IO) {
        try {
          Result.success(MediaArtworkOps.decodePreview(artwork, 1_024).asImageBitmap())
        } catch (error: IOException) {
          Log.w(TAG, "Could not display artwork", error)
          Result.failure(error)
        }
      }
  }
  val saveImage =
    rememberLauncherForActivityResult(
      ActivityResultContracts.CreateDocument(artwork.mimeType),
    ) { target ->
      if (target != null) {
        saving = true
        scope.launch {
          try {
            MediaArtworkOps.save(context, target, artwork)
            Toast.success(context, context.getString(R.string.media_info_artwork_saved))
          } catch (error: IOException) {
            Log.w(TAG, "Could not save artwork", error)
            Toast.error(context, context.getString(R.string.media_info_artwork_save_failed))
          } catch (error: SecurityException) {
            Log.w(TAG, "Permission to save artwork was denied", error)
            Toast.error(context, context.getString(R.string.media_info_artwork_save_failed))
          } finally {
            saving = false
          }
        }
      }
    }

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      text = stringResource(R.string.media_info_artwork_title),
      style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.primary,
    )
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      color = mediaInfoDetailContainerColor(),
    ) {
      Box(
        modifier = Modifier.fillMaxWidth().height(280.dp),
        contentAlignment = Alignment.Center,
      ) {
        val image = preview?.getOrNull()
        when {
          preview == null -> CircularProgressIndicator(modifier = Modifier.size(32.dp))
          image != null ->
            Image(
              bitmap = image,
              contentDescription = stringResource(R.string.media_info_artwork_title),
              contentScale = ContentScale.Fit,
              modifier = Modifier.fillMaxSize(),
            )
          else ->
            Text(
              text = stringResource(R.string.media_info_artwork_preview_failed),
              modifier = Modifier.padding(16.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
      }
    }
    val shape = rememberConnectedShape(index = 0, itemCount = 1, layout = ConnectedLayout.Horizontal)
    Surface(
      onClick = {
        try {
          saveImage.launch(artwork.downloadName(fileName))
        } catch (error: ActivityNotFoundException) {
          Log.w(TAG, "No document picker is available to save artwork", error)
          Toast.error(context, context.getString(R.string.media_info_artwork_save_failed))
        }
      },
      enabled = !saving,
      shape = shape,
      color = MaterialTheme.colorScheme.secondaryContainer,
      contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
      modifier = Modifier.fillMaxWidth().tvFocusHighlight(shape),
    ) {
      Row(
        modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (saving) {
          CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = LocalContentColor.current,
          )
        } else {
          Icon(imageVector = Icons.RoundedFilled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = stringResource(R.string.media_info_artwork_download),
          style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
      }
    }
  }
}
