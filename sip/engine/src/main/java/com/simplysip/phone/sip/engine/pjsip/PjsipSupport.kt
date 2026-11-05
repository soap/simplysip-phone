package com.simplysip.phone.sip.engine.pjsip

import java.io.File

internal object PjsipLibrary {
    @Volatile private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        System.loadLibrary("c++_shared")
        System.loadLibrary("pjsua2")
        loaded = true
    }
}

/** Small helpers for `"Display" <sip:user@host>` strings from PJSIP. */
object SipUris {
    private val nameAddr = Regex("""^\s*(?:"([^"]*)"|([^<]*?))\s*<([^>]+)>""")

    fun addressOf(value: String): String = nameAddr.find(value)?.groupValues?.get(3) ?: value.trim()

    fun displayNameOf(value: String): String? {
        val m = nameAddr.find(value) ?: return null
        return (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim().ifEmpty { null }
    }
}

/**
 * PJSIP's OpenSSL cannot read Android's certificate store directly, so concatenate the system
 * CA certificates (PEM files) into one bundle for `TlsConfig.caListFile`.
 */
object AndroidCaBundle {
    private val systemDirs = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")

    fun write(target: File): File? {
        val dir = systemDirs.map(::File).firstOrNull { it.isDirectory } ?: return null
        val certs = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        if (certs.isEmpty()) return null
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.bufferedWriter().use { out ->
            certs.forEach { cert ->
                // Each file holds the PEM block followed by a text dump; keep the PEM only.
                val pem = cert.readText().substringBefore("-----END CERTIFICATE-----", "")
                if (pem.contains("-----BEGIN CERTIFICATE-----")) {
                    out.append(pem.substring(pem.indexOf("-----BEGIN CERTIFICATE-----")))
                    out.append("-----END CERTIFICATE-----\n")
                }
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }
}
