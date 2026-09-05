package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlaybackQueueState(
    val mainQueue: List<MusicTrack> = emptyList(),
    val originalMainQueue: List<MusicTrack> = emptyList(),
    val upNextQueue: List<MusicTrack> = emptyList(),
    val mainQueueIndex: Int = -1,
    val currentUpNextTrack: MusicTrack? = null,
    val currentIsUpNext: Boolean = false,
    val queueFeature: ProviderFeature? = null,
    val queuePlaylistId: String? = null,
    val listeningContext: PlaybackContextSnapshot? = null,
    val listeningContextSequence: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.QUEUE,
    val isFmQueue: Boolean = false,
    val shuffleBeforeFm: Boolean? = null,
    val activePlaybackTransaction: PlaybackTransaction? = null,
    val playbackTransactionSequence: Long = 0L,
    val lastPlaybackStartReason: PlaybackStartReason? = null,
    val playbackStartSequence: Long = 0L,
)

/** Stable occurrence identity for one item in the live playback queue. */
internal data class PlaybackQueueEntry(
    val id: Long,
    val track: MusicTrack,
)

/**
 * Owns the durable playback-queue state independently from the app controller.
 *
 * Queue transition policy is coordinated by [PlaybackQueueCoordinator]; this
 * type remains the single source of truth for queue state and persistence.
 *
 * Queue-entry ids intentionally live beside the queue rather than inside [MusicTrack]. A logical
 * track may appear multiple times in one queue, so track identity cannot identify an occurrence.
 */
internal class PlaybackQueueController {
    private var applyingStartupSnapshot = false
    private var startupDirty = false
    private val mutableState = MutableStateFlow(PlaybackQueueState())
    val state: StateFlow<PlaybackQueueState> = mutableState.asStateFlow()

    private var queueEntrySequence = 0L
    private var mainQueueEntryIds: List<Long> = emptyList()
    private var originalMainQueueEntryIds: List<Long> = emptyList()
    private var upNextQueueEntryIds: List<Long> = emptyList()
    private var currentUpNextEntryId: Long? = null

    var mainQueue: List<MusicTrack>
        get() = mutableState.value.mainQueue
        set(value) {
            val logicalTracks = value.logicalTracks()
            mainQueueEntryIds = reconcileEntryIds(
                oldTracks = mutableState.value.mainQueue,
                oldEntryIds = mainQueueEntryIds,
                newTracks = logicalTracks,
            )
            update { it.copy(mainQueue = logicalTracks) }
        }
    var originalMainQueue: List<MusicTrack>
        get() = mutableState.value.originalMainQueue
        set(value) {
            val logicalTracks = value.logicalTracks()
            originalMainQueueEntryIds = reconcileOriginalEntryIds(logicalTracks)
            update { it.copy(originalMainQueue = logicalTracks) }
        }
    var upNextQueue: List<MusicTrack>
        get() = mutableState.value.upNextQueue
        set(value) {
            val logicalTracks = value.logicalTracks()
            upNextQueueEntryIds = reconcileEntryIds(
                oldTracks = mutableState.value.upNextQueue,
                oldEntryIds = upNextQueueEntryIds,
                newTracks = logicalTracks,
            )
            update { it.copy(upNextQueue = logicalTracks) }
        }
    var mainQueueIndex: Int
        get() = mutableState.value.mainQueueIndex
        set(value) = update { it.copy(mainQueueIndex = value) }
    var currentUpNextTrack: MusicTrack?
        get() = mutableState.value.currentUpNextTrack
        set(value) {
            val logicalTrack = value?.logicalPlaybackTrack()
            val currentTrack = mutableState.value.currentUpNextTrack
            currentUpNextEntryId = when {
                logicalTrack == null -> null
                currentUpNextEntryId != null && currentTrack?.id == logicalTrack.id -> currentUpNextEntryId
                else -> nextQueueEntryId()
            }
            update { it.copy(currentUpNextTrack = logicalTrack) }
        }
    var currentIsUpNext: Boolean
        get() = mutableState.value.currentIsUpNext
        set(value) {
            if (!value) currentUpNextEntryId = null
            update { it.copy(currentIsUpNext = value) }
        }
    var queueFeature: ProviderFeature?
        get() = mutableState.value.queueFeature
        set(value) = update { it.copy(queueFeature = value) }
    var queuePlaylistId: String?
        get() = mutableState.value.queuePlaylistId
        set(value) = update { it.copy(queuePlaylistId = value) }
    var shuffleEnabled: Boolean
        get() = mutableState.value.shuffleEnabled
        set(value) = update { it.copy(shuffleEnabled = value) }
    var repeatMode: RepeatMode
        get() = mutableState.value.repeatMode
        set(value) = update { it.copy(repeatMode = value) }
    var isFmQueue: Boolean
        get() = mutableState.value.isFmQueue
        set(value) = update { it.copy(isFmQueue = value) }
    var shuffleBeforeFm: Boolean?
        get() = mutableState.value.shuffleBeforeFm
        set(value) = update { it.copy(shuffleBeforeFm = value) }

