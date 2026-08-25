package com.decentralprospect.symposium

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class NotificationDismissedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, CallService::class.java).apply {
            action = CallService.ACTION_REPOST_NOTIFICATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.startForegroundService(serviceIntent) }
                .onFailure { diagnosticWarning(TAG, "Unable to repost call notification: ${it.message}") }
        } else {
            runCatching { context.startService(serviceIntent) }
                .onFailure { diagnosticWarning(TAG, "Unable to repost call notification: ${it.message}") }
        }
    }
}

class CallService : Service(), CallForegroundController {

    private val binder = LocalBinder()
    private var microphoneForeground = false
    private var foregroundStarted = false
    private var runtimeDestroyed = false

    internal lateinit var runtime: CallRuntime
        private set

    inner class LocalBinder : Binder() {
        fun service(): CallService = this@CallService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runtime = CallRuntime(applicationContext, this).also { it.initialize() }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        if (::runtime.isInitialized && runtime.webSocket == null && !runtime.connectedUiState) {
            stopSelf()
        }
        return super.onUnbind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val useMicrophone = when (intent?.action) {
            ACTION_START_MICROPHONE -> true
            ACTION_START_LISTENER -> false
            ACTION_REPOST_NOTIFICATION -> microphoneForeground
            ACTION_BIND_ONLY -> microphoneForeground
            else -> microphoneForeground
        }

        when (intent?.action) {
            ACTION_REPOST_NOTIFICATION -> ensureCallForeground(useMicrophone, force = true)
            ACTION_START_MICROPHONE,
            ACTION_START_LISTENER -> ensureCallForeground(useMicrophone)
            null -> ensureCallForeground(useMicrophone)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        foregroundStarted = false
        if (!runtimeDestroyed && ::runtime.isInitialized) {
            runtimeDestroyed = true
            runtime.shutdownBecauseServiceDestroyed()
        }
        super.onDestroy()
    }

    override fun startForegroundForCall(microphone: Boolean) {
        ensureCallForeground(microphone)
    }

    private fun ensureCallForeground(microphone: Boolean, force: Boolean = false) {
        if (!force && foregroundStarted && microphoneForeground == microphone) return

        if (startCallForeground(microphone)) {
            microphoneForeground = microphone
            foregroundStarted = true
        }
    }

    override fun stopForegroundForCall() {
        microphoneForeground = false
        foregroundStarted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startCallForeground(useMicrophone: Boolean): Boolean {
        val notification = buildNotification(useMicrophone)

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

                if (useMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }

                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            diagnosticError(TAG, "Unable to keep call service in foreground: ${it.javaClass.simpleName}: ${it.message}")
        }.isSuccess
    }

    private fun dismissedIntent(): PendingIntent {
        val intent = Intent(this, NotificationDismissedReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(this, 3001, intent, flags)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, 2001, intent, flags)
    }

    private fun buildNotification(useMicrophone: Boolean): Notification {
        val openIntent = openAppIntent()
        val text = if (useMicrophone) {
            getString(R.string.call_active)
        } else {
            getString(R.string.call_listening)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.call_in_progress))
            .setContentText(text)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.open_app), openIntent)
            .setDeleteIntent(dismissedIntent())
            .build()

        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        return notification
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.calls_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.calls_channel_description)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "call_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_BIND_ONLY = "com.decentralprospect.symposium.ACTION_BIND_ONLY"
        const val ACTION_START_LISTENER = "com.decentralprospect.symposium.ACTION_START_LISTENER"
        const val ACTION_START_MICROPHONE = "com.decentralprospect.symposium.ACTION_START_MICROPHONE"
        const val ACTION_REPOST_NOTIFICATION = "com.decentralprospect.symposium.ACTION_REPOST_NOTIFICATION"
    }
}
