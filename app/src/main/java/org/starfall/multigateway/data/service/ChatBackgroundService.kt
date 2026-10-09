package org.starfall.multigateway.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.starfall.multigateway.MainActivity

/**
 * Foreground service that keeps the process, CPU and Wi-Fi radio alive while a response is
 * generating, so streaming/tool connections are not cut ("Software caused connection abort")
 * when the app is sent to the background or the screen turns off.
 */
class ChatBackgroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "chat_background_channel"
        private const val NOTIFICATION_ID = 4556
        // Safety net only; the service is stopped (and locks released) when generation ends.
        private const val LOCK_TIMEOUT_MS = 6 * 60 * 60_000L
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        acquireLocks()
        // Generation belongs to the current process. Restarting an empty service
        // after process death would show "AI is thinking" with no live generation.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        // Renewed on every start command so a long generation never outlives the timeout.
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MultiGateway:Chat")
                ?.apply { setReferenceCounted(false) }
        }
        wakeLock?.acquire(LOCK_TIMEOUT_MS)
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(mode, "MultiGateway:Chat")
                ?.apply { setReferenceCounted(false) }
        }
        runCatching { if (wifiLock?.isHeld != true) wifiLock?.acquire() }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI is thinking...")
            .setContentText("The AI model is generating a response in the background.")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "AI Background Generation",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps AI generation alive in the background"
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}
