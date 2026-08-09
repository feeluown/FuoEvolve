package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderFeatureFilterCodecTest {
    @Test
    fun filterMetadataRoundTripsUnicodeAndReservedCharacters() {
        val feature = ProviderFeature(
            id = "netease_playlist_square|cat=R&B/Soul|order=hot",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "歌单广场",
            category = ProviderFeatureCategory.Music,
            contentType = ProviderContentType.Playlists,
            requiresLogin = false,
        )
        val filters = listOf(
            ProviderFeatureFilterSpec(
                key = "cat|type",
                title = "分类^筛选",
                options = listOf(
                    ProviderFeatureFilterOption(
                        label = "R&B/Soul ~ 精选 >",
                        featureId = "netease_playlist_square|cat=R&B/Soul|order=hot",
                        selected = true,
                    ),
                    ProviderFeatureFilterOption(
                        label = "摇滚",
                        featureId = "netease_playlist_square|cat=摇滚|order=new",
                    ),
                ),
            ),
        )

        val encoded = ProviderFeatureFilterCodec.attach(feature, filters)

        assertEquals(feature.id, ProviderFeatureFilterCodec.requestId(encoded.id))
        assertEquals(filters, ProviderFeatureFilterCodec.filters(encoded.id))
        assertTrue(encoded.id.contains("^filters^"))
    }

    @Test
    fun requestIdIsUnchangedWhenNoMetadataExists() {
        val id = "netease_mv_square|area=欧美|type=现场版|order=最新"

        assertEquals(id, ProviderFeatureFilterCodec.requestId(id))
        assertTrue(ProviderFeatureFilterCodec.filters(id).isEmpty())
    }
}
