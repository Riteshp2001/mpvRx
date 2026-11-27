// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.presentation.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppConnectedControlShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

private data class DeleteSize(
  val bytes: Long?,
  val loading: Boolean = false,
)

/** A read-only size segment connected to the existing destructive confirmation action. */
@Composable
fun DeleteSizeButton(
  onClick: () -> Unit,
  paths: List<String> = emptyList(),
  totalSizeBytes: Long? = null,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val size by produceState(DeleteSize(totalSizeBytes, paths.isNotEmpty()), paths, totalSizeBytes) {
    if (paths.isNotEmpty()) {
      value = DeleteSize(withContext(Dispatchers.IO) { deletionSize(context, paths) } ?: totalSizeBytes)
    }
  }
  val sizeText =
    when {
      size.bytes != null -> Formatter.formatShortFileSize(context, checkNotNull(size.bytes))
      size.loading -> stringResource(R.string.delete_size_loading)
      else -> stringResource(R.string.delete_size_unknown)
    }
  val sizeDescription = stringResource(R.string.delete_size_description, sizeText)
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      // Measure the action first, then let the size wrap its text within the remaining space.
      modifier = Modifier.weight(1f, fill = false),
      shape = rememberConnectedShape(0, 2, ConnectedLayout.Horizontal, tokens = AppConnectedControlShapeTokens),
      color = MaterialTheme.colorScheme.errorContainer,
      contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
      Box(
        Modifier
          .heightIn(min = ButtonDefaults.MinHeight)
          .semantics { contentDescription = sizeDescription }
          .padding(ButtonDefaults.TextButtonContentPadding),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          sizeText,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          softWrap = false,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    val actionShape = rememberConnectedShape(1, 2, ConnectedLayout.Horizontal, tokens = AppConnectedControlShapeTokens)
    TextButton(
      onClick = onClick,
      modifier = Modifier.tvFocusHighlight(actionShape, focusedScale = 1.04f),
      shape = actionShape,
      colors =
        ButtonDefaults.textButtonColors(
          containerColor = MaterialTheme.colorScheme.error,
          contentColor = MaterialTheme.colorScheme.onError,
        ),
      contentPadding = ButtonDefaults.TextButtonContentPadding,
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.RoundedFilled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(
          stringResource(R.string.delete),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          softWrap = false,
        )
      }
    }
  }
}

/** Count each file once, including nested folders, without blocking the dialog or following cycles. */
private suspend fun deletionSize(
  context: Context,
  paths: List<String>,
): Long? {
  val visited = HashSet<String>()
  val pending = ArrayDeque<File>()
  var total = 0L
  try {
    for (source in paths.distinct()) {
      currentCoroutineContext().ensureActive()
      val uri = Uri.parse(source)
      when (uri.scheme) {
        null, "file" -> pending.addLast(File(if (uri.scheme == "file") uri.path ?: return null else source))
        "content" -> {
          val bytes =
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
              if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0L } else null
            } ?: return null
          total += bytes
        }
        else -> return null
      }
    }
    while (pending.isNotEmpty()) {
      currentCoroutineContext().ensureActive()
      val file = pending.removeLast()
      if (!visited.add(file.canonicalPath)) continue
      if (file.isDirectory) {
        val children = file.listFiles() ?: return null
        children.forEach(pending::addLast)
      } else if (file.isFile) {
        total += file.length()
      } else {
        return null
      }
    }
    return total
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    return null
  }
}
