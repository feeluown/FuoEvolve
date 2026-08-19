from pathlib import Path

path = Path("shared/src/commonMain/kotlin/org/feeluown/mobile/FuoPlayerController.kt")
source = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global source
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    source = source.replace(old, new, 1)


def replace_range(start: str, end: str, new: str, label: str) -> None:
    global source
    start_index = source.find(start)
    if start_index < 0:
        raise SystemExit(f"{label}: start marker not found")
    end_index = source.find(end, start_index)
    if end_index < 0:
        raise SystemExit(f"{label}: end marker not found")
    source = source[:start_index] + new + source[end_index:]


replace_once(
    """    val isShuffleEnabled: Boolean
        get() = shuffleEnabled
    val repeatMode: RepeatMode
        get() = _repeatMode
    val isFmQueueActive: Boolean
        get() = isFmQueue
    val displayUpNextCount: Int
        get() = upNextQueue.size
""",
    """    val isShuffleEnabled: Boolean
        get() = playbackQueueController.shuffleEnabled
    val repeatMode: RepeatMode
        get() = playbackQueueController.repeatMode
    val isFmQueueActive: Boolean
        get() = playbackQueueController.isFmQueue
    val displayUpNextCount: Int
        get() = playbackQueueController.upNextCount
""",
    "queue facade getters",
)

replace_once(
    """    private var mainQueue: List<MusicTrack> = emptyList()
    private var originalMainQueue: List<MusicTrack> = emptyList()
    private var upNextQueue: List<MusicTrack> = emptyList()
    private var mainQueueIndex: Int = -1
    private var currentUpNextTrack: MusicTrack? = null
    private var currentIsUpNext: Boolean = false
    private var queueFeature: ProviderFeature? = null
    private var queuePlaylistId: String? = null
    private var shuffleEnabled: Boolean = false
    private var _repeatMode: RepeatMode = RepeatMode.QUEUE
    private var isFmQueue: Boolean = false
    private var shuffleBeforeFm: Boolean? = null
""",
    """    private val mainQueue: List<MusicTrack>
        get() = playbackQueueController.mainQueue
    private val upNextQueue: List<MusicTrack>
        get() = playbackQueueController.upNextQueue
    private val mainQueueIndex: Int
        get() = playbackQueueController.mainQueueIndex
    private val currentIsUpNext: Boolean
        get() = playbackQueueController.currentIsUpNext
    private val queueFeature: ProviderFeature?
        get() = playbackQueueController.queueFeature
    private val queuePlaylistId: String?
        get() = playbackQueueController.queuePlaylistId
    private val _repeatMode: RepeatMode
        get() = playbackQueueController.repeatMode
""",
    "queue state fields",
)

replace_once(
    """    private val sleepTimerController = PlaybackSleepTimerController(
""",
    """    private val playbackQueueController = PlaybackQueueController(
        playbackQueueStore = playbackQueueStore,
        scope = scope,
    )
    private val sleepTimerController = PlaybackSleepTimerController(
""",
    "queue controller wiring",
)

replace_once(
    """            runCatching { playbackQueueStore.load() }
                .onSuccess { restorePlaybackQueue(it) }
""",
    """            runCatching { playbackQueueController.load() }
                .onSuccess { restorePlaybackQueueState() }
""",
    "queue restore initialization",
)

source = source.replace(
    """        if (currentIsUpNext) {
            currentUpNextTrack = null
            currentIsUpNext = false
            persistPlaybackQueue()
        }
""",
    """        if (currentIsUpNext) {
            playbackQueueController.clearCurrentUpNext()
            persistPlaybackQueue()
        }
""",
)

