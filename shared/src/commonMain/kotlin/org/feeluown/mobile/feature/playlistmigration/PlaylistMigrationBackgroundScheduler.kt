package org.feeluown.mobile

/**
 * Platform-owned durability hook. Common migration logic remains usable without a scheduler;
 * Android installs a WorkManager-backed implementation during Application startup.
 */
private var playlistMigrationBackgroundScheduler: ((String) -> Unit)? = null

fun installPlaylistMigrationBackgroundScheduler(scheduler: ((String) -> Unit)?) {
    playlistMigrationBackgroundScheduler = scheduler
}

internal fun enqueuePlaylistMigrationBackground(taskId: String) {
    playlistMigrationBackgroundScheduler?.invoke(taskId)
}
