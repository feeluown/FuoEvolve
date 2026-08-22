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
    AUTO_NEXT,
    RESUME,
    RESTORE_SESSION,
    ;

    val isActiveSelection: Boolean
        get() = this == USER_SELECTION || this == PLAYLIST_REPLACE

    val mayResumePausedSession: Boolean
        get() = this == RESUME || this == RESTORE_SESSION
}

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
        val startReason = queue.consumePlaybackStartReason()
        prepareSleepTimer(track.id)
        val serial = nextRequestSerial()
        _startFailure.value = null
        onRequestStarted(serial, suppressPlaybackRecovery)
        val playbackTrack = if (manualSelection != null) track else prepareTrack(track)
        manualSelection?.let { selection ->
            onManualSelectionStarted(serial, playbackTrack, selection, rollbackTrack)
        }

        val existingParts = playbackParts()
        val isPlaybackPartRequest = requestedPartIndex != null && existingParts.isNotEmpty()
        if (!isPlaybackPartRequest) {
            setPlaybackParts(emptyList())
            setCurrentPartIndex(-1)
        }
        queue.updateCurrentTrack(playbackTrack)
        resetLyricsForPlaybackRequest()
        publishPlaybackState(
            currentPlaybackState().copy(
                status = PlayerStatus.Loading,
                currentTrack = playbackTrack,
                queue = queue.displayQueue(),
                queueIndex = queue.displayQueueIndex(),
                positionMs = 0,
                playbackParts = playbackParts(),
                currentPartIndex = requestedPartIndex.takeIf { isPlaybackPartRequest } ?: -1,
                lyrics = playbackTrack.lyrics,
                errorMessage = null,
            )
        )
        persistQueue()
        val reasonAwareEngine = playbackEngine as? PlaybackStartReasonAwareEngine
        if (reasonAwareEngine != null) {
            reasonAwareEngine.prepareLoading(playbackTrack, startReason)
        } else {
            playbackEngine.prepareLoading(playbackTrack)
        }
        setLoading(true)
        setMessage(messageAfterStart ?: "正在播放：${track.title}")

        val resolveTrack = requestedPartIndex
            ?.let { index -> playbackParts().getOrNull(index) }
            ?.toTrack(playbackTrack)
            ?: playbackTrack
        maybeLoadLyrics(playbackTrack)

        if (!playbackEngine.resolvesResourcesInternally) {
            resolveAndStart(
                serial = serial,
                playbackTrack = playbackTrack,
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
                generation = serial,
                requests = buildList {
                    add(
                        PlaybackRequest(
                            track = playbackTrack,
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
                            val nextTrack = prepareTrack(queuedTrack)
                            add(
                                PlaybackRequest(
                                    track = nextTrack,
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
        playbackTrack: MusicTrack,
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
                val playableTrack = playbackTrack.withResolvedPayload(payload, manualSelection != null)
                queue.updateCurrentTrack(playableTrack)
                playbackEngine.play(playableTrack, payload)
                publishPlaybackState(
                    currentPlaybackState().copy(
                        status = PlayerStatus.Loading,
                        currentTrack = playableTrack,
                        queue = queue.displayQueue(),
                        queueIndex = queue.displayQueueIndex(),
                        lyrics = payload.lyrics?.takeIf { it.isNotBlank() } ?: currentPlaybackState().lyrics,
                        audioQuality = payload.audioQuality,
                        playbackParts = playbackParts(),
                        currentPartIndex = currentPartIndex(),
                    )
                )
                maybeLoadLyrics(playableTrack)
                persistQueue()
                setMessage(
                    messageAfterStart
                        ?: currentPlaybackPartLabel(playableTrack)
                        ?: "${playableTrack.title} - ${playableTrack.artists}"
                )
                prefetchQueue()
            }.onFailure { throwable ->
                if (serial == currentRequestSerial()) {
                    _startFailure.value = PlaybackStartFailure(
                        trackId = playbackTrack.id,
                        message = failureMessage(throwable),
                    )
                    onStartFailure(
                        serial,
                        playbackTrack,
                        skippedUnavailableCount,
                        manualSelection,
                        throwable,
                    )
                }
            }
            if (serial == currentRequestSerial()) setLoading(false)
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

private fun MusicTrack.withResolvedPayload(
    payload: PlaybackPayload,
    manualSelection: Boolean,
): MusicTrack {
    val isMultipartPlayback = payload.parts.isNotEmpty()
    val isSmartReplacementPlayback = payload.isSmartReplacement || manualSelection
    return copy(
        title = if (isMultipartPlayback) title else payload.title.ifBlank { title },
        artists = payload.artists.ifBlank { artists },
        album = payload.album.ifBlank { album },
        source = if (isSmartReplacementPlayback) {
            payload.originalSource?.takeIf { it.isNotBlank() } ?: source
        } else {
            payload.source.ifBlank { source }
        },
        coverUrl = payload.coverUrl ?: coverUrl,
        durationMs = if (isMultipartPlayback) durationMs else payload.durationMs ?: durationMs,
        providerName = if (isSmartReplacementPlayback) {
            payload.providerName ?: payload.replacementProviderName ?: providerName
        } else {
            payload.providerName ?: providerName
        },
        providerId = if (isSmartReplacementPlayback) payload.originalId ?: providerId else providerId,
        isSmartReplacement = isSmartReplacementPlayback,
        originalId = payload.originalId.takeIf { isSmartReplacementPlayback }
            ?: originalId.takeIf { isSmartReplacementPlayback },
        originalTitle = payload.originalTitle.takeIf { isSmartReplacementPlayback }
            ?: originalTitle.takeIf { isSmartReplacementPlayback },
        originalArtists = payload.originalArtists.takeIf { isSmartReplacementPlayback }
            ?: originalArtists.takeIf { isSmartReplacementPlayback },
        originalAlbum = payload.originalAlbum.takeIf { isSmartReplacementPlayback }
            ?: originalAlbum.takeIf { isSmartReplacementPlayback },
        originalSource = payload.originalSource.takeIf { isSmartReplacementPlayback }
            ?: originalSource.takeIf { isSmartReplacementPlayback },
        originalProviderName = payload.originalProviderName.takeIf { isSmartReplacementPlayback }
            ?: originalProviderName.takeIf { isSmartReplacementPlayback },
        originalCoverUrl = payload.originalCoverUrl.takeIf { isSmartReplacementPlayback }
            ?: originalCoverUrl.takeIf { isSmartReplacementPlayback },
        replacementId = payload.replacementId.takeIf { isSmartReplacementPlayback }
            ?: replacementId.takeIf { isSmartReplacementPlayback },
        replacementTitle = payload.replacementTitle.takeIf { isSmartReplacementPlayback }
            ?: replacementTitle.takeIf { isSmartReplacementPlayback },
        replacementArtists = payload.replacementArtists.takeIf { isSmartReplacementPlayback }
            ?: replacementArtists.takeIf { isSmartReplacementPlayback },
        replacementAlbum = payload.replacementAlbum.takeIf { isSmartReplacementPlayback }
            ?: replacementAlbum.takeIf { isSmartReplacementPlayback },
        replacementSource = payload.replacementSource.takeIf { isSmartReplacementPlayback }
            ?: replacementSource.takeIf { isSmartReplacementPlayback },
        replacementProviderName = payload.replacementProviderName.takeIf { isSmartReplacementPlayback }
            ?: replacementProviderName.takeIf { isSmartReplacementPlayback },
        replacementCoverUrl = payload.replacementCoverUrl.takeIf { isSmartReplacementPlayback }
            ?: replacementCoverUrl.takeIf { isSmartReplacementPlayback },
        replacementStrategy = payload.replacementStrategy.takeIf { isSmartReplacementPlayback }
            ?: replacementStrategy.takeIf { isSmartReplacementPlayback },
        replacementScore = payload.replacementScore.takeIf { isSmartReplacementPlayback }
            ?: replacementScore.takeIf { isSmartReplacementPlayback },
        isUnavailable = false,
    )
}
