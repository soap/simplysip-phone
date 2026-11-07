package com.simplysip.phone.call

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.simplysip.phone.core.coroutines.ApplicationScope
import com.simplysip.phone.sip.engine.SipEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps the SIP registration alive across app and network transitions (spec §5.1, AT-10):
 * - app back in front → re-register if the last attempt failed (e.g. DNS failed while the app
 *   had no network access in the background);
 * - default network switched (Wi-Fi ↔ mobile) or came back → rebind transports and re-register.
 */
@Singleton
class RegistrationKeeper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: SipEngine,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = launch("foreground") { engine.refreshRegistration() }
        })

        context.getSystemService(ConnectivityManager::class.java)
            .registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private var current: Network? = null
                private var seen = false

                override fun onAvailable(network: Network) {
                    // The first callback only reports the network we started on.
                    val changed = seen && network != current
                    current = network
                    seen = true
                    if (changed) launch("network $network") { engine.onNetworkChanged() }
                }

                override fun onLost(network: Network) {
                    if (network == current) current = null
                }
            })
    }

    private fun launch(reason: String, block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { Log.w(TAG, "re-register on $reason failed", it) }
        }
    }

    private companion object {
        const val TAG = "RegistrationKeeper"
    }
}
