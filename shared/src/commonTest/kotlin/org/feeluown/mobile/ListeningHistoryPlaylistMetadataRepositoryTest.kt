package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class ListeningHistoryPlaylistMetadataRepositoryTest {
    @Test
    fun legacyPlaylistIdentityUsesLoadedMineMetadata() {
        val playlist = ProviderPlaylist(
            id = "playlist:netease:123",
            title = "通勤精选",
            providerId = "netease",
            providerName = "网易云音乐",
            coverUrl = "https://example.invalid/cover.jpg",
        )
        val state = HomeFeatureUiState(
            minePlaylistSections = listOf(
                ProviderContentSection(
                    feature = ProviderFeature(
                        id = "mine-playlists",
                        providerId = "netease",
                        providerName = "网易云音乐",
                        title = "我的歌单",
                        category = ProviderFeatureCategory.MinePlaylists,
                        contentType = ProviderContentType.Playlists,
                        requiresLogin = true,
                    ),
                    playlists = listOf(playlist),
                ),
            ),
        )
        val result = listOf(legacyPlaylistStat("netease", playlist.id))
            .withPlaylistDisplayMetadata(state)
            .single()

        assertEquals("通勤精选", result.resource.title)
        assertEquals("网易云音乐", result.resource.subtitle)
        assertEquals("https://example.invalid/cover.jpg", result.resource.coverUrl)
    }

    @Test
    fun unresolvedLegacyPlaylistDoesNotExposeInternalId() {
        val result = listOf(
            legacyPlaylistStat(
                sourceId = "context",
                resourceId = "playlist:qqmusic:toplist:62:2026-08-10",
            ),
        ).withPlaylistDisplayMetadata(HomeFeatureUiState()).single()

        assertEquals("QQ 音乐歌单（名称未记录）", result.resource.title)
        assertEquals("QQ 音乐", result.resource.subtitle)
    }

    private fun legacyPlaylistStat(sourceId: String, resourceId: String) = ListeningResourceStat(
        resource = ListeningResourceSnapshot(
            resourceKey = "Playlist:${sourceId.length}:$sourceId:$resourceId",
            type = ListeningResourceType.Playlist,
            sourceId = sourceId,
            sourceResourceId = resourceId,
            title = resourceId,
        ),
        eventCount = 0L,
        qualifiedPlayCount = 0L,
        playedMs = 0L,
        lastPlayedAtMillis = 1_000L,
        contextSessionCount = 3L,
    )
}
