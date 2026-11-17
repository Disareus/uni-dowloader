package ua.aniua.downloader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ua.aniua.downloader.R
import ua.aniua.downloader.model.DownloadState
import ua.aniua.downloader.model.DownloadTask
import ua.aniua.downloader.ui.DownloadsActivity

class DownloadService : Service() {

    companion object {
        const val ACTION_START_DOWNLOAD = "ua.aniua.downloader.action.START_DOWNLOAD"
        const val EXTRA_TASK = "extra_download_task"
        private const val CHANNEL_ID = "aniua_downloads_channel"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFY_THROTTLE_MS = 450L
    }

    private lateinit var notificationManager: NotificationManager
    private lateinit var queueManager: DownloadQueueManager
    private var lastNotifyTime = 0L

    private val taskListener: (DownloadTask) -> Unit = { task ->
        val activeCount = queueManager.getActiveCount()
        val now = System.currentTimeMillis()

        when (task.state) {
            DownloadState.DOWNLOADING -> {
                if (now - lastNotifyTime >= NOTIFY_THROTTLE_MS) {
                    lastNotifyTime = now
                    val prefix = if (activeCount > 1) "[$activeCount активних] " else ""
                    val sub = buildString {
                        if (task.speed.isNotEmpty()) append(task.speed)
                        if (task.eta.isNotEmpty()) {
                            if (isNotEmpty()) append(" • ")
                            append(task.eta)
                        }
                    }.ifEmpty { "Завантаження…" }
                    val title = if (task.isSingle) {
                        "${prefix}${task.mediaTitle}"
                    } else {
                        "${prefix}${task.mediaTitle} (Серія ${task.episodeNumber})"
                    }
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(title, task.progress.toInt(), sub))
                }
            }
            DownloadState.COMPLETED, DownloadState.ERROR, DownloadState.CANCELLED -> {
                if (activeCount == 0) {
                    val (title, text) = when (task.state) {
                        DownloadState.COMPLETED -> {
                            val subText = if (task.isSingle) "Відео збережено" else "Серія ${task.episodeNumber} збережена"
                            "Завантаження завершено: ${task.mediaTitle}" to subText
                        }
                        DownloadState.ERROR -> "Помилка завантаження: ${task.mediaTitle}" to (task.errorMessage ?: "Невідома помилка")
                        else -> "Завантаження скасовано" to task.mediaTitle
                    }
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(title, 0, text))
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
            else -> Unit
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        queueManager = DownloadQueueManager.getInstance(this)
        queueManager.addListener(taskListener)

        createNotificationChannel()
        val initialNotification = buildNotification("Служба завантаження готова", 0, "Очікування завдань")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_DOWNLOAD) {
            val task = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra(EXTRA_TASK, DownloadTask::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra(EXTRA_TASK) as? DownloadTask
            }
            if (task != null) {
                queueManager.enqueueDownload(task)
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String, progress: Int, text: String): Notification {
        val notifyIntent = Intent(this, DownloadsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notifyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(progress in 1..99)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress in 1..99) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, false)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_downloads_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_downloads_desc)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        queueManager.removeListener(taskListener)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