    fun currentTrack(): MusicTrack? = mutableState.value.currentTrack()

    fun currentQueueEntryId(): Long? {
        ensureQueueEntryIdsAligned()
        val current = mutableState.value
        return if (current.currentIsUpNext) {
            current.currentUpNextTrack?.let {
                currentUpNextEntryId ?: nextQueueEntryId().also { id -> currentUpNextEntryId = id }
            }
        } else {
            mainQueueEntryIds.getOrNull(current.mainQueueIndex)
        }
    }

    fun mainQueueEntries(): List<PlaybackQueueEntry> {
        ensureQueueEntryIdsAligned()
        return mutableState.value.mainQueue.mapIndexed { index, track ->
            PlaybackQueueEntry(requireNotNull(mainQueueEntryIds.getOrNull(index)), track)
        }
    }

    fun originalMainQueueEntries(): List<PlaybackQueueEntry> {
        ensureQueueEntryIdsAligned()
        return mutableState.value.originalMainQueue.mapIndexed { index, track ->
            PlaybackQueueEntry(requireNotNull(originalMainQueueEntryIds.getOrNull(index)), track)
        }
    }

    fun replaceMainQueueEntries(entries: List<PlaybackQueueEntry>) {
        requireValidEntries(entries)
        mainQueueEntryIds = entries.map(PlaybackQueueEntry::id)
        queueEntrySequence = maxOf(queueEntrySequence, mainQueueEntryIds.maxOrNull() ?: 0L)
        update { it.copy(mainQueue = entries.map { entry -> entry.track.logicalPlaybackTrack() }) }
    }

    fun replaceOriginalMainQueueEntries(entries: List<PlaybackQueueEntry>) {
        requireValidEntries(entries)
        originalMainQueueEntryIds = entries.map(PlaybackQueueEntry::id)
        queueEntrySequence = maxOf(queueEntrySequence, originalMainQueueEntryIds.maxOrNull() ?: 0L)
        update { it.copy(originalMainQueue = entries.map { entry -> entry.track.logicalPlaybackTrack() }) }
    }

    fun displayQueue(): List<MusicTrack> = mutableState.value.displayQueue()

    fun displayQueueEntries(): List<PlaybackQueueEntry> {
        ensureQueueEntryIdsAligned()
        val current = mutableState.value
        return buildList {
            currentEntry(current)?.let(::add)
            current.upNextQueue.forEachIndexed { index, track ->
                upNextQueueEntryIds.getOrNull(index)?.let { id ->
                    add(PlaybackQueueEntry(id = id, track = track))
                }
            }
            val nextMainIndex = when {
                current.currentIsUpNext -> current.mainQueueIndex + 1
                current.mainQueueIndex >= 0 -> current.mainQueueIndex + 1
                else -> 0
            }
            if (nextMainIndex in 0..current.mainQueue.size) {
                for (index in nextMainIndex until current.mainQueue.size) {
                    val id = mainQueueEntryIds.getOrNull(index) ?: continue
                    add(PlaybackQueueEntry(id = id, track = current.mainQueue[index]))
                }
            }
        }
    }

