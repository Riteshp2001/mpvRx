package app.gyrolet.mpvrx.ui.cast

data class CastSessionState(
  val isConnected: Boolean = false,
  val deviceName: String? = null,
  val isPlaying: Boolean = false,
  val isPaused: Boolean = false,
  val isBuffering: Boolean = false,
  val currentPosition: Long = 0L,
  val duration: Long = 0L,
  val volume: Double = 1.0,
  val isMuted: Boolean = false,
  val playbackSpeed: Float = 1.0f,
  val title: String = "",
)
