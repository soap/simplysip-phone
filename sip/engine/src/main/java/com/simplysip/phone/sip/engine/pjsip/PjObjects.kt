package com.simplysip.phone.sip.engine.pjsip

import com.simplysip.phone.sip.engine.CallId
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.LogEntry
import org.pjsip.pjsua2.LogWriter
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnCallStateParam
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnRegStateParam
import org.pjsip.pjsua2.pjmedia_type
import org.pjsip.pjsua2.pjsip_inv_state
import org.pjsip.pjsua2.pjsua_call_media_status

// SWIG director subclasses. Callbacks arrive on the pjsip thread (see PjsipEngine). Exceptions must
// not cross back into native code, so every callback is guarded.

internal class PjAccount(private val engine: PjsipEngine) : Account() {
    override fun onRegState(prm: OnRegStateParam) = guarded(engine) {
        engine.onRegistrationResult(prm.code.toInt(), prm.reason.orEmpty(), prm.expiration.toLong())
    }

    override fun onIncomingCall(prm: OnIncomingCallParam) = guarded(engine) {
        engine.onIncomingCall(this, prm.callId)
    }
}

internal class PjCall(
    private val engine: PjsipEngine,
    account: Account,
    val id: CallId,
    pjCallId: Int = INVALID_ID,
) : Call(account, pjCallId) {

    private var audio: AudioMedia? = null
    private var muted = false

    override fun onCallState(prm: OnCallStateParam) = guarded(engine) {
        val ci = info
        when (ci.state) {
            pjsip_inv_state.PJSIP_INV_STATE_EARLY,
            pjsip_inv_state.PJSIP_INV_STATE_CALLING -> engine.onCallProgress(id, ci.lastStatusCode.toInt())
            pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> engine.onCallConfirmed(id)
            pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> {
                audio = null
                engine.onCallDisconnected(id, ci.lastStatusCode.toInt().takeIf { it > 0 }, ci.lastReason)
            }
            else -> Unit
        }
        ci.delete()
    }

    override fun onCallMediaState(prm: OnCallMediaStateParam) = guarded(engine) {
        engine.onCallMedia(this)
    }

    data class MediaState(val active: Boolean, val remoteHold: Boolean)

    /**
     * Wire the audio stream to the sound device (spec §5.4). Called again after every re-INVITE;
     * the conference bridge ignores repeated connects of the same ports.
     */
    fun connectAudio(ep: Endpoint): MediaState {
        val ci = info
        var active = false
        var remoteHold = false
        try {
            val media = ci.media
            audio = null
            for (i in 0 until media.size) {
                val m = media[i]
                if (m.type != pjmedia_type.PJMEDIA_TYPE_AUDIO) continue
                when (m.status) {
                    pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE -> active = true
                    // Keep audio connected: the far end may still play music on hold.
                    pjsua_call_media_status.PJSUA_CALL_MEDIA_REMOTE_HOLD -> remoteHold = true
                    else -> continue
                }
                val am = getAudioMedia(i)
                val adm = ep.audDevManager()
                am.startTransmit(adm.playbackDevMedia)
                if (!muted) adm.captureDevMedia.startTransmit(am)
                audio = am
            }
        } finally {
            ci.delete()
        }
        return MediaState(active, remoteHold)
    }

    fun setMuted(ep: Endpoint, mute: Boolean) {
        muted = mute
        val am = audio ?: return
        val capture = ep.audDevManager().captureDevMedia
        if (mute) capture.stopTransmit(am) else capture.startTransmit(am)
    }

    private companion object {
        const val INVALID_ID = -1
    }
}

internal class PjLogWriter(private val sink: (Int, String) -> Unit) : LogWriter() {
    override fun write(entry: LogEntry) {
        runCatching { sink(entry.level, entry.msg.trimEnd()) }
    }
}

private inline fun guarded(engine: PjsipEngine, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        engine.reportError("pjsip callback failed", t)
    }
}
