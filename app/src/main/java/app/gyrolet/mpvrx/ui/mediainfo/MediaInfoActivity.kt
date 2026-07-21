package app.gyrolet.mpvrx.ui.mediainfo

import app.gyrolet.mpvrx.R
import androidx.compose.ui.res.stringResource

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.theme.DarkMode
import app.gyrolet.mpvrx.ui.theme.MpvrxTheme
import app.gyrolet.mpvrx.utils.clipboard.SafeClipboard
import app.gyrolet.mpvrx.utils.media.MediaInfoOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File

class MediaInfoActivity : ComponentActivity() {
  private val appearancePreferences by inject<AppearancePreferences>()
  private val TAG = "MediaInfoActivity"

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContent {
      val dark by appearancePreferences.darkMode.collectAsState()
      val isSystemInDarkTheme = isSystemInDarkTheme()
      val isDarkMode = dark == DarkMode.Dark || (dark == DarkMode.System && isSystemInDarkTheme)

      enableEdgeToEdge(
        SystemBarStyle.auto(
          lightScrim = Color.White.toArgb(),
          darkScrim = Color.Transparent.toArgb(),
        ) { isDarkMode },
      )

      MpvrxTheme {
        Surface {
          MediaInfoScreen(
            onBack = { finish() },
            isDarkMode = isDarkMode,
          )
        }
      }
    }
  }

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  private fun MediaInfoScreen(
    onBack: () -> Unit,
    isDarkMode: Boolean,
  ) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var textContent by remember { mutableStateOf<String?>(null) }
    var fullMediaInfoText by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf("Media File") }
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var mediaInfo by remember { mutableStateOf<MediaInfoOps.MediaInfoData?>(null) }

    LaunchedEffect(Unit) {
      val uri = when (intent?.action) {
        Intent.ACTION_SEND -> {
          if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
          } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
          }
        }

        Intent.ACTION_VIEW -> {
          intent.data
        }

        else -> null
      }

      if (uri == null) {
        error = "No media file provided"
        isLoading = false
        return@LaunchedEffect
      }

      fileUri = uri

      // Get the file name
      fileName = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
          val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
          if (nameIndex >= 0 && cursor.moveToFirst()) {
            cursor.getString(nameIndex) ?: uri.lastPathSegment ?: "Unknown"
          } else {
            uri.lastPathSegment ?: "Unknown"
          }
        } ?: uri.lastPathSegment ?: "Unknown"
      } catch (e: Exception) {
        Log.e(TAG, "Error getting file name", e)
        uri.lastPathSegment ?: "Unknown"
      }

      // Load media info
      scope.launch {
        try {
          val result = MediaInfoOps.getMediaInfo(context, uri, fileName)
          result.onSuccess { mediaInfoResult ->
            mediaInfo = mediaInfoResult

            // Also generate text content for sharing/copying
            val textResult = MediaInfoOps.generateTextOutput(context, uri, fileName)
            textResult.onSuccess { text ->
              textContent = text
              fullMediaInfoText = text
            }

            isLoading = false
          }.onFailure { e ->
            error = e.message ?: "Failed to load media information"
            isLoading = false
          }
        } catch (e: Exception) {
          error = e.message ?: "Unknown error"
          isLoading = false
        }
      }
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Column {
              Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_media_info),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
              )
              Text(
                text = fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          },
          navigationIcon = {
            IconButton(onClick = onBack) {
              Icon(Icons.RoundedFilled.ArrowBack, contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.back))
            }
          },
          actions = {
            if (!isLoading && error == null && textContent != null) {
              Row(modifier = Modifier.padding(end = 12.dp)) {
                FilledTonalIconButton(
                  onClick = {
                    scope.launch {
                      copyToClipboard(textContent!!, fileName)
                    }
                  },
                  colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                  ),
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.ContentCopy,
                    contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_copy),
                  )
                }

                Spacer(modifier = Modifier.width(8.dp))

                FilledTonalIconButton(
                  onClick = {
                    scope.launch {
                      shareMediaInfo(textContent!!, fileName, fileUri)
                    }
                  },
                  colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                  ),
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Share,
                    contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.generic_share),
                  )
                }
              }
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
          ),
        )
      },
    ) { padding ->
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(padding),
      ) {
        when {
          isLoading -> LoadingContent()
          error != null -> ErrorContent(error!!)
          mediaInfo != null -> MediaInfoContent(mediaInfo!!, fileName, fullMediaInfoText)
        }
      }
    }
  }

  @Composable
  private fun LoadingContent() {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        CircularProgressIndicator(
          color = MaterialTheme.colorScheme.primary,
          strokeWidth = 4.dp,
          modifier = Modifier.size(48.dp),
        )
        Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_analyzing_media_file),
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }

  @Composable
  private fun ErrorContent(errorMessage: String) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      Card(
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Text(
          text = "Error: $errorMessage",
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onErrorContainer,
          modifier = Modifier.padding(24.dp),
        )
      }
    }
  }

  enum class InfoTab(@androidx.annotation.StringRes val displayNameRes: Int) {
    OVERVIEW(R.string.media_info_tab_overview),
    VIDEO(R.string.media_info_tab_video),
    AUDIO(R.string.media_info_tab_audio),
    SUBTITLES(R.string.media_info_tab_subtitles),
    CHAPTERS(R.string.media_info_tab_chapters),
  }

  @Composable
  private fun MediaInfoContent(
    mediaInfo: MediaInfoOps.MediaInfoData,
    fileName: String,
    fullMediaInfoText: String?
  ) {
    if (fullMediaInfoText == null) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator()
      }
      return
    }

    val sections = remember(fullMediaInfoText) { parseMediaInfoText(fullMediaInfoText) }

    // Group sections dynamically
    val videoSections = remember(sections) { sections.filter { it.name.startsWith("Video", ignoreCase = true) } }
    val audioSections = remember(sections) { sections.filter { it.name.startsWith("Audio", ignoreCase = true) } }
    val subtitleSections = remember(sections) {
      sections.filter {
        it.name.startsWith("Text", ignoreCase = true) ||
        it.name.startsWith("Subtitle", ignoreCase = true)
      }
    }
    val menuSections = remember(sections) {
      sections.filter {
        it.name.equals("Menu", ignoreCase = true) ||
        it.name.startsWith("Chapter", ignoreCase = true)
      }
    }

    // Determine available tabs
    val availableTabs = remember(sections) {
      buildList {
        add(InfoTab.OVERVIEW)
        if (videoSections.isNotEmpty()) add(InfoTab.VIDEO)
        if (audioSections.isNotEmpty()) add(InfoTab.AUDIO)
        if (subtitleSections.isNotEmpty()) add(InfoTab.SUBTITLES)
        if (menuSections.isNotEmpty()) add(InfoTab.CHAPTERS)
      }
    }

    var selectedTab by remember(availableTabs) { mutableStateOf(InfoTab.OVERVIEW) }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(
          brush = androidx.compose.ui.graphics.Brush.verticalGradient(
            colors = listOf(
              MaterialTheme.colorScheme.background,
              MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            )
          )
        )
    ) {
      // Tab Content
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .padding(horizontal = 16.dp)
      ) {
        when (selectedTab) {
          InfoTab.OVERVIEW -> OverviewTabContent(
            mediaInfo,
            fileName,
            sections,
            videoSections.size,
            audioSections.size,
            subtitleSections.size,
            menuSections.firstOrNull()?.properties?.size ?: 0
          )
          InfoTab.VIDEO -> StreamTabContent(videoSections, "Video Stream")
          InfoTab.AUDIO -> StreamTabContent(audioSections, "Audio Stream")
          InfoTab.SUBTITLES -> StreamTabContent(subtitleSections, "Subtitle Track")
          InfoTab.CHAPTERS -> ChaptersTabContent(menuSections)
        }
      }

      // Horizontal Scrollable Inspired Tab Bar
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        availableTabs.forEach { tab ->
          val isSelected = selectedTab == tab
          val containerColor by animateColorAsState(
            targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            label = "TabContainerColor"
          )
          val contentColor by animateColorAsState(
            targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            label = "TabContentColor"
          )

          Surface(
            onClick = { selectedTab = tab },
            color = containerColor,
            contentColor = contentColor,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.animateContentSize(),
            border = BorderStroke(
              1.dp,
              if (isSelected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
              horizontalArrangement = Arrangement.Center,
              verticalAlignment = Alignment.CenterVertically
            ) {
              val icon = when (tab) {
                InfoTab.OVERVIEW -> Icons.RoundedFilled.Info
                InfoTab.VIDEO -> Icons.RoundedFilled.Videocam
                InfoTab.AUDIO -> Icons.RoundedFilled.VolumeUp
                InfoTab.SUBTITLES -> Icons.RoundedFilled.Subtitles
                InfoTab.CHAPTERS -> Icons.RoundedFilled.ViewList
              }
              Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = stringResource(tab.displayNameRes),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
              )
            }
          }
        }
      }
    }
  }

  @Composable
  private fun GlassmorphicCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
    borderColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    content: @Composable ColumnScope.() -> Unit
  ) {
    Card(
      modifier = modifier,
      shape = RoundedCornerShape(24.dp),
      colors = CardDefaults.cardColors(containerColor = containerColor),
      border = BorderStroke(1.dp, borderColor),
      content = content
    )
  }

  @Composable
  private fun QuickStatCard(
    title: String,
    value: String,
    icon: app.gyrolet.mpvrx.ui.icons.AppIcon,
    accentColor: Color,
    modifier: Modifier = Modifier
  ) {
    val context = LocalContext.current
    GlassmorphicCard(
      modifier = modifier.clickable {
        SafeClipboard.copyPlainText(context, title, value)
        Toast.makeText(context, context.getString(R.string.toast_copied_value, value), Toast.LENGTH_SHORT).show()
      },
      containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)
    ) {
      Row(
        modifier = Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = accentColor.copy(alpha = 0.12f),
          contentColor = accentColor,
          modifier = Modifier.size(40.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = accentColor)
          }
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
    }
  }

  @Composable
  private fun HeroChipRow(chips: List<String>) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      chips.forEach { label ->
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
          contentColor = MaterialTheme.colorScheme.primary,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
        ) {
          Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
          )
        }
      }
    }
  }

  @Composable
  private fun OverviewTabContent(
    mediaInfo: MediaInfoOps.MediaInfoData,
    fileName: String,
    sections: List<InfoSection>,
    videoCount: Int,
    audioCount: Int,
    subtitleCount: Int,
    chapterCount: Int
  ) {
    // Quick Stat values
    val primaryVideo = mediaInfo.videoStreams.firstOrNull()
    val resolutionLabel = remember(primaryVideo) {
      if (primaryVideo != null) {
        val w = primaryVideo.width.filter { it.isDigit() }
        val h = primaryVideo.height.filter { it.isDigit() }
        if (h == "2160" || w == "3840") "4K UHD"
        else if (h == "1440" || w == "2560") "2K QHD"
        else if (h == "1080") "1080p FHD"
        else if (h == "720") "720p HD"
        else if (w.isNotEmpty() && h.isNotEmpty()) "${w}x${h}"
        else "Unknown"
      } else "No Video"
    }

    val sizeLabel = mediaInfo.general.fileSize.ifBlank { "Unknown" }
    val durationLabel = mediaInfo.general.duration.ifBlank { "Unknown" }
    val formatLabel = mediaInfo.general.format.ifBlank { "Unknown" }

    val heroChips = remember(mediaInfo) {
      buildList {
        primaryVideo?.let { v ->
          val w = v.width.filter { it.isDigit() }.toIntOrNull() ?: 0
          val h = v.height.filter { it.isDigit() }.toIntOrNull() ?: 0
          val res = when {
            w >= 3840 || h >= 2160 -> "4K UHD"
            w >= 2560 || h >= 1440 -> "2K QHD"
            w >= 1920 || h >= 1080 -> "1080p"
            w >= 1280 || h >= 720  -> "720p"
            else -> null
          }
          res?.let { add(it) }
          if (v.format.isNotBlank() && v.format != "---") add(v.format)
        }
        mediaInfo.audioStreams.firstOrNull()?.let { a ->
          if (a.format.isNotBlank() && a.format != "---") add(a.format)
        }
        if (sizeLabel != "Unknown") add(sizeLabel)
        if (durationLabel != "Unknown") add(durationLabel)
      }
    }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // Neon Header Banner
      GlassmorphicCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            contentColor = MaterialTheme.colorScheme.primary,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
          ) {
            Text(
              text = formatLabel.uppercase(),
              style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
          }

          Text(
            text = fileName,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      // Hero summary chips inline row
      if (heroChips.isNotEmpty()) {
        HeroChipRow(heroChips)
      }

      // Quick Specs Grid (2 columns)
      Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          QuickStatCard(
            title = stringResource(R.string.ui_resolution),
            value = resolutionLabel,
            icon = Icons.RoundedFilled.Videocam,
            accentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
          )
          QuickStatCard(
            title = stringResource(R.string.ui_file_size),
            value = sizeLabel,
            icon = Icons.RoundedFilled.SdCard,
            accentColor = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f)
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          QuickStatCard(
            title = stringResource(R.string.ui_duration),
            value = durationLabel,
            icon = Icons.RoundedFilled.Timer,
            accentColor = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f)
          )
          QuickStatCard(
            title = stringResource(R.string.ui_bitrate),
            value = mediaInfo.general.overallBitRate.ifBlank { "Unknown" },
            icon = Icons.RoundedFilled.Speed,
            accentColor = Color(0xFFFFB300),
            modifier = Modifier.weight(1f)
          )
        }
      }

      // Stream summary blocks
      GlassmorphicCard(
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_media_tracks_summary),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
          ) {
            TrackSummaryItem(videoCount, "Video", Icons.RoundedFilled.Videocam, MaterialTheme.colorScheme.primary)
            TrackSummaryItem(audioCount, "Audio", Icons.RoundedFilled.VolumeUp, MaterialTheme.colorScheme.secondary)
            TrackSummaryItem(subtitleCount, "Subtitle", Icons.RoundedFilled.Subtitles, MaterialTheme.colorScheme.tertiary)
            if (chapterCount > 0) {
              TrackSummaryItem(chapterCount, "Chapters", Icons.RoundedFilled.ViewList, Color(0xFFFFB300))
            }
          }
        }
      }

      // Detailed metadata list
      val generalSection = sections.firstOrNull { it.name.equals("General", ignoreCase = true) }
      if (generalSection != null) {
        GlassmorphicCard(
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_container_metadata),
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.primary
            )

            SelectionContainer {
              Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                generalSection.properties.forEach { (key, value) ->
                  // Avoid showing details already on Quick Stats to keep clean
                  if (key != "Format" && key != "File size" && key != "Duration" && key != "Overall bit rate") {
                    PropertyRow(key, value)
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  @Composable
  private fun TrackSummaryItem(
    count: Int,
    label: String,
    icon: app.gyrolet.mpvrx.ui.icons.AppIcon,
    color: Color
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        modifier = Modifier.size(48.dp)
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = color)
        }
      }
      Text(
        text = "$count $label",
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface
      )
    }
  }

  @Composable
  private fun StreamCard(
    title: String,
    badge: String?,
    icon: app.gyrolet.mpvrx.ui.icons.AppIcon,
    headerBgColor: Color,
    headerTextColor: Color,
    properties: List<Pair<String, String>>,
    modifier: Modifier = Modifier
  ) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    GlassmorphicCard(
      modifier = modifier.fillMaxWidth()
    ) {
      Column {
        // Dynamic strip header representing the track class, inspired by mpvFlux cards
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(headerBgColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Box(
            modifier = Modifier
              .size(32.dp)
              .background(
                color = headerTextColor.copy(alpha = 0.18f),
                shape = RoundedCornerShape(8.dp)
              ),
            contentAlignment = Alignment.Center,
          ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = headerTextColor)
          }
          Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = headerTextColor,
            modifier = Modifier.weight(1f)
          )
          if (badge != null) {
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = headerTextColor.copy(alpha = 0.15f)
            ) {
              Text(
                text = badge,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = headerTextColor
              )
            }
          }

          Box(
            modifier = Modifier
              .size(32.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(headerTextColor.copy(alpha = 0.15f))
              .clickable {
                scope.launch {
                  val content = properties.joinToString("\n") { "${it.first}: ${it.second}" }
                  SafeClipboard.copyPlainText(context, title, content)
                  Toast.makeText(context, context.getString(app.gyrolet.mpvrx.R.string.ui_copied_specifications_to_clipboard), Toast.LENGTH_SHORT).show()
                }
              },
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.ContentCopy,
              contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_copy_all),
              tint = headerTextColor.copy(alpha = 0.8f),
              modifier = Modifier.size(16.dp)
            )
          }
        }

        // Two-column chunked Stat Tiles inspired by the premium mpvFlux UI
        Column(
          modifier = Modifier.padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          val chunked = properties.chunked(2)
          chunked.forEach { pair ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              pair.forEach { (label, value) ->
                StatTile(
                  label = label,
                  value = value,
                  modifier = Modifier.weight(1f)
                )
              }
              if (pair.size == 1) {
                Spacer(modifier = Modifier.weight(1f))
              }
            }
          }
        }
      }
    }
  }

  @Composable
  private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
  ) {
    val context = LocalContext.current
    Surface(
      modifier = modifier.clickable {
        SafeClipboard.copyPlainText(context, label, value)
        Toast.makeText(context, context.getString(R.string.toast_copied_value, value), Toast.LENGTH_SHORT).show()
      },
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
      ) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = value,
          style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
          ),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }

  @Composable
  private fun StreamTabContent(
    sections: List<InfoSection>,
    streamTypeLabel: String
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      sections.forEachIndexed { index, section ->
        val format = section.properties.firstOrNull { it.first.equals("Format", ignoreCase = true) }?.second ?: "Unknown"
        val language = section.properties.firstOrNull { it.first.equals("Language", ignoreCase = true) }?.second

        val badgeLabel = if (language != null) "$format ($language)" else format

        val (headerBgColor, headerTextColor, icon) = when {
          streamTypeLabel.contains("Video", ignoreCase = true) -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.RoundedFilled.Videocam
          )
          streamTypeLabel.contains("Audio", ignoreCase = true) -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.RoundedFilled.VolumeUp
          )
          else -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.RoundedFilled.Subtitles
          )
        }

        val filteredProperties = section.properties.filter { it.first != "Format" }

        StreamCard(
          title = "$streamTypeLabel #${index + 1}",
          badge = badgeLabel,
          icon = icon,
          headerBgColor = headerBgColor,
          headerTextColor = headerTextColor,
          properties = filteredProperties
        )
      }
    }
  }

  @Composable
  private fun ChaptersTabContent(
    sections: List<InfoSection>
  ) {
    val menuSection = sections.firstOrNull() ?: return

    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      GlassmorphicCard(
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_video_chapters_timeline),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
          )

          Column(
            modifier = Modifier.padding(start = 8.dp)
          ) {
            menuSection.properties.forEachIndexed { index, (timestamp, rawName) ->
              val context = LocalContext.current
              val scope = rememberCoroutineScope()

              // Clean chapter name: strip leading ": en:" / ": " language prefix artifacts
              val chapterName = rawName
                .trimStart()
                .removePrefix(":")
                .trimStart()
                .let { s ->
                  // Strip "en:" / "und:" / "jpn:" etc. language tag if present at start
                  val langTagRegex = Regex("^[a-z]{2,3}:")
                  if (s.matches(Regex("^[a-z]{2,3}:.*"))) s.replaceFirst(langTagRegex, "").trimStart()
                  else s
                }
                .ifBlank { "Chapter ${index + 1}" }

              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .height(IntrinsicSize.Min)
                  .clickable {
                    scope.launch {
                      SafeClipboard.copyPlainText(context, "Chapter timestamp", timestamp)
                      Toast.makeText(context, context.getString(R.string.toast_copied_value, timestamp), Toast.LENGTH_SHORT).show()
                    }
                  }
                  .padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top
              ) {
                // Connected timeline — dot + line that dynamically fills row height
                Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  modifier = Modifier
                    .width(24.dp)
                    .fillMaxHeight()
                ) {
                  // Top padding so dot lines up with the chapter name text
                  Spacer(modifier = Modifier.height(3.dp))
                  Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.size(12.dp)
                  ) {}

                  if (index < menuSection.properties.size - 1) {
                    Spacer(
                      modifier = Modifier
                        .width(2.dp)
                        .weight(1f)  // stretches to fill remaining row height
                        .background(
                          brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                              MaterialTheme.colorScheme.primary,
                              MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)
                            )
                          )
                        )
                    )
                  }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Chapter name + timestamp chip stacked vertically
                Column(
                  modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 10.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                  Text(
                    text = chapterName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                  )
                  Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                  ) {
                    Text(
                      text = timestamp,
                      modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                      style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                      ),
                      color = MaterialTheme.colorScheme.primary
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

  @Composable
  private fun PropertyRow(label: String, value: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable {
          scope.launch {
            SafeClipboard.copyPlainText(context, label, value)
            Toast.makeText(context, context.getString(R.string.toast_copied_value, value), Toast.LENGTH_SHORT).show()
          }
        }
        .padding(vertical = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.Top,
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
          .weight(1f)
          .padding(end = 12.dp),
      )

      Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium.copy(
          fontFamily = FontFamily.Monospace
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1.5f),
      )
    }
  }

  private fun parseMediaInfoText(text: String): List<InfoSection> {
    val sections = mutableListOf<InfoSection>()
    var currentName: String? = null
    val currentProps = mutableListOf<Pair<String, String>>()
    text.lines().forEach { line ->
      val trimmed = line.trim()
      when {
        trimmed.isEmpty() || trimmed.startsWith("=") || line.contains("MEDIA INFO") -> {}
        !line.startsWith(" ") && !line.contains(" : ") -> {
          if (currentName != null && currentProps.isNotEmpty()) {
            sections.add(InfoSection(currentName, currentProps.toList()))
          }
          currentName = trimmed
          currentProps.clear()
        }
        line.contains(" : ") -> {
          val parts = line.split(" : ", limit = 2)
          if (parts.size == 2 && parts[0].trim().isNotEmpty() && parts[1].trim().isNotEmpty()) {
            currentProps.add(parts[0].trim() to parts[1].trim())
          }
        }
      }
    }
    if (currentName != null && currentProps.isNotEmpty()) {
      sections.add(InfoSection(currentName, currentProps.toList()))
    }
    return sections
  }

  private data class InfoSection(
    val name: String,
    val properties: List<Pair<String, String>>,
  )

  private suspend fun copyToClipboard(content: String, fileName: String) {
    withContext(Dispatchers.Main) {
      SafeClipboard.copyPlainText(
        context = this@MediaInfoActivity,
        label = "Media Info - $fileName",
        text = content,
      )
    }
  }

  private suspend fun shareMediaInfo(content: String, fileName: String, mediaUri: Uri?) {
    withContext(Dispatchers.IO) {
      try {
        val textFileName = "mediainfo_${fileName.substringBeforeLast('.')}.txt"
        val file = File(cacheDir, textFileName)
        file.writeText(content)

        withContext(Dispatchers.Main) {
          val fileUri = FileProvider.getUriForFile(
            this@MediaInfoActivity,
            "${packageName}.provider",
            file,
          )

          val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_SUBJECT, "Media Info - $fileName")
            putExtra(Intent.EXTRA_TEXT, "Media information for: $fileName")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }

          startActivity(Intent.createChooser(shareIntent, "Share Media Info"))
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          Toast.makeText(
            this@MediaInfoActivity,
            "Failed to share: ${e.message}",
            Toast.LENGTH_LONG,
          ).show()
        }
      }
    }
  }
}
