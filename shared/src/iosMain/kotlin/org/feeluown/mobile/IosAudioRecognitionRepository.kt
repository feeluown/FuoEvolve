package org.feeluown.mobile

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class IosAudioRecognitionRepository(
    private val output: IosAudioRecognitionOutput,
) : AudioRecognitionRepository {
    override suspend fun recognize(onEvent: (AudioRecognitionEvent) -> Unit): List<RecognizedSong> =
        suspendCancellableCoroutine { continuation ->
            output.recognize(
                eventHandler = { type, attemptValue, capturedMsValue ->
                    val attempt = attemptValue.toIntOrNull() ?: 1
                    val capturedMs = capturedMsValue.toLongOrNull() ?: 0L
                    when (type) {
                        "capturing" -> onEvent(
                            AudioRecognitionEvent.Capturing(
                                attempt = attempt,
                                capturedMs = capturedMs,
                            ),
                        )
                        "matching" -> onEvent(AudioRecognitionEvent.Matching(attempt))
                        "no_match" -> onEvent(AudioRecognitionEvent.NoMatch(attempt))
                        "cancelled" -> onEvent(AudioRecognitionEvent.Cancelled)
                    }
                },
                completionHandler = completion@ { resultJson, error ->
                    if (!continuation.isActive) return@completion
                    if (!error.isNullOrBlank()) {
                        onEvent(AudioRecognitionEvent.Error(error))
                        continuation.resumeWithException(IllegalStateException(error))
                    } else {
                        val songs = parseResults(resultJson.orEmpty())
                        onEvent(AudioRecognitionEvent.Success(songs))
                        continuation.resume(songs)
                    }
                },
            )
            continuation.invokeOnCancellation { output.cancel() }
        }

    override fun cancel() {
        output.cancel()
    }

    private fun parseResults(resultJson: String): List<RecognizedSong> {
        if (resultJson.isBlank()) return emptyList()
        return Json.parseToJsonElement(resultJson).jsonArray.map { element ->
            val item = element.jsonObject
            RecognizedSong(
                neteaseSongId = item["netease_song_id"]?.jsonPrimitive?.contentOrNull,
                title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                artists = item["artists"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                }.orEmpty(),
                album = item["album"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                coverUrl = item["cover_url"]?.jsonPrimitive?.contentOrNull,
                matchStartTimeMs = item["match_start_time_ms"]?.jsonPrimitive?.longOrNull,
            )
        }
    }
}
