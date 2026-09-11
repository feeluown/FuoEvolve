package org.feeluown.mobile

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DesktopCoverArtTest {
    @Test
    fun decodesAndSamplesEncodedCover() {
        val image = assertNotNull(
            decodeDesktopCover(
                Base64.getDecoder().decode(FOUR_BY_FOUR_PNG),
                maxSizePx = 1,
            ),
        )

        assertEquals(1, image.width)
        assertEquals(1, image.height)
    }
}

private const val FOUR_BY_FOUR_PNG =
    "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAEElEQVQI12P8z4AATAxEcQAz0QEH8e1QIgAAAABJRU5ErkJggg=="
