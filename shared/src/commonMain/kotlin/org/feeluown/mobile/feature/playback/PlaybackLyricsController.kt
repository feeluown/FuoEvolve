package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val LYRICS_ASSOCIATION_PROVIDER_IDS = setOf("netease", "qqmusic", "ytmusic")

internal fun lyricsAlignmentPersistenceKey(sourceTrackId: String, lyricsTrackId: String): String =
    "${sourceTrackId.length}:$sourceTrackId$lyricsTrackId"

internal interface PlaybackLyricsRepository {
    suspend fun lyrics(track: MusicTrack): String?
    suspend fun search(keyword: String): List<MusicTrack>
    suspend fun trackDetail(trackId: String): MusicTrack?
    suspend fun searchKeyword(track: MusicTrack): String?
}

private class ProviderPlaybackLyricsRepository(
    private val registryRepository: ProviderRegistryRepository,
    private val searchRepository: ProviderSearchRepository,
    private val catalogRepository: ProviderCatalogRepository,
    private val playbackRepository: ProviderPlaybackRepository,
) : PlaybackLyricsRepository {
    override suspend fun lyrics(track: MusicTrack): String? = playbackRepository.lyrics(track)

    override suspend fun search(keyword: String): List<MusicTrack> {
        val enabledProviderIds = registryRepository.providers().mapTo(mutableSetOf()) { it.providerId }
        val results = mutableListOf<MusicTrack>()
        var attemptedProviders = 0
        var successfulProviders = 0
        var firstFailure: Throwable? = null
        for (providerId in LYRICS_ASSOCIATION_PROVIDER_IDS) {
            if (providerId !in enabledProviderIds) continue
            attemptedProviders += 1
            runCatching { searchRepository.search(keyword, providerId) }
                .fold(
                    onSuccess = { providerResults ->
                        successfulProviders += 1
                        results += providerResults
                    },
                    onFailure = { throwable ->
                        if (firstFailure == null) firstFailure = throwable
                    },
                )
        }
        if (attemptedProviders > 0 && successfulProviders == 0) {
            throw firstFailure ?: IllegalStateException("歌词搜索失败")
        }
        return results.distinctBy { it.id }
    }

    override suspend fun trackDetail(trackId: String): MusicTrack? =
        runCatching { catalogRepository.trackDetail(trackId) }.getOrNull()

    override suspend fun searchKeyword(track: MusicTrack): String? =
        playbackRepository.lyricsSearchKeyword(track)
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
    private val alignmentOffsetForTrackId: (String) -> Long = { 0L },
    private val rememberAlignmentOffset: (String, Long) -> Unit = { _, _ -> },
) : PlaybackLyricsPort {
    constructor(
        providerRegistryRepository: ProviderRegistryRepository,
        providerSearchRepository: ProviderSearchRepository,
        providerCatalogRepository: ProviderCatalogRepository,
        providerPlaybackRepository: ProviderPlaybackRepository,
        scope: CoroutineScope,
        currentRequestSerial: () -> Long,
        currentTrackId: () -> String?,
        currentLyrics: () -> String?,
        updateLyrics: (String) -> Unit,
        associationForTrackId: (String) -> String?,
        rememberAssociation: (String, String?) -> Unit,
        alignmentOffsetForTrackId: (String) -> Long = { 0L },
        rememberAlignmentOffset: (String, Long) -> Unit = { _, _ -> },
    ) : this(
        repository = ProviderPlaybackLyricsRepository(
            registryRepository = providerRegistryRepository,
            searchRepository = providerSearchRepository,
            catalogRepository = providerCatalogRepository,
            playbackRepository = providerPlaybackRepository,
        ),
        scope = scope,
        currentRequestSerial = currentRequestSerial,
        currentTrackId = currentTrackId,
        currentLyrics = currentLyrics,
        updateLyrics = updateLyrics,
        associationForTrackId = associationForTrackId,
        rememberAssociation = rememberAssociation,
        alignmentOffsetForTrackId = alignmentOffsetForTrackId,
        rememberAlignmentOffset = rememberAlignmentOffset,
    )

    private val mutableAssociationState = MutableStateFlow(LyricsAssociationUiState())
    override val associationState: StateFlow<LyricsAssociationUiState> = mutableAssociationState.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var selectionJob: Job? = null
    private var loadedForTrackId: String? = null
    private var loadedAssociationTrackId: String? = null
    private var currentSourceTrackId: String? = null
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
        loadedAssociationTrackId = null
        currentSourceTrackId = null
        associationSearchTrack = null
        associationQueryEdited = false
        mutableAssociationState.value = LyricsAssociationUiState()
    }

    fun refreshPersistentState(track: MusicTrack?) {
        maybeLoad(track)
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
        val previousTrackId = previousPlaybackState.currentTrack?.id
        val previousLyrics = previousPlaybackState.lyrics?.takeIf {
            it.isNotBlank() && previousTrackId != null && previousTrackId == currentId
        }
        val association = mutableAssociationState.value
        if (
            association.isManualAssociation &&
            association.trackId == currentId &&
            previousLyrics != null
        ) {
            return previousLyrics
        }
        engineState.lyrics?.takeIf {
            it.isNotBlank() && (engineTrackId == null || engineTrackId == currentId)
        }?.let { return it }
        return previousLyrics
    }

    fun maybeLoad(track: MusicTrack?) {
        if (track == null) return
        val lyricTrack = lyricSourceTrackForPlayback(track)
        val associatedTrackId = associationForTrackId(lyricTrack.id)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val alignmentOffsetMs = associatedTrackId?.let { lyricsTrackId ->
            alignmentOffsetForTrackId(
                lyricsAlignmentPersistenceKey(lyricTrack.id, lyricsTrackId),
            ).coerceIn(-3_000L, 3_000L)
        } ?: 0L
        currentSourceTrackId = lyricTrack.id

        if (loadedForTrackId == track.id && loadedAssociationTrackId == associatedTrackId) {
            val current = mutableAssociationState.value
            val effectiveAlignmentOffsetMs = if (current.isManualAssociation) alignmentOffsetMs else 0L
            if (
                current.trackId == track.id &&
                current.alignmentOffsetMs != effectiveAlignmentOffsetMs
            ) {
                mutableAssociationState.value = current.copy(
                    alignmentOffsetMs = effectiveAlignmentOffsetMs,
                )
            }
            return
        }

        loadedForTrackId = track.id
        loadedAssociationTrackId = associatedTrackId
        val requestSerial = currentRequestSerial()
        loadJob?.cancel()

        if (associatedTrackId != null) {
            mutableAssociationState.value = LyricsAssociationUiState(
                trackId = track.id,
            )
            loadJob = scope.launch {
                val associatedTrack = repository.trackDetail(associatedTrackId)
                val associatedLyrics = associatedTrack?.let { candidate ->
                    runCatching { repository.lyrics(candidate) }.getOrNull()
                }?.takeIf { it.isNotBlank() }
                if (!isCurrent(track, requestSerial)) return@launch
                if (associatedTrack != null && associatedLyrics != null) {
                    updateLyrics(associatedLyrics)
                    mutableAssociationState.value = LyricsAssociationUiState(
                        trackId = track.id,
                        isManualAssociation = true,
                        associatedTrackId = associatedTrack.id,
                        associatedTrackTitle = associatedTrack.title,
                        alignmentOffsetMs = alignmentOffsetMs,
                    )
                    return@launch
                }

                val fallbackLyrics = track.lyrics
                    ?.takeIf { it.isNotBlank() }
                    ?: runCatching { repository.lyrics(lyricTrack) }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                if (!isCurrent(track, requestSerial)) return@launch
                if (fallbackLyrics != null) updateLyrics(fallbackLyrics)
                mutableAssociationState.value = LyricsAssociationUiState(
                    trackId = track.id,
                    isLyricsUnavailable = fallbackLyrics == null,
                    alignmentOffsetMs = 0L,
                )
            }
            return
        }

        currentLyrics()?.takeIf { it.isNotBlank() }?.let {
            mutableAssociationState.value = LyricsAssociationUiState(
                trackId = track.id,
                alignmentOffsetMs = 0L,
            )
            return
        }
        track.lyrics?.takeIf { it.isNotBlank() }?.let {
            updateLyrics(it)
            mutableAssociationState.value = LyricsAssociationUiState(
                trackId = track.id,
                alignmentOffsetMs = 0L,
            )
            return
        }

        loadJob = scope.launch {
            val lyrics = runCatching {
                repository.lyrics(lyricTrack)
            }.getOrNull()?.takeIf { it.isNotBlank() }
            if (!isCurrent(track, requestSerial)) return@launch
            if (lyrics != null) updateLyrics(lyrics)
            mutableAssociationState.value = LyricsAssociationUiState(
                trackId = track.id,
                isLyricsUnavailable = lyrics == null,
                alignmentOffsetMs = 0L,
            )
        }
    }

    override fun openAssociationSearch(track: MusicTrack) {
        associationSearchTrack = track
        associationQueryEdited = false
        val sourceTrack = lyricSourceTrackForPlayback(track)
        currentSourceTrackId = sourceTrack.id
        val previous = mutableAssociationState.value.takeIf { it.trackId == track.id }
        val persistedAssociationTrackId = associationForTrackId(sourceTrack.id)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val alignmentOffsetMs = previous?.alignmentOffsetMs
            ?: persistedAssociationTrackId?.let { lyricsTrackId ->
                alignmentOffsetForTrackId(
                    lyricsAlignmentPersistenceKey(sourceTrack.id, lyricsTrackId),
                ).coerceIn(-3_000L, 3_000L)
            }
            ?: 0L
        mutableAssociationState.value = LyricsAssociationUiState(
            trackId = track.id,
            isLyricsUnavailable = previous?.isLyricsUnavailable ?: currentLyrics().isNullOrBlank(),
            isManualAssociation = previous?.isManualAssociation == true,
            associatedTrackId = previous?.associatedTrackId,
            associatedTrackTitle = previous?.associatedTrackTitle,
            alignmentOffsetMs = alignmentOffsetMs,
            isSearchOpen = true,
            isSearching = true,
        )
        searchJob?.cancel()
        searchJob = scope.launch {
            val rawKeyword = runCatching { repository.searchKeyword(sourceTrack) }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: sourceTrack.title.trim()
            val keyword = normalizeAssociationSearchKeyword(sourceTrack, rawKeyword)
                .takeIf { it.isNotBlank() }
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
        currentSourceTrackId = sourceTrack.id
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

            val alignmentOffsetMs = alignmentOffsetForTrackId(
                lyricsAlignmentPersistenceKey(sourceTrack.id, track.id),
            ).coerceIn(-3_000L, 3_000L)
            rememberAssociation(sourceTrack.id, track.id)
            loadedForTrackId = playbackTrack.id
            loadedAssociationTrackId = track.id
            updateLyrics(lyrics)
            mutableAssociationState.value = LyricsAssociationUiState(
                trackId = playbackTrack.id,
                isManualAssociation = true,
                associatedTrackId = track.id,
                associatedTrackTitle = track.title,
                alignmentOffsetMs = alignmentOffsetMs,
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

    override fun updateAlignmentOffset(offsetMs: Long) {
        val clamped = offsetMs.coerceIn(-3_000L, 3_000L)
        val current = mutableAssociationState.value
        if (current.alignmentOffsetMs == clamped) return
        mutableAssociationState.value = current.copy(alignmentOffsetMs = clamped)
        if (current.isManualAssociation) {
            val sourceTrackId = currentSourceTrackId
            val lyricsTrackId = current.associatedTrackId
            if (sourceTrackId != null && lyricsTrackId != null) {
                rememberAlignmentOffset(
                    lyricsAlignmentPersistenceKey(sourceTrackId, lyricsTrackId),
                    clamped,
                )
            }
        }
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

    private fun normalizeAssociationSearchKeyword(track: MusicTrack, keyword: String): String {
        val trimmed = keyword.trim()
        if (track.source != "bilibili") return trimmed
        val wrapped = if (trimmed.startsWith("发现《") && trimmed.endsWith('》')) {
            trimmed.removePrefix("发现").trim()
        } else {
            trimmed
        }
        return if (
            wrapped.length >= 2 &&
            wrapped.startsWith('《') &&
            wrapped.endsWith('》')
        ) {
            wrapped.substring(1, wrapped.length - 1).trim()
        } else {
            wrapped
        }
    }

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
