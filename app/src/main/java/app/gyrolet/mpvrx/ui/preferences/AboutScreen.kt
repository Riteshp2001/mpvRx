package app.gyrolet.mpvrx.ui.preferences

import androidx.annotation.StringRes

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.crash.CrashActivity.Companion.collectDeviceInfo
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.clipboard.SafeClipboard
import app.gyrolet.mpvrx.utils.update.UpdateViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

@Serializable
object AboutScreen : Screen {
  @Suppress("DEPRECATION")
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    val packageManager: PackageManager = context.packageManager
    val packageInfo = packageManager.getPackageInfo(context.packageName, 0)
    val versionName = packageInfo.versionName?.substringBefore('-') ?: packageInfo.versionName ?: BuildConfig.VERSION_NAME
    val buildType = BuildConfig.BUILD_TYPE
    val githubRepoUrl = stringResource(R.string.github_repo_url)

    // Conditionally initialize update feature based on build config
    val updateViewModel: UpdateViewModel? = if (BuildConfig.ENABLE_UPDATE_FEATURE) {
      viewModel(context as androidx.activity.ComponentActivity)
    } else {
      null
    }
    val updateState by (updateViewModel?.updateState ?: MutableStateFlow(UpdateViewModel.UpdateState.Idle)).collectAsState()

