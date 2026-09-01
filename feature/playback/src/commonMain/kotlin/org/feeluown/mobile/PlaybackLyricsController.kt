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
    private val currentResolvedSource: () -> ResolvedPlaybackSource? = { null },
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
        currentResolvedSource: () -> ResolvedPlaybackSource? = { null },
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
        currentResolvedSource = currentResolvedSource,
    )

    private val mutableAssociationState = MutableStateFlow(LyricsAssociationUiState())
    override val associationState: StateFlow<LyricsAssociationUiState> = mutableAssociationState.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var selectionJob: Job? = null
    private var loadedForTrackId: String? = null
    private var loadedForPlaybackSourceTrackId: String? = null
    private var loadedAssociationTrackId: String? = null
    private var currentAlignmentPersistenceKey: String? = null
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
        loadedForPlaybackSourceTrackId = null
        loadedAssociationTrackId = null
        currentAlignmentPersistenceKey = null
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
        val engineTrackId = engineState.currentTrack?.logicalPlaybackTrack()?.id
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
        val logicalTrack = track.logicalPlaybackTrack()
        val lyricTrack = lyricSourceTrackForPlayback(logicalTrack)
        val playbackSourceTrackId = alignmentSourceTrackIdForPlayback(logicalTrack)
        val associatedTrackId = associationForTrackId(lyricTrack.id)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val hasReplacementSource = currentResolvedSource()?.isReplacement == true
        val defaultAlignmentKey = if (hasReplacementSource) {
            lyricsAlignmentPersistenceKey(playbackSourceTrackId, lyricTrack.id)
        } else {
            null
        }
        val associationAlignmentKey = associatedTrackId?.let { lyricsTrackId ->
            lyricsAlignmentPersistenceKey(playbackSourceTrackId, lyricsTrackId)
        }
        val alignmentOffsetMs = (associationAlignmentKey ?: defaultAlignmentKey)?.let { alignmentKey ->
            alignmentOffsetForTrackId(alignmentKey).coerceIn(-3_000L, 3_000L)
        } ?: 0L
        currentAlignmentPersistenceKey = defaultAlignmentKey

        if (
            loadedForTrackId == logicalTrack.id &&
            loadedForPlaybackSourceTrackId == playbackSourceTrackId &&
            loadedAssociationTrackId == associatedTrackId
        ) {
            val current = mutableAssociationState.value
            val effectiveAlignmentKey = if (current.isManualAssociation) {
                associationAlignmentKey
            } else {
                defaultAlignmentKey
            }
            val effectiveAlignmentOffsetMs = effectiveAlignmentKey?.let { alignmentKey ->
                alignmentOffsetForTrackId(alignmentKey).coerceIn(-3_000L, 3_000L)
            } ?: 0L
            currentAlignmentPersistenceKey = effectiveAlignmentKey
            if (
                current.trackId == logicalTrack.id &&
                current.alignmentOffsetMs != effectiveAlignmentOffsetMs
            ) {
                mutableAssociationState.value = current.copy(
                    alignmentOffsetMs = effectiveAlignmentOffsetMs,
                )
            }
            return
        }

        loadedForTrackId = logicalTrack.id
        loadedForPlaybackSourceTrackId = playbackSourceTrackId
        loadedAssociationTrackId = associatedTrackId
        val requestSerial = currentRequestSerial()
        loadJob?.cancel()

        if (associatedTrackId != null) {
            mutableAssociationState.value = LyricsAssociationUiState(
                trackId = logicalTrack.id,
            )
            loadJob = scope.launch {
                val associatedTrack = repository.trackDetail(associatedTrackId)
                val associatedLyrics = associatedTrack?.let { candidate ->
                    runCatching { repository.lyrics(candidate) }.getOrNull()
                }?.takeIf { it.isNotBlank() }
                if (!isCurrent(logicalTrack, requestSerial)) return@launch
                if (associatedTrack != null && associatedLyrics != null) {
                    updateLyrics(associatedLyrics)
                    currentAlignmentPersistenceKey = associationAlignmentKey
                    mutableAssociationState.value = LyricsAssociationUiState(
                        trackId = logicalTrack.id,
                        isManualAssociation = true,
                        associatedTrackId = associatedTrack.id,
                        associatedTrackTitle = associatedTrack.title,
                        alignmentOffsetMs = alignmentOffsetMs,
                    )
                    return@launch
                }

                val fallbackLyrics = logicalTrack.lyrics
                    ?.takeIf { it.isNotBlank() }
                    ?: runCatching { repository.lyrics(lyricTrack) }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                if (!isCurrent(logicalTrack, requestSerial)) return@launch
                if (fallbackLyrics != null) updateLyrics(fallbackLyrics)
                val fallbackAlignmentOffsetMs = defaultAlignmentKey?.let { alignmentKey ->
                    alignmentOffsetForTrackId(alignmentKey).coerceIn(-3_000L, 3_000L)
                } ?: 0L
                currentAlignmentPersistenceKey = defaultAlignmentKey
                mutableAssociationState.value = LyricsAssociationUiState(
                    trackId = logicalTrack.id,
                    isLyricsUnavailable = fallbackLyrics == null,
                    alignmentOffsetMs = fallbackAlignmentOffsetMs,
                )
            }
            return
        }

        currentLyrics()?.takeIf { it.isNotBlank() }?.let {
            mutableAssociationState.value = LyricsAssociationUiState(
                trackId = logicalTrack.id,
                alignmentOffsetMs = alignmentOffsetMs,
            )
            return
        }
        logicalTrack.lyrics?.takeIf { it.isNotBlank() }?.let {
            updateLyrics(it)
            mutableAssociationState.value = LyricsAssociationUiState(
                trackId = logicalTrack.id,
                alignmentOffsetMs = alignmentOffsetMs,
            )
            return
        }

        loadJob = scope.launch {
            val lyrics = runCatching {
                repository.lyrics(lyricTrack)
            }.getOrNull()?.takeIf { it.isNotBlank() }
            if (!isCurrent(logicalTrack, requestSerial)) return@launch
            if (lyrics != null) updateLyrics(lyrics)
            mutableAssociationState.value = LyricsAssociationUiState(
                trackId = logicalTrack.id,
                isLyricsUnavailable = lyrics == null,
                alignmentOffsetMs = alignmentOffsetMs,
            )
        }
    }

    override fun openAssociationSearch(track: MusicTrack) {
        val logicalTrack = track.logicalPlaybackTrack()
        associationSearchTrack = logicalTrack
        associationQueryEdited = false
        val sourceTrack = lyricSourceTrackForPlayback(logicalTrack)
        val playbackSourceTrackId = alignmentSourceTrackIdForPlayback(logicalTrack)
        val previous = mutableAssociationState.value.takeIf { it.trackId == logicalTrack.id }
        val persistedAssociationTrackId = associationForTrackId(sourceTrack.id)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val hasReplacementSource = currentResolvedSource()?.isReplacement == true
        val alignmentKey = when {
            previous?.isManualAssociation == true && previous.associatedTrackId != null ->
                lyricsAlignmentPersistenceKey(playbackSourceTrackId, previous.associatedTrackId)
            previous != null && hasReplacementSource ->
                lyricsAlignmentPersistenceKey(playbackSourceTrackId, sourceTrack.id)
            previous != null -> null
            persistedAssociationTrackId != null ->
                lyricsAlignmentPersistenceKey(playbackSourceTrackId, persistedAssociationTrackId)
            hasReplacementSource ->
                lyricsAlignmentPersistenceKey(playbackSourceTrackId, sourceTrack.id)
            else -> null
        }
        currentAlignmentPersistenceKey = alignmentKey
        val alignmentOffsetMs = previous?.alignmentOffsetMs
            ?: alignmentKey?.let { persistedKey ->
                alignmentOffsetForTrackId(persistedKey).coerceIn(-3_000L, 3_000L)
            }
            ?: 0L
        mutableAssociationState.value = LyricsAssociationUiState(
            trackId = logicalTrack.id,
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
            if (associationSearchTrack?.id != logicalTrack.id || currentTrackId() != logicalTrack.id) return@launch
            if (associationQueryEdited) {
                mutableAssociationState.value = mutableAssociationState.value.copy(isSearching = false)
                return@launch
            }
            mutableAssociationState.value = mutableAssociationState.value.copy(query = keyword)
            performSearch(logicalTrack, keyword)
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
        val playbackSourceTrackId = alignmentSourceTrackIdForPlayback(playbackTrack)
        val alignmentKey = lyricsAlignmentPersistenceKey(playbackSourceTrackId, track.id)
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

            val alignmentOffsetMs = alignmentOffsetForTrackId(alignmentKey)
                .coerceIn(-3_000L, 3_000L)
            rememberAssociation(sourceTrack.id, track.id)
            loadedForTrackId = playbackTrack.id
            loadedForPlaybackSourceTrackId = playbackSourceTrackId
            loadedAssociationTrackId = track.id
            currentAlignmentPersistenceKey = alignmentKey
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
        currentAlignmentPersistenceKey?.let { alignmentKey ->
            rememberAlignmentOffset(alignmentKey, clamped)
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

    private fun alignmentSourceTrackIdForPlayback(track: MusicTrack): String {
        val resolvedSource = currentResolvedSource()?.takeIf { it.isReplacement }
        return resolvedSource?.trackId?.takeIf { it.isNotBlank() } ?: track.logicalPlaybackTrack().id
    }

    private fun lyricSourceTrackForPlayback(track: MusicTrack): MusicTrack = track.logicalPlaybackTrack()
}
