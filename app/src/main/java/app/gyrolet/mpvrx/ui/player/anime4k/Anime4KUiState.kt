package app.gyrolet.mpvrx.ui.player.anime4k

import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager

data class Anime4KUiState(
  val isEnabled: Boolean = false,
  val selectedMode: String = Anime4KManager.Mode.OFF.name,
  val usesGpuNext: Boolean = false,
  val usesVulkan: Boolean = false,
  val videoWidth: Int = 0,
  val videoHeight: Int = 0,
) {
  val isHighResolution: Boolean
    get() = videoWidth >= 3840 || videoHeight >= 2160

  val isAvailable: Boolean
    get() = isEnabled && (!usesGpuNext || usesVulkan)
}
