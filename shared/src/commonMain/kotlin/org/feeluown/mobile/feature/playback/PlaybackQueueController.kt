package org.feeluown.mobile

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Owns the durable playback-queue state independently from the app controller.
 *
 * Queue transition policy is coordinated by [PlaybackQueueCoordinator]; this
 * type remains the single source of truth for queue state and persistence.
 */
internal class PlaybackQueueController {
    private var applyingStartupSnapshot = false
    private var startupDirty = false

    var mainQueue by trackedStateOf<List<MusicTrack>>(emptyList())
    var originalMainQueue by trackedStateOf<List<MusicTrack>>(emptyList())
    var upNextQueue by trackedStateOf<List<MusicTrack>>(emptyList())
    var mainQueueIndex by trackedStateOf(-1)
    var currentUpNextTrack by trackedStateOf<MusicTrack?>(null)
    var currentIsUpNext by trackedStateOf(false)
    var queueFeature by trackedStateOf<ProviderFeature?>(null)
    var queuePlaylistId by trackedStateOf<String?>(null)
    var shuffleEnabled by trackedStateOf(false)
    var repeatMode by trackedStateOf(RepeatMode.QUEUE)
    var isFmQueue by trackedStateOf(false)
    var shuffleBeforeFm by trackedStateOf<Boolean?>(null)
    private var pendingPlaybackStartReason: PlaybackStartReason? = null

    fun currentTrack(): MusicTrack? =
        if (currentIsUpNext) currentUpNextTrack else mainQueue.getOrNull(mainQueueIndex)

    fun displayQueue(): List<MusicTrack> = buildList {
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

    fun displayQueueIndex(): Int = if (currentTrack() != null) 0 else -1

    fun markNextPlaybackStart(reason: PlaybackStartReason) {
        pendingPlaybackStartReason = reason
    }

    fun consumePlaybackStartReason(): PlaybackStartReason =
        pendingPlaybackStartReason?.also { pendingPlaybackStartReason = null }
            ?: PlaybackStartReason.AUTO_NEXT

    fun updateCurrentTrack(track: MusicTrack) {
        if (currentIsUpNext) {
            currentUpNextTrack = track
        } else if (mainQueueIndex in mainQueue.indices) {
            mainQueue = mainQueue.mapIndexed { index, item -> if (index == mainQueueIndex) track else item }
            originalMainQueue = originalMainQueue.map { item -> if (item.id == track.id) track else item }
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
            mainQueue = snapshot.mainQueue
            originalMainQueue = snapshot.originalMainQueue
            upNextQueue = snapshot.upNextQueue
            mainQueueIndex = snapshot.queueIndex.coerceIn(-1, mainQueue.lastIndex)
            shuffleEnabled = snapshot.shuffleEnabled
            repeatMode = snapshot.repeatMode
            isFmQueue = snapshot.isFmQueue
            shuffleBeforeFm = snapshot.shuffleBeforeFm
            currentUpNextTrack = null
            currentIsUpNext = false
            pendingPlaybackStartReason = null
        } finally {
            applyingStartupSnapshot = false
        }
        return true
    }

    fun snapshot(): PlaybackQueueSnapshot = PlaybackQueueSnapshot(
        mainQueue = mainQueue,
        originalMainQueue = originalMainQueue,
        upNextQueue = upNextQueue,
        queueIndex = mainQueueIndex,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
        isFmQueue = isFmQueue,
        shuffleBeforeFm = shuffleBeforeFm,
    )

    private fun <T> trackedStateOf(initialValue: T): ReadWriteProperty<Any?, T> {
        val state: MutableState<T> = mutableStateOf(initialValue)
        return object : ReadWriteProperty<Any?, T> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                if (!applyingStartupSnapshot) startupDirty = true
                state.value = value
            }
        }
    }
}
