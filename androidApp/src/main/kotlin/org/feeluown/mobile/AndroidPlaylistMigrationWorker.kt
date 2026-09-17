package org.feeluown.mobile

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
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

        return try {
            val needsContinuation = controller.runBackgroundSlice(taskId, MAX_STEPS_PER_SLICE)
            if (needsContinuation) enqueueContinuation(applicationContext, taskId)
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            Log.w(TAG, "Playlist migration background slice failed: $taskId", failure)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "PlaylistMigration"
        private const val KEY_TASK_ID = "task_id"
        private const val MAX_STEPS_PER_SLICE = 24
        private const val RETRY_BACKOFF_SECONDS = 30L
        private const val UNIQUE_WORK_PREFIX = "playlist-migration-"

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
