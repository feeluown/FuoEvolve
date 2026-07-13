package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AudioFormatInfoTest {
    @Test
    fun displaysOnlyKnownAudioFormatValues() {
        assertEquals("320 kbps", formatAudioBitrate(320_000))
        assertNull(formatAudioBitrate(null))
        assertNull(formatAudioBitrate(0))
        assertFalse(AudioFormatInfo().hasDisplayableValue())
    }
}
