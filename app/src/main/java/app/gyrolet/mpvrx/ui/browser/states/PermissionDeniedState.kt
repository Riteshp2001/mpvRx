/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

@file:Suppress("DEPRECATION")

package app.gyrolet.mpvrx.ui.browser.states

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import app.gyrolet.mpvrx.ui.utils.NavigationBackHandler as BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.download.DownloadLocations
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.FoldersPreferences
import app.gyrolet.mpvrx.preferences.SettingsManager
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.expressiveDisplayFontForText
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor
import app.gyrolet.mpvrx.utils.permission.PermissionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private fun checkFilePermission(context: Context): Boolean {
  val isPlayStoreBuild = BuildConfig.SCOPED_STORAGE_ONLY
  return if (!isPlayStoreBuild && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    Environment.isExternalStorageManager()
  } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
    true
  } else {
    ContextCompat.checkSelfPermission(
      context,
      PermissionUtils.getStoragePermission(),
    ) == PackageManager.PERMISSION_GRANTED
  }
}

private fun checkNotificationPermission(context: Context): Boolean {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    ContextCompat.checkSelfPermission(
      context,
      android.Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
  } else {
    true
  }
}

private fun checkAudioPermission(context: Context): Boolean {
  return ContextCompat.checkSelfPermission(
    context,
    android.Manifest.permission.RECORD_AUDIO,
  ) == PackageManager.PERMISSION_GRANTED
}

private fun openStoragePermissionSettings(context: Context): Boolean {
  val packageUri = Uri.parse("package:${context.packageName}")
  val intents =
    listOf(
      Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = packageUri },
      Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = packageUri },
    )
  return intents.any { intent -> runCatching { context.startActivity(intent) }.isSuccess }
}

private enum class OnboardingStep { STORAGE, CONFIGURATION, NOTIFICATIONS, AUDIO, FINISH }

