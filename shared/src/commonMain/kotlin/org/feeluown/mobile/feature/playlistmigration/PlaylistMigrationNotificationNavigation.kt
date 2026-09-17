package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaylistMigrationOpenRequest(
    val taskId: String,
    val target: PlaylistMigrationOpenTarget,
    val sequence: Long,
)

private val mutablePlaylistMigrationOpenRequest = MutableStateFlow<PlaylistMigrationOpenRequest?>(null)
internal val playlistMigrationOpenRequest: StateFlow<PlaylistMigrationOpenRequest?> =
    mutablePlaylistMigrationOpenRequest.asStateFlow()

private var requestSequence = 0L
private var navigationSequence = 0L
private var navigationHandler: Pair<Long, (PlaylistMigrationOpenRequest) -> Unit>? = null
private var pendingNavigation: PlaylistMigrationOpenRequest? = null

internal fun focusPlaylistMigrationTask(
    taskId: String,
    target: PlaylistMigrationOpenTarget,
): PlaylistMigrationOpenRequest {
    require(taskId.isNotBlank())
    requestSequence += 1L
    return PlaylistMigrationOpenRequest(taskId, target, requestSequence).also {
        mutablePlaylistMigrationOpenRequest.value = it
    }
}

/**
 * Native notification handlers can call this before Compose exists. The request is retained until
 * AppRoot installs its navigator, so a cold-start notification click cannot be lost.
 */
fun openPlaylistMigrationFromNotification(taskId: String, target: String) {
    val normalizedTaskId = taskId.trim()
    if (normalizedTaskId.isEmpty()) return
    val openTarget = runCatching { PlaylistMigrationOpenTarget.valueOf(target) }.getOrNull() ?: return
    val request = focusPlaylistMigrationTask(normalizedTaskId, openTarget)
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
