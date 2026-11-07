package com.simplysip.phone.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

/** Debug-only SIP account setup + event log, until provisioning exists (Phase 3). */
@HiltViewModel
class SipDebugViewModel @Inject constructor(
    private val engine: SipEngine,
    private val store: DebugAccountStore,
) : ViewModel() {

    data class Form(
        val domain: String = "",
        val username: String = "",
        val password: String = "",
        val transport: SipTransport = SipTransport.TCP,
        val port: String = "5060",
        val outboundProxy: String = "",
        val srtp: Boolean = false,
    ) {
        fun toConfig() = SipAccountConfig(
            domain = domain.trim(),
            username = username.trim(),
            password = password,
            transport = transport,
            port = port.toIntOrNull() ?: transport.defaultPort,
            outboundProxy = outboundProxy.trim().ifEmpty { null },
            mediaEncryption = if (srtp) MediaEncryption.SRTP_MANDATORY else MediaEncryption.NONE,
        )
    }

    data class UiState(
        val form: Form = Form(),
        val log: List<String> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState(form = store.load() ?: Form()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    val registration: StateFlow<RegistrationState> = engine.registrationState

    /** Form to remember once the server accepts it. */
    private var pendingSave: Form? = null

    init {
        viewModelScope.launch { engine.observeEvents().collect(::onEvent) }
    }

    fun edit(transform: (Form) -> Form) = _state.update { it.copy(form = transform(it.form), error = null) }

    fun register() = launchAction {
        val form = _state.value.form
        val config = form.toConfig()
        pendingSave = form
        engine.initialize()
        engine.registerAccount(config)
    }

    fun unregister() = launchAction {
        pendingSave = null
        engine.unregisterAccount()
    }

    fun forget() {
        store.clear()
        _state.update { it.copy(form = Form()) }
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
        if (event is SipEvent.Registration && event.state is RegistrationState.Registered) {
            pendingSave?.let(store::save)
            pendingSave = null
        }
        _state.update { s ->
            s.copy(log = (listOf("${LocalTime.now().format(TIME)}  ${describe(event)}") + s.log).take(MAX_LOG))
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
