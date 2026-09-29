package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeContentRoleTest {
    @Test
    fun mapsProviderFeaturesIntoStableHomeRoles() {
        assertEquals(
            HomeContentRole.DailyRecommendation,
            feature("netease_daily_songs", ProviderContentType.Songs, ProviderFeatureCategory.Recommend).homeContentRole(),
        )
        assertEquals(
            HomeContentRole.PersonalRadio,
            feature("qqmusic_radio", ProviderContentType.Songs, ProviderFeatureCategory.Recommend).homeContentRole(),
        )
        assertEquals(
            HomeContentRole.Charts,
            feature("ytmusic_toplists", ProviderContentType.Playlists, ProviderFeatureCategory.Music).homeContentRole(),
        )
        assertEquals(
            HomeContentRole.NewAlbums,
            feature("qqmusic_new_albums", ProviderContentType.Albums, ProviderFeatureCategory.Music).homeContentRole(),
        )
        assertEquals(
            HomeContentRole.Artists,
            feature("netease_artist_square", ProviderContentType.Artists, ProviderFeatureCategory.Music).homeContentRole(),
        )
    }

    @Test
    fun recommendActionsUseHomeRolePriorityInsteadOfProviderOrder() {
        val sections = listOf(
            section("bilibili_recommended_videos", ProviderContentType.Songs, ProviderFeatureCategory.Recommend),
            section("qqmusic_radio", ProviderContentType.Songs, ProviderFeatureCategory.Recommend),
            section("netease_daily_songs", ProviderContentType.Songs, ProviderFeatureCategory.Recommend),
        )

        assertEquals(
            listOf("netease_daily_songs", "qqmusic_radio", "bilibili_recommended_videos"),
            sections.homeActionSections().map { it.feature.id },
        )
    }

    @Test
    fun exploreEntriesUseHomeRolePriorityInsteadOfProviderOrder() {
        val sections = listOf(
            section("netease_new_albums", ProviderContentType.Albums),
            section("qqmusic_playlist_square", ProviderContentType.Playlists),
            section("ytmusic_toplists", ProviderContentType.Playlists),
        )

        assertEquals(
            listOf("ytmusic_toplists", "qqmusic_playlist_square", "netease_new_albums"),
            sections.homeExploreEntries().map { it.feature.id },
        )
    }

    private fun section(
        id: String,
        type: ProviderContentType,
        category: ProviderFeatureCategory = ProviderFeatureCategory.Music,
    ) = ProviderContentSection(
        feature = feature(id, type, category),
    )

    private fun feature(
        id: String,
        type: ProviderContentType,
        category: ProviderFeatureCategory,
    ) = ProviderFeature(
        id = id,
        providerId = id.substringBefore('_'),
        providerName = id.substringBefore('_'),
        title = id,
        category = category,
        contentType = type,
        requiresLogin = false,
    )
}
