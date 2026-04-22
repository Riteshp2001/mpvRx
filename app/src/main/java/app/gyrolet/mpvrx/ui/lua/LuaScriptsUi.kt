package app.gyrolet.mpvrx.ui.lua

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LuaScriptsCatalogState(
  val availableScripts: List<String> = emptyList(),
  val isLoading: Boolean = true,
)

@Composable
fun rememberLuaScriptsCatalog(
  storageUri: String,
  selectedScripts: Set<String>,
  onSelectionPruned: (Set<String>) -> Unit,
): LuaScriptsCatalogState {
  val context = LocalContext.current
  var state by remember(storageUri) { mutableStateOf(LuaScriptsCatalogState()) }

  LaunchedEffect(storageUri) {
    if (storageUri.isBlank()) {
      state = LuaScriptsCatalogState(availableScripts = emptyList(), isLoading = false)
      return@LaunchedEffect
    }

    state = state.copy(isLoading = true)

    val result =
      withContext(Dispatchers.IO) {
        runCatching {
          val scripts = mutableListOf<String>()
          val tree = DocumentFile.fromTreeUri(context, storageUri.toUri())
          if (tree != null && tree.exists()) {
            val scriptsDir =
              tree.listFiles().firstOrNull {
                it.isDirectory && it.name?.equals("scripts", ignoreCase = true) == true
              } ?: tree
            val scriptExtensions = setOf("lua", "js")

            scriptsDir.listFiles().forEach { file ->
              if (!file.isFile) return@forEach
              val name = file.name ?: return@forEach
              val extension = name.substringAfterLast('.', "").lowercase()
              if (extension in scriptExtensions) {
                scripts += name
              }
            }
          }
          scripts.sorted()
        }
      }

    result
      .onSuccess { scripts ->
        state = LuaScriptsCatalogState(availableScripts = scripts, isLoading = false)

        val validSelection = selectedScripts.filterTo(linkedSetOf()) { it in scripts }
        if (validSelection.size != selectedScripts.size) {
          onSelectionPruned(validSelection)
        }
      }.onFailure { error ->
        state = LuaScriptsCatalogState(availableScripts = emptyList(), isLoading = false)
        Toast.makeText(context, "Error loading scripts: ${error.message}", Toast.LENGTH_LONG).show()
      }
  }

  return state
}

@Composable
fun LuaRuntimeStatusCard(
  enabled: Boolean,
  hasStorageLocation: Boolean,
  enabledScriptsCount: Int,
  availableScriptsCount: Int,
  onEnabledChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val containerColor =
    when {
      !hasStorageLocation -> colors.surfaceContainerHighest.copy(alpha = 0.92f)
      enabled -> colors.primaryContainer.copy(alpha = 0.82f)
      else -> colors.surfaceContainerHigh.copy(alpha = 0.92f)
    }
  val contentColor =
    when {
      enabled && hasStorageLocation -> colors.onPrimaryContainer
      else -> colors.onSurface
    }
  val borderColor =
    when {
      enabled && hasStorageLocation -> colors.primary.copy(alpha = 0.35f)
      else -> colors.outlineVariant.copy(alpha = 0.35f)
    }
  val summary =
    when {
      !hasStorageLocation -> "Set an MPV config folder in Advanced settings to browse scripts."
      enabled && availableScriptsCount == 0 -> "Lua runtime is on, but no scripts were found in your scripts folder."
      enabled && enabledScriptsCount == 0 -> "Lua runtime is on. Tap a script below to arm it for playback."
      enabled -> "$enabledScriptsCount of $availableScriptsCount scripts enabled for playback."
      else -> "Lua runtime is off. Enabled scripts stay saved and can be reactivated anytime."
    }

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    color = containerColor,
    contentColor = contentColor,
    border = BorderStroke(1.dp, borderColor),
    tonalElevation = 0.dp,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Surface(
        shape = CircleShape,
        color =
          if (enabled && hasStorageLocation) {
            colors.primary.copy(alpha = 0.14f)
          } else {
            colors.surfaceVariant.copy(alpha = 0.9f)
          },
        tonalElevation = 0.dp,
      ) {
        Box(
          modifier = Modifier.size(42.dp),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.Code,
            contentDescription = null,
            tint =
              if (enabled && hasStorageLocation) {
                colors.primary
              } else {
                colors.onSurfaceVariant
              },
          )
        }
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = "Lua Scripts",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = summary,
          style = MaterialTheme.typography.bodySmall,
          color = contentColor.copy(alpha = 0.85f),
        )
      }

      Switch(
        checked = enabled,
        onCheckedChange = onEnabledChange,
        enabled = hasStorageLocation,
      )
    }
  }
}

@Composable
fun LuaScriptsLoadingState(
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(vertical = 24.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CircularProgressIndicator()
  }
}

@Composable
fun LuaScriptsEmptyState(
  title: String,
  summary: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    tonalElevation = 0.dp,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
      ) {
        Box(
          modifier = Modifier.size(56.dp),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.Code,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
      )
      Text(
        text = summary,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
fun LuaScriptToggleCard(
  scriptName: String,
  selected: Boolean,
  controlsEnabled: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
  trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
  val colors = MaterialTheme.colorScheme
  val active = selected && controlsEnabled
  val containerColor =
    when {
      active -> colors.primaryContainer.copy(alpha = 0.78f)
      selected -> colors.secondaryContainer.copy(alpha = 0.55f)
      else -> colors.surfaceContainerHigh.copy(alpha = 0.9f)
    }
  val borderColor =
    when {
      active -> colors.primary.copy(alpha = 0.45f)
      selected -> colors.secondary.copy(alpha = 0.35f)
      else -> colors.outlineVariant.copy(alpha = 0.28f)
    }
  val statusText =
    when {
      active -> "Enabled"
      selected -> "Saved, but Lua runtime is off"
      else -> "Disabled"
    }

  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .clickable(onClick = onToggle),
    shape = MaterialTheme.shapes.large,
    color = containerColor,
    border = BorderStroke(1.dp, borderColor),
    tonalElevation = 0.dp,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        shape = CircleShape,
        color =
          if (active) {
            colors.primary.copy(alpha = 0.14f)
          } else {
            colors.surfaceVariant.copy(alpha = 0.92f)
          },
        tonalElevation = 0.dp,
      ) {
        Box(
          modifier = Modifier.size(38.dp),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.Code,
            contentDescription = null,
            tint =
              if (active) {
                colors.primary
              } else {
                colors.onSurfaceVariant
              },
          )
        }
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = scriptName,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = statusText,
          style = MaterialTheme.typography.bodySmall,
          color = colors.onSurfaceVariant,
        )
      }

      if (trailingContent != null) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(2.dp),
          verticalAlignment = Alignment.CenterVertically,
          content = trailingContent,
        )
      }

      Switch(
        checked = selected,
        onCheckedChange = { onToggle() },
      )
    }
  }
}

@Composable
fun LuaSelectionFootnote(
  modifier: Modifier = Modifier,
) {
  Text(
    text = "Newly enabled scripts can load during playback. Scripts you turn off may need the video to be reopened before they fully stop running.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 4.dp),
  )
}

