package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Provider-track actions shared by now playing and feature/detail surfaces. */
internal class ProviderTrackActionController(
    private val providerRepository: ProviderMusicRepository,
    private val scope: CoroutineScope,
    private val navigation: PlaybackNavigationPort,
    private val providerCapabilities: () -> Map<String, ProviderCapabilities>,
    private val isProviderLoggedIn: (String) -> Boolean,
    private val openMediaItem: (ProviderMediaItem) -> Unit,
    private val openTrackDetail: (MusicTrack) -> Unit,
    private val searchTrackText: (String, String?) -> Unit,
    private val removeDislikedTrack: (MusicTrack) -> Unit,
    private val refreshMineContent: () -> Unit,
    private val setLoading: (Boolean) -> Unit,
    private val setMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) : ProviderTrackActionPort {
    var artistTargetTrack by mutableStateOf<MusicTrack?>(null)
        private set
    var artistTargets by mutableStateOf<List<TrackArtistTarget>>(emptyList())
        private set

    private val mutableArtistTargetPickerState = MutableStateFlow(ArtistTargetPickerUiState())
    override val artistTargetPickerState: StateFlow<ArtistTargetPickerUiState> =
        mutableArtistTargetPickerState.asStateFlow()
    private val mutableFeedback = MutableStateFlow<String?>(null)
    override val feedback: StateFlow<String?> = mutableFeedback.asStateFlow()

    override fun openTrackArtist(track: MusicTrack) {
        openTrackArtist(track, loadDetailWhenMissing = true)
    }

    override fun closeArtistTargetPicker() {
        artistTargetTrack = null
        artistTargets = emptyList()
        publishArtistTargetPickerState()
    }

    override fun openArtistTarget(target: TrackArtistTarget) {
        val track = artistTargetTrack ?: return
        closeArtistTargetPicker()
        navigation.closeFullPlayer()
        target.mediaItem?.let(openMediaItem)
            ?: searchTrackText(target.name, track.source.takeIf { it.isNotBlank() })
    }

    override fun openTrackAlbum(track: MusicTrack) {
        openTrackAlbum(track, loadDetailWhenMissing = true)
    }

    override fun openOriginalTrackDetail(track: MusicTrack) {
        navigation.closeFullPlayer()
        openTrackDetail(track.originalDetailTrackForNavigation())
    }

    override fun canSetSongDisliked(track: MusicTrack): Boolean = canSetSongDisliked(track, true)

    fun canSetSongDisliked(track: MusicTrack, disliked: Boolean): Boolean {
        val providerId = trackProviderId(track) ?: return false
        val capabilities = providerCapabilities()[providerId] ?: return false
        return track.sourceType == TrackSourceType.Provider &&
            isProviderLoggedIn(providerId) &&
            if (disliked) capabilities.canAddDislikedSong else capabilities.canRemoveDislikedSong
    }

    override fun setSongDisliked(track: MusicTrack) = setSongDisliked(track, true)

    fun setSongDisliked(track: MusicTrack, disliked: Boolean) {
        if (!canSetSongDisliked(track, disliked)) return
        scope.launch {
            setLoading(true)
            publishFeedback(if (disliked) "正在设为不喜欢" else "正在取消不喜欢")
            runCatching { providerRepository.setSongDisliked(track, disliked) }
                .onSuccess { result ->
                    if (result.success) {
                        if (disliked) removeDislikedTrack(track) else refreshMineContent()
                        publishFeedback(
                            result.message.ifBlank {
                                if (disliked) "已设为不喜欢" else "已取消不喜欢"
                            }
                        )
                    } else {
                        publishFeedback(result.message.ifBlank { "操作失败" })
                    }
                }
                .onFailure(::publishFailure)
            setLoading(false)
        }
    }

    override fun dismissFeedback(feedback: String) {
        if (mutableFeedback.value == feedback) mutableFeedback.value = null
    }

    private fun openTrackArtist(track: MusicTrack, loadDetailWhenMissing: Boolean) {
        val targets = track.artistNavigationTargets()
        if (
            loadDetailWhenMissing &&
            targets.any { it.mediaItem == null } &&
            track.canLoadProviderDetail()
        ) {
            scope.launch {
                setLoading(true)
                val detail = runCatching { providerRepository.trackDetail(track.providerTrackId()) }
                setLoading(false)
                detail
                    .onSuccess { openTrackArtist(it, loadDetailWhenMissing = false) }
                    .onFailure { openTrackArtist(track, loadDetailWhenMissing = false) }
            }
            return
        }
        if (targets.size > 1) {
            artistTargetTrack = track
            artistTargets = targets
            publishArtistTargetPickerState()
            return
        }
        targets.singleOrNull()?.mediaItem?.let {
            navigation.closeFullPlayer()
            openMediaItem(it)
            return
        }
        val target = targets.singleOrNull()?.name.orEmpty().ifBlank { track.artists.trim() }
        navigation.closeFullPlayer()
        searchTrackText(target, track.source.takeIf { it.isNotBlank() })
    }

    private fun openTrackAlbum(track: MusicTrack, loadDetailWhenMissing: Boolean) {
        val albumName = track.album.trim()
        val providerId = track.source.takeIf { it.isNotBlank() }
        val providerName = track.providerName ?: providerId.orEmpty()
        val itemId = track.albumItemId
        if (!itemId.isNullOrBlank() && providerId != null && albumName.isNotBlank()) {
            navigation.closeFullPlayer()
            openMediaItem(
                ProviderMediaItem(
                    id = itemId,
                    title = albumName,
                    providerId = providerId,
                    providerName = providerName,
                    type = ProviderMediaItemType.Album,
                )
            )
            return
        }
        if (loadDetailWhenMissing && track.canLoadProviderDetail()) {
            scope.launch {
                setLoading(true)
                val detail = runCatching { providerRepository.trackDetail(track.providerTrackId()) }
                setLoading(false)
                detail
                    .onSuccess { openTrackAlbum(it, loadDetailWhenMissing = false) }
                    .onFailure { openTrackAlbum(track, loadDetailWhenMissing = false) }
            }
            return
        }
        navigation.closeFullPlayer()
        searchTrackText(albumName, providerId)
    }

    private fun MusicTrack.artistNavigationTargets(): List<TrackArtistTarget> {
        if (artistItems.isNotEmpty()) {
            return artistItems.distinctBy { it.id }.map { TrackArtistTarget(it.title, it) }
        }
        val names = artists
            .split(" / ", "/", "·", ",", "，", "、")
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        val providerId = source.takeIf { it.isNotBlank() }
        val firstItem = if (!artistItemId.isNullOrBlank() && providerId != null && names.isNotEmpty()) {
            ProviderMediaItem(
                id = artistItemId,
                title = names.first(),
                providerId = providerId,
                providerName = providerName ?: providerId,
                type = ProviderMediaItemType.Artist,
            )
        } else {
            null
        }
        return names.mapIndexed { index, name ->
            TrackArtistTarget(name, firstItem.takeIf { index == 0 })
        }
    }

    private fun publishArtistTargetPickerState() {
        mutableArtistTargetPickerState.value = ArtistTargetPickerUiState(
            track = artistTargetTrack,
            targets = artistTargets,
        )
    }

    private fun publishFeedback(message: String) {
        mutableFeedback.value = message
        setMessage(message)
    }

    private fun publishFailure(throwable: Throwable) {
        publishFeedback(throwable.message ?: throwable::class.simpleName.orEmpty().ifBlank { "操作失败" })
        onError(throwable)
    }

    private fun MusicTrack.canLoadProviderDetail(): Boolean =
        sourceType != TrackSourceType.LocalMediaStore && providerTrackId().isNotBlank()

    private fun MusicTrack.providerTrackId(): String = providerId?.takeIf { it.isNotBlank() } ?: id

    private fun trackProviderId(track: MusicTrack): String? =
        track.source.takeIf { it.isNotBlank() }
            ?: track.providerId?.substringBefore(":")?.takeIf { it.isNotBlank() }
}
