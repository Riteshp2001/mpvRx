package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.preferences.preference.PreferenceStore

class GpuDriverPreferences(
    preferenceStore: PreferenceStore
) {
    val activeDriverId = preferenceStore.getString("gpu_driver_active_id", "system")
    val showDriverHud = preferenceStore.getBoolean("gpu_driver_show_hud", false)
    val hasAcceptedGpuWarning = preferenceStore.getBoolean("gpu_driver_warning_accepted", false)
    // OFF by default — gates v/d/i log emission across the GPU driver
    // subsystem so we don't spam logcat (or do unnecessary string building)
    // in normal usage. Errors/warnings remain unconditional.
    val verboseLogging = preferenceStore.getBoolean("gpu_driver_verbose_logging", false)

    // Set by the crash-loop guard when a stale loading-marker is detected at
    // app start: the previous launch died while a custom driver was active.
    // The next UI surface (MPVView creation) reads + clears it and toasts the
    // user. Empty string = no pending notice.
    val crashRevertedFrom = preferenceStore.getString("gpu_driver_crash_reverted_from", "")
}
