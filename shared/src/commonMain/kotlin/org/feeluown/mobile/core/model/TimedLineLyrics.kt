package org.feeluown.mobile

/**
 * Platform-ready timed lyrics used by system media integrations.
 *
 * [lyric] is the primary line-level LRC timeline, [rawLyric] is an enhanced LRC
 * timeline with absolute word timestamps when word timing is available, and
 * [translationLyric] keeps translated lines separate from the primary timeline.
 */
data class PlatformTimedLyrics(
    val lyric: String,
    val rawLyric: String? = null,
    val translationLyric: String? = null,
)

/**
 * Converts the app's supported lyric formats (LRC/YRC plus optional secondary
 * tracks) into plain timed line-level LRC suitable for platform media-session
 * extensions.
 *
 * This legacy representation keeps secondary lines inline for integrations that
 * only expose one lyric field. Prefer [toPlatformTimedLyrics] when the target can
 * carry primary, word-timed and translated timelines separately.
 */
fun toTimedLineLrc(
    rawLyrics: String?,
    alignmentOffsetMs: Long = 0L,
): String? {
    val timedLines = parseLyrics(rawLyrics)
        .filter { it.timeMs != Long.MAX_VALUE && it.text.isNotBlank() }
    if (timedLines.isEmpty()) return null

    return buildString {
        timedLines.forEach { line ->
            val timestamp = formatLrcTimestamp(line.timeMs + alignmentOffsetMs)
            append(timestamp)
            append(line.text.trim())
            append('\n')
            listOf(line.translation, line.romanization)
                .filterNotNull()
                .flatMap { secondary -> secondary.lineSequence().toList() }
                .map(String::trim)
                .filter(String::isNotBlank)
                .filter { it != line.text.trim() }
                .distinct()
                .forEach { secondaryLine ->
                    append(timestamp)
                    append(secondaryLine)
                    append('\n')
                }
        }
    }.trimEnd()
}

/**
 * Converts rich app lyrics into the three timelines understood by ColorOS-style
 * media-session lyric metadata without mixing translation/romanization into the
 * primary lyric timeline.
 */
fun toPlatformTimedLyrics(
    rawLyrics: String?,
    alignmentOffsetMs: Long = 0L,
): PlatformTimedLyrics? {
    val timedLines = parseLyrics(rawLyrics)
        .filter { it.timeMs != Long.MAX_VALUE && it.text.isNotBlank() }
    if (timedLines.isEmpty()) return null

    val lyric = buildString {
        timedLines.forEach { line ->
            append(formatLrcTimestamp(line.timeMs + alignmentOffsetMs))
            append(line.text.trim())
            append('\n')
        }
    }.trimEnd()

    val translationLyric = buildString {
        timedLines.forEach { line ->
            val timestamp = formatLrcTimestamp(line.timeMs + alignmentOffsetMs)
            line.translation
                ?.lineSequence()
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.distinct()
                ?.forEach { translation ->
                    append(timestamp)
                    append(translation)
                    append('\n')
                }
        }
    }.trimEnd().takeIf(String::isNotBlank)

    val hasWordTiming = timedLines.any { !it.words.isNullOrEmpty() }
    val rawLyric = if (hasWordTiming) {
        buildString {
            timedLines.forEach { line ->
                val lineTimestamp = formatLrcTimestamp(line.timeMs + alignmentOffsetMs)
                val words = line.words.orEmpty()
                if (words.isEmpty()) {
                    append(lineTimestamp)
                    append(line.text.trim())
                } else {
                    append(lineTimestamp)
                    words.forEach { word ->
                        append('<')
                        append(formatBareLrcTimestamp(word.startMs + alignmentOffsetMs))
                        append('>')
                        append(word.text)
                    }
                    val finalWord = words.last()
                    append('<')
                    append(formatBareLrcTimestamp(finalWord.startMs + finalWord.durationMs + alignmentOffsetMs))
                    append('>')
                }
                append('\n')
            }
        }.trimEnd()
    } else {
        null
    }

    return PlatformTimedLyrics(
        lyric = lyric,
        rawLyric = rawLyric,
        translationLyric = translationLyric,
    )
}

/**
 * Recovers an already-applied line-timeline offset. Android currently publishes
 * the aligned line LRC before the ColorOS metadata adapter runs, while the media
 * item still retains the original rich provider lyrics.
 */
fun inferTimedLyricsAlignmentOffsetMs(
    sourceLyrics: String?,
    alignedLyrics: String?,
): Long {
    val sourceLines = parseLyrics(sourceLyrics)
        .filter { it.timeMs != Long.MAX_VALUE && it.text.isNotBlank() }
    val alignedLines = parseLyrics(alignedLyrics)
        .filter { it.timeMs != Long.MAX_VALUE && it.text.isNotBlank() }
    val sourceFirst = sourceLines.firstOrNull() ?: return 0L
    val alignedMatch = alignedLines.firstOrNull { it.text.trim() == sourceFirst.text.trim() }
        ?: alignedLines.firstOrNull()
        ?: return 0L
    return alignedMatch.timeMs - sourceFirst.timeMs
}

private fun formatLrcTimestamp(timeMs: Long): String =
    "[${formatBareLrcTimestamp(timeMs)}]"

private fun formatBareLrcTimestamp(timeMs: Long): String {
    val normalized = timeMs.coerceAtLeast(0L)
    val minutes = normalized / 60_000L
    val seconds = (normalized % 60_000L) / 1_000L
    val millis = normalized % 1_000L
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}.${millis.toString().padStart(3, '0')}"
}