    fun displayQueueIndex(): Int = if (currentTrack() != null) 0 else -1

    /** Activates a pending up-next occurrence without losing its queue-entry identity. */
    fun activateUpNext(index: Int): MusicTrack? {
        ensureQueueEntryIdsAligned()
        val current = mutableState.value
        val track = current.upNextQueue.getOrNull(index) ?: return null
        val entryId = upNextQueueEntryIds.getOrNull(index) ?: return null
        val remainingTracks = current.upNextQueue.filterIndexed { itemIndex, _ -> itemIndex != index }
        upNextQueueEntryIds = upNextQueueEntryIds.filterIndexed { itemIndex, _ -> itemIndex != index }
        currentUpNextEntryId = entryId
        update {
            it.copy(
                upNextQueue = remainingTracks,
                currentUpNextTrack = track,
                currentIsUpNext = true,
            )
        }
        return track
    }

    /**
     * Reconciles the actual platform media item back to the exact queue occurrence.
     *
     * @return `null` when [entryId] is unknown and legacy track-id reconciliation should be used;
     * `false` when the entry was already current; `true` when queue position/content changed.
     */
    fun synchronizePlaybackEntry(entryId: Long, track: MusicTrack): Boolean? {
        ensureQueueEntryIdsAligned()
        val logicalTrack = track.logicalPlaybackTrack()
        val current = mutableState.value

        if (currentQueueEntryId() == entryId) {
            val changed = currentTrack() != logicalTrack
            if (changed) updateCurrentTrack(logicalTrack)
            return changed
        }

        val upNextIndex = upNextQueueEntryIds.indexOf(entryId)
        if (upNextIndex >= 0) {
            val queuedTrack = current.upNextQueue.getOrNull(upNextIndex) ?: return null
            if (queuedTrack.id != logicalTrack.id) return null
            val remainingTracks = current.upNextQueue.filterIndexed { index, _ -> index != upNextIndex }
            upNextQueueEntryIds = upNextQueueEntryIds.filterIndexed { index, _ -> index != upNextIndex }
            currentUpNextEntryId = entryId
            update {
                it.copy(
                    upNextQueue = remainingTracks,
                    currentUpNextTrack = logicalTrack,
                    currentIsUpNext = true,
                )
            }
            return true
        }

        val mainIndex = mainQueueEntryIds.indexOf(entryId)
        if (mainIndex >= 0) {
            val queuedTrack = current.mainQueue.getOrNull(mainIndex) ?: return null
            if (queuedTrack.id != logicalTrack.id) return null
            val originalIndex = originalMainQueueEntryIds.indexOf(entryId)
            currentUpNextEntryId = null
            update { snapshot ->
                snapshot.copy(
                    mainQueue = snapshot.mainQueue.mapIndexed { index, item ->
                        if (index == mainIndex) logicalTrack else item
                    },
                    originalMainQueue = snapshot.originalMainQueue.mapIndexed { index, item ->
                        if (index == originalIndex) logicalTrack else item
                    },
                    mainQueueIndex = mainIndex,
                    currentUpNextTrack = null,
                    currentIsUpNext = false,
                )
            }
            return true
        }

        return null
    }

    fun activePlaybackTransaction(): PlaybackTransaction? = mutableState.value.activePlaybackTransaction

