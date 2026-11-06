package com.simplysip.phone.sip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SipAccountConfigTest {
    private val config = SipAccountConfig(
        domain = "acme.sip.example.com",
        username = "1001",
        password = "s3cret-value",
    )

    @Test
    fun `toString never contains the password`() {
        assertFalse(config.toString().contains("s3cret-value"))
    }

    @Test
    fun `aor is built from username and tenant domain`() {
        assertEquals("sip:1001@acme.sip.example.com", config.aor)
    }

    @Test
    fun `port defaults to the transport port`() {
        assertEquals(5061, config.port)
        assertEquals(5060, config.copy(transport = SipTransport.TCP, port = 5060).port)
    }

    @Test
    fun `rejects blank domain and bad port`() {
        assertThrows(IllegalArgumentException::class.java) { config.copy(domain = " ") }
        assertThrows(IllegalArgumentException::class.java) { config.copy(port = 0) }
    }

    @Test
    fun `rejects values that would make an invalid SIP URI`() {
        listOf("sip:acme.example.com", "acme.example.com:5060", "acme .example.com", "acme.example.com.")
            .forEach { assertThrows(it, IllegalArgumentException::class.java) { config.copy(domain = it) } }
        listOf("1001@acme.example.com", "10 01", "sip:1001")
            .forEach { assertThrows(it, IllegalArgumentException::class.java) { config.copy(username = it) } }
        listOf("sip:node1.example.com", "node1.example.com;transport=tcp")
            .forEach { assertThrows(it, IllegalArgumentException::class.java) { config.copy(outboundProxy = it) } }
    }

    @Test
    fun `accepts tenant domains, IPs and host-port proxies`() {
        config.copy(domain = "dmo-2.sip.simplysip.cloud", outboundProxy = "203.0.113.10:5060")
        config.copy(domain = "10.0.2.2", outboundProxy = "node1.example.com")
    }
}
