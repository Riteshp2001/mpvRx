package app.gyrolet.mpvrx.exoplayer.feature.player.state

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.DisposableEffectResult
import androidx.compose.runtime.DisposableEffectScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat.getSystemService
import androidx.media3.common.Player
import androidx.media3.common.listen
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.exoplayer.core.common.Logger
import app.gyrolet.mpvrx.exoplayer.feature.player.service.getLoudnessGain
import app.gyrolet.mpvrx.exoplayer.feature.player.service.isLoudnessGainSupported
import app.gyrolet.mpvrx.exoplayer.feature.player.service.setLoudnessGain

@OptIn(UnstableApi::class)
@Composable
fun rememberVolumeState(
    player: Player?,
    shouldShowVolumePanelIfHeadsetIsOn: Boolean,
    isVolumeBoostEnabled: Boolean,
): VolumeState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val volumeState = remember(
        player,
        shouldShowVolumePanelIfHeadsetIsOn,
        isVolumeBoostEnabled,
    ) {
        VolumeState(
            player = player,
            context = context,
            shouldShowVolumePanelIfHeadsetIsOn = shouldShowVolumePanelIfHeadsetIsOn,
            isVolumeBoostEnabled = isVolumeBoostEnabled,
            scope = scope,
        )
    }
    LaunchedEffect(player) { volumeState.initialize() }
    DisposableEffect(context) { volumeState.handleLifecycle(this) }
    return volumeState
}

@Stable
class VolumeState(
    private val player: Player?,
    private val context: Context,
    private val shouldShowVolumePanelIfHeadsetIsOn: Boolean,
    private val isVolumeBoostEnabled: Boolean,
    private val scope: CoroutineScope,
) {
    private val audioManager = getSystemService(context, AudioManager::class.java)!!

    private val systemMaxVolume: Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private var isLoudnessGainSupported: Boolean by mutableStateOf(false)

    val maxVolume: Int
        get() = if (isLoudnessGainSupported) systemMaxVolume * 2 else systemMaxVolume

    val maxVolumePercentage: Int
        get() = if (isLoudnessGainSupported) MAX_VOLUME_PERCENTAGE_BOOST else MAX_VOLUME_PERCENTAGE_NORMAL

    var currentVolume: Int by mutableIntStateOf(audioManager.currentStreamVolume)
        private set

    var volumePercentage: Int by mutableIntStateOf(calculateVolumePercentage())
        private set

    fun updateVolumePercentage(percentage: Int) {
        val maxPercentage = if (isVolumeBoostEnabled) {
            MAX_VOLUME_PERCENTAGE_BOOST
        } else {
            maxVolumePercentage
        }
        val clampedPercentage = percentage.coerceIn(0, maxPercentage)
        val targetVolume = (clampedPercentage * systemMaxVolume) / MAX_VOLUME_PERCENTAGE_NORMAL
        Logger.debug(TAG, "Restore player volume: percentage=$clampedPercentage, targetVolume=$targetVolume")

        setVolume(targetVolume)
    }

    fun increaseVolume(shouldShowVolumePanel: Boolean = false) {
        setVolume(currentVolume + 1, shouldShowVolumePanel)
    }

    fun decreaseVolume(shouldShowVolumePanel: Boolean = false) {
        setVolume(currentVolume - 1, shouldShowVolumePanel)
    }

    fun handleLifecycle(disposableEffectScope: DisposableEffectScope): DisposableEffectResult = with(disposableEffectScope) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == VOLUME_CHANGED_ACTION) {
                    val systemVolume = audioManager.currentStreamVolume
                    if (currentVolume > systemMaxVolume && systemVolume == systemMaxVolume) return
                    currentVolume = systemVolume
                    volumePercentage = calculateVolumePercentage()
                    if (isVolumeBoostEnabled) {
                        applyVolumeBoost(0)
                    }
                }
            }
        }

        context.registerReceiver(receiver, IntentFilter(VOLUME_CHANGED_ACTION))

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    suspend fun initialize() {
        val player = player as? MediaController ?: return
        isLoudnessGainSupported = isVolumeBoostEnabled && player.isLoudnessGainSupported()
        val loudnessGain = player.getLoudnessGain()
        val systemVolume = audioManager.currentStreamVolume

        if (loudnessGain > 0 && isLoudnessGainSupported) {
            if (systemVolume < systemMaxVolume) {
                currentVolume = systemVolume
                volumePercentage = calculateVolumePercentage()
                player.setLoudnessGain(0)
            } else {
                val boostVolume = (loudnessGain * systemMaxVolume) / MAX_BOOST_GAIN_MB
                currentVolume = systemMaxVolume + boostVolume
                volumePercentage = calculateVolumePercentage()
            }
        }

        player.listen { events ->
            if (events.contains(Player.EVENT_AUDIO_SESSION_ID)) {
                scope.launch {
                    isLoudnessGainSupported = isVolumeBoostEnabled && player.isLoudnessGainSupported()
                }
            }
        }
    }

    private fun setVolume(volume: Int, shouldShowVolumePanel: Boolean = false) {
        val clampedVolume = volume.coerceIn(0, maximumAllowedVolume)
        currentVolume = clampedVolume
        volumePercentage = calculateVolumePercentage()

        Logger.debug(TAG, "Set player volume: current=$clampedVolume, percentage=$volumePercentage, boostEnabled=$isVolumeBoostEnabled")

        if (!isVolumeBoostEnabled) {
            setSystemVolume(clampedVolume, shouldShowVolumePanel)
            applyVolumeBoost(0)
            return
        }

        if (clampedVolume <= systemMaxVolume) {
            setSystemVolume(clampedVolume, shouldShowVolumePanel)
            applyVolumeBoost(0)
        } else {
            setSystemVolume(systemMaxVolume, shouldShowVolumePanel)
            applyVolumeBoost(clampedVolume - systemMaxVolume)
        }
    }

    private val maximumAllowedVolume: Int
        get() = if (isVolumeBoostEnabled) systemMaxVolume * 2 else maxVolume

    private fun setSystemVolume(volume: Int, shouldShowVolumePanel: Boolean) {
        val shouldShowUi = shouldShowVolumePanel || (shouldShowVolumePanelIfHeadsetIsOn && audioManager.isHeadsetOn)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            volume.coerceIn(0, systemMaxVolume),
            if (shouldShowUi) AudioManager.FLAG_SHOW_UI else 0,
        )
    }

    private fun applyVolumeBoost(volume: Int) {
        val player = player as? MediaController ?: return
        val gainMillibels = (volume.toFloat() / systemMaxVolume * MAX_BOOST_GAIN_MB).toInt()

        try {
            player.setLoudnessGain(gainMillibels)
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to apply volume boost", e)
        }
    }

    private fun calculateVolumePercentage(): Int = (currentVolume.toFloat() / systemMaxVolume * MAX_VOLUME_PERCENTAGE_NORMAL).toInt()

    private val AudioManager.currentStreamVolume: Int
        get() = getStreamVolume(AudioManager.STREAM_MUSIC)

    private val AudioManager.isHeadsetOn: Boolean
        get() = getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && device.type == AudioDeviceInfo.TYPE_USB_HEADSET)
        }

    companion object {
        private const val TAG = "VolumeState"
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val MAX_VOLUME_PERCENTAGE_NORMAL = 100
        private const val MAX_VOLUME_PERCENTAGE_BOOST = 200
        private const val MAX_BOOST_GAIN_MB = 2000
    }
}
