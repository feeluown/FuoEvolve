package org.feeluown.mobile

interface IosAudioOutput {
    fun play(url: String, headers: Map<String, String>, title: String, artists: String, album: String)
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun playbackStatus(): String
    fun positionMs(): Long
    fun durationMs(): Long
    fun bufferedMs(): Long
    fun errorMessage(): String?
    fun audioFormatInfo(): AudioFormatInfo?
    fun spectrumLevels(): List<Float>
}
