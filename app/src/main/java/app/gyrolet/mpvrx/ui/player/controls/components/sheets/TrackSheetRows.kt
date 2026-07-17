package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.player.TrackNode
import app.gyrolet.mpvrx.ui.theme.spacing

@Composable
fun AddTrackRow(
  title: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  actions: @Composable RowScope.() -> Unit = {},
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .height(56.dp)
        .padding(horizontal = MaterialTheme.spacing.medium),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
  ) {
    Icon(
      Icons.RoundedFilled.Add,
      contentDescription = null,
      modifier = Modifier.size(24.dp),
    )
    Text(
      text = title,
      style = MaterialTheme.typography.bodyLarge,
      modifier = Modifier.weight(1f),
    )
    Row(
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      actions()
    }
  }
}

@Composable
fun getTrackTitle(track: TrackNode): String {
  val hasTitle = !track.title.isNullOrBlank()
  val hasLang = !track.lang.isNullOrBlank()

  if (track.isSubtitle && track.external == true && !hasTitle && !hasLang && track.externalFilename != null) {
    val decoded = Uri.decode(track.externalFilename)
    val fileName = decoded.substringAfterLast("/")
    return stringResource(R.string.player_sheets_track_title_wo_lang, track.id, fileName)
  }

  return when {
    hasTitle && hasLang ->
      stringResource(
        R.string.player_sheets_track_title_w_lang,
        track.id,
        track.title,
        track.lang,
      )
    hasTitle -> stringResource(R.string.player_sheets_track_title_wo_lang, track.id, track.title)
    hasLang -> stringResource(R.string.player_sheets_track_lang_wo_title, track.id, track.lang)
    track.isSubtitle -> stringResource(R.string.player_sheets_chapter_title_substitute_subtitle, track.id)
    track.isAudio -> stringResource(R.string.player_sheets_chapter_title_substitute_audio, track.id)
    else -> ""
  }
}
