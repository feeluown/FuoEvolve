package org.feeluown.mobile

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class DefaultAudioRecognitionRepositoryTest {
    @Test
    fun captureWindowFingerprintAndMatchAreSharedAcrossPlatforms() = runTest {
        val chunks = listOf(
            FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES / 2) { 0.25f },
            FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES - AUDIO_RECOGNITION_WINDOW_SAMPLES / 2) { -0.25f },
        )
        val capture = object : AudioRecognitionCaptureDevice {
            override suspend fun capture(onSamples: (FloatArray) -> Unit) {
                chunks.forEach(onSamples)
            }
            override fun cancel() = Unit
        }
        var fingerprintSamples = 0
        val fingerprint = object : AudioFingerprintRuntime {
            override suspend fun generate(samples: FloatArray): String {
                fingerprintSamples = samples.size
                return "fingerprint"
            }
        }
        val expected = RecognizedSong(
            neteaseSongId = "42",
            title = "Song",
            artists = listOf("Artist"),
            album = "Album",
        )
        var matcherSession = ""
        val matcher = object : AudioRecognitionMatcher {
            override suspend fun match(sessionId: String, fingerprint: String): List<RecognizedSong> {
                matcherSession = sessionId
                assertEquals("fingerprint", fingerprint)
                return listOf(expected)
            }
        }
        val repository = DefaultAudioRecognitionRepository(
            captureDevice = capture,
            fingerprintRuntime = fingerprint,
            matcher = matcher,
            sessionIdFactory = { "session" },
        )
        val events = mutableListOf<AudioRecognitionEvent>()

        val result = repository.recognize(events::add)

        assertEquals(listOf(expected), result)
        assertEquals(AUDIO_RECOGNITION_FINGERPRINT_SAMPLES, fingerprintSamples)
        assertEquals("session", matcherSession)
        assertIs<AudioRecognitionEvent.Matching>(events.first { it is AudioRecognitionEvent.Matching })
        assertIs<AudioRecognitionEvent.Success>(events.last())
    }

    @Test
    fun recognitionWindowsOverlapByConfiguredStride() = runTest {
        val firstWindow = sineWave(AUDIO_RECOGNITION_WINDOW_SAMPLES, frequencyHz = 440.0)
        val nextStride = sineWave(AUDIO_RECOGNITION_WINDOW_STRIDE_SAMPLES, frequencyHz = 880.0)
        val capture = object : AudioRecognitionCaptureDevice {
            override suspend fun capture(onSamples: (FloatArray) -> Unit) {
                onSamples(firstWindow)
                yield()
                onSamples(nextStride)
                yield()
            }
            override fun cancel() = Unit
        }
        val generated = mutableListOf<FloatArray>()
        val fingerprint = object : AudioFingerprintRuntime {
            override suspend fun generate(samples: FloatArray): String {
                generated += samples.copyOf()
                return "fingerprint-${generated.size}"
            }
        }
        val expectedSong = RecognizedSong(
            neteaseSongId = "84",
            title = "Overlap",
            artists = listOf("Artist"),
            album = "Album",
        )
        var matches = 0
        val matcher = object : AudioRecognitionMatcher {
            override suspend fun match(sessionId: String, fingerprint: String): List<RecognizedSong> {
                matches += 1
                return if (matches == 2) listOf(expectedSong) else emptyList()
            }
        }
        val repository = DefaultAudioRecognitionRepository(
            captureDevice = capture,
            fingerprintRuntime = fingerprint,
            matcher = matcher,
        )

        val result = repository.recognize { }

        val expectedSecondWindow = FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES)
        firstWindow.copyInto(
            destination = expectedSecondWindow,
            destinationOffset = 0,
            startIndex = AUDIO_RECOGNITION_WINDOW_STRIDE_SAMPLES,
            endIndex = firstWindow.size,
        )
        nextStride.copyInto(
            destination = expectedSecondWindow,
            destinationOffset = AUDIO_RECOGNITION_WINDOW_SAMPLES - AUDIO_RECOGNITION_WINDOW_STRIDE_SAMPLES,
        )
        assertEquals(listOf(expectedSong), result)
        assertEquals(2, generated.size)
        assertContentEquals(downsampleRecognitionWindow(firstWindow), generated[0])
        assertContentEquals(downsampleRecognitionWindow(expectedSecondWindow), generated[1])
    }

    @Test
    fun unusableWindowsSkipFingerprintAndNetworkWork() = runTest {
        val capture = object : AudioRecognitionCaptureDevice {
            override suspend fun capture(onSamples: (FloatArray) -> Unit) {
                onSamples(FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES))
                yield()
                repeat(AUDIO_RECOGNITION_MAX_ATTEMPTS - 1) {
                    onSamples(FloatArray(AUDIO_RECOGNITION_WINDOW_STRIDE_SAMPLES))
                    yield()
                }
            }
            override fun cancel() = Unit
        }
        var fingerprintCalls = 0
        val fingerprint = object : AudioFingerprintRuntime {
            override suspend fun generate(samples: FloatArray): String {
                fingerprintCalls += 1
                return "unexpected"
            }
        }
        var matcherCalls = 0
        val matcher = object : AudioRecognitionMatcher {
            override suspend fun match(sessionId: String, fingerprint: String): List<RecognizedSong> {
                matcherCalls += 1
                return emptyList()
            }
        }
        val repository = DefaultAudioRecognitionRepository(
            captureDevice = capture,
            fingerprintRuntime = fingerprint,
            matcher = matcher,
        )

        val result = repository.recognize { }

        assertEquals(emptyList(), result)
        assertEquals(0, fingerprintCalls)
        assertEquals(0, matcherCalls)
    }

    private fun sineWave(size: Int, frequencyHz: Double): FloatArray = FloatArray(size) { index ->
        (0.1 * sin(2.0 * PI * frequencyHz * index / AUDIO_RECOGNITION_SAMPLE_RATE)).toFloat()
    }
}
