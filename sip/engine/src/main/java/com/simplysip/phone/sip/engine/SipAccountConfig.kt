package com.simplysip.phone.sip.engine

/**
 * One SIP account. With sip-manager, [domain] is the tenant domain (`<slug>.<suffix>`, plan D6)
 * and [outboundProxy] is the PBX node.
 */
data class SipAccountConfig(
    val domain: String,
    val username: String,
    val password: String,
    val displayName: String? = null,
    val transport: SipTransport = SipTransport.TLS,
    val port: Int = transport.defaultPort,
    val outboundProxy: String? = null,
    val mediaEncryption: MediaEncryption = MediaEncryption.SRTP_MANDATORY,
    val registrationExpirySeconds: Int = 300,
) {
    init {
        require(domain.isNotBlank()) { "domain is blank" }
        require(HOST.matches(domain)) { "domain must be a bare host name (no sip:, port or spaces): '$domain'" }
        require(username.isNotBlank()) { "username is blank" }
        require(USER.matches(username)) { "username must be the extension only (no @domain or spaces): '$username'" }
        require(port in 1..65535) { "port out of range: $port" }
        outboundProxy?.let {
            require(HOST_PORT.matches(it)) { "outbound proxy must be host or host:port (no sip:): '$it'" }
        }
        require(registrationExpirySeconds > 0) { "registrationExpirySeconds must be positive" }
    }

    val aor: String get() = "sip:$username@$domain"

    // Never let the secret reach logs or crash reports.
    override fun toString(): String =
        "SipAccountConfig(aor=$aor, transport=$transport, port=$port, " +
            "outboundProxy=$outboundProxy, mediaEncryption=$mediaEncryption, password=***)"

    private companion object {
        val HOST = Regex("""[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*""")
        val HOST_PORT = Regex("""${HOST.pattern}(:\d{1,5})?""")

        // RFC 3261 user part: unreserved, escaped and user-unreserved characters.
        val USER = Regex("""[A-Za-z0-9\-_.!~*'()&=+$,;?/%]+""")
    }
}

enum class SipTransport(val defaultPort: Int) {
    UDP(5060),
    TCP(5060),
    TLS(5061),
}

enum class MediaEncryption {
    NONE,
    SRTP_OPTIONAL,
    SRTP_MANDATORY,
}
