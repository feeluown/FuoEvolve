package org.feeluown.mobile

enum class AudioRecognitionSource {
    Microphone,
    SystemOutput,
}

data class AudioRecognitionAccess(
    val source: AudioRecognitionSource,
    val isAvailable: Boolean,
    val requestAccess: () -> Unit = {},
)
