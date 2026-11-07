package com.simplysip.phone.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local call sounds: the ringtone (+ vibration) for an incoming call, and ringback for an
 * outgoing call when the network sends 180 without early media.
 */
@Singleton
class CallSounds @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null

    @MainThread
    fun startRinging() {
        if (ringtone != null) return
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= 28) isLooping = true
            play()
        }
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT && vibrator?.hasVibrator() == true) {
            vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION, 0))
        }
    }

    @MainThread
    fun stopRinging() {
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
    }

    @MainThread
    fun startRingback() {
        if (ringback != null) return
        ringback = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        }.getOrNull()
    }

    @MainThread
    fun stopRingback() {
        ringback?.run {
            stopTone()
            release()
        }
        ringback = null
    }

    @MainThread
    fun stopAll() {
        stopRinging()
        stopRingback()
    }

    private companion object {
        const val RINGBACK_VOLUME = 80
        val VIBRATION = longArrayOf(0, 1000, 1000)
    }
}
