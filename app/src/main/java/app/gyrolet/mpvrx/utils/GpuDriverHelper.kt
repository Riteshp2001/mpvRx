package app.gyrolet.mpvrx.utils

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.Os
import app.gyrolet.mpvrx.domain.gpu.GpuDriver
import app.gyrolet.mpvrx.domain.gpu.GpuDriverBridge
import app.gyrolet.mpvrx.domain.gpu.GpuDriverManager
import app.gyrolet.mpvrx.preferences.GpuDriverPreferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

object GpuDriverHelper : KoinComponent {
    private const val TAG = "GpuDriverHelper"
    // Shared with MPVView.applyCustomVulkanDriver — must match.
    const val LOADING_MARKER_NAME = "gpu_driver_loading.flag"

    private val preferences: GpuDriverPreferences by inject()
    private val driverManager: GpuDriverManager by inject()
    private var isInitialized = false

    /** Live status of the driver subsystem, queryable from the UI. */
    enum class DriverStatus {
        NOT_INITIALIZED,
        UNSUPPORTED_ARCH,
        BRIDGE_UNAVAILABLE,
        SYSTEM,
        CUSTOM_ACTIVE,
        CUSTOM_FAILED,
    }

    @Volatile
    var activeDriverStatus: DriverStatus = DriverStatus.NOT_INITIALIZED
        private set

