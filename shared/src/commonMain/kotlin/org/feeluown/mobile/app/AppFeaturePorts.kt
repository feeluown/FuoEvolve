package org.feeluown.mobile

import kotlinx.coroutines.flow.StateFlow

/**
 * App-shell capabilities that Search needs from sibling features.
 *
 * Shared app-shell adapters compose these capabilities from narrow feature owners so Search itself
 * never depends on the compatibility controller or platform-specific forwarding objects.
 */
interface SearchAppPort {
    val providers: List<ProviderInfo>
    val downloadStates: Map<String, DownloadState>

    fun closeSearch()

    fun playResult(index: Int)

    fun addToUpNext(track: MusicTrack)

    fun download(track: MusicTrack)

    fun deleteDownload(track: MusicTrack)

    fun openArtist(track: MusicTrack)

    fun openAlbum(track: MusicTrack)

    fun openTrackDetail(track: MusicTrack)

    fun canAddToPlaylist(track: MusicTrack): Boolean

    fun openPlaylistTargetPicker(track: MusicTrack)

    fun openMediaItem(item: ProviderMediaItem)

    fun openPlaylist(playlist: ProviderPlaylist)

    fun openVideo(video: ProviderVideo)
}

data class RecognitionDetailLoadState(
    val loadingTrackId: String? = null,
    val errorMessage: String? = null,
)

/** App-shell capabilities that Recognition needs outside its feature owner. */
interface RecognitionAppPort {
    val detailLoadState: StateFlow<RecognitionDetailLoadState>

    fun canOpenNeteaseDetail(song: RecognizedSong): Boolean

    suspend fun openNeteaseDetail(song: RecognizedSong)

    fun clearDetailLoadError()
}
