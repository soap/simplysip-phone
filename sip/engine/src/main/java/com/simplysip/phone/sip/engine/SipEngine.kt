package com.simplysip.phone.sip.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The only way the app talks to the SIP stack (spec §4.3). Implementations must run every native
 * call on their own thread and turn native callbacks into [SipEvent]s.
 */
interface SipEngine {
    val registrationState: StateFlow<RegistrationState>

    suspend fun initialize()
    suspend fun registerAccount(config: SipAccountConfig)
    suspend fun unregisterAccount()

    /** Re-send REGISTER now if the account is not registered (e.g. app back in front). */
    suspend fun refreshRegistration()

    /** The device switched network or got one back: rebind transports and re-register (spec §5.1). */
    suspend fun onNetworkChanged()
    suspend fun makeCall(destination: String): CallId
    suspend fun answerCall(callId: CallId)
    suspend fun rejectCall(callId: CallId)
    suspend fun hangup(callId: CallId)
    suspend fun setMuted(callId: CallId, muted: Boolean)
    suspend fun setHold(callId: CallId, held: Boolean)
    suspend fun sendDtmf(callId: CallId, digits: String)
    suspend fun shutdown()

    fun observeEvents(): Flow<SipEvent>
}

/** Engine-local call handle. Not the SIP Call-ID and never used for authorization (spec §10.3). */
@JvmInline
value class CallId(val value: String)