    // Show toast when no update is available after manual check (only if update feature is enabled)
    LaunchedEffect(updateState) {
        if (BuildConfig.ENABLE_UPDATE_FEATURE && updateViewModel != null && updateState is UpdateViewModel.UpdateState.NoUpdate) {
            Toast.makeText(context, context.getString(app.gyrolet.mpvrx.R.string.ui_already_using_latest_version), Toast.LENGTH_SHORT).show()
            updateViewModel.dismissNoUpdate()
        }
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { 
            Text(
              text = stringResource(id = R.string.pref_about_title),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            ) 
          },
          navigationIcon = {
            if (LocalShowSettingsBackArrow.current) {
              IconButton(onClick = { backstack.popSafely() }) {
                Icon(
                  imageVector = Icons.RoundedFilled.ArrowBack,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.secondary,
                )
              }
            }
          },
        )
      },
    ) { paddingValues ->
      val cs = MaterialTheme.colorScheme
      val colorPrimary = cs.primaryContainer
      val colorTertiary = cs.tertiaryContainer
      val transition = rememberInfiniteTransition()
      val fraction by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
          infiniteRepeatable(
            animation = tween(durationMillis = 5000),
            repeatMode = RepeatMode.Reverse,
          ),
      )
      val cornerRadius = 28.dp
      
      Column(
        modifier =
          Modifier
            .padding(paddingValues)
            .verticalScroll(rememberScrollState()),
      ) {
        PreferenceCard {
          Box(
            modifier =
              Modifier
                .drawWithCache {
                  val cx = size.width - size.width * fraction
                  val cy = size.height * fraction

                  val gradient =
                    Brush.radialGradient(
                      colors = listOf(colorPrimary, colorTertiary),
                      center = Offset(cx, cy),
                      radius = 800f,
                    )

                  onDrawBehind {
                    drawRoundRect(
                      brush = gradient,
                      cornerRadius =
                        CornerRadius(
                          cornerRadius.toPx(),
                          cornerRadius.toPx(),
                        ),
                    )
                  }
                }
                .padding(16.dp),
          ) {
            Column {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(64.dp)) {
                  AndroidView(
                    modifier = Modifier.matchParentSize(),
                    factory = { ctx ->
                      ImageView(ctx).apply {
                        setImageResource(R.mipmap.ic_launcher)
                      }
                    },
                  )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                  Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = cs.onPrimaryContainer,
                  )
                  Spacer(Modifier.height(4.dp))
                  Text(
                    text = "v$versionName $buildType",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onPrimaryContainer.copy(alpha = 0.85f),
                  )
                  Spacer(Modifier.height(8.dp))
                  Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = cs.primary.copy(alpha = 0.16f),
                  ) {
                    Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_by_ritesh_pandit),
                      modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = FontWeight.SemiBold,
                      color = cs.onPrimaryContainer,
                    )
                  }
                }
              }

              Spacer(modifier = Modifier.height(20.dp))

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
              ) {
                val btnContainer = cs.primary
                val btnContent = cs.onPrimary
                Button(
                  onClick = { backstack.add(LibrariesScreen) },
                  modifier =
                    Modifier
                      .weight(1f)
                      .height(56.dp),
                  shape = RoundedCornerShape(16.dp),
                  colors =
                    ButtonDefaults.buttonColors(
                      containerColor = btnContainer,
                      contentColor = btnContent,
                    ),
                ) {
                  Text(
                    text = stringResource(id = R.string.pref_about_oss_libraries),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                  )
                }

                Button(
                  onClick = {
                    context.startActivity(
                      Intent(
                        Intent.ACTION_VIEW,
                        githubRepoUrl.toUri(),
                      ),
                    )
                  },
                  modifier =
                    Modifier
                      .weight(1f)
                      .height(56.dp),
                  shape = RoundedCornerShape(16.dp),
                  colors =
                    ButtonDefaults.buttonColors(
                      containerColor = btnContainer,
                      contentColor = btnContent,
                    ),
                ) {
                  Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_github),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                  )
                }
              }

              Spacer(modifier = Modifier.height(20.dp))

              Column(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clickable {
                      SafeClipboard.copyPlainText(context, "mpvrx_device_info", collectDeviceInfo())
                    },
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.padding(bottom = 8.dp),
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Info,
                    contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_device_info),
                    modifier = Modifier.size(20.dp),
                    tint = cs.onPrimaryContainer,
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_device_info),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onPrimaryContainer,
                  )
                }
                Text(
                  text = collectDeviceInfo(),
                  style = MaterialTheme.typography.bodySmall,
                  color = cs.onPrimaryContainer.copy(alpha = 0.85f),
                )
              }
            }
          }
        }

        Spacer(Modifier.height(8.dp))

        // Support / Donation Section
        PreferenceSectionHeader(title = stringResource(R.string.pref_section_support))
        PreferenceCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.RoundedFilled.MonetizationOn,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = cs.error,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_buy_me_a_coffee),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = cs.onSurface,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_if_you_enjoy_mpvrx_consider_supporting_its_development_every_bit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = cs.primaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                SafeClipboard.copyPlainText(
                                    context = context,
                                    label = "mpvrx_upi_id",
                                    text = "panditritesh2001@okhdfcbank",
                                    showToast = false,
                                )
                                Toast.makeText(context, context.getString(app.gyrolet.mpvrx.R.string.ui_upi_id_copied), Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_upi_id),
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.outline,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_panditritesh2001_okhdfcbank),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = cs.onSurface,
                            )
                        }
                        Icon(
                            imageVector = Icons.RoundedFilled.ContentCopy,
                            contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_copy_upi_id),
                            modifier = Modifier.size(20.dp),
                            tint = cs.primary,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        try {
                            val upiIntent = Intent(
                                Intent.ACTION_VIEW,
                                "upi://pay?pa=panditritesh2001@okhdfcbank&pn=Ritesh%20Pandit&cu=INR".toUri(),
                            )
                            context.startActivity(upiIntent)
                        } catch (_: Exception) {
                            Toast.makeText(context, context.getString(app.gyrolet.mpvrx.R.string.ui_no_upi_app_found), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cs.error,
                        contentColor = cs.onError,
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                ) {
                    Icon(Icons.RoundedFilled.MonetizationOn, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_send_love), fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Updates Section (only show if update feature is enabled)
        if (BuildConfig.ENABLE_UPDATE_FEATURE && updateViewModel != null) {
          PreferenceSectionHeader(title = stringResource(R.string.pref_section_updates))
          PreferenceCard {
                val isAutoUpdateEnabled by updateViewModel.isAutoUpdateEnabled.collectAsState()
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { updateViewModel.toggleAutoUpdate(!isAutoUpdateEnabled) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_auto_check_for_updates),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = cs.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_check_on_startup),
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.outline
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = isAutoUpdateEnabled,
                            onCheckedChange = { updateViewModel.toggleAutoUpdate(it) }
                        )
                    }
                    
                    PreferenceDivider()
                    
                    Column(modifier = Modifier.padding(16.dp)) {
                        Button(
                            onClick = { updateViewModel.checkForUpdate(manual = true) },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = cs.secondaryContainer, 
                                contentColor = cs.onSecondaryContainer
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                        ) {
                             Icon(Icons.RoundedFilled.Update, null, modifier = Modifier.size(18.dp))
                             Spacer(Modifier.width(8.dp))
                             Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_check_for_updates_now), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
          }
          
          Spacer(Modifier.height(8.dp))
        }

        // System Stats Section
        PreferenceSectionHeader(title = stringResource(R.string.pref_section_system))

        val systemStats = remember { collectSystemStats(context) }
        PreferenceCard {
          systemStats.forEachIndexed { index, (label, value) ->
            SystemStatRow(label = label, value = value)
            if (index < systemStats.lastIndex) PreferenceDivider()
          }
        }

        Spacer(Modifier.height(12.dp))
      }
    }
  }
}

