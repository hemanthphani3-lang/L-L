package com.offgrid.app.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.offgrid.app.MainActivity
import com.offgrid.app.core.model.MessageType
import java.util.concurrent.ConcurrentHashMap

object NotificationHelper {

    const val CHANNEL_FOREGROUND = "offgrid_connection_channel"
    const val CHANNEL_MESSAGES = "offgrid_messages_channel"
    const val CHANNEL_TRANSFERS = "offgrid_transfers_channel"

    private val lastTransferNotificationTimes = ConcurrentHashMap<String, Long>()
    private val lastTransferNotificationProgress = ConcurrentHashMap<String, Int>()

    fun initChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // 1. Foreground mesh connection channel (ongoing, low distraction)
            val sessionChannel = NotificationChannel(
                CHANNEL_FOREGROUND,
                "Local Link Mesh Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active local offline mesh and background communication status"
                setShowBadge(false)
            }

            // 2. Incoming messages channel (high importance, alert user)
            val messagesChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Local Link Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for incoming messages, photos, voice notes, and files"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                setSound(
                    defaultSound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }

            // 3. File transfer progress channel (low importance, progress bar updates)
            val transfersChannel = NotificationChannel(
                CHANNEL_TRANSFERS,
                "Local Link File Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays ongoing background file and media transfer progress"
                setShowBadge(false)
            }

            manager.createNotificationChannels(listOf(sessionChannel, messagesChannel, transfersChannel))
        }
    }

    fun showMessageNotification(
        context: Context,
        peerId: String,
        senderName: String,
        messageText: String,
        messageType: MessageType
    ) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_NAVIGATE_PEER_ID, peerId)
                putExtra(MainActivity.EXTRA_NAVIGATE_PEER_NAME, senderName)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                peerId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val displayBody = when (messageType) {
                MessageType.VOICE_MESSAGE -> "🎙️ Voice message"
                MessageType.IMAGE -> "📷 Photo"
                MessageType.VIDEO -> "🎥 Video"
                MessageType.AUDIO -> "🎵 Audio file"
                MessageType.DOCUMENT -> "📄 Document"
                else -> messageText
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(senderName)
                .setContentText(displayBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText(displayBody))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setGroup("LOCAL_LINK_MESSAGES_GROUP")
                .build()

            val notificationId = (peerId.hashCode() and 0x7FFFFFFF) % 10000 + 1000
            val manager = NotificationManagerCompat.from(context)
            manager.notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Android 13+ POST_NOTIFICATIONS not granted yet
        } catch (_: Exception) {}
    }

    fun cancelMessageNotification(context: Context, peerId: String) {
        try {
            val notificationId = (peerId.hashCode() and 0x7FFFFFFF) % 10000 + 1000
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(notificationId)
        } catch (_: Exception) {}
    }

    fun updateTransferProgressNotification(
        context: Context,
        transferId: String,
        fileName: String,
        bytesTransferred: Long,
        totalBytes: Long,
        isIncoming: Boolean
    ) {
        if (totalBytes <= 0) return
        val now = System.currentTimeMillis()
        val lastTime = lastTransferNotificationTimes[transferId] ?: 0L
        val progressPct = ((bytesTransferred * 100L) / totalBytes).toInt().coerceIn(0, 100)
        val lastPct = lastTransferNotificationProgress[transferId] ?: -1

        // Throttle updates: update only every 400ms or if progress jumped at least 5% or reached 100%
        if (now - lastTime < 400 && progressPct < 100 && (progressPct - lastPct) < 5) {
            return
        }

        lastTransferNotificationTimes[transferId] = now
        lastTransferNotificationProgress[transferId] = progressPct

        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = if (isIncoming) "Receiving $fileName" else "Sending $fileName"
            val text = "${formatFileSize(bytesTransferred)} of ${formatFileSize(totalBytes)} ($progressPct%)"

            val notification = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
                .setSmallIcon(if (isIncoming) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, progressPct, false)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build()

            val notificationId = (transferId.hashCode() and 0x7FFFFFFF) % 10000 + 20000
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: Exception) {}
    }

    fun cancelTransferNotification(context: Context, transferId: String) {
        lastTransferNotificationTimes.remove(transferId)
        lastTransferNotificationProgress.remove(transferId)
        try {
            val notificationId = (transferId.hashCode() and 0x7FFFFFFF) % 10000 + 20000
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (_: Exception) {}
    }

    fun showTransferCompleteNotification(
        context: Context,
        transferId: String,
        fileName: String,
        isIncoming: Boolean
    ) {
        cancelTransferNotification(context, transferId)
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = if (isIncoming) "File Received" else "File Sent"
            val text = fileName

            val notification = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            val notificationId = (transferId.hashCode() and 0x7FFFFFFF) % 10000 + 20000
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: Exception) {}
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> "%.1f MB".format(mb)
            kb >= 1.0 -> "%.1f KB".format(kb)
            else -> "$bytes B"
        }
    }
}