replace_range(
    "    fun removeFromQueue(track: MusicTrack) {\n",
    "    fun download(track: MusicTrack) = downloadController.download(track)\n",
    """    fun removeFromQueue(track: MusicTrack) {
        if (!playbackQueueController.removeFromQueue(track.id)) return
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    fun clearQueue() {
        val keptCurrentTrack = playbackQueueController.clearQueue()
        updatePlaybackQueueState()
        persistPlaybackQueue()
        message = if (keptCurrentTrack) "已清空播放队列" else "播放队列已清空"
    }

    fun addToUpNext(track: MusicTrack) {
        playbackQueueController.addToUpNext(track)
        message = "已加入接下来播放：${track.title}"
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    fun toggleShuffle() {
        if (!playbackQueueController.toggleShuffle()) return
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    fun toggleRepeat() {
        if (!playbackQueueController.toggleRepeat()) return
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

""",
    "public queue operations",
)

replace_range(
    "    private fun replaceMainQueue(\n",
    "    private fun loadFeatureAndPlayAll(feature: ProviderFeature) {\n",
    """    private fun replaceMainQueue(
        sourceQueue: List<MusicTrack>,
        index: Int,
        sourceFeature: ProviderFeature?,
        sourcePlaylistId: String?,
        keepSelectedTrack: Boolean,
    ): Int {
        val playbackIndex = playbackQueueController.replaceMainQueue(
            sourceQueue = sourceQueue,
            index = index,
            sourceFeature = sourceFeature,
            sourcePlaylistId = sourcePlaylistId,
            keepSelectedTrack = keepSelectedTrack,
        )
        if (playbackIndex >= 0) {
            updatePlaybackQueueState()
            persistPlaybackQueue()
        }
        return playbackIndex
    }

    private fun appendPlaylistPlaybackQueue(
        playlist: ProviderPlaylist,
        tracks: List<MusicTrack>,
    ) {
        if (
            tracks.isEmpty() ||
            queuePlaylistId != playlist.id ||
            selectedPlaylist?.id != playlist.id
        ) {
            return
        }
        if (playbackQueueController.appendPlaylistTracks(tracks).isEmpty()) return
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    private fun reshuffleCompletedPlaylistQueue(playlist: ProviderPlaylist) {
        if (queuePlaylistId != playlist.id || selectedPlaylist?.id != playlist.id) return
        if (!playbackQueueController.reshuffleRemaining()) return
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    private fun playMainIndex(index: Int, skippedUnavailableCount: Int = 0) {
        val track = playbackQueueController.selectMainIndex(index) ?: return
        startPlayback(track, skippedUnavailableCount)
    }

    private fun playUpNextIndex(index: Int) {
        val track = playbackQueueController.selectUpNextIndex(index) ?: return
        updatePlaybackQueueState()
        persistPlaybackQueue()
        startPlayback(track)
    }

""",
    "queue mutation helpers",
)

replace_range(
    "    private suspend fun appendFeatureQueueOnce(feature: ProviderFeature): Int {\n",
    "    private fun MusicTrack.preferDownloaded(): MusicTrack {\n",
    """    private suspend fun appendFeatureQueueOnce(feature: ProviderFeature): Int {
        return try {
            val tracks = withTimeout(30_000) {
                providerRepository.loadMoreFeatureTracks(feature)
            }
            if (queueFeature != feature) return 0
            val newTracks = playbackQueueController.appendDistinctMainTracks(tracks)
            if (newTracks.isNotEmpty()) {
                updatePlaybackQueueState()
                persistPlaybackQueue()
                if (selectedFeature == feature) {
                    val seenSelectedIds = selectedFeatureTracks.mapTo(mutableSetOf()) { it.id }
                    val newSelectedTracks = newTracks.filter { seenSelectedIds.add(it.id) }
                    if (newSelectedTracks.isNotEmpty()) {
                        selectedFeatureTracks = selectedFeatureTracks + newSelectedTracks
                    }
                }
            }
            newTracks.size
        } catch (throwable: Throwable) {
            if (queueFeature == feature) {
                message = when (throwable) {
                    is TimeoutCancellationException -> "加载后续歌曲超时，请检查网络后重试"
                    else -> throwable.message ?: throwable::class.simpleName.orEmpty()
                }
            }
            0
        }
    }

""",
    "dynamic queue append",
)

