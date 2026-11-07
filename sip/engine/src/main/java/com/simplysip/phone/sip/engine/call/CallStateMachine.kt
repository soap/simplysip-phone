package com.simplysip.phone.sip.engine.call

import com.simplysip.phone.sip.engine.CallId
import com.simplysip.phone.sip.engine.SipEvent
import com.simplysip.phone.sip.engine.SipUris
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Everything that can move the call state: engine events and what the user did. */
sealed interface CallInput {
    data class Dialled(val callId: CallId, val destination: String) : CallInput
    data class Answering(val callId: CallId) : CallInput

    /** Hang up, cancel or reject — whichever fits the current state. */
    data class HangingUp(val callId: CallId) : CallInput
    data class Event(val event: SipEvent) : CallInput

    /** Leave [CallState.Ended] for [CallState.Idle]. */
    data object Dismissed : CallInput
}

/**
 * Pure transition function. Events for another call, repeated events and late events after the
 * call ended leave the state unchanged (spec §4.4: idempotent, order-tolerant).
 */
object CallReducer {
    fun reduce(state: CallState, input: CallInput, now: Long): CallState = when (input) {
        is CallInput.Dialled -> if (state.isFree) {
            CallState.Outgoing(input.callId, CallParty(input.destination.trim()))
        } else {
            state
        }

        is CallInput.Answering -> when (state) {
            is CallState.Incoming -> if (state.callId == input.callId) state.copy(answering = true) else state
            else -> state
        }

        is CallInput.HangingUp -> when {
            state !is CallState.Live || state.callId != input.callId -> state
            state is CallState.Outgoing -> state.copy(hangingUp = true)
            state is CallState.Incoming -> state.copy(hangingUp = true)
            state is CallState.Active -> state.copy(hangingUp = true)
            else -> state
        }

        CallInput.Dismissed -> if (state is CallState.Ended) CallState.Idle else state

        is CallInput.Event -> onEvent(state, input.event, now)
    }

    private fun onEvent(state: CallState, event: SipEvent, now: Long): CallState = when (event) {
        is SipEvent.IncomingCall -> if (state.isFree) {
            CallState.Incoming(
                event.callId,
                CallParty(SipUris.userOf(event.remoteUri), event.remoteDisplayName),
            )
        } else {
            state // single call: the engine already answered the second INVITE with 486
        }

        is SipEvent.CallProgress -> state.ifCall(event.callId) {
            if (it is CallState.Outgoing && event.statusCode in 180..189) it.copy(ringing = true) else it
        }

        is SipEvent.CallMediaChanged -> state.ifCall(event.callId) {
            when (it) {
                is CallState.Outgoing -> it.copy(earlyMedia = event.active)
                is CallState.Active -> it.copy(remoteHold = event.remoteHold)
                else -> it
            }
        }

        is SipEvent.CallConnected -> state.ifCall(event.callId) {
            when (it) {
                is CallState.Outgoing -> CallState.Active(it.callId, it.remote, now, hangingUp = it.hangingUp)
                is CallState.Incoming -> CallState.Active(it.callId, it.remote, now, hangingUp = it.hangingUp)
                else -> it // already active
            }
        }

        is SipEvent.CallMuteChanged -> state.ifCall(event.callId) {
            if (it is CallState.Active) it.copy(muted = event.muted) else it
        }

        is SipEvent.CallHoldChanged -> state.ifCall(event.callId) {
            if (it is CallState.Active) it.copy(onHold = event.held) else it
        }

        is SipEvent.CallEnded -> state.ifCall(event.callId) {
            CallState.Ended(
                callId = it.callId,
                remote = it.remote,
                cause = endCause(it, event.statusCode, event.reason),
                durationMillis = (it as? CallState.Active)?.let { a -> (now - a.connectedAtMillis).coerceAtLeast(0) },
            )
        }

        is SipEvent.Registration, is SipEvent.Error -> state
    }

    private fun endCause(call: CallState.Live, code: Int?, reason: String?): EndCause = when (call) {
        is CallState.Active -> if (call.hangingUp) EndCause.LocalHangup else EndCause.RemoteHangup
        is CallState.Incoming -> if (call.hangingUp) EndCause.Rejected else EndCause.Missed
        is CallState.Outgoing -> when {
            call.hangingUp -> EndCause.Cancelled
            code == 486 || code == 600 -> EndCause.Busy
            code == 603 -> EndCause.Declined
            code == 408 || code == 480 -> EndCause.NoAnswer
            code == 404 || code == 484 || code == 604 -> EndCause.NotFound
            else -> EndCause.Failed(code, reason)
        }
    }

    private val CallState.isFree get() = this is CallState.Idle || this is CallState.Ended

    private inline fun CallState.ifCall(id: CallId, transform: (CallState.Live) -> CallState): CallState =
        if (this is CallState.Live && callId == id) transform(this) else this
}

/** Holds the current [CallState]; thread-safe, every input is applied atomically. */
class CallStateMachine(private val clock: () -> Long) {
    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    fun apply(input: CallInput) = _state.update { CallReducer.reduce(it, input, clock()) }

    fun onEvent(event: SipEvent) = apply(CallInput.Event(event))
}
