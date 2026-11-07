package com.simplysip.phone.sip.engine.fake

import com.simplysip.phone.sip.engine.CallId
import com.simplysip.phone.sip.engine.RegistrationState
import com.simplysip.phone.sip.engine.SipAccountConfig
import com.simplysip.phone.sip.engine.SipEngine
import com.simplysip.phone.sip.engine.SipEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory engine for UI work and tests until the PJSUA2 engine exists. Registration always
 * succeeds and outgoing calls connect immediately.
 */
class FakeSipEngine : SipEngine {
    private val _registrationState =
        MutableStateFlow<RegistrationState>(RegistrationState.Unregistered)
    override val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val events = MutableSharedFlow<SipEvent>(extraBufferCapacity = 64)
    private val nextCallId = AtomicInteger(1)
    private val activeCalls = mutableSetOf<CallId>()
    private var account: SipAccountConfig? = null

    override fun observeEvents(): Flow<SipEvent> = events.asSharedFlow()

    override suspend fun initialize() = Unit

    override suspend fun registerAccount(config: SipAccountConfig) {
        account = config
        setRegistration(RegistrationState.Registering)
        setRegistration(RegistrationState.Registered(config.registrationExpirySeconds))
    }

    override suspend fun unregisterAccount() {
        account = null
        setRegistration(RegistrationState.Unregistered)
    }

    override suspend fun refreshRegistration() {
        account?.let { if (_registrationState.value !is RegistrationState.Registered) registerAccount(it) }
    }

    override suspend fun onNetworkChanged() = refreshRegistration()

    override suspend fun makeCall(destination: String): CallId {
        check(account != null) { "not registered" }
        require(destination.isNotBlank()) { "destination is blank" }
        val id = CallId("fake-${nextCallId.getAndIncrement()}")
        activeCalls += id
        events.emit(SipEvent.CallProgress(id, 180))
        events.emit(SipEvent.CallConnected(id))
        return id
    }

    /** Test hook: simulate an INVITE arriving from [remoteUri]. */
    suspend fun simulateIncomingCall(remoteUri: String, displayName: String? = null): CallId {
        val id = CallId("fake-${nextCallId.getAndIncrement()}")
        activeCalls += id
        events.emit(SipEvent.IncomingCall(id, remoteUri, displayName))
        return id
    }

    override suspend fun answerCall(callId: CallId) {
        if (callId in activeCalls) events.emit(SipEvent.CallConnected(callId))
    }

    override suspend fun rejectCall(callId: CallId) = end(callId, 486, "Busy Here")

    override suspend fun hangup(callId: CallId) = end(callId, 200, "Normal clearing")

    override suspend fun setMuted(callId: CallId, muted: Boolean) {
        if (callId in activeCalls) events.emit(SipEvent.CallMuteChanged(callId, muted))
    }

    override suspend fun setHold(callId: CallId, held: Boolean) {
        if (callId in activeCalls) events.emit(SipEvent.CallHoldChanged(callId, held))
    }

    override suspend fun sendDtmf(callId: CallId, digits: String) {
        require(digits.all { it in DTMF_DIGITS }) { "invalid DTMF digits" }
    }

    override suspend fun shutdown() {
        activeCalls.toList().forEach { hangup(it) }
        unregisterAccount()
    }

    // Idempotent: ending an unknown or already-ended call is a no-op (spec §4.4).
    private suspend fun end(callId: CallId, code: Int, reason: String) {
        if (activeCalls.remove(callId)) events.emit(SipEvent.CallEnded(callId, code, reason))
    }

    private suspend fun setRegistration(state: RegistrationState) {
        _registrationState.value = state
        events.emit(SipEvent.Registration(state))
    }

    private companion object {
        const val DTMF_DIGITS = "0123456789*#ABCD"
    }
}
