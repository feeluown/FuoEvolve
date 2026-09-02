package org.feeluown.mobile

import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Platform microphone boundary. Implementations emit normalized mono PCM chunks at 48 kHz. */
interface AudioRecognitionCaptureDevice {
    suspend fun capture(onSamples: (FloatArray) -> Unit)
    fun cancel()
}

/** Platform host for the shared NetEase fingerprint JavaScript/WASM runtime. */
interface AudioFingerprintRuntime {
    suspend fun generate(samples: FloatArray): String
    fun cancel() = Unit
}

/** Network matching boundary kept independent from microphone and fingerprint runtimes. */
interface AudioRecognitionMatcher {
    suspend fun match(sessionId: String, fingerprint: String): List<RecognizedSong>
    fun cancel() = Unit
}

/**
 * Shared recognition transaction.
 *
 * Platform code only captures PCM and hosts the fingerprint runtime. Windowing, progress,
 * matching attempts, cancellation and result semantics stay identical on every platform.
 */
class DefaultAudioRecognitionRepository(
    private val captureDevice: AudioRecognitionCaptureDevice,
    private val fingerprintRuntime: AudioFingerprintRuntime,
    private val matcher: AudioRecognitionMatcher,
    private val sessionIdFactory: () -> String = ::newRecognitionSessionId,
) : AudioRecognitionRepository {
    private val recognitionMutex = Mutex()
    private val cancelled = MutableStateFlow(false)

    override suspend fun recognize(onEvent: (AudioRecognitionEvent) -> Unit): List<RecognizedSong> =
        recognitionMutex.withLock {
            coroutineScope recognition@{
                cancelled.value = false
                val windows = Channel<FloatArray>(Channel.CONFLATED)
                var matching = false
                var captureAttempt = 1
                var window = FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES)
                var windowOffset = 0
                var lastProgressMs = -1L

                val captureJob = launch {
                    captureDevice.capture { chunk ->
                        if (cancelled.value || chunk.isEmpty()) return@capture
                        var sourceOffset = 0
                        while (sourceOffset < chunk.size && !cancelled.value) {
                            val copied = minOf(chunk.size - sourceOffset, window.size - windowOffset)
                            chunk.copyInto(
                                destination = window,
                                destinationOffset = windowOffset,
                                startIndex = sourceOffset,
                                endIndex = sourceOffset + copied,
                            )
                            sourceOffset += copied
                            windowOffset += copied

                            val capturedMs = windowOffset * 1_000L / AUDIO_RECOGNITION_SAMPLE_RATE
                            if (!matching && capturedMs - lastProgressMs >= RECOGNITION_PROGRESS_INTERVAL_MS) {
                                lastProgressMs = capturedMs
                                onEvent(
                                    AudioRecognitionEvent.Capturing(
                                        attempt = captureAttempt,
                                        capturedMs = capturedMs,
                                    ),
                                )
                            }

                            if (windowOffset == window.size) {
                                windows.trySend(window)
                                window = FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES)
                                windowOffset = 0
                                captureAttempt += 1
                                lastProgressMs = -1L
                            }
                        }
                    }
                }

                val sessionId = sessionIdFactory()
                var attempt = 1
                try {
                    while (isActive && !cancelled.value) {
                        val capturedWindow = windows.receive()
                        matching = true
                        onEvent(AudioRecognitionEvent.Matching(attempt))
                        val fingerprint = fingerprintRuntime.generate(
                            downsampleRecognitionWindow(capturedWindow),
                        )
                        val matches = matcher.match(sessionId, fingerprint)
                        if (matches.isNotEmpty()) {
                            onEvent(AudioRecognitionEvent.Success(matches))
                            return@recognition matches
                        }

                        matching = false
                        onEvent(AudioRecognitionEvent.NoMatch(attempt))
                        if (attempt >= AUDIO_RECOGNITION_MAX_ATTEMPTS) {
                            return@recognition emptyList()
                        }
                        attempt += 1
                    }
                    emptyList()
                } catch (throwable: Throwable) {
                    if (throwable !is CancellationException && !cancelled.value) {
                        onEvent(AudioRecognitionEvent.Error(throwable.message ?: "听歌识曲失败"))
                    }
                    throw throwable
                } finally {
                    cancelled.value = true
                    captureDevice.cancel()
                    fingerprintRuntime.cancel()
                    matcher.cancel()
                    captureJob.cancel()
                    windows.close()
                }
            }
        }

    override fun cancel() {
        cancelled.value = true
        captureDevice.cancel()
        fingerprintRuntime.cancel()
        matcher.cancel()
    }
}

private fun newRecognitionSessionId(): String = buildString {
    repeat(4) { index ->
        if (index > 0) append('-')
        append(Random.nextLong().toULong().toString(16))
    }
}

private const val RECOGNITION_PROGRESS_INTERVAL_MS = 250L
