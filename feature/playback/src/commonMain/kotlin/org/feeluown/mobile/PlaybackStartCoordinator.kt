package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val PLAYBACK_START_PLAN_LOOKAHEAD = 8

enum class PlaybackStartReason {
    USER_SELECTION,
    PLAYLIST_REPLACE,
    SOURCE_SWITCH,
    AUTO_NEXT,
    RESUME,
    RESTORE_SESSION,
    RECOVERY,
    ;

    val isActiveSelection: Boolean
        get() = this == USER_SELECTION || this == PLAYLIST_REPLACE

    val shouldDiscardLiveSession: Boolean
        get() = isActiveSelection || this == SOURCE_SWITCH

    val mayResumePausedSession: Boolean
        get() = this == RESUME || this == RESTORE_SESSION
}

/** Stable identity for one playback start from intent through engine generation. */
data class PlaybackTransaction(
    val id: Long,
    val reason: PlaybackStartReason,
    val targetTrackId: String,
)

/** Optional engine capability for distinguishing a fresh selection from session resume. */
interface PlaybackStartReasonAwareEngine {
    fun prepareLoading(track: MusicTrack, reason: PlaybackStartReason)
}

data class PlaybackStartFailure(
    val trackId: String,
    val message: String,
)

interface PlaybackStartFailureSource {
    val startFailure: StateFlow<PlaybackStartFailure?>
}

