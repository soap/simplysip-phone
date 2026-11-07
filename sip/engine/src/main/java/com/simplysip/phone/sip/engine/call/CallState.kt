package com.simplysip.phone.sip.engine.call

import com.simplysip.phone.sip.engine.CallId

/** Who is on the other end. [number] is what the user dialled or the caller's user part. */
data class CallParty(val number: String, val displayName: String? = null) {
    val label: String get() = displayName ?: number
}

/**
 * The one call the app handles (single call in the MVP, plan §5 #6). UI derives everything from
 * this sealed state instead of separate flags (spec §4.4).
 */
sealed interface CallState {
    data object Idle : CallState

    /** A call that still has a dialog. [hangingUp] = the user ended it and we wait for the engine. */
    sealed interface Live : CallState {
        val callId: CallId
        val remote: CallParty
        val hangingUp: Boolean
    }

    /** OUTGOING_CALLING: INVITE sent. [ringing] after 180/183; [earlyMedia] = audio before answer. */
    data class Outgoing(
        override val callId: CallId,
        override val remote: CallParty,
        val ringing: Boolean = false,
        val earlyMedia: Boolean = false,
        override val hangingUp: Boolean = false,
    ) : Live

    /** INCOMING_RINGING: not answered yet. [answering] after the user pressed answer. */
    data class Incoming(
        override val callId: CallId,
        override val remote: CallParty,
        val answering: Boolean = false,
        override val hangingUp: Boolean = false,
    ) : Live

    /** IN_CALL. [connectedAtMillis] is on the machine's clock (elapsed realtime on Android). */
    data class Active(
        override val callId: CallId,
        override val remote: CallParty,
        val connectedAtMillis: Long,
        val muted: Boolean = false,
        val onHold: Boolean = false,
        val remoteHold: Boolean = false,
        override val hangingUp: Boolean = false,
    ) : Live

    /** Shown briefly after the call; [durationMillis] is null when it never connected. */
    data class Ended(
        val callId: CallId,
        val remote: CallParty,
        val cause: EndCause,
        val durationMillis: Long?,
    ) : CallState
}

sealed interface EndCause {
    /** We hung up a connected call. */
    data object LocalHangup : EndCause
    data object RemoteHangup : EndCause
    /** We gave up on an outgoing call before it was answered. */
    data object Cancelled : EndCause
    /** We declined an incoming call. */
    data object Rejected : EndCause
    /** The caller gave up (or another device answered) before we answered. */
    data object Missed : EndCause
    data object Busy : EndCause
    data object Declined : EndCause
    data object NoAnswer : EndCause
    data object NotFound : EndCause
    data class Failed(val statusCode: Int?, val reason: String?) : EndCause
}
