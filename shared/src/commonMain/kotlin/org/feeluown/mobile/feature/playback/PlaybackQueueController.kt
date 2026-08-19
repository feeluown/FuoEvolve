package org.feeluown.mobile

/**
 * Owns the durable playback-queue state independently from the app controller.
 *
 * Queue transition policy is coordinated by [PlaybackQueueCoordinator]; this
 * type remains the single source of truth for queue state and persistence.
 */
internal class PlaybackQueueController {
    var mainQueue: List<MusicTrack> = emptyList()
    var originalMainQueue: List<MusicTrack> = emptyList()
    var upNextQueue: List<MusicTrack> = emptyList()
    var mainQueueIndex: Int = -1
    var currentUpNextTrack: MusicTrack? = null
    var currentIsUpNext: Boolean = false
    var queueFeature: ProviderFeature? = null
    var queuePlaylistId: String? = null
    var shuffleEnabled: Boolean = false
    var repeatMode: RepeatMode = RepeatMode.QUEUE
    var isFmQueue: Boolean = false
    var shuffleBeforeFm: Boolean? = null

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

    fun updateCurrentTrack(track: MusicTrack) {
        if (currentIsUpNext) {
            currentUpNextTrack = track
        } else if (mainQueueIndex in mainQueue.indices) {
            mainQueue = mainQueue.mapIndexed { index, item -> if (index == mainQueueIndex) track else item }
            originalMainQueue = originalMainQueue.map { item -> if (item.id == track.id) track else item }
        }
    }

    fun restore(snapshot: PlaybackQueueSnapshot) {
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
}
