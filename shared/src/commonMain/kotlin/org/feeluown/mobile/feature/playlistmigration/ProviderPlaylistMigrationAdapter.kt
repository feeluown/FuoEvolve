package org.feeluown.mobile

import kotlinx.coroutines.delay

/** Reuses the playback match ranking, but never resolves a playback URL for a migration. */
class ProviderPlaylistMigrationAdapter(
    private val catalog: ProviderCatalogRepository,
    private val library: ProviderLibraryRepository,
    private val replacement: PlaybackReplacementProviderPort,
) : PlaylistMigrationProvider {
    override suspend fun loadPage(playlist: MigrationPlaylist, offset: Int): MigrationPage {
        val detail = catalog.playlistDetailPage(playlist.toProviderPlaylist(), offset)
        return MigrationPage(
            tracks = detail.tracks.map { it.toMigrationTrack() },
            nextOffset = detail.tracksNextOffset.takeIf { it > offset } ?: offset + detail.tracks.size,
            hasMore = detail.tracksHasMore,
        )
    }

    override suspend fun candidates(track: MigrationTrack, targetProviderId: String): List<MigrationCandidate> =
        replacement.replacementCandidates(
            track = track.toMusicTrack(),
            smartReplacementProviderIds = setOf(targetProviderId),
            smartReplacementMinScore = 0.0,
        ).filter { it.track.source == targetProviderId }
            .map { MigrationCandidate(it.track.toMigrationTrack(), it.score) }

    override suspend fun createPlaylist(providerId: String, name: String): MigrationPlaylist {
        val before = ownedPlaylists(providerId).mapTo(mutableSetOf()) { it.id }
        val result = library.createPlaylist(providerId, name)
        if (!result.success) error(result.message.ifBlank { "创建歌单失败" })
        // The existing mutation contract doesn't return a playlist ID. Only accept a newly
        // discovered ID; matching by name alone may accidentally select an older playlist.
        repeat(3) { attempt ->
            val created = ownedPlaylists(providerId).filter { it.title == name && it.id !in before }
            if (created.size == 1) return created.single().toMigrationPlaylist()
            if (created.size > 1) error("找到多个同名歌单，请手动选择")
            if (attempt < 2) delay(400)
        }
        error("歌单可能已创建，请手动选择")
    }

    override suspend fun targetTracks(playlist: MigrationPlaylist): Set<String> {
        val ids = mutableSetOf<String>()
        var offset = 0
        while (true) {
            val detail = catalog.playlistDetailPage(playlist.toProviderPlaylist(), offset)
            ids.addAll(detail.tracks.map { it.id })
            if (!detail.tracksHasMore) break
            val next = detail.tracksNextOffset.takeIf { it > offset } ?: offset + detail.tracks.size
            check(next > offset) { "无法读取目标歌单" }
            offset = next
        }
        return ids
    }

    override suspend fun addTrack(playlist: MigrationPlaylist, track: MigrationTrack): Boolean {
        require(track.providerId == playlist.providerId) { "只能添加目标平台的歌曲" }
        val result = library.addTrackToPlaylist(playlist.toProviderPlaylist(), track.toMusicTrack())
        return result.success
    }

    suspend fun ownedPlaylists(providerId: String): List<ProviderPlaylist> {
        val features = catalog.features().filter {
            it.providerId == providerId && it.category == ProviderFeatureCategory.MinePlaylists &&
                it.contentType == ProviderContentType.Playlists
        }
        return buildList {
            features.forEach { feature ->
                var offset = 0
                while (true) {
                    val page = catalog.loadFeaturePage(feature, offset)
                    addAll(page.playlists.filter { it.providerId == providerId && it.isOwnedByCurrentUser != false })
                    if (!page.hasMore) break
                    val next = page.nextOffset.takeIf { it > offset } ?: offset + page.playlists.size
                    check(next > offset) { "无法读取歌单列表" }
                    offset = next
                }
            }
        }.distinctBy { it.id }
    }
}

internal fun ProviderPlaylist.toMigrationPlaylist() = MigrationPlaylist(
    id = id,
    title = title,
    providerId = providerId,
    providerName = providerName,
)

internal fun MigrationPlaylist.toProviderPlaylist() = ProviderPlaylist(
    id = id,
    title = title,
    providerId = providerId,
    providerName = providerName,
)

internal fun MusicTrack.toMigrationTrack() = MigrationTrack(
    id = id,
    title = title,
    artists = artists,
    album = album,
    durationMs = durationMs,
    providerId = source,
)

internal fun MigrationTrack.toMusicTrack() = MusicTrack(
    id = id,
    title = title,
    artists = artists,
    album = album,
    source = providerId,
    sourceType = TrackSourceType.Provider,
    durationMs = durationMs,
    providerId = id,
)
