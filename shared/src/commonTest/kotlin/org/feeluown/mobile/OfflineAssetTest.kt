package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class OfflineAssetTest {
    @Test
    fun providerIdentityKeepsAssetsDistinct() {
        val first = offlineAssetId(providerId = "netease", source = "netease", providerTrackId = "42")
        val second = offlineAssetId(providerId = "qqmusic", source = "qqmusic", providerTrackId = "42")

        assertNotEquals(first, second)
    }

    @Test
    fun identityEscapesSeparators() {
        assertEquals("provider%3Aid:track%3Aid", offlineAssetId("provider:id", "ignored", "track:id"))
    }
}