/** Owns the prepare -> resolve/plan -> engine start pipeline. */
internal class PlaybackStartCoordinator(
    private val queue: PlaybackQueueController,
    private val playbackEngine: PlaybackEngine,
    private val playbackRepository: ProviderPlaybackRepository,
    private val scope: CoroutineScope,
    private val currentPlaybackState: () -> PlaybackState,
    private val publishPlaybackState: (PlaybackState) -> Unit,
    /** Builds the physical resolver input without changing logical queue identity. */
    private val prepareTrack: (MusicTrack) -> MusicTrack,
    private val unavailablePlaybackPolicy: () -> UnavailablePlaybackPolicy,
    private val smartReplacementProviderIds: () -> Set<String>,
    private val smartReplacementMinScore: () -> Double,
    private val nextRequestSerial: () -> Long,
    private val currentRequestSerial: () -> Long,
    private val playbackParts: () -> List<PlaybackPart>,
    private val setPlaybackParts: (List<PlaybackPart>) -> Unit,
    private val currentPartIndex: () -> Int,
    private val setCurrentPartIndex: (Int) -> Unit,
    private val prepareSleepTimer: (String) -> Unit,
    private val resetLyricsForPlaybackRequest: () -> Unit,
    private val maybeLoadLyrics: (MusicTrack?) -> Unit,
    private val persistQueue: () -> Unit,
    private val setLoading: (Boolean) -> Unit,
    private val setMessage: (String) -> Unit,
    private val failureMessage: (Throwable) -> String,
    private val onRequestStarted: (Long, Boolean) -> Unit,
    private val onManualSelectionStarted: (Long, MusicTrack, SmartReplacementSelection, MusicTrack?) -> Unit,
    private val onStartFailure: (Long, MusicTrack, Int, SmartReplacementSelection?, Throwable) -> Unit,
    private val prefetchQueue: () -> Unit,
) : PlaybackStartFailureSource {
    private val _startFailure = MutableStateFlow<PlaybackStartFailure?>(null)
    override val startFailure: StateFlow<PlaybackStartFailure?> = _startFailure.asStateFlow()

    fun start(
        track: MusicTrack,
        skippedUnavailableCount: Int = 0,
        requestedPartIndex: Int? = null,
        manualSelection: SmartReplacementSelection? = null,
        rollbackTrack: MusicTrack? = null,
        messageAfterStart: String? = null,
        suppressPlaybackRecovery: Boolean = false,
    ) {
        val logicalTrack = track.logicalPlaybackTrack()
        prepareSleepTimer(logicalTrack.id)
        val preparedResolveTrack = when {
            manualSelection != null -> logicalTrack.withReplacementSelection(manualSelection)
            suppressPlaybackRecovery && track.isSmartReplacement -> track
            else -> prepareTrack(logicalTrack)
        }
        val transaction = when {
            manualSelection != null -> queue.beginPlaybackTransaction(
                trackId = logicalTrack.id,
                reason = PlaybackStartReason.SOURCE_SWITCH,
                recordPlaybackStart = false,
            )
            suppressPlaybackRecovery -> queue.beginPlaybackTransaction(
                trackId = logicalTrack.id,
                reason = PlaybackStartReason.RECOVERY,
                recordPlaybackStart = false,
            )
            else -> queue.activePlaybackTransaction()
                ?.takeIf { it.targetTrackId == logicalTrack.id }
                ?: queue.beginPlaybackTransaction(
                    trackId = logicalTrack.id,
                    reason = PlaybackStartReason.RECOVERY,
                    recordPlaybackStart = false,
                )
        }
        val startReason = transaction.reason
        val serial = nextRequestSerial()
        _startFailure.value = null
        onRequestStarted(serial, suppressPlaybackRecovery)
        manualSelection?.let { selection ->
            onManualSelectionStarted(serial, logicalTrack, selection, rollbackTrack)
        }

        val previousPlaybackState = currentPlaybackState()
        val isManualSourceSwitch = manualSelection != null &&
            previousPlaybackState.currentTrack?.id == logicalTrack.id
        val preservedLyrics = previousPlaybackState.lyrics
            ?.takeIf { isManualSourceSwitch && it.isNotBlank() }

        val existingParts = playbackParts()
        val isPlaybackPartRequest = requestedPartIndex != null && existingParts.isNotEmpty()
        if (!isPlaybackPartRequest) {
            setPlaybackParts(emptyList())
            setCurrentPartIndex(-1)
        }
        queue.updateCurrentTrack(logicalTrack)
        if (!isManualSourceSwitch) {
            resetLyricsForPlaybackRequest()
        }

        // Establish the platform playback transaction before publishing the new queue/current-track
        // overlay. Android can still hold a restored paused Media3 session at this point; publishing
        // B first lets runtime observers republish stale A before the engine has invalidated it.
        // prepareLoading() is therefore the transaction boundary: after it returns, fresh starts
        // have invalidated the old live session and stale service state is generation-gated.
        val reasonAwareEngine = playbackEngine as? PlaybackStartReasonAwareEngine
        if (reasonAwareEngine != null) {
            reasonAwareEngine.prepareLoading(logicalTrack, startReason)
        } else {
            playbackEngine.prepareLoading(logicalTrack)
        }

        publishPlaybackState(
            currentPlaybackState().copy(
                status = PlayerStatus.Loading,
                currentTrack = logicalTrack,
                resolvedSource = null,
                queue = queue.displayQueue(),
                queueIndex = queue.displayQueueIndex(),
                positionMs = 0,
                playbackParts = playbackParts(),
                currentPartIndex = requestedPartIndex.takeIf { isPlaybackPartRequest } ?: -1,
                lyrics = preservedLyrics ?: logicalTrack.lyrics,
                playbackGeneration = transaction.id,
                errorMessage = null,
            )
        )
        persistQueue()
        setLoading(true)
        setMessage(messageAfterStart ?: "正在播放：${logicalTrack.title}")

        val resolveTrack = requestedPartIndex
            ?.let { index -> playbackParts().getOrNull(index) }
            ?.toTrack(preparedResolveTrack)
            ?: preparedResolveTrack
        maybeLoadLyrics(logicalTrack)

        if (!playbackEngine.resolvesResourcesInternally) {
            resolveAndStart(
                serial = serial,
                transaction = transaction,
                logicalTrack = logicalTrack,
                resolveTrack = resolveTrack,
                skippedUnavailableCount = skippedUnavailableCount,
                requestedPartIndex = requestedPartIndex,
                manualSelection = manualSelection,
                messageAfterStart = messageAfterStart,
            )
            return
        }

        playbackEngine.play(
            PlaybackPlan(
                generation = transaction.id,
                requests = buildList {
                    add(
                        PlaybackRequest(
                            track = logicalTrack,
                            resolveTrack = resolveTrack,
                            requestedPartIndex = requestedPartIndex,
                            unavailablePolicy = unavailablePlaybackPolicy(),
                            smartReplacementProviderIds = smartReplacementProviderIds(),
                            smartReplacementMinScore = smartReplacementMinScore(),
                            smartReplacementUseOriginalMetadata = true,
                            smartReplacementUseOriginalLyrics = true,
                            resolveOnlySelectedReplacement = manualSelection != null,
                        )
                    )
                    queue.displayQueue()
                        .drop(1)
                        .take(PLAYBACK_START_PLAN_LOOKAHEAD)
                        .forEach { queuedTrack ->
                            val logicalQueuedTrack = queuedTrack.logicalPlaybackTrack()
                            add(
                                PlaybackRequest(
                                    track = logicalQueuedTrack,
                                    resolveTrack = prepareTrack(logicalQueuedTrack),
                                    unavailablePolicy = unavailablePlaybackPolicy(),
                                    smartReplacementProviderIds = smartReplacementProviderIds(),
                                    smartReplacementMinScore = smartReplacementMinScore(),
                                    smartReplacementUseOriginalMetadata = true,
                                    smartReplacementUseOriginalLyrics = true,
                                )
                            )
                        }
                },
            )
        )
    }

    private fun resolveAndStart(
        serial: Long,
        transaction: PlaybackTransaction,
        logicalTrack: MusicTrack,
        resolveTrack: MusicTrack,
        skippedUnavailableCount: Int,
        requestedPartIndex: Int?,
        manualSelection: SmartReplacementSelection?,
        messageAfterStart: String?,
    ) {
        scope.launch playRequest@{
            runCatching {
                val payload = resolveTrack.toLocalPayload()
                    ?: if (manualSelection != null) {
                        playbackRepository.resolveSelectedReplacement(
                            resolveTrack,
                            true,
                            true,
                            smartReplacementProviderIds(),
                        )
                    } else {
                        playbackRepository.resolve(
                            resolveTrack,
                            unavailablePlaybackPolicy(),
                            smartReplacementProviderIds(),
                            smartReplacementMinScore(),
                            true,
                            true,
                        )
                    }
                if (serial != currentRequestSerial()) return@playRequest
                if (queue.activePlaybackTransaction()?.id != transaction.id) return@playRequest
                // The legacy direct-resolution path cleared recovery suppression once
                // media resolution succeeded, before engine playback started.
                onRequestStarted(serial, false)

                val nextParts = payload.parts
                val nextPartIndex = when {
                    nextParts.isEmpty() -> -1
                    payload.currentPartIndex in nextParts.indices -> payload.currentPartIndex
                    requestedPartIndex != null && requestedPartIndex in nextParts.indices -> requestedPartIndex
                    else -> -1
                }
                setPlaybackParts(nextParts)
                setCurrentPartIndex(nextPartIndex)
                val resolvedSource = payload.toResolvedPlaybackSource(
                    logicalTrack = logicalTrack,
                    resolveTrack = resolveTrack,
                    selectedReplacement = manualSelection != null,
                )
                val sourceAwareEngine = playbackEngine as? ResolvedPlaybackSourceAwareEngine
                if (sourceAwareEngine != null) {
                    sourceAwareEngine.playResolved(
                        logicalTrack = logicalTrack,
                        resolveTrack = resolveTrack,
                        payload = payload,
                    )
                } else {
                    playbackEngine.play(logicalTrack, payload)
                }
                publishPlaybackState(
                    currentPlaybackState().copy(
                        status = PlayerStatus.Loading,
                        currentTrack = logicalTrack,
                        resolvedSource = resolvedSource,
                        queue = queue.displayQueue(),
                        queueIndex = queue.displayQueueIndex(),
                        lyrics = payload.lyrics?.takeIf { it.isNotBlank() } ?: currentPlaybackState().lyrics,
                        audioQuality = payload.audioQuality,
                        playbackParts = playbackParts(),
                        currentPartIndex = currentPartIndex(),
                        playbackGeneration = transaction.id,
                    )
                )
                maybeLoadLyrics(logicalTrack)
                persistQueue()
                setMessage(
                    messageAfterStart
                        ?: currentPlaybackPartLabel(logicalTrack)
                        ?: "${logicalTrack.title} - ${logicalTrack.artists}"
                )
                prefetchQueue()
            }.onFailure { throwable ->
                if (serial == currentRequestSerial() && queue.activePlaybackTransaction()?.id == transaction.id) {
                    _startFailure.value = PlaybackStartFailure(
                        trackId = logicalTrack.id,
                        message = failureMessage(throwable),
                    )
                    onStartFailure(
                        serial,
                        logicalTrack,
                        skippedUnavailableCount,
                        manualSelection,
                        throwable,
                    )
                }
            }
            if (serial == currentRequestSerial() && queue.activePlaybackTransaction()?.id == transaction.id) {
                setLoading(false)
            }
        }
    }

    private fun currentPlaybackPartLabel(track: MusicTrack): String? {
        val index = currentPartIndex()
        val part = playbackParts().getOrNull(index) ?: return null
        return "${track.title} · 第 ${index + 1}P · ${part.title.ifBlank { "未命名分段" }}"
    }
}