@SuppressLint("UseKtx")
@Composable
fun PermissionDeniedState(
  onRequestPermission: () -> Unit,
  modifier: Modifier = Modifier,
  onNext: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val isTelevision = DeviceFormFactor.isTelevision(context)
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  var showExplanationDialog by remember { mutableStateOf(false) }

  val isPlayStoreBuild = remember { BuildConfig.SCOPED_STORAGE_ONLY }

  var isFileGranted by remember { mutableStateOf(checkFilePermission(context)) }
  var isNotificationGranted by remember { mutableStateOf(checkNotificationPermission(context)) }
  var isAudioGranted by remember { mutableStateOf(checkAudioPermission(context)) }

  // Initial check on composition
  LaunchedEffect(Unit) {
    isFileGranted = checkFilePermission(context)
    isNotificationGranted = checkNotificationPermission(context)
    isAudioGranted = checkAudioPermission(context)
  }

  // Re-check permissions whenever activity resumes from system settings or permission dialogs
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        isFileGranted = checkFilePermission(context)
        isNotificationGranted = checkNotificationPermission(context)
        isAudioGranted = checkAudioPermission(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  // Launcher for notification permission prompt
  val notificationLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    isNotificationGranted = granted || checkNotificationPermission(context)
    if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val activity = context as? Activity
      if (activity != null && !activity.shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
          putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        runCatching { context.startActivity(intent) }
      }
    }
  }

  // Launcher for audio permission prompt
  val audioLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    isAudioGranted = granted || checkAudioPermission(context)
    if (!granted) {
      val activity = context as? Activity
      if (activity != null && !activity.shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
          data = Uri.parse("package:${context.packageName}")
        }
        runCatching { context.startActivity(intent) }
      }
    }
  }

  val browserPreferences = koinInject<BrowserPreferences>()
  val advancedPreferences = koinInject<AdvancedPreferences>()
  val foldersPreferences = koinInject<FoldersPreferences>()
  val subtitlesPreferences = koinInject<SubtitlesPreferences>()
  val settingsManager = koinInject<SettingsManager>()
  val downloadLocations = koinInject<DownloadLocations>()
  var configurationFolderUri by rememberSaveable { mutableStateOf(advancedPreferences.mpvConfStorageUri.get()) }
  var configurationMessage by rememberSaveable { mutableStateOf<String?>(null) }
  var restoredSettings by rememberSaveable { mutableStateOf(false) }
  var configuringFolder by remember { mutableStateOf(false) }
  val configurationFolderLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      runCatching {
        context.contentResolver.takePersistableUriPermission(
          uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
      }
      configuringFolder = true
      configurationMessage = context.getString(R.string.onboarding_configuration_restoring)
      scope.launch {
        val uriString = uri.toString()
        try {
          withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, uri) ?: error("Configuration folder is unavailable")
            listOf("Backup", "Downloads", "fonts", "Subtitles", "scripts", "script-opts", "shaders").forEach { name ->
              val existing = root.findFile(name)
              if (existing == null) root.createDirectory(name) else require(existing.isDirectory) { "$name is not a folder" }
            }
            check(downloadLocations.prepareLocationUnderTree(uri, "Downloads") != null) {
              "Downloads folder is not accessible"
            }
          }
          val restored = settingsManager.restoreNewestFromConfiguration(uriString)
          val previousBaseStorageFolder = foldersPreferences.baseStorageFolder.get()
          if (subtitlesPreferences.fontsFolder.get() == previousBaseStorageFolder) {
            subtitlesPreferences.fontsFolder.set("")
          }
          foldersPreferences.baseStorageFolder.set(uriString)
          advancedPreferences.mpvConfStorageUri.set(uriString)
          subtitlesPreferences.subtitleSaveFolder.set(uriString)
          check(downloadLocations.setLocationUnderTree(uri, "Downloads") != null) {
            "Downloads folder is not accessible"
          }
          configurationFolderUri = uriString
          configurationMessage = restored.fold(
            onSuccess = { result ->
              if (result == null) {
                context.getString(R.string.onboarding_configuration_no_backup)
              } else {
                restoredSettings = true
                context.getString(R.string.onboarding_configuration_restored, result.fileName)
              }
            },
            onFailure = { error ->
              context.getString(
                R.string.onboarding_configuration_restore_failed,
                error.message ?: context.getString(R.string.generic_unknown_error),
              )
            },
          )
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          configurationMessage = context.getString(
            R.string.onboarding_configuration_restore_failed,
            error.message ?: context.getString(R.string.generic_unknown_error),
          )
        } finally {
          configuringFolder = false
        }
      }
    }
  val steps =
    remember {
      buildList {
        add(OnboardingStep.STORAGE)
        add(OnboardingStep.CONFIGURATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(OnboardingStep.NOTIFICATIONS)
        add(OnboardingStep.AUDIO)
        add(OnboardingStep.FINISH)
      }
    }
  var stepIndex by rememberSaveable { mutableIntStateOf(0) }
  val currentStep = steps[stepIndex.coerceIn(0, steps.lastIndex)]
  val currentStepGranted =
    when (currentStep) {
      OnboardingStep.STORAGE -> isFileGranted
      OnboardingStep.CONFIGURATION -> configurationFolderUri.isNotBlank() && !configuringFolder
      OnboardingStep.NOTIFICATIONS -> isNotificationGranted
      OnboardingStep.AUDIO -> isAudioGranted
      OnboardingStep.FINISH -> true
    }
  val reduceMotion = AppMotion.shouldReduceMotion()
  val stepFocusRequester =
    rememberTvInitialFocusRequester(requestKey = currentStep to currentStepGranted)
  val explanationFocusRequester =
    rememberTvInitialFocusRequester(
      enabled = showExplanationDialog,
      requestKey = showExplanationDialog,
    )
  val goNext: () -> Unit = { if (stepIndex < steps.lastIndex) stepIndex++ }
  val finishSetup: () -> Unit = {
    browserPreferences.onboardingCompleted.set(true)
    onNext?.invoke()
    if (restoredSettings) {
      (context as? Activity)?.recreate()
    } else if (onNext == null) {
      onRequestPermission()
    }
  }

  BackHandler(enabled = isTelevision && stepIndex > 0 && !showExplanationDialog) {
    stepIndex--
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .padding(
        top = if (isTelevision) 32.dp else 16.dp,
        bottom = if (isTelevision) 32.dp else 48.dp,
      ),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize(),
      color = MaterialTheme.colorScheme.background,
    ) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          modifier = Modifier
            .widthIn(max = if (isTelevision) 760.dp else 560.dp)
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(
              horizontal = if (isTelevision) 48.dp else 24.dp,
              vertical = 16.dp,
            ),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          // Step progress dots
          Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
          ) {
            steps.forEachIndexed { index, _ ->
              val isCurrent = index == stepIndex
              val indicatorWidth by
                animateDpAsState(
                  targetValue = if (isCurrent) 28.dp else 8.dp,
                  animationSpec = if (reduceMotion) AppMotion.ReducedDp else AppMotion.Spatial.SnappyDp,
                  label = "onboarding progress width",
                )
              val indicatorColor by
                animateColorAsState(
                  targetValue =
                    if (isCurrent) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                  animationSpec = if (reduceMotion) snap() else AppMotion.Effect.Color,
                  label = "onboarding progress color",
                )
              Surface(
                shape = AppShapeScale.full,
                color = indicatorColor,
                modifier = Modifier
                  .height(8.dp)
                  .width(indicatorWidth),
              ) {}
            }
          }

          // One step per page so nothing ever overflows the display
          Column(
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth()
              .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
          ) {
            AnimatedContent(
              targetState = currentStep,
              transitionSpec = {
                if (reduceMotion) {
                  fadeIn(animationSpec = tween(120)) togetherWith fadeOut(animationSpec = tween(90))
                } else {
                  val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                  (
                    slideInHorizontally(
                      animationSpec = tween(360, easing = FastOutSlowInEasing),
                      initialOffsetX = { width -> direction * width / 4 },
                    ) +
                      fadeIn(animationSpec = tween(240, delayMillis = 60)) +
                      scaleIn(initialScale = 0.97f, animationSpec = tween(360, easing = FastOutSlowInEasing))
                  ) togetherWith
                    (
                      slideOutHorizontally(
                        animationSpec = tween(240, easing = FastOutSlowInEasing),
                        targetOffsetX = { width -> -direction * width / 5 },
                      ) +
                        fadeOut(animationSpec = tween(150)) +
                        scaleOut(targetScale = 0.99f, animationSpec = tween(180))
                    )
                }
              },
              label = "onboarding_step",
            ) { step ->
              Column(
                horizontalAlignment = if (isTelevision) Alignment.CenterHorizontally else Alignment.Start,
                modifier = Modifier.fillMaxWidth(),
              ) {
                val stepIcon = when (step) {
                  OnboardingStep.STORAGE -> Icons.RoundedFilled.Folder
                  OnboardingStep.CONFIGURATION -> Icons.RoundedFilled.Settings
                  OnboardingStep.NOTIFICATIONS -> Icons.RoundedFilled.Notifications
                  OnboardingStep.AUDIO -> Icons.RoundedFilled.Mic
                  OnboardingStep.FINISH -> Icons.RoundedFilled.CheckCircle
                }

                Surface(
                  modifier = Modifier.size(72.dp),
                  shape = AppShapeScale.extraLarge,
                  color = MaterialTheme.colorScheme.primaryContainer,
                  tonalElevation = 2.dp,
                ) {
                  Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                  ) {
                    Icon(
                      imageVector = stepIcon,
                      contentDescription = null,
                      modifier = Modifier.size(36.dp),
                      tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                  }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val pageTitle =
                  when (step) {
                    OnboardingStep.FINISH -> stringResource(R.string.onboarding_all_set_title)
                    OnboardingStep.CONFIGURATION -> stringResource(R.string.onboarding_configuration_title)
                    else -> stringResource(R.string.ui_app_permissions)
                  }
                Text(
                  text = "${steps.indexOf(step) + 1} / ${steps.size}",
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.primary,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                  text = pageTitle,
                  style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = expressiveDisplayFontForText(pageTitle),
                    fontWeight = FontWeight.Normal,
                  ),
                  textAlign = if (isTelevision) TextAlign.Center else TextAlign.Start,
                  color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                  text = when (step) {
                    OnboardingStep.FINISH -> stringResource(R.string.onboarding_all_set_desc)
                    OnboardingStep.CONFIGURATION -> stringResource(R.string.onboarding_configuration_desc)
                    else -> stringResource(R.string.ui_permissions_setup_subtitle)
                  },
                  style = MaterialTheme.typography.bodyLarge,
                  textAlign = if (isTelevision) TextAlign.Center else TextAlign.Start,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(28.dp))

                when (step) {
                  OnboardingStep.STORAGE ->
                    PermissionSectionCard(
                      title = stringResource(R.string.ui_file_permission_title),
                      description = if (isPlayStoreBuild) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                          stringResource(R.string.ui_file_permission_desc_playstore_tiramisu)
                        } else {
                          stringResource(R.string.ui_file_permission_desc_playstore)
                        }
                      } else {
                        stringResource(R.string.ui_file_permission_desc_all_files)
                      },
                      isGranted = isFileGranted,
                      icon = Icons.RoundedFilled.Folder,
                      modifier =
                        if (!isFileGranted) {
                          Modifier.tvInitialFocus(stepFocusRequester)
                        } else {
                          Modifier
                        },
                      onClick = {
                        if (!isFileGranted) {
                          if (isPlayStoreBuild) {
                            onRequestPermission()
                          } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                              if (!openStoragePermissionSettings(context)) onRequestPermission()
                            } else {
                              onRequestPermission()
                            }
                          }
                        }
                      },
                    )

                  OnboardingStep.CONFIGURATION ->
                    PermissionSectionCard(
                      title = stringResource(R.string.onboarding_configuration_card_title),
                      description = configurationMessage
                        ?: stringResource(R.string.onboarding_configuration_card_desc),
                      isGranted = configurationFolderUri.isNotBlank() && !configuringFolder,
                      icon = Icons.RoundedFilled.Folder,
                      modifier =
                        if (configurationFolderUri.isBlank() && !configuringFolder) {
                          Modifier.tvInitialFocus(stepFocusRequester)
                        } else {
                          Modifier
                        },
                      onClick = { if (!configuringFolder) configurationFolderLauncher.launch(null) },
                    )

                  OnboardingStep.NOTIFICATIONS ->
                    PermissionSectionCard(
                      title = stringResource(R.string.ui_notification_permission_title),
                      description = stringResource(R.string.ui_notification_permission_desc),
                      isGranted = isNotificationGranted,
                      icon = Icons.RoundedFilled.Notifications,
                      modifier =
                        if (!isNotificationGranted) {
                          Modifier.tvInitialFocus(stepFocusRequester)
                        } else {
                          Modifier
                        },
                      onClick = {
                        if (!isNotificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                          notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                      },
                    )

                  OnboardingStep.AUDIO ->
                    PermissionSectionCard(
                      title = stringResource(R.string.ui_audio_record_permission_title),
                      description = stringResource(R.string.ui_audio_record_permission_desc),
                      isGranted = isAudioGranted,
                      icon = Icons.RoundedFilled.Mic,
                      modifier =
                        if (!isAudioGranted) {
                          Modifier.tvInitialFocus(stepFocusRequester)
                        } else {
                          Modifier
                        },
                      onClick = {
                        if (!isAudioGranted) {
                          audioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                      },
                    )

                  OnboardingStep.FINISH -> {
                    val missingPermissions = buildList {
                      if (!isFileGranted) add(stringResource(R.string.ui_file_permission_title))
                      if (!isNotificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(stringResource(R.string.ui_notification_permission_title))
                      }
                      if (!isAudioGranted) add(stringResource(R.string.ui_audio_record_permission_title))
                    }
                    if (missingPermissions.isNotEmpty()) {
                      val warningColor = Color(0xFFFFB300)
                      Surface(
                        shape = AppShapeScale.largeIncreased,
                        color = warningColor.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth(),
                      ) {
                        Row(
                          verticalAlignment = Alignment.Top,
                          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                          Icon(
                            imageVector = Icons.RoundedFilled.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = warningColor,
                          )
                          Spacer(modifier = Modifier.width(12.dp))
                          Column {
                            Text(
                              text = stringResource(R.string.onboarding_missing_permissions_title),
                              style = MaterialTheme.typography.titleSmall,
                              fontWeight = FontWeight.SemiBold,
                              color = warningColor,
                            )
                            missingPermissions.forEach { permission ->
                              Text(
                                text = "\u2022 $permission",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                              )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                              text = stringResource(R.string.onboarding_missing_permissions_desc),
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
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

          // Bottom controls: Skip everywhere; Next only unlocks once the step's permission is granted
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 16.dp),
          ) {
            val nextEnabled = currentStepGranted
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (currentStep != OnboardingStep.FINISH) {
                TextButton(
                  onClick = {
                    // Skipping storage skips every permission step
                    if (currentStep == OnboardingStep.STORAGE) stepIndex = steps.lastIndex else goNext()
                  },
                  modifier = Modifier
                    .weight(0.35f)
                    .tvFocusHighlight(AppShapeScale.large, focusedScale = 1.04f)
                    .height(54.dp),
                  shape = AppShapeScale.large,
                ) {
                  Text(
                    text = stringResource(R.string.onboarding_skip),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                  )
                }
              }

              Button(
                onClick = {
                  if (!nextEnabled) return@Button
                  if (currentStep == OnboardingStep.FINISH) finishSetup() else goNext()
                },
                enabled = nextEnabled,
                modifier = Modifier
                  .weight(1f)
                  .then(
                    if (nextEnabled) {
                      Modifier.tvInitialFocus(stepFocusRequester)
                    } else {
                      Modifier
                    },
                  ).tvFocusHighlight(AppShapeScale.large, enabled = nextEnabled, focusedScale = 1.04f)
                  .height(54.dp)
                  .alpha(if (nextEnabled) 1f else 0.45f),
                shape = AppShapeScale.large,
                colors = ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.primary,
                  contentColor = MaterialTheme.colorScheme.onPrimary,
                  disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                  disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                ),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.Center,
                ) {
                  Text(
                    text = if (currentStep == OnboardingStep.FINISH) {
                      stringResource(R.string.onboarding_get_started)
                    } else {
                      stringResource(R.string.ui_next)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Icon(
                    imageVector = Icons.RoundedFilled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                  )
                }
              }
            }

            if (currentStep == OnboardingStep.STORAGE) {
              Spacer(modifier = Modifier.height(12.dp))

              // "Why do I see this?" link
              TextButton(
                onClick = { showExplanationDialog = true },
                modifier = Modifier.tvFocusHighlight(AppShapeScale.medium, focusedScale = 1.04f),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Info,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = stringResource(R.string.ui_why_do_i_see_this),
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.Medium,
                )
              }
            }
          }
        }
      }
    }
  }

  // Explanation Dialog
  if (showExplanationDialog) {
    val uriHandler = LocalUriHandler.current
    val githubUrl = "https://github.com/Riteshp2001/mpvRx"

    AlertDialog(
      onDismissRequest = { showExplanationDialog = false },
      icon = {
        Icon(
          imageVector = Icons.RoundedFilled.Info,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
      },
      title = {
        Text(
          text = stringResource(R.string.ui_why_this_permission_is_needed),
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
        )
      },
      text = {
        Column(
          modifier = Modifier
            .heightIn(max = 400.dp)
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (isPlayStoreBuild) {
            Text(
              text = stringResource(R.string.ui_mpvrx_needs_access_to_your_video_files_to_provide_its_core_funct),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "On Android 13 and above, this permission allows the app to read video files from your device's storage, including Downloads, Movies, and DCIM folders."
              } else {
                "This permission allows the app to read media files from your device's storage to play videos and audio."
              },
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = stringResource(R.string.ui_the_permission_is_used_exclusively_for),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              fontWeight = FontWeight.Medium,
            )
            Text(
              text = stringResource(R.string.ui_discovering_and_displaying_your_video_files_n_playing_media_cont),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            Text(
              text = stringResource(R.string.ui_mpvrx_has_always_required_storage_access_permission_as_it_s_esse),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = stringResource(R.string.ui_however_due_to_a_change_in_security_policy_apps_built_for_androi),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = stringResource(R.string.ui_please_know_that_this_permission_is_only_used_for_the_auto_disco),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          Text(
            text = stringResource(R.string.ui_mpvrx_is_an_open_source_project_you_can_review_the_source_code_a),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          val annotatedString = buildAnnotatedString {
            pushStringAnnotation(
              tag = "URL",
              annotation = githubUrl,
            )
            withStyle(
              style = SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                textDecoration = TextDecoration.Underline,
              ),
            ) {
              append(githubUrl)
            }
            pop()
          }

          ClickableText(
            text = annotatedString,
            style = MaterialTheme.typography.bodyMedium,
            onClick = { offset ->
              annotatedString
                .getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()
                ?.let { uriHandler.openUri(it.item) }
            },
          )

          Text(
            text = stringResource(R.string.ui_be_rest_assured_your_privacy_is_our_utmost_priority_and_we_neith),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
          )
        }
      },
      confirmButton = {
        FilledTonalButton(
          onClick = { showExplanationDialog = false },
          shape = AppShapeScale.medium,
          modifier =
            Modifier
              .tvInitialFocus(explanationFocusRequester)
              .tvFocusHighlight(AppShapeScale.medium, focusedScale = 1.04f),
        ) {
          Text(stringResource(R.string.got_it))
        }
      },
      shape = AppShapeScale.extraLarge,
    )
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PermissionSectionCard(
  title: String,
  description: String,
  isGranted: Boolean,
  icon: app.gyrolet.mpvrx.ui.icons.AppIcon,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isTelevision = DeviceFormFactor.isTelevision(LocalContext.current)
  val cardBgColor by animateColorAsState(
    targetValue = if (isGranted) {
      MaterialTheme.colorScheme.surfaceContainerLowest
    } else {
      MaterialTheme.colorScheme.surfaceContainer
    },
    animationSpec = if (AppMotion.shouldReduceMotion()) snap() else AppMotion.Effect.Color,
    label = "card_bg",
  )

  val borderModifier = if (!isGranted) {
    Modifier.border(
      width = 1.dp,
      color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
      shape = AppShapeScale.largeIncreased,
    )
  } else {
    Modifier
  }

  Card(
    modifier = modifier
      .fillMaxWidth()
      .then(borderModifier)
      .tvFocusHighlight(
        AppShapeScale.largeIncreased,
        enabled = !isGranted,
        focusedScale = 1.025f,
      )
      .clip(AppShapeScale.largeIncreased)
      .clickable(enabled = !isGranted, onClick = onClick),
    colors = CardDefaults.cardColors(containerColor = cardBgColor),
    shape = AppShapeScale.largeIncreased,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(18.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Left Icon Container
      Surface(
        modifier = Modifier.size(44.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (isGranted) {
          MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
          MaterialTheme.colorScheme.primaryContainer
        },
      ) {
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier.fillMaxSize(),
        ) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (isGranted) {
              MaterialTheme.colorScheme.onSurfaceVariant
            } else {
              MaterialTheme.colorScheme.onPrimaryContainer
            },
          )
        }
      }

      Spacer(modifier = Modifier.width(14.dp))

      // Middle Title & Description
      Column(
        modifier = Modifier.weight(1f),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = title,
            modifier = Modifier.weight(1f, fill = false),
            style = if (isTelevision) MaterialTheme.typography.titleLargeEmphasized else MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
          )

          if (isGranted) {
            Spacer(modifier = Modifier.width(8.dp))
            PillBadge(text = stringResource(R.string.ui_permission_granted))
          }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
          text = description,
          style = if (isTelevision) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!isGranted) {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = stringResource(R.string.ui_grant_permission_hint),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      // Right Status Indicator
      if (isGranted) {
        Icon(
          imageVector = Icons.RoundedFilled.CheckCircle,
          contentDescription = null,
          modifier = Modifier.size(24.dp),
          tint = MaterialTheme.colorScheme.primary,
        )
      } else {
        Icon(
          imageVector = Icons.RoundedFilled.ChevronRight,
          contentDescription = null,
          modifier = Modifier.size(24.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PillBadge(text: String) {
  Box(
    modifier = Modifier
      .background(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = AppShapeScale.full,
      )
      .padding(horizontal = 8.dp, vertical = 2.dp),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmallEmphasized,
      color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
  }
}

/** Compact in-place prompt for screens that need storage access after onboarding was skipped. */
@Composable
fun StoragePermissionPrompt(
  onRequestPermission: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val isPlayStoreBuild = remember { BuildConfig.SCOPED_STORAGE_ONLY }
  var isFileGranted by remember { mutableStateOf(checkFilePermission(context)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  val isTelevision = DeviceFormFactor.isTelevision(context)
  val grantFocusRequester =
    rememberTvInitialFocusRequester(
      enabled = !isFileGranted,
      requestKey = isFileGranted,
    )

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) isFileGranted = checkFilePermission(context)
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .widthIn(max = if (isTelevision) 560.dp else 420.dp)
        .padding(horizontal = if (isTelevision) 48.dp else 24.dp),
    ) {
      Surface(
        modifier = Modifier.size(64.dp),
        shape = AppShapeScale.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
      ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
          Icon(
            imageVector = Icons.RoundedFilled.FolderOff,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
          )
        }
      }
      Spacer(modifier = Modifier.height(14.dp))
      Text(
        text = stringResource(R.string.storage_permission_needed_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = stringResource(R.string.storage_permission_needed_desc),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(18.dp))
      Button(
        onClick = {
          if (isFileGranted) return@Button
          if (!isPlayStoreBuild && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!openStoragePermissionSettings(context)) onRequestPermission()
          } else {
            onRequestPermission()
          }
        },
        shape = AppShapeScale.large,
        modifier =
          Modifier
            .tvInitialFocus(grantFocusRequester)
            .tvFocusHighlight(AppShapeScale.large, enabled = !isFileGranted, focusedScale = 1.04f)
            .height(48.dp),
      ) {
        Text(
          text = stringResource(R.string.storage_permission_grant),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
        )
      }
    }
  }
}
