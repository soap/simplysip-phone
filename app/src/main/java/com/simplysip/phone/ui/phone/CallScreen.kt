package com.simplysip.phone.ui.phone

import android.annotation.SuppressLint
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.DrawableRes
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.simplysip.phone.R
import com.simplysip.phone.audio.AudioRoute
import com.simplysip.phone.audio.AudioRouteState
import com.simplysip.phone.sip.engine.CallId
import com.simplysip.phone.sip.engine.call.CallParty
import com.simplysip.phone.sip.engine.call.CallState
import com.simplysip.phone.sip.engine.call.EndCause
import com.simplysip.phone.ui.theme.SimplySIPPhoneTheme
import java.util.Locale
import kotlinx.coroutines.delay

class CallActions(
    val onAnswer: () -> Unit = {},
    val onHangup: () -> Unit = {},
    val onToggleMute: () -> Unit = {},
    val onToggleHold: () -> Unit = {},
    val onDtmf: (Char) -> Unit = {},
    val onSelectRoute: (AudioRoute) -> Unit = {},
)

/** Incoming, outgoing, active and just-ended call. [call] is never [CallState.Idle]. */
@Composable
fun CallScreen(
    call: CallState,
    audio: AudioRouteState,
    actions: CallActions,
    modifier: Modifier = Modifier,
) {
    val remote = when (call) {
        is CallState.Live -> call.remote
        is CallState.Ended -> call.remote
        CallState.Idle -> return
    }
    var showKeypad by rememberSaveable { mutableStateOf(false) }
    var dtmfTyped by rememberSaveable { mutableStateOf("") }

    // Screen off at the ear while the earpiece carries the call.
    ProximityScreenOff(enabled = (call is CallState.Active || call is CallState.Outgoing) && audio.current == AudioRoute.EARPIECE)

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            remote.label,
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (remote.displayName != null) {
            Text(remote.number, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            statusText(call),
            style = MaterialTheme.typography.titleMedium,
            color = if (call is CallState.Ended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.weight(1f))

        when {
            call is CallState.Incoming && !call.answering && !call.hangingUp -> IncomingButtons(actions)

            call is CallState.Active && showKeypad -> {
                Text(dtmfTyped, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                Spacer(Modifier.height(12.dp))
                Keypad(
                    onKey = { key ->
                        dtmfTyped = (dtmfTyped + key).takeLast(DialInput.MAX_LENGTH)
                        actions.onDtmf(key)
                    },
                    keySize = 64.dp,
                )
                TextButton(onClick = { showKeypad = false }) { Text(stringResource(R.string.call_hide_keypad)) }
                HangupButton(actions.onHangup, enabled = !call.hangingUp)
            }

            call is CallState.Live -> {
                ControlGrid(call, audio, actions, onKeypad = { showKeypad = true })
                Spacer(Modifier.height(32.dp))
                HangupButton(actions.onHangup, enabled = !call.hangingUp)
            }

            else -> Spacer(Modifier.height(72.dp)) // Ended: dismissed automatically
        }
    }
}

@Composable
private fun statusText(call: CallState): String = when (call) {
    is CallState.Outgoing -> when {
        call.hangingUp -> stringResource(R.string.call_status_ending)
        call.ringing -> stringResource(R.string.call_status_ringing)
        else -> stringResource(R.string.call_status_calling)
    }
    is CallState.Incoming -> when {
        call.hangingUp -> stringResource(R.string.call_status_ending)
        call.answering -> stringResource(R.string.call_status_connecting)
        else -> stringResource(R.string.call_status_incoming)
    }
    is CallState.Active -> when {
        call.hangingUp -> stringResource(R.string.call_status_ending)
        call.onHold -> stringResource(R.string.call_status_on_hold)
        call.remoteHold -> stringResource(R.string.call_status_remote_hold)
        else -> formatDuration(rememberElapsedSince(call.connectedAtMillis))
    }
    is CallState.Ended -> endedText(call.cause)
    CallState.Idle -> ""
}

@Composable
private fun endedText(cause: EndCause): String = when (cause) {
    EndCause.LocalHangup -> stringResource(R.string.ended_local)
    EndCause.RemoteHangup -> stringResource(R.string.ended_remote)
    EndCause.Cancelled -> stringResource(R.string.ended_cancelled)
    EndCause.Rejected -> stringResource(R.string.ended_rejected)
    EndCause.Missed -> stringResource(R.string.ended_missed)
    EndCause.Busy -> stringResource(R.string.ended_busy)
    EndCause.Declined -> stringResource(R.string.ended_declined)
    EndCause.NoAnswer -> stringResource(R.string.ended_no_answer)
    EndCause.NotFound -> stringResource(R.string.ended_not_found)
    is EndCause.Failed -> stringResource(
        R.string.ended_failed,
        listOfNotNull(cause.statusCode, cause.reason).joinToString(" ").ifEmpty { "?" },
    )
}

@Composable
private fun IncomingButtons(actions: CallActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RoundAction(R.drawable.ic_call_end, stringResource(R.string.call_decline), CallRed, actions.onHangup)
        RoundAction(R.drawable.ic_call, stringResource(R.string.call_answer), CallGreen, actions.onAnswer)
    }
}

@Composable
private fun ControlGrid(call: CallState.Live, audio: AudioRouteState, actions: CallActions, onKeypad: () -> Unit) {
    val active = call as? CallState.Active
    val connected = active != null && !active.hangingUp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Toggle(R.drawable.ic_mic_off, stringResource(R.string.call_mute), active?.muted == true, connected) { actions.onToggleMute() }
        Toggle(R.drawable.ic_dialpad, stringResource(R.string.call_keypad), false, connected) { onKeypad() }
    }
    Spacer(Modifier.height(24.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RouteToggle(audio, actions.onSelectRoute)
        Toggle(R.drawable.ic_pause, stringResource(R.string.call_hold), active?.onHold == true, connected) { actions.onToggleHold() }
    }
}

/** Earpiece/speaker toggle; a menu once a headset or Bluetooth device is also available. */
@Composable
private fun RouteToggle(audio: AudioRouteState, onSelect: (AudioRoute) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val simple = audio.available.all { it == AudioRoute.EARPIECE || it == AudioRoute.SPEAKER }
    Box {
        Toggle(
            R.drawable.ic_volume_up,
            if (simple) stringResource(R.string.route_speaker) else routeLabel(audio.current),
            checked = audio.current == AudioRoute.SPEAKER,
            enabled = audio.available.size > 1,
        ) {
            if (simple) {
                onSelect(if (audio.current == AudioRoute.SPEAKER) AudioRoute.EARPIECE else AudioRoute.SPEAKER)
            } else {
                menu = true
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            audio.available.forEach { route ->
                DropdownMenuItem(
                    text = { Text(routeLabel(route) + if (route == audio.current) "  ✓" else "") },
                    onClick = {
                        menu = false
                        onSelect(route)
                    },
                )
            }
        }
    }
}

@Composable
private fun routeLabel(route: AudioRoute): String = stringResource(
    when (route) {
        AudioRoute.EARPIECE -> R.string.route_earpiece
        AudioRoute.SPEAKER -> R.string.route_speaker
        AudioRoute.WIRED_HEADSET -> R.string.route_wired
        AudioRoute.BLUETOOTH -> R.string.route_bluetooth
    },
)

@Composable
private fun Toggle(@DrawableRes icon: Int, label: String, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconToggleButton(checked = checked, onCheckedChange = { onClick() }, enabled = enabled, modifier = Modifier.size(64.dp)) {
            Icon(painterResource(icon), contentDescription = label)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RoundAction(@DrawableRes icon: Int, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FloatingActionButton(
            onClick = onClick,
            shape = CircleShape,
            containerColor = color,
            contentColor = Color.White,
            modifier = Modifier.size(72.dp),
        ) { Icon(painterResource(icon), contentDescription = label) }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun HangupButton(onClick: () -> Unit, enabled: Boolean) {
    FloatingActionButton(
        onClick = { if (enabled) onClick() },
        shape = CircleShape,
        containerColor = if (enabled) CallRed else CallRed.copy(alpha = 0.4f),
        contentColor = Color.White,
        modifier = Modifier.size(72.dp),
    ) { Icon(painterResource(R.drawable.ic_call_end), contentDescription = stringResource(R.string.call_hangup)) }
}

@Composable
private fun rememberElapsedSince(start: Long): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(start) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1_000 - (now - start).mod(1_000L))
        }
    }
    return (now - start).coerceAtLeast(0)
}

internal fun formatDuration(millis: Long): String {
    val total = millis / 1_000
    val h = total / 3_600
    val m = (total % 3_600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
}

@SuppressLint("WakelockTimeout") // held only while the call screen shows; released on dispose
@Composable
private fun ProximityScreenOff(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val power = context.getSystemService(PowerManager::class.java)
        val lock = if (enabled && power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "SimplySIP:proximity").apply { acquire() }
        } else {
            null
        }
        onDispose {
            lock?.takeIf { it.isHeld }?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ActiveCallPreview() {
    SimplySIPPhoneTheme {
        CallScreen(
            CallState.Active(CallId("p"), CallParty("202", "ATCom wireless"), SystemClock.elapsedRealtime() - 65_000, muted = true),
            AudioRouteState(),
            CallActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun IncomingCallPreview() {
    SimplySIPPhoneTheme {
        CallScreen(CallState.Incoming(CallId("p"), CallParty("202")), AudioRouteState(), CallActions())
    }
}
