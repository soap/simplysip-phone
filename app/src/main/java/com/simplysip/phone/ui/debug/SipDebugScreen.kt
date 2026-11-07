package com.simplysip.phone.ui.debug

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplysip.phone.R
import com.simplysip.phone.sip.engine.RegistrationState
import com.simplysip.phone.sip.engine.SipTransport

/** Debug-only SIP account setup (plan §3, Phase 1–2). Not localized on purpose. */
@Composable
fun SipDebugScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: SipDebugViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val registration by viewModel.registration.collectAsStateWithLifecycle()
    val form = state.form
    BackHandler(onBack = onBack)

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SIP account (debug)", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Done") }
        }
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
        Field("Outbound proxy host:port (leave empty for sip-manager)", form.outboundProxy) { v ->
            viewModel.edit { it.copy(outboundProxy = v) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = form.srtp, onCheckedChange = { on -> viewModel.edit { it.copy(srtp = on) } })
            Text("SRTP mandatory")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::register) { Text("Register") }
            OutlinedButton(onClick = viewModel::unregister) { Text("Unregister") }
            TextButton(onClick = viewModel::forget) { Text("Forget saved") }
        }
        Text(
            "Saved after a successful register (password encrypted with Android Keystore) and used at app start.",
            style = MaterialTheme.typography.bodySmall,
        )
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
    var revealed by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) {
            {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        painterResource(if (revealed) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        contentDescription = if (revealed) "Hide password" else "Show password",
                    )
                }
            }
        } else {
            null
        },
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
