package org.feeluown.mobile

/**
 * Converts the app's supported lyric formats (LRC/YRC plus optional translation)
 * into plain timed line-level LRC suitable for platform media-session extensions.
 *
 * ColorOS accepts this line-level LRC as the required `lyric` payload. Word-level
 * `rawLyric` is intentionally omitted until FuoEvolve can serialize a compatible
 * word timeline rather than passing provider-specific YRC through unchanged.
 */
fun toTimedLineLrc(rawLyrics: String?): String? {
    val timedLines = parseLyrics(rawLyrics)
        .filter { it.timeMs != Long.MAX_VALUE && it.text.isNotBlank() }
    if (timedLines.isEmpty()) return null

    return buildString {
        timedLines.forEach { line ->
            val timestamp = formatLrcTimestamp(line.timeMs)
            append(timestamp)
            append(line.text.trim())
            append('\n')
            line.translation
                ?.lineSequence()
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.filter { it != line.text.trim() }
                ?.distinct()
                ?.forEach { secondaryLine ->
                    append(timestamp)
                    append(secondaryLine)
                    append('\n')
                }
        }
    }.trimEnd()
}

private fun formatLrcTimestamp(timeMs: Long): String {
    val normalized = timeMs.coerceAtLeast(0L)
    val minutes = normalized / 60_000L
    val seconds = (normalized % 60_000L) / 1_000L
    val millis = normalized % 1_000L
    return "[${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}.${millis.toString().padStart(3, '0')}]"
}
