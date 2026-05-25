package app.gyrolet.mpvrx.ui.preferences

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.gyrolet.mpvrx.ui.components.AdaptiveSwitch
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
import app.gyrolet.mpvrx.presentation.components.LiquidDialog
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons as AppIcons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.NativeFreedrenoConfig
import kotlinx.coroutines.launch
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
        val remoteDriverGroups = remember { mutableStateListOf<GpuDriverManager.RemoteDriverGroup>() }
        var isFetching by remember { mutableStateOf(false) }
        var showFetchSheet by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableStateOf<Float?>(null) }
        var showInfoDialog by remember { mutableStateOf(false) }

        val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
        val expandedReleases = remember { mutableStateMapOf<String, Boolean>() }
        
        val activeDriverId by preferences.activeDriverId.collectAsState()
        val hasAcceptedWarning by preferences.hasAcceptedGpuWarning.collectAsState()
        var showCompatibilityWarning by remember { mutableStateOf(!hasAcceptedWarning && !driverManager.isAdrenoSupported()) }

        val isArm64 = remember { Build.SUPPORTED_ABIS.contains("arm64-v8a") }
        val isQualcomm = remember { driverManager.isAdrenoSupported() }
        val isSupported = isArm64 && isQualcomm
        
        val gpuModel = remember { 
            val bridgeInfo = driverManager.getGpuModel()
            if (bridgeInfo.contains("Architecture Not Supported") || bridgeInfo.contains("Generic GPU")) {
                "Detected ${Build.HARDWARE} (${Build.MODEL})"
            } else {
                bridgeInfo
            }
        }
        val adrenoModel = remember { driverManager.parseAdrenoModel(gpuModel) }
        val recommendedDriver = remember { driverManager.getRecommendedDriver(adrenoModel) }

        LaunchedEffect(Unit) {
            drivers.clear()
            drivers.addAll(driverManager.getInstalledDrivers())
        }

        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                scope.launch {
                    val result = driverManager.installDriver(it)
                    if (result.isSuccess) {
                        drivers.clear()
                        drivers.addAll(driverManager.getInstalledDrivers())
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
                if (showCompatibilityWarning) {
                    LiquidDialog(
                        onDismissRequest = { /* Must accept or go back */ },
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Icon(AppIcons.Default.Aperture, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Compatibility Warning")
                            }
                        },
                        text = {
                            Text("Custom GPU drivers are primarily designed for Qualcomm Adreno GPUs. Your device does not appear to have one.\n\nThere is no guarantee these drivers will work, and they could cause crashes or graphical glitches. Proceed with caution.")
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    preferences.hasAcceptedGpuWarning.set(true)
                                    showCompatibilityWarning = false
                                }
                            ) {
                                Text("I Understand")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { backstack.popSafely() }) {
                                Text("Go Back")
                            }
                        }
                    )
                }

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
                                        Text(
                                            if (!isArm64) stringResource(R.string.gpu_driver_not_supported)
                                            else "Custom drivers require a Qualcomm Adreno GPU.",
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            DeviceInfoHub(gpuModel, recommendedDriver)
                        }

                        item {
                            val showHud by preferences.showDriverHud.collectAsState()
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
                                        preferences.activeDriverId.set(driver.id)
                                        Toast.makeText(context, R.string.gpu_driver_restart_required, Toast.LENGTH_LONG).show()
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        driverManager.deleteDriver(driver.id)
                                        drivers.clear()
                                        drivers.addAll(driverManager.getInstalledDrivers())
                                        if (activeDriverId == driver.id) {
                                            preferences.activeDriverId.set("system")
                                        }
                                    }
                                }
                            )
                        }
                        
                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        tonalElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface
                    ) {
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
                                            remoteDriverGroups.addAll(driverManager.fetchRemoteDriverGroups())
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
                                                remoteDriverGroups.addAll(driverManager.fetchRemoteDriverGroups())
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
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("MpvRx System Info", systemInfo)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "System info copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(AppIcons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
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
}