@Composable
private fun SystemStatRow(label: String, value: String) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f).padding(end = 8.dp),
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1.4f),
    )
  }
}

private fun collectSystemStats(context: Context): List<Pair<String, String>> {
  val pm = context.packageManager
  val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

  // RAM
  val memInfo = ActivityManager.MemoryInfo()
  am.getMemoryInfo(memInfo)
  val totalRamMb = memInfo.totalMem / (1024 * 1024)
  val ramStr = if (totalRamMb >= 1024) "${"%.1f".format(totalRamMb / 1024f)} GB" else "$totalRamMb MB"

  // GLES version
  val configInfo = am.deviceConfigurationInfo
  val glesVersion = if (configInfo.reqGlEsVersion != android.content.pm.ConfigurationInfo.GL_ES_VERSION_UNDEFINED) {
    configInfo.glEsVersion
  } else "Unknown"

  // Vulkan
  val vulkanStr = when {
    pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL, 1) -> {
      // Try to get Vulkan version from system features
      val features = pm.systemAvailableFeatures
      val vulkanVersionFeature = features.firstOrNull {
        it.name?.startsWith("android.hardware.vulkan.version") == true
      }
      if (vulkanVersionFeature != null && Build.VERSION.SDK_INT >= 26) {
        val ver = vulkanVersionFeature.version
        val major = (ver shr 22) and 0x3FF
        val minor = (ver shr 12) and 0x3FF
        "Vulkan $major.$minor (Level 1)"
      } else "Vulkan 1.1+ (Level 1)"
    }
    pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL, 0) -> "Vulkan 1.0 (Level 0)"
    pm.hasSystemFeature("android.hardware.vulkan.compute") -> "Vulkan (compute)"
    else -> "Not supported"
  }

  // CPU ABIs
  val abis = Build.SUPPORTED_ABIS.take(2).joinToString(", ")

  // CPU cores
  val cores = Runtime.getRuntime().availableProcessors()

  return listOf(
    "Manufacturer"  to Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
    "Device"        to "${Build.MODEL} (${Build.DEVICE})",
    "Android"       to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    "CPU ABI"       to abis,
    "CPU Cores"     to "$cores cores",
    "RAM"           to ramStr,
    "OpenGL ES"     to glesVersion,
    "Vulkan"        to vulkanStr,
    "GPU Renderer"  to (Build.HARDWARE.ifBlank { "Unknown" }),
    "Board"         to Build.BOARD,
    "Kernel"        to System.getProperty("os.version", "Unknown"),
  )
}

