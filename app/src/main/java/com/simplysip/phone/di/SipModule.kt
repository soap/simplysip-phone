package com.simplysip.phone.di

import android.content.Context
import android.util.Log
import com.simplysip.phone.BuildConfig
import com.simplysip.phone.sip.engine.SipEngine
import com.simplysip.phone.sip.engine.pjsip.AndroidCaBundle
import com.simplysip.phone.sip.engine.pjsip.PjsipEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SipModule {
    @Provides
    @Singleton
    fun sipEngine(@ApplicationContext context: Context): SipEngine = PjsipEngine(
        PjsipEngine.Options(
            userAgent = "SimplySIP-Android/${BuildConfig.VERSION_NAME}",
            caBundle = runCatching { AndroidCaBundle.write(File(context.noBackupFilesDir, "pjsip/ca.pem")) }
                .onFailure { Log.w(TAG, "CA bundle unavailable; TLS disabled", it) }
                .getOrNull(),
            // Level 5 logs full SIP messages (no passwords, but digest headers) — debug builds only.
            logLevel = if (BuildConfig.DEBUG) 5 else 3,
            logSink = { level, msg -> Log.println(priorityOf(level), TAG, msg) },
        ),
    )

    private fun priorityOf(pjLevel: Int): Int = when {
        pjLevel <= 1 -> Log.ERROR
        pjLevel == 2 -> Log.WARN
        else -> Log.DEBUG
    }

    private const val TAG = "pjsip"
}
