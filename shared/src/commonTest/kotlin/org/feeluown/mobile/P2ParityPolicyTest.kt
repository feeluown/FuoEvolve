package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class P2ParityPolicyTest {
    @Test
    fun providerNormalizationNeverLeavesAvailableCatalogEmpty() {
        assertEquals(
            setOf("netease"),
            normalizedEnabledProviderIds(emptySet(), listOf("netease", "qqmusic")),
        )
        assertEquals(
            setOf("qqmusic"),
            normalizedEnabledProviderIds(setOf("removed", "qqmusic"), listOf("netease", "qqmusic")),
        )
    }

    @Test
    fun finalProviderCannotBeDisabled() {
        assertEquals(
            setOf("netease"),
            updatedEnabledProviderIds(
                current = setOf("netease"),
                providerId = "netease",
                enabled = false,
                availableProviderIds = listOf("netease", "qqmusic"),
            ),
        )
        assertEquals(
            setOf("qqmusic"),
            updatedEnabledProviderIds(
                current = setOf("netease", "qqmusic"),
                providerId = "netease",
                enabled = false,
                availableProviderIds = listOf("netease", "qqmusic"),
            ),
        )
    }

    @Test
    fun savedCacheLimitsAreAppliedAtStartup() = runTest {
        val settings = AppSettings().copy(audioCacheLimitMb = 768, imageCacheLimitMb = 192)
        var applied: CacheLimit? = null

        applySavedCacheLimits(
            loadSettings = { settings },
            applyLimit = { applied = it },
        )

        val limit = assertNotNull(applied)
        assertEquals(768L * 1024L * 1024L, limit.audioMaxBytes)
        assertEquals(192L * 1024L * 1024L, limit.imageMaxBytes)
    }

    @Test
    fun weeklyPeriodIdentitySurvivesP2DetailRouting() {
        val feature = ProviderFeature(
            id = "bilibili_weekly_must_watch|number=312",
            providerId = "bilibili",
            providerName = "Bilibili",
            title = "每周必看",
            category = ProviderFeatureCategory.Recommend,
            contentType = ProviderContentType.Songs,
            requiresLogin = false,
        )
        val playlist = ProviderPlaylist(
            id = "playlist:bilibili:weekly_312",
            title = "每周必看 第312期",
            providerId = "bilibili",
            providerName = "Bilibili",
        )

        assertEquals("312", feature.bilibiliWeeklyNumber())
        assertEquals("312", playlist.bilibiliWeeklyNumber())
        assertTrue(feature.isBilibiliWeeklyMustWatch())
    }
}
