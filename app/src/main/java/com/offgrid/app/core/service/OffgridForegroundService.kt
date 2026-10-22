package com.offgrid.app.core.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.offgrid.app.MainActivity
import com.offgrid.app.OffgridEngine
import com.offgrid.app.core.notification.NotificationHelper

class OffgridForegroundService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.offgrid.app.action.START_SERVICE"
        const val ACTION_STOP = "com.offgrid.app.action.STOP_SERVICE"
        const val ACTION_UPDATE_STATUS = "com.offgrid.app.action.UPDATE_STATUS"
        const val EXTRA_STATUS = "extra_status"

        @Volatile
        var isServiceRunning = false
            private set

        fun start(context: Context, status: String = "Local Link Mesh Active") {
            try {
                val intent = Intent(context, OffgridForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_STATUS, status)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.w("OffgridFgService", "Unable to start foreground service: ${e.message}")
            }
        }

        fun updateStatus(context: Context, status: String) {
            if (!isServiceRunning) return
            try {
                val intent = Intent(context, OffgridForegroundService::class.java).apply {
                    action = ACTION_UPDATE_STATUS
                    putExtra(EXTRA_STATUS, status)
                }
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            if (!isServiceRunning) return
            try {
                val intent = Intent(context, OffgridForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStatus: String = "Local offline wireless session active"

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.initChannels(this)
        isServiceRunning = true
        android.util.Log.i("OffgridFgService", "[SERVICE_CREATED] OFFGRID Foreground Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            android.util.Log.i("OffgridFgService", "[SERVICE_STOPPING] User or system requested service stop")
            releaseWakeLock()
            isServiceRunning = false
            // Disconnect all peers gracefully
            try {
                OffgridEngine.getInstance(this).disconnectAll()
            } catch (_: Exception) {}
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val statusText = intent?.getStringExtra(EXTRA_STATUS) ?: lastStatus
        lastStatus = statusText

        val notification = buildNotification(statusText)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isServiceRunning = true

        if (action == ACTION_UPDATE_STATUS) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, OffgridForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_FOREGROUND)
            .setContentTitle("Local Link Mesh Connected")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
            .build()
    }

    fun acquireWakeLock(tag: String = "Offgrid:TransferWakeLock") {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)?.apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(10 * 60 * 1000L) // 10 minutes safety timeout
                }
            }
        } catch (_: Exception) {}
    }

    fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        releaseWakeLock()
        isServiceRunning = false
        android.util.Log.i("OffgridFgService", "[SERVICE_DESTROYED] OFFGRID Foreground Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
