package com.simplysip.phone.sip.engine.pjsip

import com.simplysip.phone.sip.engine.CallId
import com.simplysip.phone.sip.engine.MediaEncryption
import com.simplysip.phone.sip.engine.RegistrationState
import com.simplysip.phone.sip.engine.SipAccountConfig
import com.simplysip.phone.sip.engine.SipEngine
import com.simplysip.phone.sip.engine.SipEvent
import com.simplysip.phone.sip.engine.SipTransport
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.CallOpParam
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pjmedia_srtp_use
import org.pjsip.pjsua2.pjsip_status_code
import org.pjsip.pjsua2.pjsip_transport_type_e
import org.pjsip.pjsua2.pjsua_call_flag

/**
 * [SipEngine] on PJSUA2 (spec §4.3).
 *
 * Threading: PJSIP runs without worker threads (`threadCnt = 0`, `mainThreadOnly`). One dedicated
 * thread owns the [Endpoint], polls `libHandleEvents` and executes every public call, so all
 * native calls and callbacks happen on that thread and no state here needs locking.
 * Native objects are deleted explicitly on that thread, never by the garbage collector.
 */
class PjsipEngine(
    private val options: Options,
) : SipEngine {

    data class Options(
        val userAgent: String,
        /** PEM bundle used to verify the server certificate for TLS. Null = no TLS transport. */
        val caBundle: File?,
        /** PJSIP log level 0–6. Level 5+ logs whole SIP messages; keep ≤ 4 outside debugging. */
        val logLevel: Int = 3,
        val logSink: (level: Int, message: String) -> Unit = { _, _ -> },
    )

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, THREAD_NAME).apply { isDaemon = true } }
    private val sipDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + sipDispatcher)

    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Unregistered)
    override val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val events = MutableSharedFlow<SipEvent>(extraBufferCapacity = 256)
    override fun observeEvents(): Flow<SipEvent> = events.asSharedFlow()

    // ---- state owned by the pjsip thread --------------------------------------------------------
    private var endpoint: Endpoint? = null
    private var logWriter: PjLogWriter? = null
    private var pollJob: Job? = null
    private val transportIds = mutableMapOf<SipTransport, Int>()
    private var account: PjAccount? = null
    private var accountConfig: SipAccountConfig? = null
    private val calls = mutableMapOf<CallId, PjCall>()
    private val nextCallId = AtomicLong(1)

    override suspend fun initialize() = onSipThread {
        if (endpoint != null) return@onSipThread
        PjsipLibrary.load()

        val ep = Endpoint()
        val writer = PjLogWriter(options.logSink)
        try {
            ep.libCreate()
            val cfg = EpConfig().apply {
                uaConfig.userAgent = options.userAgent
                uaConfig.threadCnt = 0
                uaConfig.mainThreadOnly = true
                logConfig.level = options.logLevel.toLong()
                // consoleLevel gates what reaches the writer; 0 would silence it entirely.
                logConfig.consoleLevel = options.logLevel.toLong()
                logConfig.msgLogging = if (options.logLevel >= 5) 1L else 0L
                logConfig.writer = writer
                // No silence suppression: a steady RTP stream keeps NAT/rtpengine bindings alive
                // and avoids clipped speech onsets.
                medConfig.noVad = true
                // Echo canceller on by default; tuning per device is Phase 2 (spec §5.4).
            }
            try {
                ep.libInit(cfg)
            } finally {
                cfg.delete()
            }
            // Needs libInit (thread registry mutex). Covers a re-create on a new engine thread.
            if (!ep.libIsThreadRegistered()) ep.libRegisterThread(THREAD_NAME)

            transportIds[SipTransport.UDP] = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, TransportConfig())
            transportIds[SipTransport.TCP] = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, TransportConfig())
            options.caBundle?.let { ca ->
                val tls = TransportConfig().apply {
                    tlsConfig.caListFile = ca.absolutePath
                    tlsConfig.verifyServer = true
                }
                transportIds[SipTransport.TLS] = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tls)
            }
            ep.libStart()
        } catch (e: Exception) {
            // Leave nothing half-initialised: PJSUA allows one library instance per process.
            transportIds.clear()
            runCatching { ep.libDestroy() }
            ep.delete()
            writer.delete()
            throw e
        }
        endpoint = ep
        logWriter = writer
        pollJob = scope.launch {
            while (isActive) {
                ep.libHandleEvents(POLL_MS)
                yield() // let queued engine calls run between polls
            }
        }
    }

    override suspend fun registerAccount(config: SipAccountConfig) = onSipThread {
        val ep = requireEndpoint()
        val transportId = transportIds[config.transport]
            ?: error("transport ${config.transport} not available (TLS needs a CA bundle)")
        removeAccount()

        // No Route header unless an outbound proxy is configured: sip-manager's OpenSIPS rejects
        // initial requests carrying one ("403 Preload Route denied"). Transport and port go into
        // the request URI instead.
        val registrarUri = "sip:${config.domain}${serverParams(config)}"
        val proxyUri = config.outboundProxy?.let { "sip:$it;transport=${config.transport.name.lowercase()};lr" }
        // pjsua_acc_add only says PJSIP_EINVALIDURI; name the offending field instead.
        listOfNotNull(
            "account (user@domain)" to config.aor,
            "registrar" to registrarUri,
            proxyUri?.let { "outbound proxy" to it },
        ).forEach { (field, uri) -> require(ep.utilVerifySipUri(uri) == 0) { "invalid $field URI: $uri" } }

        val acfg = AccountConfig().apply {
            idUri = buildString {
                config.displayName?.let { append('"').append(it.replace("\"", "")).append("\" ") }
                append('<').append(config.aor).append('>')
            }
            regConfig.registrarUri = registrarUri
            regConfig.timeoutSec = config.registrationExpirySeconds.toLong()
            sipConfig.transportId = transportId
            proxyUri?.let { sipConfig.proxies.add(it) }
            sipConfig.authCreds.add(AuthCredInfo("digest", "*", config.username, 0, config.password))
            mediaConfig.srtpUse = when (config.mediaEncryption) {
                MediaEncryption.NONE -> pjmedia_srtp_use.PJMEDIA_SRTP_DISABLED
                MediaEncryption.SRTP_OPTIONAL -> pjmedia_srtp_use.PJMEDIA_SRTP_OPTIONAL
                MediaEncryption.SRTP_MANDATORY -> pjmedia_srtp_use.PJMEDIA_SRTP_MANDATORY
            }
            // SDES keys must only travel over TLS (spec §9).
            mediaConfig.srtpSecureSignaling = if (config.transport == SipTransport.TLS) 1 else 0
        }

        val acc = PjAccount(this)
        accountConfig = config
        setRegistration(RegistrationState.Registering)
        try {
            acc.create(acfg, true)
            account = acc
        } catch (e: Exception) {
            acc.delete()
            accountConfig = null
            setRegistration(RegistrationState.Failed(null, e.message ?: "account create failed"))
            throw e
        } finally {
            acfg.delete()
        }
    }

    override suspend fun unregisterAccount() = onSipThread {
        removeAccount()
        setRegistration(RegistrationState.Unregistered)
    }

    override suspend fun makeCall(destination: String): CallId = onSipThread {
        val acc = account ?: error("no account registered")
        val cfg = accountConfig ?: error("no account registered")
        require(destination.isNotBlank()) { "destination is blank" }
        val uri = if (destination.startsWith("sip:") || destination.startsWith("sips:")) {
            destination
        } else {
            // With an outbound proxy the Route header carries transport/port; otherwise the R-URI must.
            val params = if (cfg.outboundProxy == null) serverParams(cfg) else ""
            "sip:${destination.trim()}@${cfg.domain}$params"
        }
        val id = newCallId()
        val call = PjCall(this, acc, id)
        val prm = CallOpParam(true)
        try {
            call.makeCall(uri, prm)
        } catch (e: Exception) {
            call.delete()
            throw e
        } finally {
            prm.delete()
        }
        calls[id] = call
        id
    }

    override suspend fun answerCall(callId: CallId) = onSipThread {
        calls[callId]?.let { call -> withParam(pjsip_status_code.PJSIP_SC_OK) { call.answer(it) } }
        Unit
    }

    override suspend fun rejectCall(callId: CallId) = onSipThread {
        calls[callId]?.let { call -> withParam(pjsip_status_code.PJSIP_SC_BUSY_HERE) { call.hangup(it) } }
        Unit
    }

    override suspend fun hangup(callId: CallId) = onSipThread {
        calls[callId]?.let { call -> withParam(null) { call.hangup(it) } }
        Unit
    }

    override suspend fun setMuted(callId: CallId, muted: Boolean) = onSipThread {
        val call = calls[callId] ?: return@onSipThread
        call.setMuted(requireEndpoint(), muted)
        emit(SipEvent.CallMuteChanged(callId, muted))
    }

    override suspend fun setHold(callId: CallId, held: Boolean) = onSipThread {
        val call = calls[callId] ?: return@onSipThread
        val prm = CallOpParam(true)
        try {
            if (held) {
                call.setHold(prm)
            } else {
                prm.opt.flag = pjsua_call_flag.PJSUA_CALL_UNHOLD.toLong()
                call.reinvite(prm)
            }
        } finally {
            prm.delete()
        }
        emit(SipEvent.CallHoldChanged(callId, held))
    }

    override suspend fun sendDtmf(callId: CallId, digits: String) = onSipThread {
        require(digits.isNotEmpty() && digits.all { it in DTMF_DIGITS }) { "invalid DTMF digits" }
        // RFC 4733 telephone-event by default, matching the PBX (plan: dtmf_mode = rfc4733).
        calls[callId]?.dialDtmf(digits)
        Unit
    }

    override suspend fun shutdown() {
        onSipThread {
            calls.values.forEach { runCatching { withParam(null) { p -> it.hangup(p) } } }
            calls.values.forEach { it.delete() }
            calls.clear()
            removeAccount()
            setRegistration(RegistrationState.Unregistered)
            pollJob?.cancel()
            pollJob = null
            endpoint?.let { ep ->
                runCatching { ep.libDestroy() }
                ep.delete()
            }
            endpoint = null
            logWriter?.delete()
            logWriter = null
            transportIds.clear()
        }
    }

    // ---- callbacks from PjAccount / PjCall (always on the pjsip thread) ---------------------

    internal fun onRegistrationResult(code: Int, reason: String, expiresSeconds: Long) {
        val state = when {
            code / 100 == 2 && expiresSeconds > 0 -> RegistrationState.Registered(expiresSeconds.toInt())
            code / 100 == 2 -> RegistrationState.Unregistered
            else -> RegistrationState.Failed(code.takeIf { it > 0 }, reason)
        }
        setRegistration(state)
    }

    internal fun onIncomingCall(acc: PjAccount, pjCallId: Int) {
        val id = newCallId()
        val call = PjCall(this, acc, id, pjCallId)
        calls[id] = call
        val remote = runCatching { call.info.remoteUri }.getOrDefault("")
        withParam(pjsip_status_code.PJSIP_SC_RINGING) { call.answer(it) }
        emit(SipEvent.IncomingCall(id, SipUris.addressOf(remote), SipUris.displayNameOf(remote)))
    }

    internal fun onCallProgress(id: CallId, statusCode: Int) = emit(SipEvent.CallProgress(id, statusCode))

    internal fun onCallConfirmed(id: CallId) = emit(SipEvent.CallConnected(id))

    internal fun onCallDisconnected(id: CallId, statusCode: Int?, reason: String?) {
        val call = calls.remove(id) ?: return // already ended: idempotent (spec §4.4)
        emit(SipEvent.CallEnded(id, statusCode, reason))
        // Deleting inside the call's own callback is not allowed; do it on the next turn.
        scope.launch { call.delete() }
    }

    internal fun connectAudio(call: PjCall) = endpoint?.let { call.connectAudio(it) }

    internal fun reportError(message: String, cause: Throwable?) = emit(SipEvent.Error(message, cause))

    // ---- helpers ----------------------------------------------------------------------------

    private suspend fun <T> onSipThread(block: suspend () -> T): T = withContext(sipDispatcher) { block() }

    /** `:port` when non-default and `;transport=` when not UDP, for URIs that target the server. */
    private fun serverParams(cfg: SipAccountConfig): String = buildString {
        if (cfg.port != cfg.transport.defaultPort) append(':').append(cfg.port)
        if (cfg.transport != SipTransport.UDP) append(";transport=").append(cfg.transport.name.lowercase())
    }

    private fun requireEndpoint(): Endpoint = endpoint ?: error("SipEngine.initialize() not called")

    private fun removeAccount() {
        account?.let { acc ->
            runCatching { acc.shutdown() }
            acc.delete()
        }
        account = null
        accountConfig = null
    }

    private fun newCallId() = CallId("pj-${nextCallId.getAndIncrement()}")

    private inline fun withParam(statusCode: Int?, action: (CallOpParam) -> Unit) {
        val prm = CallOpParam(true)
        try {
            statusCode?.let { prm.statusCode = it }
            action(prm)
        } finally {
            prm.delete()
        }
    }

    private fun setRegistration(state: RegistrationState) {
        _registrationState.value = state
        emit(SipEvent.Registration(state))
    }

    private fun emit(event: SipEvent) {
        if (!events.tryEmit(event)) options.logSink(2, "SipEngine event dropped: ${event::class.simpleName}")
    }

    private companion object {
        const val POLL_MS = 20L
        const val THREAD_NAME = "pjsip"
        const val DTMF_DIGITS = "0123456789*#ABCD"
    }
}
