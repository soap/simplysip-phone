package com.simplysip.phone.sip.engine.call

import com.simplysip.phone.sip.engine.CallId
import com.simplysip.phone.sip.engine.SipEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateMachineTest {
    private var now = 1_000L
    private val machine = CallStateMachine { now }
    private val a = CallId("a")
    private val b = CallId("b")

    private fun event(e: SipEvent) = machine.onEvent(e)
    private val state get() = machine.state.value

    @Test
    fun `outgoing call rings, connects and ends by local hangup`() {
        machine.apply(CallInput.Dialled(a, " 202 "))
        assertEquals(CallState.Outgoing(a, CallParty("202")), state)

        event(SipEvent.CallProgress(a, 100))
        assertEquals(false, (state as CallState.Outgoing).ringing)
        event(SipEvent.CallProgress(a, 180))
        assertEquals(true, (state as CallState.Outgoing).ringing)

        event(SipEvent.CallConnected(a))
        assertEquals(CallState.Active(a, CallParty("202"), connectedAtMillis = 1_000L), state)

        now = 61_000L
        machine.apply(CallInput.HangingUp(a))
        event(SipEvent.CallEnded(a, 200, "Normal"))
        assertEquals(CallState.Ended(a, CallParty("202"), EndCause.LocalHangup, 60_000L), state)

        machine.apply(CallInput.Dismissed)
        assertEquals(CallState.Idle, state)
    }

    @Test
    fun `early media is tracked before answer`() {
        machine.apply(CallInput.Dialled(a, "*43"))
        event(SipEvent.CallMediaChanged(a, active = true, remoteHold = false))
        assertTrue((state as CallState.Outgoing).earlyMedia)
    }

    @Test
    fun `incoming call keeps caller identity and can be rejected`() {
        event(SipEvent.IncomingCall(a, "sip:202@acme.example.com", "ATCom wireless"))
        assertEquals(CallState.Incoming(a, CallParty("202", "ATCom wireless")), state)

        machine.apply(CallInput.HangingUp(a))
        event(SipEvent.CallEnded(a, 486, "Busy Here"))
        assertEquals(EndCause.Rejected, (state as CallState.Ended).cause)
        assertEquals(null, (state as CallState.Ended).durationMillis)
    }

    @Test
    fun `caller giving up is a missed call`() {
        event(SipEvent.IncomingCall(a, "sip:202@acme.example.com", null))
        event(SipEvent.CallEnded(a, 487, "Request Terminated"))
        assertEquals(EndCause.Missed, (state as CallState.Ended).cause)
    }

    @Test
    fun `answered incoming call becomes active and ends by remote hangup`() {
        event(SipEvent.IncomingCall(a, "sip:202@acme.example.com", null))
        machine.apply(CallInput.Answering(a))
        assertTrue((state as CallState.Incoming).answering)
        event(SipEvent.CallConnected(a))
        now = 5_000L
        event(SipEvent.CallEnded(a, 200, null))
        assertEquals(CallState.Ended(a, CallParty("202"), EndCause.RemoteHangup, 4_000L), state)
    }

    @Test
    fun `failed outgoing calls map SIP codes to causes`() {
        val cases = mapOf(
            486 to EndCause.Busy,
            600 to EndCause.Busy,
            603 to EndCause.Declined,
            480 to EndCause.NoAnswer,
            408 to EndCause.NoAnswer,
            404 to EndCause.NotFound,
            503 to EndCause.Failed(503, "Service Unavailable"),
        )
        cases.forEach { (code, cause) ->
            machine.apply(CallInput.Dismissed)
            machine.apply(CallInput.Dialled(a, "999"))
            event(SipEvent.CallEnded(a, code, "Service Unavailable"))
            assertEquals("code $code", cause, (state as CallState.Ended).cause)
        }
    }

    @Test
    fun `cancelling an outgoing call is not a failure`() {
        machine.apply(CallInput.Dialled(a, "202"))
        machine.apply(CallInput.HangingUp(a))
        event(SipEvent.CallEnded(a, 487, "Request Terminated"))
        assertEquals(EndCause.Cancelled, (state as CallState.Ended).cause)
    }

    @Test
    fun `mute, hold and remote hold update the active call`() {
        machine.apply(CallInput.Dialled(a, "202"))
        event(SipEvent.CallConnected(a))
        event(SipEvent.CallMuteChanged(a, true))
        event(SipEvent.CallHoldChanged(a, true))
        event(SipEvent.CallMediaChanged(a, active = false, remoteHold = true))
        val active = state as CallState.Active
        assertTrue(active.muted && active.onHold && active.remoteHold)
    }

    @Test
    fun `duplicate and out-of-order events are ignored`() {
        machine.apply(CallInput.Dialled(a, "202"))
        event(SipEvent.CallConnected(a))
        val connected = state
        now = 9_000L
        event(SipEvent.CallConnected(a)) // duplicate must not reset the timer
        event(SipEvent.CallProgress(a, 180)) // late provisional response
        assertEquals(connected, state)

        event(SipEvent.CallEnded(a, 200, null))
        val ended = state
        event(SipEvent.CallEnded(a, 200, null))
        event(SipEvent.CallConnected(a))
        event(SipEvent.CallMuteChanged(a, true))
        assertEquals(ended, state)
    }

    @Test
    fun `events for another call do not touch the current one`() {
        machine.apply(CallInput.Dialled(a, "202"))
        val before = state
        event(SipEvent.CallConnected(b))
        event(SipEvent.CallEnded(b, 486, null))
        event(SipEvent.IncomingCall(b, "sip:201@acme.example.com", null)) // single call
        machine.apply(CallInput.HangingUp(b))
        assertEquals(before, state)
    }

    @Test
    fun `cannot dial while a call is live, can after it ended`() {
        machine.apply(CallInput.Dialled(a, "202"))
        machine.apply(CallInput.Dialled(b, "201"))
        assertEquals(a, (state as CallState.Outgoing).callId)

        event(SipEvent.CallEnded(a, 486, null))
        machine.apply(CallInput.Dialled(b, "201"))
        assertEquals(b, (state as CallState.Outgoing).callId)
    }
}
