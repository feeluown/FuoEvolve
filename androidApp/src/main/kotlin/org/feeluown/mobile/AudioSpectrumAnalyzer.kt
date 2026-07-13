package org.feeluown.mobile

import android.media.AudioFormat
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

class AudioSpectrumAnalyzer(
    private val onSpectrum: (List<Float>) -> Unit,
) : TeeAudioProcessor.AudioBufferSink {
    private var sampleRateHz = 0
    private var channelCount = 0
    private var encoding = AudioFormat.ENCODING_INVALID
    private var frameIndex = 0
    private var lastPublishedAtMs = 0L
    private val window = FloatArray(WINDOW_SIZE)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.sampleRateHz = sampleRateHz
        this.channelCount = channelCount
        this.encoding = encoding
        clear()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (sampleRateHz <= 0 || channelCount <= 0) return
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val bytesPerFrame = bytesPerSample * channelCount
        while (input.remaining() >= bytesPerFrame) {
            var sample = 0f
            repeat(channelCount) {
                sample += when (encoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> input.short / 32768f
                    AudioFormat.ENCODING_PCM_FLOAT -> input.float
                    else -> 0f
                }
            }
            window[frameIndex++] = sample / channelCount
            if (frameIndex == WINDOW_SIZE) {
                publishIfDue()
                frameIndex = 0
            }
        }
    }

    fun clear() {
        frameIndex = 0
        lastPublishedAtMs = 0L
        window.fill(0f)
        onSpectrum(emptyList())
    }

    private fun publishIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastPublishedAtMs < PUBLISH_INTERVAL_MS) return
        lastPublishedAtMs = now
        val nyquist = sampleRateHz / 2f
        onSpectrum(BAND_CENTERS_HZ.map { centerHz ->
            if (centerHz >= nyquist) {
                0f
            } else {
                val normalized = goertzelMagnitude(centerHz) * 18f
                (ln(1f + normalized) / ln(19f)).coerceIn(0f, 1f)
            }
        })
    }

    private fun goertzelMagnitude(frequencyHz: Float): Float {
        val omega = 2.0 * PI * frequencyHz / sampleRateHz
        val coefficient = 2.0 * cos(omega)
        var previous = 0.0
        var previousPrevious = 0.0
        window.forEachIndexed { index, sample ->
            val hann = 0.5 - 0.5 * cos(2.0 * PI * index / (WINDOW_SIZE - 1))
            val current = sample * hann + coefficient * previous - previousPrevious
            previousPrevious = previous
            previous = current
        }
        val power = previousPrevious * previousPrevious + previous * previous - coefficient * previous * previousPrevious
        return (sqrt(power.coerceAtLeast(0.0)) / WINDOW_SIZE).toFloat()
    }

    private companion object {
        const val WINDOW_SIZE = 512
        const val PUBLISH_INTERVAL_MS = 50L
        val BAND_CENTERS_HZ = floatArrayOf(60f, 120f, 250f, 500f, 1_000f, 2_000f, 3_500f, 5_000f, 7_000f, 9_000f, 12_000f, 15_000f)
    }
}
