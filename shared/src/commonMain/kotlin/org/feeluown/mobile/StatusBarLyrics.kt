package org.feeluown.mobile

sealed interface StatusBarLyricsPayload {
    data object Empty : StatusBarLyricsPayload

    data class Text(
        val text: String,
    ) : StatusBarLyricsPayload

    data class Timed(
        val lines: List<StatusBarLyricLine>,
    ) : StatusBarLyricsPayload
}

data class StatusBarLyricLine(
    val beginMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String? = null,
    val romanization: String? = null,
    val words: List<StatusBarLyricWord>? = null,
)

data class StatusBarLyricWord(
    val beginMs: Long,
    val endMs: Long,
    val text: String,
)

fun buildStatusBarLyricsPayload(
    rawLyrics: String?,
    durationMs: Long?,
): StatusBarLyricsPayload {
    val parsed = parseLyrics(rawLyrics)
        .filter { it.text.isNotBlank() }
    if (parsed.isEmpty()) return StatusBarLyricsPayload.Empty

    val timed = parsed.filter { it.timeMs != Long.MAX_VALUE }
    if (timed.isEmpty()) {
        val text = parsed
            .map { it.text.trim() }
            .filter(String::isNotBlank)
            .joinToString("\n")
        return if (text.isBlank()) StatusBarLyricsPayload.Empty else StatusBarLyricsPayload.Text(text)
    }

    val lines = timed.mapIndexed { index, line ->
        val begin = line.timeMs.coerceAtLeast(0L)
        val nextBegin = timed.getOrNull(index + 1)
            ?.timeMs
            ?.takeIf { it > begin }
        val durationEnd = durationMs
            ?.takeIf { it > begin }
        val end = nextBegin ?: durationEnd ?: (begin + MIN_VALID_LYRIC_DURATION_MS)
        val words = line.words
            ?.mapNotNull { word ->
                val wordBegin = word.startMs.coerceAtLeast(begin)
                if (wordBegin >= end || word.text.isBlank()) return@mapNotNull null
                val requestedEnd = word.startMs + word.durationMs.coerceAtLeast(MIN_VALID_LYRIC_DURATION_MS)
                val wordEnd = requestedEnd
                    .coerceAtMost(end)
                    .coerceAtLeast(wordBegin + MIN_VALID_LYRIC_DURATION_MS)
                    .coerceAtMost(end)
                if (wordEnd <= wordBegin) {
                    null
                } else {
                    StatusBarLyricWord(
                        beginMs = wordBegin,
                        endMs = wordEnd,
                        text = word.text,
                    )
                }
            }
            ?.takeIf { it.isNotEmpty() }
        StatusBarLyricLine(
            beginMs = begin,
            endMs = end,
            text = line.text,
            translation = line.translation?.takeIf(String::isNotBlank),
            romanization = line.romanization?.takeIf(String::isNotBlank),
            words = words,
        )
    }
    return StatusBarLyricsPayload.Timed(lines)
}

private const val MIN_VALID_LYRIC_DURATION_MS = 1L
