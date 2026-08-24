package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal interface PlaybackLyricsRepository {
    suspend fun lyrics(track: MusicTrack): String?
    suspend fun search(keyword: String): List<MusicTrack>
    suspend fun trackDetail(trackId: String): MusicTrack?
    suspend fun searchKeyword(track: MusicTrack): String?
}

private class ProviderPlaybackLyricsRepository(
    private val delegate: ProviderMusicRepository,
) : PlaybackLyricsRepository {
    override suspend fun lyrics(track: MusicTrack): String? = delegate.lyrics(track)

    override suspend fun search(keyword: String): List<MusicTrack> =
        delegate.search(keyword, providerId = null)

    override suspend fun trackDetail(trackId: String): MusicTrack? =
        runCatching { delegate.trackDetail(trackId) }.getOrNull()

    override suspend fun searchKeyword(track: MusicTrack): String? =
        delegate.lyricsSearchKeyword(track)
}

internal class PlaybackLyricsController(
    private val repository: PlaybackLyricsRepository,
    private val scope: CoroutineScope,
    private val currentRequestSerial: () -> Long,
    private val currentTrackId: () -> String?,
    private val currentLyrics: () -> String?,
    private val updateLyrics: (String) -> Unit,
    private val associationForTrackId: (String) -> String?,
    private val rememberAssociation: (String, String?) -> Unit,
) : PlaybackLyricsPort {
    constructor(
        providerRepository: ProviderMusicRepository,
        scope: CoroutineScope,
        currentRequestSerial: () -> Long,
        currentTrackId: () -> String?,
        currentLyrics: () -> String?,
        updateLyrics: (String) -> Unit,
        associationForTrackId: (String) -> String?,
        rememberAssociation: (String, String?) -> Unit,
    ) : this(
        repository = ProviderPlaybackLyricsRepository(providerRepository),
        scope = scope,
        currentRequestSerial = currentRequestSerial,
        currentTrackId = currentTrackId,
        currentLyrics = currentLyrics,
        updateLyrics = updateLyrics,
        associationForTrackId = associationForTrackId,
        rememberAssociation = rememberAssociation,
    )

    private val mutableAssociationState = MutableStateFlow(LyricsAssociationUiState())
    override val associationState: StateFlow<LyricsAssociationUiState> = mutableAssociationState.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var selectionJob: Job? = null
    private var loadedForTrackId: String? = null
    private var associationSearchTrack: MusicTrack? = null
    private var associationQueryEdited = false

    fun resetForPlaybackRequest() {
        loadJob?.cancel()
        searchJob?.cancel()
        selectionJob?.cancel()
        loadJob = null
        searchJob = null
        selectionJob = null
        loadedForTrackId = null
        associationSearchTrack = null
        associationQueryEdited = false
        mutableAssociationState.value = LyricsAssociationUiState()
    }

    fun mergedLyrics(
        engineState: PlaybackState,
        currentQueueTrackId: String?,
        previousPlaybackState: PlaybackState,
    ): String? {
        val engineTrackId = engineState.currentTrack?.id
        val currentId = currentQueueTrackId
            ?: engineTrackId
            ?: previousPlaybackState.currentTrack?.id
        engineState.lyrics?.takeIf {
            it.isNotBlank() && (engineTrackId == null || engineTrackId == currentId)
        }?.let { return it }
        val previousTrackId = previousPlaybackState.currentTrack?.id
        return previousPlaybackState.lyrics?.takeIf {
            it.isNotBlank() && previousTrackId != null && previousTrackId == currentId
        }
    }

    fun maybeLoad(track: MusicTrack?) {
        if (track == null) return
        if (!currentLyrics().isNullOrBlank()) {
            loadedForTrackId = track.id
            if (mutableAssociationState.value.trackId != track.id) {
                mutableAssociationState.value = LyricsAssociationUiState(trackId = track.id)
            }
            return
        }
        track.lyrics?.takeIf { it.isNotBlank() }?.let {
            updateLyrics(it)
            loadedForTrackId = track.id
            mutableAssociationState.value = LyricsAssociationUiState(trackId = track.id)
            return
        }
        if (loadedForTrackId == track.id) return

        loadedForTrackId = track.id
        val requestSerial = currentRequestSerial()
        val lyricTrack = lyricSourceTrackForPlayback(track)
        loadJob?.cancel()
        loadJob = scope.launch {
            val associatedTrackId = associationForTrackId(lyricTrack.id)
            if (!associatedTrackId.isNullOrBlank()) {
                val associatedTrack = repository.trackDetail(associatedTrackId)
                val associatedLyrics = associatedTrack?.let { candidate ->
                    runCatching { repository.lyrics(candidate) }.getOrNull()
                }?.takeIf { it.isNotBlank() }
                if (isCurrent(track, requestSerial) && associatedTrack != null && associatedLyrics != null) {
                    updateLyrics(associatedLyrics)
                    mutableAssociationState.value = LyricsAssociationUiState(
                        trackId = track.id,
                        isManualAssociation = true,
                        associatedTrackId = associatedTrack.id,
                        associatedTrackTitle = associatedTrack.title,
                    )
                    return@launch
                }
            }

            val lyrics = runCatching {
                repository.lyrics(lyricTrack)
            }.getOrNull()?.takeIf { it.isNotBlank() }
            if (!isCurrent(track, requestSerial)) return@launch
            if (lyrics != null) {
                updateLyrics(lyrics)
                mutableAssociationState.value = LyricsAssociationUiState(trackId = track.id)
            } else {
                mutableAssociationState.value = LyricsAssociationUiState(
                    trackId = track.id,
                    isLyricsUnavailable = true,
                )
            }
        }
    }

    override fun openAssociationSearch(track: MusicTrack) {
        associationSearchTrack = track
        associationQueryEdited = false
        val previous = mutableAssociationState.value.takeIf { it.trackId == track.id }
        mutableAssociationState.value = LyricsAssociationUiState(
            trackId = track.id,
            isLyricsUnavailable = previous?.isLyricsUnavailable ?: currentLyrics().isNullOrBlank(),
            isManualAssociation = previous?.isManualAssociation == true,
            associatedTrackId = previous?.associatedTrackId,
            associatedTrackTitle = previous?.associatedTrackTitle,
            isSearchOpen = true,
            isSearching = true,
        )
        searchJob?.cancel()
        searchJob = scope.launch {
            val sourceTrack = lyricSourceTrackForPlayback(track)
            val keyword = runCatching { repository.searchKeyword(sourceTrack) }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: sourceTrack.title.trim()
            if (associationSearchTrack?.id != track.id || currentTrackId() != track.id) return@launch
            if (associationQueryEdited) {
                mutableAssociationState.value = mutableAssociationState.value.copy(isSearching = false)
                return@launch
            }
            mutableAssociationState.value = mutableAssociationState.value.copy(query = keyword)
            performSearch(track, keyword)
        }
    }

    override fun updateAssociationQuery(query: String) {
        associationQueryEdited = true
        mutableAssociationState.value = mutableAssociationState.value.copy(
            query = query,
            message = null,
        )
    }

    override fun searchAssociation() {
        val track = associationSearchTrack ?: return
        val keyword = mutableAssociationState.value.query.trim()
        if (keyword.isBlank()) {
            mutableAssociationState.value = mutableAssociationState.value.copy(
                results = emptyList(),
                isSearching = false,
                message = "请输入搜索关键词",
            )
            return
        }
        searchJob?.cancel()
        searchJob = scope.launch {
            performSearch(track, keyword)
        }
    }

    override fun selectAssociation(track: MusicTrack) {
        val playbackTrack = associationSearchTrack ?: return
        val sourceTrack = lyricSourceTrackForPlayback(playbackTrack)
        val requestSerial = currentRequestSerial()
        selectionJob?.cancel()
        mutableAssociationState.value = mutableAssociationState.value.copy(
            selectingTrackId = track.id,
            message = null,
        )
        selectionJob = scope.launch {
            val lyrics = runCatching { repository.lyrics(track) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            if (!isCurrent(playbackTrack, requestSerial) || associationSearchTrack?.id != playbackTrack.id) {
                return@launch
            }
            if (lyrics == null) {
                mutableAssociationState.value = mutableAssociationState.value.copy(
                    selectingTrackId = null,
                    message = "该搜索结果没有可用歌词，请选择其他结果",
                )
                return@launch
            }

            rememberAssociation(sourceTrack.id, track.id)
            updateLyrics(lyrics)
            mutableAssociationState.value = LyricsAssociationUiState(
                trackId = playbackTrack.id,
                isManualAssociation = true,
                associatedTrackId = track.id,
                associatedTrackTitle = track.title,
            )
            associationSearchTrack = null
        }
    }

    override fun closeAssociationSearch() {
        searchJob?.cancel()
        selectionJob?.cancel()
        searchJob = null
        selectionJob = null
        associationSearchTrack = null
        associationQueryEdited = false
        mutableAssociationState.value = mutableAssociationState.value.copy(
            isSearchOpen = false,
            isSearching = false,
            selectingTrackId = null,
            message = null,
        )
    }

    private suspend fun performSearch(track: MusicTrack, keyword: String) {
        mutableAssociationState.value = mutableAssociationState.value.copy(
            isSearching = true,
            results = emptyList(),
            message = null,
        )
        val sourceTrack = lyricSourceTrackForPlayback(track)
        val result = runCatching { repository.search(keyword) }
        if (associationSearchTrack?.id != track.id || currentTrackId() != track.id) return
        result.onSuccess { tracks ->
            val candidates = tracks
                .asSequence()
                .filter { it.id != sourceTrack.id }
                .distinctBy { it.id }
                .toList()
            mutableAssociationState.value = mutableAssociationState.value.copy(
                isSearching = false,
                results = candidates,
                message = if (candidates.isEmpty()) "没有找到可关联的歌曲" else null,
            )
        }.onFailure { throwable ->
            mutableAssociationState.value = mutableAssociationState.value.copy(
                isSearching = false,
                message = throwable.message ?: "搜索歌词失败",
            )
        }
    }

    private fun isCurrent(track: MusicTrack, requestSerial: Long): Boolean =
        requestSerial == currentRequestSerial() && currentTrackId() == track.id

    private fun lyricSourceTrackForPlayback(track: MusicTrack): MusicTrack {
        if (!track.isSmartReplacement) return track
        val originalId = track.originalId?.takeIf { it.isNotBlank() } ?: return track
        val originalSource = track.originalSource?.takeIf { it.isNotBlank() }
            ?: originalId.substringBefore(':').takeIf { it.isNotBlank() }
            ?: track.source
        return track.copy(
            id = originalId,
            providerId = originalId,
            source = originalSource,
            providerName = track.originalProviderName ?: track.providerName,
            title = track.originalTitle ?: track.title,
            artists = track.originalArtists ?: track.artists,
            album = track.originalAlbum ?: track.album,
            coverUrl = track.originalCoverUrl ?: track.coverUrl,
            isSmartReplacement = false,
            lyrics = null,
        )
    }
}
