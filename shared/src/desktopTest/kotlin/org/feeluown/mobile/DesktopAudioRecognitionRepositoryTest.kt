package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopAudioRecognitionRepositoryTest {
    @Test
    fun pcm16LittleEndianIsNormalizedForSharedRecognition() {
        val samples = decodePcm16Le(
            byteArrayOf(
                0x00, 0x00,
                0xff.toByte(), 0x7f,
                0x00, 0x80.toByte(),
            ),
            6,
        )

        assertEquals(3, samples.size)
        assertEquals(0f, samples[0])
        assertTrue(samples[1] > 0.999f)
        assertEquals(-1f, samples[2])
    }
}
