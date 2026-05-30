package app.gyrolet.mpvrx.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.domain.gpu.GpuDriverBridge
import app.gyrolet.mpvrx.domain.gpu.GpuDriverManager
import app.gyrolet.mpvrx.preferences.GpuDriverPreferences
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a human-readable diagnostic dump for the custom GPU driver feature
 * and exposes a share intent so users can hand it off to a bug report.
 *
 * Includes: device/GPU/Vulkan info, native bridge state, current and installed
 * drivers, Freedreno env vars, and recent logcat lines for our own tags.
 */
object GpuDriverLogger {

    private val GPU_TAGS = listOf("GpuDriverHelper", "GpuDriverBridge", "GpuDriverManager", "NativeFreedreno", "App")

    fun collect(
        context: Context,
        manager: GpuDriverManager,
        prefs: GpuDriverPreferences,
    ): String = buildString {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US).format(Date())
        appendLine("=== StreamX GPU Driver Diagnostic ===")
        appendLine("Generated: $now")
        appendLine("App: ${BuildConfig.APPLICATION_ID} (build ${BuildConfig.GIT_SHA})")
        appendLine()

        appendLine("--- Device ---")
        appendLine("Manufacturer: ${Build.MANUFACTURER}")
        appendLine("Model: ${Build.MODEL}")
        appendLine("Device: ${Build.DEVICE}")
        appendLine("Hardware: ${Build.HARDWARE}")
        appendLine("Board: ${Build.BOARD}")
        appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine()

        appendLine("--- GPU / Vulkan ---")
        appendLine("GPU model (bridge): ${runCatching { manager.getGpuModel() }.getOrElse { "ERROR: ${it.message}" }}")
        appendLine("Adreno supported: ${runCatching { manager.isAdrenoSupported() }.getOrElse { "ERROR: ${it.message}" }}")
        val vulkanFeature = context.packageManager.systemAvailableFeatures
            .find { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
        if (vulkanFeature != null) {
            val v = vulkanFeature.version
            appendLine("Vulkan HW version: ${v shr 22}.${(v shr 12) and 0x3FF}.${v and 0xFFF}")
        } else {
            appendLine("Vulkan HW version: not reported")
        }
        appendLine()

        appendLine("--- Native bridge ---")
        appendLine("adrenotools_bridge loaded: ${GpuDriverBridge.isAvailable()}")
        appendLine("NativeFreedrenoConfig loaded: ${NativeFreedrenoConfig.isAvailable()}")
        val envSummary = runCatching {
            if (NativeFreedrenoConfig.isAvailable()) NativeFreedrenoConfig.getFreedrenoEnvSummary() else ""
        }.getOrElse { "ERROR: ${it.message}" }
        appendLine("Freedreno env: ${if (envSummary.isEmpty()) "(none)" else envSummary}")
        appendLine()

        appendLine("--- Preferences ---")
        appendLine("Active driver id: ${prefs.activeDriverId.get()}")
        appendLine("Show HUD: ${prefs.showDriverHud.get()}")
        appendLine("Warning accepted: ${prefs.hasAcceptedGpuWarning.get()}")
        appendLine()

        appendLine("--- Installed drivers ---")
        val installed = runCatching { manager.getInstalledDriversSync() }
            .getOrElse {
                appendLine("ERROR: ${it.message}")
                emptyList()
            }
        if (installed.isEmpty()) {
            appendLine("(none)")
        } else {
            installed.forEach { d ->
                appendLine("- id=${d.id} name='${d.name}' version='${d.version}' vendor='${d.vendor}' vulkanLib='${d.vulkanLibName}' system=${d.isSystem}")
            }
        }
        appendLine()

        appendLine("--- Memory ---")
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        appendLine("Total RAM: ${mi.totalMem / (1024 * 1024)} MB")
        appendLine("Available RAM: ${mi.availMem / (1024 * 1024)} MB")
        appendLine("Low memory: ${mi.lowMemory}")
        appendLine()

        appendLine("--- Recent logcat (our tags) ---")
        append(dumpLogcat())
    }

    /**
     * Reads our own process logcat with `-d` (dump-and-exit), filtered to the
     * GPU driver tags. Since Jelly Bean apps can only read their own process
     * logs without READ_LOGS permission — perfect for our case.
     *
     * Dumps the full kernel log buffer for those tags — no line cap. The
     * underlying OS ring buffer (usually ~256 KB) is the hard limit.
     */
    private fun dumpLogcat(): String {
        return try {
            // -d: dump and exit  -v threadtime: include timestamp/pid/tid
            // *:S silences everything not explicitly enabled.
            val cmd = mutableListOf("logcat", "-d", "-v", "threadtime")
            GPU_TAGS.forEach { cmd += "$it:V" }
            cmd += "*:S"

            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val text = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()
            if (text.isBlank()) "(no log entries captured)\n" else text
        } catch (t: Throwable) {
            "logcat capture failed: ${t.message}\n"
        }
    }

    /**
     * Fires an ACTION_SEND chooser with the diagnostic as plain text body.
     * No FileProvider / WRITE_EXTERNAL_STORAGE needed.
     */
    fun share(context: Context, content: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "MpvRx GPU Driver Diagnostic")
            putExtra(Intent.EXTRA_TEXT, content)
        }
        val chooser = Intent.createChooser(send, "Share diagnostic log").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
