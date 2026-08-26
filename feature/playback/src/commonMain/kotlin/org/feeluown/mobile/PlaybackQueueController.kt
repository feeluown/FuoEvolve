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
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.QUEUE,
    val isFmQueue: Boolean = false,
    val shuffleBeforeFm: Boolean? = null,
    val lastPlaybackStartReason: PlaybackStartReason? = null,
    val playbackStartSequence: Long = 0L,
)

/**
 * Owns the durable playback-queue state independently from the app controller.
 *
 * Queue transition policy is coordinated by [PlaybackQueueCoordinator]; this
 * type remains the single source of truth for queue state and persistence.
 */
internal class PlaybackQueueController {
    private var applyingStartupSnapshot = false
    private var startupDirty = false
    private val mutableState = MutableStateFlow(PlaybackQueueState())
    val state: StateFlow<PlaybackQueueState> = mutableState.asStateFlow()

    var mainQueue: List<MusicTrack>
        get() = mutableState.value.mainQueue
        set(value) = update { it.copy(mainQueue = value) }
    var originalMainQueue: List<MusicTrack>
        get() = mutableState.value.originalMainQueue
        set(value) = update { it.copy(originalMainQueue = value) }
    var upNextQueue: List<MusicTrack>
        get() = mutableState.value.upNextQueue
        set(value) = update { it.copy(upNextQueue = value) }
    var mainQueueIndex: Int
        get() = mutableState.value.mainQueueIndex
        set(value) = update { it.copy(mainQueueIndex = value) }
    var currentUpNextTrack: MusicTrack?
        get() = mutableState.value.currentUpNextTrack
        set(value) = update { it.copy(currentUpNextTrack = value) }
    var currentIsUpNext: Boolean
        get() = mutableState.value.currentIsUpNext
        set(value) = update { it.copy(currentIsUpNext = value) }
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

    private var pendingPlaybackStartReason: PlaybackStartReason? = null

    fun currentTrack(): MusicTrack? = mutableState.value.currentTrack()

    fun displayQueue(): List<MusicTrack> = mutableState.value.displayQueue()

    fun displayQueueIndex(): Int = if (currentTrack() != null) 0 else -1

    fun markNextPlaybackStart(reason: PlaybackStartReason) {
        pendingPlaybackStartReason = reason
        updateEphemeral { current ->
            current.copy(
                lastPlaybackStartReason = reason,
                playbackStartSequence = current.playbackStartSequence + 1L,
            )
        }
    }

    fun consumePlaybackStartReason(): PlaybackStartReason =
        pendingPlaybackStartReason?.also { pendingPlaybackStartReason = null }
            ?: PlaybackStartReason.AUTO_NEXT

    fun updateCurrentTrack(track: MusicTrack) {
        val current = mutableState.value
        when {
            current.currentIsUpNext -> update { it.copy(currentUpNextTrack = track) }
            current.mainQueueIndex in current.mainQueue.indices -> update { snapshot ->
                snapshot.copy(
                    mainQueue = snapshot.mainQueue.mapIndexed { index, item ->
                        if (index == snapshot.mainQueueIndex) track else item
                    },
                    originalMainQueue = snapshot.originalMainQueue.map { item ->
                        if (item.id == track.id) track else item
                    },
                )
            }
        }
    }

    /**
     * Applies the persisted startup queue only if no live queue mutation happened first.
     *
     * Mutation history is tracked monotonically rather than inferred from current values so user
     * actions that return the queue to its defaults (for example clearing an already-empty queue or
     * toggling shuffle twice) still invalidate a delayed startup snapshot.
     *
     * @return true when the snapshot was applied, false when a newer live mutation won the race.
     */
    fun restore(snapshot: PlaybackQueueSnapshot): Boolean {
        if (startupDirty) return false
        applyingStartupSnapshot = true
        try {
            mutableState.value = PlaybackQueueState(
                mainQueue = snapshot.mainQueue,
                originalMainQueue = snapshot.originalMainQueue,
                upNextQueue = snapshot.upNextQueue,
                mainQueueIndex = snapshot.queueIndex.coerceIn(-1, snapshot.mainQueue.lastIndex),
                shuffleEnabled = snapshot.shuffleEnabled,
                repeatMode = snapshot.repeatMode,
                isFmQueue = snapshot.isFmQueue,
                shuffleBeforeFm = snapshot.shuffleBeforeFm,
            )
            pendingPlaybackStartReason = null
        } finally {
            applyingStartupSnapshot = false
        }
        return true
    }

    fun snapshot(): PlaybackQueueSnapshot {
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
        )
    }

    private inline fun update(
        crossinline transform: (PlaybackQueueState) -> PlaybackQueueState,
    ) {
        if (!applyingStartupSnapshot) startupDirty = true
        mutableState.update { current -> transform(current) }
    }

    /** Playback transaction metadata is observable but not part of durable queue mutation history. */
    private inline fun updateEphemeral(
        crossinline transform: (PlaybackQueueState) -> PlaybackQueueState,
    ) {
        mutableState.update { current -> transform(current) }
    }
}

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
