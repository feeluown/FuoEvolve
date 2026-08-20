package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Owns manual smart-replacement discovery/selection for the now-playing surface. */
internal class PlaybackReplacementController(
    private val playbackRepository: ProviderPlaybackRepository,
    private val scope: CoroutineScope,
    private val smartReplacementProviderIds: () -> Set<String>,
    private val smartReplacementMinScore: () -> Double,
    private val currentTrack: () -> MusicTrack?,
    private val startManualReplacement: (MusicTrack, SmartReplacementSelection, MusicTrack) -> Unit,
    private val closePlayer: () -> Unit,
    private val openTrackDetail: (MusicTrack) -> Unit,
    private val failureMessage: (Throwable, String, String?) -> String,
) : ReplacementActionPort {
    override var replacementCandidateState by mutableStateOf(ReplacementCandidateState())
        private set

    private var candidatesJob: Job? = null

    override fun loadReplacementCandidates(track: MusicTrack) {
        val originalTrack = track.originalDetailTrackForNavigation()
        val trackId = originalTrack.id
        candidatesJob?.cancel()
        replacementCandidateState = ReplacementCandidateState(
            trackId = trackId,
            isLoading = true,
        )
        candidatesJob = scope.launch {
            runCatching {
                withTimeout(30_000) {
                    playbackRepository.replacementCandidates(
                        track = originalTrack,
                        smartReplacementProviderIds = smartReplacementProviderIds(),
                        smartReplacementMinScore = smartReplacementMinScore(),
                    )
                }
            }.onSuccess { candidates ->
                if (replacementCandidateState.trackId == trackId) {
                    replacementCandidateState = ReplacementCandidateState(
                        trackId = trackId,
                        candidates = candidates
                            .sortedByDescending { candidate -> candidate.score }
                            .distinctBy { candidate -> candidate.track.id },
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) return@onFailure
                if (replacementCandidateState.trackId == trackId) {
                    replacementCandidateState = ReplacementCandidateState(
                        trackId = trackId,
                        errorMessage = failureMessage(throwable, "查询失败", originalTrack.source),
                    )
                }
            }
        }
    }

    override fun selectReplacementCandidate(track: MusicTrack, candidate: ReplacementCandidate) {
        val previousTrack = currentTrack() ?: return
        val originalTrack = track.originalDetailTrackForNavigation()
        val selection = candidate.toSmartReplacementSelection()
        replacementCandidateState = replacementCandidateState.copy(isLoading = false)
        startManualReplacement(
            originalTrack.withReplacementSelection(selection),
            selection,
            previousTrack,
        )
    }

    override fun openReplacementTrackDetail(track: MusicTrack) {
        val detailTrack = track.replacementDetailTrackForNavigation() ?: return
        closePlayer()
        openTrackDetail(detailTrack)
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

internal fun MusicTrack.withReplacementSelection(selection: SmartReplacementSelection): MusicTrack = copy(
    id = id,
    providerId = providerId ?: id,
    sourceType = TrackSourceType.Provider,
    localUri = null,
    isSmartReplacement = true,
    originalId = id,
    originalTitle = title,
    originalArtists = artists,
    originalAlbum = album,
    originalSource = source,
    originalProviderName = providerName,
    originalCoverUrl = coverUrl,
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

internal fun MusicTrack.originalDetailTrackForNavigation(): MusicTrack {
    if (!isSmartReplacement) return this
    val detailId = originalId ?: providerId ?: id
    val detailSource = originalSource
        ?: detailId.substringBefore(':').takeIf { it.isNotBlank() }
        ?: source
    return copy(
        id = detailId,
        title = originalTitle ?: title,
        artists = originalArtists ?: artists,
        album = originalAlbum ?: album,
        source = detailSource,
        sourceType = TrackSourceType.Provider,
        localUri = null,
        coverUrl = originalCoverUrl ?: coverUrl,
        providerId = detailId,
        providerName = originalProviderName ?: detailSource,
        isSmartReplacement = false,
        originalId = null,
        originalTitle = null,
        originalArtists = null,
        originalAlbum = null,
        originalSource = null,
        originalProviderName = null,
        originalCoverUrl = null,
        replacementId = null,
        replacementTitle = null,
        replacementArtists = null,
        replacementAlbum = null,
        replacementSource = null,
        replacementProviderName = null,
        replacementCoverUrl = null,
        replacementStrategy = null,
        replacementScore = null,
    )
}

internal fun MusicTrack.replacementDetailTrackForNavigation(): MusicTrack? {
    val detailId = replacementId?.takeIf { it.isNotBlank() } ?: return null
    val detailSource = replacementSource
        ?: detailId.substringBefore(':').takeIf { it.isNotBlank() }
        ?: return null
    return copy(
        id = detailId,
        title = replacementTitle ?: title,
        artists = replacementArtists ?: artists,
        album = replacementAlbum ?: album,
        source = detailSource,
        sourceType = TrackSourceType.Provider,
        localUri = null,
        coverUrl = replacementCoverUrl ?: coverUrl,
        providerId = detailId,
        providerName = replacementProviderName ?: detailSource,
        isSmartReplacement = false,
        originalId = null,
        originalTitle = null,
        originalArtists = null,
        originalAlbum = null,
        originalSource = null,
        originalProviderName = null,
        originalCoverUrl = null,
        replacementId = null,
        replacementTitle = null,
        replacementArtists = null,
        replacementAlbum = null,
        replacementSource = null,
        replacementProviderName = null,
        replacementCoverUrl = null,
        replacementStrategy = null,
        replacementScore = null,
    )
}
