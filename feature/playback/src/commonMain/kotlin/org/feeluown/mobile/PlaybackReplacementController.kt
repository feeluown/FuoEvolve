package org.feeluown.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Owns manual smart-replacement discovery/selection for the now-playing surface. */
internal class PlaybackReplacementController(
    private val playbackRepository: ProviderPlaybackRepository,
    private val scope: CoroutineScope,
    private val smartReplacementProviderIds: () -> Set<String>,
    private val smartReplacementMinScore: () -> Double,
    private val currentTrack: () -> MusicTrack?,
    private val currentResolvedSource: () -> ResolvedPlaybackSource?,
    private val startManualReplacement: (MusicTrack, SmartReplacementSelection, MusicTrack) -> Unit,
    private val closePlayer: () -> Unit,
    private val openTrackDetail: (MusicTrack) -> Unit,
    private val failureMessage: (Throwable, String, String?) -> String,
) : ReplacementActionPort {
    private val mutableReplacementCandidateState = MutableStateFlow(ReplacementCandidateState())
    override val replacementCandidateStateFlow: StateFlow<ReplacementCandidateState> =
        mutableReplacementCandidateState.asStateFlow()
    override val replacementCandidateState: ReplacementCandidateState
        get() = mutableReplacementCandidateState.value

    private var candidatesJob: Job? = null

    override fun loadReplacementCandidates(track: MusicTrack) {
        val logicalTrack = track.logicalPlaybackTrack()
        val trackId = logicalTrack.id
        candidatesJob?.cancel()
        mutableReplacementCandidateState.value = ReplacementCandidateState(
            trackId = trackId,
            isLoading = true,
        )
        candidatesJob = scope.launch {
            runCatching {
                withTimeout(30_000) {
                    playbackRepository.replacementCandidates(
                        track = logicalTrack,
                        smartReplacementProviderIds = smartReplacementProviderIds(),
                        smartReplacementMinScore = smartReplacementMinScore(),
                    )
                }
            }.onSuccess { candidates ->
                if (replacementCandidateState.trackId == trackId) {
                    mutableReplacementCandidateState.value = ReplacementCandidateState(
                        trackId = trackId,
                        candidates = candidates
                            .sortedByDescending { candidate -> candidate.score }
                            .distinctBy { candidate -> candidate.track.id },
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) return@onFailure
                if (replacementCandidateState.trackId == trackId) {
                    mutableReplacementCandidateState.value = ReplacementCandidateState(
                        trackId = trackId,
                        errorMessage = failureMessage(throwable, "查询失败", logicalTrack.source),
                    )
                }
            }
        }
    }

    override fun selectReplacementCandidate(track: MusicTrack, candidate: ReplacementCandidate) {
        val previousLogicalTrack = currentTrack()?.logicalPlaybackTrack() ?: return
        val previousResolvedSource = currentResolvedSource()
        val rollbackTrack = if (previousResolvedSource?.isReplacement == true) {
            previousLogicalTrack.withReplacementSelection(previousResolvedSource.toSmartReplacementSelection())
        } else {
            previousLogicalTrack
        }
        val logicalTrack = track.logicalPlaybackTrack()
        val selection = candidate.toSmartReplacementSelection()
        mutableReplacementCandidateState.value = replacementCandidateState.copy(isLoading = false)
        startManualReplacement(
            logicalTrack,
            selection,
            rollbackTrack,
        )
    }

    override fun openReplacementTrackDetail(track: MusicTrack) {
        val logicalTrack = track.logicalPlaybackTrack()
        val source = currentResolvedSource()
            ?.takeIf { it.isReplacement }
            ?: return
        closePlayer()
        openTrackDetail(source.toNavigationTrack(logicalTrack))
    }
}

internal fun ReplacementCandidate.toSmartReplacementSelection(): SmartReplacementSelection =
    SmartReplacementSelection(
        replacementId = track.id,
        replacementTitle = track.title,
        replacementArtists = track.artists,
        replacementAlbum = track.album,
        replacementSource = track.source,
        replacementProviderName = track.providerName,
        replacementCoverUrl = track.coverUrl,
        replacementDurationMs = track.durationMs,
        replacementScore = score,
    )

private fun ResolvedPlaybackSource.toSmartReplacementSelection(): SmartReplacementSelection =
    SmartReplacementSelection(
        replacementId = trackId,
        replacementTitle = title,
        replacementArtists = artists,
        replacementAlbum = album,
        replacementSource = source,
        replacementProviderName = providerName,
        replacementCoverUrl = coverUrl,
        replacementDurationMs = durationMs,
        replacementScore = replacementScore ?: 0.0,
    )

/**
 * Compatibility resolver input for providers that still consume replacement metadata on MusicTrack.
 * The returned value must never be stored in PlaybackQueueController or exposed as currentTrack.
 */
internal fun MusicTrack.withReplacementSelection(selection: SmartReplacementSelection): MusicTrack {
    val logicalTrack = logicalPlaybackTrack()
    return logicalTrack.copy(
        providerId = logicalTrack.providerId ?: logicalTrack.id,
        sourceType = TrackSourceType.Provider,
        localUri = null,
        isSmartReplacement = true,
        originalId = logicalTrack.id,
        originalTitle = logicalTrack.title,
        originalArtists = logicalTrack.artists,
        originalAlbum = logicalTrack.album,
        originalSource = logicalTrack.source,
        originalProviderName = logicalTrack.providerName,
        originalCoverUrl = logicalTrack.coverUrl,
        replacementId = selection.replacementId,
        replacementTitle = selection.replacementTitle,
        replacementArtists = selection.replacementArtists,
        replacementAlbum = selection.replacementAlbum,
        replacementSource = selection.replacementSource,
        replacementProviderName = selection.replacementProviderName,
        replacementCoverUrl = selection.replacementCoverUrl,
        replacementStrategy = "user_selected",
        replacementScore = selection.replacementScore,
        isUnavailable = false,
    )
}

/** Reconstructs the provider-native logical track from legacy replacement-decorated state. */
fun MusicTrack.originalDetailTrackForNavigation(): MusicTrack = logicalPlaybackTrack()

/** Legacy compatibility helper; new playback UI should use PlaybackState.resolvedSource. */
internal fun MusicTrack.replacementDetailTrackForNavigation(): MusicTrack? {
    val detailId = replacementId?.takeIf { it.isNotBlank() } ?: return null
    val detailSource = replacementSource
        ?: detailId.substringBefore(':').takeIf { it.isNotBlank() }
        ?: return null
    return MusicTrack(
        id = detailId,
        title = replacementTitle ?: title,
        artists = replacementArtists ?: artists,
        album = replacementAlbum ?: album,
        source = detailSource,
        sourceType = TrackSourceType.Provider,
        coverUrl = replacementCoverUrl ?: coverUrl,
        providerId = detailId,
        providerName = replacementProviderName ?: detailSource,
    )
}
