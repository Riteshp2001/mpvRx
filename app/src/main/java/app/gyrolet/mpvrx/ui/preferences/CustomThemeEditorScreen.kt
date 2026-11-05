/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.AppComboBox
import app.gyrolet.mpvrx.ui.components.ExpressiveColorPicker
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusGroup
import app.gyrolet.mpvrx.ui.preferences.components.ThemePreviewCard
import app.gyrolet.mpvrx.ui.theme.AppTheme
import app.gyrolet.mpvrx.ui.theme.AppConnectedControlShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.CustomThemeDefinition
import app.gyrolet.mpvrx.ui.theme.ThemeColorRole
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.theme.resolveAppColorScheme
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import android.graphics.Color as AndroidColor
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField

@Serializable
data class CustomThemeEditorScreen(
  val themeName: String? = null,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backStack = LocalBackStack.current
    val context = LocalContext.current
    val preferences = koinInject<AppearancePreferences>()
    val serializedThemes by preferences.customTheme.collectAsState()
    val savedThemes = remember(serializedThemes) { CustomThemeDefinition.parseCollection(serializedThemes) }
    val existingTheme = savedThemes.firstOrNull { it.name == themeName }
    val initialTheme =
      remember(themeName) {
        existingTheme
          ?: savedThemes.firstOrNull { it.name == preferences.selectedCustomThemeName.get() }
          ?: preferences.appTheme.get().editorDefinition(context)
      }
    val customThemeLabel = stringResource(R.string.pref_appearance_custom_theme_title)
    val defaultThemeName =
      remember(savedThemes, customThemeLabel) {
        generateSequence(1) { it + 1 }
          .map { index -> "$customThemeLabel $index" }
          .first { candidate -> savedThemes.none { it.name.equals(candidate, ignoreCase = true) } }
      }

    var name by rememberSaveable(themeName) { mutableStateOf(existingTheme?.name ?: defaultThemeName) }
    // HashMap<String, String> is Serializable: unfinished hex edits survive recreation, too.
    var lightColors by rememberSaveable(themeName) { mutableStateOf(initialTheme.editorColors(dark = false)) }
    var darkColors by rememberSaveable(themeName) { mutableStateOf(initialTheme.editorColors(dark = true)) }
    var editorMode by rememberSaveable { mutableStateOf(CustomThemeEditorMode.Light) }
    var selectedKey by rememberSaveable { mutableStateOf("primary") }
    var showValidationErrors by rememberSaveable { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable(themeName) { mutableStateOf(selectedKey.startsWith("override_")) }
    val baseOptions = remember(context, savedThemes) {
      AppTheme.entries.map { theme ->
        ThemeBaseOption("built_in_${theme.name}", theme.editorDefinition(context), theme.titleRes)
      } + savedThemes.map { theme ->
        ThemeBaseOption("saved_${theme.name}", theme)
      }
    }
    var baseKey by rememberSaveable(themeName) {
      mutableStateOf(
        if (savedThemes.any { it.name == initialTheme.name }) "saved_${initialTheme.name}"
        else "built_in_${initialTheme.name}",
      )
    }
    val selectedBase = baseOptions.firstOrNull { it.key == baseKey }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val trimmedName = name.trim().replace('|', ' ')
    val duplicateName = savedThemes.any { it.name.equals(trimmedName, ignoreCase = true) && it.name != themeName }
    val invalidLightKey = lightColors.keys.firstOrNull { parseThemeColor(lightColors.getValue(it)) == null }
    val invalidDarkKey = darkColors.keys.firstOrNull { parseThemeColor(darkColors.getValue(it)) == null }
    val canSave = trimmedName.isNotBlank() && !duplicateName && invalidLightKey == null && invalidDarkKey == null
    val previewTheme = initialTheme.withEditorColors(trimmedName, lightColors, darkColors)
    val colors = if (editorMode.isDark) darkColors else lightColors
    val fields = editorFields()
    val paletteFields = fields.take(4)
    val advancedFields = fields.drop(4)
    val selectedField = fields.firstOrNull { it.key == selectedKey } ?: fields.first()
    val selectedRole = ThemeColorRole.entries.firstOrNull { "override_${it.key}" == selectedField.key }
    // Edit the underlying dark palette even while previewing the AMOLED surface treatment.
    val editScheme = if (editorMode.isDark) previewTheme.darkColorScheme() else previewTheme.lightColorScheme()
    val selectedHex = colors[selectedField.key] ?: selectedRole!!.color(editScheme).toEditorHex()

    fun updateColors(updated: HashMap<String, String>) {
      if (editorMode.isDark) darkColors = updated else lightColors = updated
    }

    fun applyBase(definition: CustomThemeDefinition) {
      lightColors = definition.editorColors(dark = false)
      darkColors = definition.editorColors(dark = true)
      showValidationErrors = false
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              stringResource(
                if (existingTheme == null) {
                  R.string.pref_appearance_custom_theme_new
                } else {
                  R.string.pref_appearance_custom_theme_edit_title
                },
              ),
            )
          },
          navigationIcon = {
            IconButton(onClick = { backStack.popSafely() }) {
              Icon(Icons.RoundedFilled.ArrowBack, contentDescription = null)
            }
          },
          actions = {
            TextButton(onClick = {
              showValidationErrors = true
              if (!canSave) {
                val invalidName = trimmedName.isBlank() || duplicateName
                if (!invalidName) {
                  when {
                    invalidLightKey != null -> {
                      editorMode = CustomThemeEditorMode.Light
                      selectedKey = invalidLightKey
                    }
                    invalidDarkKey != null -> {
                      editorMode = CustomThemeEditorMode.Dark
                      selectedKey = invalidDarkKey
                    }
                  }
                  advancedExpanded = selectedKey.startsWith("override_")
                }
                scope.launch { listState.animateScrollToItem(if (invalidName) 0 else 3) }
                return@TextButton
              }
              val definition = previewTheme.copy(name = trimmedName)
              val updatedThemes =
                if (existingTheme == null) {
                  savedThemes + definition
                } else {
                  savedThemes.map { if (it.name == existingTheme.name) definition else it }
                }
              preferences.customTheme.set(CustomThemeDefinition.serializeCollection(updatedThemes))
              preferences.selectedCustomThemeName.set(definition.name)
              backStack.popSafely()
            }) {
              Text(stringResource(R.string.pref_appearance_custom_theme_save))
            }
          },
        )
      },
    ) { padding ->
      Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.TopCenter,
      ) {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxHeight().widthIn(max = 640.dp).fillMaxWidth().tvFocusGroup(),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
          verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
          item(key = "theme") {
            Column(
              modifier = Modifier.fillMaxWidth(),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              val nameError =
                when {
                  showValidationErrors && trimmedName.isBlank() -> R.string.pref_appearance_custom_theme_name_required
                  duplicateName -> R.string.pref_appearance_custom_theme_name_duplicate
                  else -> null
                }
              OutlinedTextField(
                value = name,
                onValueChange = { name = it.replace("|", "").take(40) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.pref_appearance_custom_theme_name)) },
                supportingText = if (nameError != null) ({ Text(stringResource(nameError)) }) else null,
                isError = nameError != null,
                singleLine = true,
              )
              AppComboBox(
                options = baseOptions,
                selected = selectedBase,
                onSelect = { option ->
                  applyBase(option.definition)
                  baseKey = option.key
                },
                optionLabel = { option -> option.titleRes?.let { stringResource(it) } ?: option.definition.name },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.pref_appearance_custom_theme_base),
                leadingIcon = { ThemeBaseSwatches(previewTheme, editorMode) },
                optionLeadingIcon = { ThemeBaseSwatches(it.definition, editorMode) },
              )
            }
          }
          item(key = "preview") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              Text(
                stringResource(R.string.pref_appearance_custom_theme_preview),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
              )
              LazyRow(
                modifier = Modifier.fillMaxWidth().tvFocusGroup(),
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
              ) {
                items(CustomThemeEditorMode.entries.size) { index ->
                  val mode = CustomThemeEditorMode.entries[index]
                  ThemePreviewCard(
                    label = stringResource(mode.titleRes),
                    colorScheme =
                      if (mode.isDark) {
                        previewTheme.darkColorScheme(mode == CustomThemeEditorMode.Amoled)
                      } else {
                        previewTheme.lightColorScheme()
                      },
                    isSelected = editorMode == mode,
                    onClick = { editorMode = mode },
                  )
                }
              }
            }
          }
          item(key = "palette") {
            Column(
              modifier = Modifier.fillMaxWidth().selectableGroup(),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Text(
                stringResource(
                  if (editorMode.isDark) R.string.pref_appearance_custom_theme_dark_palette
                  else R.string.pref_appearance_custom_theme_light_palette,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
              )
              Column(verticalArrangement = Arrangement.spacedBy(AppConnectedControlShapeTokens.spacing)) {
                paletteFields.chunked(2).forEachIndexed { rowIndex, row ->
                  Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppConnectedControlShapeTokens.spacing),
                  ) {
                    row.forEachIndexed { columnIndex, field ->
                      ThemePaletteColor(
                        title = stringResource(field.titleRes),
                        hex = colors.getValue(field.key),
                        selected = field.key == selectedField.key,
                        onClick = {
                          selectedKey = field.key
                          advancedExpanded = false
                        },
                        index = rowIndex * 2 + columnIndex,
                        itemCount = paletteFields.size,
                        modifier = Modifier.weight(1f),
                      )
                    }
                  }
                }
              }
              TextButton(
                onClick = {
                  advancedExpanded = !advancedExpanded
                  selectedKey = if (advancedExpanded) advancedFields.first().key else paletteFields.first().key
                },
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                  stringResource(R.string.pref_appearance_custom_theme_advanced),
                  modifier = Modifier.weight(1f),
                )
                Icon(
                  if (advancedExpanded) Icons.RoundedFilled.ExpandLess else Icons.RoundedFilled.ExpandMore,
                  contentDescription = null,
                )
              }
              if (advancedExpanded) {
                AppComboBox(
                  options = advancedFields,
                  selected = selectedField,
                  onSelect = { selectedKey = it.key },
                  optionLabel = { stringResource(it.titleRes) },
                  modifier = Modifier.fillMaxWidth(),
                  leadingIcon = {
                    selectedRole?.let { ThemeRoleSwatch(it, editScheme, parseThemeColor(selectedHex)) }
                  },
                  optionLeadingIcon = { field ->
                    val role = ThemeColorRole.entries.first { "override_${it.key}" == field.key }
                    ThemeRoleSwatch(
                      role, editScheme, colors[field.key]?.let(::parseThemeColor) ?: role.color(editScheme),
                    )
                  },
                )
                if (colors.keys.any { it.startsWith("override_") }) {
                  TextButton(
                    onClick = { updateColors(HashMap(colors.filterKeys { !it.startsWith("override_") })) },
                    modifier = Modifier.align(Alignment.End),
                  ) {
                    Text(stringResource(R.string.pref_appearance_custom_theme_reset_all))
                  }
                }
              }
            }
          }
          item(key = "color") {
            ThemeColorEditor(
              title = stringResource(selectedField.titleRes),
              hex = selectedHex,
              onHexChange = { value ->
                updateColors(
                  HashMap(colors).apply {
                    put(selectedField.key, value)
                    if (!selectedField.key.startsWith("override_")) remove("override_${selectedField.key}")
                  },
                )
              },
              showValidationError = showValidationErrors,
            )
            if (selectedRole != null && colors.containsKey(selectedField.key)) {
              TextButton(
                onClick = { updateColors(HashMap(colors).apply { remove(selectedField.key) }) },
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(stringResource(R.string.pref_appearance_custom_theme_reset_color))
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ThemePaletteColor(
  title: String,
  hex: String,
  selected: Boolean,
  onClick: () -> Unit,
  index: Int,
  itemCount: Int,
  modifier: Modifier = Modifier,
) {
  val shape = rememberConnectedShape(
    index, itemCount, ConnectedLayout.Grid, columns = 2, tokens = AppConnectedControlShapeTokens,
  )
  Surface(
    modifier = modifier.clip(shape).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    shape = shape,
    color =
      if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor =
      if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
    border =
      BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      modifier = Modifier.heightIn(min = 56.dp).padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      ThemeColorSwatch(parseThemeColor(hex), Modifier.size(28.dp))
      Text(
        title,
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.labelLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun ThemeColorSwatch(color: Color?, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier.clip(CircleShape)
      .background(color ?: MaterialTheme.colorScheme.errorContainer)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
  )
}

@Composable
private fun ThemeBaseSwatches(theme: CustomThemeDefinition, mode: CustomThemeEditorMode) {
  val scheme =
    if (mode.isDark) theme.darkColorScheme(mode == CustomThemeEditorMode.Amoled) else theme.lightColorScheme()
  Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    listOf(scheme.primary, scheme.secondary, scheme.background).forEach { color ->
      ThemeColorSwatch(color, Modifier.size(12.dp))
    }
  }
}

@Composable
private fun ThemeRoleSwatch(role: ThemeColorRole, scheme: ColorScheme, color: Color?) {
  val background = when (role) {
    ThemeColorRole.OnPrimary -> scheme.primary
    ThemeColorRole.OnSecondary -> scheme.secondary
    ThemeColorRole.OnTertiary -> scheme.tertiary
    ThemeColorRole.OnPrimaryContainer -> scheme.primaryContainer
    ThemeColorRole.OnSecondaryContainer -> scheme.secondaryContainer
    ThemeColorRole.OnTertiaryContainer -> scheme.tertiaryContainer
    ThemeColorRole.OnSurface -> scheme.surface
    ThemeColorRole.OnSurfaceVariant -> scheme.surfaceVariant
    ThemeColorRole.OnBackground -> scheme.background
    ThemeColorRole.InverseOnSurface -> scheme.inverseSurface
    ThemeColorRole.OnError -> scheme.error
    ThemeColorRole.OnErrorContainer -> scheme.errorContainer
    ThemeColorRole.OnPrimaryFixed, ThemeColorRole.OnPrimaryFixedVariant -> scheme.primaryFixed
    ThemeColorRole.OnSecondaryFixed, ThemeColorRole.OnSecondaryFixedVariant -> scheme.secondaryFixed
    ThemeColorRole.OnTertiaryFixed, ThemeColorRole.OnTertiaryFixedVariant -> scheme.tertiaryFixed
    else -> null
  }
  if (background == null) {
    ThemeColorSwatch(color, Modifier.size(24.dp))
  } else {
    val shape = RoundedCornerShape(6.dp)
    Box(
      Modifier.size(36.dp).clip(shape).background(background)
        .clearAndSetSemantics {}
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        "Aa",
        color = color ?: MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

private enum class CustomThemeEditorMode(
  @StringRes val titleRes: Int,
) {
  Light(R.string.pref_appearance_darkmode_light),
  Dark(R.string.pref_appearance_darkmode_dark),
  Amoled(R.string.pref_appearance_custom_theme_amoled_preview),
  ;

  val isDark: Boolean get() = this != Light
}

private data class ThemeBaseOption(
  val key: String,
  val definition: CustomThemeDefinition,
  @StringRes val titleRes: Int? = null,
)

private data class ThemeEditorField(
  val key: String,
  @StringRes val titleRes: Int,
)

private fun editorFields(): List<ThemeEditorField> =
  listOf(
    ThemeEditorField("primary", R.string.theme_color_primary),
    ThemeEditorField("secondary", R.string.theme_color_secondary),
    ThemeEditorField("tertiary", R.string.theme_color_tertiary),
    ThemeEditorField("background", R.string.pref_appearance_custom_theme_background),
  ) +
    ThemeColorRole.entries
      .filterNot { it in listOf(ThemeColorRole.Primary, ThemeColorRole.Secondary, ThemeColorRole.Tertiary) }
      .map { ThemeEditorField("override_${it.key}", it.titleRes) }

private fun CustomThemeDefinition.editorColors(dark: Boolean): HashMap<String, String> =
  hashMapOf(
    "primary" to (if (dark) primaryDark else primaryLight).toEditorHex(),
    "secondary" to (if (dark) secondaryDark else secondaryLight).toEditorHex(),
    "tertiary" to (if (dark) tertiaryDark else tertiaryLight).toEditorHex(),
    "background" to (if (dark) backgroundDark else backgroundLight).toEditorHex(),
  ).apply {
    (if (dark) darkOverrides else lightOverrides).forEach { (role, color) ->
      put("override_${role.key}", color.toEditorHex())
    }
  }

private fun CustomThemeDefinition.withEditorColors(
  name: String,
  light: Map<String, String>,
  dark: Map<String, String>,
): CustomThemeDefinition {
  fun Map<String, String>.color(
    key: String,
    fallback: Color,
  ): Color = get(key)?.let(::parseThemeColor) ?: fallback

  fun Map<String, String>.overrides(): Map<ThemeColorRole, Color> =
    ThemeColorRole.entries
      .mapNotNull { role ->
        get("override_${role.key}")?.let(::parseThemeColor)?.let { role to it }
      }.toMap()

  return copy(
    name = name,
    primaryLight = light.color("primary", primaryLight),
    primaryDark = dark.color("primary", primaryDark),
    secondaryLight = light.color("secondary", secondaryLight),
    secondaryDark = dark.color("secondary", secondaryDark),
    tertiaryLight = light.color("tertiary", tertiaryLight),
    tertiaryDark = dark.color("tertiary", tertiaryDark),
    backgroundLight = light.color("background", backgroundLight),
    backgroundDark = dark.color("background", backgroundDark),
    lightOverrides = light.overrides(),
    darkOverrides = dark.overrides(),
  )
}

private fun AppTheme.editorDefinition(context: android.content.Context): CustomThemeDefinition {
  if (!isDynamic) return toCustomTheme(name)
  return CustomThemeDefinition.fromColorSchemes(
    name = name,
    light = resolveAppColorScheme(context, this, null, useDarkTheme = false, amoledMode = false),
    dark = resolveAppColorScheme(context, this, null, useDarkTheme = true, amoledMode = false),
  )
}

@Composable
private fun ThemeColorEditor(
  title: String,
  hex: String,
  onHexChange: (String) -> Unit,
  showValidationError: Boolean,
) {
  val color = parseThemeColor(hex)
  val argb = color?.toArgb() ?: 0xFF000000.toInt()
  val invalid = showValidationError && color == null

  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      ThemeColorSwatch(color, Modifier.size(42.dp))
      OutlinedTextField(
        value = hex,
        onValueChange = { value -> onHexChange(normalizeHexInput(value)) },
        modifier = Modifier.weight(1f),
        label = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingText =
          if (invalid) ({ Text(stringResource(R.string.pref_appearance_custom_theme_hex_invalid)) }) else null,
        isError = invalid,
        singleLine = true,
      )
    }
    ExpressiveColorPicker(
      color = argb,
      onColorChange = { onHexChange(it.toOpaqueHex()) },
      showAlpha = false,
    )
  }
}

private fun parseThemeColor(value: String): Color? {
  val normalized = value.trim()
  if (!normalized.matches(Regex("^#[0-9A-Fa-f]{6}$"))) return null
  return runCatching { Color(AndroidColor.parseColor(normalized)) }.getOrNull()
}

private fun normalizeHexInput(value: String): String {
  val digits =
    value
      .filter { character ->
        character.isDigit() || character.uppercaseChar() in 'A'..'F'
      }.take(6)
      .uppercase()
  return if (digits.isEmpty()) "" else "#$digits"
}

private fun Color.toEditorHex(): String = toArgb().toOpaqueHex()

private fun Int.toOpaqueHex(): String = "#%06X".format(this and 0x00FFFFFF)
