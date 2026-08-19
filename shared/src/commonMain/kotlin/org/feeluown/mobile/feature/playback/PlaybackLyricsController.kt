package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class PlaybackLyricsController(
    providerRepository: ProviderMusicRepository,
    private val scope: CoroutineScope,
    private val currentRequestSerial: () -> Long,
    private val currentTrackId: () -> String?,
    private val currentLyrics: () -> String?,
    private val updateLyrics: (String) -> Unit,
) {
    private val providerRepository: ProviderPlaybackRepository = ProviderPlaybackRepositoryView(providerRepository)
    private var loadJob: Job? = null
    private var loadedForTrackId: String? = null

    fun resetForPlaybackRequest() {
        loadJob?.cancel()
        loadJob = null
        loadedForTrackId = null
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
            return
        }
        track.lyrics?.takeIf { it.isNotBlank() }?.let {
            updateLyrics(it)
            loadedForTrackId = track.id
            return
        }
        if (loadedForTrackId == track.id) return
        loadedForTrackId = track.id
        val requestSerial = currentRequestSerial()
        loadJob?.cancel()
        loadJob = scope.launch {
            val lyrics = runCatching {
                providerRepository.lyrics(lyricSourceTrackForPlayback(track))
            }.getOrNull()?.takeIf { it.isNotBlank() }
            if (requestSerial != currentRequestSerial()) return@launch
            if (currentTrackId() != track.id) return@launch
            if (!lyrics.isNullOrBlank()) {
                updateLyrics(lyrics)
            }
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
