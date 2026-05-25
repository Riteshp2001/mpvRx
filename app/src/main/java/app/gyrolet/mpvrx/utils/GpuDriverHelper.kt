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
            if (!GpuDriverBridge.isAvailable()) {
                Log.d(TAG, "Native library not available, skipping GPU driver initialization")
                isInitialized = true
                return
            }

            if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
                Log.d(TAG, "Custom GPU drivers only supported on arm64-v8a")
                isInitialized = true
                return
            }

            val activeDriverId = preferences.activeDriverId.get()
            
            // File redirect dir if HUD or debug is enabled
            var fileRedirectDir: String? = null
            if (preferences.showDriverHud.get()) {
                // In Azahar/Adrenotools this allows shader/config redirection
                fileRedirectDir = context.cacheDir.absolutePath
            }

            if (activeDriverId == "system") {
                Log.d(TAG, "Using system default GPU driver")
                // Load system driver with hooks (to allow file redirection if needed)
                val success = GpuDriverBridge.setDriver(
                    hookLibDir = context.applicationInfo.nativeLibraryDir,
                    customDriverDir = null,
                    customDriverName = null,
                    fileRedirectDir = fileRedirectDir,
                    tmpDir = context.cacheDir.absolutePath
                )
                if (success) {
                    Log.i(TAG, "Successfully initialized system GPU driver hooks")
                } else {
                    Log.w(TAG, "Failed to initialize system GPU driver hooks")
                }
                isInitialized = true
                return
            }

            // Load drivers synchronously to avoid race condition with MPV initialization
            val drivers = driverManager.getInstalledDriversSync()
            val activeDriver = drivers.find { it.id == activeDriverId }

            if (activeDriver != null && !activeDriver.isSystem) {
                Log.d(TAG, "Initializing custom GPU driver: ${activeDriver.name}")
                
                // Create an ICD json file to force Vulkan loaders to use this driver
                val icdFile = java.io.File(activeDriver.driverPath, "icd.json")
                val icdContent = """
                {
                    "file_format_version": "1.0.0",
                    "ICD": {
                        "library_path": "${activeDriver.driverPath}/${activeDriver.vulkanLibName}",
                        "api_version": "1.1.0"
                    }
                }
                """.trimIndent()
                icdFile.writeText(icdContent)
                
                // Inject the VK_ICD_FILENAMES environment variable
                try {
                    android.system.Os.setenv("VK_ICD_FILENAMES", icdFile.absolutePath, true)
                    Log.i(TAG, "Injected VK_ICD_FILENAMES=${icdFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set VK_ICD_FILENAMES", e)
                }

                val success = GpuDriverBridge.setDriver(
                    hookLibDir = context.applicationInfo.nativeLibraryDir,
                    customDriverDir = activeDriver.driverPath,
                    customDriverName = activeDriver.vulkanLibName,
                    fileRedirectDir = fileRedirectDir,
                    tmpDir = context.cacheDir.absolutePath
                )
                
                if (success) {
                    Log.i(TAG, "Successfully initialized custom GPU driver: ${activeDriver.name}")
                } else {
                    Log.e(TAG, "Failed to initialize custom GPU driver: ${activeDriver.name}. Driver may be incompatible.")
                    // Don't automatically revert to system here to allow user to see the error/retry
                    // and because it might fail due to temporary conditions (though unlikely for local files)
                }
            } else {
                // Fallback if the active driver is missing from installed list
                GpuDriverBridge.setDriver(
                    hookLibDir = context.applicationInfo.nativeLibraryDir,
                    customDriverDir = null,
                    customDriverName = null,
                    fileRedirectDir = fileRedirectDir,
                    tmpDir = context.cacheDir.absolutePath
                )
            }
            
            isInitialized = true
        } catch (t: Throwable) {
            Log.e(TAG, "CRITICAL: Native crash or error in GpuDriverHelper.initialize", t)
            isInitialized = true
        }
    }
}
