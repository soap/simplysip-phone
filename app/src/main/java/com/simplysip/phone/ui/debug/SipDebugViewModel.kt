package com.simplysip.phone.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simplysip.phone.sip.engine.CallId
import com.simplysip.phone.sip.engine.MediaEncryption
import com.simplysip.phone.sip.engine.RegistrationState
import com.simplysip.phone.sip.engine.SipAccountConfig
import com.simplysip.phone.sip.engine.SipEngine
import com.simplysip.phone.sip.engine.SipEvent
import com.simplysip.phone.sip.engine.SipTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Phase 1 test bench: manual SIP settings, register, test calls (debug builds only). */
@HiltViewModel
class SipDebugViewModel @Inject constructor(
    private val engine: SipEngine,
) : ViewModel() {

    data class Form(
        val domain: String = "",
        val username: String = "",
        val password: String = "",
        val transport: SipTransport = SipTransport.TCP,
        val port: String = "5060",
        val outboundProxy: String = "",
        val srtp: Boolean = false,
        val destination: String = "*43",
    )

    data class UiState(
        val form: Form = Form(),
        val activeCall: CallId? = null,
        val callStatus: String? = null,
        val muted: Boolean = false,
        val log: List<String> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    val registration: StateFlow<RegistrationState> = engine.registrationState

    init {
        viewModelScope.launch { engine.observeEvents().collect(::onEvent) }
    }

    fun edit(transform: (Form) -> Form) = _state.update { it.copy(form = transform(it.form), error = null) }

    fun register() = launchAction {
        val f = _state.value.form
        val config = SipAccountConfig(
            domain = f.domain.trim(),
            username = f.username.trim(),
            password = f.password,
            transport = f.transport,
            port = f.port.toIntOrNull() ?: f.transport.defaultPort,
            outboundProxy = f.outboundProxy.trim().ifEmpty { null },
            mediaEncryption = if (f.srtp) MediaEncryption.SRTP_MANDATORY else MediaEncryption.NONE,
        )
        engine.initialize()
        engine.registerAccount(config)
    }

    fun unregister() = launchAction { engine.unregisterAccount() }

    fun call() = launchAction {
        val id = engine.makeCall(_state.value.form.destination)
        _state.update { it.copy(activeCall = id, callStatus = "calling", muted = false) }
    }

    fun answer() = withCall { engine.answerCall(it) }
    fun hangup() = withCall { engine.hangup(it) }
    fun toggleMute() = withCall { engine.setMuted(it, !_state.value.muted) }
    fun dtmf(digit: Char) = withCall { engine.sendDtmf(it, digit.toString()) }

    private fun withCall(action: suspend (CallId) -> Unit) = launchAction {
        _state.value.activeCall?.let { action(it) }
    }

    private fun launchAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e::class.simpleName) }
            }
        }
    }

    private fun onEvent(event: SipEvent) {
        _state.update { s ->
            val next = when (event) {
                is SipEvent.IncomingCall -> s.copy(activeCall = event.callId, callStatus = "incoming ${event.remoteUri}", muted = false)
                is SipEvent.CallProgress -> s.copy(callStatus = "progress ${event.statusCode}")
                is SipEvent.CallConnected -> s.copy(callStatus = "connected")
                is SipEvent.CallMuteChanged -> s.copy(muted = event.muted)
                is SipEvent.CallEnded -> if (s.activeCall == event.callId) {
                    s.copy(activeCall = null, callStatus = "ended ${event.statusCode ?: ""} ${event.reason.orEmpty()}".trim())
                } else s
                else -> s
            }
            next.copy(log = (listOf("${LocalTime.now().format(TIME)}  ${describe(event)}") + s.log).take(MAX_LOG))
        }
    }

    private fun describe(e: SipEvent): String = when (e) {
        is SipEvent.Registration -> "registration: ${e.state}"
        is SipEvent.Error -> "error: ${e.message} ${e.cause?.message.orEmpty()}"
        else -> e.toString()
    }

    private companion object {
        const val MAX_LOG = 50
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
