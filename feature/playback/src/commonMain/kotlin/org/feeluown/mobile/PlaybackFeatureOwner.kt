package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val PLAYBACK_OWNER_DYNAMIC_QUEUE_PREFETCH_REMAINING = 2

private data class PlaybackOwnerPendingManualReplacement(
    val requestSerial: Long,
    val previousTrack: MusicTrack?,
    val originalTrackId: String,
    val selection: SmartReplacementSelection,
)

/** Playback-scoped application owner, independent from app shell and concrete providers. */
interface PlaybackFeatureOwner {
    val playbackState: StateFlow<PlaybackState>
    val transport: PlaybackTransportCoordinator
    val startFailureSource: PlaybackStartFailureSource
    val navigation: PlaybackNavigationPort
    val sleepTimer: PlaybackSleepTimerPort
    val lyrics: PlaybackLyricsPort
    val replacement: ReplacementActionPort

    fun updateTrackCopies(trackId: String, updatedTrack: MusicTrack)
}

fun createPlaybackFeatureOwner(
    providerRepository: PlaybackProviderPort,
    playbackEngine: PlaybackEngine,
    playbackQueueStore: PlaybackQueueStore,
    settings: PlaybackSettingsPort,
    downloads: PlaybackDownloadPort,
    navigation: PlaybackNavigationPort,
    scope: CoroutineScope,
    openTrackDetail: (MusicTrack) -> Unit,
    nowMillis: () -> Long,
): PlaybackFeatureOwner = DefaultPlaybackFeatureOwner(
    providerRepository = providerRepository,
    playbackEngine = playbackEngine,
    playbackQueueStore = playbackQueueStore,
    settings = settings,
    downloads = downloads,
    navigationOwner = navigation,
    scope = scope,
    openTrackDetail = openTrackDetail,
    nowMillis = nowMillis,
)

