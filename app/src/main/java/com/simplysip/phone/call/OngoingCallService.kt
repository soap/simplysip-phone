package com.simplysip.phone.call

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.simplysip.phone.MainActivity
import com.simplysip.phone.R
import com.simplysip.phone.sip.engine.call.CallState
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (type microphone) while a call is ongoing, so audio capture and the process
 * survive leaving the app. Interim until Core-Telecom in Phase 4.
 */
@AndroidEntryPoint
class OngoingCallService : Service() {

    @Inject lateinit var controller: CallController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    // FOREGROUND_SERVICE_TYPE_MICROPHONE is an inlined int; ServiceCompat drops it below API 29.
    @SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANGUP) {
            controller.hangup()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            createChannel()
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification(controller.state.value),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } catch (e: Exception) {
                // e.g. microphone permission revoked or started from the background.
                Log.w(TAG, "cannot enter foreground", e)
                stopSelf()
                return START_NOT_STICKY
            }
            scope.launch {
                controller.state.collect { state ->
                    if (state is CallState.Outgoing || state is CallState.Active) {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: CallState): Notification {
        val remote = (state as? CallState.Live)?.remote?.label.orEmpty()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val hangup = PendingIntent.getService(
            this,
            1,
            Intent(this, OngoingCallService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(remote)
            .setContentText(getString(if (state is CallState.Active) R.string.call_notification_active else R.string.call_status_calling))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_call_end, getString(R.string.call_hangup), hangup)
            .apply {
                if (state is CallState.Active) {
                    // Chronometer counts from wall-clock "when"; convert from elapsed realtime.
                    setUsesChronometer(true)
                    setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - state.connectedAtMillis))
                    setShowWhen(true)
                }
            }
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.call_channel_name), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "OngoingCallService"
        private const val CHANNEL_ID = "ongoing_call"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_HANGUP = "com.simplysip.phone.action.HANGUP"
    }
}

@Singleton
class OngoingCallServiceStarter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun start() {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, OngoingCallService::class.java)) }
            .onFailure { Log.w("OngoingCallService", "cannot start", it) }
    }

    fun stop() {
        context.stopService(Intent(context, OngoingCallService::class.java))
    }
}
