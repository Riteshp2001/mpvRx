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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import app.gyrolet.mpvrx.domain.motion.MotionInterpolation
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.preferences.DolbyVisionProfile7
import app.gyrolet.mpvrx.preferences.MpvConfigControlledFeatures
import app.gyrolet.mpvrx.preferences.MpvConfigOverride
import app.gyrolet.mpvrx.preferences.VulkanFrameImport
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.Debanding
import app.gyrolet.mpvrx.ui.player.DolbyVisionDirect
import app.gyrolet.mpvrx.ui.player.FongMiMpv
import app.gyrolet.mpvrx.ui.player.HwPlusGuard
import app.gyrolet.mpvrx.ui.player.MPVProfile
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.RendererBackendPolicy
import app.gyrolet.mpvrx.ui.player.shouldUseTextureRendering
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.device.VulkanCapabilities
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton
import app.gyrolet.mpvrx.ui.preferences.components.AppListPreference as ListPreference

@Serializable
object DecoderPreferencesScreen : ScrollableSettingsScreen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val preferences = koinInject<DecoderPreferences>()
    val embeddedInPlayer = LocalPlayerSettingsPanel.current
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
    val dolbyVisionConfigOwned = "android-dolby-vision-output" in configOwnedOptions
    val doviProfile7ConfigOwned = "demuxer-dovi-profile7" in configOwnedOptions
    val vulkanFrameImportConfigOwned = "android-vulkan-aimagereader-backend" in configOwnedOptions
    // Its decoder and output are fixed, so mpv.conf owning either keeps it off.
    val mediaCodecEmbedConfigOwned = "vo" in configOwnedOptions || decoderConfigOwned
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val isDeviceVulkanSupported = remember { VulkanCapabilities.isDeviceSupported(context) }
    val isVulkanSupported = BuildConfig.MPV_SUPPORTS_VULKAN && isDeviceVulkanSupported
    var showGpuNextWarning by remember { mutableStateOf(false) }
    var anime4kExpanded by remember { mutableStateOf(false) }
    Scaffold(
      contentWindowInsets = settingsContentWindowInsets(),
      topBar = {
        SettingsTopAppBar(
          title = stringResource(R.string.pref_decoder),
          onBack = { backstack.popSafely() },
        )
      },
    ) { padding ->
      ProvideExpressivePreferenceLocals {
        val (settingsListState, settingsHighlight) =
          rememberSettingsSearchList(DecoderPreferencesScreen, MaterialTheme.colorScheme.primary)
        LazyColumn(
          state = settingsListState,
          contentPadding = settingsContentPadding(),
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .then(settingsHighlight),
        ) {
          if (!embeddedInPlayer) {
            item {
              PreferenceSectionHeader(
                position = PreferenceSectionPosition.Single,
                title = stringResource(R.string.pref_decoder),
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder),
              )
            }
          }

          item {
            PreferenceCard {
              val profile by preferences.profile.collectAsState()
              val currentProfile = MPVProfile.fromValue(profile)
              ExpressivePreferenceItem {
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
              }

              PreferenceDivider()

              val tryHWDecoding by preferences.tryHWDecoding.collectAsState()
              val mediaCodecEmbed by preferences.mediaCodecEmbed.collectAsState()
              val forcedOnByMediaCodecEmbed: @Composable () -> Unit = {
                MediaCodecEmbedNote(R.string.pref_decoder_mediacodec_embed_forced_on)
              }
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_try_hw_dec_title),
                value = tryHWDecoding || mediaCodecEmbed,
                enabled = !decoderConfigOwned && !mediaCodecEmbed,
                onValueChange = {
                  preferences.tryHWDecoding.set(it)
                  if (embeddedInPlayer) {
                    val usesVulkan =
                      PlaybackSession.getPropertyString("gpu-api") == "vulkan" || preferences.useVulkan.get()
                    PlaybackSession.setPropertyString(
                      "hwdec",
                      RendererBackendPolicy.preferredHwdecMode(
                        hardwareDecodingEnabled = it,
                        usesVulkan = usesVulkan,
                        buildSupportsMediaCodecVulkan = BuildConfig.MPV_SUPPORTS_MEDIACODEC_VULKAN,
                      ),
                    )
                  }
                },
                title = { Text(stringResource(R.string.pref_decoder_try_hw_dec_title)) },
                summary = forcedOnByMediaCodecEmbed.takeIf { mediaCodecEmbed },
              )

              PreferenceDivider()

              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_mediacodec_embed_title),
                value = mediaCodecEmbed,
                enabled = !mediaCodecEmbedConfigOwned,
                onValueChange = { preferences.mediaCodecEmbed.set(it) },
                title = { Text(stringResource(R.string.pref_decoder_mediacodec_embed_title)) },
                summary = {
                  Column {
                    Text(
                      stringResource(R.string.pref_decoder_mediacodec_embed_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                    // Only FongMi's mpv draws subtitles and OSD beside this output.
                    if (!DolbyVisionDirect.isBuildSupported) {
                      Text(
                        stringResource(R.string.pref_decoder_mediacodec_embed_no_subtitles),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                      )
                    }
                  }
                },
              )

              PreferenceDivider()

              val gpuNext by preferences.gpuNext.collectAsState()
              val useVulkan by preferences.useVulkan.collectAsState() // Added to check Vulkan state
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_gpu_next_title),
                value = gpuNext && !mediaCodecEmbed,
                enabled = !mediaCodecEmbed,
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
                  if (mediaCodecEmbed) {
                    MediaCodecEmbedNote(R.string.pref_decoder_mediacodec_embed_blocks)
                  } else {
                    Text(
                      stringResource(R.string.pref_decoder_gpu_next_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  }
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
                value = useVulkan && isVulkanSupported && !mediaCodecEmbed,
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
                enabled = isVulkanSupported && !rendererBackendConfigOwned && !mediaCodecEmbed,
                title = { Text(stringResource(R.string.pref_decoder_vulkan_experimental_title)) },
                summary = {
                  Text(
                    when {
                      mediaCodecEmbed && isVulkanSupported ->
                        stringResource(R.string.pref_decoder_mediacodec_embed_blocks)
                      !BuildConfig.MPV_SUPPORTS_VULKAN && isDeviceVulkanSupported ->
                        stringResource(R.string.pref_decoder_vulkan_excluded_supported_device)
                      !BuildConfig.MPV_SUPPORTS_VULKAN ->
                        stringResource(R.string.pref_decoder_vulkan_excluded_unsupported_device)
                      isDeviceVulkanSupported -> stringResource(R.string.pref_decoder_vulkan_summary)
                      else ->
                        stringResource(
                          R.string.pref_decoder_vulkan_not_supported,
                          VulkanCapabilities.requiredAndroidRelease,
                          VulkanCapabilities.requiredVulkanVersion,
                        )
                    },
                    color =
                      if (isVulkanSupported) {
                        MaterialTheme.colorScheme.outline
                      } else {
                        MaterialTheme.colorScheme.error
                      },
                  )
                },
              )

              if (FongMiMpv.isBuildSupported && isVulkanSupported) {
                PreferenceDivider()
                // How MediaCodec frames reach Vulkan; a forced mode helps when one path misrenders.
                val vulkanFrameImport by preferences.vulkanFrameImport.collectAsState()
                ExpressivePreferenceItem {
                  ListPreference(
                    modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_vulkan_import_title),
                    value = vulkanFrameImport,
                    onValueChange = {
                      preferences.vulkanFrameImport.set(it)
                      // The next hardware decoder picks it up.
                      if (embeddedInPlayer) {
                        PlaybackSession.setPropertyString("android-vulkan-aimagereader-backend", it.mpvValue)
                      }
                    },
                    values = VulkanFrameImport.entries,
                    valueToText = { AnnotatedString(stringResource(it.titleRes)) },
                    enabled = useVulkan && !vulkanFrameImportConfigOwned && !mediaCodecEmbed,
                    title = { Text(stringResource(R.string.pref_decoder_vulkan_import_title)) },
                    summary = {
                      Text(
                        stringResource(vulkanFrameImport.titleRes),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                  )
                }
              }

              PreferenceDivider()

              val debanding by preferences.debanding.collectAsState()
              ExpressivePreferenceItem {
                ListPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_debanding_title),
                  value = debanding,
                  onValueChange = {
                    val select: () -> Unit = {
                      preferences.debanding.set(it)
                      if (embeddedInPlayer) PlaybackSession.applyDebanding(it)
                    }
                    val blocked =
                      embeddedInPlayer &&
                        it == Debanding.CPU &&
                        HwPlusGuard.blocks(context, R.string.player_hwplus_cpu_deband, select)
                    if (!blocked) select()
                  },
                  values = Debanding.entries,
                  enabled = !debandingConfigOwned && !mediaCodecEmbed,
                  title = { Text(stringResource(R.string.pref_decoder_debanding_title)) },
                  summary = {
                    if (mediaCodecEmbed) {
                      MediaCodecEmbedNote(R.string.pref_decoder_mediacodec_embed_blocks)
                    } else {
                      Text(
                        debanding.name,
                        color = MaterialTheme.colorScheme.outline,
                      )
                    }
                  },
                )
              }

              PreferenceDivider()

              val useYUV420p by preferences.useYUV420P.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_yuv420p_title),
                value = useYUV420p && !mediaCodecEmbed,
                enabled = !yuv420ConfigOwned && !mediaCodecEmbed,
                onValueChange = {
                  preferences.useYUV420P.set(it)
                },
                title = { Text(stringResource(R.string.pref_decoder_yuv420p_title)) },
                summary = {
                  if (mediaCodecEmbed) {
                    MediaCodecEmbedNote(R.string.pref_decoder_mediacodec_embed_blocks)
                  } else {
                    Text(
                      stringResource(R.string.pref_decoder_yuv420p_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                  }
                },
              )

              if (DolbyVisionDirect.isBuildSupported) {
                PreferenceDivider()
                val dolbyVisionDirect by preferences.dolbyVisionDirect.collectAsState()
                val appearancePreferences = koinInject<AppearancePreferences>()
                val liquidGlass by appearancePreferences.liquidGlassEnabled.collectAsState()
                val liquidGlassInHdr by appearancePreferences.liquidGlassInHdr.collectAsState()
                val hdrOutput by preferences.hdrScreenOutput.collectAsState()
                // The subtitle overlay needs the native SurfaceView renderer.
                val textureRendering =
                  shouldUseTextureRendering(liquidGlass, hdrOutput, liquidGlassInHdr, mediaCodecEmbed)
                SwitchPreference(
                  modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_dolby_vision_direct_title),
                  // MediaCodec embed already sends every video, Dolby Vision included, straight to the screen.
                  value = dolbyVisionDirect || mediaCodecEmbed,
                  enabled = !dolbyVisionConfigOwned && !mediaCodecEmbed,
                  onValueChange = { preferences.dolbyVisionDirect.set(it) },
                  title = { Text(stringResource(R.string.pref_decoder_dolby_vision_direct_title)) },
                  summary = {
                    Column {
                      if (mediaCodecEmbed) {
                        MediaCodecEmbedNote(R.string.pref_decoder_mediacodec_embed_forced_on)
                      } else {
                        Text(
                          stringResource(R.string.pref_decoder_dolby_vision_direct_summary),
                          color = MaterialTheme.colorScheme.outline,
                        )
                      }
                      if (dolbyVisionDirect && textureRendering) {
                        Text(
                          stringResource(R.string.pref_decoder_dolby_vision_direct_needs_surface),
                          color = MaterialTheme.colorScheme.error,
                          style = MaterialTheme.typography.bodySmall,
                        )
                      }
                    }
                  },
                )

                PreferenceDivider()
                val doviProfile7 by preferences.doviProfile7.collectAsState()
                ExpressivePreferenceItem {
                  ListPreference(
                    modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_dovi_p7_title),
                    value = doviProfile7,
                    onValueChange = {
                      preferences.doviProfile7.set(it)
                      // The demuxer reads it when the next video opens.
                      PlaybackSession.applyDolbyVisionProfile7()
                    },
                    values = DolbyVisionProfile7.entries,
                    valueToText = { AnnotatedString(stringResource(it.titleRes)) },
                    enabled = !doviProfile7ConfigOwned,
                    title = { Text(stringResource(R.string.pref_decoder_dovi_p7_title)) },
                    summary = {
                      Text(
                        stringResource(doviProfile7.titleRes),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    },
                  )
                }
              }

              PreferenceDivider()

              val enableAnime4K by preferences.enableAnime4K.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_anime4k_title),
                value = enableAnime4K && !mediaCodecEmbed,
                enabled = !shadersConfigOwned && !mediaCodecEmbed,
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
                  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (mediaCodecEmbed) {
                      MediaCodecEmbedNote(R.string.pref_decoder_mediacodec_embed_blocks)
                    } else {
                      Text(
                        stringResource(R.string.pref_anime4k_summary),
                        color = MaterialTheme.colorScheme.outline,
                      )
                    }
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

              if (!embeddedInPlayer && enableAnime4K && !shadersConfigOwned && !mediaCodecEmbed) {
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
                  shape =
                    androidx.compose.foundation.shape
                      .RoundedCornerShape(12.dp),
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
                          modifier = Modifier.settingsSearchTarget(R.string.pref_anime4k_in_4k_title),
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
                        ExpressivePreferenceItem {
                          ListPreference(
                            modifier = Modifier.settingsSearchTarget(R.string.pref_anime4k_quality_title),
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
                        }

                        PreferenceDivider()

                        val anime4kDarken by preferences.anime4kDarken.collectAsState()
                        SwitchPreference(
                          modifier = Modifier.settingsSearchTarget(R.string.pref_anime4k_darken_title),
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
                          modifier = Modifier.settingsSearchTarget(R.string.pref_anime4k_thin_title),
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
                          modifier = Modifier.settingsSearchTarget(R.string.pref_anime4k_deblur_title),
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

          if (MotionInterpolation.isBuildSupported) {
            if (!embeddedInPlayer) {
              item {
                PreferenceSectionHeader(
                  position = PreferenceSectionPosition.Single,
                  title = stringResource(R.string.motion_title),
                )
              }
            }

            item {
              MotionInterpolationPreferences(
                preferences = preferences,
                rendererConfigOwned = rendererBackendConfigOwned,
              )
            }
          }
        }
      }
    }
  }
}

/** Why a decoder setting is fixed while MediaCodec embed is on. */
@Composable
private fun MediaCodecEmbedNote(textRes: Int) {
  Text(
    stringResource(textRes),
    color = MaterialTheme.colorScheme.outline,
  )
}
