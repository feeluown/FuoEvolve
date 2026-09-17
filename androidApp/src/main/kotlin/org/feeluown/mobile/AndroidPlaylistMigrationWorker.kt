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
                setForeground(createForegroundInfo(taskId, task))
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

    private fun createForegroundInfo(taskId: String, task: PlaylistMigrationTask?): ForegroundInfo {
        val progress = task?.notificationProgress()
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
            .setContentText(progress?.text ?: "正在准备迁移")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(progress?.total ?: 0, progress?.completed ?: 0, progress?.indeterminate != false)
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

    private data class NotificationProgress(
        val title: String,
        val text: String,
        val completed: Int,
        val total: Int,
        val indeterminate: Boolean,
    )

    private fun PlaylistMigrationTask.notificationProgress(): NotificationProgress = when (phase) {
        MigrationPhase.Loading -> NotificationProgress(
            title = "正在迁移 ${source.title}",
            text = "正在读取歌曲 · 已读取 ${entries.size} 首",
            completed = 0,
            total = 0,
            indeterminate = true,
        )
        MigrationPhase.Matching -> {
            val completed = entries.count { it.status != MigrationTrackStatus.Pending }
            NotificationProgress(
                title = "正在迁移 ${source.title}",
                text = "正在匹配歌曲 · $completed/${entries.size}",
                completed = completed,
                total = entries.size.coerceAtLeast(1),
                indeterminate = entries.isEmpty(),
            )
        }
        MigrationPhase.Writing -> {
            val writable = entries.filter { it.selected != null && it.status != MigrationTrackStatus.Skipped }
            val completed = writable.count {
                it.status == MigrationTrackStatus.Added ||
                    it.status == MigrationTrackStatus.Failed ||
                    it.status == MigrationTrackStatus.Uncertain
            }
            NotificationProgress(
                title = "正在迁移 ${source.title}",
                text = "正在写入歌曲 · $completed/${writable.size}",
                completed = completed,
                total = writable.size.coerceAtLeast(1),
                indeterminate = writable.isEmpty(),
            )
        }
        MigrationPhase.Complete -> NotificationProgress(
            title = "歌单迁移完成",
            text = "已迁移 $addedCount 首${if (skippedCount > 0) " · 跳过 $skippedCount 首" else ""}",
            completed = 1,
            total = 1,
            indeterminate = false,
        )
        MigrationPhase.Partial -> NotificationProgress(
            title = "歌单迁移需要处理",
            text = "已迁移 $addedCount 首 · $failedCount 首待重试",
            completed = addedCount,
            total = (addedCount + failedCount).coerceAtLeast(1),
            indeterminate = false,
        )
        MigrationPhase.Review -> NotificationProgress(
            title = "歌单迁移等待确认",
            text = "有 $unresolvedCount 首需要确认",
            completed = 0,
            total = 0,
            indeterminate = true,
        )
        MigrationPhase.Destination -> NotificationProgress(
            title = "歌单迁移等待确认",
            text = "请选择目标歌单",
            completed = 0,
            total = 0,
            indeterminate = true,
        )
        MigrationPhase.Paused -> NotificationProgress(
            title = "歌单迁移已暂停",
            text = "已迁移 $addedCount 首",
            completed = 0,
            total = 0,
            indeterminate = true,
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
