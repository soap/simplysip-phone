package com.simplysip.phone.call

import android.os.SystemClock
import com.simplysip.phone.audio.AudioRoute
import com.simplysip.phone.audio.AudioRouteManager
import com.simplysip.phone.audio.AudioRouteState
import com.simplysip.phone.audio.CallSounds
import com.simplysip.phone.core.coroutines.ApplicationScope
import com.simplysip.phone.sip.engine.CallId
import com.simplysip.phone.sip.engine.RegistrationState
import com.simplysip.phone.sip.engine.SipEngine
import com.simplysip.phone.sip.engine.call.CallInput
import com.simplysip.phone.sip.engine.call.CallState
import com.simplysip.phone.sip.engine.call.CallStateMachine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * The single owner of the call (spec §4.4): feeds engine events and user actions into the
 * [CallStateMachine] and drives the side effects of each state — audio session, ringtone,
 * ringback and the ongoing-call foreground service. Lives for the whole process.
 */
@Singleton
class CallController @Inject constructor(
    private val engine: SipEngine,
    private val audio: AudioRouteManager,
    private val sounds: CallSounds,
    private val service: OngoingCallServiceStarter,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val machine = CallStateMachine { SystemClock.elapsedRealtime() }
    val state: StateFlow<CallState> = machine.state
    val audioRoute: StateFlow<AudioRouteState> = audio.state
    val registration: StateFlow<RegistrationState> = engine.registrationState

    private val _errors = MutableSharedFlow<CallError>(extraBufferCapacity = 8)
    /** One-off problems to show the user, e.g. why a call could not be placed. */
    val errors: SharedFlow<CallError> = _errors.asSharedFlow()

    // Main thread: audio and service APIs expect it, and it serialises everything below.
    private val scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate)
    private var dismissJob: Job? = null

    init {
        scope.launch { engine.observeEvents().collect(machine::onEvent) }
        scope.launch {
            var previous: CallState = CallState.Idle
            machine.state.collect { next ->
                onStateChanged(previous, next)
                previous = next
            }
        }
    }

    fun dial(destination: String) = action {
        val number = destination.trim()
        when {
            number.isEmpty() -> _errors.tryEmit(CallError.EmptyNumber)
            engine.registrationState.value !is RegistrationState.Registered -> _errors.tryEmit(CallError.NotRegistered)
            state.value is CallState.Live -> _errors.tryEmit(CallError.AlreadyInCall)
            else -> {
                val id = engine.makeCall(number)
                machine.apply(CallInput.Dialled(id, number))
            }
        }
    }

    fun answer() = withIncoming { id ->
        machine.apply(CallInput.Answering(id))
        engine.answerCall(id)
    }

    /** Hang up, cancel or reject, depending on the state. */
    fun hangup() = withLive { call ->
        machine.apply(CallInput.HangingUp(call.callId))
        if (call is CallState.Incoming && !call.answering) engine.rejectCall(call.callId) else engine.hangup(call.callId)
    }

    fun toggleMute() = withActive { engine.setMuted(it.callId, !it.muted) }

    fun toggleHold() = withActive { engine.setHold(it.callId, !it.onHold) }

    fun sendDtmf(digit: Char) = withActive { engine.sendDtmf(it.callId, digit.toString()) }

    fun selectRoute(route: AudioRoute) = audio.select(route)

    fun dismissEnded() = machine.apply(CallInput.Dismissed)

    private fun onStateChanged(previous: CallState, next: CallState) {
        // Ringtone only while an incoming call waits for the user.
        if (next is CallState.Incoming && !next.answering && !next.hangingUp) sounds.startRinging() else sounds.stopRinging()

        // Ringback when the far end rings but sends no audio of its own.
        if (next is CallState.Outgoing && next.ringing && !next.earlyMedia && !next.hangingUp) sounds.startRingback() else sounds.stopRingback()

        // Voice audio session from dialling/answering until the call is over.
        val needsAudio = next is CallState.Outgoing || next is CallState.Active ||
            (next is CallState.Incoming && next.answering)
        if (needsAudio) audio.start() else audio.stop()

        // Keep the microphone and process alive in the background (Phase 4 → Core-Telecom).
        when {
            next is CallState.Outgoing || next is CallState.Active -> if (!previous.isOngoing) service.start()
            !next.isOngoing -> if (previous.isOngoing) service.stop()
        }

        dismissJob?.cancel()
        if (next is CallState.Ended) {
            dismissJob = scope.launch {
                delay(ENDED_SCREEN_MILLIS)
                machine.apply(CallInput.Dismissed)
            }
        }
    }

    private val CallState.isOngoing get() = this is CallState.Outgoing || this is CallState.Active

    private fun withIncoming(block: suspend (CallId) -> Unit) = action {
        (state.value as? CallState.Incoming)?.let { block(it.callId) }
    }

    private fun withLive(block: suspend (CallState.Live) -> Unit) = action {
        (state.value as? CallState.Live)?.let { block(it) }
    }

    private fun withActive(block: suspend (CallState.Active) -> Unit) = action {
        (state.value as? CallState.Active)?.let { block(it) }
    }

    private fun action(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errors.tryEmit(CallError.Engine(e.message ?: e::class.simpleName.orEmpty()))
            }
        }
    }

    private companion object {
        const val ENDED_SCREEN_MILLIS = 2_000L
    }
}

sealed interface CallError {
    data object EmptyNumber : CallError
    data object NotRegistered : CallError
    data object AlreadyInCall : CallError
    /** Technical detail from the SIP engine; shown as is. */
    data class Engine(val detail: String) : CallError
}
