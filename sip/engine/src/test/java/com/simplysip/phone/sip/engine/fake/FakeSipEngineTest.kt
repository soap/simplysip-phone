package com.simplysip.phone.sip.engine.fake

import app.cash.turbine.test
import com.simplysip.phone.sip.engine.RegistrationState
import com.simplysip.phone.sip.engine.SipAccountConfig
import com.simplysip.phone.sip.engine.SipEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeSipEngineTest {
    private val config = SipAccountConfig(domain = "acme.example.com", username = "1001", password = "x")

    @Test
    fun `register moves through Registering to Registered`() = runTest {
        val engine = FakeSipEngine()
        engine.observeEvents().test {
            engine.registerAccount(config)
            assertEquals(SipEvent.Registration(RegistrationState.Registering), awaitItem())
            assertEquals(SipEvent.Registration(RegistrationState.Registered(300)), awaitItem())
        }
        assertEquals(RegistrationState.Registered(300), engine.registrationState.value)
    }

    @Test
    fun `hangup is idempotent`() = runTest {
        val engine = FakeSipEngine()
        engine.registerAccount(config)
        engine.observeEvents().test {
            val id = engine.makeCall("1002")
            assertEquals(SipEvent.CallProgress(id, 180), awaitItem())
            assertEquals(SipEvent.CallConnected(id), awaitItem())
            engine.hangup(id)
            engine.hangup(id)
            assertEquals(SipEvent.CallEnded(id, 200, "Normal clearing"), awaitItem())
            expectNoEvents()
        }
    }
}