replace_range(
    "    private fun currentQueueTrack(): MusicTrack? {\n",
    "    private fun providerName(providerId: String): String {\n",
    """    private fun currentQueueTrack(): MusicTrack? = playbackQueueController.currentTrack

    private fun displayQueue(): List<MusicTrack> = playbackQueueController.displayQueue()

    private fun displayQueueIndex(): Int = playbackQueueController.displayQueueIndex

    private fun updateCurrentTrack(track: MusicTrack) {
        playbackQueueController.updateCurrentTrack(track)
    }

    private fun synchronizePlaybackTrack(track: MusicTrack) {
        if (playbackQueueController.synchronizePlaybackTrack(track)) {
            persistPlaybackQueue()
        }
    }

    private fun updateLocalTrackCopies(trackId: String, updatedTrack: MusicTrack) {
        playbackQueueController.updateTrackCopies(trackId, updatedTrack)
        if (playbackState.currentTrack?.id == trackId) {
            playbackState = playbackState.copy(
                currentTrack = updatedTrack,
                lyrics = updatedTrack.lyrics ?: playbackState.lyrics,
            )
        }
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    private fun updatePlaybackQueueState() {
        playbackState = playbackState.copy(
            queue = displayQueue(),
            queueIndex = displayQueueIndex(),
            currentTrack = currentQueueTrack() ?: playbackState.currentTrack,
            playbackParts = playbackParts,
            currentPartIndex = currentPartIndex,
        )
    }

    private fun restorePlaybackQueueState() {
        playbackParts = emptyList()
        currentPartIndex = -1
        playbackState = playbackState.copy(
            currentTrack = currentQueueTrack(),
            queue = displayQueue(),
            queueIndex = displayQueueIndex(),
            playbackParts = playbackParts,
            currentPartIndex = currentPartIndex,
        )
    }

    private fun persistPlaybackQueue() {
        playbackQueueController.persist()
    }

""",
    "queue state helpers",
)

replace_range(
    "    private fun removeDislikedTrack(track: MusicTrack) {\n",
    "    fun removeTrackFromSelectedPlaylist(track: MusicTrack) {\n",
    """    private fun removeDislikedTrack(track: MusicTrack) {
        val isCurrent = currentQueueTrack()?.id == track.id
        if (isCurrent) {
            val hasNext = playbackQueueController.hasNextAfterCurrent
            if (hasNext) next() else playbackEngine.stop()
        }
        playbackQueueController.removeTrackEverywhere(track.id)
        recommendSections = recommendSections.withoutTrack(track.id)
        musicSections = musicSections.withoutTrack(track.id)
        mineSections = mineSections.withoutTrack(track.id)
        selectedFeatureTracks = selectedFeatureTracks.filterNot { it.id == track.id }
        selectedFeatureContent = selectedFeatureContent?.copy(
            tracks = selectedFeatureContent.orEmptyTracks().filterNot { it.id == track.id },
        )
        selectedPlaylistTracks = selectedPlaylistTracks.filterNot { it.id == track.id }
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

""",
    "disliked queue removal",
)

# Engine-error recovery contains the same current-Up-Next cleanup shape as next()/previous().
source = source.replace(
    """        if (currentIsUpNext) {
            currentUpNextTrack = null
            currentIsUpNext = false
            persistPlaybackQueue()
        }
""",
    """        if (currentIsUpNext) {
            playbackQueueController.clearCurrentUpNext()
            persistPlaybackQueue()
        }
""",
)

for forbidden in (
    "mainQueue =",
    "upNextQueue =",
    "mainQueueIndex =",
    "currentUpNextTrack =",
    "currentIsUpNext =",
    "queueFeature =",
    "queuePlaylistId =",
    "shuffleEnabled =",
    "_repeatMode =",
    "isFmQueue =",
    "shuffleBeforeFm =",
    "originalMainQueue",
):
    if forbidden in source:
        raise SystemExit(f"queue state mutation still remains in FuoPlayerController: {forbidden}")

