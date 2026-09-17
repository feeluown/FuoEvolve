package org.feeluown.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

internal class AndroidPlaylistMigrationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        val application = applicationContext as? FuoEvolveApplication
            ?: return Result.failure()
        val controller = application.appUiGraph.playlistMigration
            ?: return Result.failure()

        ensureNotificationChannel()
        setForeground(createForegroundInfo(taskId, null))

        return try {
            val needsContinuation = controller.runBackgroundSlice(taskId, MAX_STEPS_PER_SLICE) { task ->
                setForeground(createForegroundInfo(taskId, task.backgroundProgress()))
            }
            if (needsContinuation) enqueueContinuation(applicationContext, taskId)
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            AppLogger.w(TAG, "Playlist migration background slice failed: $taskId", failure)
            if (runAttemptCount >= MAX_RETRY_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    private fun createForegroundInfo(
        taskId: String,
        progress: PlaylistMigrationBackgroundProgress?,
    ): ForegroundInfo {
        val openAppIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            taskId.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(progress?.title ?: "正在迁移歌单")
            .setContentText(progress?.detail ?: "正在准备迁移")
            .setContentIntent(contentIntent)
            .setOngoing(progress?.terminal != true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(
                progress?.total ?: 0,
                progress?.completed ?: 0,
                progress?.indeterminate != false,
            )
            .build()
        val notificationId = NOTIFICATION_ID_BASE + (taskId.hashCode() and NOTIFICATION_ID_MASK)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "歌单迁移",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示歌单迁移进度"
                setSound(null, null)
            },
        )
    }

    companion object {
        private const val TAG = "PlaylistMigration"
        private const val CHANNEL_ID = "playlist_migration"
        private const val KEY_TASK_ID = "task_id"
        private const val MAX_STEPS_PER_SLICE = 24
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_BACKOFF_SECONDS = 30L
        private const val UNIQUE_WORK_PREFIX = "playlist-migration-"
        private const val NOTIFICATION_ID_BASE = 28_000
        private const val NOTIFICATION_ID_MASK = 0x0FFF

        fun enqueue(context: Context, taskId: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(taskId),
                ExistingWorkPolicy.KEEP,
                request(taskId),
            )
        }

        private fun enqueueContinuation(context: Context, taskId: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(taskId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request(taskId),
            )
        }

        private fun request(taskId: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<AndroidPlaylistMigrationWorker>()
                .setInputData(workDataOf(KEY_TASK_ID to taskId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    RETRY_BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                )
                .build()

        private fun uniqueWorkName(taskId: String): String = UNIQUE_WORK_PREFIX + taskId
    }
}
