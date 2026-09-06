package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import org.feeluown.mobile.provider.bilibili.resolveBilibiliPlaybackReportPage

class BilibiliPlaybackReportingProviderTest {
    @Test
    fun activeMultipartIndexOverridesBaseVideoPage() {
        assertEquals(2, resolveBilibiliPlaybackReportPage(encodedPage = 1, currentPartIndex = 1))
        assertEquals(4, resolveBilibiliPlaybackReportPage(encodedPage = 1, currentPartIndex = 3))
    }

    @Test
    fun encodedPageIsUsedWhenNoActivePartIsKnown() {
        assertEquals(3, resolveBilibiliPlaybackReportPage(encodedPage = 3, currentPartIndex = -1))
        assertEquals(1, resolveBilibiliPlaybackReportPage(encodedPage = 0, currentPartIndex = -1))
    }
}
