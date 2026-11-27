/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.fonts.GoogleFontFamily
import app.gyrolet.mpvrx.domain.fonts.GoogleFontsRepository
import app.gyrolet.mpvrx.domain.fonts.InstalledGoogleFont
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.AppPickerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetSearchField
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton

@Composable
internal fun GoogleFontsSheet(
  preferences: AppearancePreferences,
  onDismiss: () -> Unit,
) {
  val repository = koinInject<GoogleFontsRepository>()
  val selectedFamily by preferences.googleFontFamily.collectAsState()
  val useSystemFont by preferences.useSystemFont.collectAsState()
  val scope = rememberCoroutineScope()
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var catalog by remember { mutableStateOf<List<GoogleFontFamily>>(emptyList()) }
  var isLoading by remember { mutableStateOf(true) }
  var catalogFailed by remember { mutableStateOf(false) }
  var downloadingFamily by remember { mutableStateOf<String?>(null) }
  var downloadFailedFamily by remember { mutableStateOf<String?>(null) }
  var installedFonts by remember { mutableStateOf<List<InstalledGoogleFont>>(emptyList()) }
  var refreshKey by remember { mutableIntStateOf(0) }
  var pendingDeletion by remember { mutableStateOf<InstalledGoogleFont?>(null) }

  pendingDeletion?.let { font ->
    AlertDialog(
      onDismissRequest = { pendingDeletion = null },
      title = { Text(stringResource(R.string.app_font_delete_title)) },
      text = { Text(stringResource(R.string.app_font_delete_message, font.family)) },
      confirmButton = {
        TextButton(
          onClick = {
            pendingDeletion = null
            scope.launch {
              withContext(Dispatchers.IO) { repository.uninstall(font.appFamily) }
              if (preferences.googleFontFamily.get() == font.appFamily) {
                preferences.googleFontFamily.set("")
              }
              preferences.googleFontRevision.set(preferences.googleFontRevision.get() + 1)
              installedFonts =
                withContext(Dispatchers.IO) {
                  repository.installedFonts(preferences.googleFontFamily.get())
                }
            }
          },
        ) {
          Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingDeletion = null }) {
          Text(stringResource(R.string.generic_cancel))
        }
      },
    )
  }

  LaunchedEffect(selectedFamily) {
    installedFonts =
      withContext(Dispatchers.IO) {
        repository.installedFonts(selectedFamily)
      }
  }

  LaunchedEffect(refreshKey) {
    isLoading = true
    catalogFailed = false
    repository.loadCatalog(forceRefresh = refreshKey > 0).fold(
      onSuccess = { catalog = it },
      onFailure = { catalogFailed = true },
    )
    isLoading = false
  }

  val installedSet = remember(installedFonts) { installedFonts.mapTo(mutableSetOf(), InstalledGoogleFont::appFamily) }
  val catalogByFamily = remember(catalog) { catalog.associateBy(GoogleFontFamily::family) }
  val filteredFonts =
    remember(catalog, searchQuery, installedSet) {
      catalog.filter { font ->
        font.family != BUILT_IN_FONT_FAMILY &&
          font.family !in installedSet &&
          (
            searchQuery.isBlank() ||
              font.family.contains(searchQuery, ignoreCase = true) ||
              font.category.contains(searchQuery, ignoreCase = true)
          )
      }
    }

  AppPickerSheet(
    onDismissRequest = onDismiss,
    title = stringResource(R.string.app_font_google_fonts_title),
    subtitle = stringResource(R.string.app_font_attribution),
    scrollContent = false,
    actions = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.generic_cancel))
      }
    },
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
      FontSectionLabel(stringResource(R.string.app_font_section_app_fonts))
      AppFontRow(
        title = stringResource(R.string.app_font_google_sans_flex),
        subtitle = stringResource(R.string.app_font_built_in),
        selected = !useSystemFont && selectedFamily.isBlank(),
        onClick = {
          preferences.googleFontFamily.set("")
          preferences.useSystemFont.set(false)
          onDismiss()
        },
      )
      Spacer(Modifier.height(4.dp))
      AppFontRow(
        title = stringResource(R.string.pref_appearance_system_font_title),
        subtitle = stringResource(R.string.pref_appearance_system_font_summary),
        selected = useSystemFont,
        onClick = {
          preferences.useSystemFont.set(true)
          onDismiss()
        },
      )
      HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        item(key = "downloaded-label") {
          FontSectionLabel(stringResource(R.string.app_font_section_downloaded))
        }
        if (installedFonts.isEmpty()) {
          item(key = "no-downloads") {
            Text(
              text = stringResource(R.string.app_font_no_downloaded),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.outline,
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            )
          }
        } else {
          items(installedFonts, key = { font -> "installed:${font.appFamily}" }) { font ->
            AppFontRow(
              title = font.family,
              subtitle = catalogByFamily[font.appFamily]?.category ?: stringResource(R.string.app_font_downloaded),
              selected = !useSystemFont && selectedFamily == font.appFamily,
              enabled = downloadingFamily == null,
              onClick = {
                preferences.googleFontFamily.set(font.appFamily)
                preferences.useSystemFont.set(false)
                onDismiss()
              },
              onDelete = { pendingDeletion = font },
            )
          }
        }
        item(key = "browse-divider") {
          HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
        }
        item(key = "browse-label") {
          FontSectionLabel(stringResource(R.string.app_font_section_browse))
        }
        item(key = "search") {
          PlayerSheetSearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = stringResource(R.string.generic_search),
            modifier = Modifier.padding(bottom = 10.dp),
          )
        }
        when {
          isLoading -> {
            item(key = "loading") {
              Box(
                modifier = Modifier.fillMaxWidth().padding(28.dp),
                contentAlignment = Alignment.Center,
              ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
              }
            }
          }
          catalogFailed -> {
            item(key = "catalog-error") {
              Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                Text(
                  text = stringResource(R.string.app_font_catalog_error),
                  color = MaterialTheme.colorScheme.error,
                  style = MaterialTheme.typography.bodyMedium,
                  modifier = Modifier.padding(horizontal = 12.dp),
                )
                TextButton(onClick = { refreshKey++ }) {
                  Text(stringResource(R.string.ui_retry))
                }
              }
            }
          }
          filteredFonts.isEmpty() -> {
            item(key = "empty") {
              Text(
                text = stringResource(R.string.ui_no_results_found),
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 28.dp),
              )
            }
          }
          else -> {
            items(filteredFonts, key = GoogleFontFamily::family) { font ->
              AppFontRow(
                title = font.family,
                subtitle = font.category,
                selected = !useSystemFont && selectedFamily == font.family,
                downloading = downloadingFamily == font.family,
                enabled = downloadingFamily == null,
                onClick = {
                  downloadingFamily = font.family
                  downloadFailedFamily = null
                  scope.launch {
                    repository.install(font.family).fold(
                      onSuccess = {
                        preferences.googleFontFamily.set(font.family)
                        preferences.googleFontRevision.set(preferences.googleFontRevision.get() + 1)
                        preferences.useSystemFont.set(false)
                        installedFonts =
                          withContext(Dispatchers.IO) {
                            repository.installedFonts(font.family)
                          }
                        onDismiss()
                      },
                      onFailure = { downloadFailedFamily = font.family },
                    )
                    downloadingFamily = null
                  }
                },
              )
            }
          }
        }
      }

      downloadFailedFamily?.let { family ->
        Text(
          text = stringResource(R.string.app_font_download_error, family),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
        )
      }
    }
  }
}

@Composable
private fun FontSectionLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
  )
}

@Composable
private fun AppFontRow(
  title: String,
  subtitle: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  downloading: Boolean = false,
  enabled: Boolean = true,
  onDelete: (() -> Unit)? = null,
) {
  Surface(
    onClick = onClick,
    enabled = enabled,
    shape = RoundedCornerShape(8.dp),
    color =
      if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
      } else {
        MaterialTheme.colorScheme.surfaceContainerLow
      },
    modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (downloading) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
      } else {
        RadioButton(selected = selected, onClick = null)
      }
      Spacer(Modifier.width(4.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
          Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      if (onDelete != null) {
        IconButton(onClick = onDelete, enabled = enabled) {
          Icon(
            imageVector = Icons.RoundedFilled.Delete,
            contentDescription = stringResource(R.string.app_font_delete_title),
            tint = MaterialTheme.colorScheme.error,
          )
        }
      }
    }
  }
}

private const val BUILT_IN_FONT_FAMILY = "Google Sans Flex"
