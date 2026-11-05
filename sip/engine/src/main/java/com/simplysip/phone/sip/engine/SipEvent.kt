package com.simplysip.phone.sip.engine

sealed interface RegistrationState {
    data object Unregistered : RegistrationState
    data object Registering : RegistrationState
    data class Registered(val expiresInSeconds: Int) : RegistrationState

    /** [statusCode] is the SIP response code when the failure came from the server. */
    data class Failed(val statusCode: Int?, val reason: String) : RegistrationState
}

sealed interface SipEvent {
    data class Registration(val state: RegistrationState) : SipEvent

    data class IncomingCall(
        val callId: CallId,
        val remoteUri: String,
        val remoteDisplayName: String?,
    ) : SipEvent

    data class CallProgress(val callId: CallId, val statusCode: Int) : SipEvent

    data class CallConnected(val callId: CallId) : SipEvent

    data class CallHoldChanged(val callId: CallId, val held: Boolean) : SipEvent

    data class CallMuteChanged(val callId: CallId, val muted: Boolean) : SipEvent

    data class CallEnded(
        val callId: CallId,
        val statusCode: Int?,
        val reason: String?,
    ) : SipEvent

    data class Error(val message: String, val cause: Throwable? = null) : SipEvent
}
