package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runtime-facing transport contract owned by the playback feature.
 *
 * Platform runtime adapters depend on this contract instead of calling the
 * legacy app-controller transport facade.
 */
interface PlaybackTransportCoordinator {
    fun startCurrent()
    fun previous()
    fun next()
}

/**
 * Owns queue/part transition policy while [PlaybackQueueController] remains the
 * durable queue state holder.
 */
internal class PlaybackQueueCoordinator(
    private val queue: PlaybackQueueController,
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
) : PlaybackTransportCoordinator {

    override fun startCurrent() {
        (queue.currentTrack() ?: fallbackTrack())?.let { startPlayback(it, 0, null) }
    }

    override fun next() {
        setTrackChangeDirection(TrackChangeDirection.Next)
        if (queue.repeatMode == RepeatMode.SINGLE) {
            if (playPlaybackPartOffset(1, wrap = true)) return
            startCurrent()
            return
        }
        if (playPlaybackPartOffset(1)) return
        if (queue.currentIsUpNext) {
            queue.currentUpNextTrack = null
            queue.currentIsUpNext = false
            persistQueue()
        }
        if (queue.upNextQueue.isNotEmpty()) {
            playUpNextIndex(0)
            return
        }
        if (queue.mainQueue.isEmpty()) return
        val feature = queue.queueFeature
        if (feature != null && queue.mainQueueIndex >= queue.mainQueue.lastIndex) {
            scope.launch {
                val nextIndex = queue.mainQueue.size
                val appendedCount = appendFeatureQueue(feature)
                if (appendedCount > 0 && queue.queueFeature == feature) {
                    playMainIndex(nextIndex)
                } else if (queue.queueFeature == feature) {
                    setMessage("${feature.title} 暂无后续歌曲")
                }
            }
            return
        }
        val nextIndex = queue.mainQueueIndex + 1
        if (nextIndex < queue.mainQueue.size) {
            playMainIndex(nextIndex)
        } else if (queue.repeatMode == RepeatMode.QUEUE) {
            playMainIndex(0)
        }
    }

    override fun previous() {
        setTrackChangeDirection(TrackChangeDirection.Previous)
        if (queue.repeatMode == RepeatMode.SINGLE) {
            if (playPlaybackPartOffset(-1, wrap = true)) return
            startCurrent()
            return
        }
        if (playPlaybackPartOffset(-1)) return
        if (queue.currentIsUpNext) {
            queue.currentUpNextTrack = null
            queue.currentIsUpNext = false
            persistQueue()
            playMainIndex(queue.mainQueueIndex.coerceAtLeast(0))
            return
        }
        if (queue.mainQueue.isEmpty()) return
        val previousIndex = queue.mainQueueIndex - 1
        if (previousIndex >= 0) {
            playMainIndex(previousIndex)
        } else if (queue.repeatMode == RepeatMode.QUEUE) {
            playMainIndex(queue.mainQueue.lastIndex)
        }
    }

    fun playQueueIndex(index: Int) {
        setTrackChangeDirection(TrackChangeDirection.Next)
        val currentOffset = if (queue.currentTrack() != null) 1 else 0
        if (index == 0 && currentOffset == 1) {
            startCurrent()
            return
        }
        val pendingOffset = currentOffset
        val pendingEnd = pendingOffset + queue.upNextQueue.size
        when {
            index in pendingOffset until pendingEnd -> {
                playUpNextIndex(index - pendingOffset)
            }
            else -> {
                val mainStartIndex = when {
                    queue.currentIsUpNext -> queue.mainQueueIndex + 1
                    queue.mainQueueIndex >= 0 -> queue.mainQueueIndex + 1
                    else -> 0
                }
                val mainIndex = mainStartIndex + (index - pendingEnd)
                playMainIndex(mainIndex)
            }
        }
    }

    fun playPlaybackPart(index: Int) {
        val parts = playbackParts()
        if (index !in parts.indices) return
        queue.currentTrack()?.let { startPlayback(it, 0, index) }
    }

    fun playMainIndex(index: Int, skippedUnavailableCount: Int = 0) {
        val track = queue.mainQueue.getOrNull(index) ?: return
        queue.currentUpNextTrack = null
        queue.currentIsUpNext = false
        queue.mainQueueIndex = index
        startPlayback(track, skippedUnavailableCount, null)
    }

    fun playUpNextIndex(index: Int) {
        val track = queue.upNextQueue.getOrNull(index) ?: return
        queue.upNextQueue = queue.upNextQueue.filterIndexed { itemIndex, _ -> itemIndex != index }
        queue.currentUpNextTrack = track
        queue.currentIsUpNext = true
        updateQueueState()
        persistQueue()
        startPlayback(track, 0, null)
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
        queue.currentTrack()?.let { startPlayback(it, 0, targetPartIndex) } ?: return false
        return true
    }

    private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor
}
