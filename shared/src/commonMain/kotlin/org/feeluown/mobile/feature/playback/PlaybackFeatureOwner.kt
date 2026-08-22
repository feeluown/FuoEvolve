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
import org.feeluown.mobile.provider.core.network.currentTimeMillis

private const val PLAYBACK_OWNER_DYNAMIC_QUEUE_PREFETCH_REMAINING = 2

private data class PlaybackOwnerPendingManualReplacement(
    val requestSerial: Long,
    val previousTrack: MusicTrack?,
    val originalTrackId: String,
    val selection: SmartReplacementSelection,
)

/**
 * Playback-scoped composition boundary.
 *
 * This owner only coordinates playback state/policy. App navigation, provider catalog/content,
 * settings UI, downloads and playlist mutations stay in their dedicated feature owners.
 */
interface PlaybackFeatureOwner {
    val playbackState: StateFlow<PlaybackState>
    val transport: PlaybackTransportCoordinator
    val startFailureSource: PlaybackStartFailureSource
    val navigation: PlaybackNavigationPort
    val sleepTimer: PlaybackSleepTimerPort
    val replacement: ReplacementActionPort

    fun updateTrackCopies(trackId: String, updatedTrack: MusicTrack)
}

fun createPlaybackFeatureOwner(
    providerRepository: ProviderMusicRepository,
    playbackEngine: PlaybackEngine,
    playbackQueueStore: PlaybackQueueStore,
    settingsRepository: AppSettingsRepository,
    downloadActions: DownloadActionPort,
    scope: CoroutineScope,
    openTrackDetail: (MusicTrack) -> Unit,
    nowMillis: () -> Long = ::currentTimeMillis,
): PlaybackFeatureOwner = DefaultPlaybackFeatureOwner(
    providerRepository = providerRepository,
    playbackEngine = playbackEngine,
    playbackQueueStore = playbackQueueStore,
    settingsRepository = settingsRepository,
    downloadActions = downloadActions,
    scope = scope,
    openTrackDetail = openTrackDetail,
    nowMillis = nowMillis,
)