@Suppress("DEPRECATION")
@Serializable
object LibrariesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    Scaffold(
      topBar = {
        TopAppBar(
          title = { 
            Text(
              text = stringResource(id = R.string.pref_about_oss_libraries),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            ) 
          },
          navigationIcon = {
            IconButton(onClick = { backstack.popSafely() }) {
              Icon(
                imageVector = Icons.RoundedFilled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
            }
          },
        )
      },
    ) { paddingValues ->
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(paddingValues)
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_core_open_source_dependencies_used_by_mpvrx),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OPEN_SOURCE_LIBRARIES.forEach { library ->
          Card(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                context.startActivity(
                  Intent(Intent.ACTION_VIEW, library.url.toUri()),
                )
              },
            colors = CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ),
            shape = RoundedCornerShape(18.dp),
          ) {
            Column(
              modifier = Modifier.padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(
                text = library.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = library.artifact,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
              )
              Text(
                text = stringResource(library.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = library.license,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
              )
            }
          }
        }
      }
    }
  }
}

private data class OpenSourceLibrary(
  val name: String,
  val artifact: String,
  @StringRes val descriptionRes: Int,
  val license: String,
  val url: String,
)

private val OPEN_SOURCE_LIBRARIES = listOf(
  OpenSourceLibrary(
    name = "Jetpack Compose",
    artifact = "androidx.compose",
    descriptionRes = R.string.oss_jetpack_compose_description,
    license = "Apache-2.0",
    url = "https://developer.android.com/jetpack/compose",
  ),
  OpenSourceLibrary(
    name = "Material 3",
    artifact = "androidx.compose.material3:material3",
    descriptionRes = R.string.oss_material_3_description,
    license = "Apache-2.0",
    url = "https://developer.android.com/jetpack/androidx/releases/compose-material3",
  ),
  OpenSourceLibrary(
    name = "Navigation 3",
    artifact = "androidx.navigation3:navigation3-runtime",
    descriptionRes = R.string.oss_navigation_3_description,
    license = "Apache-2.0",
    url = "https://developer.android.com/jetpack/androidx/releases/navigation3",
  ),
  OpenSourceLibrary(
    name = "Koin",
    artifact = "io.insert-koin",
    descriptionRes = R.string.oss_koin_description,
    license = "Apache-2.0",
    url = "https://insert-koin.io/",
  ),
  OpenSourceLibrary(
    name = "Room",
    artifact = "androidx.room",
    descriptionRes = R.string.oss_room_description,
    license = "Apache-2.0",
    url = "https://developer.android.com/jetpack/androidx/releases/room",
  ),
  OpenSourceLibrary(
    name = "OkHttp",
    artifact = "com.squareup.okhttp3:okhttp",
    descriptionRes = R.string.oss_okhttp_description,
    license = "Apache-2.0",
    url = "https://square.github.io/okhttp/",
  ),
  OpenSourceLibrary(
    name = "kotlinx.serialization",
    artifact = "org.jetbrains.kotlinx:kotlinx-serialization-json",
    descriptionRes = R.string.oss_kotlinx_serialization_description,
    license = "Apache-2.0",
    url = "https://github.com/Kotlin/kotlinx.serialization",
  ),
  OpenSourceLibrary(
    name = "Accompanist Permissions",
    artifact = "com.google.accompanist:accompanist-permissions",
    descriptionRes = R.string.oss_accompanist_permissions_description,
    license = "Apache-2.0",
    url = "https://github.com/google/accompanist",
  ),
  OpenSourceLibrary(
    name = "MediaInfo Android",
    artifact = "com.github.marlboro-advance:mediainfoAndroid",
    descriptionRes = R.string.oss_mediainfo_android_description,
    license = "Open source",
    url = "https://github.com/marlboro-advance/mediainfoAndroid",
  ),
  OpenSourceLibrary(
    name = "SMBJ",
    artifact = "com.hierynomus:smbj",
    descriptionRes = R.string.oss_smbj_description,
    license = "Apache-2.0",
    url = "https://github.com/hierynomus/smbj",
  ),
  OpenSourceLibrary(
    name = "Commons Net",
    artifact = "commons-net:commons-net",
    descriptionRes = R.string.oss_commons_net_description,
    license = "Apache-2.0",
    url = "https://commons.apache.org/proper/commons-net/",
  ),
  OpenSourceLibrary(
    name = "Sardine Android",
    artifact = "com.github.thegrizzlylabs:sardine-android",
    descriptionRes = R.string.oss_sardine_android_description,
    license = "Apache-2.0",
    url = "https://github.com/thegrizzlylabs/sardine-android",
  ),
  OpenSourceLibrary(
    name = "NanoHTTPD",
    artifact = "org.nanohttpd:nanohttpd",
    descriptionRes = R.string.oss_nanohttpd_description,
    license = "BSD-3-Clause",
    url = "https://github.com/NanoHttpd/nanohttpd",
  ),
  OpenSourceLibrary(
    name = "FSAF",
    artifact = "com.github.K1rakishou:Fuck-Storage-Access-Framework",
    descriptionRes = R.string.oss_fsaf_description,
    license = "Apache-2.0",
    url = "https://github.com/K1rakishou/Fuck-Storage-Access-Framework",
  ),
  OpenSourceLibrary(
    name = "TrueType Parser",
    artifact = "io.github.yubyf:truetypeparser-light",
    descriptionRes = R.string.oss_truetype_parser_description,
    license = "Apache-2.0",
    url = "https://github.com/yubyf/truetypeparser",
  ),
  OpenSourceLibrary(
    name = "Compose Preference",
    artifact = "me.zhanghai.compose.preference:preference",
    descriptionRes = R.string.oss_compose_preference_description,
    license = "Apache-2.0",
    url = "https://github.com/zhanghai/ComposePreference",
  ),
  OpenSourceLibrary(
    name = "LazyColumnScrollbar",
    artifact = "com.github.nanihadesuka:LazyColumnScrollbar",
    descriptionRes = R.string.oss_lazycolumnscrollbar_description,
    license = "Apache-2.0",
    url = "https://github.com/Nanihadesuka/LazyColumnScrollbar",
  ),
  OpenSourceLibrary(
    name = "Reorderable",
    artifact = "sh.calvin.reorderable:reorderable",
    descriptionRes = R.string.oss_reorderable_description,
    license = "Apache-2.0",
    url = "https://github.com/Calvin-LL/Reorderable",
  ),
  OpenSourceLibrary(
    name = "Seeker",
    artifact = "com.github.abdallahmehiz:seeker",
    descriptionRes = R.string.oss_seeker_description,
    license = "Apache-2.0",
    url = "https://github.com/abdallahmehiz/seeker",
  ),
  OpenSourceLibrary(
    name = "Sora Editor",
    artifact = "io.github.rosemoe:editor",
    descriptionRes = R.string.oss_sora_editor_description,
    license = "LGPL-2.1",
    url = "https://github.com/Rosemoe/sora-editor",
  ),
  OpenSourceLibrary(
    name = "Media3",
    artifact = "androidx.media3",
    descriptionRes = R.string.oss_media3_description,
    license = "Apache-2.0",
    url = "https://developer.android.com/jetpack/androidx/releases/media3",
  ),
  OpenSourceLibrary(
    name = "Jsoup",
    artifact = "org.jsoup:jsoup",
    descriptionRes = R.string.oss_jsoup_description,
    license = "MIT",
    url = "https://jsoup.org/",
  ),
  OpenSourceLibrary(
    name = "Llamatik",
    artifact = "com.llamatik:library",
    descriptionRes = R.string.oss_llamatik_description,
    license = "Apache-2.0",
    url = "https://github.com/ferranpons/llamatik",
  ),
  OpenSourceLibrary(
    name = "AndroidX Core KTX",
    artifact = "androidx.core:core-ktx",
    descriptionRes = R.string.oss_androidx_core_ktx_description,
    license = "Apache-2.0",
    url = "https://developer.android.com/jetpack/androidx/releases/core",
  ),
  OpenSourceLibrary(
    name = "AndroidX Appcompat",
    artifact = "androidx.appcompat:appcompat",
    descriptionRes = R.string.oss_androidx_appcompat_description,
    license = "Apache-2.0",
    url = "https://developer.android.com/jetpack/androidx/releases/appcompat",
  ),
  OpenSourceLibrary(
    name = "AndroidX ConstraintLayout",
    artifact = "androidx.constraintlayout:constraintlayout",
    descriptionRes = R.string.oss_androidx_constraintlayout_description,
    license = "Apache-2.0",
    url = "https://developer.android.com/jetpack/androidx/releases/constraintlayout",
  ),
  OpenSourceLibrary(
    name = "AndroidX DocumentFile",
    artifact = "androidx.documentfile:documentfile",
    descriptionRes = R.string.oss_androidx_documentfile_description,
    license = "Apache-2.0",
    url = "https://developer.android.com/jetpack/androidx/releases/documentfile",
  ),
  OpenSourceLibrary(
    name = "AndroidX Media",
    artifact = "androidx.media:media",
    descriptionRes = R.string.oss_androidx_media_description,
    license = "Apache-2.0",
    url = "https://developer.android.com/jetpack/androidx/releases/media",
  ),
  OpenSourceLibrary(
    name = "AndroidX Preference",
    artifact = "androidx.preference:preference-ktx",
    descriptionRes = R.string.oss_androidx_preference_description,
    license = "Apache-2.0",
    url = "https://developer.android.com/jetpack/androidx/releases/preference",
  ),
  OpenSourceLibrary(
    name = "AndroidX Profile Installer",
    artifact = "androidx.profileinstaller:profileinstaller",
    descriptionRes = R.string.oss_androidx_profile_installer_description,
    license = "Apache-2.0",
    url = "https://developer.android.com/jetpack/androidx/releases/profileinstaller",
  ),
  OpenSourceLibrary(
    name = "Google Material",
    artifact = "com.google.android.material:material",
    descriptionRes = R.string.oss_google_material_description,
    license = "Apache-2.0",
    url = "https://material.io/develop/android",
  ),
  OpenSourceLibrary(
    name = "Google Cast",
    artifact = "com.google.android.gms:play-services-cast-framework",
    descriptionRes = R.string.oss_google_cast_description,
    license = "Apache-2.0",
    url = "https://developers.google.com/cast",
  ),
  OpenSourceLibrary(
    name = "Material Symbols",
    artifact = "com.composables:icons-material-symbols",
    descriptionRes = R.string.oss_material_symbols_description,
    license = "Apache-2.0",
    url = "https://github.com/compose-icons/compose-icons",
  ),
  OpenSourceLibrary(
    name = "Kotlinx Immutable Collections",
    artifact = "org.jetbrains.kotlinx:kotlinx-collections-immutable",
    descriptionRes = R.string.oss_kotlinx_immutable_collections_description,
    license = "Apache-2.0",
    url = "https://github.com/Kotlin/kotlinx.collections.immutable",
  ),
  OpenSourceLibrary(
    name = "Curl Android",
    artifact = "io.github.vvb2060.ndk:curl",
    descriptionRes = R.string.oss_curl_android_description,
    license = "curl",
    url = "https://github.com/vvb2060/curl-android",
  ),
  OpenSourceLibrary(
    name = "Desugar JDK Libs",
    artifact = "com.android.tools:desugar_jdk_libs",
    descriptionRes = R.string.oss_desugar_jdk_libs_description,
    license = "Apache-2.0",
    url = "https://github.com/google/desugar_jdk_libs",
  ),
)