private fun MusicTrack.toLocalPayload(): PlaybackPayload? {
    val uri = localUri ?: return null
    return PlaybackPayload(
        url = uri,
        title = title,
        artists = artists,
        album = album,
        source = source,
        coverUrl = coverUrl,
        durationMs = durationMs,
        lyrics = lyrics,
        audioQuality = null,
        providerName = providerName,
        isSmartReplacement = isSmartReplacement,
        originalId = originalId,
        originalTitle = originalTitle,
        originalArtists = originalArtists,
        originalAlbum = originalAlbum,
        originalSource = originalSource,
        originalProviderName = originalProviderName,
        originalCoverUrl = originalCoverUrl,
        replacementId = replacementId,
        replacementTitle = replacementTitle,
        replacementArtists = replacementArtists,
        replacementAlbum = replacementAlbum,
        replacementSource = replacementSource,
        replacementProviderName = replacementProviderName,
        replacementCoverUrl = replacementCoverUrl,
        replacementStrategy = replacementStrategy,
        replacementScore = replacementScore,
    )
}

private fun PlaybackPart.toTrack(parent: MusicTrack): MusicTrack = parent.copy(
    id = id,
    title = title.ifBlank { parent.title },
    durationMs = durationMs ?: parent.durationMs,
    providerId = id,
)
