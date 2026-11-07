package com.simplysip.phone.ui.phone

import androidx.lifecycle.ViewModel
import com.simplysip.phone.audio.AudioRoute
import com.simplysip.phone.audio.AudioRouteState
import com.simplysip.phone.call.CallController
import com.simplysip.phone.call.CallError
import com.simplysip.phone.sip.engine.RegistrationState
import com.simplysip.phone.sip.engine.call.CallState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Dialer and call screen state; call logic lives in [CallController]. */
@HiltViewModel
class PhoneViewModel @Inject constructor(
    private val calls: CallController,
) : ViewModel() {
    val callState: StateFlow<CallState> = calls.state
    val audioRoute: StateFlow<AudioRouteState> = calls.audioRoute
    val registration: StateFlow<RegistrationState> = calls.registration
    val errors: Flow<CallError> = calls.errors

    private val _number = MutableStateFlow("")
    val number: StateFlow<String> = _number.asStateFlow()

    fun press(key: Char) = _number.update { DialInput.append(it, key) }
    fun backspace() = _number.update(DialInput::backspace)
    fun clearNumber() = _number.update { "" }

    fun call() = calls.dial(_number.value)
    fun answer() = calls.answer()
    fun hangup() = calls.hangup()
    fun toggleMute() = calls.toggleMute()
    fun toggleHold() = calls.toggleHold()
    fun dtmf(key: Char) = calls.sendDtmf(key)
    fun selectRoute(route: AudioRoute) = calls.selectRoute(route)
}

/** Dial-string editing rules. */
object DialInput {
    const val MAX_LENGTH = 32
    private const val KEYS = "0123456789*#"

    fun append(current: String, key: Char): String = when {
        current.length >= MAX_LENGTH -> current
        key in KEYS -> current + key
        key == '+' && current.isEmpty() -> "+" // international prefix only at the start
        else -> current
    }

    fun backspace(current: String): String = current.dropLast(1)
}
