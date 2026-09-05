package org.feeluown.mobile

/**
 * Reconciles the durable controller queue with a platform playback session restored after process
 * recreation.
 *
 * The active Up Next item is intentionally absent from [PlaybackQueueSnapshot.upNextQueue] while it
 * is playing. When the platform persisted a [currentQueueEntryId], occurrence identity takes
 * precedence over logical track identity so a duplicate Up Next item can be reinserted even when the
 * same logical track is still present in the main queue.
 *
 * Older builds could also overwrite the queue with an empty bootstrap snapshot. In that case the
 * playback plan is the best remaining source of truth, so recover the current item and its ordered
 * look-ahead window.
 */
fun PlaybackQueueSnapshot.reconcileRestoredPlayback(
    plan: PlaybackPlan?,
    currentTrack: MusicTrack?,
    currentQueueEntryId: Long? = null,
): PlaybackQueueSnapshot {
    currentTrack ?: return this

    val restoredEntryId = currentQueueEntryId?.takeIf { it > 0L }
    if (restoredEntryId != null) {
        val knownEntryIds = (mainQueueEntryIds + upNextQueueEntryIds).toSet()
        if (restoredEntryId in knownEntryIds) return this

        if (mainQueue.isEmpty() && upNextQueue.isEmpty()) {
            val requests = plan?.requests.orEmpty()
            val currentRequestIndex = requests.indexOfFirst { request ->
                request.queueEntryId == restoredEntryId
            }.takeIf { it >= 0 }
                ?: requests.indexOfFirst { request -> request.track.id == currentTrack.id }
                    .takeIf { it >= 0 }
                ?: 0
            val recoveredRequests = requests.drop(currentRequestIndex)
            val recoveredTracks = recoveredRequests.map { it.track }
                .let { tracks ->
                    if (tracks.isEmpty()) listOf(currentTrack) else tracks
                }
            val recoveredEntryIds = recoveredRequests.mapNotNull(PlaybackRequest::queueEntryId)
                .takeIf { ids ->
                    ids.size == recoveredTracks.size &&
                        ids.all { it > 0L } &&
                        ids.distinct().size == ids.size
                }
                ?: emptyList()
            return copy(
                mainQueue = recoveredTracks,
                mainQueueEntryIds = recoveredEntryIds,
                queueIndex = 0,
                queueEntrySequence = maxOf(
                    queueEntrySequence,
                    restoredEntryId,
                    recoveredEntryIds.maxOrNull() ?: 0L,
                ),
            )
        }

        return copy(
            upNextQueue = listOf(currentTrack) + upNextQueue,
            upNextQueueEntryIds = listOf(restoredEntryId) + upNextQueueEntryIds,
            queueEntrySequence = maxOf(queueEntrySequence, restoredEntryId),
        )
    }

    // Legacy restoration has no occurrence identity and therefore falls back to logical track ids.
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
