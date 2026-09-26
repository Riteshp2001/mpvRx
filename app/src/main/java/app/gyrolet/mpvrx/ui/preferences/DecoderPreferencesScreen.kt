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
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.preferences.MpvConfigOverride
import app.gyrolet.mpvrx.preferences.MpvConfigControlledFeatures
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.Debanding
import app.gyrolet.mpvrx.ui.player.MPVProfile
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.device.VulkanCapabilities
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState as composeCollectAsState
import androidx.compose.runtime.rememberCoroutineScope
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.ui.player.framegen.LosslessScalingHelper
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import org.koin.compose.koinInject

@Serializable
object DecoderPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val preferences = koinInject<DecoderPreferences>()
    val playerPreferences = koinInject<PlayerPreferences>()
    val advancedPreferences = koinInject<AdvancedPreferences>()
    val storedConfigOverrides by advancedPreferences.mpvConfOverrides.collectAsState()
    val configOwnedOptions =
      remember(storedConfigOverrides) { MpvConfigOverride.resolveOptionNames(storedConfigOverrides) }
    val profileConfigOwned = "profile" in configOwnedOptions
    val rendererBackendConfigOwned = setOf("gpu-api", "gpu-context").any(configOwnedOptions::contains)
    val decoderConfigOwned = MpvConfigControlledFeatures.HARDWARE_DECODER.any(configOwnedOptions::contains)
    val shadersConfigOwned = MpvConfigControlledFeatures.ANIME4K.any(configOwnedOptions::contains)
    val debandingConfigOwned =
      setOf("vf", "deband", "deband-iterations", "deband-threshold", "deband-range", "deband-grain")
        .any(configOwnedOptions::contains)
    val yuv420ConfigOwned = "vf" in configOwnedOptions
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val isDeviceVulkanSupported = remember { VulkanCapabilities.isDeviceSupported(context) }
    val isVulkanSupported = BuildConfig.MPV_SUPPORTS_VULKAN && isDeviceVulkanSupported
    var showGpuNextWarning by remember { mutableStateOf(false) }
    var anime4kExpanded by remember { mutableStateOf(false) }
    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.pref_decoder),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            if (LocalShowSettingsBackArrow.current) {
              IconButton(onClick = { backstack.popSafely() }) {
                Icon(
                  Icons.RoundedFilled.ArrowBack,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.secondary,
                )
              }
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        val (settingsListState, settingsHighlight) =
          rememberSettingsSearchList(DecoderPreferencesScreen, MaterialTheme.colorScheme.primary)
        LazyColumn(
          state = settingsListState,
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .then(settingsHighlight),
        ) {
          item {
            PreferenceSectionHeader(
              title = stringResource(R.string.pref_decoder),
              modifier = Modifier.settingsSearchTarget(R.string.pref_decoder),
            )
          }

          item {
            PreferenceCard {
              val profile by preferences.profile.collectAsState()
              val currentProfile = MPVProfile.fromValue(profile)
              ListPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_profile_title),
                value = currentProfile,
                onValueChange = { preferences.profile.set(it.value) },
                values = MPVProfile.entries,
                enabled = !profileConfigOwned,
                title = { Text(stringResource(R.string.pref_decoder_profile_title)) },
                summary = {
                  Text(
                    currentProfile.displayName,
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val tryHWDecoding by preferences.tryHWDecoding.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_try_hw_dec_title),
                value = tryHWDecoding,
                enabled = !decoderConfigOwned,
                onValueChange = {
                  preferences.tryHWDecoding.set(it)
                },
                title = { Text(stringResource(R.string.pref_decoder_try_hw_dec_title)) },
              )

              PreferenceDivider()

              val gpuNext by preferences.gpuNext.collectAsState()
              val useVulkan by preferences.useVulkan.collectAsState() // Added to check Vulkan state
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_gpu_next_title),
                value = gpuNext,
                onValueChange = { enabled ->
                  if (enabled && !gpuNext && !useVulkan) { // Only show warning if Vulkan is disabled
                    showGpuNextWarning = true
                  } else {
                    preferences.gpuNext.set(enabled)
                    if (enabled && !useVulkan) { // Only disable Anime4K if Vulkan is disabled
                      preferences.enableAnime4K.set(false)
                    }
                  }
                },
                title = { Text(stringResource(R.string.pref_decoder_gpu_next_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_decoder_gpu_next_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              if (showGpuNextWarning) {
                AlertDialog(
                  onDismissRequest = { showGpuNextWarning = false },
                  title = { Text(stringResource(R.string.pref_decoder_gpu_next_enable_title)) },
                  text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                      Text(stringResource(R.string.pref_decoder_gpu_next_warning))
                      Text(stringResource(R.string.pref_decoder_gpu_next_purple_screen_fix))

                      Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                      ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                          Text(
                            text = stringResource(R.string.pref_anime4k_incompatibility),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                          )
                          Text(
                            text = stringResource(R.string.pref_anime4k_gpu_next_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                          )
                        }
                      }
                    }
                  },
                  confirmButton = {
                    Button(onClick = {
                      preferences.gpuNext.set(true)
                      preferences.enableAnime4K.set(false) // Ensure Anime4K is disabled on confirmation
                      showGpuNextWarning = false
                    }) {
                      Text(stringResource(R.string.pref_decoder_gpu_next_enable_anyway))
                    }
                  },
                  dismissButton = {
                    TextButton(onClick = { showGpuNextWarning = false }) {
                      Text(stringResource(R.string.generic_cancel))
                    }
                  },
                )
              }

              PreferenceDivider()

              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_vulkan_experimental_title),
                value = useVulkan && isVulkanSupported,
                onValueChange = { enabled ->
                  if (isVulkanSupported) {
                    preferences.useVulkan.set(enabled)
                    // When Vulkan is disabled, ensure Anime4K and GPU Next are not both enabled
                    if (!enabled) {
                      val anime4kEnabled = preferences.enableAnime4K.get()
                      val gpuNextEnabled = preferences.gpuNext.get()
                      if (anime4kEnabled && gpuNextEnabled) {
                        // Disable GPU Next to keep Anime4K
                        preferences.gpuNext.set(false)
                      }
                    }
                  }
                },
                enabled = isVulkanSupported && !rendererBackendConfigOwned,
                title = { Text(stringResource(R.string.pref_decoder_vulkan_experimental_title)) },
                summary = {
                  Text(
                    stringResource(
                      when {
                        !BuildConfig.MPV_SUPPORTS_VULKAN && isDeviceVulkanSupported ->
                          R.string.pref_decoder_vulkan_excluded_supported_device
                        !BuildConfig.MPV_SUPPORTS_VULKAN ->
                          R.string.pref_decoder_vulkan_excluded_unsupported_device
                        isDeviceVulkanSupported -> R.string.pref_decoder_vulkan_summary
                        else -> R.string.pref_decoder_vulkan_not_supported
                      },
                    ),
                    color =
                      if (isVulkanSupported) {
                        MaterialTheme.colorScheme.outline
                      } else {
                        MaterialTheme.colorScheme.error
                      },
                  )
                },
              )

              PreferenceDivider()

              val debanding by preferences.debanding.collectAsState()
              ListPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_debanding_title),
                value = debanding,
                onValueChange = { preferences.debanding.set(it) },
                values = Debanding.entries,
                enabled = !debandingConfigOwned,
                title = { Text(stringResource(R.string.pref_decoder_debanding_title)) },
                summary = {
                  Text(
                    debanding.name,
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val useYUV420p by preferences.useYUV420P.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_yuv420p_title),
                value = useYUV420p,
                enabled = !yuv420ConfigOwned,
                onValueChange = {
                  preferences.useYUV420P.set(it)
                },
                title = { Text(stringResource(R.string.pref_decoder_yuv420p_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_decoder_yuv420p_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val enableAnime4K by preferences.enableAnime4K.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_anime4k_title),
                value = enableAnime4K,
                enabled = !shadersConfigOwned,
                onValueChange = { enabled ->
                  preferences.enableAnime4K.set(enabled)
                  if (enabled && !useVulkan) {
                    preferences.gpuNext.set(false)
                  }
                  if (enabled) {
                    anime4kExpanded = true
                  }
                },
                title = { Text(stringResource(R.string.pref_anime4k_title)) },
                summary = {
                  Column {
                    Text(
                      stringResource(R.string.pref_anime4k_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                      text =
                        androidx.compose.ui.res
                          .stringResource(app.gyrolet.mpvrx.R.string.pref_anime4k_link),
                      color = MaterialTheme.colorScheme.primary,
                      style = MaterialTheme.typography.bodySmall,
                      textDecoration = TextDecoration.Underline,
                      modifier =
                        Modifier.clickable {
                          val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bloc97/Anime4K"))
                          context.startActivity(intent)
                        },
                    )
                  }
                },
              )

              if (enableAnime4K && !shadersConfigOwned) {
                val rotationState by animateFloatAsState(
                  targetValue = if (anime4kExpanded) 180f else 0f,
                  label = "anime4k_chevron_rotation",
                )

                PreferenceDivider()

                Surface(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 8.dp, vertical = 4.dp),
                  shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                  color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                  Column {
                    Row(
                      modifier =
                        Modifier
                          .fillMaxWidth()
                          .clickable { anime4kExpanded = !anime4kExpanded }
                          .padding(vertical = 12.dp, horizontal = 16.dp),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Text(
                        text =
                          androidx.compose.ui.res.stringResource(
                            app.gyrolet.mpvrx.R.string.ui_anime4k_shaders_options,
                          ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                      )
                      Icon(
                        Icons.RoundedFilled.KeyboardArrowDown,
                        contentDescription = if (anime4kExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.rotate(rotationState),
                      )
                    }

                    AnimatedVisibility(visible = anime4kExpanded) {
                      Column {
                        val anime4kIn4k by preferences.anime4kIn4k.collectAsState()
                        SwitchPreference(
                          value = anime4kIn4k,
                          onValueChange = { preferences.anime4kIn4k.set(it) },
                          title = { Text(stringResource(R.string.pref_anime4k_in_4k_title)) },
                          summary = {
                            Text(
                              stringResource(R.string.pref_anime4k_in_4k_summary),
                              color = MaterialTheme.colorScheme.outline,
                            )
                          },
                        )

                        PreferenceDivider()

                        val anime4kQuality by preferences.anime4kQuality.collectAsState()
                        ListPreference(
                          value = anime4kQuality,
                          onValueChange = { preferences.anime4kQuality.set(it) },
                          values = Anime4KManager.Quality.entries,
                          valueToText = { AnnotatedString(context.getString(it.titleRes)) },
                          title = { Text(stringResource(R.string.pref_anime4k_quality_title)) },
                          summary = {
                            Text(
                              stringResource(anime4kQuality.titleRes),
                              color = MaterialTheme.colorScheme.outline,
                            )
                          },
                        )

                        PreferenceDivider()

                        val anime4kDarken by preferences.anime4kDarken.collectAsState()
                        SwitchPreference(
                          value = anime4kDarken,
                          onValueChange = { preferences.anime4kDarken.set(it) },
                          title = { Text(stringResource(R.string.pref_anime4k_darken_title)) },
                          summary = {
                            Text(
                              stringResource(R.string.pref_anime4k_darken_summary),
                              color = MaterialTheme.colorScheme.outline,
                            )
                          },
                        )

                        PreferenceDivider()

                        val anime4kThin by preferences.anime4kThin.collectAsState()
                        SwitchPreference(
                          value = anime4kThin,
                          onValueChange = { preferences.anime4kThin.set(it) },
                          title = { Text(stringResource(R.string.pref_anime4k_thin_title)) },
                          summary = {
                            Text(
                              stringResource(R.string.pref_anime4k_thin_summary),
                              color = MaterialTheme.colorScheme.outline,
                            )
                          },
                        )

                        PreferenceDivider()

                        val anime4kDeblur by preferences.anime4kDeblur.collectAsState()
                        SwitchPreference(
                          value = anime4kDeblur,
                          onValueChange = { preferences.anime4kDeblur.set(it) },
                          title = { Text(stringResource(R.string.pref_anime4k_deblur_title)) },
                          summary = {
                            Text(
                              stringResource(R.string.pref_anime4k_deblur_summary),
                              color = MaterialTheme.colorScheme.outline,
                            )
                          },
                        )
                      }
                    }
                  }
                }
              }
            }
          }

          // ── Frame Generation (LSFG) ──────────────────────────────────
          item {
            PreferenceSectionHeader(
              title = stringResource(R.string.frame_gen_sheet_title),
            )
          }

          item {
            val installed by LosslessScalingHelper.installed.composeCollectAsState()
            val statusText by LosslessScalingHelper.statusText.composeCollectAsState()
            val isSupported = isDeviceVulkanSupported
            val scope = rememberCoroutineScope()
            var installing by remember { mutableStateOf(false) }
            var showRemoveDialog by remember { mutableStateOf(false) }
            var showResultDialog by remember { mutableStateOf<String?>(null) }

            val dllPicker = rememberLauncherForActivityResult(
              ActivityResultContracts.OpenDocument(),
            ) { uri ->
              if (uri == null) return@rememberLauncherForActivityResult
              installing = true
              scope.launch {
                val result = LosslessScalingHelper.install(context.contentResolver, uri)
                installing = false
                showResultDialog = when (result) {
                  LosslessScalingHelper.RESULT_OK -> context.getString(R.string.frame_gen_install_success)
                  LosslessScalingHelper.RESULT_NOT_PE -> "Selected file is not a valid PE / DLL library."
                  LosslessScalingHelper.RESULT_MISSING_SHADERS -> "Missing required LSFG compute shaders in DLL."
                  LosslessScalingHelper.RESULT_TRANSLATION_FAILED -> "Failed to translate DXBC to SPIR-V shaders."
                  else -> context.getString(R.string.frame_gen_install_failed)
                }
              }
            }

            PreferenceCard {
              // 1. Enable Toggle
              val isFrameGenEnabled by playerPreferences.isFrameGenEnabled.collectAsState()
              SwitchPreference(
                value = isFrameGenEnabled && installed && isSupported,
                enabled = isSupported && installed && !installing,
                onValueChange = { playerPreferences.isFrameGenEnabled.set(it) },
                title = { Text(stringResource(R.string.btn_label_frame_generation)) },
                summary = {
                  Text(
                    when {
                      !isSupported -> stringResource(R.string.frame_gen_not_supported)
                      !installed -> stringResource(R.string.frame_gen_library_not_installed)
                      else -> stringResource(R.string.frame_gen_description)
                    },
                    color = if (!isSupported || !installed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // 2. Multiplier (2x, 3x, 4x)
              val multiplier by playerPreferences.frameGenMultiplier.collectAsState()
              val multipliers = listOf(2, 3, 4)
              ListPreference(
                value = multiplier,
                onValueChange = { playerPreferences.frameGenMultiplier.set(it) },
                values = multipliers,
                valueToText = { AnnotatedString("${it}×") },
                enabled = installed && isSupported,
                title = { Text(stringResource(R.string.frame_gen_multiplier_label)) },
                summary = {
                  Text(
                    "${multiplier}×",
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              // 3. Install / Replace Library
              Preference(
                title = {
                  Text(
                    if (installing) stringResource(R.string.frame_gen_installing)
                    else if (installed) stringResource(R.string.frame_gen_replace_library)
                    else stringResource(R.string.frame_gen_install_library)
                  )
                },
                summary = {
                  Text(
                    if (statusText.isNotEmpty()) statusText
                    else if (installed) stringResource(R.string.frame_gen_library_installed)
                    else stringResource(R.string.frame_gen_install_description),
                    color = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                  )
                },
                enabled = !installing,
                onClick = { dllPicker.launch(arrayOf("*/*")) },
              )

              // 4. Remove Library (only if installed)
              if (installed) {
                PreferenceDivider()
                Preference(
                  title = {
                    Text(
                      stringResource(R.string.frame_gen_remove_library),
                      color = MaterialTheme.colorScheme.error,
                    )
                  },
                  summary = {
                    Text(
                      "Remove Lossless.dll and clear cached shaders",
                      color = MaterialTheme.colorScheme.outline,
                    )
                  },
                  enabled = !installing,
                  onClick = { showRemoveDialog = true },
                )
              }
            }

            if (showRemoveDialog) {
              AlertDialog(
                onDismissRequest = { showRemoveDialog = false },
                title = { Text(stringResource(R.string.frame_gen_remove_library)) },
                text = { Text(stringResource(R.string.frame_gen_remove_confirmation)) },
                confirmButton = {
                  TextButton(
                    onClick = {
                      showRemoveDialog = false
                      scope.launch {
                        LosslessScalingHelper.remove()
                        playerPreferences.isFrameGenEnabled.set(false)
                      }
                    },
                  ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                  }
                },
                dismissButton = {
                  TextButton(onClick = { showRemoveDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                  }
                },
              )
            }

            if (showResultDialog != null) {
              AlertDialog(
                onDismissRequest = { showResultDialog = null },
                title = { Text(stringResource(R.string.frame_gen_sheet_title)) },
                text = { Text(showResultDialog!!) },
                confirmButton = {
                  TextButton(onClick = { showResultDialog = null }) {
                    Text(stringResource(android.R.string.ok))
                  }
                },
              )
            }
          }
        }
      }
    }
  }
}
