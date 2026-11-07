package com.simplysip.phone.sip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SipUrisTest {
    @Test
    fun `parses quoted display name`() {
        val v = "\"Alice 1002\" <sip:1002@acme.example.com>"
        assertEquals("sip:1002@acme.example.com", SipUris.addressOf(v))
        assertEquals("Alice 1002", SipUris.displayNameOf(v))
    }

    @Test
    fun `parses unquoted display name`() {
        assertEquals("Bob", SipUris.displayNameOf("Bob <sip:1003@acme.example.com>"))
    }

    @Test
    fun `bare uri has no display name`() {
        assertEquals("sip:1004@acme.example.com", SipUris.addressOf(" sip:1004@acme.example.com "))
        assertNull(SipUris.displayNameOf("sip:1004@acme.example.com"))
        assertNull(SipUris.displayNameOf("<sip:1004@acme.example.com>"))
    }

    @Test
    fun `user part strips scheme, host and parameters`() {
        assertEquals("202", SipUris.userOf("sip:202@dmo-2.sip.simplysip.cloud;transport=tcp"))
        assertEquals("1002", SipUris.userOf("\"Alice\" <sips:1002@acme.example.com:5061>"))
        assertEquals("*43", SipUris.userOf("*43"))
        assertEquals("acme.example.com", SipUris.userOf("sip:acme.example.com"))
    }
}
