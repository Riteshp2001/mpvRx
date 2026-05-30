package app.gyrolet.mpvrx.ui.preferences

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.gyrolet.mpvrx.ui.player.components.expressive.ExpressiveSwitch as AdaptiveSwitch
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.gpu.GpuDriver
import app.gyrolet.mpvrx.domain.gpu.GpuDriverManager
import app.gyrolet.mpvrx.preferences.GpuDriverPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import androidx.compose.material3.AlertDialog as LiquidDialog
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons as AppIcons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.GpuDriverHelper
import app.gyrolet.mpvrx.utils.GpuDriverLogger
import app.gyrolet.mpvrx.utils.NativeFreedrenoConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object GpuDriverPreferencesScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("LocalContextGetResourceValueCall")
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val backstack = LocalBackStack.current
        val preferences = koinInject<GpuDriverPreferences>()
        val driverManager = koinInject<GpuDriverManager>()
        val scope = rememberCoroutineScope()

        val drivers = remember { mutableStateListOf<GpuDriver>() }
        val safDrivers = remember { mutableStateListOf<GpuDriverManager.SafGpuDriver>() }
        val remoteDriverGroups = remember { mutableStateListOf<GpuDriverManager.RemoteDriverGroup>() }
        var isFetching by remember { mutableStateOf(false) }
        var showFetchSheet by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableStateOf<Float?>(null) }
        var showInfoDialog by remember { mutableStateOf(false) }

        val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
        val expandedReleases = remember { mutableStateMapOf<String, Boolean>() }
        
        val activeDriverId by preferences.activeDriverId.collectAsState()

        val isArm64 = remember { Build.SUPPORTED_ABIS.contains("arm64-v8a") }
        val isQualcomm = remember { driverManager.isAdrenoSupported() }
        val isSupported = isArm64 && isQualcomm
        
        // Prefer a Build.SOC_MODEL / Build.BOARD lookup for the display
        // label too — the native bridge string is the generic
        // "Qualcomm Adreno GPU Detected" on every Adreno device. Fall back
        // to the bridge string only if AdrenoLookup doesn't know the SoC.
        val gpuModel = remember {
            val bridgeInfo = driverManager.getGpuModel()
            val enriched = app.gyrolet.mpvrx.utils.AdrenoLookup.displayLabel()
            when {
                bridgeInfo.contains("Architecture Not Supported") -> bridgeInfo
                enriched != null -> enriched
                bridgeInfo.contains("Generic GPU") -> "Detected ${Build.HARDWARE} (${Build.MODEL})"
                else -> bridgeInfo
            }
        }
        val adrenoModel = remember { driverManager.getAdrenoModelNumber() }
        val recommendedDriver = remember { driverManager.getRecommendedDriver(adrenoModel) }

        LaunchedEffect(Unit) {
            drivers.clear()
            drivers.addAll(driverManager.getInstalledDrivers())
            safDrivers.clear()
            safDrivers.addAll(driverManager.getSafDrivers())
        }

        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                scope.launch {
                    val result = driverManager.installDriver(it)
                    if (result.isSuccess) {
                        drivers.clear()
                        drivers.addAll(driverManager.getInstalledDrivers())
                        safDrivers.clear()
                        safDrivers.addAll(driverManager.getSafDrivers())
                        Toast.makeText(context, R.string.gpu_driver_install_success, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, context.getString(R.string.gpu_driver_install_failed, result.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.pref_gpu_driver_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { backstack.popSafely() }) {
                            Icon(AppIcons.Default.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showInfoDialog = true }) {
                            Icon(AppIcons.Default.DeveloperBoard, contentDescription = "Technical Info", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                if (showInfoDialog) {
                    TechnicalInfoDialog(onDismiss = { showInfoDialog = false })
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        item {
                            if (!isSupported) {
                                Card(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f))
                                ) {
                                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(AppIcons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column {
                                            Text(
                                                "GPU is unsupported",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                if (!isArm64) stringResource(R.string.gpu_driver_not_supported)
                                                else "Custom GPU drivers only work on Qualcomm Adreno hardware (no Mali, PowerVR, etc.). The driver loader is disabled on this device.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            DeviceInfoHub(gpuModel, recommendedDriver)
                        }

                        // Live driver status — green when a custom driver is
                        // armed, red when the last load failed and we auto-
                        // reverted to system. Sourced from GpuDriverHelper's
                        // mutable activeDriverStatus, which is set on every
                        // code path inside initialize(). Read inside a
                        // remember(activeDriverId) so the banner refreshes
                        // when the user changes the active driver pref.
                        item {
                            val status = remember(activeDriverId) {
                                app.gyrolet.mpvrx.utils.GpuDriverHelper.activeDriverStatus
                            }
                            when (status) {
                                app.gyrolet.mpvrx.utils.GpuDriverHelper.DriverStatus.CUSTOM_FAILED ->
                                    StatusBanner(
                                        icon = AppIcons.Outlined.Info,
                                        text = "Custom driver failed to load. Automatically reverted to system driver.",
                                        container = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                        content = MaterialTheme.colorScheme.onErrorContainer,
                                        accent = MaterialTheme.colorScheme.error,
                                    )
                                app.gyrolet.mpvrx.utils.GpuDriverHelper.DriverStatus.CUSTOM_ACTIVE ->
                                    StatusBanner(
                                        icon = AppIcons.Default.Check,
                                        text = "Custom driver active and running",
                                        container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                                        accent = MaterialTheme.colorScheme.primary,
                                        bold = true,
                                    )
                                else -> Unit
                            }
                        }

                        item {
                            val showHud by preferences.showDriverHud.collectAsState()
                            val verboseLogging by preferences.verboseLogging.collectAsState()
                            PreferenceSectionHeader(title = "General Settings")
                            PreferenceCard {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.gpu_driver_show_hud), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(stringResource(R.string.gpu_driver_show_hud_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                    AdaptiveSwitch(checked = showHud, onCheckedChange = { preferences.showDriverHud.set(it) })
                                }
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.gpu_driver_verbose_log), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(stringResource(R.string.gpu_driver_verbose_log_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                    AdaptiveSwitch(checked = verboseLogging, onCheckedChange = { preferences.verboseLogging.set(it) })
                                }
                            }
                        }

                        item {
                            PreferenceSectionHeader(title = "Installed Drivers")
                        }

                        items(drivers, key = { it.id }) { driver ->
                            DriverItem(
                                driver = driver,
                                isSelected = activeDriverId == driver.id,
                                onSelect = {
                                    if (activeDriverId != driver.id) {
                                        // Live-swap: tear down the old bridge hooks and
                                        // arm the new driver in-process. Saves the user a
                                        // force-close + cold-start. Pref is only persisted
                                        // after the bridge confirms the load. On failure
                                        // applyDriverChange THROWS — we deliberately let
                                        // it propagate so the app crashes with the real
                                        // cause instead of silently falling back to the
                                        // OEM driver. Boot-time guard recovers on the
                                        // next launch.
                                        scope.launch(Dispatchers.IO) {
                                            GpuDriverHelper.applyDriverChange(context, driver.id)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    context,
                                                    "Driver '${driver.name}' applied — start a new video to use it",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        val wasActive = activeDriverId == driver.id
                                        driverManager.deleteDriver(driver.id)
                                        drivers.clear()
                                        drivers.addAll(driverManager.getInstalledDrivers())
                                        safDrivers.clear()
                                        safDrivers.addAll(driverManager.getSafDrivers())
                                        if (wasActive) {
                                            // Live-swap to system so the bridge isn't
                                            // pointing at a now-deleted .so.
                                            withContext(Dispatchers.IO) {
                                                GpuDriverHelper.applyDriverChange(context, "system")
                                            }
                                        }
                                    }
                                }
                            )
                        }

                        // Hide SAF entries that duplicate an already-installed
                        // driver — showing an "Install" button next to a driver
                        // you've already installed is just noise. The auto-
                        // backup zip is still on disk for crash recovery; once
                        // the user uninstalls, the entry re-appears here so they
                        // can reinstall without redownloading.
                        val installedNames = drivers
                            .filterNot { it.isSystem }
                            .mapTo(mutableSetOf()) { it.name }
                        val visibleSafDrivers = safDrivers.filter { it.name !in installedNames }

                        if (isSupported && visibleSafDrivers.isNotEmpty()) {
                            item { PreferenceSectionHeader(title = "Drivers in Storage (gpudriver/)") }
                            items(visibleSafDrivers, key = { it.uri.toString() }) { safDriver ->
                                SafDriverItem(
                                    driver = safDriver,
                                    onInstall = {
                                        scope.launch {
                                            val result = driverManager.installDriver(safDriver.uri)
                                            if (result.isSuccess) {
                                                drivers.clear()
                                                drivers.addAll(driverManager.getInstalledDrivers())
                                                safDrivers.clear()
                                                safDrivers.addAll(driverManager.getSafDrivers())
                                                // Auto-activate the freshly installed driver
                                                // via the live-swap path so the user doesn't
                                                // need a restart to try it out. Throws on
                                                // failure (no silent OEM fallback).
                                                val newId = result.getOrNull()?.id
                                                if (newId != null) {
                                                    withContext(Dispatchers.IO) {
                                                        GpuDriverHelper.applyDriverChange(context, newId)
                                                    }
                                                }
                                                Toast.makeText(context, R.string.gpu_driver_install_success, Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.gpu_driver_install_failed, result.exceptionOrNull()?.message),
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        tonalElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        if (isSupported) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { launcher.launch("application/zip") },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                ) {
                                    Icon(AppIcons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.gpu_driver_install_from_file), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Button(
                                    onClick = {
                                        showFetchSheet = true
                                        if (remoteDriverGroups.isEmpty()) {
                                            scope.launch {
                                                isFetching = true
                                                remoteDriverGroups.clear()
                                                remoteDriverGroups.addAll(driverManager.fetchRemoteDriverGroups())
                                                isFetching = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(AppIcons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.gpu_driver_fetch_drivers), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        } else {
                            // Non-Adreno / non-arm64 hardware: replace the
                            // SAF picker and remote-driver downloader with a
                            // single warning so the user can't sideload or
                            // download an incompatible driver and crash the
                            // app on the next vkCreateInstance.
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    AppIcons.Outlined.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "Custom GPU installation disabled for preventing playback crashes",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                if (showFetchSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showFetchSheet = false },
                        sheetState = rememberModalBottomSheetState(),
                        containerColor = MaterialTheme.colorScheme.surface,
                        dragHandle = { BottomSheetDefaults.DragHandle() }
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.gpu_driver_fetch_drivers), 
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            isFetching = true
                                            remoteDriverGroups.clear()
                                            remoteDriverGroups.addAll(driverManager.fetchRemoteDriverGroups(forceRefresh = true))
                                            isFetching = false
                                        }
                                    },
                                    enabled = !isFetching
                                ) {
                                    Icon(AppIcons.Default.Refresh, contentDescription = "Refresh")
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Box(modifier = Modifier.fillMaxHeight(0.8f)) {
                                if (isFetching) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator()
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(stringResource(R.string.gpu_driver_fetching), style = MaterialTheme.typography.bodyMedium)
                                    }
                                } else if (remoteDriverGroups.isEmpty() || remoteDriverGroups.all { it.releases.isEmpty() }) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(AppIcons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("No drivers found or API error.", color = MaterialTheme.colorScheme.outline)
                                        TextButton(onClick = {
                                            scope.launch {
                                                isFetching = true
                                                remoteDriverGroups.clear()
                                                remoteDriverGroups.addAll(driverManager.fetchRemoteDriverGroups(forceRefresh = true))
                                                isFetching = false
                                            }
                                        }) {
                                            Text("Retry")
                                        }
                                    }
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        item {
                                            Card(
                                                modifier = Modifier.padding(bottom = 16.dp).fillMaxWidth(),
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                                            ) {
                                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(AppIcons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Text(
                                                        "Recommend: $recommendedDriver",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                                    )
                                                }
                                            }
                                        }

                                        remoteDriverGroups.forEach { group ->
                                            if (group.releases.isNotEmpty()) {
                                                item {
                                                    RemoteGroupItem(
                                                        group = group,
                                                        isExpanded = expandedGroups[group.name] ?: false,
                                                        onToggle = { expandedGroups[group.name] = !(expandedGroups[group.name] ?: false) }
                                                    ) {
                                                        group.releases.forEach { release ->
                                                            val releaseKey = "${group.name}_${release.version}"
                                                            RemoteReleaseItem(
                                                                release = release,
                                                                isExpanded = expandedReleases[releaseKey] ?: false,
                                                                onToggle = { expandedReleases[releaseKey] = !(expandedReleases[releaseKey] ?: false) },
                                                                downloadProgress = downloadProgress,
                                                                adrenoModel = adrenoModel,
                                                                onDownload = { remoteDriver ->
                                                                    scope.launch {
                                                                        downloadProgress = 0f
                                                                        val result = driverManager.downloadAndInstallDriver(remoteDriver) {
                                                                            downloadProgress = it
                                                                        }
                                                                        downloadProgress = null
                                                                        if (result.isSuccess) {
                                                                            drivers.clear()
                                                                            drivers.addAll(driverManager.getInstalledDrivers())
                                                                            showFetchSheet = false
                                                                            Toast.makeText(context, R.string.gpu_driver_install_success, Toast.LENGTH_SHORT).show()
                                                                        } else {
                                                                            Toast.makeText(context, context.getString(R.string.gpu_driver_install_failed, result.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
                                                                        }
                                                                    }
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        item { Spacer(modifier = Modifier.height(32.dp)) }
                                    }
                                }
                            }

                            AnimatedVisibility(visible = downloadProgress != null) {
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                                    LinearProgressIndicator(
                                        progress = { downloadProgress ?: 0f }, 
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Downloading: ${((downloadProgress ?: 0f) * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DeviceInfoHub(gpuModel: String, recommendedDriver: String) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.gpu_driver_device_info),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(AppIcons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = gpuModel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(AppIcons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = recommendedDriver,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SafDriverItem(
        driver: GpuDriverManager.SafGpuDriver,
        onInstall: () -> Unit,
    ) {
        Card(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
            onClick = onInstall,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
            shape = RoundedCornerShape(20.dp),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(
                        driver.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = listOfNotNull(
                        driver.version.takeIf { it.isNotEmpty() }?.let { "v$it" },
                        driver.author.takeIf { it.isNotEmpty() }?.let { "by $it" },
                    ).joinToString(" • ")
                    if (subtitle.isNotEmpty()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(
                    onClick = onInstall,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text("Install", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    @Composable
    private fun DriverItem(
        driver: GpuDriver,
        isSelected: Boolean,
        onSelect: () -> Unit,
        onDelete: () -> Unit
    ) {
        var showDeleteDialog by remember { mutableStateOf(false) }

        Card(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
            onClick = onSelect,
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(20.dp),
            border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = isSelected, onClick = onSelect)
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        driver.name, 
                        style = MaterialTheme.typography.titleMedium, 
                        fontWeight = FontWeight.Bold, 
                        maxLines = 1, 
                        overflow = TextOverflow.Ellipsis,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                    if (driver.version.isNotEmpty() || driver.author.isNotEmpty()) {
                        Text(
                            listOfNotNull(
                                if (driver.version.isNotEmpty()) "v${driver.version}" else null,
                                if (driver.author.isNotEmpty()) "by ${driver.author}" else null
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (driver.isSystem) {
                        Text(driver.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        // Metadata chips: size + install date + min Vulkan API.
                        // Each chip is conditional — empty fields don't render
                        // a placeholder, so older drivers without these values
                        // just show nothing extra.
                        val chips = buildList {
                            if (driver.fileSizeBytes > 0) add(formatFileSize(driver.fileSizeBytes))
                            if (driver.installDate > 0) add(formatInstallDate(driver.installDate))
                            if (driver.minApi.isNotEmpty()) add("Vulkan ${driver.minApi}")
                        }
                        if (chips.isNotEmpty()) {
                            Text(
                                chips.joinToString("  •  "),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                if (!driver.isSystem) {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(AppIcons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    }
                } else if (isSelected) {
                    Icon(AppIcons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                }
            }
        }

        if (showDeleteDialog) {
            LiquidDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text(stringResource(R.string.gpu_driver_delete_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text(stringResource(R.string.generic_cancel))
                    }
                }
            )
        }
    }

    @Composable
    private fun RemoteGroupItem(
        group: GpuDriverManager.RemoteDriverGroup,
        isExpanded: Boolean,
        onToggle: () -> Unit,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Surface(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                border = if (isExpanded) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(AppIcons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        group.name, 
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (isExpanded) AppIcons.Default.ExpandLess else AppIcons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    content()
                }
            }
        }
    }

    @Composable
    private fun RemoteReleaseItem(
        release: GpuDriverManager.RemoteRelease,
        isExpanded: Boolean,
        onToggle: () -> Unit,
        downloadProgress: Float?,
        adrenoModel: Int,
        onDownload: (GpuDriverManager.RemoteGpuDriver) -> Unit
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Surface(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = if (release.isLatest) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (release.isLatest) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (release.isLatest) AppIcons.Default.NewReleases else AppIcons.Default.Aperture, 
                            contentDescription = null, 
                            tint = if (release.isLatest) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, 
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                release.title, 
                                style = MaterialTheme.typography.bodyLarge, 
                                fontWeight = FontWeight.Bold, 
                                maxLines = 1, 
                                overflow = TextOverflow.Ellipsis
                            )
                            if (release.isLatest) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        "LATEST", 
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                        Text(
                            text = "Tag: ${release.version} • ${release.drivers.size} assets",
                            style = MaterialTheme.typography.labelSmall, 
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Icon(
                        if (isExpanded) AppIcons.Default.ExpandLess else AppIcons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    release.drivers.forEach { driver ->
                        val isRecommended = remember(driver.name, adrenoModel) {
                            if (adrenoModel >= 800) driver.name.contains("8xx", ignoreCase = true)
                            else if (adrenoModel >= 700) driver.name.contains("7xx", ignoreCase = true)
                            else if (adrenoModel >= 600) driver.name.contains("6xx", ignoreCase = true)
                            else false
                        }
                        RemoteGpuDriverItem(
                            driver = driver,
                            isRecommended = isRecommended,
                            isDownloading = downloadProgress != null,
                            onDownload = { onDownload(driver) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun TechnicalInfoDialog(onDismiss: () -> Unit) {
        val context = LocalContext.current
        val driverManager = koinInject<GpuDriverManager>()
        val gpuPrefs = koinInject<GpuDriverPreferences>()
        val shareScope = rememberCoroutineScope()
        
        val systemInfo = remember {
            buildString {
                appendLine("=== General Information ===")
                appendLine("Manufacturer: ${Build.MANUFACTURER}")
                appendLine("Model: ${Build.MODEL}")
                appendLine("Device: ${Build.DEVICE}")
                appendLine("Hardware: ${Build.HARDWARE}")
                appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
                appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                
                appendLine()
                appendLine("=== CPU Info ===")
                appendLine("Processor/Board: ${Build.BOARD}")

                appendLine()
                appendLine("=== GPU Information ===")
                val gpuModel = driverManager.getGpuModel()
                appendLine("GPU Model: $gpuModel")
                
                val vulkanFeature = context.packageManager.systemAvailableFeatures.find { 
                    it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION 
                }
                vulkanFeature?.let {
                    val version = it.version
                    val major = version shr 22
                    val minor = (version shr 12) and 0x3FF
                    val patch = version and 0xFFF
                    appendLine("Vulkan Hardware Version: $major.$minor.$patch")
                }

                appendLine()
                appendLine("=== Memory Info ===")
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val memInfo = android.app.ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memInfo)
                val totalRam = memInfo.totalMem / (1024 * 1024)
                val availableRam = memInfo.availMem / (1024 * 1024)
                
                appendLine("Total RAM: $totalRam MB")
                appendLine("Available RAM: $availableRam MB")
                if (memInfo.lowMemory) {
                    appendLine("Status: Low Memory Warning Active")
                }
            }
        }

        LiquidDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Default.DeveloperBoard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Technical Info")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                // Heavy: collects state + runs `logcat -d`.
                                // Off-main thread to keep the dialog snappy.
                                shareScope.launch {
                                    val dump = withContext(Dispatchers.IO) {
                                        GpuDriverLogger.collect(context, driverManager, gpuPrefs)
                                    }
                                    GpuDriverLogger.share(context, dump)
                                }
                            }
                        ) {
                            Icon(AppIcons.Default.Download, contentDescription = "Share diagnostic log", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("StreamX System Info", systemInfo)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "System info copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(AppIcons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            },
            text = {
                Box(modifier = Modifier.heightIn(max = 400.dp)) {
                    val scrollState = rememberScrollState()
                    Column(modifier = Modifier.verticalScroll(scrollState)) {
                        Text(
                            text = systemInfo,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                lineHeight = 16.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp)
        )
    }

    @Composable
    private fun RemoteGpuDriverItem(
        driver: GpuDriverManager.RemoteGpuDriver,
        isRecommended: Boolean,
        isDownloading: Boolean,
        onDownload: () -> Unit
    ) {
        Surface(
            onClick = onDownload,
            enabled = !isDownloading,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = if (isRecommended) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
            border = if (isRecommended) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)) else null
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isRecommended) AppIcons.Default.AutoAwesome else AppIcons.Default.Download, 
                    contentDescription = null, 
                    tint = if (isRecommended) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    driver.name, 
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isRecommended) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isRecommended) {
                    Text(
                        "MATCH",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Icon(
                    AppIcons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }

    // Compact status card rendered above the General Settings section. Driven
    // by GpuDriverHelper.activeDriverStatus — green when a custom driver is
    // armed, red when the last load failed and we auto-reverted.
    @Composable
    private fun StatusBanner(
        icon: app.gyrolet.mpvrx.ui.icons.AppIcon,
        text: String,
        container: Color,
        content: Color,
        accent: Color,
        bold: Boolean = false,
    ) {
        Card(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = container),
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    color = content,
                )
            }
        }
    }

    // Renders bytes as "12.3 MB" / "456 KB" / "789 B" — driver sizes are
    // typically 5–100 MB so MB is the common case. Two-decimal precision
    // keeps the chip narrow without losing useful detail.
    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    // Renders an epoch-millis install timestamp as a short locale date.
    // Uses java.text directly to avoid pulling in a new dep; the formatter is
    // re-created per call which is fine for a UI list of ~5–20 drivers.
    private fun formatInstallDate(epochMillis: Long): String {
        val fmt = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(epochMillis))
    }
}
