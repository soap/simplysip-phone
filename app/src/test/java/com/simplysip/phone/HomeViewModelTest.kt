package com.simplysip.phone

import com.simplysip.phone.sip.engine.RegistrationState
import com.simplysip.phone.sip.engine.SipAccountConfig
import com.simplysip.phone.sip.engine.fake.FakeSipEngine
import com.simplysip.phone.ui.home.HomeViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeViewModelTest {
    @Test
    fun `exposes the engine registration state`() = runTest {
        val engine = FakeSipEngine()
        val viewModel = HomeViewModel(engine)
        assertEquals(RegistrationState.Unregistered, viewModel.registrationState.value)

        engine.registerAccount(SipAccountConfig(domain = "acme.example.com", username = "1001", password = "x"))
        assertEquals(RegistrationState.Registered(300), viewModel.registrationState.value)
    }
}
