package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformCoverArtTest {
    @Test
    fun prefersKnownProviderThumbnailSizes() {
        assertEquals(
            "https://y.qq.com/music/photo_new/T001R128x128M000track.jpg",
            preferredCoverImageUrl(
                "https://y.qq.com/music/photo_new/T001R300x300M000track.jpg",
                128,
            ),
        )
        assertEquals(
            "https://p1.music.126.net/cover.jpg?param=128y128",
            preferredCoverImageUrl(
                "https://p1.music.126.net/cover.jpg?param=1000y1000",
                128,
            ),
        )
        assertEquals(
            "https://i.ytimg.com/vi/video/default.jpg",
            preferredCoverImageUrl(
                "https://i.ytimg.com/vi/video/hqdefault.jpg",
                128,
            ),
        )
        assertEquals(
            "https://lh3.googleusercontent.com/image=w128-h128-l90-rj",
            preferredCoverImageUrl(
                "https://lh3.googleusercontent.com/image=w544-h544-l90-rj",
                128,
            ),
        )
    }

    @Test
    fun leavesUnknownImageUrlsUntouched() {
        val url = "https://example.com/cover-original.jpg"

        assertEquals(url, preferredCoverImageUrl(url, 128))
    }
}