path.write_text(source, encoding="utf-8")

queue_controller = r'''package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class PlaybackQueueController(
    private val playbackQueueStore: PlaybackQueueStore,
    private val scope: CoroutineScope,
) {
    var mainQueue: List<MusicTrack> = emptyList()
        private set
    var originalMainQueue: List<MusicTrack> = emptyList()
        private set
    var upNextQueue: List<MusicTrack> = emptyList()
        private set
    var mainQueueIndex: Int = -1
        private set
    var currentUpNextTrack: MusicTrack? = null
        private set
    var currentIsUpNext: Boolean = false
        private set
    var queueFeature: ProviderFeature? = null
        private set
    var queuePlaylistId: String? = null
        private set
    var shuffleEnabled: Boolean = false
        private set
    var repeatMode: RepeatMode = RepeatMode.QUEUE
        private set
    var isFmQueue: Boolean = false
        private set
    var shuffleBeforeFm: Boolean? = null
        private set

    val currentTrack: MusicTrack?
        get() = if (currentIsUpNext) currentUpNextTrack else mainQueue.getOrNull(mainQueueIndex)

    val displayQueueIndex: Int
        get() = if (currentTrack != null) 0 else -1

    val upNextCount: Int
        get() = upNextQueue.size

    val playableCount: Int
        get() = upNextQueue.size + mainQueue.size

    val hasNextAfterCurrent: Boolean
        get() = upNextQueue.isNotEmpty() || mainQueueIndex + 1 < mainQueue.size

    suspend fun load() {
        restore(playbackQueueStore.load())
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
        queueFeature = null
        queuePlaylistId = null
    }

    fun persist() {
        val snapshot = PlaybackQueueSnapshot(
            mainQueue = mainQueue,
            originalMainQueue = originalMainQueue,
            upNextQueue = upNextQueue,
            queueIndex = mainQueueIndex,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            isFmQueue = isFmQueue,
            shuffleBeforeFm = shuffleBeforeFm,
        )
        scope.launch {
            playbackQueueStore.save(snapshot)
        }
    }

    fun displayQueue(): List<MusicTrack> {
        return buildList {
            currentTrack?.let { add(it) }
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
    }

    fun replaceMainQueue(
        sourceQueue: List<MusicTrack>,
        index: Int,
        sourceFeature: ProviderFeature?,
        sourcePlaylistId: String?,
        keepSelectedTrack: Boolean,
    ): Int {
        if (sourceQueue.isEmpty()) return -1
        val normalizedIndex = index.coerceIn(0, sourceQueue.lastIndex)
        val enteringFm = sourceFeature?.id?.endsWith("_radio") == true
        val restoreShuffle = if (isFmQueue && !enteringFm) shuffleBeforeFm else null
        if (enteringFm && !isFmQueue) {
            shuffleBeforeFm = shuffleEnabled
            shuffleEnabled = false
        } else if (!enteringFm && restoreShuffle != null) {
            shuffleEnabled = restoreShuffle
            shuffleBeforeFm = null
        }
        isFmQueue = enteringFm
        queueFeature = sourceFeature
        queuePlaylistId = sourcePlaylistId
        currentUpNextTrack = null
        currentIsUpNext = false
        originalMainQueue = emptyList()
        mainQueue = sourceQueue
        mainQueueIndex = normalizedIndex
        if (shuffleEnabled && !enteringFm) {
            if (keepSelectedTrack) {
                enableShuffle()
            } else {
                originalMainQueue = mainQueue
                mainQueue = mainQueue.shuffledForPlaybackStart()
                mainQueueIndex = 0
            }
        }
        return mainQueueIndex
    }

    fun selectMainIndex(index: Int): MusicTrack? {
        val track = mainQueue.getOrNull(index) ?: return null
        currentUpNextTrack = null
        currentIsUpNext = false
        mainQueueIndex = index
        return track
    }

    fun selectUpNextIndex(index: Int): MusicTrack? {
        val track = upNextQueue.getOrNull(index) ?: return null
        upNextQueue = upNextQueue.filterIndexed { itemIndex, _ -> itemIndex != index }
        currentUpNextTrack = track
        currentIsUpNext = true
        return track
    }

    fun clearCurrentUpNext() {
        currentUpNextTrack = null
        currentIsUpNext = false
    }

    fun addToUpNext(track: MusicTrack) {
        upNextQueue = upNextQueue + track
    }

    fun removeFromQueue(trackId: String): Boolean {
        if (currentUpNextTrack?.id == trackId) {
            clearCurrentUpNext()
            return true
        }
        val upNextIndex = upNextQueue.indexOfFirst { it.id == trackId }
        if (upNextIndex >= 0) {
            upNextQueue = upNextQueue.filterIndexed { index, _ -> index != upNextIndex }
            return true
        }
        val mainIndex = mainQueue.indexOfFirst { it.id == trackId }
        if (mainIndex < 0) return false
        mainQueue = mainQueue.filterIndexed { index, _ -> index != mainIndex }
        originalMainQueue = originalMainQueue.filterNot { it.id == trackId }
        mainQueueIndex = when {
            mainQueue.isEmpty() -> -1
            mainIndex < mainQueueIndex -> mainQueueIndex - 1
            mainIndex == mainQueueIndex -> mainQueueIndex.coerceAtMost(mainQueue.lastIndex)
            else -> mainQueueIndex
        }
        return true
    }

    fun clearQueue(): Boolean {
        val current = currentTrack
        mainQueue = emptyList()
        originalMainQueue = emptyList()
        upNextQueue = emptyList()
        currentUpNextTrack = null
        currentIsUpNext = false
        mainQueueIndex = -1
        queueFeature = null
        queuePlaylistId = null
        isFmQueue = false
        shuffleBeforeFm = null
        if (current != null) {
            mainQueue = listOf(current)
            mainQueueIndex = 0
        }
        return current != null
    }

    fun toggleShuffle(): Boolean {
        if (isFmQueue) return false
        if (shuffleEnabled) disableShuffle() else enableShuffle()
        return true
    }

    fun toggleRepeat(): Boolean {
        if (isFmQueue) return false
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.QUEUE
            RepeatMode.QUEUE -> RepeatMode.SINGLE
            RepeatMode.SINGLE -> RepeatMode.OFF
        }
        return true
    }

    fun appendPlaylistTracks(tracks: List<MusicTrack>): List<MusicTrack> {
        val existingIds = (mainQueue + originalMainQueue).mapTo(mutableSetOf()) { it.id }
        val newTracks = tracks.filter { existingIds.add(it.id) }
        if (newTracks.isEmpty()) return emptyList()
        if (shuffleEnabled) {
            val sourceQueue = originalMainQueue.ifEmpty { mainQueue }
            originalMainQueue = sourceQueue + newTracks
        }
        mainQueue = mainQueue + newTracks
        return newTracks
    }

    fun reshuffleRemaining(): Boolean {
        if (!shuffleEnabled || mainQueue.isEmpty()) return false
        val nextIndex = (mainQueueIndex + 1).coerceIn(0, mainQueue.size)
        mainQueue = mainQueue.take(nextIndex) + mainQueue.drop(nextIndex).shuffledForPlaybackStart()
        return true
    }

    fun appendDistinctMainTracks(tracks: List<MusicTrack>): List<MusicTrack> {
        val seenQueueIds = mainQueue.mapTo(mutableSetOf()) { it.id }
        val newTracks = tracks.filter { seenQueueIds.add(it.id) }
        if (newTracks.isNotEmpty()) {
            mainQueue = mainQueue + newTracks
        }
        return newTracks
    }

    fun updateCurrentTrack(track: MusicTrack) {
        if (currentIsUpNext) {
            currentUpNextTrack = track
        } else if (mainQueueIndex in mainQueue.indices) {
            mainQueue = mainQueue.mapIndexed { index, item -> if (index == mainQueueIndex) track else item }
            originalMainQueue = originalMainQueue.map { item -> if (item.id == track.id) track else item }
        }
    }

    fun synchronizePlaybackTrack(track: MusicTrack): Boolean {
        val current = currentTrack
        var changed = current != track
        if (current?.id != track.id) {
            val upNextIndex = upNextQueue.indexOfFirst { it.id == track.id }
            if (upNextIndex >= 0) {
                currentUpNextTrack = upNextQueue[upNextIndex]
                upNextQueue = upNextQueue.filterIndexed { index, _ -> index != upNextIndex }
                currentIsUpNext = true
            } else {
                val mainIndex = mainQueue.indexOfFirst { it.id == track.id }
                if (mainIndex >= 0) {
                    mainQueueIndex = mainIndex
                    currentUpNextTrack = null
                    currentIsUpNext = false
                    changed = true
                }
            }
        }
        updateCurrentTrack(track)
        return changed
    }

    fun updateTrackCopies(trackId: String, updatedTrack: MusicTrack) {
        mainQueue = mainQueue.map { if (it.id == trackId) updatedTrack else it }
        originalMainQueue = originalMainQueue.map { if (it.id == trackId) updatedTrack else it }
        upNextQueue = upNextQueue.map { if (it.id == trackId) updatedTrack else it }
        if (currentUpNextTrack?.id == trackId) {
            currentUpNextTrack = updatedTrack
        }
    }

    fun removeTrackEverywhere(trackId: String) {
        val removedBeforeCurrent = mainQueue
            .take(mainQueueIndex.coerceIn(0, mainQueue.size))
            .count { it.id == trackId }
        mainQueue = mainQueue.filterNot { it.id == trackId }
        originalMainQueue = originalMainQueue.filterNot { it.id == trackId }
        upNextQueue = upNextQueue.filterNot { it.id == trackId }
        if (currentUpNextTrack?.id == trackId) {
            currentUpNextTrack = null
            currentIsUpNext = false
        }
        mainQueueIndex = (mainQueueIndex - removedBeforeCurrent).coerceIn(-1, mainQueue.lastIndex)
    }

    private fun enableShuffle() {
        if (isFmQueue || mainQueue.size <= 1) {
            shuffleEnabled = !isFmQueue
            return
        }
        val current = currentTrack
        originalMainQueue = if (originalMainQueue.isEmpty()) mainQueue else originalMainQueue
        val currentInMain = current?.let { track -> mainQueue.firstOrNull { it.id == track.id } }
        val shuffledRest = mainQueue.filterNot { it.id == currentInMain?.id }.shuffled()
        mainQueue = listOfNotNull(currentInMain) + shuffledRest
        mainQueueIndex = currentInMain?.let { 0 } ?: mainQueueIndex.coerceIn(0, mainQueue.lastIndex)
        shuffleEnabled = true
    }

    private fun disableShuffle() {
        val current = currentTrack
        if (originalMainQueue.isNotEmpty()) {
            mainQueue = originalMainQueue
            mainQueueIndex = current?.let { track -> mainQueue.indexOfFirst { it.id == track.id } }
                ?.takeIf { it >= 0 }
                ?: mainQueueIndex.coerceIn(-1, mainQueue.lastIndex)
        }
        originalMainQueue = emptyList()
        shuffleEnabled = false
    }

    private fun List<MusicTrack>.shuffledForPlaybackStart(): List<MusicTrack> {
        if (size <= 1) return this
        val shuffled = shuffled()
        return if (shuffled.first().id == first().id) {
            shuffled.drop(1) + shuffled.first()
        } else {
            shuffled
        }
    }
}
'''
Path("shared/src/commonMain/kotlin/org/feeluown/mobile/PlaybackQueueController.kt").write_text(
    queue_controller,
    encoding="utf-8",
)
