package org.feeluown.mobile

data class InstrumentLyricsWindow(
    val previous: String,
    val current: String,
    val next: String,
)

fun buildInstrumentLyricsWindow(
    payload: StatusBarLyricsPayload,
    positionMs: Long,
): InstrumentLyricsWindow? = when (payload) {
    StatusBarLyricsPayload.Empty -> null
    is StatusBarLyricsPayload.Text -> {
        val lines = payload.text.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
        if (lines.isEmpty()) {
            null
        } else {
            InstrumentLyricsWindow(
                previous = "",
                current = lines[0],
                next = lines.getOrNull(1).orEmpty(),
            )
        }
    }
    is StatusBarLyricsPayload.Timed -> {
        val lines = payload.lines
        if (lines.isEmpty()) {
            null
        } else {
            val safePositionMs = positionMs.coerceAtLeast(0L)
            val currentIndex = lines.indexOfLast { line -> safePositionMs >= line.beginMs }
                .coerceAtLeast(0)
                .coerceAtMost(lines.lastIndex)
            InstrumentLyricsWindow(
                previous = lines.getOrNull(currentIndex - 1)?.text.orEmpty(),
                current = lines[currentIndex].text,
                next = lines.getOrNull(currentIndex + 1)?.text.orEmpty(),
            )
        }
    }
}
