/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.mediainfo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
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
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.ConnectedInfoStatCard
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.MediaFormatBadges
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.resolveLocalPath
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppConnectedControlShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.DarkMode
import app.gyrolet.mpvrx.ui.theme.MpvrxTheme
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.utils.clipboard.SafeClipboard
import app.gyrolet.mpvrx.utils.media.MediaInfoOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import java.io.File

private data class ValueDetailSelection(
  val label: String,
  val value: String,
)

private val LocalValueDetailRequest =
  staticCompositionLocalOf<(String, String) -> Unit> {
    error("No value-detail dialog host")
  }

class MediaInfoActivity : AppCompatActivity() {
  private val appearancePreferences by inject<AppearancePreferences>()

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
          MediaInfoScreen(onBack = { finish() })
        }
      }
    }
  }

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  private fun MediaInfoScreen(
    onBack: () -> Unit,
  ) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val fallbackTitle = stringResource(R.string.ui_media_info)
    var fileName by remember { mutableStateOf(fallbackTitle) }
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var report by remember { mutableStateOf<MediaInfoOps.Report?>(null) }

    LaunchedEffect(Unit) {
      val uri =
        when (intent?.action) {
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
        return@LaunchedEffect
      }

      fileUri = uri
      fileName = mediaInfoDisplayName(context, uri, fallbackTitle)
      MediaInfoOps.getMediaInfoReport(context, uri, fileName)
        .onSuccess { report = it }
        .onFailure { error = it.message ?: "Failed to load media information" }
    }

    val loaded = report
    val failure = error
    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Column {
              Text(
                text =
                  androidx.compose.ui.res
                    .stringResource(app.gyrolet.mpvrx.R.string.ui_media_info),
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
              Icon(
                Icons.RoundedFilled.ArrowBack,
                contentDescription =
                  androidx.compose.ui.res
                    .stringResource(app.gyrolet.mpvrx.R.string.back),
              )
            }
          },
          actions = {
            if (loaded != null) {
              Row(modifier = Modifier.padding(end = 12.dp)) {
                FilledTonalIconButton(
                  onClick = {
                    scope.launch {
                      SafeClipboard.copyPlainText(context, "Media Info - $fileName", loaded.rawText)
                    }
                  },
                  colors =
                    IconButtonDefaults.filledTonalIconButtonColors(
                      containerColor = MaterialTheme.colorScheme.secondaryContainer,
                      contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.ContentCopy,
                    contentDescription =
                      androidx.compose.ui.res
                        .stringResource(app.gyrolet.mpvrx.R.string.ui_copy),
                  )
                }

                Spacer(modifier = Modifier.width(8.dp))

                FilledTonalIconButton(
                  onClick = {
                    scope.launch {
                      shareMediaInfo(loaded.rawText, fileName)
                    }
                  },
                  colors =
                    IconButtonDefaults.filledTonalIconButtonColors(
                      containerColor = MaterialTheme.colorScheme.secondaryContainer,
                      contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Share,
                    contentDescription =
                      androidx.compose.ui.res.stringResource(
                        app.gyrolet.mpvrx.R.string.generic_share,
                      ),
                  )
                }
              }
            }
          },
          colors =
            TopAppBarDefaults.topAppBarColors(
              containerColor = MaterialTheme.colorScheme.surface,
              titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
      },
    ) { padding ->
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(padding),
      ) {
        when {
          failure != null -> MediaInfoDetails.ErrorContent(failure)
          loaded != null -> MediaInfoDetails.Content(loaded, fileName, fileUri)
          else -> MediaInfoDetails.LoadingContent()
        }
      }
    }
  }

  private suspend fun shareMediaInfo(content: String, fileName: String) {
    withContext(Dispatchers.IO) {
      try {
        val safeName = fileName.substringBeforeLast('.').replace(Regex("""[\\/:*?"<>|]"""), "_")
        val file = File(cacheDir, "mediainfo_$safeName.txt")
        file.writeText(content)

        withContext(Dispatchers.Main) {
          val fileUri = FileProvider.getUriForFile(this@MediaInfoActivity, "$packageName.provider", file)
          val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_SUBJECT, "Media Info - $fileName")
            putExtra(Intent.EXTRA_TEXT, "Media information for: $fileName")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
          startActivity(Intent.createChooser(shareIntent, "Share Media Info"))
        }
      } catch (error: java.io.IOException) {
        withContext(Dispatchers.Main) {
          Toast.makeText(this@MediaInfoActivity, "Failed to share: ${error.message}", Toast.LENGTH_LONG).show()
        }
      }
    }
  }
}

