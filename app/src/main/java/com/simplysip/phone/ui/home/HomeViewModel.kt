package com.simplysip.phone.ui.home

import androidx.lifecycle.ViewModel
import com.simplysip.phone.sip.engine.RegistrationState
import com.simplysip.phone.sip.engine.SipEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class HomeViewModel @Inject constructor(
    sipEngine: SipEngine,
) : ViewModel() {
    val registrationState: StateFlow<RegistrationState> = sipEngine.registrationState
}
