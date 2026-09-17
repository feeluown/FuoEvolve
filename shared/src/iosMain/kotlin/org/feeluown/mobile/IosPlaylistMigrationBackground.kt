package org.feeluown.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Swift-owned BackgroundTasks implementation used by the iOS application host. */
interface IosPlaylistMigrationBackgroundOutput {
    fun attach(runner: IosPlaylistMigrationBackgroundRunner)
    fun enqueue(taskId: String, sourceTitle: String)
}

/**
 * Callback-oriented bridge so Swift BackgroundTasks code can execute checkpointed Kotlin work
 * without owning provider state. Completion reports whether another slice remains runnable.
 */
class IosPlaylistMigrationBackgroundRunner internal constructor(
    private val controller: PlaylistMigrationFeatureController,
    private val scope: CoroutineScope,
) {
    fun runSlice(
        taskId: String,
        maxSteps: Int,
        onProgress: (PlaylistMigrationBackgroundProgress) -> Unit,
        completionHandler: (Boolean, String?) -> Unit,
    ) {
        scope.launch {
            try {
                val needsContinuation = controller.runBackgroundSlice(
                    taskId = taskId,
                    maxSteps = maxSteps.coerceAtLeast(1),
                    onProgress = { task -> onProgress(task.backgroundProgress()) },
                )
                completionHandler(needsContinuation, null)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                completionHandler(false, failure.message ?: "迁移失败")
            }
        }
    }

    fun pause(taskId: String, completionHandler: (String?) -> Unit) {
        scope.launch {
            try {
                controller.coordinator.pause(taskId)
                completionHandler(null)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                completionHandler(failure.message ?: "暂停迁移失败")
            }
        }
    }
}
