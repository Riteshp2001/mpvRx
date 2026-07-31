/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.utils.permission.PermissionUtils

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

@SuppressLint("UseKtx")
@Composable
fun PermissionDeniedState(
  onRequestPermission: () -> Unit,
  modifier: Modifier = Modifier,
  onNext: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var showExplanationDialog by remember { mutableStateOf(false) }

  val isPlayStoreBuild = remember { BuildConfig.SCOPED_STORAGE_ONLY }

  var isFileGranted by remember { mutableStateOf(checkFilePermission(context)) }
  var isNotificationGranted by remember { mutableStateOf(checkNotificationPermission(context)) }

  // Initial check on composition
  LaunchedEffect(Unit) {
    isFileGranted = checkFilePermission(context)
    isNotificationGranted = checkNotificationPermission(context)
  }

  // Re-check permissions whenever activity resumes from system settings or permission dialogs
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        isFileGranted = checkFilePermission(context)
        isNotificationGranted = checkNotificationPermission(context)
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

  Box(
    modifier = modifier
      .fillMaxSize()
      .padding(top = 16.dp, bottom = 48.dp),
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
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Header Icon
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
                  imageVector = Icons.RoundedFilled.Security,
                  contentDescription = null,
                  modifier = Modifier.size(36.dp),
                  tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
              }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Title
            Text(
              text = stringResource(R.string.ui_app_permissions),
              style = MaterialTheme.typography.headlineMedium,
              fontWeight = FontWeight.Bold,
              textAlign = TextAlign.Center,
              color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Subtitle
            Text(
              text = stringResource(R.string.ui_permissions_setup_subtitle),
              style = MaterialTheme.typography.bodyMedium,
              textAlign = TextAlign.Center,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Section 1: File & Storage Access
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
              onClick = {
                if (!isFileGranted) {
                  if (isPlayStoreBuild) {
                    onRequestPermission()
                  } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                      try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:${context.packageName}")
                        context.startActivity(intent)
                      } catch (_: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        context.startActivity(intent)
                      }
                    } else {
                      onRequestPermission()
                    }
                  }
                }
              },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Section 2: Notifications
            PermissionSectionCard(
              title = stringResource(R.string.ui_notification_permission_title),
              description = stringResource(R.string.ui_notification_permission_desc),
              isGranted = isNotificationGranted,
              icon = Icons.RoundedFilled.Notifications,
              onClick = {
                if (!isNotificationGranted) {
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                  }
                }
              },
            )
          }

          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 24.dp),
          ) {
            // Next Button - Disabled until file permission is granted
            Button(
              onClick = {
                if (isFileGranted) {
                  if (onNext != null) {
                    onNext()
                  } else {
                    onRequestPermission()
                  }
                }
              },
              enabled = isFileGranted,
              modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .alpha(if (isFileGranted) 1f else 0.45f),
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
                  text = stringResource(R.string.ui_next),
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

            Spacer(modifier = Modifier.height(12.dp))

            // "Why do I see this?" link
            TextButton(
              onClick = { showExplanationDialog = true },
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
        ) {
          Text(stringResource(R.string.got_it))
        }
      },
      shape = AppShapeScale.extraLarge,
    )
  }
}

@Composable
private fun PermissionSectionCard(
  title: String,
  description: String,
  isGranted: Boolean,
  icon: app.gyrolet.mpvrx.ui.icons.AppIcon,
  onClick: () -> Unit,
) {
  val cardBgColor by animateColorAsState(
    targetValue = if (isGranted) {
      MaterialTheme.colorScheme.surfaceContainerLowest
    } else {
      MaterialTheme.colorScheme.surfaceContainer
    },
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
    modifier = Modifier
      .fillMaxWidth()
      .alpha(if (isGranted) 0.65f else 1f)
      .then(borderModifier)
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
        shape = RoundedCornerShape(12.dp),
        color = if (isGranted) {
          MaterialTheme.colorScheme.surfaceVariant
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
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (isGranted) {
              MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            } else {
              MaterialTheme.colorScheme.onSurface
            },
          )

          if (isGranted) {
            Spacer(modifier = Modifier.width(8.dp))
            PillBadge(text = stringResource(R.string.ui_permission_granted))
          }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
          text = description,
          style = MaterialTheme.typography.bodySmall,
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

@Composable
private fun PillBadge(text: String) {
  Box(
    modifier = Modifier
      .background(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
        shape = RoundedCornerShape(50),
      )
      .padding(horizontal = 8.dp, vertical = 2.dp),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
  }
}
