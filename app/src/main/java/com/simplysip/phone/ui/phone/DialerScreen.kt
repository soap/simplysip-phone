package com.simplysip.phone.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.simplysip.phone.R
import com.simplysip.phone.sip.engine.RegistrationState
import com.simplysip.phone.ui.theme.SimplySIPPhoneTheme

@Composable
fun DialerScreen(
    number: String,
    registration: RegistrationState,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onCall: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAccount: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RegistrationBadge(registration, Modifier.weight(1f))
            if (onOpenAccount != null) {
                IconButton(onClick = onOpenAccount) {
                    Icon(painterResource(R.drawable.ic_account), contentDescription = stringResource(R.string.dialer_account))
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(48.dp))
            Text(
                text = number.ifEmpty { stringResource(R.string.dialer_hint) },
                style = if (number.isEmpty()) MaterialTheme.typography.titleLarge else MaterialTheme.typography.displaySmall,
                color = if (number.isEmpty()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f),
            )
            if (number.isNotEmpty()) {
                val label = stringResource(R.string.dialer_backspace)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .combinedClickable(role = Role.Button, onClick = onBackspace, onLongClick = onClear)
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_backspace), contentDescription = null)
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        Keypad(onKey = onKey, onLongKey = { if (it == '0') onKey('+') })
        Spacer(Modifier.height(24.dp))

        FloatingActionButton(
            onClick = onCall,
            shape = CircleShape,
            containerColor = CallGreen,
            contentColor = Color.White,
            modifier = Modifier.size(72.dp),
        ) {
            Icon(painterResource(R.drawable.ic_call), contentDescription = stringResource(R.string.dialer_call))
        }
        Spacer(Modifier.weight(0.3f))
    }
}

@Composable
fun RegistrationBadge(state: RegistrationState, modifier: Modifier = Modifier) {
    val (color, text) = when (state) {
        is RegistrationState.Registered -> CallGreen to stringResource(R.string.registration_registered)
        RegistrationState.Registering -> MaterialTheme.colorScheme.tertiary to stringResource(R.string.registration_registering)
        RegistrationState.Unregistered -> MaterialTheme.colorScheme.outline to stringResource(R.string.registration_unregistered)
        is RegistrationState.Failed -> MaterialTheme.colorScheme.error to
            stringResource(R.string.registration_failed, listOfNotNull(state.statusCode, state.reason).joinToString(" "))
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

val CallGreen = Color(0xFF1E8E3E)
val CallRed = Color(0xFFD93025)

@Preview(showBackground = true)
@Composable
private fun DialerPreview() {
    SimplySIPPhoneTheme {
        DialerScreen("202", RegistrationState.Registered(300), {}, {}, {}, {}, onOpenAccount = {})
    }
}
