/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.theme.spacing
import dev.vivvvek.seeker.Segment
import `is`.xyz.mpv.Utils
import org.koin.compose.koinInject

@Composable
fun CurrentChapter(
  chapter: Segment,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
) {
  Surface(
    modifier =
      modifier
        .height(45.dp)
        .widthIn(max = 220.dp)
        .clip(AppShapeScale.full)
        .clickable(onClick = onClick),
    shape = AppShapeScale.full,
    color =
      MaterialTheme.colorScheme.surfaceContainer.copy(
        alpha = 0.55f,
      ),
    contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = 0.dp,
    border =
      BorderStroke(
        1.dp,
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
      ),
  ) {
    AnimatedContent(
      targetState = chapter,
      modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small),
      transitionSpec = {
        if (targetState.start > initialState.start) {
          (slideInVertically { height -> height } + fadeIn())
            .togetherWith(slideOutVertically { height -> -height } + fadeOut())
        } else {
          (slideInVertically { height -> -height } + fadeIn())
            .togetherWith(slideOutVertically { height -> height } + fadeOut())
        }.using(
          SizeTransform(clip = false),
        )
      },
      label = "Chapter",
    ) { currentChapter ->
      val startTimeSec = currentChapter.start.toInt()
      val timeText = remember(startTimeSec) { Utils.prettyTime(startTimeSec) }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
      ) {
        Text(
          text = timeText,
          fontWeight = FontWeight.Bold,
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 1,
          overflow = TextOverflow.Clip,
          color = MaterialTheme.colorScheme.primary,
        )
        currentChapter.name.let {
          Text(
            text = Typography.bullet.toString(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface,
            overflow = TextOverflow.Clip,
          )
          Text(
            text = it,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.basicMarquee(),
          )
        }
      }
    }
  }
}
