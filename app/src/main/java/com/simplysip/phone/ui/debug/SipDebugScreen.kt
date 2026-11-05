package com.simplysip.phone.ui.debug

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplysip.phone.sip.engine.RegistrationState
import com.simplysip.phone.sip.engine.SipTransport

/** Debug-only test bench for Phase 1 (plan §3). Not localized on purpose. */
@Composable
fun SipDebugScreen(modifier: Modifier = Modifier, viewModel: SipDebugViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val registration by viewModel.registration.collectAsStateWithLifecycle()
    val form = state.form
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.call()
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("SIP test bench", style = MaterialTheme.typography.titleLarge)
        Text("Registration: ${registration.label()}", style = MaterialTheme.typography.bodyLarge)

        Field("Domain (tenant SIP domain)", form.domain) { v -> viewModel.edit { it.copy(domain = v) } }
        Field("Username (extension)", form.username) { v -> viewModel.edit { it.copy(username = v) } }
        Field("Password", form.password, password = true) { v -> viewModel.edit { it.copy(password = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SipTransport.entries.forEach { t ->
                FilterChip(
                    selected = form.transport == t,
                    onClick = { viewModel.edit { it.copy(transport = t, port = t.defaultPort.toString()) } },
                    label = { Text(t.name) },
                )
            }
        }
        Field("Port", form.port, keyboard = KeyboardType.Number) { v -> viewModel.edit { it.copy(port = v) } }
        Field("Outbound proxy host:port (optional)", form.outboundProxy) { v -> viewModel.edit { it.copy(outboundProxy = v) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = form.srtp, onCheckedChange = { on -> viewModel.edit { it.copy(srtp = on) } })
            Text("SRTP mandatory")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::register) { Text("Register") }
            OutlinedButton(onClick = viewModel::unregister) { Text("Unregister") }
        }

        Field("Destination", form.destination) { v -> viewModel.edit { it.copy(destination = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.activeCall == null) {
                Button(onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) viewModel.call() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Call") }
            } else {
                Button(onClick = viewModel::answer) { Text("Answer") }
                OutlinedButton(onClick = viewModel::hangup) { Text("Hang up") }
                OutlinedButton(onClick = viewModel::toggleMute) { Text(if (state.muted) "Unmute" else "Mute") }
            }
        }
        if (state.activeCall != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                "123456789*0#".forEach { d -> OutlinedButton(onClick = { viewModel.dtmf(d) }) { Text(d.toString()) } }
            }
        }
        state.callStatus?.let { Text("Call: $it") }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("Events", style = MaterialTheme.typography.titleMedium)
        state.log.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun RegistrationState.label(): String = when (this) {
    RegistrationState.Unregistered -> "unregistered"
    RegistrationState.Registering -> "registering…"
    is RegistrationState.Registered -> "registered (expires ${expiresInSeconds}s)"
    is RegistrationState.Failed -> "failed ${statusCode ?: ""} $reason"
}