    fun beginPlaybackTransaction(
        trackId: String,
        reason: PlaybackStartReason,
        recordPlaybackStart: Boolean = true,
    ): PlaybackTransaction {
        val current = mutableState.value
        val transaction = PlaybackTransaction(
            id = current.playbackTransactionSequence + 1L,
            reason = reason,
            targetTrackId = trackId,
        )
        updateEphemeral { state ->
            state.copy(
                activePlaybackTransaction = transaction,
                playbackTransactionSequence = transaction.id,
                lastPlaybackStartReason = if (recordPlaybackStart) reason else state.lastPlaybackStartReason,
                playbackStartSequence = if (recordPlaybackStart) {
                    state.playbackStartSequence + 1L
                } else {
                    state.playbackStartSequence
                },
            )
        }
        return transaction
    }

    /** Starts a new logical playback-context session without making it part of durable queue state. */
    fun beginListeningContext(context: PlaybackContextSnapshot?) {
        updateEphemeral { current ->
            current.copy(
                listeningContext = context,
                listeningContextSequence = current.listeningContextSequence + 1L,
            )
        }
    }

    fun updateCurrentTrack(track: MusicTrack) {
        ensureQueueEntryIdsAligned()
        val logicalTrack = track.logicalPlaybackTrack()
        val current = mutableState.value
        when {
            current.currentIsUpNext -> update { it.copy(currentUpNextTrack = logicalTrack) }
            current.mainQueueIndex in current.mainQueue.indices -> {
                val entryId = mainQueueEntryIds.getOrNull(current.mainQueueIndex)
                val originalIndex = entryId?.let(originalMainQueueEntryIds::indexOf) ?: -1
                update { snapshot ->
                    snapshot.copy(
                        mainQueue = snapshot.mainQueue.mapIndexed { index, item ->
                            if (index == snapshot.mainQueueIndex) logicalTrack else item
                        },
                        originalMainQueue = snapshot.originalMainQueue.mapIndexed { index, item ->
                            if (index == originalIndex) logicalTrack else item
                        },
                    )
                }
            }
        }
    }

    /**
     * Applies the persisted startup queue only if no live durable queue mutation happened first.
     *
     * @return true when the snapshot was applied, false when a newer live queue mutation won the race.
     */
    fun restore(snapshot: PlaybackQueueSnapshot): Boolean {
        if (startupDirty) return false
        val current = mutableState.value
        val mainQueue = snapshot.mainQueue.logicalTracks()
        val originalMainQueue = snapshot.originalMainQueue.logicalTracks()
        val upNextQueue = snapshot.upNextQueue.logicalTracks()

        val mainIdsValid = validIds(snapshot.mainQueueEntryIds, mainQueue.size)
        val upNextIdsValid = validIds(snapshot.upNextQueueEntryIds, upNextQueue.size)
        val originalIdsValid = when {
            originalMainQueue.isEmpty() -> snapshot.originalMainQueueEntryIds.isEmpty()
            !validIds(snapshot.originalMainQueueEntryIds, originalMainQueue.size) -> false
            !mainIdsValid -> false
            else -> snapshot.originalMainQueueEntryIds.toSet() == snapshot.mainQueueEntryIds.toSet()
        }
        val idsDoNotCrossQueues = mainIdsValid && upNextIdsValid &&
            snapshot.mainQueueEntryIds.none(snapshot.upNextQueueEntryIds.toSet()::contains)
        val canRestoreEntryIds = mainIdsValid && upNextIdsValid && originalIdsValid && idsDoNotCrossQueues

        val persistedIds = snapshot.mainQueueEntryIds + snapshot.originalMainQueueEntryIds + snapshot.upNextQueueEntryIds
        queueEntrySequence = maxOf(snapshot.queueEntrySequence, persistedIds.maxOrNull() ?: 0L)
        if (canRestoreEntryIds) {
            mainQueueEntryIds = snapshot.mainQueueEntryIds
            originalMainQueueEntryIds = snapshot.originalMainQueueEntryIds
            upNextQueueEntryIds = snapshot.upNextQueueEntryIds
        } else {
            mainQueueEntryIds = mainQueue.map { nextQueueEntryId() }
            upNextQueueEntryIds = upNextQueue.map { nextQueueEntryId() }
            originalMainQueueEntryIds = entryIdsForTracks(
                sourceTracks = mainQueue,
                sourceEntryIds = mainQueueEntryIds,
                targetTracks = originalMainQueue,
            ) ?: originalMainQueue.map { nextQueueEntryId() }
        }
        currentUpNextEntryId = null
        applyingStartupSnapshot = true
        try {
            mutableState.value = PlaybackQueueState(
                mainQueue = mainQueue,
                originalMainQueue = originalMainQueue,
                upNextQueue = upNextQueue,
                mainQueueIndex = snapshot.queueIndex.coerceIn(-1, mainQueue.lastIndex),
                shuffleEnabled = snapshot.shuffleEnabled,
                repeatMode = snapshot.repeatMode,
                isFmQueue = snapshot.isFmQueue,
                shuffleBeforeFm = snapshot.shuffleBeforeFm,
                activePlaybackTransaction = current.activePlaybackTransaction,
                playbackTransactionSequence = current.playbackTransactionSequence,
                lastPlaybackStartReason = current.lastPlaybackStartReason,
                playbackStartSequence = current.playbackStartSequence,
            )
        } finally {
            applyingStartupSnapshot = false
        }
        return true
    }