private class DefaultPlaybackFeatureOwner(
    private val providerRepository: PlaybackProviderPort,
    private val playbackEngine: PlaybackEngine,
    private val playbackQueueStore: PlaybackQueueStore,
    private val settings: PlaybackSettingsPort,
    private val downloads: PlaybackDownloadPort,
    private val navigationOwner: PlaybackNavigationPort,
    private val scope: CoroutineScope,
    private val openTrackDetail: (MusicTrack) -> Unit,
    nowMillis: () -> Long,
) : PlaybackFeatureOwner {
    private val queueState = PlaybackQueueController()
    private val playbackRepository: ProviderPlaybackRepository = providerRepository
    private val mutablePlaybackState = MutableStateFlow(PlaybackState())
    private val playbackFeedback = MutableStateFlow<String?>(null)
    override val playbackState: StateFlow<PlaybackState> = mutablePlaybackState.asStateFlow()

    private var playbackParts: List<PlaybackPart> = emptyList()
    private var currentPartIndex = -1
    private var playRequestSerial = 0L
    private var lastRecoveredPlaybackErrorKey: String? = null
    private var smartReplacementSelections: Map<String, SmartReplacementSelection> = emptyMap()
    private var lyricsAssociations: Map<String, String> = emptyMap()
    private var lyricsAlignmentOffsetsMs: Map<String, Long> = emptyMap()
    private var pendingManualReplacementSwitch: PlaybackOwnerPendingManualReplacement? = null
    private var suppressPlaybackRecoveryRequestSerial: Long? = null
    private var appendQueueFeatureTask: Deferred<Int>? = null

    private val sleepTimerOwner = PlaybackSleepTimerController(
        playbackEngine = playbackEngine,
        scope = scope,
        currentTrackId = { queueState.currentTrack()?.id ?: playbackState.value.currentTrack?.id },
        nowMillis = nowMillis,
        onFeedback = {},
    )

    private val lyricsOwner = PlaybackLyricsController(
        providerRegistryRepository = providerRepository,
        providerSearchRepository = providerRepository,
        providerCatalogRepository = providerRepository,
        providerPlaybackRepository = playbackRepository,
        scope = scope,
        currentRequestSerial = { playRequestSerial },
        currentTrackId = { queueState.currentTrack()?.id ?: playbackState.value.currentTrack?.id },
        currentResolvedSource = { playbackState.value.resolvedSource },
        currentLyrics = { playbackState.value.lyrics },
        updateLyrics = { lyrics -> updatePlaybackState { it.copy(lyrics = lyrics) } },
        associationForTrackId = { trackId -> lyricsAssociations[trackId] },
        rememberAssociation = ::rememberLyricsAssociation,
        alignmentOffsetForTrackId = { trackId -> lyricsAlignmentOffsetsMs[trackId] ?: 0L },
        rememberAlignmentOffset = ::rememberLyricsAlignmentOffset,
    )

    private val startOwner = PlaybackStartCoordinator(
        queue = queueState,
        playbackEngine = playbackEngine,
        playbackRepository = playbackRepository,
        scope = scope,
        currentPlaybackState = { playbackState.value },
        publishPlaybackState = { state ->
            mutablePlaybackState.value = state.copy(
                lyricsAlignmentOffsetMs = lyricsOwner.associationState.value.alignmentOffsetMs,
            )
        },
        prepareTrack = { track -> track.withRememberedReplacement().preferDownloaded() },
        unavailablePlaybackPolicy = { currentSettings().unavailablePlaybackPolicy },
        smartReplacementProviderIds = ::selectedSmartReplacementProviderIds,
        smartReplacementMinScore = { currentSettings().smartReplacementMinScore.coerceIn(0.0, 1.0) },
        nextRequestSerial = { ++playRequestSerial },
        currentRequestSerial = { playRequestSerial },
        playbackParts = { playbackParts },
        setPlaybackParts = { playbackParts = it },
        currentPartIndex = { currentPartIndex },
        setCurrentPartIndex = { currentPartIndex = it },
        prepareSleepTimer = sleepTimerOwner::prepareForTrack,
        resetLyricsForPlaybackRequest = lyricsOwner::resetForPlaybackRequest,
        maybeLoadLyrics = lyricsOwner::maybeLoad,
        persistQueue = ::persistPlaybackQueue,
        setLoading = {},
        setMessage = { playbackFeedback.value = it },
        failureMessage = { throwable -> playbackFailureMessage(throwable) },
        onRequestStarted = { serial, suppressRecovery ->
            suppressPlaybackRecoveryRequestSerial = serial.takeIf { suppressRecovery }
        },
        onManualSelectionStarted = { serial, playbackTrack, selection, rollbackTrack ->
            pendingManualReplacementSwitch = PlaybackOwnerPendingManualReplacement(
                requestSerial = serial,
                previousTrack = rollbackTrack,
                originalTrackId = playbackTrack.id,
                selection = selection,
            )
        },
        onStartFailure = startFailure@{ serial, playbackTrack, skippedUnavailableCount, manualSelection, throwable ->
            if (manualSelection != null && rollbackManualReplacement(serial)) return@startFailure
            if (suppressPlaybackRecoveryRequestSerial == serial) {
                showManualReplacementRestoreFailure(throwable.message)
            } else if (!skipUnavailableTrack(playbackTrack, skippedUnavailableCount, throwable)) {
                publishPlaybackError(playbackFailureMessage(throwable))
            }
        },
        prefetchQueue = ::prefetchFeatureQueueIfNeeded,
    )

    private val queueOwner = PlaybackQueueCoordinator(
        queue = queueState,
        scope = scope,
        fallbackTrack = { playbackState.value.currentTrack },
        playbackParts = { playbackParts },
        currentPartIndex = { currentPartIndex },
        startPlayback = { track, skippedUnavailableCount, requestedPartIndex ->
            startPlayback(track, skippedUnavailableCount, requestedPartIndex)
        },
        stopPlayback = playbackEngine::stop,
        persistQueue = ::persistPlaybackQueue,
        updateQueueState = ::updatePlaybackQueueState,
        appendFeatureQueue = ::appendFeatureQueue,
        setTrackChangeDirection = {},
        setMessage = {},
        feedbackState = playbackFeedback,
    )

    private val replacementOwner = PlaybackReplacementController(
        playbackRepository = playbackRepository,
        scope = scope,
        smartReplacementProviderIds = ::selectedSmartReplacementProviderIds,
        smartReplacementMinScore = { currentSettings().smartReplacementMinScore.coerceIn(0.0, 1.0) },
        currentTrack = { queueState.currentTrack() ?: playbackState.value.currentTrack },
        currentResolvedSource = { playbackState.value.resolvedSource },
        startManualReplacement = { track, selection, rollbackTrack ->
            startPlayback(
                track = track,
                manualSelection = selection,
                rollbackTrack = rollbackTrack,
            )
        },
        closePlayer = navigationOwner::closeFullPlayer,
        openTrackDetail = openTrackDetail,
        failureMessage = providerRepository::failureMessage,
    )

    private val lifecycleOwner = PlaybackLifecycleCoordinator(
        sleepTimer = sleepTimerOwner,
        fallbackPlaybackParts = { playbackParts },
        fallbackCurrentPartIndex = { currentPartIndex },
        autoAdvance = queueOwner::next,
    )

    override val transport: PlaybackTransportCoordinator = queueOwner
    override val startFailureSource: PlaybackStartFailureSource = startOwner
    override val navigation: PlaybackNavigationPort = navigationOwner
    override val sleepTimer: PlaybackSleepTimerPort = sleepTimerOwner
    override val lyrics: PlaybackLyricsPort = lyricsOwner
    override val replacement: ReplacementActionPort = replacementOwner

    init {
        scope.launch {
            val initialSettings = settings.awaitSettings()
            smartReplacementSelections = initialSettings.smartReplacementSelections
            lyricsAssociations = initialSettings.lyricsAssociations
            lyricsAlignmentOffsetsMs = initialSettings.lyricsAlignmentOffsetsMs
            runCatching { playbackQueueStore.load() }
                .onSuccess(::restorePlaybackQueue)
            lyricsOwner.refreshPersistentState(playbackState.value.currentTrack)
        }
        scope.launch {
            settings.state.collect { state ->
                smartReplacementSelections = state.smartReplacementSelections
                lyricsAssociations = state.lyricsAssociations
                lyricsAlignmentOffsetsMs = state.lyricsAlignmentOffsetsMs
                lyricsOwner.refreshPersistentState(playbackState.value.currentTrack)
            }
        }
        scope.launch {
            lyricsOwner.associationState.collect { state ->
                if (playbackState.value.lyricsAlignmentOffsetMs != state.alignmentOffsetMs) {
                    updatePlaybackState { current ->
                        current.copy(lyricsAlignmentOffsetMs = state.alignmentOffsetMs)
                    }
                }
            }
        }
        scope.launch {
            playbackEngine.state.collect(::onEngineState)
        }
    }

    override fun updateTrackCopies(trackId: String, updatedTrack: MusicTrack) {
        queueState.mainQueue = queueState.mainQueue.map { if (it.id == trackId) updatedTrack else it }
        queueState.originalMainQueue = queueState.originalMainQueue.map { if (it.id == trackId) updatedTrack else it }
        queueState.upNextQueue = queueState.upNextQueue.map { if (it.id == trackId) updatedTrack else it }
        if (queueState.currentUpNextTrack?.id == trackId) queueState.currentUpNextTrack = updatedTrack
        if (playbackState.value.currentTrack?.id == trackId) {
            updatePlaybackState {
                it.copy(
                    currentTrack = updatedTrack.logicalPlaybackTrack(),
                    lyrics = updatedTrack.lyrics ?: it.lyrics,
                )
            }
        }
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    private fun onEngineState(engineState: PlaybackState) {
        val engineTrack = engineState.currentTrack
        val logicalEngineTrack = engineTrack?.logicalPlaybackTrack()
        logicalEngineTrack?.let(::synchronizePlaybackTrack)
        val currentQueueTrackId = queueState.currentTrack()?.id
        val endAction = lifecycleOwner.evaluate(
            engineState.copy(currentTrack = logicalEngineTrack),
            currentQueueTrackId,
        )
        if (engineState.status == PlayerStatus.Playing) lastRecoveredPlaybackErrorKey = null

        val previous = playbackState.value
        val resolvedSource = engineState.resolvedSource
            ?: engineTrack?.toLegacyResolvedPlaybackSource()
            ?: previous.resolvedSource?.takeIf { logicalEngineTrack?.id == previous.currentTrack?.id }
        mutablePlaybackState.value = engineState.copy(
            queue = queueState.displayQueue(),
            queueIndex = queueState.displayQueueIndex(),
            currentTrack = queueState.currentTrack() ?: logicalEngineTrack,
            resolvedSource = resolvedSource,
            playbackParts = engineState.playbackParts.ifEmpty { playbackParts },
            currentPartIndex = engineState.currentPartIndex.takeIf { it >= 0 } ?: currentPartIndex,
            lyrics = lyricsOwner.mergedLyrics(
                engineState = engineState.copy(currentTrack = logicalEngineTrack),
                currentQueueTrackId = currentQueueTrackId,
                previousPlaybackState = previous,
            ),
            lyricsAlignmentOffsetMs = lyricsOwner.associationState.value.alignmentOffsetMs,
        )
        lyricsOwner.maybeLoad(playbackState.value.currentTrack)
        if (engineState.playbackParts.isNotEmpty()) {
            playbackParts = engineState.playbackParts
            currentPartIndex = engineState.currentPartIndex
        }

        if (endAction != PlaybackEndAction.None) {
            lifecycleOwner.execute(endAction)
        } else if (engineState.status == PlayerStatus.Error) {
            if (!rollbackManualReplacement(playRequestSerial)) {
                if (suppressPlaybackRecoveryRequestSerial == playRequestSerial) {
                    showManualReplacementRestoreFailure(engineState.errorMessage)
                } else {
                    recoverPlaybackEngineError(engineState.copy(currentTrack = logicalEngineTrack))
                }
            }
        }
        if (engineState.status == PlayerStatus.Playing || engineState.status == PlayerStatus.Paused) {
            if (suppressPlaybackRecoveryRequestSerial == playRequestSerial) {
                suppressPlaybackRecoveryRequestSerial = null
            }
            commitManualReplacementIfReady(engineState)
        }
    }

    private fun startPlayback(
        track: MusicTrack,
        skippedUnavailableCount: Int = 0,
        requestedPartIndex: Int? = null,
        manualSelection: SmartReplacementSelection? = null,
        rollbackTrack: MusicTrack? = null,
        suppressPlaybackRecovery: Boolean = false,
    ) {
        startOwner.start(
            track = track,
            skippedUnavailableCount = skippedUnavailableCount,
            requestedPartIndex = requestedPartIndex,
            manualSelection = manualSelection,
            rollbackTrack = rollbackTrack,
            suppressPlaybackRecovery = suppressPlaybackRecovery,
        )
    }

    private fun currentSettings(): PlaybackFeatureSettings = settings.state.value

    private fun selectedSmartReplacementProviderIds(): Set<String> {
        val current = currentSettings()
        val enabled = current.enabledProviderIds
        return if (enabled.isEmpty()) {
            current.smartReplacementProviderIds
        } else {
            current.smartReplacementProviderIds.intersect(enabled).ifEmpty { enabled }
        }
    }

    private fun MusicTrack.preferDownloaded(): MusicTrack {
        if (isSmartReplacement) return this
        val uri = downloads.downloadedUri(id) ?: return this
        return copy(
            sourceType = TrackSourceType.Downloaded,
            localUri = uri,
            providerId = providerId ?: id,
        )
    }

    private fun MusicTrack.withRememberedReplacement(): MusicTrack {
        val originalTrack = logicalPlaybackTrack()
        val selection = smartReplacementSelections[originalTrack.id] ?: return this
        if (selection.replacementSource !in selectedSmartReplacementProviderIds()) {
            return originalTrack
        }
        return originalTrack.withReplacementSelection(selection)
    }

    private fun rememberLyricsAssociation(sourceTrackId: String, lyricsTrackId: String?) {
        val nextAssociations = if (lyricsTrackId.isNullOrBlank()) {
            lyricsAssociations - sourceTrackId
        } else {
            lyricsAssociations + (sourceTrackId to lyricsTrackId)
        }
        lyricsAssociations = nextAssociations
        scope.launch { settings.storeLyricsAssociations(nextAssociations) }
    }

    private fun rememberLyricsAlignmentOffset(sourceTrackId: String, offsetMs: Long) {
        val clamped = offsetMs.coerceIn(-3_000L, 3_000L)
        val nextOffsets = if (clamped == 0L) {
            lyricsAlignmentOffsetsMs - sourceTrackId
        } else {
            lyricsAlignmentOffsetsMs + (sourceTrackId to clamped)
        }
        lyricsAlignmentOffsetsMs = nextOffsets
        scope.launch { settings.storeLyricsAlignmentOffsetsMs(nextOffsets) }
    }

    private fun commitManualReplacementIfReady(engineState: PlaybackState) {
        val pending = pendingManualReplacementSwitch ?: return
        if (pending.requestSerial != playRequestSerial) return
        val engineTrack = engineState.currentTrack ?: return
        val logicalTrackId = engineTrack.logicalPlaybackTrack().id
        val resolvedSource = engineState.resolvedSource ?: engineTrack.toLegacyResolvedPlaybackSource()
        if (
            logicalTrackId != pending.originalTrackId ||
            !resolvedSource.isReplacement ||
            resolvedSource.trackId != pending.selection.replacementId
        ) {
            return
        }
        smartReplacementSelections = smartReplacementSelections + (pending.originalTrackId to pending.selection)
        pendingManualReplacementSwitch = null
        val nextSelections = smartReplacementSelections
        scope.launch { settings.storeSmartReplacementSelections(nextSelections) }
    }

    private fun rollbackManualReplacement(requestSerial: Long): Boolean {
        val pending = pendingManualReplacementSwitch?.takeIf { it.requestSerial == requestSerial } ?: return false
        pendingManualReplacementSwitch = null
        val previousTrack = pending.previousTrack ?: return true
        startOwner.start(
            track = previousTrack,
            messageAfterStart = "手动换源失败，已恢复原播放源：${previousTrack.logicalPlaybackTrack().title}",
            suppressPlaybackRecovery = true,
        )
        return true
    }

    private fun showManualReplacementRestoreFailure(errorMessage: String?) {
        val detail = errorMessage?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
        publishPlaybackError("手动换源失败，无法恢复原播放源$detail")
    }

    private fun prefetchFeatureQueueIfNeeded() {
        val feature = queueState.queueFeature ?: return
        if (queueState.currentIsUpNext || queueState.upNextQueue.isNotEmpty()) return
        if (queueState.mainQueueIndex < 0) return
        if (queueState.mainQueue.size - queueState.mainQueueIndex <= PLAYBACK_OWNER_DYNAMIC_QUEUE_PREFETCH_REMAINING) {
            scope.launch { appendFeatureQueue(feature) }
        }
    }

    private suspend fun appendFeatureQueue(feature: ProviderFeature): Int {
        if (queueState.queueFeature != feature) return 0
        val active = appendQueueFeatureTask?.takeIf { it.isActive }
        if (active != null) return active.await()
        val task = scope.async { appendFeatureQueueOnce(feature) }
        appendQueueFeatureTask = task
        return try {
            task.await()
        } finally {
            if (appendQueueFeatureTask == task) appendQueueFeatureTask = null
        }
    }

    private suspend fun appendFeatureQueueOnce(feature: ProviderFeature): Int {
        return try {
            val tracks = withTimeout(30_000) { providerRepository.loadMoreFeatureTracks(feature) }
            if (queueState.queueFeature != feature) return 0
            val seenIds = queueState.mainQueue.mapTo(mutableSetOf()) { it.id }
            val additions = tracks.filter { seenIds.add(it.id) }
            if (additions.isNotEmpty()) {
                queueState.mainQueue = queueState.mainQueue + additions
                updatePlaybackQueueState()
                persistPlaybackQueue()
            }
            additions.size
        } catch (_: TimeoutCancellationException) {
            playbackFeedback.value = "${feature.title} 加载超时，请重试"
            FEATURE_QUEUE_APPEND_FAILED
        } catch (throwable: Throwable) {
            playbackFeedback.value = providerRepository.failureMessage(
                throwable,
                "${feature.title} 加载后续歌曲失败",
                feature.providerId,
            )
            FEATURE_QUEUE_APPEND_FAILED
        }
    }

    private fun synchronizePlaybackTrack(track: MusicTrack) {
        val logicalTrack = track.logicalPlaybackTrack()
        val current = queueState.currentTrack()
        var changed = current != logicalTrack
        if (current?.id != logicalTrack.id) {
            val upNextIndex = queueState.upNextQueue.indexOfFirst { it.id == logicalTrack.id }
            if (upNextIndex >= 0) {
                queueState.currentUpNextTrack = queueState.upNextQueue[upNextIndex]
                queueState.upNextQueue = queueState.upNextQueue.filterIndexed { index, _ -> index != upNextIndex }
                queueState.currentIsUpNext = true
            } else {
                val mainIndex = queueState.mainQueue.indexOfFirst { it.id == logicalTrack.id }
                if (mainIndex >= 0) {
                    queueState.mainQueueIndex = mainIndex
                    queueState.currentUpNextTrack = null
                    queueState.currentIsUpNext = false
                    changed = true
                }
            }
        }
        queueState.updateCurrentTrack(logicalTrack)
        if (changed) persistPlaybackQueue()
    }

    private fun updatePlaybackQueueState() {
        updatePlaybackState { current ->
            current.copy(
                queue = queueState.displayQueue(),
                queueIndex = queueState.displayQueueIndex(),
                currentTrack = queueState.currentTrack() ?: current.currentTrack?.logicalPlaybackTrack(),
                playbackParts = playbackParts,
                currentPartIndex = currentPartIndex,
            )
        }
    }

    private fun restorePlaybackQueue(snapshot: PlaybackQueueSnapshot) {
        if (!queueState.restore(snapshot)) return
        val restoredTrack = queueState.mainQueue.getOrNull(queueState.mainQueueIndex)
        val current = playbackState.value
        val preserveRestoredSession = restoredTrack != null && current.currentTrack?.id == restoredTrack.id
        if (preserveRestoredSession) {
            playbackParts = current.playbackParts
            currentPartIndex = current.currentPartIndex
        } else {
            playbackParts = emptyList()
            currentPartIndex = -1
        }
        updatePlaybackState {
            it.copy(
                currentTrack = restoredTrack,
                resolvedSource = null,
                queue = queueState.displayQueue(),
                queueIndex = queueState.displayQueueIndex(),
                playbackParts = playbackParts,
                currentPartIndex = currentPartIndex,
            )
        }
    }

    private fun persistPlaybackQueue() {
        val snapshot = queueState.snapshot()
        scope.launch { playbackQueueStore.save(snapshot) }
    }

    private fun recoverPlaybackEngineError(engineState: PlaybackState) {
        val failedTrack = engineState.currentTrack?.logicalPlaybackTrack() ?: queueState.currentTrack() ?: return
        val activeTrackId = queueState.currentTrack()?.id ?: playbackState.value.currentTrack?.id
        if (activeTrackId != null && activeTrackId != failedTrack.id) return
        val errorMessage = engineState.errorMessage.orEmpty()
        val recoveryKey = "$playRequestSerial:${failedTrack.id}:$errorMessage"
        if (lastRecoveredPlaybackErrorKey == recoveryKey) return
        lastRecoveredPlaybackErrorKey = recoveryKey
        if (!shouldRecoverPlaybackEngineError(failedTrack, errorMessage)) return
        val playableCount = queueState.upNextQueue.size + queueState.mainQueue.size
        if (playableCount <= 1 || queueState.repeatMode == RepeatMode.SINGLE) return
        queueState.updateCurrentTrack(failedTrack.copy(isUnavailable = true))
        updatePlaybackQueueState()
        persistPlaybackQueue()
        if (queueState.currentIsUpNext) {
            queueState.currentUpNextTrack = null
            queueState.currentIsUpNext = false
            persistPlaybackQueue()
        }
        if (queueState.upNextQueue.isNotEmpty()) {
            queueOwner.playUpNextIndex(0)
            return
        }
        val nextIndex = queueState.mainQueueIndex + 1
        if (nextIndex < queueState.mainQueue.size) {
            queueOwner.playMainIndex(nextIndex)
        } else if (queueState.repeatMode == RepeatMode.QUEUE) {
            queueOwner.playMainIndex(0)
        }
    }

    private fun shouldRecoverPlaybackEngineError(track: MusicTrack, errorMessage: String): Boolean {
        if (track.sourceType != TrackSourceType.Provider) return false
        return when (currentSettings().unavailablePlaybackPolicy) {
            UnavailablePlaybackPolicy.Skip -> true
            UnavailablePlaybackPolicy.SmartReplace -> errorMessage.isMediaNotFoundMessage()
        }
    }

    private fun skipUnavailableTrack(
        track: MusicTrack,
        skippedUnavailableCount: Int,
        throwable: Throwable,
    ): Boolean {
        if (!shouldSkipUnavailable(throwable)) return false
        queueState.updateCurrentTrack(track.logicalPlaybackTrack().copy(isUnavailable = true))
        updatePlaybackQueueState()
        persistPlaybackQueue()
        val playableCount = queueState.upNextQueue.size + queueState.mainQueue.size
        if (playableCount <= 1 || skippedUnavailableCount >= playableCount) return false
        if (queueState.upNextQueue.isNotEmpty()) {
            queueOwner.playUpNextIndex(0)
        } else {
            val nextIndex = queueState.mainQueueIndex + 1
            if (nextIndex < queueState.mainQueue.size) {
                queueOwner.playMainIndex(nextIndex, skippedUnavailableCount + 1)
            } else if (queueState.repeatMode == RepeatMode.QUEUE) {
                queueOwner.playMainIndex(0, skippedUnavailableCount + 1)
            } else {
                return false
            }
        }
        return true
    }

    private fun shouldSkipUnavailable(throwable: Throwable): Boolean =
        throwable.isMediaNotFound() &&
            (currentSettings().unavailablePlaybackPolicy == UnavailablePlaybackPolicy.Skip ||
                currentSettings().unavailablePlaybackPolicy == UnavailablePlaybackPolicy.SmartReplace)

    private fun playbackFailureMessage(throwable: Throwable): String =
        providerRepository.failureMessage(throwable, "播放失败")

    private fun publishPlaybackError(message: String) {
        updatePlaybackState { it.copy(status = PlayerStatus.Error, errorMessage = message) }
    }

    private fun updatePlaybackState(transform: (PlaybackState) -> PlaybackState) {
        mutablePlaybackState.value = transform(mutablePlaybackState.value)
    }

    private fun Throwable.isMediaNotFound(): Boolean {
        val messages = mutableListOf<String>()
        var current: Throwable? = this
        while (current != null) {
            current.message?.let(messages::add)
            current = current.cause
        }
        return messages.joinToString(" ").isMediaNotFoundMessage()
    }

    private fun String.isMediaNotFoundMessage(): Boolean =
        contains("media not found", ignoreCase = true) || contains("MediaNotFound", ignoreCase = true)
}
