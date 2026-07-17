package app.gyrolet.mpvrx.ui.preferences

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlCodecPreference
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlContainerPreference
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlHdrPreference
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlPlaylistMode
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlAudioPreference
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpOptionSettings
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpOptionsBuilder
import androidx.compose.runtime.saveable.rememberSaveable
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.io.File
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlAudioQuality

@Serializable
object YtdlpSettingsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val backStack = LocalBackStack.current
        val scope = rememberCoroutineScope()
        val scrollState = rememberScrollState()
        var isRunning by remember { mutableStateOf(false) }

        val ytdlPreferences = koinInject<YtdlPreferences>()
        val ytdlQuality by ytdlPreferences.ytdlQuality.collectAsState()
        val preferH264 by ytdlPreferences.preferH264.collectAsState()
        val codecPreference by ytdlPreferences.codecPreference.collectAsState()
        val maxFps by ytdlPreferences.maxFps.collectAsState()
        val hdrPreference by ytdlPreferences.hdrPreference.collectAsState()
        val containerPreference by ytdlPreferences.containerPreference.collectAsState()
        val audioPreference by ytdlPreferences.audioPreference.collectAsState()
        val audioQuality by ytdlPreferences.audioQuality.collectAsState()
        val playlistMode by ytdlPreferences.playlistMode.collectAsState()
        val geoBypass by ytdlPreferences.geoBypass.collectAsState()
        val liveFromStart by ytdlPreferences.liveFromStart.collectAsState()
        val writeSubs by ytdlPreferences.writeSubs.collectAsState()
        val writeAutoSubs by ytdlPreferences.writeAutoSubs.collectAsState()
        
        var showAdvancedNetworking by rememberSaveable { mutableStateOf(false) }
        
        var userAgentText by remember { mutableStateOf(ytdlPreferences.customUserAgent.get()) }
        var subtitleLanguagesText by remember { mutableStateOf(ytdlPreferences.subtitleLanguages.get()) }
        var formatSortText by remember { mutableStateOf(ytdlPreferences.formatSort.get()) }
        var mergeOutputFormatText by remember { mutableStateOf(ytdlPreferences.mergeOutputFormat.get()) }
        var refererText by remember { mutableStateOf(ytdlPreferences.referer.get()) }
        var cookiesFileText by remember { mutableStateOf(ytdlPreferences.cookiesFile.get()) }
        var proxyText by remember { mutableStateOf(ytdlPreferences.proxy.get()) }
        var extractorArgsText by remember { mutableStateOf(ytdlPreferences.extractorArgs.get()) }
        var sponsorBlockMarkText by remember { mutableStateOf(ytdlPreferences.sponsorBlockMark.get()) }
        var sponsorBlockRemoveText by remember { mutableStateOf(ytdlPreferences.sponsorBlockRemove.get()) }
        var rawOptionsText by remember { mutableStateOf(ytdlPreferences.customRawOptions.get()) }

        val ytdlDir = remember { YtdlpManager.getYtdlDir(context) }
        var hasYtdlp by remember { mutableStateOf(File(ytdlDir, "yt-dlp").exists()) }

        LaunchedEffect(isRunning) {
            if (!isRunning) {
                hasYtdlp = File(ytdlDir, "yt-dlp").exists()
            }
        }

        val ytdlpInfo = remember(hasYtdlp) {
            if (hasYtdlp) {
                val size = try {
                    val f = File(ytdlDir, "yt-dlp")
                    if (f.exists()) " (${f.length() / 1024 / 1024} MB)" else ""
                } catch (_: Exception) { "" }
                "Installed$size"
            } else {
                "Not Configured"
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "yt-dlp Streaming",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { backStack.popSafely() }) {
                            Icon(Icons.RoundedFilled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            ProvidePreferenceLocals {
                Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scrollState)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)
            ) {
                // Expressive Installation Status Card
                PreferenceCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (hasYtdlp) MaterialTheme.colorScheme.primaryContainer 
                                    else MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (hasYtdlp) Icons.RoundedFilled.Check else Icons.RoundedFilled.CloudDownload,
                                    contentDescription = null,
                                    tint = if (hasYtdlp) MaterialTheme.colorScheme.onPrimaryContainer 
                                           else MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "yt-dlp Core Engine",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (hasYtdlp) "Subprocess active and ready for streaming" 
                                       else "Engine missing. Please run installation below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (hasYtdlp) MaterialTheme.colorScheme.primary 
                                    else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Text(
                                text = ytdlpInfo,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (hasYtdlp) MaterialTheme.colorScheme.onPrimary 
                                        else MaterialTheme.colorScheme.onError
                            )
                        }
                    }
                }

                PreferenceSectionHeader(title = "Engine Installer")

                PreferenceCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Manage yt-dlp Environment",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Download the latest wrapper modules and compile python-friendly native binaries inside local sandboxed folders.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        isRunning = true
                                        YtdlpManager.runInstall(context) {}
                                        isRunning = false
                                    }
                                },
                                enabled = !isRunning,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(Icons.RoundedFilled.Download, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Install Core")
                            }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        isRunning = true
                                        YtdlpManager.runUpdate(context) {}
                                        isRunning = false
                                    }
                                },
                                enabled = !isRunning && hasYtdlp,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Icon(Icons.RoundedFilled.Update, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Update Core")
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isRunning = true
                                    YtdlpManager.runUpdateToNightly(context) {}
                                    isRunning = false
                                }
                            },
                            enabled = !isRunning && hasYtdlp,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.secondary
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.RoundedFilled.Update, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Update to Nightly Build")
                        }
                    }
                }

                PreferenceSectionHeader(title = "Quality & Format")

                PreferenceCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)
                    ) {
                        Text(
                            text = "Streaming Quality",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        val qualityLevels = remember { arrayOf(-1, 2160, 1440, 1080, 720, 480, 360, 240, 144) }
                        val qualityLabels = remember { qualityLevels.map { if (it == -1) "Any" else "${it}p" } }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            qualityLevels.forEachIndexed { index, level ->
                                FilterChip(
                                    selected = ytdlQuality == level,
                                    onClick = {
                                        ytdlPreferences.ytdlQuality.set(level)
                                        updateFormatString(ytdlPreferences)
                                    },
                                    label = { Text(qualityLabels[index]) },
                                    leadingIcon = if (ytdlQuality == level) {
                                        { Icon(Icons.RoundedFilled.Check, null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }

                        PreferenceDivider()

                        OptionDropdown(
                            title = "Video Codec",
                            value = codecPreference,
                            values = YtdlCodecPreference.entries,
                            valueLabel = { it.title },
                            onValueChange = { selected ->
                                ytdlPreferences.codecPreference.set(selected)
                                ytdlPreferences.preferH264.set(selected == YtdlCodecPreference.H264)
                                updateFormatString(ytdlPreferences)
                            },
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(0 to "Any FPS", 30 to "30 FPS", 60 to "60 FPS", 120 to "120 FPS").forEach { (fps, label) ->
                                FilterChip(
                                    selected = maxFps == fps,
                                    onClick = {
                                        ytdlPreferences.maxFps.set(fps)
                                        updateFormatString(ytdlPreferences)
                                    },
                                    label = { Text(label) },
                                    leadingIcon = if (maxFps == fps) {
                                        { Icon(Icons.RoundedFilled.Check, null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }

                        OptionDropdown(
                            title = "HDR Preference",
                            value = hdrPreference,
                            values = YtdlHdrPreference.entries,
                            valueLabel = { it.title },
                            onValueChange = {
                                ytdlPreferences.hdrPreference.set(it)
                                updateFormatString(ytdlPreferences)
                            },
                        )

                        OptionDropdown(
                            title = "Container",
                            value = containerPreference,
                            values = YtdlContainerPreference.entries,
                            valueLabel = { it.title },
                            onValueChange = {
                                ytdlPreferences.containerPreference.set(it)
                                updateFormatString(ytdlPreferences)
                            },
                        )

                        OptionDropdown(
                            title = "Audio Preference",
                            value = audioPreference,
                            values = YtdlAudioPreference.entries,
                            valueLabel = { it.title },
                            onValueChange = { selected ->
                                ytdlPreferences.audioPreference.set(selected)
                                updateFormatString(ytdlPreferences)
                            },
                        )

                        OptionDropdown(
                            title = "Audio Quality",
                            value = audioQuality,
                            values = YtdlAudioQuality.entries,
                            valueLabel = { it.title },
                            onValueChange = { selected ->
                                ytdlPreferences.audioQuality.set(selected)
                                updateFormatString(ytdlPreferences)
                            },
                        )

                        Text(
                            text = "Bitrate caps apply when yt-dlp reports audio bitrate metadata.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )


                        PreferenceDivider()

                        val currentFormat = remember(ytdlQuality, preferH264, codecPreference, maxFps, hdrPreference, containerPreference, audioPreference, audioQuality) {
                            YtdlpOptionsBuilder.buildFormat(YtdlpOptionSettings.fromYtdlPreferences(ytdlPreferences))
                        }
                        
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = "Generated Format String",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = currentFormat.ifBlank { "(default)" },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                PreferenceSectionHeader(title = "Subtitles & Language")

                PreferenceCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)
                    ) {
                        SwitchPreference(
                            value = writeSubs,
                            onValueChange = { ytdlPreferences.writeSubs.set(it) },
                            title = { Text("Download Media Subtitles", fontWeight = FontWeight.Medium) },
                            summary = { Text("Automatically extract and load physical subtitle tracks from supported URLs.") }
                        )

                        PreferenceDivider()

                        SwitchPreference(
                            value = writeAutoSubs,
                            onValueChange = { ytdlPreferences.writeAutoSubs.set(it) },
                            title = { Text("Include Auto-Generated Subtitles", fontWeight = FontWeight.Medium) },
                            summary = { Text("Fetch auto-caption tracks (e.g. YouTube Speech-to-Text) when regular subs are absent.") }
                        )

                        PreferenceDivider()

                        OutlinedTextField(
                            value = subtitleLanguagesText,
                            onValueChange = {
                                subtitleLanguagesText = it
                                ytdlPreferences.subtitleLanguages.set(it)
                            },
                            label = { Text("Subtitle Languages") },
                            placeholder = { Text("all or en.*,ja") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text("Overrides app subtitle languages only for yt-dlp downloads.") }
                        )
                    }
                }

                PreferenceSectionHeader(title = "Advanced Networking")

                PreferenceCard {
                    Column(
                        modifier = Modifier.fillMaxWidth().animateContentSize(),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAdvancedNetworking = !showAdvancedNetworking }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Advanced Configurations",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Custom HTTP agent, proxy, extractor args, SponsorBlock, and raw options",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = if (showAdvancedNetworking) Icons.RoundedFilled.KeyboardArrowUp else Icons.RoundedFilled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        
                        if (showAdvancedNetworking) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)
                            ) {
                                PreferenceDivider()

                                OutlinedTextField(
                                    value = formatSortText,
                                    onValueChange = {
                                        formatSortText = it
                                        ytdlPreferences.formatSort.set(it)
                                    },
                                    label = { Text("Format Sort") },
                                    placeholder = { Text("res,fps,hdr:12,vcodec:vp9.2") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    supportingText = { Text("Passed to yt-dlp as format-sort for advanced ranking.") }
                                )

                                PreferenceDivider()

                                OutlinedTextField(
                                    value = mergeOutputFormatText,
                                    onValueChange = {
                                        mergeOutputFormatText = it
                                        ytdlPreferences.mergeOutputFormat.set(it)
                                    },
                                    label = { Text("Merge Output Format") },
                                    placeholder = { Text("mp4, mkv, webm") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                
                                PreferenceDivider()
                                
                                OutlinedTextField(
                                    value = userAgentText,
                                    onValueChange = { 
                                        userAgentText = it
                                        ytdlPreferences.customUserAgent.set(it)
                                    },
                                    label = { Text("Custom User-Agent Override") },
                                    placeholder = { Text("Mozilla/5.0 ...") },
                                    singleLine = false,
                                    maxLines = 3,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    supportingText = {
                                        Text("Leave blank to use default browser User-Agent. Helps bypass anti-bot scrapers.")
                                    }
                                )

                                PreferenceDivider()

                                OutlinedTextField(
                                    value = refererText,
                                    onValueChange = {
                                        refererText = it
                                        ytdlPreferences.referer.set(it)
                                    },
                                    label = { Text("Referer") },
                                    placeholder = { Text("https://www.youtube.com/") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                OutlinedTextField(
                                    value = cookiesFileText,
                                    onValueChange = {
                                        cookiesFileText = it
                                        ytdlPreferences.cookiesFile.set(it)
                                    },
                                    label = { Text("Cookies File") },
                                    placeholder = { Text("/storage/emulated/0/Download/cookies.txt") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                OutlinedTextField(
                                    value = proxyText,
                                    onValueChange = {
                                        proxyText = it
                                        ytdlPreferences.proxy.set(it)
                                    },
                                    label = { Text("Proxy") },
                                    placeholder = { Text("socks5://127.0.0.1:1080") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                OutlinedTextField(
                                    value = extractorArgsText,
                                    onValueChange = {
                                        extractorArgsText = it
                                        ytdlPreferences.extractorArgs.set(it)
                                    },
                                    label = { Text("Extractor Args") },
                                    placeholder = { Text("youtube:player_client=android,web") },
                                    singleLine = false,
                                    maxLines = 2,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                OptionDropdown(
                                    title = "Playlist Behavior",
                                    value = playlistMode,
                                    values = YtdlPlaylistMode.entries,
                                    valueLabel = { it.title },
                                    onValueChange = { ytdlPreferences.playlistMode.set(it) },
                                )

                                SwitchPreference(
                                    value = geoBypass,
                                    onValueChange = { ytdlPreferences.geoBypass.set(it) },
                                    title = { Text("Geo Bypass", fontWeight = FontWeight.Medium) },
                                    summary = { Text("Ask yt-dlp to use its extractor-level region bypass logic.") }
                                )

                                SwitchPreference(
                                    value = liveFromStart,
                                    onValueChange = { ytdlPreferences.liveFromStart.set(it) },
                                    title = { Text("Live From Start", fontWeight = FontWeight.Medium) },
                                    summary = { Text("Start live streams from the beginning when the extractor supports it.") }
                                )

                                OutlinedTextField(
                                    value = sponsorBlockMarkText,
                                    onValueChange = {
                                        sponsorBlockMarkText = it
                                        ytdlPreferences.sponsorBlockMark.set(it)
                                    },
                                    label = { Text("SponsorBlock Mark") },
                                    placeholder = { Text("sponsor,selfpromo") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                OutlinedTextField(
                                    value = sponsorBlockRemoveText,
                                    onValueChange = {
                                        sponsorBlockRemoveText = it
                                        ytdlPreferences.sponsorBlockRemove.set(it)
                                    },
                                    label = { Text("SponsorBlock Remove") },
                                    placeholder = { Text("sponsor") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                PreferenceDivider()

                                OutlinedTextField(
                                    value = rawOptionsText,
                                    onValueChange = { 
                                        rawOptionsText = it
                                        ytdlPreferences.customRawOptions.set(it)
                                    },
                                    label = { Text("Raw yt-dlp Options") },
                                    placeholder = { Text("extractor-args=\"youtube:player_client=android,web\"\ngeo-bypass=") },
                                    singleLine = false,
                                    maxLines = 6,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    supportingText = {
                                        Text("Anything not exposed above. Separate options with new lines or commas; quote values that contain commas.")
                                    }
                                )
                            }
                        }
                    }
                }

                // Engine Installer moved to top
            }
        }
    }
    }

    private fun updateFormatString(prefs: YtdlPreferences) {
        prefs.ytdlFormat.set(YtdlpOptionsBuilder.buildFormat(YtdlpOptionSettings.fromYtdlPreferences(prefs)))
    }
}

private fun YtdlpOptionSettings.Companion.fromYtdlPreferences(prefs: YtdlPreferences): YtdlpOptionSettings =
    YtdlpOptionSettings(
        codecPreference = prefs.codecPreference.get(),
        legacyPreferH264 = prefs.preferH264.get(),
        maxHeight = prefs.ytdlQuality.get(),
        maxFps = prefs.maxFps.get(),
        hdrPreference = prefs.hdrPreference.get(),
        containerPreference = prefs.containerPreference.get(),
        audioPreference = prefs.audioPreference.get(),
        audioQuality = prefs.audioQuality.get(),
    )

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun <T> OptionDropdown(
    title: String,
    value: T,
    values: List<T>,
    valueLabel: (T) -> String,
    onValueChange: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = valueLabel(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(title) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            values.forEach { item ->
                DropdownMenuItem(
                    text = { Text(valueLabel(item)) },
                    onClick = {
                        expanded = false
                        onValueChange(item)
                    },
                )
            }
        }
    }
}
