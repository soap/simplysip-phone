package com.simplysip.phone.sip.engine

/** Small helpers for `"Display" <sip:user@host>` strings. */
object SipUris {
    private val nameAddr = Regex("""^\s*(?:"([^"]*)"|([^<]*?))\s*<([^>]+)>""")

    fun addressOf(value: String): String = nameAddr.find(value)?.groupValues?.get(3) ?: value.trim()

    fun displayNameOf(value: String): String? {
        val m = nameAddr.find(value) ?: return null
        return (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim().ifEmpty { null }
    }

    /** The number to show for a URI: `sip:202@host;x=y` → `202`. Without a user part, the host. */
    fun userOf(value: String): String {
        val uri = addressOf(value).substringAfter("sips:").substringAfter("sip:")
        return uri.substringBefore('@').substringBefore(';').ifEmpty { uri }
    }
}
