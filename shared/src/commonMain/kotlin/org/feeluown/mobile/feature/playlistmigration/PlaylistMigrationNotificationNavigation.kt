package org.feeluown.mobile

data class PlaylistMigrationOpenRequest(
    val taskId: String,
    val target: PlaylistMigrationOpenTarget,
)

private var navigationSequence = 0L
private var navigationHandler: Pair<Long, (PlaylistMigrationOpenRequest) -> Unit>? = null
private var pendingNavigation: PlaylistMigrationOpenRequest? = null

/**
 * Native notification handlers can call this before Compose exists. The request is retained until
 * AppRoot installs its navigator, so a cold-start notification click cannot be lost. Once Compose
 * is ready, the request becomes a typed PlaylistMigrationDetail route instead of mutable screen
 * selection state.
 */
fun openPlaylistMigrationFromNotification(taskId: String, target: String) {
    val normalizedTaskId = taskId.trim()
    if (normalizedTaskId.isEmpty()) return
    val openTarget = runCatching { PlaylistMigrationOpenTarget.valueOf(target) }.getOrNull() ?: return
    val request = PlaylistMigrationOpenRequest(normalizedTaskId, openTarget)
    val handler = navigationHandler?.second
    if (handler == null) {
        pendingNavigation = request
    } else {
        handler(request)
    }
}

internal fun installPlaylistMigrationNotificationNavigator(
    handler: (PlaylistMigrationOpenRequest) -> Unit,
): Long {
    navigationSequence += 1L
    val token = navigationSequence
    navigationHandler = token to handler
    pendingNavigation?.let { pending ->
        pendingNavigation = null
        handler(pending)
    }
    return token
}

internal fun uninstallPlaylistMigrationNotificationNavigator(token: Long) {
    if (navigationHandler?.first == token) navigationHandler = null
}