    fun snapshot(): PlaybackQueueSnapshot {
        ensureQueueEntryIdsAligned()
        val current = mutableState.value
        return PlaybackQueueSnapshot(
            mainQueue = current.mainQueue,
            originalMainQueue = current.originalMainQueue,
            upNextQueue = current.upNextQueue,
            queueIndex = current.mainQueueIndex,
            shuffleEnabled = current.shuffleEnabled,
            repeatMode = current.repeatMode,
            isFmQueue = current.isFmQueue,
            shuffleBeforeFm = current.shuffleBeforeFm,
            mainQueueEntryIds = mainQueueEntryIds,
            originalMainQueueEntryIds = originalMainQueueEntryIds,
            upNextQueueEntryIds = upNextQueueEntryIds,
            queueEntrySequence = queueEntrySequence,
        )
    }

    private fun currentEntry(current: PlaybackQueueState): PlaybackQueueEntry? {
        val track = current.currentTrack() ?: return null
        val entryId = if (current.currentIsUpNext) {
            currentUpNextEntryId ?: nextQueueEntryId().also { currentUpNextEntryId = it }
        } else {
            mainQueueEntryIds.getOrNull(current.mainQueueIndex)
        } ?: return null
        return PlaybackQueueEntry(id = entryId, track = track)
    }

    private fun ensureQueueEntryIdsAligned() {
        val current = mutableState.value
        if (mainQueueEntryIds.size != current.mainQueue.size) {
            mainQueueEntryIds = reconcileEntryIds(current.mainQueue, mainQueueEntryIds, current.mainQueue)
        }
        if (originalMainQueueEntryIds.size != current.originalMainQueue.size) {
            originalMainQueueEntryIds = reconcileOriginalEntryIds(current.originalMainQueue)
        }
        if (upNextQueueEntryIds.size != current.upNextQueue.size) {
            upNextQueueEntryIds = reconcileEntryIds(current.upNextQueue, upNextQueueEntryIds, current.upNextQueue)
        }
    }

    private fun reconcileOriginalEntryIds(newTracks: List<MusicTrack>): List<Long> {
        val oldTracks = mutableState.value.originalMainQueue
        val oldIdsByTrack = mutableMapOf<String, ArrayDeque<Long>>()
        oldTracks.forEachIndexed { index, track ->
            originalMainQueueEntryIds.getOrNull(index)?.let { id ->
                oldIdsByTrack.getOrPut(track.id) { ArrayDeque() }.addLast(id)
            }
        }
        val usedIds = mutableSetOf<Long>()
        val mainIdsByTrack = mutableMapOf<String, ArrayDeque<Long>>()
        mutableState.value.mainQueue.forEachIndexed { index, track ->
            mainQueueEntryIds.getOrNull(index)?.let { id ->
                mainIdsByTrack.getOrPut(track.id) { ArrayDeque() }.addLast(id)
            }
        }
        return newTracks.map { track ->
            val reused = oldIdsByTrack[track.id]?.removeFirstOrNull()
            if (reused != null) {
                usedIds += reused
                reused
            } else {
                var shared = mainIdsByTrack[track.id]?.removeFirstOrNull()
                while (shared != null && shared in usedIds) {
                    shared = mainIdsByTrack[track.id]?.removeFirstOrNull()
                }
                (shared ?: nextQueueEntryId()).also(usedIds::add)
            }
        }
    }

