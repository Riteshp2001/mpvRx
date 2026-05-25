package app.gyrolet.mpvrx.utils

import android.content.Context
import android.os.Build
import android.util.Log
import app.gyrolet.mpvrx.domain.gpu.GpuDriverBridge
import app.gyrolet.mpvrx.domain.gpu.GpuDriverManager
import app.gyrolet.mpvrx.preferences.GpuDriverPreferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object GpuDriverHelper : KoinComponent {
    private const val TAG = "GpuDriverHelper"
    private val preferences: GpuDriverPreferences by inject()
    private val driverManager: GpuDriverManager by inject()
    private var isInitialized = false

    /**
     * Initializes the GPU driver on app startup.
     * This should be called early in Application.onCreate().
     */
    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) return
        
        try {
            if (!NativeFreedrenoConfig.isAvailable()) {
                Log.d(TAG, "Native library not available, skipping GPU driver initialization")
                isInitialized = true
                return
            }

            // Initialize Freedreno config early like Eden
            NativeFreedrenoConfig.setFreedrenoBasePath(context.cacheDir.absolutePath)
            NativeFreedrenoConfig.initializeFreedrenoConfig()
            NativeFreedrenoConfig.reloadFreedrenoConfig()

            if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
                Log.d(TAG, "Custom GPU drivers only supported on arm64-v8a")
                isInitialized = true
                return
            }

            // Apply HUD setting early
            if (preferences.showDriverHud.get()) {
                NativeFreedrenoConfig.setFreedrenoEnv("TU_DEBUG", "sysmem,stat")
                NativeFreedrenoConfig.setFreedrenoEnv("KGSL_REDIRECT_HUD", "1")
            } else {
                NativeFreedrenoConfig.clearFreedrenoEnv("TU_DEBUG")
                NativeFreedrenoConfig.clearFreedrenoEnv("KGSL_REDIRECT_HUD")
            }

            val activeDriverId = preferences.activeDriverId.get()
            if (activeDriverId == "system") {
                Log.d(TAG, "Using system default GPU driver")
                isInitialized = true
                return
            }

            // Load drivers synchronously to avoid race condition with MPV initialization
            val drivers = driverManager.getInstalledDriversSync()
            val activeDriver = drivers.find { it.id == activeDriverId }

            if (activeDriver != null && !activeDriver.isSystem) {
                Log.d(TAG, "Initializing custom GPU driver: ${activeDriver.name}")
                
                val success = GpuDriverBridge.setDriver(
                    2, // RTLD_NOW
                    0x0001 or 0x0002, // ADRENOTOOLS_GPU_DEFAULT | ADRENOTOOLS_GPU_HELPERS
                    context.cacheDir.absolutePath,
                    context.applicationInfo.nativeLibraryDir,
                    activeDriver.driverPath,
                    activeDriver.vulkanLibName
                )
                
                if (success) {
                    Log.i(TAG, "Successfully initialized custom GPU driver: ${activeDriver.name}")
                } else {
                    Log.e(TAG, "Failed to initialize custom GPU driver: ${activeDriver.name}. Driver may be incompatible.")
                    // Don't automatically revert to system here to allow user to see the error/retry
                    // and because it might fail due to temporary conditions (though unlikely for local files)
                }
            }
            
            isInitialized = true
        } catch (t: Throwable) {
            Log.e(TAG, "CRITICAL: Native crash or error in GpuDriverHelper.initialize", t)
            isInitialized = true
        }
    }
}
