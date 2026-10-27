/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.presentation.crash

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.coroutineScope
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.MainActivity
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.MpvrxTheme
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CrashActivity : AppCompatActivity() {
  private var reportFile by mutableStateOf<File?>(null)
  private var preparingReport by mutableStateOf(false)
  private var reportFailed by mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    onBackPressedDispatcher.addCallback(this) {
      closeApplication()
    }
    prepareReport()
    setContent {
      MpvrxTheme(loadDownloadedFonts = false) {
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        SideEffect {
          val bars = SystemBarStyle.auto(Color.Transparent.toArgb(), Color.Transparent.toArgb()) { dark }
          enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        }
        CrashScreen()
      }
    }
  }

  private fun closeApplication() {
    // A crash from an earlier session is shown over the running app; closing returns to it.
    if (intent.getBooleanExtra(EXTRA_PREVIOUS_SESSION, false)) finish() else finishAffinity()
  }

  private fun restartApplication() {
    startActivity(
      Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
      },
    )
    finishAffinity()
  }

  private fun prepareReport() {
    if (preparingReport) return
    preparingReport = true
    reportFailed = false
    lifecycle.coroutineScope.launch {
      try {
        reportFile = withContext(Dispatchers.IO) { CrashReportStore.complete(applicationContext, intent) }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        reportFailed = true
      } finally {
        preparingReport = false
      }
    }
  }

  private fun deleteDatabase(): Boolean =
    runCatching { deleteDatabase("mpvrx.db") }.getOrDefault(false)

  private fun copyReport() {
    val report = reportFile ?: return
    lifecycle.coroutineScope.launch {
      try {
        if (report.length() > 256 * 1024) {
          Toast.makeText(this@CrashActivity, R.string.crash_screen_copy_failed, Toast.LENGTH_LONG).show()
          return@launch
        }
        val text = withContext(Dispatchers.IO) { report.readText() }
        getSystemService(ClipboardManager::class.java)
          .setPrimaryClip(ClipData.newPlainText("mpvRx crash report", text))
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        Toast.makeText(this@CrashActivity, R.string.crash_screen_copy_failed, Toast.LENGTH_LONG).show()
      }
    }
  }

  private fun shareReport() {
    val report = reportFile ?: return
    try {
      shareReportFile(this, report, "$packageName.crash-reports")
    } catch (_: Exception) {
      Toast.makeText(this, R.string.crash_screen_report_failed, Toast.LENGTH_LONG).show()
    }
  }

  companion object {
    /** Set when the report belongs to a crash the user did not see when it happened. */
    const val EXTRA_PREVIOUS_SESSION = "crash_previous_session"

    suspend fun shareLogs(
      deviceInfo: String,
      exceptionString: String? = null,
      logcat: String,
      activity: Activity,
    ) {
      // Advanced Settings calls this without an exception. In that case open the
      // interactive log viewer instead of immediately throwing the user into a share sheet.
      // Crash reports still use the original export/share flow below.
      if (exceptionString == null) {
        withContext(Dispatchers.Main) {
          activity.startActivity(
            Intent(activity, DebugLogsActivity::class.java),
          )
        }
        return
      }

      val file = withContext(Dispatchers.IO) {
        File(activity.cacheDir, "mpvrx_logs.txt").apply { writeText(concatLogs(deviceInfo, exceptionString, logcat)) }
      }
      withContext(Dispatchers.Main) { shareReportFile(activity, file) }
    }

    private fun shareReportFile(
      activity: Activity,
      file: File,
      authority: String = "${activity.packageName}.provider",
    ) {
      val uri = FileProvider.getUriForFile(activity, authority, file)
      val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("mpvRx crash report", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      activity.startActivity(Intent.createChooser(sendIntent, activity.getString(R.string.crash_screen_share)))
    }

    fun concatLogs(
      deviceInfo: String,
      crashLogs: String? = null,
      logcat: String,
    ): String =
      StringBuilder()
        .apply {
          appendLine(deviceInfo)
          appendLine()
          if (!crashLogs.isNullOrBlank()) {
            appendLine("Exception:")
            appendLine(crashLogs)
            appendLine()
          }
          appendLine("Logcat:")
          appendLine(logcat)
        }.toString()

    fun collectLogcat(): String {
      val process = ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start()
      return try {
        process.inputStream.bufferedReader().use { it.readText() }
      } finally {
        process.destroy()
      }
    }

    fun collectDeviceInfo(): String =
      """
      App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})
      Android version: ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})
      Device brand: ${Build.BRAND}
      Device manufacturer: ${Build.MANUFACTURER}
      Device model: ${Build.MODEL} (${Build.DEVICE})
      MPV version: ${Utils.VERSIONS.mpv}
      ffmpeg version: ${Utils.VERSIONS.ffmpeg}
      libplacebo version: ${Utils.VERSIONS.libPlacebo}
      """.trimIndent()
  }

  @Composable
  private fun CrashScreen() {
    val scope = rememberCoroutineScope()
    var databaseDeleted by remember { mutableStateOf(false) }
    var resetFailed by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val exceptionString = remember { intent.getStringExtra("exception").orEmpty() }
    val isDatabaseRelated =
      remember(exceptionString) {
        val details = exceptionString.lowercase(java.util.Locale.ROOT)
        listOf("android.database.sqlite", "androidx.room", "mpvrx.db").any { it in details }
      }

    Scaffold(
      modifier = Modifier.fillMaxSize(),
      topBar = {
        TopAppBar(
          title = {
            Text(
              stringResource(R.string.app_name),
              style = MaterialTheme.typography.titleLargeEmphasized,
              color = MaterialTheme.colorScheme.primary,
            )
          },
        )
      },
    ) { paddingValues ->
      Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.TopCenter) {
        Column(
          modifier =
            Modifier.widthIn(max = 640.dp).fillMaxSize()
              .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLargeIncreased,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          ) {
            Column(
              modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(80.dp),
              ) {
                Icon(
                  painter = painterResource(R.drawable.ic_launcher_monochrome),
                  contentDescription = null,
                  modifier = Modifier.padding(18.dp),
                )
              }
              Text(
                stringResource(R.string.crash_screen_title),
                style = MaterialTheme.typography.headlineMediumEmphasized,
                textAlign = TextAlign.Center,
              )
              Text(
                stringResource(R.string.crash_screen_subtitle, stringResource(R.string.app_name)),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
              )
            }
          }

          Button(
            onClick = ::restartApplication,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            enabled = !resetting,
          ) {
            Icon(Icons.RoundedFilled.Refresh, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.crash_screen_restart))
          }
          OutlinedButton(
            onClick = ::closeApplication,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            enabled = !resetting,
          ) {
            Icon(Icons.RoundedFilled.Close, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ui_close))
          }

          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
          ) {
            SelectionContainer {
              Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                  intent.getStringExtra("crash_exception_class").orEmpty().substringAfterLast('.').ifBlank { "Exception" },
                  style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                  stringResource(R.string.crash_screen_report_id, intent.getStringExtra("crash_report_id") ?: "—"),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }

          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
          ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.RoundedFilled.BugReport, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                  stringResource(R.string.crash_screen_logs_title),
                  style = MaterialTheme.typography.titleMediumEmphasized,
                  fontWeight = FontWeight.SemiBold,
                )
              }
              Text(
                stringResource(R.string.crash_screen_report_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              if (preparingReport) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.crash_screen_report_preparing), style = MaterialTheme.typography.bodySmall)
              }
              if (reportFailed) {
                Text(stringResource(R.string.crash_screen_report_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = ::prepareReport) { Text(stringResource(R.string.contributors_retry)) }
              }
              HorizontalDivider()
              FilledTonalButton(
                onClick = ::shareReport,
                enabled = reportFile != null,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Icon(Icons.RoundedFilled.Share, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.crash_screen_share))
              }
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { showDetails = true }, enabled = reportFile != null, modifier = Modifier.weight(1f)) {
                  Text(stringResource(R.string.crash_screen_details))
                }
                TextButton(onClick = ::copyReport, enabled = reportFile != null, modifier = Modifier.weight(1f)) {
                  Text(stringResource(R.string.ui_copy_all))
                }
              }
            }
          }

          if (isDatabaseRelated || databaseDeleted || resetFailed) {
            Surface(
              modifier = Modifier.fillMaxWidth(),
              shape = MaterialTheme.shapes.extraLarge,
              color = if (databaseDeleted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
            ) {
              Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                  when {
                    databaseDeleted -> stringResource(R.string.crash_screen_database_deleted)
                    resetFailed -> stringResource(R.string.crash_screen_reset_failed)
                    else -> stringResource(R.string.crash_screen_database_hint)
                  },
                  color = if (databaseDeleted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                )
                if (isDatabaseRelated && !databaseDeleted) {
                  TextButton(onClick = { confirmReset = true }, enabled = !resetting) {
                    Text(stringResource(R.string.crash_screen_fix_crash), color = MaterialTheme.colorScheme.error)
                  }
                }
              }
            }
          }
        }
      }
    }
    if (confirmReset) {
      AlertDialog(
        onDismissRequest = { confirmReset = false },
        icon = { Icon(Icons.RoundedFilled.Warning, null) },
        title = { Text(stringResource(R.string.crash_screen_fix_crash)) },
        text = { Text(stringResource(R.string.crash_screen_reset_confirm)) },
        confirmButton = {
          TextButton(
            onClick = {
              confirmReset = false
              resetting = true
              scope.launch {
                try {
                  databaseDeleted = withContext(Dispatchers.IO) { deleteDatabase() }
                  resetFailed = !databaseDeleted
                } finally {
                  resetting = false
                }
              }
            },
          ) { Text(stringResource(R.string.generic_confirm)) }
        },
        dismissButton = {
          TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.generic_cancel)) }
        },
      )
    }
    val report = reportFile
    if (showDetails && report != null) {
      CrashDetails(report = report, onDismiss = { showDetails = false })
    }
  }

  @Composable
  private fun CrashDetails(
    report: File,
    onDismiss: () -> Unit,
  ) {
    var lines by remember(report) { mutableStateOf<List<String>?>(null) }
    var failed by remember(report) { mutableStateOf(false) }
    LaunchedEffect(report) {
      try {
        lines = withContext(Dispatchers.IO) { report.readLines() }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        failed = true
      }
    }
    Dialog(
      onDismissRequest = onDismiss,
      properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
      Scaffold(
        topBar = {
          TopAppBar(
            title = { Text(stringResource(R.string.crash_screen_details)) },
            navigationIcon = {
              IconButton(onClick = onDismiss) {
                Icon(Icons.RoundedFilled.ArrowBack, stringResource(R.string.ui_close))
              }
            },
            actions = {
              IconButton(onClick = ::copyReport) {
                Icon(Icons.RoundedFilled.ContentCopy, stringResource(R.string.ui_copy_all))
              }
              IconButton(onClick = ::shareReport) {
                Icon(Icons.RoundedFilled.Share, stringResource(R.string.crash_screen_share))
              }
            },
          )
        },
      ) { padding ->
        val reportLines = lines
        if (failed) {
          Text(stringResource(R.string.crash_screen_report_failed), modifier = Modifier.padding(padding).padding(16.dp))
        } else if (reportLines == null) {
          LinearProgressIndicator(Modifier.fillMaxWidth().padding(padding))
        } else {
          SelectionContainer(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
              items(reportLines) { line ->
                Text(
                  text = line.ifEmpty { " " },
                  fontFamily = FontFamily.Monospace,
                  style = MaterialTheme.typography.bodySmall,
                )
              }
            }
          }
        }
      }
    }
  }
}
