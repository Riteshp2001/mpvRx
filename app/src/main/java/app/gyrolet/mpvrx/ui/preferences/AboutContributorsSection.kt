/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.repository.GitHubContributor
import app.gyrolet.mpvrx.repository.GitHubContributorsRepository
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import org.koin.compose.koinInject

@Composable
internal fun AboutContributorsSection(
  githubRepoUrl: String,
  modifier: Modifier = Modifier,
) {
  val repository = koinInject<GitHubContributorsRepository>()
  val uriHandler = LocalUriHandler.current
  var refreshRequest by remember { mutableIntStateOf(0) }
  var state by remember { mutableStateOf<ContributorsUiState>(ContributorsUiState.Loading) }

  LaunchedEffect(refreshRequest) {
    state = ContributorsUiState.Loading
    repository
      .contributors(forceRefresh = refreshRequest > 0)
      .onSuccess { contributors -> state = ContributorsUiState.Loaded(contributors) }
      .onFailure { state = ContributorsUiState.Error }
  }

  Column(modifier = modifier) {
    PreferenceSectionHeader(title = stringResource(R.string.pref_section_contributors))
    PreferenceCard {
      when (val currentState = state) {
        ContributorsUiState.Loading -> ContributorsLoading()
        ContributorsUiState.Error ->
          ContributorsError(
            onRetry = {
              state = ContributorsUiState.Loading
              refreshRequest++
            },
            onViewAll = {
              runCatching { uriHandler.openUri("${githubRepoUrl.trimEnd('/')}/graphs/contributors") }
            },
          )
        is ContributorsUiState.Loaded -> {
          if (currentState.contributors.isEmpty()) {
            Text(
              text = stringResource(R.string.contributors_empty),
              modifier = Modifier.fillMaxWidth().padding(24.dp),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            currentState.contributors.take(MAX_VISIBLE_CONTRIBUTORS).forEachIndexed { index, contributor ->
              ContributorRow(
                contributor = contributor,
                onClick =
                  contributor.profileUrl?.let { profileUrl ->
                    { runCatching { uriHandler.openUri(profileUrl) } }
                  },
              )
              if (index < minOf(currentState.contributors.size, MAX_VISIBLE_CONTRIBUTORS) - 1) {
                PreferenceDivider()
              }
            }
          }

          PreferenceDivider()
          TextButton(
            onClick = {
              runCatching { uriHandler.openUri("${githubRepoUrl.trimEnd('/')}/graphs/contributors") }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
          ) {
            Text(stringResource(R.string.contributors_view_all))
          }
        }
      }
    }
  }
}

@Composable
private fun ContributorRow(
  contributor: GitHubContributor,
  onClick: (() -> Unit)?,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier
          .size(42.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.RoundedFilled.Person,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(22.dp),
      )
      contributor.avatarUrl?.let { avatarUrl ->
        RemoteImage(
          url = avatarUrl,
          contentDescription = contributor.displayName,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      }
    }

    Spacer(Modifier.width(12.dp))
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = contributor.displayName,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text =
          pluralStringResource(
            R.plurals.contributors_contribution_count,
            contributor.contributions,
            contributor.contributions,
          ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ContributorsLoading() {
  Row(
    modifier = Modifier.fillMaxWidth().padding(24.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
    Spacer(Modifier.width(12.dp))
    Text(
      text = stringResource(R.string.contributors_loading),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun ContributorsError(
  onRetry: () -> Unit,
  onViewAll: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = stringResource(R.string.contributors_load_error),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row {
      TextButton(onClick = onRetry) {
        Text(stringResource(R.string.contributors_retry))
      }
      TextButton(onClick = onViewAll) {
        Text(stringResource(R.string.contributors_view_all))
      }
    }
  }
}

private sealed interface ContributorsUiState {
  data object Loading : ContributorsUiState

  data object Error : ContributorsUiState

  data class Loaded(
    val contributors: List<GitHubContributor>,
  ) : ContributorsUiState
}

private const val MAX_VISIBLE_CONTRIBUTORS = 8