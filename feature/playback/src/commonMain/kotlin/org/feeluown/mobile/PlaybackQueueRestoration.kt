package org.feeluown.mobile

/**
 * Reconciles the durable controller queue with a platform playback session restored after process
 * recreation.
 *
 * The active Up Next item is intentionally absent from [PlaybackQueueSnapshot.upNextQueue] while it
 * is playing. If the restored track is not present in either durable queue, keep it as the first
 * pending item so FuoPlayerController.synchronizePlaybackTrack can promote it back to the active Up
 * Next slot when the playback engine republishes its state.
 *
 * Older builds could also overwrite the queue with an empty bootstrap snapshot. In that case the
 * playback plan is the best remaining source of truth, so recover the current item and its ordered
 * look-ahead window.
 */
fun PlaybackQueueSnapshot.reconcileRestoredPlayback(
    plan: PlaybackPlan?,
    currentTrack: MusicTrack?,
): PlaybackQueueSnapshot {
    currentTrack ?: return this
    val knownTrackIds = (mainQueue + upNextQueue).mapTo(mutableSetOf()) { it.id }
    if (currentTrack.id in knownTrackIds) return this

    if (mainQueue.isEmpty() && upNextQueue.isEmpty()) {
        val requests = plan?.requests.orEmpty()
        val currentRequestIndex = requests.indexOfFirst { it.track.id == currentTrack.id }
        val recoveredQueue = requests
            .drop(currentRequestIndex.coerceAtLeast(0))
            .map { it.track }
            .distinctBy { it.id }
            .let { tracks ->
                if (tracks.any { it.id == currentTrack.id }) tracks else listOf(currentTrack) + tracks
            }
            .ifEmpty { listOf(currentTrack) }
        return copy(
            mainQueue = recoveredQueue,
            queueIndex = recoveredQueue.indexOfFirst { it.id == currentTrack.id }.coerceAtLeast(0),
        )
    }

    return copy(upNextQueue = listOf(currentTrack) + upNextQueue)
}
