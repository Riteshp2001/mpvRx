package app.gyrolet.mpvrx.ui.preferences

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
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
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.io.File
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import app.gyrolet.mpvrx.ui.preferences.components.AdaptiveSwitchPreference

@Serializable
object YtdlpSettingsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val backStack = LocalBackStack.current
        val clipboardManager = LocalClipboardManager.current
        val scope = rememberCoroutineScope()
        var logs by remember { mutableStateOf("") }
        val scrollState = rememberScrollState()
        var isRunning by remember { mutableStateOf(false) }

        val ytdlPreferences = koinInject<YtdlPreferences>()
        val ytdlQuality by ytdlPreferences.ytdlQuality.collectAsState()
        val preferH264 by ytdlPreferences.preferH264.collectAsState()
        val writeSubs by ytdlPreferences.writeSubs.collectAsState()
        val writeAutoSubs by ytdlPreferences.writeAutoSubs.collectAsState()
        
        var userAgentText by remember { mutableStateOf(ytdlPreferences.customUserAgent.get()) }
        var rawOptionsText by remember { mutableStateOf(ytdlPreferences.customRawOptions.get()) }

        val ytdlDir = remember { YtdlpManager.getYtdlDir(context) }
        var hasYtdlp by remember { mutableStateOf(File(ytdlDir, "yt-dlp").exists()) }

        LaunchedEffect(isRunning) {
            if (!isRunning) {
                hasYtdlp = File(ytdlDir, "yt-dlp").exists()
            }
        }

        LaunchedEffect(logs) {
            scrollState.animateScrollTo(scrollState.maxValue)
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
                            Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
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
                                    imageVector = if (hasYtdlp) Icons.Default.Check else Icons.Default.CloudDownload,
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
                                        updateFormatString(ytdlPreferences, level, preferH264)
                                    },
                                    label = { Text(qualityLabels[index]) },
                                    leadingIcon = if (ytdlQuality == level) {
                                        { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }

                        PreferenceDivider()

                        AdaptiveSwitchPreference(
                            value = preferH264,
                            onValueChange = { newValue ->
                                ytdlPreferences.preferH264.set(newValue)
                                updateFormatString(ytdlPreferences, ytdlQuality, newValue)
                            },
                            title = { Text("Prefer H.264 (AVC) Codec", fontWeight = FontWeight.Medium) },
                            summary = { Text("Limits quality to 1080p but maximizes hardware decoding compatibility.") }
                        )

                        PreferenceDivider()

                        val currentFormat = remember(ytdlQuality, preferH264) {
                            computeFormatString(ytdlQuality, preferH264)
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
                        AdaptiveSwitchPreference(
                            value = writeSubs,
                            onValueChange = { ytdlPreferences.writeSubs.set(it) },
                            title = { Text("Download Media Subtitles", fontWeight = FontWeight.Medium) },
                            summary = { Text("Automatically extract and load physical subtitle tracks from supported URLs.") }
                        )

                        PreferenceDivider()

                        AdaptiveSwitchPreference(
                            value = writeAutoSubs,
                            onValueChange = { ytdlPreferences.writeAutoSubs.set(it) },
                            title = { Text("Include Auto-Generated Subtitles", fontWeight = FontWeight.Medium) },
                            summary = { Text("Fetch auto-caption tracks (e.g. YouTube Speech-to-Text) when regular subs are absent.") }
                        )
                    }
                }

                PreferenceSectionHeader(title = "Advanced Networking")

                PreferenceCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)
                    ) {
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
                            value = rawOptionsText,
                            onValueChange = { 
                                rawOptionsText = it
                                ytdlPreferences.customRawOptions.set(it)
                            },
                            label = { Text("Raw CLI Option Flags (Comma-separated)") },
                            placeholder = { Text("geo-bypass=, referer=https://youtube.com/") },
                            singleLine = false,
                            maxLines = 3,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            ),
                            supportingText = {
                                Text("Pass arbitrary key=value parameters to yt-dlp. Make sure option names end with '=' (e.g. referer=val, geo-bypass=).")
                            }
                        )
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
                                        logs = ""
                                        YtdlpManager.runInstall(context) { line ->
                                            logs += line
                                        }
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
                                Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Install Core")
                            }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        isRunning = true
                                        logs = ""
                                        YtdlpManager.runUpdate(context) { line ->
                                            logs += line
                                        }
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
                                Icon(Icons.Default.Update, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Update Core")
                            }
                        }
                    }
                }

                // High-Tech Console Terminal
                AnimatedVisibility(
                    visible = isRunning || logs.isNotEmpty(),
                    enter = androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // Terminal Header
                        Surface(
                            color = Color(0xFF0F1419),
                            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Simulated Terminal status dots
                                    Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFF5F56)))
                                    Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFFBD2E)))
                                    Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF27C93F)))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "engine-terminal.sh",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFE6B450)
                                        )
                                    )
                                }
                                
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isRunning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = Color(0xFF00FF99)
                                        )
                                    }
                                    
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(logs))
                                            Toast.makeText(context, "Terminal logs copied", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy logs",
                                            tint = Color(0xFFB3B3B3),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    
                                    IconButton(
                                        onClick = { logs = "" },
                                        modifier = Modifier.size(24.dp),
                                        enabled = !isRunning
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Clear logs",
                                            tint = if (isRunning) Color(0xFF555555) else Color(0xFFB3B3B3),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Terminal logs content
                        Surface(
                            color = Color(0xFF070B0E),
                            shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                        ) {
                            val terminalScrollState = rememberScrollState()
                            
                            LaunchedEffect(logs) {
                                terminalScrollState.animateScrollTo(terminalScrollState.maxValue)
                            }
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(terminalScrollState)
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = logs.ifEmpty { "Ready..." },
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                        color = Color(0xFF00FF99)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }

    private fun updateFormatString(prefs: YtdlPreferences, quality: Int, preferH264: Boolean) {
        prefs.ytdlFormat.set(computeFormatString(quality, preferH264))
    }

    private fun computeFormatString(quality: Int, preferH264: Boolean): String {
        var qstr = ""
        if (quality != -1 && preferH264) {
            qstr = "(bv*[vcodec^=?avc]/bv*[vcodec^=?mp4])[height<=?${quality}]+ba/" +
                    "(b[vcodec^=?avc]/b[vcodec^=?mp4])[height<=?${quality}]"
        } else if (quality != -1) {
            qstr = "bv[height<=?${quality}]+ba/b[height<=?${quality}]"
        } else if (preferH264) {
            qstr = "(bv*[vcodec^=?avc]/bv*[vcodec^=?mp4])+ba/(b[vcodec^=?avc]/b[vcodec^=?mp4])"
        }
        if (qstr.isNotEmpty())
            qstr += "/bv*+ba/b"
        return qstr
    }
}
