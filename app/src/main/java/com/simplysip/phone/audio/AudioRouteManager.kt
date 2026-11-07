package com.simplysip.phone.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioRoute { EARPIECE, SPEAKER, WIRED_HEADSET, BLUETOOTH }

data class AudioRouteState(
    val current: AudioRoute = AudioRoute.EARPIECE,
    val available: List<AudioRoute> = listOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER),
)

/**
 * Voice-call audio session (spec §5.4): communication mode, audio focus and the output route.
 * PJSIP's Android sound device plays on the voice-call stream, so routing here applies to it.
 *
 * API 31+ uses communication devices (earpiece, speaker, wired, Bluetooth SCO/LE). Older APIs get
 * earpiece/speaker/wired via the speakerphone switch; Bluetooth there is Phase 5.
 */
@Singleton
class AudioRouteManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(AudioRouteState())
    val state: StateFlow<AudioRouteState> = _state.asStateFlow()

    private var active = false
    private var userChoice: AudioRoute? = null
    private var focusRequest: AudioFocusRequest? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onDevicesChanged()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onDevicesChanged()
    }
    private val commDeviceListener: Any? =
        if (Build.VERSION.SDK_INT >= 31) AudioManager.OnCommunicationDeviceChangedListener { publish() } else null

    @MainThread
    fun start() {
        if (active) return
        active = true
        userChoice = null
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(VOICE_ATTRIBUTES)
            .build()
            .also { audioManager.requestAudioFocus(it) }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        if (Build.VERSION.SDK_INT >= 31) {
            audioManager.addOnCommunicationDeviceChangedListener(
                { handler.post(it) },
                commDeviceListener as AudioManager.OnCommunicationDeviceChangedListener,
            )
        }
        applyRoute(defaultRoute(availableRoutes()))
    }

    @MainThread
    fun select(route: AudioRoute) {
        if (!active || route !in availableRoutes()) return
        userChoice = route
        applyRoute(route)
    }

    @MainThread
    fun stop() {
        if (!active) return
        active = false
        userChoice = null
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= 31) {
            audioManager.removeOnCommunicationDeviceChangedListener(
                commDeviceListener as AudioManager.OnCommunicationDeviceChangedListener,
            )
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        _state.value = AudioRouteState()
    }

    /** Headset plugged in or out: follow it unless the user's choice is still available. */
    private fun onDevicesChanged() {
        if (!active) return
        val available = availableRoutes()
        val choice = userChoice?.takeIf { it in available }
        userChoice = choice
        applyRoute(choice ?: defaultRoute(available))
    }

    private fun defaultRoute(available: List<AudioRoute>): AudioRoute = when {
        AudioRoute.WIRED_HEADSET in available -> AudioRoute.WIRED_HEADSET
        AudioRoute.BLUETOOTH in available -> AudioRoute.BLUETOOTH
        AudioRoute.EARPIECE in available -> AudioRoute.EARPIECE
        else -> AudioRoute.SPEAKER // tablets without an earpiece
    }

    private fun applyRoute(route: AudioRoute) {
        if (Build.VERSION.SDK_INT >= 31) {
            communicationDevices()[route]?.let { audioManager.setCommunicationDevice(it) }
        } else {
            // Wired headsets take over from the earpiece by themselves when the speaker is off.
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = route == AudioRoute.SPEAKER
        }
        publish(route)
    }

    private fun publish(assumed: AudioRoute? = null) {
        if (!active) return
        val available = availableRoutes()
        val current = if (Build.VERSION.SDK_INT >= 31) {
            audioManager.communicationDevice?.let(::routeOf) ?: assumed
        } else {
            assumed
        } ?: _state.value.current
        _state.value = AudioRouteState(current, available)
    }

    private fun availableRoutes(): List<AudioRoute> = if (Build.VERSION.SDK_INT >= 31) {
        AudioRoute.entries.filter { it in communicationDevices() }
    } else {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { routeOf(it) }
        AudioRoute.entries.filter { it in outputs && it != AudioRoute.BLUETOOTH }
    }

    @RequiresApi(31)
    private fun communicationDevices(): Map<AudioRoute, AudioDeviceInfo> =
        audioManager.availableCommunicationDevices
            .mapNotNull { device -> routeOf(device)?.let { it to device } }
            .distinctBy { it.first }
            .toMap()

    private fun routeOf(device: AudioDeviceInfo): AudioRoute? = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.WIRED_HEADSET
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioRoute.BLUETOOTH
        else -> if (Build.VERSION.SDK_INT >= 31 && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) AudioRoute.BLUETOOTH else null
    }

    private companion object {
        val VOICE_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
