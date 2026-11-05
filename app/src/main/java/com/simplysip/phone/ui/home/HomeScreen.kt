package com.simplysip.phone.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplysip.phone.R
import com.simplysip.phone.sip.engine.RegistrationState
import com.simplysip.phone.ui.theme.SimplySIPPhoneTheme

@Composable
fun HomeRoute(modifier: Modifier = Modifier, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.registrationState.collectAsStateWithLifecycle()
    HomeScreen(registrationState = state, modifier = modifier)
}

@Composable
fun HomeScreen(registrationState: RegistrationState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Text(registrationLabel(registrationState), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun registrationLabel(state: RegistrationState): String = when (state) {
    RegistrationState.Unregistered -> stringResource(R.string.registration_unregistered)
    RegistrationState.Registering -> stringResource(R.string.registration_registering)
    is RegistrationState.Registered -> stringResource(R.string.registration_registered)
    is RegistrationState.Failed -> stringResource(R.string.registration_failed, state.reason)
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    SimplySIPPhoneTheme {
        HomeScreen(RegistrationState.Unregistered)
    }
}
