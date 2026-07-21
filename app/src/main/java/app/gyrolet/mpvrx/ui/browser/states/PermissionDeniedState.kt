@file:Suppress("DEPRECATION")

package app.gyrolet.mpvrx.ui.browser.states

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.theme.AppShapeScale

@SuppressLint("UseKtx")
@Composable
fun PermissionDeniedState(
  onRequestPermission: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var showExplanationDialog by remember { mutableStateOf(false) }

  // Determine if we're using MANAGE_EXTERNAL_STORAGE or scoped storage permissions
  val isPlayStoreBuild = remember { BuildConfig.SCOPED_STORAGE_ONLY }

  // Animated scale for the icon
  val infiniteTransition = rememberInfiniteTransition(label = "permission_icon")
  val scale by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 1.1f,
    animationSpec =
      infiniteRepeatable(
        animation = tween(2000, easing = FastOutSlowInEasing),
        repeatMode = RepeatMode.Reverse,
      ),
    label = "icon_scale",
  )

  Box(
    modifier = modifier
      .fillMaxSize()
      .padding(top = 40.dp, bottom = 100.dp) // Added top padding for icon, reduced bottom padding
  ) {
    Surface(
      modifier = Modifier.fillMaxSize(),
      color = MaterialTheme.colorScheme.background,
    ) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
      ) {
        Column(
          modifier =
            Modifier
              .widthIn(max = 560.dp)
              .fillMaxWidth()
              .fillMaxHeight()
              .padding(32.dp) // Increased padding to prevent icon cutoff
              .verticalScroll(rememberScrollState()),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {

        // Animated Icon with Surface
        Surface(
          modifier =
            Modifier
              .size(152.dp)
              .padding(16.dp)
              .scale(scale),
          shape = AppShapeScale.extraLargeIncreased,
          color = MaterialTheme.colorScheme.errorContainer,
          tonalElevation = 3.dp,
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.Warning,
            contentDescription = null,
            modifier =
              Modifier
                .padding(28.dp)
                .fillMaxSize(),
            tint = MaterialTheme.colorScheme.onErrorContainer,
          )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Title
        Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_storage_access_required),
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
          color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Description Card
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors =
            CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
          shape = AppShapeScale.largeIncreased,
        ) {
          Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = if (isPlayStoreBuild) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  "mpvRx requires \"Photos and videos\" permission to access and play your video files stored on your device."
                } else {
                  "mpvRx requires \"Storage\" permission to access and play your media files stored on your device."
                }
              } else {
                "mpvRx requires \"All file access\" permission to discover media and subtitles on your device due to a change in security policy in Android 11 and later versions."
              },
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurface,
              textAlign = TextAlign.Center,
            )
          }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Allow Access Button
        FilledTonalButton(
          onClick = {
            if (isPlayStoreBuild) {
              // Play Store build: Use regular permission request
              onRequestPermission()
            } else {
              // Standard build: Open All Files Access settings for Android 11+
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                  val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                  intent.data = Uri.parse("package:${context.packageName}")
                  context.startActivity(intent)
                } catch (_: Exception) {
                  // Fallback to general All Files Access settings
                  val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                  context.startActivity(intent)
                }
              } else {
                // For older Android versions, use the regular permission request
                onRequestPermission()
              }
            }
          },
          modifier =
            Modifier
              .fillMaxWidth()
              .height(56.dp),
          shape = AppShapeScale.large,
        ) {
          Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_allow_access),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Why do I see this? link
        TextButton(
          onClick = { showExplanationDialog = true },
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.Info,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_why_do_i_see_this),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
          )
        }

        Spacer(modifier = Modifier.weight(1f))
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
        Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_why_this_permission_is_needed),
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
        )
      },
      text = {
        Column(
          modifier =
            Modifier
              .heightIn(max = 400.dp)
              .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (isPlayStoreBuild) {
            // Play Store build explanation
            Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_mpvrx_needs_access_to_your_video_files_to_provide_its_core_funct),
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

            Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_the_permission_is_used_exclusively_for),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              fontWeight = FontWeight.Medium,
            )

            Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_discovering_and_displaying_your_video_files_n_playing_media_cont),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            // Standard build explanation
            Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_mpvrx_has_always_required_storage_access_permission_as_it_s_esse),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_however_due_to_a_change_in_security_policy_apps_built_for_androi),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_please_know_that_this_permission_is_only_used_for_the_auto_disco),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_mpvrx_is_an_open_source_project_you_can_review_the_source_code_a),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          // Clickable GitHub link
          val annotatedString =
            buildAnnotatedString {
              pushStringAnnotation(
                tag = "URL",
                annotation = githubUrl,
              )
              withStyle(
                style =
                  SpanStyle(
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
                .getStringAnnotations(
                  tag = "URL",
                  start = offset,
                  end = offset,
                ).firstOrNull()
                ?.let {
                  uriHandler.openUri(it.item)
                }
            },
          )

          Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_be_rest_assured_your_privacy_is_our_utmost_priority_and_we_neith),
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