internal suspend fun mediaInfoDisplayName(context: Context, uri: Uri, fallback: String): String =
  withContext(Dispatchers.IO) {
    val name = if (uri.scheme == "content") {
      try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
          val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
      } catch (error: SecurityException) {
        Log.w("MediaInfo", "File name is not accessible", error)
        null
      } catch (error: IllegalArgumentException) {
        Log.w("MediaInfo", "Provider does not expose a display name", error)
        null
      }
    } else null
    name?.takeIf(String::isNotBlank) ?: uri.lastPathSegment?.takeIf(String::isNotBlank) ?: fallback
  }

internal object MediaInfoDetails {
  @Composable
  fun LoadingContent() {
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
        Text(
          text =
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_analyzing_media_file),
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }

  @Composable
  fun ErrorContent(errorMessage: String) {
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      Card(
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
          ),
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Text(
          text = errorMessage,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onErrorContainer,
          modifier = Modifier.padding(24.dp),
        )
      }
    }
  }

  private enum class InfoTab(
    @androidx.annotation.StringRes val displayNameRes: Int,
  ) {
    OVERVIEW(R.string.media_info_tab_overview),
    VIDEO(R.string.media_info_tab_video),
    AUDIO(R.string.media_info_tab_audio),
    SUBTITLES(R.string.media_info_tab_subtitles),
    IMAGE(R.string.media_info_tab_image),
    CHAPTERS(R.string.media_info_tab_chapters),
    OTHER(R.string.media_info_tab_other),
    RAW(R.string.media_info_tab_raw),
  }

  @Composable
  fun Content(
    report: MediaInfoOps.Report,
    fileName: String,
    fileUri: Uri?,
    inPlayer: Boolean = false,
    playbackSnapshot: Boolean = false,
  ) {
    var valueDetail by remember { mutableStateOf<ValueDetailSelection?>(null) }
    CompositionLocalProvider(
      LocalValueDetailRequest provides { label, value -> valueDetail = ValueDetailSelection(label, value) },
    ) {
      MediaInfoTabs(report.data, fileName, report.rawText, fileUri, inPlayer, playbackSnapshot)
    }
    valueDetail?.let { detail ->
      ValueDetailDialog(label = detail.label, value = detail.value, onDismiss = { valueDetail = null })
    }
  }

  @Composable
  private fun MediaInfoTabs(
    mediaInfo: MediaInfoOps.MediaInfoData,
    fileName: String,
    fullMediaInfoText: String,
    fileUri: Uri?,
    inPlayer: Boolean,
    playbackSnapshot: Boolean,
  ) {
    val context = LocalContext.current
    val filePath =
      remember(fileUri) {
        fileUri?.let { uri -> uri.resolveLocalPath(context) ?: uri.toString() }
      }
    val sections = remember(fullMediaInfoText) { parseMediaInfoText(fullMediaInfoText) }

    // Group sections dynamically
    val videoSections = remember(sections) { sections.filter { it.name.startsWith("Video", ignoreCase = true) } }
    val audioSections = remember(sections) { sections.filter { it.name.startsWith("Audio", ignoreCase = true) } }
    val subtitleSections =
      remember(sections) {
        sections.filter {
          it.name.startsWith("Text", ignoreCase = true) ||
            it.name.startsWith("Subtitle", ignoreCase = true)
        }
      }
    val imageSections = remember(sections) { sections.filter { it.name.startsWith("Image", ignoreCase = true) } }
    val menuSections =
      remember(sections) {
        sections.filter {
          it.name.startsWith("Menu", ignoreCase = true) ||
            it.name.startsWith("Chapter", ignoreCase = true)
        }
      }
    // Timecode tracks, programs, and any section kind a future MediaInfo release adds.
    val otherSections =
      remember(sections) {
        sections.filter { section ->
          !section.name.equals("General", ignoreCase = true) &&
            !section.name.startsWith("Video", ignoreCase = true) &&
            !section.name.startsWith("Audio", ignoreCase = true) &&
            !section.name.startsWith("Text", ignoreCase = true) &&
            !section.name.startsWith("Subtitle", ignoreCase = true) &&
            !section.name.startsWith("Image", ignoreCase = true) &&
            !section.name.startsWith("Menu", ignoreCase = true) &&
            !section.name.startsWith("Chapter", ignoreCase = true)
        }
      }
    val attachmentNames =
      remember(sections) {
        sections
          .firstOrNull { it.name.equals("General", ignoreCase = true) }
          ?.properties
          ?.firstOrNull { it.first.equals("Attachments", ignoreCase = true) || it.first.equals("Attachment", ignoreCase = true) }
          ?.second
          ?.split(" / ")
          ?.map(String::trim)
          ?.filter(String::isNotEmpty)
          .orEmpty()
      }

    // Determine available tabs
    val availableTabs =
      remember(sections) {
        buildList {
          add(InfoTab.OVERVIEW)
          if (videoSections.isNotEmpty()) add(InfoTab.VIDEO)
          if (audioSections.isNotEmpty()) add(InfoTab.AUDIO)
          if (subtitleSections.isNotEmpty()) add(InfoTab.SUBTITLES)
          if (imageSections.isNotEmpty()) add(InfoTab.IMAGE)
          if (menuSections.isNotEmpty()) add(InfoTab.CHAPTERS)
          if (otherSections.isNotEmpty() || attachmentNames.isNotEmpty()) add(InfoTab.OTHER)
          add(InfoTab.RAW)
        }
      }

    var selectedTab by remember(availableTabs) { mutableStateOf(InfoTab.OVERVIEW) }

    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .then(if (inPlayer) Modifier else Modifier.background(MaterialTheme.colorScheme.surface)),
    ) {
      // Tab Content
      Box(
        modifier =
          Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
      ) {
        when (selectedTab) {
          InfoTab.OVERVIEW ->
            OverviewTabContent(
              mediaInfo,
              fileName,
              sections,
              videoSections.size,
              audioSections.size,
              subtitleSections.size,
              menuSections.firstOrNull()?.properties?.size ?: 0,
              filePath,
              playbackSnapshot,
            )
          InfoTab.VIDEO -> StreamTabContent(videoSections, "Video Stream")
          InfoTab.AUDIO -> StreamTabContent(audioSections, "Audio Stream")
          InfoTab.SUBTITLES -> StreamTabContent(subtitleSections, "Subtitle Track")
          InfoTab.IMAGE -> StreamTabContent(imageSections, "Image")
          InfoTab.CHAPTERS -> ChaptersTabContent(menuSections)
          InfoTab.OTHER -> OtherTabContent(otherSections, attachmentNames)
          InfoTab.RAW -> RawTabContent(fullMediaInfoText)
        }
      }

      // Horizontal Scrollable Inspired Tab Bar
      Row(
        modifier =
          Modifier
            .align(if (inPlayer) Alignment.CenterHorizontally else Alignment.End)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = if (inPlayer) 4.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(AppConnectedControlShapeTokens.spacing),
      ) {
        availableTabs.forEachIndexed { index, tab ->
          val tabShape = rememberConnectedShape(
            index = index,
            itemCount = availableTabs.size,
            layout = ConnectedLayout.Horizontal,
            tokens = AppConnectedControlShapeTokens,
          )
          val isSelected = selectedTab == tab
          val containerColor by animateColorAsState(
            targetValue =
              if (isSelected) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.surfaceVariant
                  .copy(
                    alpha = 0.45f,
                  )
              },
            label = "TabContainerColor",
          )
          val contentColor by animateColorAsState(
            targetValue =
              if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            label = "TabContentColor",
          )

          Surface(
            onClick = { selectedTab = tab },
            color = containerColor,
            contentColor = contentColor,
            shape = tabShape,
            modifier = Modifier
              .semantics { selected = isSelected }
              .tvFocusHighlight(tabShape)
              .animateContentSize(),
          ) {
            Row(
              modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 6.dp),
              horizontalArrangement = Arrangement.Center,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              val icon =
                when (tab) {
                  InfoTab.OVERVIEW -> Icons.RoundedFilled.Info
                  InfoTab.VIDEO -> Icons.RoundedFilled.Videocam
                  InfoTab.AUDIO -> Icons.RoundedFilled.VolumeUp
                  InfoTab.SUBTITLES -> Icons.RoundedFilled.Subtitles
                  InfoTab.IMAGE -> Icons.RoundedFilled.Palette
                  InfoTab.CHAPTERS -> Icons.RoundedFilled.ViewList
                  InfoTab.OTHER -> Icons.RoundedFilled.Tune
                  InfoTab.RAW -> Icons.RoundedFilled.Article
                }
              Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = stringResource(tab.displayNameRes),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
              )
            }
          }
        }
      }
    }
  }

  @Composable
  private fun ExpressiveInfoCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    content: @Composable ColumnScope.() -> Unit,
  ) {
    Card(
      modifier = modifier,
      shape = shape,
      colors = CardDefaults.cardColors(containerColor = containerColor),
      content = content,
    )
  }

  @Composable
  private fun QuickStatCard(
    title: String,
    value: String,
    icon: app.gyrolet.mpvrx.ui.icons.AppIcon,
    accentColor: Color,
    index: Int,
    modifier: Modifier = Modifier,
  ) {
    val context = LocalContext.current
    ConnectedInfoStatCard(
      title = title,
      value = value,
      icon = icon,
      accentColor = accentColor,
      index = index,
      itemCount = 4,
      columns = 2,
      onClick = {
        SafeClipboard.copyPlainText(context, title, value)
      },
      modifier = modifier,
    )
  }

  @Composable
  private fun HeroChipRow(chips: List<String>) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      chips.forEach { label ->
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
          contentColor = MaterialTheme.colorScheme.primary,
        ) {
          Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
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
    chapterCount: Int,
    filePath: String?,
    playbackSnapshot: Boolean,
  ) {
    // Quick Stat values
    val primaryVideo = mediaInfo.videoStreams.firstOrNull()
    val primaryAudio = mediaInfo.audioStreams.firstOrNull()
    val imageSection = remember(sections) { sections.firstOrNull { it.name.startsWith("Image", ignoreCase = true) } }
    val isImageFile = imageSection != null && primaryVideo == null && primaryAudio == null
    val isAudioFile = primaryAudio != null && primaryVideo == null

    fun imageValue(key: String): String =
      imageSection?.properties?.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second.orEmpty()

    val imageResolution =
      remember(imageSection) {
        val w = imageValue("Width").filter(Char::isDigit)
        val h = imageValue("Height").filter(Char::isDigit)
        if (w.isNotEmpty() && h.isNotEmpty()) "${w}x$h" else ""
      }
    val resolutionLabel =
      remember(primaryVideo) {
        if (primaryVideo != null) {
          val w = primaryVideo.width.filter { it.isDigit() }
          val h = primaryVideo.height.filter { it.isDigit() }
          if (h == "2160" || w == "3840") {
            "4K UHD"
          } else if (h == "1440" || w == "2560") {
            "2K QHD"
          } else if (h == "1080") {
            "1080p FHD"
          } else if (h == "720") {
            "720p HD"
          } else if (w.isNotEmpty() && h.isNotEmpty()) {
            "${w}x$h"
          } else {
            "Unknown"
          }
        } else {
          "No Video"
        }
      }

    val sizeLabel = mediaInfo.general.fileSize.ifBlank { "Unknown" }
    val durationLabel = mediaInfo.general.duration.ifBlank { "Unknown" }
    val formatLabel = mediaInfo.general.format.ifBlank { "Unknown" }

    val colorizeBadges by koinInject<AppearancePreferences>().colorizeMediaFormatBadges.collectAsState()
    val formatBadges = remember(mediaInfo, fileName) { mediaInfo.overviewBadges(fileName) }
    val heroChips =
      remember(mediaInfo, sections) {
        buildList {
          if (isImageFile) {
            imageValue("Format").takeIf(String::isNotBlank)?.let { add(it) }
            if (imageResolution.isNotBlank()) add(imageResolution)
          }
        }
      }

    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Strong, readable Material Expressive hero anchored by the detected media type.
      ExpressiveInfoCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(20.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.primary,
          ) {
            Box(modifier = Modifier.size(60.dp), contentAlignment = Alignment.Center) {
              Icon(
                imageVector = when {
                  isImageFile -> Icons.RoundedFilled.Palette
                  isAudioFile -> Icons.RoundedFilled.Audiotrack
                  else -> Icons.RoundedFilled.Videocam
                },
                contentDescription = null,
                modifier = Modifier.size(30.dp),
              )
            }
          }
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Surface(
              shape = MaterialTheme.shapes.small,
              color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
              contentColor = MaterialTheme.colorScheme.primary,
            ) {
              Text(
                text = formatLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
              )
            }
            Text(
              text = fileName,
              style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      if (playbackSnapshot) {
        Text(
          text = stringResource(R.string.media_info_playback_snapshot),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (formatBadges.isNotEmpty()) {
        val background = MaterialTheme.colorScheme.surfaceContainerLow
        Surface(shape = MaterialTheme.shapes.large, color = background) {
          MediaFormatBadges(
            badges = formatBadges,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            background = background,
            colorize = colorizeBadges,
            rowSpacing = 8.dp,
          )
        }
      }
      if (heroChips.isNotEmpty()) {
        HeroChipRow(heroChips)
      }

      // Quick Specs Grid (2 columns), adapted to the media kind so images and music never
      // show placeholder values such as "No Video".
      Column(
        verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
      ) {
        when {
          isImageFile -> {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
            ) {
              QuickStatCard(
                index = 0,
                title = stringResource(R.string.ui_resolution),
                value = imageResolution.ifBlank { "Unknown" },
                icon = Icons.RoundedFilled.Palette,
                accentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
              )
              QuickStatCard(
                index = 1,
                title = stringResource(R.string.media_info_stat_format),
                value = imageValue("Format").ifBlank { formatLabel },
                icon = Icons.RoundedFilled.Info,
                accentColor = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
              )
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
            ) {
              QuickStatCard(
                index = 2,
                title = stringResource(R.string.media_info_stat_bit_depth),
                value = imageValue("Bit depth").ifBlank { "Unknown" },
                icon = Icons.RoundedFilled.Tune,
                accentColor = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
              )
              QuickStatCard(
                index = 3,
                title = stringResource(R.string.ui_file_size),
                value = sizeLabel,
                icon = Icons.RoundedFilled.SdCard,
                accentColor = Color(0xFFFFB300),
                modifier = Modifier.weight(1f),
              )
            }
          }
          isAudioFile -> {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
            ) {
              QuickStatCard(
                index = 0,
                title = stringResource(R.string.ui_duration),
                value = durationLabel,
                icon = Icons.RoundedFilled.Timer,
                accentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
              )
              QuickStatCard(
                index = 1,
                title = stringResource(R.string.media_info_stat_channels),
                value = primaryAudio.channels.ifBlank { "Unknown" },
                icon = Icons.RoundedFilled.VolumeUp,
                accentColor = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
              )
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
            ) {
              QuickStatCard(
                index = 2,
                title = stringResource(R.string.media_info_stat_sample_rate),
                value = primaryAudio.samplingRate.ifBlank { "Unknown" },
                icon = Icons.RoundedFilled.Tune,
                accentColor = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
              )
              QuickStatCard(
                index = 3,
                title = stringResource(R.string.ui_bitrate),
                value = mediaInfo.general.overallBitRate.ifBlank { "Unknown" },
                icon = Icons.RoundedFilled.Speed,
                accentColor = Color(0xFFFFB300),
                modifier = Modifier.weight(1f),
              )
            }
          }
          else -> {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
            ) {
              QuickStatCard(
                index = 0,
                title = stringResource(R.string.ui_resolution),
                value = resolutionLabel,
                icon = Icons.RoundedFilled.Videocam,
                accentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
              )
              QuickStatCard(
                index = 1,
                title = stringResource(R.string.ui_file_size),
                value = sizeLabel,
                icon = Icons.RoundedFilled.SdCard,
                accentColor = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
              )
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
            ) {
              QuickStatCard(
                index = 2,
                title = stringResource(R.string.ui_duration),
                value = durationLabel,
                icon = Icons.RoundedFilled.Timer,
                accentColor = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
              )
              QuickStatCard(
                index = 3,
                title = stringResource(R.string.ui_bitrate),
                value = mediaInfo.general.overallBitRate.ifBlank { "Unknown" },
                icon = Icons.RoundedFilled.Speed,
                accentColor = Color(0xFFFFB300),
                modifier = Modifier.weight(1f),
              )
            }
          }
        }
      }

      // Stream summary blocks; only kinds that exist appear, so images and music never
      // display irrelevant zero-count rows.
      val imageCount = remember(sections) { sections.count { it.name.startsWith("Image", ignoreCase = true) } }
      if (videoCount + audioCount + subtitleCount + chapterCount + imageCount > 0) {
        ExpressiveInfoCard(
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            Text(
              text =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_media_tracks_summary),
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.primary,
            )

            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              if (videoCount > 0) {
                TrackSummaryItem(videoCount, "Video", Icons.RoundedFilled.Videocam, MaterialTheme.colorScheme.primary)
              }
              if (audioCount > 0) {
                TrackSummaryItem(audioCount, "Audio", Icons.RoundedFilled.VolumeUp, MaterialTheme.colorScheme.secondary)
              }
              if (subtitleCount > 0) {
                TrackSummaryItem(
                  subtitleCount,
                  "Subtitle",
                  Icons.RoundedFilled.Subtitles,
                  MaterialTheme.colorScheme.tertiary,
                )
              }
              if (imageCount > 0) {
                TrackSummaryItem(imageCount, "Image", Icons.RoundedFilled.Palette, MaterialTheme.colorScheme.secondary)
              }
              if (chapterCount > 0) {
                TrackSummaryItem(chapterCount, "Chapters", Icons.RoundedFilled.ViewList, Color(0xFFFFB300))
              }
            }
          }
        }
      }

      // Detailed metadata list: every General field MediaInfo reports, plus the resolved path.
      val generalSection = sections.firstOrNull { it.name.equals("General", ignoreCase = true) }
      if (generalSection != null || filePath != null) {
        ExpressiveInfoCard(
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_container_metadata),
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.primary,
            )

            val pathLabel = stringResource(R.string.media_info_file_path)
            val generalEntries = buildList<Pair<String, String>> {
              filePath?.let { add(pathLabel to it) }
              generalSection?.properties?.let { addAll(it) }
            }
            SelectionContainer {
              Column(verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing)) {
                generalEntries.forEachIndexed { index, (key, value) ->
                  val itemShape = rememberConnectedShape(
                    index = index,
                    itemCount = generalEntries.size,
                    layout = ConnectedLayout.Vertical,
                  )
                  Surface(
                    shape = itemShape,
                    color = mediaInfoDetailContainerColor(),
                  ) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
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
  }

  @Composable
  private fun TrackSummaryItem(
    count: Int,
    label: String,
    icon: app.gyrolet.mpvrx.ui.icons.AppIcon,
    color: Color,
  ) {
    Surface(
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Surface(
          shape = MaterialTheme.shapes.medium,
          color = color.copy(alpha = 0.12f),
        ) {
          Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
          }
        }
        Text(
          text = "$count $label",
          style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
      }
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
    modifier: Modifier = Modifier,
  ) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    ExpressiveInfoCard(
      modifier = modifier.fillMaxWidth(),
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
      Column {
        // High-contrast semantic header instead of another near-identical dark surface.
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .background(headerBgColor)
              .padding(horizontal = 16.dp, vertical = 16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Box(
            modifier =
              Modifier
                .size(40.dp)
                .background(
                  color = headerTextColor.copy(alpha = 0.13f),
                  shape = MaterialTheme.shapes.medium,
                ),
            contentAlignment = Alignment.Center,
          ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = headerTextColor)
          }
          // Keep the title and format together, rather than forcing the title to wrap
          // around a wide codec/language badge and the trailing copy action.
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(
              text = title,
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = headerTextColor,
            )
            if (badge != null) {
              Surface(
                shape = MaterialTheme.shapes.medium,
                color = headerTextColor.copy(alpha = 0.12f),
              ) {
                Text(
                  text = badge,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                  style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                  color = headerTextColor,
                  softWrap = true,
                )
              }
            }
          }

          Box(
            modifier =
              Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(headerTextColor.copy(alpha = 0.12f))
                .clickable {
                  scope.launch {
                    val content = properties.joinToString("\n") { "${it.first}: ${it.second}" }
                    SafeClipboard.copyPlainText(context, title, content)
                  }
                },
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.ContentCopy,
              contentDescription =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_copy_all),
              tint = headerTextColor.copy(alpha = 0.8f),
              modifier = Modifier.size(16.dp),
            )
          }
        }

        // Each row grows to its content's natural intrinsic height. Both tiles share
        // that height, so multi-line codec values cannot leave staggered/broken borders.
        // On small screens we use a single full-width column, not compressed text.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
          val columnCount = if (maxWidth < 300.dp) 1 else 2
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
          ) {
            properties.chunked(columnCount).forEachIndexed { rowIndex, pair ->
              Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
              ) {
                pair.forEachIndexed { columnIndex, (label, value) ->
                  val isWide = pair.size == 1
                  StatTile(
                    label = label,
                    value = value,
                    index = rowIndex * columnCount + columnIndex,
                    itemCount = properties.size,
                    columns = if (isWide) 1 else columnCount,
                    modifier = if (isWide) {
                      Modifier.fillMaxWidth().fillMaxHeight()
                    } else {
                      Modifier.weight(1f).fillMaxHeight()
                    },
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
  private fun StatTile(
    label: String,
    value: String,
    index: Int,
    itemCount: Int,
    columns: Int,
    modifier: Modifier = Modifier,
  ) {
    val requestValueDetail = LocalValueDetailRequest.current
    val shape =
      rememberConnectedShape(
        index = index,
        itemCount = itemCount,
        layout = if (columns == 1) ConnectedLayout.Vertical else ConnectedLayout.Grid,
        columns = columns,
      )
    Surface(
      modifier = modifier.clip(shape).clickable { requestValueDetail(label, value) },
      shape = shape,
      color = mediaInfoDetailContainerColor(),
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          softWrap = true,
        )
        Text(
          text = value,
          style =
            MaterialTheme.typography.bodyMedium.copy(
              fontWeight = FontWeight.SemiBold,
              fontFamily = FontFamily.Monospace,
            ),
          color = MaterialTheme.colorScheme.onSurface,
          softWrap = true,
        )
      }
    }
  }

  @Composable
  private fun StreamTabContent(
    sections: List<InfoSection>,
    streamTypeLabel: String,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      sections.forEachIndexed { index, section ->
        val format =
          section.properties.firstOrNull { it.first.equals("Format", ignoreCase = true) }?.second ?: "Unknown"
        val language = section.properties.firstOrNull { it.first.equals("Language", ignoreCase = true) }?.second

        val badgeLabel = if (language != null) "$format ($language)" else format

        val (headerBgColor, headerTextColor, icon) =
          when {
            streamTypeLabel.contains("Video", ignoreCase = true) ->
              Triple(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.onPrimary,
                Icons.RoundedFilled.Videocam,
              )
            streamTypeLabel.contains("Audio", ignoreCase = true) ->
              Triple(
                MaterialTheme.colorScheme.tertiary,
                MaterialTheme.colorScheme.onTertiary,
                Icons.RoundedFilled.VolumeUp,
              )
            streamTypeLabel.contains("Image", ignoreCase = true) ->
              Triple(
                MaterialTheme.colorScheme.secondary,
                MaterialTheme.colorScheme.onSecondary,
                Icons.RoundedFilled.Palette,
              )
            else ->
              Triple(
                MaterialTheme.colorScheme.secondary,
                MaterialTheme.colorScheme.onSecondary,
                Icons.RoundedFilled.Subtitles,
              )
          }

        StreamCard(
          title = "$streamTypeLabel #${index + 1}",
          badge = badgeLabel,
          icon = icon,
          headerBgColor = headerBgColor,
          headerTextColor = headerTextColor,
          properties = section.properties,
        )
      }
    }
  }

  @Composable
  private fun ChaptersTabContent(sections: List<InfoSection>) {
    val menuSection = sections.firstOrNull() ?: return

    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      ExpressiveInfoCard(
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Text(
            text =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_video_chapters_timeline),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
          )

          Column(
            modifier = Modifier.padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
          ) {
            menuSection.properties.forEachIndexed { index, (timestamp, rawName) ->
              val context = LocalContext.current
              val scope = rememberCoroutineScope()

              // Clean chapter name: strip leading ": en:" / ": " language prefix artifacts
              val chapterName =
                rawName
                  .trimStart()
                  .removePrefix(":")
                  .trimStart()
                  .let { s ->
                    // Strip "en:" / "und:" / "jpn:" etc. language tag if present at start
                    val langTagRegex = Regex("^[a-z]{2,3}:")
                    if (s.matches(Regex("^[a-z]{2,3}:.*"))) {
                      s.replaceFirst(langTagRegex, "").trimStart()
                    } else {
                      s
                    }
                  }.ifBlank { "Chapter ${index + 1}" }

              val chapterShape = rememberConnectedShape(
                index = index,
                itemCount = menuSection.properties.size,
                layout = ConnectedLayout.Vertical,
              )
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clip(chapterShape)
                    .background(mediaInfoDetailContainerColor())
                    .clickable {
                      scope.launch {
                        SafeClipboard.copyPlainText(context, "Chapter timestamp", timestamp)
                      }
                    }.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
              ) {
                // Connected timeline — dot + line that dynamically fills row height
                Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  modifier =
                    Modifier
                      .width(24.dp)
                      .fillMaxHeight(),
                ) {
                  // Top padding so dot lines up with the chapter name text
                  Spacer(modifier = Modifier.height(3.dp))
                  Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.size(12.dp),
                  ) {}

                  if (index < menuSection.properties.size - 1) {
                    Spacer(
                      modifier =
                        Modifier
                          .width(2.dp)
                          .weight(1f) // stretches to fill remaining row height
                          .background(
                            brush =
                              androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors =
                                  listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f),
                                  ),
                              ),
                          ),
                    )
                  }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Chapter name + timestamp chip stacked vertically
                Column(
                  modifier =
                    Modifier
                      .weight(1f)
                      .padding(bottom = 10.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  Text(
                    text = chapterName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                  ) {
                    Text(
                      text = timestamp,
                      modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                      style =
                        MaterialTheme.typography.bodySmall.copy(
                          fontFamily = FontFamily.Monospace,
                          fontWeight = FontWeight.Bold,
                        ),
                      color = MaterialTheme.colorScheme.primary,
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
  private fun PropertyRow(
    label: String,
    value: String,
  ) {
    val requestValueDetail = LocalValueDetailRequest.current

    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .clickable { requestValueDetail(label, value) }
          .padding(vertical = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.Top,
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
          Modifier
            .weight(1f)
            .padding(end = 12.dp),
      )

      Text(
        text = value,
        style =
          MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
          ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1.5f),
      )
    }
  }

  /** Full-value reader for truncated fields: scrollable, selectable, and copyable. */
  @Composable
  private fun ValueDetailDialog(
    label: String,
    value: String,
    onDismiss: () -> Unit,
  ) {
    val context = LocalContext.current
    AlertDialog(
      onDismissRequest = onDismiss,
      title = {
        Text(
          text = label,
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
      },
      text = {
        SelectionContainer {
          Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier =
              Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            SafeClipboard.copyPlainText(context, label, value)
            onDismiss()
          },
        ) {
          Text(stringResource(R.string.ui_copy))
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) {
          Text(stringResource(R.string.generic_cancel))
        }
      },
    )
  }

  @Composable
  private fun OtherTabContent(
    sections: List<InfoSection>,
    attachments: List<String>,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      if (attachments.isNotEmpty()) {
        ExpressiveInfoCard(modifier = Modifier.fillMaxWidth()) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = stringResource(R.string.media_info_attachments),
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.primary,
            )
            SelectionContainer {
              Column(
                verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
              ) {
                attachments.forEachIndexed { index, name ->
                  val shape = rememberConnectedShape(
                    index = index,
                    itemCount = attachments.size,
                    layout = ConnectedLayout.Vertical,
                  )
                  Surface(
                    shape = shape,
                    color = mediaInfoDetailContainerColor(),
                  ) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                      PropertyRow("#${index + 1}", name)
                    }
                  }
                }
              }
            }
          }
        }
      }

      sections.forEach { section ->
        StreamCard(
          title = section.name,
          badge = null,
          icon = Icons.RoundedFilled.Tune,
          headerBgColor = MaterialTheme.colorScheme.secondary,
          headerTextColor = MaterialTheme.colorScheme.onSecondary,
          properties = section.properties,
        )
      }
    }
  }

  /**
   * The raw report remains byte-for-byte unchanged for copying/sharing, but renders as a
   * virtualized code reader with clearly differentiated headings, labels and values.
   * Rendering per-line avoids building one huge AnnotatedString for long reports.
   */
  @Composable
  private fun RawTabContent(rawText: String) {
    val context = LocalContext.current
    val lines = remember(rawText) { rawText.lines() }
    val sectionCount = remember(rawText) { parseMediaInfoText(rawText).size }
    val colors = MaterialTheme.colorScheme

    Column(
      modifier = Modifier.fillMaxSize().padding(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Surface(
            shape = MaterialTheme.shapes.large,
            color = colors.onPrimaryContainer.copy(alpha = 0.12f),
          ) {
            Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.RoundedFilled.Article,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
              )
            }
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.media_info_tab_raw),
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Text(
              text = "${lines.size} lines  •  ${sectionCount} sections",
              style = MaterialTheme.typography.labelMedium,
              color = colors.onPrimaryContainer.copy(alpha = 0.76f),
            )
          }
          Surface(
            onClick = {
              SafeClipboard.copyPlainText(context, "MediaInfo", rawText)
            },
            shape = MaterialTheme.shapes.large,
            color = colors.onPrimaryContainer.copy(alpha = 0.13f),
            contentColor = colors.onPrimaryContainer,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Text(
                text = stringResource(R.string.ui_copy_all),
                style = MaterialTheme.typography.labelLarge,
              )
            }
          }
        }
      }

      // Keep source spacing exactly as received; only paint semantic spans.
      SelectionContainer {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(colors.surfaceContainerLow),
          contentPadding = PaddingValues(vertical = 8.dp),
        ) {
          items(count = lines.size) { index ->
            val line = lines[index]
            val trimmed = line.trim()
            val delimiter = line.indexOf(" : ")
            val isHeading = trimmed.isNotEmpty() &&
              !line.startsWith(" ") && delimiter < 0 &&
              !trimmed.startsWith("=") && !trimmed.contains("MEDIA INFO")
            val annotated = buildAnnotatedString {
              when {
                isHeading -> withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold)) {
                  append(line)
                }
                delimiter >= 0 -> {
                  withStyle(SpanStyle(color = colors.onSurfaceVariant, fontWeight = FontWeight.SemiBold)) {
                    append(line.substring(0, delimiter))
                  }
                  withStyle(SpanStyle(color = colors.primary)) {
                    append(" : ")
                  }
                  withStyle(SpanStyle(color = colors.onSurface)) {
                    append(line.substring(delimiter + 3))
                  }
                }
                else -> append(line)
              }
            }
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(
                  if (isHeading) colors.secondaryContainer.copy(alpha = 0.62f)
                  else Color.Transparent,
                )
                .padding(horizontal = 12.dp, vertical = if (isHeading) 10.dp else 4.dp),
              verticalAlignment = Alignment.Top,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Text(
                text = (index + 1).toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.onSurfaceVariant.copy(alpha = 0.72f),
                textAlign = TextAlign.End,
                modifier = Modifier.width(32.dp),
              )
              Text(
                text = annotated,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(
                  fontFamily = FontFamily.Monospace,
                  lineHeight = 19.sp,
                ),
                color = colors.onSurface,
                softWrap = true,
              )
            }
          }
        }
      }
    }
  }

  private fun parseMediaInfoText(text: String): List<InfoSection> {
    val sections = mutableListOf<InfoSection>()
    var currentName: String? = null
    val currentProps = mutableListOf<Pair<String, String>>()
    text.lines().forEach { line ->
      val trimmed = line.trim()
      when {
        trimmed.isEmpty() || trimmed.startsWith("=") || trimmed.startsWith("MEDIA INFO - ") -> {}
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

}
