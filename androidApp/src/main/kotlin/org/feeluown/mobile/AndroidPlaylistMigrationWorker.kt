package org.feeluown.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

internal class AndroidPlaylistMigrationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        val stage = inputData.getString(KEY_STAGE)
            ?.let { runCatching { PlaylistMigrationBackgroundStage.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        val application = applicationContext as? FuoEvolveApplication
            ?: return Result.failure()
        val controller = application.appUiGraph.playlistMigration
            ?: return Result.failure()

        ensureNotificationChannel()
        setForeground(createForegroundInfo(taskId, stage, null))

        return try {
            val needsContinuation = controller.runBackgroundSlice(
                taskId = taskId,
                stage = stage,
                maxSteps = MAX_STEPS_PER_SLICE,
            ) { task ->
                setForeground(createForegroundInfo(taskId, stage, task.backgroundProgress(stage)))
            }
            val finalTask = controller.tasks.value.firstOrNull { it.id == taskId }
            if (needsContinuation) {
                enqueueContinuation(applicationContext, taskId, stage)
            } else if (finalTask != null && finalTask.isTerminalFor(stage)) {
                publishTerminalNotification(finalTask.backgroundProgress(stage))
            }
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            AppLogger.w(TAG, "Playlist migration background stage failed: $taskId/${stage.name}", failure)
            if (runAttemptCount >= MAX_RETRY_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    private fun createForegroundInfo(
        taskId: String,
        stage: PlaylistMigrationBackgroundStage,
        progress: PlaylistMigrationBackgroundProgress?,
    ): ForegroundInfo {
        val notification = createNotification(taskId, stage, progress, terminal = false)
        val notificationId = progressNotificationId(taskId, stage)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun publishTerminalNotification(progress: PlaylistMigrationBackgroundProgress) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.notify(
            resultNotificationId(progress.taskId, progress.stage),
            createNotification(progress.taskId, progress.stage, progress, terminal = true),
        )
    }

    private fun createNotification(
        taskId: String,
        stage: PlaylistMigrationBackgroundStage,
        progress: PlaylistMigrationBackgroundProgress?,
        terminal: Boolean,
    ) = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(progress?.title ?: stage.initialTitle())
        .setContentText(progress?.detail ?: "正在准备")
        .setContentIntent(contentIntent(taskId, stage, progress?.openTarget))
        .setOngoing(!terminal)
        .setAutoCancel(terminal)
        .setOnlyAlertOnce(!terminal)
        .setSilent(!terminal)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setProgress(
            progress?.total ?: 0,
            progress?.completed ?: 0,
            progress?.indeterminate != false,
        )
        .build()

    private fun contentIntent(
        taskId: String,
        stage: PlaylistMigrationBackgroundStage,
        openTarget: PlaylistMigrationOpenTarget?,
    ): PendingIntent {
        val target = openTarget ?: when (stage) {
            PlaylistMigrationBackgroundStage.Conversion -> PlaylistMigrationOpenTarget.Review
            PlaylistMigrationBackgroundStage.Writing -> PlaylistMigrationOpenTarget.Result
        }
        val uri = Uri.parse("fuo://playlist-migration/$taskId?target=${target.name}")
        val openAppIntent = Intent(
            Intent.ACTION_VIEW,
            uri,
            applicationContext,
            PlaylistMigrationDeepLinkActivity::class.java,
        )
        return PendingIntent.getActivity(
            applicationContext,
            taskId.hashCode() xor stage.ordinal,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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

    private fun PlaylistMigrationTask.isTerminalFor(stage: PlaylistMigrationBackgroundStage): Boolean = when (stage) {
        PlaylistMigrationBackgroundStage.Conversion -> phase == MigrationPhase.Review ||
            (phase == MigrationPhase.Paused && resumePhase in setOf(MigrationPhase.Loading, MigrationPhase.Matching))
        PlaylistMigrationBackgroundStage.Writing -> phase == MigrationPhase.Complete ||
            phase == MigrationPhase.Partial ||
            (phase == MigrationPhase.Paused && resumePhase == MigrationPhase.Writing)
    }

    private fun PlaylistMigrationBackgroundStage.initialTitle(): String = when (this) {
        PlaylistMigrationBackgroundStage.Conversion -> "正在找歌"
        PlaylistMigrationBackgroundStage.Writing -> "正在迁移歌单"
    }

    companion object {
        private const val TAG = "PlaylistMigration"
        private const val CHANNEL_ID = "playlist_migration"
        private const val KEY_TASK_ID = "task_id"
        private const val KEY_STAGE = "stage"
        private const val MAX_STEPS_PER_SLICE = 24
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_BACKOFF_SECONDS = 30L
        private const val UNIQUE_WORK_PREFIX = "playlist-migration-"
        private const val PROGRESS_NOTIFICATION_ID_BASE = 28_000
        private const val RESULT_NOTIFICATION_ID_BASE = 36_000
        private const val NOTIFICATION_ID_MASK = 0x0FFF

        fun enqueue(context: Context, request: PlaylistMigrationBackgroundRequest) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(request.taskId, request.stage),
                ExistingWorkPolicy.KEEP,
                request(request.taskId, request.stage),
            )
        }

        private fun enqueueContinuation(
            context: Context,
            taskId: String,
            stage: PlaylistMigrationBackgroundStage,
        ) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(taskId, stage),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request(taskId, stage),
            )
        }

        private fun request(
            taskId: String,
            stage: PlaylistMigrationBackgroundStage,
        ): OneTimeWorkRequest = OneTimeWorkRequestBuilder<AndroidPlaylistMigrationWorker>()
            .setInputData(workDataOf(KEY_TASK_ID to taskId, KEY_STAGE to stage.name))
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

        private fun uniqueWorkName(taskId: String, stage: PlaylistMigrationBackgroundStage): String =
            "$UNIQUE_WORK_PREFIX${stage.name.lowercase()}-$taskId"

        private fun progressNotificationId(taskId: String, stage: PlaylistMigrationBackgroundStage): Int =
            PROGRESS_NOTIFICATION_ID_BASE + ((taskId.hashCode() xor stage.ordinal) and NOTIFICATION_ID_MASK)

        private fun resultNotificationId(taskId: String, stage: PlaylistMigrationBackgroundStage): Int =
            RESULT_NOTIFICATION_ID_BASE + ((taskId.hashCode() xor stage.ordinal) and NOTIFICATION_ID_MASK)
    }
}
