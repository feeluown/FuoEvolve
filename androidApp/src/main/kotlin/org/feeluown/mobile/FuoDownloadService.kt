package org.feeluown.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class FuoDownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = intent?.getIntExtra(EXTRA_ACTIVE_COUNT, 0) ?: 0
        val title = if (active > 0) "正在下载 $active 个任务" else "下载队列"
        startForeground(NOTIFICATION_ID, notification(title))
        return START_NOT_STICKY
    }

    private fun notification(title: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("FeelUOwn 下载")
        .setContentText(title)
        .setOngoing(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "下载任务", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        private const val CHANNEL_ID = "fuo_downloads"
        private const val NOTIFICATION_ID = 1002
        private const val EXTRA_ACTIVE_COUNT = "active_count"

        fun update(context: Context, tasks: List<DownloadTask>) {
            val active = tasks.count { it.status == DownloadTaskStatus.Downloading || it.status == DownloadTaskStatus.Queued }
            if (active == 0) {
                context.stopService(Intent(context, FuoDownloadService::class.java))
                return
            }
            val intent = Intent(context, FuoDownloadService::class.java).putExtra(EXTRA_ACTIVE_COUNT, active)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
