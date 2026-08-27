package org.feeluown.mobile

/**
 * Presentation-side read adapter that enriches legacy playlist history with metadata already loaded by Mine.
 *
 * The pre-event playlist counter only persisted provider/id/count/last-played, so migrated all-time
 * baselines cannot contain a title by themselves. New event history already stores rich snapshots.
 */
internal class ListeningHistoryPlaylistMetadataRepository(
    private val delegate: ListeningHistoryRepository,
    private val home: HomeFeatureController,
) : ListeningHistoryRepository by delegate {
    override suspend fun recentResources(
        range: ListeningTimeRange,
        limit: Int,
        resourceType: ListeningResourceType?,
    ): List<ListeningResourceStat> = delegate
        .recentResources(range, limit, resourceType)
        .withPlaylistDisplayMetadata(home.uiState.value)

    override suspend fun topResources(
        resourceType: ListeningResourceType,
        range: ListeningTimeRange,
        limit: Int,
    ): List<ListeningResourceStat> = delegate
        .topResources(resourceType, range, limit)
        .withPlaylistDisplayMetadata(home.uiState.value)
}

internal fun List<ListeningResourceStat>.withPlaylistDisplayMetadata(
    state: HomeFeatureUiState,
): List<ListeningResourceStat> {
    val knownPlaylists = (state.minePlaylistSections + state.mineFavoritePlaylistSections)
        .filterNot { it.isLoginRequired }
        .flatMap { it.playlists }
        .distinctBy { it.providerId to it.id }
    if (knownPlaylists.isEmpty()) {
        return map(ListeningResourceStat::withLegacyPlaylistFallback)
    }
    return map { stat -> stat.withPlaylistDisplayMetadata(knownPlaylists) }
}

private fun ListeningResourceStat.withPlaylistDisplayMetadata(
    knownPlaylists: List<ProviderPlaylist>,
): ListeningResourceStat {
    if (resource.type != ListeningResourceType.Playlist) return this

    val inferredProviderId = resource.inferredPlaylistProviderId()
    val known = knownPlaylists.firstOrNull { playlist ->
        playlist.providerId == inferredProviderId && playlist.id == resource.sourceResourceId
    } ?: knownPlaylists.filter { playlist ->
        playlist.id == resource.sourceResourceId
    }.singleOrNull()

    if (known == null) return withLegacyPlaylistFallback()

    return copy(
        resource = resource.copy(
            title = known.title.ifBlank { resource.displayFallbackPlaylistTitle() },
            subtitle = known.providerName.ifBlank { resource.subtitle },
            coverUrl = known.coverUrl ?: resource.coverUrl,
        ),
    )
}

private fun ListeningResourceStat.withLegacyPlaylistFallback(): ListeningResourceStat {
    if (resource.type != ListeningResourceType.Playlist) return this
    if (resource.title.isNotBlank() && resource.title != resource.sourceResourceId) return this
    return copy(
        resource = resource.copy(
            title = resource.displayFallbackPlaylistTitle(),
            subtitle = resource.subtitle.ifBlank {
                resource.inferredPlaylistProviderId().displayPlaylistProviderName()
            },
        ),
    )
}

private fun ListeningResourceSnapshot.displayFallbackPlaylistTitle(): String {
    val providerName = inferredPlaylistProviderId().displayPlaylistProviderName()
    return if (providerName.isBlank()) {
        "歌单（名称未记录）"
    } else {
        "${providerName}歌单（名称未记录）"
    }
}

private fun ListeningResourceSnapshot.inferredPlaylistProviderId(): String {
    if (sourceId.isNotBlank() && sourceId != "context") return sourceId
    val parts = sourceResourceId.split(':', limit = 3)
    return if (parts.size >= 2 && parts.first() == "playlist") parts[1] else ""
}

private fun String.displayPlaylistProviderName(): String = when (lowercase()) {
    "netease" -> "网易云音乐"
    "qqmusic" -> "QQ 音乐"
    "bilibili" -> "哔哩哔哩"
    "ytmusic" -> "YouTube Music"
    "local" -> "本地"
    else -> this
}
