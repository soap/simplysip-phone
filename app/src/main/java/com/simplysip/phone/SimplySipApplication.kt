package com.simplysip.phone

import android.app.Application
import com.simplysip.phone.call.CallController
import com.simplysip.phone.call.RegistrationKeeper
import com.simplysip.phone.ui.debug.DebugAccountStore
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SimplySipApplication : Application() {

    // Created at start so incoming calls are handled even before any screen asks for it.
    @Inject lateinit var callController: CallController
    @Inject lateinit var registrationKeeper: RegistrationKeeper
    @Inject lateinit var debugAccount: Lazy<DebugAccountStore>

    override fun onCreate() {
        super.onCreate()
        registrationKeeper.start()
        // Until provisioning (Phase 3), debug builds re-register with the saved test account.
        if (BuildConfig.DEBUG) debugAccount.get().registerSaved()
    }
}