    /**
     * Initializes the GPU driver on app startup.
     * This should be called early in Application.onCreate().
     */
    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) {
            GpuLog.v(TAG) { "initialize() called again, already initialized — no-op" }
            return
        }

        val startMs = SystemClock.uptimeMillis()
        GpuLog.d(TAG) { "initialize() start; ABIs=${Build.SUPPORTED_ABIS.joinToString(",")}, HW=${Build.HARDWARE}, board=${Build.BOARD}" }

        // ── Crash-loop guard ──────────────────────────────────────────────
        // If the loading-marker file exists at app start, the previous
        // launch died while a custom Vulkan driver was active. The marker
        // is written by MPVView before the custom driver is loaded and
        // deleted ~30s after init succeeds — its presence here is strong
        // evidence the driver killed the app. Auto-revert to system so the
        // user isn't stuck in a crash loop they can't escape from the UI.
        runCatching {
            val marker = File(context.cacheDir, LOADING_MARKER_NAME)
            if (marker.exists()) {
                val crashedId = runCatching { marker.readText().trim() }
                    .getOrDefault("(unknown)")
                GpuLog.e(TAG, "previous launch crashed with custom driver '$crashedId' — reverting to system")
                preferences.activeDriverId.set("system")
                preferences.crashRevertedFrom.set(crashedId)
                marker.delete()
            }
        }.onFailure {
            GpuLog.w(TAG, "crash-loop guard error: ${it.message}", it)
        }

        try {
            // ABI check FIRST: a single in-memory array lookup, no syscalls.
            // On non-arm64 devices, this avoids loading the native lib AND
            // the .freedreno.conf disk read, saving startup time.
            if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
                GpuLog.d(TAG) { "skipping: device is not arm64-v8a" }
                activeDriverStatus = DriverStatus.UNSUPPORTED_ARCH
                isInitialized = true
                return
            }

            if (!NativeFreedrenoConfig.isAvailable() || !GpuDriverBridge.isAvailable()) {
                GpuLog.d(TAG) { "skipping: native bridge library not loaded" }
                activeDriverStatus = DriverStatus.BRIDGE_UNAVAILABLE
                isInitialized = true
                return
            }

            // Non-Adreno arm64 (Mali, PowerVR, etc.): adrenotools and the
            // Turnip / Freedreno drivers only target Qualcomm hardware.
            // If activeDriverId still points at a custom driver — typical
            // scenario is a backup restored from an Adreno phone — loading
            // it would crash on vkCreateInstance. Demote the pref to system
            // and arm system-mode hooks. The bridge stays loaded so the
            // file-redirect / debug env features still work; we just never
            // attempt a custom-driver load.
            if (!GpuDriverBridge.isAdrenoDevice()) {
                GpuLog.i(TAG) { "non-Adreno device — forcing system driver, custom selection disabled" }
                val activeId = preferences.activeDriverId.get()
                if (activeId.isNotEmpty() && activeId != "system") {
                    GpuLog.w(TAG, "demoting stale custom driver pref '$activeId' to system (no Adreno hardware)")
                    preferences.activeDriverId.set("system")
                }
                val hookLibDir = context.applicationInfo.nativeLibraryDir
                val tmpDir = context.cacheDir.absolutePath
                runCatching {
                    GpuDriverBridge.setDriver(hookLibDir, null, null, null, tmpDir)
                }
                activeDriverStatus = DriverStatus.SYSTEM
                isInitialized = true
                return
            }

            // ── Freedreno env-var path (Eden-style) ──────────────────────
            // HUD/TU_DEBUG flags are read by the driver at its own boot time
            // via getenv(). Independent of who eventually loads libvulkan.
            val cachePath = context.cacheDir.absolutePath
            GpuLog.v(TAG) { "freedreno base path = $cachePath" }
            NativeFreedrenoConfig.setFreedrenoBasePath(cachePath)
            NativeFreedrenoConfig.initializeFreedrenoConfig()
            NativeFreedrenoConfig.reloadFreedrenoConfig()
            GpuLog.v(TAG) { "freedreno reloaded, env=${NativeFreedrenoConfig.getFreedrenoEnvSummary()}" }

            val hud = preferences.showDriverHud.get()
            GpuLog.d(TAG) { "HUD pref = $hud" }
            if (hud) {
                NativeFreedrenoConfig.setFreedrenoEnv("TU_DEBUG", "sysmem,stat")
                NativeFreedrenoConfig.setFreedrenoEnv("KGSL_REDIRECT_HUD", "1")
                GpuLog.v(TAG) { "HUD env applied: TU_DEBUG=sysmem,stat, KGSL_REDIRECT_HUD=1" }
            } else {
                NativeFreedrenoConfig.clearFreedrenoEnv("TU_DEBUG")
                NativeFreedrenoConfig.clearFreedrenoEnv("KGSL_REDIRECT_HUD")
                GpuLog.v(TAG) { "HUD env cleared" }
            }

            // ── ByteHook bridge path ─────────────────────────────────────
            // Eagerly call GpuDriverBridge.setDriver so subsequent dlopen(
            // "libvulkan.so") calls from libmpv/libplacebo are rerouted to
            // the custom driver. MPVView's --vulkan-library path stays in
            // place for direct loads of custom-named .so files; the linker
            // refcounts the handle, so loading via both paths is safe.
            val activeDriverId = preferences.activeDriverId.get()
            val hookLibDir = context.applicationInfo.nativeLibraryDir
            val tmpDir = context.cacheDir.absolutePath
            // File-redirect dir is only meaningful when HUD/debug needs the
            // driver to spill files into our cache. Off by default.
            val fileRedirectDir: String? = if (hud) cachePath else null

            if (activeDriverId == "system" || activeDriverId.isEmpty()) {
                GpuLog.d(TAG) { "active driver pref = system — arming hooks for system loader" }
                val ok = GpuDriverBridge.setDriver(
                    hookLibDir = hookLibDir,
                    customDriverDir = null,
                    customDriverName = null,
                    fileRedirectDir = fileRedirectDir,
                    tmpDir = tmpDir,
                )
                GpuLog.i(TAG) { "system driver hooks ${if (ok) "armed" else "FAILED"}" }
                activeDriverStatus = DriverStatus.SYSTEM
                isInitialized = true
                return
            }

            val driver = runCatching { driverManager.getInstalledDriversSync() }
                .getOrNull()
                ?.firstOrNull { it.id == activeDriverId && !it.isSystem }

            // "Active driver id no longer in the installed list" is a config
            // problem (user deleted the .so behind our back), not a driver
            // crash. Reset the pref to system and fall through to the
            // system-driver branch — no point throwing for this.
            if (driver == null) {
                GpuLog.w(TAG, "active driver id='$activeDriverId' not installed — resetting pref to system")
                preferences.activeDriverId.set("system")
                GpuDriverBridge.setDriver(hookLibDir, null, null, fileRedirectDir, tmpDir)
                activeDriverStatus = DriverStatus.SYSTEM
                isInitialized = true
                return
            }

            // ── Pre-flight file validation ───────────────────────────────
            // Genuine driver-load failures: throw instead of silently
            // swapping to system, so the user sees the real cause in logcat
            // / a crash dialog rather than playback "just working" on the
            // OEM driver while the UI claims the custom one is active.
            val driverDir = File(driver.driverPath)
            val driverLib = File(driver.driverPath, driver.vulkanLibName)
            if (!driverDir.isDirectory) {
                activeDriverStatus = DriverStatus.CUSTOM_FAILED
                throw IllegalStateException(
                    "GPU driver dir missing for '${driver.name}': ${driver.driverPath}"
                )
            }
            if (!driverLib.isFile) {
                activeDriverStatus = DriverStatus.CUSTOM_FAILED
                throw IllegalStateException(
                    "GPU driver lib missing for '${driver.name}': ${driverLib.absolutePath}"
                )
            }

            GpuLog.d(TAG) { "active driver = '${driver.name}' v${driver.version} (${driver.driverPath}/${driver.vulkanLibName})" }

            // Write a Vulkan ICD manifest next to the driver and surface it
            // to the standard Vulkan loader via env vars. This is the path
            // the Android loader uses when it ISN'T routed through our
            // bytehook (e.g. the OS-level Vulkan service ahead of libmpv).
            injectVulkanIcd(driver)

            // Mesa/Turnip-specific tuning. No-op for non-Mesa drivers.
            configureMesaEnvironment(driver.vulkanLibName)

            // Crash-loop guard for the bridge load. The marker is written
            // ONLY around the setDriver call (which loads the custom .so
            // and arms bytehook). If that call crashes the process, the
            // marker survives and the next launch reverts to system.
            // The actual vkCreateInstance test fires later (when MPVView
            // starts playback); MPVView writes its own marker around that
            // and clears it on wasHookUsed() success.
            val marker = File(context.cacheDir, LOADING_MARKER_NAME)
            runCatching { marker.writeText(driver.id) }
                .onFailure { GpuLog.w(TAG, "could not write crash-guard marker: ${it.message}") }

            val ok = GpuDriverBridge.setDriver(
                hookLibDir = hookLibDir,
                customDriverDir = driver.driverPath,
                customDriverName = driver.vulkanLibName,
                fileRedirectDir = fileRedirectDir,
                tmpDir = tmpDir,
            )

            // setDriver returned without crashing — clear the bridge-load
            // marker immediately. Any further crash would be from MPV /
            // libplacebo / Vulkan instance creation, which MPVView guards
            // separately.
            runCatching { marker.delete() }

            if (ok) {
                GpuLog.i(TAG) { "custom driver '${driver.name}' armed via bridge" }
                activeDriverStatus = DriverStatus.CUSTOM_ACTIVE
            } else {
                // No silent fallback. The boot-time crash-loop guard above
                // handles the recovery story on the NEXT launch — within
                // this launch we want the failure to be loud so users (and
                // logs) see the real cause instead of the app pretending
                // the driver is active while libplacebo ends up on the OEM
                // Vulkan blob.
                activeDriverStatus = DriverStatus.CUSTOM_FAILED
                throw IllegalStateException(
                    "GpuDriverBridge.setDriver failed for '${driver.name}' " +
                        "(${driver.vulkanLibName}). Driver may be incompatible " +
                        "with this device's Vulkan loader."
                )
            }

            isInitialized = true
        } catch (t: Throwable) {
            // Log loudly, mark failed, then rethrow. The user explicitly
            // wants real driver-load failures to surface as a crash so the
            // root cause is visible in logcat / Play Console / the bug
            // report, instead of being masked by an in-process fallback
            // that silently switches to the OEM Vulkan driver.
            GpuLog.e(TAG, "CRITICAL: GpuDriverHelper.initialize failed — letting it propagate", t)
            activeDriverStatus = DriverStatus.CUSTOM_FAILED
            isInitialized = true
            throw t
        } finally {
            GpuLog.d(TAG) { "initialize() done in ${SystemClock.uptimeMillis() - startMs}ms" }
        }
    }

    /**
     * Live-swap the GPU driver without an app restart. Called by the
     * GPU Driver Preferences screen when the user picks a different
     * driver. The flow:
     *
     *   1. Unhook the currently-armed bridge (clears the bytehook stubs;
     *      does NOT dlclose the old .so — that would crash any live
     *      Vulkan objects).
     *   2. Persist the new pref so subsequent launches stay consistent.
     *   3. Call [GpuDriverBridge.setDriver] with the new driver's paths
     *      (or null for system mode).
     *   4. Update [activeDriverStatus] so the UI banner refreshes.
     *
     * Safety: the new hook table only affects FUTURE dlopen/vkCreate
     * calls. Anything libplacebo has already resolved (e.g. an active
     * playback's cached vk function pointers) keeps working against the
     * old handle until the next VO is created. So this is safe to call
     * mid-session, but the user has to start a new video for the new
     * driver to actually drive frames.
     *
     * On failure, throws — same policy as initialize(). A failed swap
     * leaves the bridge in an unhooked state, so silently returning
     * would let the next video fall back to the OEM Vulkan driver while
     * the UI claims the custom one is active. Caller should let the
     * exception propagate so the app crashes with the real cause in
     * logcat; the boot-time guard recovers on the next launch.
     */
    @Synchronized
    fun applyDriverChange(context: Context, newDriverId: String) {
        check(isInitialized) { "GpuDriverHelper not yet initialized — cannot swap drivers" }
        check(Build.SUPPORTED_ABIS.contains("arm64-v8a") && GpuDriverBridge.isAvailable()) {
            "Native bridge unavailable on this device"
        }
        // Non-Adreno hardware: refuse to swap to a custom driver. UI gates
        // this at the prefs screen too, but enforce here as well so any
        // future caller (auto-restore, deep-link, debug command) can't slip
        // a custom driver onto a Mali / PowerVR device and crash the app.
        check(GpuDriverBridge.isAdrenoDevice() || newDriverId == "system" || newDriverId.isEmpty()) {
            "Cannot apply custom GPU driver on non-Adreno hardware"
        }

        val hookLibDir = context.applicationInfo.nativeLibraryDir
        val tmpDir = context.cacheDir.absolutePath
        val hud = preferences.showDriverHud.get()
        val fileRedirectDir: String? = if (hud) context.cacheDir.absolutePath else null

        GpuLog.i(TAG) { "applyDriverChange: swapping to '$newDriverId' (was ${preferences.activeDriverId.get()})" }

        // Tear down the previous hook table before arming the new one.
        // Idempotent — safe even if no driver was loaded. Underlying .so
        // is NOT dlclose'd to keep any live Vulkan objects valid.
        runCatching { GpuDriverBridge.unloadDriver() }

        if (newDriverId == "system" || newDriverId.isEmpty()) {
            preferences.activeDriverId.set("system")
            val ok = GpuDriverBridge.setDriver(hookLibDir, null, null, fileRedirectDir, tmpDir)
            GpuLog.i(TAG) { "applyDriverChange: system mode hooks ${if (ok) "armed" else "FAILED"}" }
            activeDriverStatus = DriverStatus.SYSTEM
            return
        }

        val driver = runCatching { driverManager.getInstalledDriversSync() }
            .getOrNull()
            ?.firstOrNull { it.id == newDriverId && !it.isSystem }
            ?: throw IllegalStateException("Driver id '$newDriverId' is not in the installed list")

        val driverDir = File(driver.driverPath)
        val driverLib = File(driver.driverPath, driver.vulkanLibName)
        if (!driverDir.isDirectory) {
            activeDriverStatus = DriverStatus.CUSTOM_FAILED
            throw IllegalStateException("Driver dir missing: ${driver.driverPath}")
        }
        if (!driverLib.isFile) {
            activeDriverStatus = DriverStatus.CUSTOM_FAILED
            throw IllegalStateException("Driver lib missing: ${driverLib.absolutePath}")
        }

        injectVulkanIcd(driver)
        configureMesaEnvironment(driver.vulkanLibName)

        val ok = GpuDriverBridge.setDriver(
            hookLibDir = hookLibDir,
            customDriverDir = driver.driverPath,
            customDriverName = driver.vulkanLibName,
            fileRedirectDir = fileRedirectDir,
            tmpDir = tmpDir,
        )
        if (!ok) {
            activeDriverStatus = DriverStatus.CUSTOM_FAILED
            throw IllegalStateException(
                "GpuDriverBridge.setDriver failed for '${driver.name}' (${driver.vulkanLibName}). " +
                    "Driver may be incompatible with this device's Vulkan loader."
            )
        }

        // Only persist the pref after the bridge confirmed the load —
        // otherwise a bad driver poisons the next launch too.
        preferences.activeDriverId.set(newDriverId)
        activeDriverStatus = DriverStatus.CUSTOM_ACTIVE
        GpuLog.i(TAG) { "applyDriverChange: '${driver.name}' armed live (start a new video to use it)" }
    }

    /**
     * Writes a Vulkan ICD (Installable Client Driver) JSON manifest next
     * to the driver and exports three env vars so the standard Vulkan
     * loader picks up our driver regardless of which protocol level it's
     * using.
     *
     *   VK_ICD_FILENAMES     — original env var (Vulkan loader pre-1.3.234)
     *   VK_DRIVER_FILES      — replacement env var (Vulkan loader 1.3.234+)
     *   VK_ADD_DRIVER_FILES  — additive variant (does not exclude system ICDs)
     *
     * Without this, even when our bytehook successfully rewrites
     * dlopen("libvulkan.so") to return the custom handle, the Android
     * Vulkan service can still consult /vendor/lib64/hw/ during its
     * own startup probes and reach the OEM blob ahead of us.
     */
    private fun injectVulkanIcd(driver: GpuDriver) {
        runCatching {
            val fullLibPath = "${driver.driverPath}/${driver.vulkanLibName}"
            val icdFile = File(driver.driverPath, "icd.json")
            val icdContent = """
                {
                    "file_format_version": "1.0.0",
                    "ICD": {
                        "library_path": "$fullLibPath",
                        "api_version": "1.3.0"
                    }
                }
            """.trimIndent()
            icdFile.writeText(icdContent)

            val icdPath = icdFile.absolutePath
            Os.setenv("VK_ICD_FILENAMES", icdPath, true)
            Os.setenv("VK_DRIVER_FILES", icdPath, true)
            Os.setenv("VK_ADD_DRIVER_FILES", icdPath, true)
            GpuLog.i(TAG) { "ICD manifest written + env exposed: $icdPath" }
        }.onFailure {
            GpuLog.w(TAG, "ICD JSON / env injection failed: ${it.message}", it)
        }
    }

    /**
     * Sets Mesa/Turnip-specific env vars for Freedreno-flavoured drivers.
     * Skipped for non-Mesa drivers (Qualcomm proprietary blob etc.) since
     * the vars would be no-ops at best and can confuse non-Mesa loaders.
     */
    private fun configureMesaEnvironment(vulkanLibName: String) {
        val isMesa = vulkanLibName.contains("freedreno", ignoreCase = true) ||
            vulkanLibName.contains("turnip", ignoreCase = true)
        if (!isMesa) return

        runCatching {
            // Force the freedreno backend inside Mesa's loader (prevents
            // it from selecting another Mesa Vulkan driver if multiple
            // are linked into the same .so).
            Os.setenv("MESA_LOADER_DRIVER_OVERRIDE", "freedreno", true)
            // Triple-buffered low-latency present mode; the default FIFO
            // adds a frame of input lag that's visible during seek/skip.
            Os.setenv("MESA_VK_WSI_PRESENT_MODE", "mailbox", true)
            GpuLog.v(TAG) { "Mesa env applied: driver=freedreno, present=mailbox" }
        }.onFailure {
            GpuLog.w(TAG, "Mesa env setenv failed: ${it.message}")
        }
    }
}