    private fun reconcileEntryIds(
        oldTracks: List<MusicTrack>,
        oldEntryIds: List<Long>,
        newTracks: List<MusicTrack>,
    ): List<Long> {
        val reusableByTrackId = mutableMapOf<String, ArrayDeque<Long>>()
        oldTracks.forEachIndexed { index, track ->
            val entryId = oldEntryIds.getOrNull(index) ?: nextQueueEntryId()
            reusableByTrackId.getOrPut(track.id) { ArrayDeque() }.addLast(entryId)
        }
        return newTracks.map { track ->
            reusableByTrackId[track.id]?.removeFirstOrNull() ?: nextQueueEntryId()
        }
    }

    private fun entryIdsForTracks(
        sourceTracks: List<MusicTrack>,
        sourceEntryIds: List<Long>,
        targetTracks: List<MusicTrack>,
    ): List<Long>? {
        if (sourceTracks.size != sourceEntryIds.size) return null
        val available = mutableMapOf<String, ArrayDeque<Long>>()
        sourceTracks.forEachIndexed { index, track ->
            available.getOrPut(track.id) { ArrayDeque() }.addLast(sourceEntryIds[index])
        }
        val result = mutableListOf<Long>()
        targetTracks.forEach { track ->
            result += available[track.id]?.removeFirstOrNull() ?: return null
        }
        return result
    }

    private fun validIds(ids: List<Long>, expectedSize: Int): Boolean =
        ids.size == expectedSize && ids.all { it > 0L } && ids.distinct().size == ids.size

    private fun requireValidEntries(entries: List<PlaybackQueueEntry>) {
        require(entries.all { it.id > 0L }) { "Queue entry ids must be positive" }
        require(entries.map(PlaybackQueueEntry::id).distinct().size == entries.size) {
            "Queue entry ids must be unique within a queue view"
        }
    }

    private fun nextQueueEntryId(): Long {
        queueEntrySequence += 1L
        return queueEntrySequence
    }

    private inline fun update(
        crossinline transform: (PlaybackQueueState) -> PlaybackQueueState,
    ) {
        if (!applyingStartupSnapshot) startupDirty = true
        mutableState.update { current -> transform(current) }
    }

    /** Playback transaction/context metadata is observable but not part of durable queue mutation history. */
    private inline fun updateEphemeral(
        crossinline transform: (PlaybackQueueState) -> PlaybackQueueState,
    ) {
        mutableState.update { current -> transform(current) }
    }
}

private fun List<MusicTrack>.logicalTracks(): List<MusicTrack> = map(MusicTrack::logicalPlaybackTrack)

/** Read-only projection used by app/player presentation outside the playback module. */
fun PlaybackQueueState.currentTrack(): MusicTrack? =
    if (currentIsUpNext) currentUpNextTrack else mainQueue.getOrNull(mainQueueIndex)

/** Read-only display ordering used by app/player presentation outside the playback module. */
fun PlaybackQueueState.displayQueue(): List<MusicTrack> = buildList {
    currentTrack()?.let(::add)
    addAll(upNextQueue)
    val nextMainIndex = when {
        currentIsUpNext -> mainQueueIndex + 1
        mainQueueIndex >= 0 -> mainQueueIndex + 1
        else -> 0
    }
    if (nextMainIndex in 0..mainQueue.size) {
        addAll(mainQueue.drop(nextMainIndex))
    }
}
