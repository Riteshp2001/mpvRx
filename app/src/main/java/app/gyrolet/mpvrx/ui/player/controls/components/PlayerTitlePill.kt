// SPDX-License-Identifier: AGPL-3.0-or-later

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.LocalPlayerButtonsClickEvent
import app.gyrolet.mpvrx.ui.player.controls.playerTitleHeight
import app.gyrolet.mpvrx.ui.player.controls.playerTitleMarquee

@Composable
internal fun PlayerTitlePill(
  title: String?,
  playlistInfo: String?,
  onOpenInfo: () -> Unit,
  onOpenPlaylist: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val height = playerTitleHeight(48.dp, MaterialTheme.typography.titleMedium.lineHeight)
  val foreground = playerButtonContentColor()
  val clickEvent = LocalPlayerButtonsClickEvent.current
  PlayerControlSurface(CircleShape, modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = {
        clickEvent()
        onOpenInfo()
      }) {
        Icon(
          imageVector = Icons.RoundedFilled.Info,
          contentDescription = stringResource(R.string.ui_media_info),
          tint = foreground,
          modifier = Modifier.size(20.dp),
        )
      }
      if (!title.isNullOrBlank()) {
        Row(
          modifier =
            Modifier
              .weight(1f, fill = false)
              .heightIn(min = height)
              .then(
                if (onOpenPlaylist != null) {
                  Modifier.clip(CircleShape).clickable(role = Role.Button) {
                    clickEvent()
                    onOpenPlaylist()
                  }
                } else {
                  Modifier
                },
              ).padding(end = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
            color = foreground,
            modifier = Modifier.weight(1f, fill = false).playerTitleMarquee(),
          )
          if (playlistInfo != null) {
            Text(
              text = " • $playlistInfo",
              maxLines = 1,
              style = MaterialTheme.typography.bodyMedium,
              color = foreground.copy(alpha = 0.75f),
            )
          }
        }
      }
    }
  }
}
