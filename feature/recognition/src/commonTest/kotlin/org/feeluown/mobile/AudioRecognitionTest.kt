package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AudioRecognitionTest {
    @Test
    fun downsampleRecognitionWindowTakesEverySixthSample() {
        val samples = FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES) { it.toFloat() }

        val downsampled = downsampleRecognitionWindow(samples)

        assertEquals(AUDIO_RECOGNITION_FINGERPRINT_SAMPLES, downsampled.size)
        assertEquals(0f, downsampled.first())
        assertEquals(6f, downsampled[1])
        assertEquals((AUDIO_RECOGNITION_WINDOW_SAMPLES - 6).toFloat(), downsampled.last())
    }

    @Test
    fun downsampleRecognitionWindowRejectsShortInput() {
        assertFailsWith<IllegalArgumentException> {
            downsampleRecognitionWindow(FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES - 1))
        }
    }

    @Test
    fun downsampleRecognitionWindowKeepsSilentFixtureSilent() {
        val downsampled = downsampleRecognitionWindow(
            FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES),
        )

        assertEquals(AUDIO_RECOGNITION_FINGERPRINT_SAMPLES, downsampled.size)
        assertEquals(0f, downsampled.minOrNull())
        assertEquals(0f, downsampled.maxOrNull())
    }

    @Test
    fun downsampleRecognitionWindowUsesExactlyFirstSixSeconds() {
        val samples = FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES + 6) { index ->
            if (index < AUDIO_RECOGNITION_WINDOW_SAMPLES) index.toFloat() else -1f
        }

        val downsampled = downsampleRecognitionWindow(samples)

        assertEquals((AUDIO_RECOGNITION_WINDOW_SAMPLES - 6).toFloat(), downsampled.last())
    }
}