private class DefaultPlaybackFeatureOwner(
    private val providerRepository: ProviderMusicRepository,
    private val playbackEngine: PlaybackEngine,
    private val playbackQueueStore: PlaybackQueueStore,
    private val settingsRepository: AppSettingsRepository,
    private val downloadActions: DownloadActionPort,
    private val scope: CoroutineScope,
    private val openTrackDetail: (MusicTrack) -> Unit,
    nowMillis: () -> Long,
) : PlaybackFeatureOwner {
    private val queueState = PlaybackQueueController()
    private val navigationOwner = DefaultPlaybackNavigationPort()
    private val playbackRepository: ProviderPlaybackRepository = ProviderPlaybackRepositoryView(providerRepository)
    private val mutablePlaybackState = MutableStateFlow(PlaybackState())
    private val playbackFeedback = MutableStateFlow<String?>(null)
    override val playbackState: StateFlow<PlaybackState> = mutablePlaybackState.asStateFlow()

    private var playbackParts: List<PlaybackPart> = emptyList()
    private var currentPartIndex = -1
    private var playRequestSerial = 0L
    private var lastRecoveredPlaybackErrorKey: String? = null
    private var smartReplacementSelections: Map<String, SmartReplacementSelection> = emptyMap()
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
        providerRepository = providerRepository,
        scope = scope,
        currentRequestSerial = { playRequestSerial },
        currentTrackId = { queueState.currentTrack()?.id ?: playbackState.value.currentTrack?.id },
        currentLyrics = { playbackState.value.lyrics },
        updateLyrics = { lyrics -> updatePlaybackState { it.copy(lyrics = lyrics) } },
    )

    private val startOwner = PlaybackStartCoordinator(
        queue = queueState,
        playbackEngine = playbackEngine,
        playbackRepository = playbackRepository,
        scope = scope,
        currentPlaybackState = { playbackState.value },
        publishPlaybackState = { mutablePlaybackState.value = it },
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
                originalTrackId = playbackTrack.originalId ?: playbackTrack.id,
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
        startManualReplacement = { track, selection, rollbackTrack ->
            startPlayback(
                track = track,
                manualSelection = selection,
                rollbackTrack = rollbackTrack,
            )
        },
        closePlayer = navigationOwner::closeFullPlayer,
        openTrackDetail = openTrackDetail,
        failureMessage = { throwable, fallback, providerId ->
            throwable.providerFailureOrNull(providerId)?.userMessage
                ?: throwable.message
                ?: fallback
        },
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
    override val replacement: ReplacementActionPort = replacementOwner

    init {
        scope.launch {
            val settings = settingsRepository.awaitSettings()
            smartReplacementSelections = settings.smartReplacementSelections
            runCatching { playbackQueueStore.load() }
                .onSuccess(::restorePlaybackQueue)
        }
        scope.launch {
            settingsRepository.state.collect { state ->
                if (state.isLoaded) smartReplacementSelections = state.settings.smartReplacementSelections
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
                    currentTrack = updatedTrack,
                    lyrics = updatedTrack.lyrics ?: it.lyrics,
                )
            }
        }
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    private fun onEngineState(engineState: PlaybackState) {
        engineState.currentTrack?.let(::synchronizePlaybackTrack)
        val currentQueueTrackId = queueState.currentTrack()?.id
        val endAction = lifecycleOwner.evaluate(engineState, currentQueueTrackId)
        if (engineState.status == PlayerStatus.Playing) lastRecoveredPlaybackErrorKey = null

        val previous = playbackState.value
        mutablePlaybackState.value = engineState.copy(
            queue = queueState.displayQueue(),
            queueIndex = queueState.displayQueueIndex(),
            currentTrack = queueState.currentTrack() ?: engineState.currentTrack,
            playbackParts = engineState.playbackParts.ifEmpty { playbackParts },
            currentPartIndex = engineState.currentPartIndex.takeIf { it >= 0 } ?: currentPartIndex,
            lyrics = lyricsOwner.mergedLyrics(
                engineState = engineState,
                currentQueueTrackId = currentQueueTrackId,
                previousPlaybackState = previous,
            ),
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
                    recoverPlaybackEngineError(engineState)
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

    private fun currentSettings(): AppSettings = settingsRepository.state.value.settings

    private fun selectedSmartReplacementProviderIds(): Set<String> {
        val settings = currentSettings()
        val enabled = settings.enabledProviderIds.ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS }
        return settings.smartReplacementProviderIds.intersect(enabled).ifEmpty { enabled }
    }

    private fun MusicTrack.preferDownloaded(): MusicTrack {
        if (isSmartReplacement) return this
        val downloaded = downloadActions.downloadStates[id] as? DownloadState.Downloaded ?: return this
        return copy(
            sourceType = TrackSourceType.Downloaded,
            localUri = downloaded.uri,
            providerId = providerId ?: id,
        )
    }

    private fun MusicTrack.withRememberedReplacement(): MusicTrack {
        val originalTrack = originalDetailTrackForNavigation()
        val selection = smartReplacementSelections[originalTrack.id] ?: return this
        if (selection.replacementSource !in selectedSmartReplacementProviderIds()) {
            return if (isSmartReplacement) originalTrack else this
        }
        return originalTrack.withReplacementSelection(selection)
    }

    private fun commitManualReplacementIfReady(engineState: PlaybackState) {
        val pending = pendingManualReplacementSwitch ?: return
        if (pending.requestSerial != playRequestSerial) return
        val currentTrack = engineState.currentTrack ?: queueState.currentTrack() ?: return
        val currentOriginalId = currentTrack.originalId ?: currentTrack.id
        if (currentOriginalId != pending.originalTrackId || currentTrack.replacementId != pending.selection.replacementId) return
        smartReplacementSelections = smartReplacementSelections + (pending.originalTrackId to pending.selection)
        pendingManualReplacementSwitch = null
        val nextSelections = smartReplacementSelections
        scope.launch {
            settingsRepository.update { current -> current.copy(smartReplacementSelections = nextSelections) }
        }
    }

    private fun rollbackManualReplacement(requestSerial: Long): Boolean {
        val pending = pendingManualReplacementSwitch?.takeIf { it.requestSerial == requestSerial } ?: return false
        pendingManualReplacementSwitch = null
        val previousTrack = pending.previousTrack ?: return true
        startOwner.start(
            track = previousTrack,
            messageAfterStart = "手动换源失败，已恢复原播放源：${previousTrack.title}",
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
            playbackFeedback.value = throwable.providerFailureOrNull(feature.providerId)?.userMessage
                ?: throwable.message
                ?: "${feature.title} 加载后续歌曲失败"
            FEATURE_QUEUE_APPEND_FAILED
        }
    }

    private fun synchronizePlaybackTrack(track: MusicTrack) {
        val current = queueState.currentTrack()
        var changed = current != track
        if (current?.id != track.id) {
            val upNextIndex = queueState.upNextQueue.indexOfFirst { it.id == track.id }
            if (upNextIndex >= 0) {
                queueState.currentUpNextTrack = queueState.upNextQueue[upNextIndex]
                queueState.upNextQueue = queueState.upNextQueue.filterIndexed { index, _ -> index != upNextIndex }
                queueState.currentIsUpNext = true
            } else {
                val mainIndex = queueState.mainQueue.indexOfFirst { it.id == track.id }
                if (mainIndex >= 0) {
                    queueState.mainQueueIndex = mainIndex
                    queueState.currentUpNextTrack = null
                    queueState.currentIsUpNext = false
                    changed = true
                }
            }
        }
        queueState.updateCurrentTrack(track)
        if (changed) persistPlaybackQueue()
    }

    private fun updatePlaybackQueueState() {
        updatePlaybackState { current ->
            current.copy(
                queue = queueState.displayQueue(),
                queueIndex = queueState.displayQueueIndex(),
                currentTrack = queueState.currentTrack() ?: current.currentTrack,
                playbackParts = playbackParts,
                currentPartIndex = currentPartIndex,
            )
        }
    }

    private fun restorePlaybackQueue(snapshot: PlaybackQueueSnapshot) {
        if (!queueState.restore(snapshot)) return
        playbackParts = emptyList()
        currentPartIndex = -1
        updatePlaybackState { current ->
            current.copy(
                currentTrack = queueState.mainQueue.getOrNull(queueState.mainQueueIndex),
                queue = queueState.displayQueue(),
                queueIndex = queueState.displayQueueIndex(),
                playbackParts = emptyList(),
                currentPartIndex = -1,
            )
        }
    }

    private fun persistPlaybackQueue() {
        val snapshot = queueState.snapshot()
        scope.launch { playbackQueueStore.save(snapshot) }
    }

    private fun recoverPlaybackEngineError(engineState: PlaybackState) {
        val failedTrack = engineState.currentTrack ?: queueState.currentTrack() ?: return
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
        queueState.updateCurrentTrack(track.copy(isUnavailable = true))
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
        throwable.providerFailureOrNull()?.userMessage
            ?: throwable.message
            ?: throwable::class.simpleName.orEmpty().ifBlank { "播放失败" }

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
