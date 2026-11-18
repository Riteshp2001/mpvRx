/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.preferences

import app.gyrolet.mpvrx.ui.tv.tvContentFocus

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable

@Serializable
object LibrariesScreen : ScrollableSettingsScreen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    var query by rememberSaveable { mutableStateOf("") }
    val search = query.trim()
    val libraries = OPEN_SOURCE_LIBRARIES.filter { library ->
      library.name.contains(search, ignoreCase = true) ||
        library.artifact.contains(search, ignoreCase = true) ||
        library.license.contains(search, ignoreCase = true) ||
        context.getString(library.descriptionRes).contains(search, ignoreCase = true)
    }

    Scaffold(
      topBar = {
        SettingsTopAppBar(
          title = stringResource(R.string.pref_about_oss_libraries),
          onBack = { backStack.popSafely() },
        )
      },
    ) { padding ->
      LazyColumn(
        modifier = Modifier.fillMaxSize()
            .tvContentFocus().padding(padding),
        contentPadding = settingsContentPadding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
        verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
      ) {
        item(key = "introduction") {
          Surface(
            shape = RoundedCornerShape(AppConnectedShapeTokens.outerCorner),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(24.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Icon(Icons.RoundedFilled.Code, contentDescription = null, modifier = Modifier.size(32.dp))
              Text(
                text = stringResource(R.string.libraries_built_together),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
              )
              Text(
                text = stringResource(R.string.ui_core_open_source_dependencies_used_by_mpvrx),
                style = MaterialTheme.typography.bodyLarge,
              )
              Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
              ) {
                Text(
                  text = stringResource(R.string.libraries_count, OPEN_SOURCE_LIBRARIES.size),
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                  style = MaterialTheme.typography.labelLarge,
                )
              }
            }
          }
        }
        item(key = "search") {
          OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            label = { Text(stringResource(R.string.libraries_search_hint)) },
            leadingIcon = { Icon(Icons.RoundedFilled.Search, contentDescription = null) },
            trailingIcon = {
              if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                  Icon(Icons.RoundedFilled.Close, contentDescription = stringResource(R.string.search_clear_query))
                }
              }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
          )
        }
        if (libraries.isEmpty()) {
          item(key = "empty") {
            EmptyState(
              icon = Icons.RoundedFilled.Search,
              title = stringResource(R.string.ui_no_results_found),
              message = stringResource(R.string.libraries_search_hint),
              modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            )
          }
        }
        // Group by license without losing the alphabetical order within each group.
        libraries.groupBy { it.license }.toSortedMap().forEach { (license, group) ->
          item(key = "license_$license") {
            Row(
              modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 8.dp)
                .semantics { heading() },
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Text(
                text = license,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
              )
              Text(
                text = group.size.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          itemsIndexed(group, key = { _, library -> library.artifact }) { index, library ->
            Surface(
              onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, library.url.toUri())) }
              },
              modifier = Modifier.fillMaxWidth(),
              shape = rememberConnectedShape(index, group.size, ConnectedLayout.Vertical),
              color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
              Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  Text(
                    text = library.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                  )
                  Icon(
                    Icons.RoundedFilled.Link,
                    contentDescription = stringResource(R.string.libraries_open_project),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                  )
                }
                Text(
                  text = stringResource(library.descriptionRes),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  text = library.artifact,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = MaterialTheme.colorScheme.secondaryContainer,
                  contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                  Text(
                    text = library.license,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}
