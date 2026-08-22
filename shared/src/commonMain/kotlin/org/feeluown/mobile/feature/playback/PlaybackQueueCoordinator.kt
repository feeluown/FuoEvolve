package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val FEATURE_QUEUE_APPEND_FAILED = -1

/**
 * Runtime-facing transport contract owned by the playback feature.
 *
 * The same owner also exposes queue UI state/actions so migrated player UI no longer needs the
 * app-controller facade for queue operations.
 */
interface PlaybackTransportCoordinator : PlaybackQueueUiPort {
    fun startCurrent()
    fun previous()
    fun next()
}

/**
 * Owns queue/part transition policy while [PlaybackQueueController] remains the
 * durable queue state holder.
 */
internal class PlaybackQueueCoordinator(
    queue: PlaybackQueueController,
    private val scope: CoroutineScope,
    private val fallbackTrack: () -> MusicTrack?,
    private val playbackParts: () -> List<PlaybackPart>,
    private val currentPartIndex: () -> Int,
    private val startPlayback: (MusicTrack, Int, Int?) -> Unit,
    @Suppress("UNUSED_PARAMETER") private val stopPlayback: () -> Unit,
    private val persistQueue: () -> Unit,
    private val updateQueueState: () -> Unit,
    private val appendFeatureQueue: suspend (ProviderFeature) -> Int,
    private val setTrackChangeDirection: (TrackChangeDirection) -> Unit,
    private val setMessage: (String) -> Unit,
    feedbackState: MutableStateFlow<String?> = MutableStateFlow(null),
    private val shuffleTracks: (List<MusicTrack>) -> List<MusicTrack> = { it.shuffled() },
) : PlaybackTransportCoordinator {
    private val queueState = queue
    private val mutableFeedback = feedbackState

    override var trackChangeDirection by mutableStateOf(TrackChangeDirection.Next)
        private set
    override val feedback: StateFlow<String?> = mutableFeedback.asStateFlow()

    override val currentQueueTrack: MusicTrack?
        get() = queueState.currentTrack()
    override val queue: List<MusicTrack>
        get() = queueState.displayQueue()
    override val displayUpNextCount: Int
        get() = queueState.upNextQueue.size
    override val isShuffleEnabled: Boolean
        get() = queueState.shuffleEnabled
    override val repeatMode: RepeatMode
        get() = queueState.repeatMode
    override val isFmQueueActive: Boolean
        get() = queueState.isFmQueue

    override fun startCurrent() {
        (queueState.currentTrack() ?: fallbackTrack())?.let { track ->
            startTrack(track, 0, null, PlaybackStartReason.RESUME)
        }
    }

    override fun playTracks(tracks: List<MusicTrack>, index: Int) {
        replaceSourceQueue(
            tracks = tracks,
            index = index,
            sourceFeature = null,
            sourcePlaylistId = null,
            keepSelectedTrack = true,
        )
    }

    override fun playPlaylistTracks(tracks: List<MusicTrack>, index: Int, sourcePlaylistId: String) {
        replaceSourceQueue(
            tracks = tracks,
            index = index,
            sourceFeature = null,
            sourcePlaylistId = sourcePlaylistId,
            keepSelectedTrack = true,
        )
    }

    override fun playAllPlaylistTracks(tracks: List<MusicTrack>, sourcePlaylistId: String) {
        replaceSourceQueue(
            tracks = tracks,
            index = 0,
            sourceFeature = null,
            sourcePlaylistId = sourcePlaylistId,
            keepSelectedTrack = false,
        )
    }

    override fun appendPlaylistTracks(sourcePlaylistId: String, tracks: List<MusicTrack>) {
        if (tracks.isEmpty() || queueState.queuePlaylistId != sourcePlaylistId) return
        val seenIds = queueState.mainQueue.mapTo(mutableSetOf()) { it.id }
        val additions = tracks.filter { seenIds.add(it.id) }
        if (additions.isEmpty()) return
        queueState.mainQueue = queueState.mainQueue + additions
        if (queueState.originalMainQueue.isNotEmpty()) {
            val originalSeenIds = queueState.originalMainQueue.mapTo(mutableSetOf()) { it.id }
            queueState.originalMainQueue = queueState.originalMainQueue + additions.filter { originalSeenIds.add(it.id) }
        }
        reshufflePendingMainQueue()
        publishQueueMutation()
    }

    override fun playFeatureTracks(tracks: List<MusicTrack>, index: Int, sourceFeature: ProviderFeature) {
        replaceSourceQueue(
            tracks = tracks,
            index = index,
            sourceFeature = sourceFeature,
            sourcePlaylistId = null,
            keepSelectedTrack = true,
        )
    }

    override fun next() {
        updateTrackChangeDirection(TrackChangeDirection.Next)
        if (queueState.repeatMode == RepeatMode.SINGLE) {
            if (playPlaybackPartOffset(1, wrap = true)) return
            (queueState.currentTrack() ?: fallbackTrack())?.let { track ->
                startTrack(track, 0, null, PlaybackStartReason.AUTO_NEXT)
            }
            return
        }
        if (playPlaybackPartOffset(1)) return
        if (queueState.currentIsUpNext) {
            queueState.currentUpNextTrack = null
            queueState.currentIsUpNext = false
            persistQueue()
        }
        if (queueState.upNextQueue.isNotEmpty()) {
            playUpNextIndexInternal(0, TrackChangeDirection.Next)
            return
        }
        if (queueState.mainQueue.isEmpty()) return
        val feature = queueState.queueFeature
        if (feature != null && queueState.mainQueueIndex >= queueState.mainQueue.lastIndex) {
            scope.launch {
                val nextIndex = queueState.mainQueue.size
                val appendedCount = appendFeatureQueue(feature)
                when {
                    appendedCount > 0 && queueState.queueFeature == feature ->
                        playMainIndexInternal(nextIndex, 0, TrackChangeDirection.Next)
                    appendedCount == 0 && queueState.queueFeature == feature ->
                        publishMessage("${feature.title} 暂无后续歌曲")
                    // Negative values represent a load failure. The playback owner has already
                    // published the retryable provider/network error and it must not be replaced
                    // with an indistinguishable "no more songs" message here.
                    else -> Unit
                }
            }
            return
        }
        val nextIndex = queueState.mainQueueIndex + 1
        if (nextIndex < queueState.mainQueue.size) {
            playMainIndexInternal(nextIndex, 0, TrackChangeDirection.Next)
        } else if (queueState.repeatMode == RepeatMode.QUEUE) {
            playMainIndexInternal(0, 0, TrackChangeDirection.Next)
        }
    }

    override fun previous() {
        updateTrackChangeDirection(TrackChangeDirection.Previous)
        if (queueState.repeatMode == RepeatMode.SINGLE) {
            if (playPlaybackPartOffset(-1, wrap = true)) return
            (queueState.currentTrack() ?: fallbackTrack())?.let { track ->
                startTrack(track, 0, null, PlaybackStartReason.AUTO_NEXT)
            }
            return
        }
        if (playPlaybackPartOffset(-1)) return
        if (queueState.currentIsUpNext) {
            queueState.currentUpNextTrack = null
            queueState.currentIsUpNext = false
            persistQueue()
            playMainIndexInternal(
                queueState.mainQueueIndex.coerceAtLeast(0),
                0,
                TrackChangeDirection.Previous,
            )
            return
        }
        if (queueState.mainQueue.isEmpty()) return
        val previousIndex = queueState.mainQueueIndex - 1
        if (previousIndex >= 0) {
            playMainIndexInternal(previousIndex, 0, TrackChangeDirection.Previous)
        } else if (queueState.repeatMode == RepeatMode.QUEUE) {
            playMainIndexInternal(queueState.mainQueue.lastIndex, 0, TrackChangeDirection.Previous)
        }
    }

    override fun playQueueIndex(index: Int) {
        updateTrackChangeDirection(TrackChangeDirection.Next)
        val currentOffset = if (queueState.currentTrack() != null) 1 else 0
        if (index == 0 && currentOffset == 1) {
            queueState.currentTrack()?.let { track ->
                startTrack(track, 0, null, PlaybackStartReason.USER_SELECTION)
            }
            return
        }
        val pendingOffset = currentOffset
        val pendingEnd = pendingOffset + queueState.upNextQueue.size
        when {
            index in pendingOffset until pendingEnd -> {
                playUpNextIndexInternal(
                    index - pendingOffset,
                    TrackChangeDirection.Next,
                    PlaybackStartReason.USER_SELECTION,
                )
            }
            else -> {
                val mainStartIndex = when {
                    queueState.currentIsUpNext -> queueState.mainQueueIndex + 1
                    queueState.mainQueueIndex >= 0 -> queueState.mainQueueIndex + 1
                    else -> 0
                }
                val mainIndex = mainStartIndex + (index - pendingEnd)
                playMainIndexInternal(
                    mainIndex,
                    0,
                    TrackChangeDirection.Next,
                    PlaybackStartReason.USER_SELECTION,
                )
            }
        }
    }

    override fun playPlaybackPart(index: Int) {
        val parts = playbackParts()
        if (index !in parts.indices) return
        queueState.currentTrack()?.let { track ->
            startTrack(track, 0, index, PlaybackStartReason.USER_SELECTION)
        }
    }

    override fun removeFromQueue(track: MusicTrack) {
        if (queueState.currentUpNextTrack?.id == track.id) {
            queueState.currentUpNextTrack = null
            queueState.currentIsUpNext = false
            publishQueueMutation()
            return
        }
        val upNextIndex = queueState.upNextQueue.indexOfFirst { it.id == track.id }
        if (upNextIndex >= 0) {
            queueState.upNextQueue = queueState.upNextQueue.filterIndexed { index, _ -> index != upNextIndex }
            publishQueueMutation()
            return
        }
        val mainIndex = queueState.mainQueue.indexOfFirst { it.id == track.id }
        if (mainIndex < 0) return
        queueState.mainQueue = queueState.mainQueue.filterIndexed { index, _ -> index != mainIndex }
        queueState.originalMainQueue = queueState.originalMainQueue.filterNot { it.id == track.id }
        queueState.mainQueueIndex = when {
            queueState.mainQueue.isEmpty() -> -1
            mainIndex < queueState.mainQueueIndex -> queueState.mainQueueIndex - 1
            mainIndex == queueState.mainQueueIndex -> queueState.mainQueueIndex.coerceAtMost(queueState.mainQueue.lastIndex)
            else -> queueState.mainQueueIndex
        }
        publishQueueMutation()
    }

    override fun clearQueue() {
        val currentTrack = queueState.currentTrack()
        queueState.mainQueue = emptyList()
        queueState.originalMainQueue = emptyList()
        queueState.upNextQueue = emptyList()
        queueState.currentUpNextTrack = null
        queueState.currentIsUpNext = false
        queueState.mainQueueIndex = -1
        queueState.queueFeature = null
        queueState.queuePlaylistId = null
        queueState.isFmQueue = false
        queueState.shuffleBeforeFm = null
        if (currentTrack != null) {
            queueState.mainQueue = listOf(currentTrack)
            queueState.mainQueueIndex = 0
        }
        publishQueueMutation()
        publishMessage(if (currentTrack != null) "已清空播放队列" else "播放队列已清空")
    }

    override fun addToUpNext(track: MusicTrack) {
        queueState.upNextQueue = queueState.upNextQueue + track
        publishQueueMutation()
        publishMessage("已加入接下来播放：${track.title}")
    }

    override fun dismissFeedback(feedback: String) {
        if (mutableFeedback.value == feedback) mutableFeedback.value = null
    }

    override fun toggleShuffle() {
        if (queueState.isFmQueue) return
        if (queueState.shuffleEnabled) {
            disableShuffle()
        } else {
            enableShuffle()
        }
        publishQueueMutation()
    }

    override fun toggleRepeat() {
        if (queueState.isFmQueue) return
        queueState.repeatMode = when (queueState.repeatMode) {
            RepeatMode.OFF -> RepeatMode.QUEUE
            RepeatMode.QUEUE -> RepeatMode.SINGLE
            RepeatMode.SINGLE -> RepeatMode.OFF
        }
        publishQueueMutation()
    }

    /** Compatibility entry point used by legacy feature callers; explicit selection moves forward. */
    fun playMainIndex(index: Int, skippedUnavailableCount: Int = 0) {
        playMainIndexInternal(index, skippedUnavailableCount, TrackChangeDirection.Next)
    }

    /** Compatibility entry point used by unavailable/recovery flows; up-next is a forward move. */
    fun playUpNextIndex(index: Int) {
        playUpNextIndexInternal(index, TrackChangeDirection.Next)
    }

    fun playPlaybackPartOffset(offset: Int, wrap: Boolean = false): Boolean {
        val parts = playbackParts()
        val partIndex = currentPartIndex()
        if (parts.isEmpty() || partIndex < 0) return false
        val nextPartIndex = partIndex + offset
        val targetPartIndex = if (wrap) {
            nextPartIndex.floorMod(parts.size)
        } else {
            nextPartIndex.takeIf { it in parts.indices } ?: return false
        }
        queueState.currentTrack()?.let { track ->
            startTrack(track, 0, targetPartIndex, PlaybackStartReason.AUTO_NEXT)
        } ?: return false
        return true
    }

    private fun replaceSourceQueue(
        tracks: List<MusicTrack>,
        index: Int,
        sourceFeature: ProviderFeature?,
        sourcePlaylistId: String?,
        keepSelectedTrack: Boolean,
    ) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        updateTrackChangeDirection(TrackChangeDirection.Next)
        val enteringFm = sourceFeature?.isDynamicQueueFeature() == true
        val restoreShuffle = if (queueState.isFmQueue && !enteringFm) queueState.shuffleBeforeFm else null
        if (enteringFm && !queueState.isFmQueue) {
            queueState.shuffleBeforeFm = queueState.shuffleEnabled
            queueState.shuffleEnabled = false
        } else if (!enteringFm && restoreShuffle != null) {
            queueState.shuffleEnabled = restoreShuffle
            queueState.shuffleBeforeFm = null
        }
        queueState.isFmQueue = enteringFm
        queueState.queueFeature = sourceFeature?.takeIf { enteringFm }
        queueState.queuePlaylistId = sourcePlaylistId
        queueState.currentUpNextTrack = null
        queueState.currentIsUpNext = false
        queueState.originalMainQueue = emptyList()
        queueState.mainQueue = tracks
        queueState.mainQueueIndex = index
        if (queueState.shuffleEnabled && !enteringFm) {
            if (keepSelectedTrack) {
                enableShuffle()
            } else {
                queueState.originalMainQueue = queueState.mainQueue
                queueState.mainQueue = shuffledForPlaybackStart(queueState.mainQueue)
                queueState.mainQueueIndex = 0
            }
        }
        publishQueueMutation()
        playMainIndexInternal(
            queueState.mainQueueIndex,
            0,
            TrackChangeDirection.Next,
            if (keepSelectedTrack) PlaybackStartReason.USER_SELECTION else PlaybackStartReason.PLAYLIST_REPLACE,
        )
    }

    private fun playMainIndexInternal(
        index: Int,
        skippedUnavailableCount: Int,
        direction: TrackChangeDirection,
        reason: PlaybackStartReason = PlaybackStartReason.AUTO_NEXT,
    ) {
        val track = queueState.mainQueue.getOrNull(index) ?: return
        updateTrackChangeDirection(direction)
        queueState.currentUpNextTrack = null
        queueState.currentIsUpNext = false
        queueState.mainQueueIndex = index
        startTrack(track, skippedUnavailableCount, null, reason)
    }

    private fun playUpNextIndexInternal(
        index: Int,
        direction: TrackChangeDirection,
        reason: PlaybackStartReason = PlaybackStartReason.AUTO_NEXT,
    ) {
        val track = queueState.upNextQueue.getOrNull(index) ?: return
        updateTrackChangeDirection(direction)
        queueState.upNextQueue = queueState.upNextQueue.filterIndexed { itemIndex, _ -> itemIndex != index }
        queueState.currentUpNextTrack = track
        queueState.currentIsUpNext = true
        publishQueueMutation()
        startTrack(track, 0, null, reason)
    }

    private fun startTrack(
        track: MusicTrack,
        skippedUnavailableCount: Int,
        requestedPartIndex: Int?,
        reason: PlaybackStartReason,
    ) {
        queueState.markNextPlaybackStart(reason)
        startPlayback(track, skippedUnavailableCount, requestedPartIndex)
    }

    private fun updateTrackChangeDirection(direction: TrackChangeDirection) {
        trackChangeDirection = direction
        setTrackChangeDirection(direction)
    }

    private fun publishQueueMutation() {
        updateQueueState()
        persistQueue()
    }

    private fun publishMessage(message: String) {
        mutableFeedback.value = message
        setMessage(message)
    }

    private fun enableShuffle() {
        if (queueState.isFmQueue || queueState.mainQueue.size <= 1) {
            queueState.shuffleEnabled = !queueState.isFmQueue
            return
        }
        val current = queueState.currentTrack()
        queueState.originalMainQueue = if (queueState.originalMainQueue.isEmpty()) {
            queueState.mainQueue
        } else {
            queueState.originalMainQueue
        }
        val currentInMain = current?.let { track -> queueState.mainQueue.firstOrNull { it.id == track.id } }
        val shuffledRest = shuffleTracks(queueState.mainQueue.filterNot { it.id == currentInMain?.id })
        queueState.mainQueue = listOfNotNull(currentInMain) + shuffledRest
        queueState.mainQueueIndex = currentInMain?.let { 0 }
            ?: queueState.mainQueueIndex.coerceIn(0, queueState.mainQueue.lastIndex)
        queueState.shuffleEnabled = true
    }

    private fun shuffledForPlaybackStart(tracks: List<MusicTrack>): List<MusicTrack> {
        if (tracks.size <= 1) return tracks
        val shuffled = shuffleTracks(tracks)
        return if (shuffled.first().id == tracks.first().id) {
            shuffled.drop(1) + shuffled.first()
        } else {
            shuffled
        }
    }

    private fun reshufflePendingMainQueue() {
        if (!queueState.shuffleEnabled || queueState.isFmQueue || queueState.mainQueue.size <= 1) return
        val firstPendingIndex = (queueState.mainQueueIndex + 1).coerceIn(0, queueState.mainQueue.size)
        val pending = queueState.mainQueue.drop(firstPendingIndex)
        if (pending.size <= 1) return
        queueState.mainQueue = queueState.mainQueue.take(firstPendingIndex) + shuffleTracks(pending)
    }

    private fun disableShuffle() {
        val current = queueState.currentTrack()
        if (queueState.originalMainQueue.isNotEmpty()) {
            queueState.mainQueue = queueState.originalMainQueue
            queueState.mainQueueIndex = current?.let { track ->
                queueState.mainQueue.indexOfFirst { it.id == track.id }
            }?.takeIf { it >= 0 }
                ?: queueState.mainQueueIndex.coerceIn(-1, queueState.mainQueue.lastIndex)
        }
        queueState.originalMainQueue = emptyList()
        queueState.shuffleEnabled = false
    }

    private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor
}
