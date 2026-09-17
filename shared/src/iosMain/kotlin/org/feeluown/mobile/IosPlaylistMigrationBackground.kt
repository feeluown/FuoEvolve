package org.feeluown.mobile

/** Swift-owned BackgroundTasks implementation used by the iOS application host. */
interface IosPlaylistMigrationBackgroundOutput {
    fun enqueue(taskId: String, sourceTitle: String)
}

fun installIosPlaylistMigrationBackgroundOutput(output: IosPlaylistMigrationBackgroundOutput?) {
    installPlaylistMigrationBackgroundScheduler(
        output?.let { bridge ->
            { request -> bridge.enqueue(request.taskId, request.sourceTitle) }
        },
    )
}
