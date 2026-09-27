package org.feeluown.mobile

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioRecognitionTest {
    @Test
    fun downsampleRecognitionWindowPreservesInBandTone() {
        val samples = sineWave(frequencyHz = 1_000.0)

        val downsampled = downsampleRecognitionWindow(samples)

        assertEquals(AUDIO_RECOGNITION_FINGERPRINT_SAMPLES, downsampled.size)
        assertTrue(rms(downsampled) > 0.6f)
    }

    @Test
    fun downsampleRecognitionWindowSuppressesOutOfBandTone() {
        val samples = sineWave(frequencyHz = 10_000.0)

        val downsampled = downsampleRecognitionWindow(samples)

        assertTrue(rms(downsampled) < 0.02f)
    }

    @Test
    fun downsampleRecognitionWindowRemovesDcOffset() {
        val samples = FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES) { 0.25f }

        val downsampled = downsampleRecognitionWindow(samples)

        assertTrue(downsampled.all { abs(it) < 0.000001f })
    }

    @Test
    fun downsampleRecognitionWindowRejectsShortInput() {
        assertFailsWith<IllegalArgumentException> {
            downsampleRecognitionWindow(FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES - 1))
        }
    }

    @Test
    fun downsampleRecognitionWindowUsesExactlyFirstSixSeconds() {
        val base = sineWave(frequencyHz = 440.0)
        val extended = FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES + 6) { index ->
            if (index < base.size) base[index] else -1f
        }

        assertContentEquals(
            downsampleRecognitionWindow(base),
            downsampleRecognitionWindow(extended),
        )
    }

    @Test
    fun signalQualityRejectsSilenceAndHeavyClipping() {
        assertFalse(
            analyzeRecognitionSignal(FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES)).usable,
        )
        val clipped = FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES) { index ->
            if (index % 2 == 0) 1f else -1f
        }
        assertFalse(analyzeRecognitionSignal(clipped).usable)
    }

    @Test
    fun signalQualityAcceptsNormalMusicLevelTone() {
        val samples = sineWave(frequencyHz = 440.0, amplitude = 0.05f)

        assertTrue(analyzeRecognitionSignal(samples).usable)
    }

    private fun sineWave(
        frequencyHz: Double,
        amplitude: Float = 1f,
    ): FloatArray = FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES) { index ->
        (amplitude * sin(2.0 * PI * frequencyHz * index / AUDIO_RECOGNITION_SAMPLE_RATE)).toFloat()
    }

    private fun rms(samples: FloatArray): Float {
        var squareSum = 0.0
        for (sample in samples) squareSum += sample * sample
        return sqrt(squareSum / samples.size).toFloat()
    }
}
