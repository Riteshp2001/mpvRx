package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.preferences.preference.PreferenceStore

class GpuDriverPreferences(
    preferenceStore: PreferenceStore
) {
    val activeDriverId = preferenceStore.getString("gpu_driver_active_id", "system")
    val showDriverHud = preferenceStore.getBoolean("gpu_driver_show_hud", false)
    val hasAcceptedGpuWarning = preferenceStore.getBoolean("gpu_driver_warning_accepted", false)
}
