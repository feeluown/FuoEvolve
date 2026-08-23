package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import org.feeluown.mobile.provider.core.base64DecodeToString
import org.feeluown.mobile.provider.core.md5Hex

class ProviderHashingTest {
    @Test
    fun md5MatchesKnownVectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5Hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5Hex("abc"))
        assertEquals(
            "9e107d9d372bb6826bd81d3542a419d6",
            md5Hex("The quick brown fox jumps over the lazy dog"),
        )
        assertEquals("Hello Kotlin", base64DecodeToString("SGVsbG8gS290bGlu"))
    }
}
