package com.simplysip.phone.sip.engine.pjsip

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simplysip.phone.sip.engine.MediaEncryption
import com.simplysip.phone.sip.engine.RegistrationState
import com.simplysip.phone.sip.engine.SipAccountConfig
import com.simplysip.phone.sip.engine.SipTransport
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device checks of the native stack without a SIP server: the libraries load, PJSUA2 starts
 * with UDP/TCP/TLS transports, and a registration that cannot succeed ends in Failed.
 */
@RunWith(AndroidJUnit4::class)
class PjsipEngineSmokeTest {
    private lateinit var engine: PjsipEngine
    private lateinit var caBundle: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        caBundle = AndroidCaBundle.write(File(context.cacheDir, "pjsip-test/ca.pem"))
            ?: error("no system CA certificates found")
        engine = PjsipEngine(
            PjsipEngine.Options(
                userAgent = "SimplySIP-Test",
                caBundle = caBundle,
                logLevel = 4,
                logSink = { _, msg -> Log.d("pjsip-test", msg) },
            ),
        )
    }

    @After
    fun tearDown() = runBlocking { engine.shutdown() }

    @Test
    fun caBundleContainsSystemCertificates() {
        val count = caBundle.readLines().count { it == "-----BEGIN CERTIFICATE-----" }
        assertTrue("only $count certificates", count > 50)
    }

    @Test
    fun initializesWithAllTransports() = runBlocking {
        engine.initialize()
        engine.initialize() // idempotent
        // TLS transport exists: registering over TLS gets past transport selection.
        engine.registerAccount(config(SipTransport.TLS))
        assertTrue(engine.registrationState.value != RegistrationState.Unregistered)
    }

    @Test
    fun unreachableRegistrarEndsInFailed() = runBlocking {
        engine.initialize()
        engine.registerAccount(config(SipTransport.UDP))
        val state = withTimeout(30_000) {
            engine.registrationState.first { it is RegistrationState.Failed || it is RegistrationState.Registered }
        }
        Log.i("pjsip-test", "final state: $state")
        assertTrue("expected Failed, got $state", state is RegistrationState.Failed)
    }

    @Test
    fun unregisterReturnsToUnregistered() = runBlocking {
        engine.initialize()
        engine.registerAccount(config(SipTransport.TCP))
        engine.unregisterAccount()
        assertTrue(engine.registrationState.value == RegistrationState.Unregistered)
    }

    private fun config(transport: SipTransport) = SipAccountConfig(
        domain = "nonexistent.invalid", // RFC 6761: never resolves
        username = "1001",
        password = "not-a-real-secret",
        transport = transport,
        mediaEncryption = MediaEncryption.NONE,
        registrationExpirySeconds = 60,
    )
}
