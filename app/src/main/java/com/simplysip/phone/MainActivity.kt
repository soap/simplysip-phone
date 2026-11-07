package com.simplysip.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.simplysip.phone.ui.phone.PhoneApp
import com.simplysip.phone.ui.theme.SimplySIPPhoneTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SimplySIPPhoneTheme {
                PhoneApp()
            }
        }
    }
}
