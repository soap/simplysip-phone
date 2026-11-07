package com.simplysip.phone.ui.debug

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.simplysip.phone.core.coroutines.ApplicationScope
import com.simplysip.phone.core.security.KeystoreCipher
import com.simplysip.phone.sip.engine.SipEngine
import com.simplysip.phone.sip.engine.SipTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Remembers the test bench account between launches (debug builds only, until provisioning in
 * Phase 3). Non-secret fields in private prefs; the password only as Keystore AES-GCM ciphertext.
 */
@Singleton
class DebugAccountStore @Inject constructor(
    @ApplicationContext context: Context,
    private val engine: SipEngine,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("debug_sip_account", Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher("debug_sip_password")

    fun load(): SipDebugViewModel.Form? {
        val domain = prefs.getString(KEY_DOMAIN, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null)
            ?.let { blob -> runCatching { cipher.decrypt(blob).decodeToString() }.getOrNull() }
            ?: return null // key lost (e.g. restored device): ask again
        val defaults = SipDebugViewModel.Form()
        return SipDebugViewModel.Form(
            domain = domain,
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            password = password,
            transport = SipTransport.entries.firstOrNull { it.name == prefs.getString(KEY_TRANSPORT, null) }
                ?: defaults.transport,
            port = prefs.getString(KEY_PORT, defaults.port).orEmpty(),
            outboundProxy = prefs.getString(KEY_PROXY, "").orEmpty(),
            srtp = prefs.getBoolean(KEY_SRTP, false),
        )
    }

    fun save(form: SipDebugViewModel.Form) = prefs.edit {
        putString(KEY_DOMAIN, form.domain)
        putString(KEY_USERNAME, form.username)
        putString(KEY_PASSWORD, cipher.encrypt(form.password.encodeToByteArray()))
        putString(KEY_TRANSPORT, form.transport.name)
        putString(KEY_PORT, form.port)
        putString(KEY_PROXY, form.outboundProxy)
        putBoolean(KEY_SRTP, form.srtp)
    }

    fun clear() {
        prefs.edit { clear() }
        runCatching { cipher.deleteKey() }
    }

    /** Register with the saved account at app start, if there is one. */
    fun registerSaved() {
        val form = load() ?: return
        scope.launch {
            runCatching {
                engine.initialize()
                engine.registerAccount(form.toConfig())
            }.onFailure { Log.w(TAG, "auto-register failed", it) }
        }
    }

    private companion object {
        const val TAG = "DebugAccountStore"
        const val KEY_DOMAIN = "domain"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_TRANSPORT = "transport"
        const val KEY_PORT = "port"
        const val KEY_PROXY = "proxy"
        const val KEY_SRTP = "srtp"
    }
}
