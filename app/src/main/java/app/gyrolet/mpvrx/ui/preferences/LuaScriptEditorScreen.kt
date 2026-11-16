/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton
import app.gyrolet.mpvrx.presentation.components.AppDropdownMenu
import app.gyrolet.mpvrx.presentation.components.AppDropdownMenuItem
import app.gyrolet.mpvrx.presentation.components.ConfirmDialog
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import app.gyrolet.mpvrx.ui.editor.MpvHelpScreen
import app.gyrolet.mpvrx.ui.editor.MpvScriptEditor
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppConnectedControlShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.animatedConnectedContainerColor
import app.gyrolet.mpvrx.ui.theme.appPressFeedback
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.tv.TvEdgeToEdgeScreen
import app.gyrolet.mpvrx.ui.tv.tvOverscanPadding
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalRootBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.io.File

@Serializable
data class LuaScriptEditorScreen(
  val scriptName: String?,
) : Screen, TvEdgeToEdgeScreen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val view = LocalView.current
    val backStack = LocalBackStack.current
    val rootBackStack = LocalRootBackStack.current ?: backStack
    val preferences = koinInject<AdvancedPreferences>()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val haptics = rememberAppHaptics()
    val reduceMotion = AppMotion.shouldReduceMotion()

    val mpvConfStorageLocation by preferences.mpvConfStorageUri.collectAsState()

    val isNewScript = scriptName == null
    val initialBaseName = remember(scriptName) { scriptName?.substringBeforeLast('.') ?: "" }
    val initialExtension =
      remember(scriptName) {
        scriptName
          ?.substringAfterLast('.', "lua")
          ?.lowercase()
          ?.takeIf { it == "lua" || it == "js" }
          ?: "lua"
      }

    var loadedContent by rememberSaveable(scriptName) { mutableStateOf("") }
    var scriptContent by rememberSaveable(scriptName) { mutableStateOf("") }
    var fileName by rememberSaveable(scriptName) { mutableStateOf(initialBaseName) }
    var scriptExtension by rememberSaveable(scriptName) { mutableStateOf(initialExtension) }
    var userEditedWhileLoading by rememberSaveable(scriptName) { mutableStateOf(false) }

    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var isFileNameFocused by remember { mutableStateOf(false) }
    val fileNameFocusRequester = remember { FocusRequester() }

    val normalizedBaseName =
      remember(fileName) {
        val trimmed = fileName.trim()
        when {
          trimmed.endsWith(".lua", ignoreCase = true) -> trimmed.dropLast(4).trim()
          trimmed.endsWith(".js", ignoreCase = true) -> trimmed.dropLast(3).trim()
          else -> trimmed
        }
      }
    val effectiveFileName =
      remember(normalizedBaseName, scriptExtension) {
        "${normalizedBaseName.ifBlank { "untitled" }}.$scriptExtension"
      }
    val hasUnsavedChanges =
      fileName != initialBaseName ||
        scriptExtension != initialExtension ||
        scriptContent != loadedContent
    val canSave = hasUnsavedChanges

    fun dismissKeyboard() {
      focusManager.clearFocus(force = true)
      keyboardController?.hide()
    }

    fun requestClose() {
      if (hasUnsavedChanges) {
        showDiscardDialog = true
      } else {
        dismissKeyboard()
        backStack.popSafely()
      }
    }

    BackHandler {
      if (isFullscreen) {
        isFullscreen = false
      } else {
        requestClose()
      }
    }

    DisposableEffect(activity, view, isFullscreen) {
      val window = activity?.window
      val controller = if (window != null) WindowCompat.getInsetsController(window, view) else null
      if (isFullscreen && controller != null) {
        controller.systemBarsBehavior =
          WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
      } else {
        controller?.show(WindowInsetsCompat.Type.systemBars())
      }
      onDispose {
        controller?.show(WindowInsetsCompat.Type.systemBars())
      }
    }

    // Load script content if editing existing script
    LaunchedEffect(scriptName, mpvConfStorageLocation) {
      if (scriptName != null && mpvConfStorageLocation.isNotBlank()) {
        withContext(Dispatchers.IO) {
          runCatching {
            val tree = DocumentFile.fromTreeUri(context, mpvConfStorageLocation.toUri())
            if (tree != null && tree.exists()) {
              val scriptsDir =
                tree.listFiles().firstOrNull {
                  it.isDirectory && it.name?.equals("scripts", ignoreCase = true) == true
                } ?: tree

              val scriptFile = scriptsDir.findFile(scriptName)
              if (scriptFile != null && scriptFile.exists()) {
                val content =
                  context.contentResolver.openInputStream(scriptFile.uri)?.bufferedReader()?.use { reader ->
                    reader.readText()
                  }
                if (content != null) {
                  withContext(Dispatchers.Main) {
                    loadedContent = content
                    if (!userEditedWhileLoading) {
                      scriptContent = content
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    fun saveScript() {
      if (normalizedBaseName.isBlank()) {
        if (isFullscreen) isFullscreen = false
        Toast
          .makeText(
            context,
            context.getString(R.string.ui_please_enter_a_file_name),
            Toast.LENGTH_SHORT,
          ).show()
        runCatching { fileNameFocusRequester.requestFocus() }
        return
      }

      dismissKeyboard()
      val finalFileName = "$normalizedBaseName.$scriptExtension"
      val contentToSave = scriptContent

      scope.launch(Dispatchers.IO) {
        try {
          if (mpvConfStorageLocation.isBlank()) {
            withContext(Dispatchers.Main) {
              Toast
                .makeText(
                  context,
                  context.getString(R.string.ui_no_storage_location_set),
                  Toast.LENGTH_LONG,
                ).show()
            }
            return@launch
          }

          val tree = DocumentFile.fromTreeUri(context, mpvConfStorageLocation.toUri())
          if (tree == null) {
            withContext(Dispatchers.Main) {
              Toast
                .makeText(
                  context,
                  context.getString(R.string.ui_no_storage_location_set),
                  Toast.LENGTH_LONG,
                ).show()
            }
            return@launch
          }

          val scriptsDir =
            tree.listFiles().firstOrNull {
              it.isDirectory && it.name?.equals("scripts", ignoreCase = true) == true
            } ?: tree

          val existingScriptName = scriptName.orEmpty()
          if (!isNewScript && existingScriptName != finalFileName) {
            scriptsDir.findFile(existingScriptName)?.delete()
          }

          val existing = scriptsDir.findFile(finalFileName)
          val scriptFile =
            existing ?: scriptsDir.createFile("text/plain", finalFileName)?.also { it.renameTo(finalFileName) }
          val uri =
            scriptFile?.uri ?: run {
              withContext(Dispatchers.Main) {
                Toast
                  .makeText(
                    context,
                    context.getString(R.string.ui_failed_to_create_file),
                    Toast.LENGTH_LONG,
                  ).show()
              }
              return@launch
            }

          context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(contentToSave.toByteArray(Charsets.UTF_8))
            out.flush()
          } ?: run {
            withContext(Dispatchers.Main) {
              Toast
                .makeText(
                  context,
                  context.getString(R.string.ui_failed_to_open_output_stream),
                  Toast.LENGTH_LONG,
                ).show()
            }
            return@launch
          }

          val selectedScripts = preferences.selectedLuaScripts.get()
          val updatedSelected =
            if (!isNewScript && existingScriptName != finalFileName && existingScriptName in selectedScripts) {
              (selectedScripts - existingScriptName + finalFileName).also {
                preferences.selectedLuaScripts.set(it)
                File(File(context.filesDir, "scripts"), existingScriptName).delete()
              }
            } else {
              selectedScripts
            }

          if (preferences.enableLuaScripts.get() && finalFileName in updatedSelected) {
            val internalScriptsDir = File(context.filesDir, "scripts").apply { mkdirs() }
            File(internalScriptsDir, finalFileName).writeText(contentToSave)
            PlaybackSession.invalidateCoreConfiguration()
          }

          withContext(Dispatchers.Main) {
            loadedContent = contentToSave
            fileName = normalizedBaseName
            Toast
              .makeText(
                context,
                context.getString(R.string.toast_file_saved, finalFileName),
                Toast.LENGTH_SHORT,
              ).show()
            backStack.popSafely()
          }
        } catch (e: Exception) {
          withContext(Dispatchers.Main) {
            Toast
              .makeText(
                context,
                context.getString(
                  R.string.toast_failed_to_save_reason,
                  e.message ?: context.getString(R.string.generic_unknown_error),
                ),
                Toast.LENGTH_LONG,
              ).show()
          }
        }
      }
    }

    fun shareScript() {
      val targetName = scriptName
      if (isNewScript || targetName.isNullOrBlank()) {
        Toast
          .makeText(
            context,
            context.getString(R.string.ui_save_the_script_first_before_sharing),
            Toast.LENGTH_SHORT,
          ).show()
        return
      }

      val snapshotContent = scriptContent
      scope.launch(Dispatchers.IO) {
        try {
          val cacheFile = File(context.cacheDir, targetName)
          cacheFile.writeText(snapshotContent, Charsets.UTF_8)

          val contentUri =
            FileProvider.getUriForFile(
              context,
              "${context.packageName}.provider",
              cacheFile,
            )

          withContext(Dispatchers.Main) {
            val shareIntent =
              Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, targetName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              }
            context.startActivity(Intent.createChooser(shareIntent, "Share $targetName"))
          }
        } catch (e: Exception) {
          withContext(Dispatchers.Main) {
            Toast
              .makeText(
                context,
                context.getString(
                  R.string.toast_error_sharing_script,
                  e.message ?: context.getString(R.string.generic_unknown_error),
                ),
                Toast.LENGTH_LONG,
              ).show()
          }
        }
      }
    }

    fun deleteScript() {
      val targetName = scriptName
      if (isNewScript || targetName.isNullOrBlank()) {
        dismissKeyboard()
        backStack.popSafely()
        return
      }

      dismissKeyboard()
      scope.launch(Dispatchers.IO) {
        try {
          val tree = DocumentFile.fromTreeUri(context, mpvConfStorageLocation.toUri())
          if (tree != null && tree.exists()) {
            val scriptsDir =
              tree.listFiles().firstOrNull {
                it.isDirectory && it.name?.equals("scripts", ignoreCase = true) == true
              } ?: tree

            val scriptFile = scriptsDir.findFile(targetName)
            if (scriptFile != null && scriptFile.exists()) {
              val deleted = scriptFile.delete()
              if (deleted) {
                val selectedScripts = preferences.selectedLuaScripts.get()
                if (selectedScripts.contains(targetName)) {
                  preferences.selectedLuaScripts.set(selectedScripts - targetName)
                }
                File(File(context.filesDir, "scripts"), targetName).delete()

                withContext(Dispatchers.Main) {
                  Toast
                    .makeText(
                      context,
                      context.getString(R.string.toast_file_deleted, targetName),
                      Toast.LENGTH_SHORT,
                    ).show()
                  backStack.popSafely()
                }
              }
            }
          }
        } catch (e: Exception) {
          withContext(Dispatchers.Main) {
            Toast
              .makeText(
                context,
                "Failed to delete: ${e.message}",
                Toast.LENGTH_LONG,
              ).show()
          }
        }
      }
    }

    Surface(
      modifier = Modifier.fillMaxSize(),
      color = MaterialTheme.colorScheme.background,
    ) {
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .windowInsetsPadding(
              WindowInsets.navigationBars
                .only(WindowInsetsSides.Bottom)
                .union(WindowInsets.ime),
            ),
      ) {
        AnimatedVisibility(
          visible = !isFullscreen,
          enter =
            if (reduceMotion) {
              EnterTransition.None
            } else {
              expandVertically(
                animationSpec = AppMotion.IntSizeSpring,
                expandFrom = Alignment.Top,
              ) + fadeIn(animationSpec = AppMotion.Effect.Alpha)
            },
          exit =
            if (reduceMotion) {
              ExitTransition.None
            } else {
              shrinkVertically(
                animationSpec = AppMotion.IntSizeSpring,
                shrinkTowards = Alignment.Top,
              ) + fadeOut(animationSpec = AppMotion.Effect.Alpha)
            },
        ) {
          Column(modifier = Modifier.fillMaxWidth()) {
            TopAppBar(
              colors =
                TopAppBarDefaults.topAppBarColors(
                  containerColor = MaterialTheme.colorScheme.surface,
                ),
              navigationIcon = {
                IconButton(onClick = ::requestClose) {
                  Icon(
                    imageVector = Icons.RoundedFilled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface,
                  )
                }
              },
              title = {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                  Text(
                    text =
                      stringResource(
                        if (isNewScript) R.string.ui_create_new_script else R.string.lua_editor_title,
                      ),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                  ) {
                    Text(
                      text = effectiveFileName,
                      style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.weight(1f, fill = false),
                    )
                    if (hasUnsavedChanges) {
                      Box(
                        modifier =
                          Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                      )
                      Text(
                        text = stringResource(R.string.ui_unsaved_changes),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                    }
                  }
                }
              },
              actions = {
                Row(
                  modifier = Modifier.padding(end = 8.dp),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  IconButton(
                    onClick = {
                      haptics.tick()
                      isFullscreen = true
                    },
                    modifier = Modifier.size(38.dp),
                    shape = CircleShape,
                    colors =
                      IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                      ),
                  ) {
                    Icon(
                      imageVector = Icons.RoundedFilled.ZoomOutMap,
                      contentDescription = stringResource(R.string.lua_editor_fullscreen),
                      modifier = Modifier.size(19.dp),
                    )
                  }

                  IconButton(
                    onClick = {
                      dismissKeyboard()
                      rootBackStack.navigateTo(MpvHelpScreen())
                    },
                    modifier = Modifier.size(38.dp),
                    shape = CircleShape,
                    colors =
                      IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                      ),
                  ) {
                    Icon(
                      imageVector = Icons.RoundedFilled.Info,
                      contentDescription = stringResource(R.string.ui_help),
                      modifier = Modifier.size(19.dp),
                    )
                  }

                  if (!isNewScript) {
                    Box {
                      IconButton(
                        onClick = { showMoreMenu = true },
                        modifier = Modifier.size(38.dp),
                        shape = CircleShape,
                        colors =
                          IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                          ),
                      ) {
                        Icon(
                          imageVector = Icons.RoundedFilled.MoreVert,
                          contentDescription = null,
                          modifier = Modifier.size(19.dp),
                        )
                      }

                      AppDropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                      ) {
                        AppDropdownMenuItem(
                          text = { Text(stringResource(R.string.generic_share)) },
                          leadingIcon = {
                            Icon(
                              imageVector = Icons.RoundedFilled.Share,
                              contentDescription = null,
                              modifier = Modifier.size(18.dp),
                            )
                          },
                          index = 0,
                          itemCount = 2,
                          onClick = {
                            showMoreMenu = false
                            shareScript()
                          },
                        )
                        AppDropdownMenuItem(
                          text = {
                            Text(
                              text = stringResource(R.string.delete),
                              color = MaterialTheme.colorScheme.error,
                              fontWeight = FontWeight.SemiBold,
                            )
                          },
                          leadingIcon = {
                            Icon(
                              imageVector = Icons.RoundedFilled.Delete,
                              contentDescription = null,
                              tint = MaterialTheme.colorScheme.error,
                              modifier = Modifier.size(18.dp),
                            )
                          },
                          index = 1,
                          itemCount = 2,
                          onClick = {
                            showMoreMenu = false
                            showDeleteDialog = true
                          },
                        )
                      }
                    }
                  }

                  val saveInteraction = remember { MutableInteractionSource() }
                  Surface(
                    onClick = ::saveScript,
                    enabled = canSave,
                    interactionSource = saveInteraction,
                    shape = CircleShape,
                    color =
                      if (canSave) {
                        MaterialTheme.colorScheme.primary
                      } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
                      },
                    contentColor =
                      if (canSave) {
                        MaterialTheme.colorScheme.onPrimary
                      } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                      },
                    modifier =
                      Modifier
                        .height(38.dp)
                        .appPressFeedback(saveInteraction, enabled = canSave, pressedScale = 0.96f, reduceMotion = reduceMotion)
                        .tvFocusHighlight(CircleShape),
                  ) {
                    Row(
                      modifier = Modifier.padding(horizontal = 14.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                      Icon(
                        imageVector = Icons.RoundedFilled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                      )
                      Text(
                        text = stringResource(R.string.ui_save),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                      )
                    }
                  }
                }
              },
            )

            // Script Identity & Language Control Strip
            Surface(
              color = MaterialTheme.colorScheme.surfaceContainerLow,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .tvOverscanPadding(top = false, bottom = false)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Surface(
                  shape = AppShapeScale.medium,
                  color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
                  border =
                    BorderStroke(
                      width = if (isFileNameFocused) 1.5.dp else 1.dp,
                      color =
                        if (isFileNameFocused) {
                          MaterialTheme.colorScheme.primary
                        } else {
                          MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)
                        },
                    ),
                  modifier = Modifier.weight(1f).height(40.dp),
                ) {
                  Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Icon(
                      imageVector = Icons.RoundedFilled.Code,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(18.dp),
                    )
                    BasicTextField(
                      value = fileName,
                      onValueChange = { updated ->
                        val cleaned = updated.replace("\n", "").replace("\r", "")
                        when {
                          cleaned.endsWith(".lua", ignoreCase = true) -> {
                            fileName = cleaned.dropLast(4)
                            scriptExtension = "lua"
                          }
                          cleaned.endsWith(".js", ignoreCase = true) -> {
                            fileName = cleaned.dropLast(3)
                            scriptExtension = "js"
                          }
                          else -> fileName = cleaned
                        }
                      },
                      singleLine = true,
                      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                      keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                      textStyle =
                        MaterialTheme.typography.bodyMedium.copy(
                          fontFamily = FontFamily.Monospace,
                          fontWeight = FontWeight.SemiBold,
                          color = MaterialTheme.colorScheme.onSurface,
                        ),
                      cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                      modifier =
                        Modifier
                          .weight(1f)
                          .focusRequester(fileNameFocusRequester)
                          .onFocusChanged { isFileNameFocused = it.isFocused },
                      decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                          if (fileName.isEmpty()) {
                            Text(
                              text = stringResource(R.string.ui_script_name),
                              style =
                                MaterialTheme.typography.bodyMedium.copy(
                                  fontFamily = FontFamily.Monospace,
                                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                ),
                              maxLines = 1,
                              overflow = TextOverflow.Ellipsis,
                            )
                          }
                          innerTextField()
                        }
                      },
                    )
                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
                      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                      Text(
                        text = ".$scriptExtension",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                      )
                    }
                  }
                }

                Row(
                  horizontalArrangement = Arrangement.spacedBy(AppConnectedControlShapeTokens.spacing),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  ScriptExtensionSegment(
                    label = "Lua",
                    index = 0,
                    itemCount = 2,
                    selected = scriptExtension == "lua",
                    reduceMotion = reduceMotion,
                    onClick = {
                      if (scriptExtension != "lua") {
                        haptics.tick()
                        scriptExtension = "lua"
                      }
                    },
                  )
                  ScriptExtensionSegment(
                    label = "JS",
                    index = 1,
                    itemCount = 2,
                    selected = scriptExtension == "js",
                    reduceMotion = reduceMotion,
                    onClick = {
                      if (scriptExtension != "js") {
                        haptics.tick()
                        scriptExtension = "js"
                      }
                    },
                  )
                }
              }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
          }
        }

        // Full-bleed edge-to-edge code canvas + docked bottom toolbar
        Box(
          modifier =
            Modifier
              .weight(1f)
              .fillMaxSize(),
        ) {
          MpvScriptEditor(
            content = scriptContent,
            onContentChange = {
              userEditedWhileLoading = true
              scriptContent = it
            },
            language = scriptExtension,
            modifier = Modifier.fillMaxSize(),
          )

          // Compact floating HUD pill when distraction-free fullscreen is active
          AnimatedVisibility(
            visible = isFullscreen,
            enter = if (reduceMotion) EnterTransition.None else fadeIn(AppMotion.Effect.Alpha),
            exit = if (reduceMotion) ExitTransition.None else fadeOut(AppMotion.Effect.Alpha),
            modifier =
              Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .padding(top = 8.dp, end = 12.dp),
          ) {
            Surface(
              shape = CircleShape,
              color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
              contentColor = MaterialTheme.colorScheme.onSurface,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
              shadowElevation = 4.dp,
            ) {
              Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                if (hasUnsavedChanges) {
                  Box(
                    modifier =
                      Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                  )
                }
                Text(
                  text = effectiveFileName,
                  style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                )
                if (canSave) {
                  IconButton(
                    onClick = ::saveScript,
                    modifier = Modifier.size(30.dp),
                    shape = CircleShape,
                    colors =
                      IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                      ),
                  ) {
                    Icon(
                      imageVector = Icons.RoundedFilled.Check,
                      contentDescription = stringResource(R.string.ui_save),
                      modifier = Modifier.size(16.dp),
                    )
                  }
                }
                IconButton(
                  onClick = {
                    haptics.tick()
                    isFullscreen = false
                  },
                  modifier = Modifier.size(30.dp),
                  shape = CircleShape,
                  colors =
                    IconButtonDefaults.iconButtonColors(
                      containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                      contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.FitScreen,
                    contentDescription = stringResource(R.string.lua_editor_exit_fullscreen),
                    modifier = Modifier.size(16.dp),
                  )
                }
              }
            }
          }
        }
      }
    }

    if (showDiscardDialog) {
      AppAlertDialog(
        onDismissRequest = { showDiscardDialog = false },
        title = { Text(stringResource(R.string.lua_editor_discard_title)) },
        text = { Text(stringResource(R.string.lua_editor_discard_message, effectiveFileName)) },
        confirmButton = {
          AppDialogTextButton(
            onClick = {
              showDiscardDialog = false
              dismissKeyboard()
              backStack.popSafely()
            },
          ) {
            Text(
              text = stringResource(R.string.lyrics_editor_discard),
              color = MaterialTheme.colorScheme.error,
            )
          }
        },
        dismissButton = {
          AppDialogTextButton(onClick = { showDiscardDialog = false }) {
            Text(stringResource(R.string.generic_cancel))
          }
        },
      )
    }

    if (showDeleteDialog) {
      ConfirmDialog(
        title = stringResource(R.string.lua_delete_script_title),
        subtitle = stringResource(R.string.lua_delete_script_message, scriptName ?: effectiveFileName),
        onConfirm = {
          deleteScript()
          showDeleteDialog = false
        },
        onCancel = {
          showDeleteDialog = false
        },
      )
    }
  }
}

@Composable
private fun ScriptExtensionSegment(
  label: String,
  index: Int,
  itemCount: Int,
  selected: Boolean,
  reduceMotion: Boolean,
  onClick: () -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  val shape =
    rememberConnectedShape(
      index = index,
      itemCount = itemCount,
      layout = ConnectedLayout.Horizontal,
      tokens = AppConnectedControlShapeTokens,
      pressed = pressed,
      selected = selected,
      reduceMotion = reduceMotion,
    )
  val containerColor =
    animatedConnectedContainerColor(
      selected = selected,
      pressed = pressed,
      containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
      selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
      reduceMotion = reduceMotion,
    )

  Surface(
    onClick = onClick,
    interactionSource = interactionSource,
    shape = shape,
    color = containerColor,
    contentColor =
      if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
      } else {
        MaterialTheme.colorScheme.onSurfaceVariant
      },
    border =
      BorderStroke(
        width = 1.dp,
        color =
          if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
          } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
          },
      ),
    modifier =
      Modifier
        .height(40.dp)
        .appPressFeedback(interactionSource, pressedScale = 0.96f, reduceMotion = reduceMotion)
        .tvFocusHighlight(shape),
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.padding(horizontal = 14.dp),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
      )
    }
  }
}

