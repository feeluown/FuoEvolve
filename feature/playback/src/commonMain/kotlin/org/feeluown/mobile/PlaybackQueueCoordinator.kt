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

/** Owns queue/part transition policy while [PlaybackQueueController] remains the durable state holder. */
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
    override val queueStateFlow: StateFlow<PlaybackQueueState> = queueState.state

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
            val parts = playbackParts()
            val resumePartIndex = currentPartIndex().takeIf { it in parts.indices }
            startTrack(track, 0, resumePartIndex, PlaybackStartReason.RESUME)
        }
    }

    override fun playTracks(tracks: List<MusicTrack>, index: Int) {
        replaceSourceQueue(tracks, index, null, null, null, true)
    }

    override fun playTracks(tracks: List<MusicTrack>, index: Int, context: PlaybackContextSnapshot) {
        replaceSourceQueue(tracks, index, null, null, context, true)
    }

    override fun playPlaylistTracks(tracks: List<MusicTrack>, index: Int, sourcePlaylistId: String) {
        replaceSourceQueue(
            tracks,
            index,
            null,
            sourcePlaylistId,
            fallbackPlaylistContext(sourcePlaylistId),
            true,
        )
    }

    override fun playPlaylistTracks(
        tracks: List<MusicTrack>,
        index: Int,
        sourcePlaylistId: String,
        context: PlaybackContextSnapshot,
    ) {
        replaceSourceQueue(tracks, index, null, sourcePlaylistId, context, true)
    }

    override fun playAllPlaylistTracks(tracks: List<MusicTrack>, sourcePlaylistId: String) {
        replaceSourceQueue(
            tracks,
            0,
            null,
            sourcePlaylistId,
            fallbackPlaylistContext(sourcePlaylistId),
            false,
        )
    }

    override fun playAllPlaylistTracks(
        tracks: List<MusicTrack>,
        sourcePlaylistId: String,
        context: PlaybackContextSnapshot,
    ) {
        replaceSourceQueue(tracks, 0, null, sourcePlaylistId, context, false)
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
            tracks,
            index,
            sourceFeature,
            null,
            sourceFeature.toPlaybackContextSnapshot(),
            true,
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
        val upNextIndex = queueState.upNextQueue.indexOfFirst { it == track }
            .takeIf { it >= 0 }
            ?: queueState.upNextQueue.indexOfFirst { it.id == track.id }
        if (upNextIndex >= 0) {
            queueState.upNextQueue = queueState.upNextQueue.filterIndexed { index, _ -> index != upNextIndex }
            publishQueueMutation()
            return
        }
        val mainEntries = queueState.mainQueueEntries()
        val mainIndex = mainEntries.indexOfFirst { it.track == track }
            .takeIf { it >= 0 }
            ?: mainEntries.indexOfFirst { it.track.id == track.id }
        if (mainIndex < 0) return
        val removedEntryId = mainEntries[mainIndex].id
        queueState.replaceMainQueueEntries(mainEntries.filterIndexed { index, _ -> index != mainIndex })
        if (queueState.originalMainQueue.isNotEmpty()) {
            queueState.replaceOriginalMainQueueEntries(
                queueState.originalMainQueueEntries().filterNot { it.id == removedEntryId },
            )
        }
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
        queueState.beginListeningContext(null)
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
        if (queueState.shuffleEnabled) disableShuffle() else enableShuffle()
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

    fun playMainIndex(index: Int, skippedUnavailableCount: Int = 0) {
        playMainIndexInternal(index, skippedUnavailableCount, TrackChangeDirection.Next)
    }

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
        sourceContext: PlaybackContextSnapshot?,
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
        queueState.beginListeningContext(sourceContext)
        queueState.currentUpNextTrack = null
        queueState.currentIsUpNext = false
        queueState.originalMainQueue = emptyList()
        queueState.mainQueue = tracks
        queueState.mainQueueIndex = index
        if (queueState.shuffleEnabled && !enteringFm) {
            if (keepSelectedTrack) {
                enableShuffle()
            } else {
                val originalEntries = queueState.mainQueueEntries()
                queueState.replaceOriginalMainQueueEntries(originalEntries)
                queueState.replaceMainQueueEntries(shuffledEntriesForPlaybackStart(originalEntries))
                queueState.mainQueueIndex = 0
            }
        }

        playMainIndexInternal(
            queueState.mainQueueIndex,
            0,
            TrackChangeDirection.Next,
            if (keepSelectedTrack) PlaybackStartReason.USER_SELECTION else PlaybackStartReason.PLAYLIST_REPLACE,
        )
        publishQueueMutation()
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
        updateTrackChangeDirection(direction)
        val track = queueState.activateUpNext(index) ?: return
        startTrack(track, 0, null, reason)
        publishQueueMutation()
    }

    private fun startTrack(
        track: MusicTrack,
        skippedUnavailableCount: Int,
        requestedPartIndex: Int?,
        reason: PlaybackStartReason,
    ) {
        queueState.beginPlaybackTransaction(track.id, reason)
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
        val entries = queueState.mainQueueEntries()
        if (queueState.originalMainQueue.isEmpty()) {
            queueState.replaceOriginalMainQueueEntries(entries)
        }

        if (queueState.currentIsUpNext) {
            val prefixEnd = (queueState.mainQueueIndex + 1).coerceIn(0, entries.size)
            queueState.replaceMainQueueEntries(
                entries.take(prefixEnd) + shuffleQueueEntries(entries.drop(prefixEnd)),
            )
        } else {
            val currentEntryId = queueState.currentQueueEntryId()
            val currentIndex = entries.indexOfFirst { it.id == currentEntryId }
            if (currentIndex >= 0) {
                val currentEntry = entries[currentIndex]
                val rest = entries.filterIndexed { index, _ -> index != currentIndex }
                queueState.replaceMainQueueEntries(listOf(currentEntry) + shuffleQueueEntries(rest))
                queueState.mainQueueIndex = 0
            } else {
                queueState.replaceMainQueueEntries(shuffleQueueEntries(entries))
                queueState.mainQueueIndex = queueState.mainQueueIndex.coerceIn(0, queueState.mainQueue.lastIndex)
            }
        }
        queueState.shuffleEnabled = true
    }

    private fun shuffledEntriesForPlaybackStart(entries: List<PlaybackQueueEntry>): List<PlaybackQueueEntry> {
        if (entries.size <= 1) return entries
        val shuffled = shuffleQueueEntries(entries)
        return if (shuffled.firstOrNull()?.id == entries.first().id) {
            shuffled.drop(1) + shuffled.first()
        } else {
            shuffled
        }
    }

    private fun reshufflePendingMainQueue() {
        if (!queueState.shuffleEnabled || queueState.isFmQueue || queueState.mainQueue.size <= 1) return
        val entries = queueState.mainQueueEntries()
        val firstPendingIndex = (queueState.mainQueueIndex + 1).coerceIn(0, entries.size)
        val pending = entries.drop(firstPendingIndex)
        if (pending.size <= 1) return
        queueState.replaceMainQueueEntries(entries.take(firstPendingIndex) + shuffleQueueEntries(pending))
    }

    private fun disableShuffle() {
        val originalEntries = queueState.originalMainQueueEntries()
        if (originalEntries.isNotEmpty()) {
            val currentEntries = queueState.mainQueueEntries()
            val anchorEntryId = if (queueState.currentIsUpNext) {
                currentEntries.getOrNull(queueState.mainQueueIndex)?.id
            } else {
                queueState.currentQueueEntryId()
            }
            queueState.replaceMainQueueEntries(originalEntries)
            queueState.mainQueueIndex = anchorEntryId
                ?.let { id -> originalEntries.indexOfFirst { it.id == id } }
                ?.takeIf { it >= 0 }
                ?: queueState.mainQueueIndex.coerceIn(-1, queueState.mainQueue.lastIndex)
        }
        queueState.originalMainQueue = emptyList()
        queueState.shuffleEnabled = false
    }

    private fun shuffleQueueEntries(entries: List<PlaybackQueueEntry>): List<PlaybackQueueEntry> {
        if (entries.size <= 1) return entries
        val shuffledTracks = shuffleTracks(entries.map(PlaybackQueueEntry::track))
        if (shuffledTracks.size != entries.size) return entries
        val entriesByTrack = mutableMapOf<MusicTrack, ArrayDeque<PlaybackQueueEntry>>()
        entries.forEach { entry ->
            entriesByTrack.getOrPut(entry.track) { ArrayDeque() }.addLast(entry)
        }
        val result = shuffledTracks.mapNotNull { track -> entriesByTrack[track]?.removeFirstOrNull() }
        return result.takeIf { it.size == entries.size } ?: entries
    }

    private fun fallbackPlaylistContext(sourcePlaylistId: String) = PlaybackContextSnapshot(
        type = PlaybackContextType.Playlist,
        sourceId = "context",
        resourceId = sourcePlaylistId,
        title = sourcePlaylistId,
    )

    private fun ProviderFeature.toPlaybackContextSnapshot() = PlaybackContextSnapshot(
        type = PlaybackContextType.Feature,
        sourceId = providerId,
        resourceId = id,
        title = title,
        subtitle = providerName,
    )

    private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor
}
