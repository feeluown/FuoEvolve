package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

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
}
