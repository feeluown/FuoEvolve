package org.feeluown.mobile

/**
 * Owns the durable playback-queue state independently from the app controller.
 *
 * Queue mutation policy still lives in the playback coordinator/facade while it is
 * migrated, but the queue's single source of truth and persistence snapshot no
 * longer belong to [FuoPlayerController].
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
