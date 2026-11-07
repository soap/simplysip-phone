package com.simplysip.phone.ui.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplysip.phone.BuildConfig
import com.simplysip.phone.R
import com.simplysip.phone.call.CallError
import com.simplysip.phone.sip.engine.call.CallState
import com.simplysip.phone.ui.debug.SipDebugScreen

/** App root: the call screen whenever there is a call, otherwise the dialer. */
@Composable
fun PhoneApp(viewModel: PhoneViewModel = hiltViewModel()) {
    val call by viewModel.callState.collectAsStateWithLifecycle()
    val audio by viewModel.audioRoute.collectAsStateWithLifecycle()
    val registration by viewModel.registration.collectAsStateWithLifecycle()
    val number by viewModel.number.collectAsStateWithLifecycle()
    var showAccount by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(viewModel) {
        viewModel.errors.collect { error ->
            snackbar.showSnackbar(
                when (error) {
                    CallError.EmptyNumber -> resources.getString(R.string.error_empty_number)
                    CallError.NotRegistered -> resources.getString(R.string.error_not_registered)
                    CallError.AlreadyInCall -> resources.getString(R.string.error_already_in_call)
                    is CallError.Engine -> resources.getString(R.string.error_engine, error.detail)
                },
            )
        }
    }

    val withMicrophone = rememberMicrophoneGate(onDenied = {
        snackbar.showSnackbar(resources.getString(R.string.error_mic_permission))
    })

    Scaffold(modifier = Modifier.fillMaxSize(), snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val modifier = Modifier.padding(padding)
        when {
            call !is CallState.Idle -> CallScreen(
                call = call,
                audio = audio,
                actions = CallActions(
                    onAnswer = { withMicrophone(viewModel::answer) },
                    onHangup = viewModel::hangup,
                    onToggleMute = viewModel::toggleMute,
                    onToggleHold = viewModel::toggleHold,
                    onDtmf = viewModel::dtmf,
                    onSelectRoute = viewModel::selectRoute,
                ),
                modifier = modifier,
            )

            BuildConfig.DEBUG && showAccount -> SipDebugScreen(onBack = { showAccount = false }, modifier = modifier)

            else -> DialerScreen(
                number = number,
                registration = registration,
                onKey = viewModel::press,
                onBackspace = viewModel::backspace,
                onClear = viewModel::clearNumber,
                onCall = { withMicrophone(viewModel::call) },
                onOpenAccount = if (BuildConfig.DEBUG) ({ showAccount = true }) else null,
                modifier = modifier,
            )
        }
    }
}

/**
 * Runs an action that needs the microphone, asking for it first (and for notifications, used by
 * the ongoing-call notification, on API 33+).
 */
@Composable
private fun rememberMicrophoneGate(onDenied: suspend () -> Unit): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var denied by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val action = pending
        pending = null
        if (result[Manifest.permission.RECORD_AUDIO] == true) action?.invoke() else denied++
    }
    LaunchedEffect(denied) { if (denied > 0) onDenied() }

    return { action ->
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pending = action
            launcher.launch(
                buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray(),
            )
        }
    }
}
