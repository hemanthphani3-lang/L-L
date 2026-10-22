package com.offgrid.app.feature.screenshare

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.offgrid.app.MainActivity
import com.offgrid.app.OffgridApplication
import com.offgrid.app.core.notification.NotificationHelper

class ScreenShareService : Service() {

    companion object {
        const val NOTIFICATION_ID = 2002
        const val ACTION_START = "com.offgrid.app.screenshare.START"
        const val ACTION_STOP = "com.offgrid.app.screenshare.STOP"
        const val ACTION_UPDATE = "com.offgrid.app.screenshare.UPDATE"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_VIEWERS = "extra_viewers"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        const val EXTRA_CAPTURE_MIC = "extra_capture_mic"
        const val EXTRA_CAPTURE_SYSTEM = "extra_capture_system"

        @Volatile
        var isRunning = false
            private set

        fun start(
            context: Context,
            url: String,
            resultCode: Int = 0,
            data: Intent? = null,
            captureMic: Boolean = false,
            captureSystemAudio: Boolean = true
        ) {
            val intent = Intent(context, ScreenShareService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_CAPTURE_MIC, captureMic)
                putExtra(EXTRA_CAPTURE_SYSTEM, captureSystemAudio)
                if (resultCode != 0) putExtra(EXTRA_RESULT_CODE, resultCode)
                if (data != null) putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun update(context: Context, url: String, viewers: Int) {
            if (!isRunning) return
            val intent = Intent(context, ScreenShareService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_VIEWERS, viewers)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenShareService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var currentUrl = ""
    private var currentViewers = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.initChannels(this)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            isRunning = false
            try {
                OffgridApplication.getEngine(this).screenShareManager.stopScreenShare()
            } catch (_: Exception) {}
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        currentUrl = intent?.getStringExtra(EXTRA_URL) ?: currentUrl
        currentViewers = intent?.getIntExtra(EXTRA_VIEWERS, currentViewers) ?: currentViewers
        val captureMic = intent?.getBooleanExtra(EXTRA_CAPTURE_MIC, false) ?: false

        val notification = buildNotification(currentUrl, currentViewers)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (captureMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(
                NOTIFICATION_ID,
                notification,
                fgsType
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (action == ACTION_START && resultCode != 0 && data != null) {
            android.util.Log.i("ScreenShareService", "[SERVICE] Foreground active with mediaProjection. Initializing ScreenCapturer...")
            OffgridApplication.getEngine(this).screenShareManager.onServiceForegroundActive(resultCode, data)
        }

        if (action == ACTION_UPDATE) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    private fun buildNotification(url: String, viewers: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ScreenShareService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = if (url.isNotBlank()) {
            "Sharing at $url ($viewers viewer${if (viewers == 1) "" else "s"})"
        } else {
            "Local screen share is active"
        }

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_FOREGROUND)
            .setContentTitle("🔴 Local Link Live Screen Share")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Sharing", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }
}
