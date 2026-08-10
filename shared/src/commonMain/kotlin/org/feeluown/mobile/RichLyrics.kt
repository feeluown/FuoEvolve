package org.feeluown.mobile

import kotlin.math.abs

/**
 * Composes provider lyric tracks into the app's existing lyric transport format.
 * The main LRC/YRC/QRC-derived track keeps its timing. Translation,
 * romanization and annotation tracks are aligned and rendered as secondary
 * lines through the existing translation slot for backward-compatible styling.
 */
fun composeRichLyrics(
    main: String,
    translation: String? = null,
    romanization: String? = null,
    annotation: String? = null,
): String {
    val primary = main.trimEnd()
    if (primary.isBlank()) return primary

    val secondaryTracks = listOf(translation, romanization, annotation)
        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
    if (secondaryTracks.isEmpty()) return primary
    if (secondaryTracks.size == 1) return composeLyricsWithTranslation(primary, secondaryTracks.first())

    val primaryLines = parseTimedRichTrack(primary)
    val parsedTracks = secondaryTracks.map(::parseTimedRichTrack).filter { it.isNotEmpty() }
    if (primaryLines.isEmpty() || parsedTracks.isEmpty()) {
        return composeLyricsWithTranslation(primary, secondaryTracks.first())
    }

    val secondary = buildString {
        primaryLines.forEachIndexed { index, line ->
            val values = parsedTracks.mapNotNull { track ->
                alignedSecondaryText(track, line.timeMs, index, primaryLines.size)
            }
                .map(String::trim)
                .filter(String::isNotBlank)
                .filter { it != line.text.trim() }
                .distinct()
            values.forEach { value ->
                append(formatRichLrcTimestamp(line.timeMs))
                append(value)
                append('\n')
            }
        }
    }.trimEnd()

    return composeLyricsWithTranslation(primary, secondary.takeIf(String::isNotBlank))
}

private data class TimedRichText(val timeMs: Long, val text: String)

private fun parseTimedRichTrack(raw: String): List<TimedRichText> = buildList {
    raw.lineSequence().forEach { originalLine ->
        val line = originalLine.trim()
        if (line.isBlank()) return@forEach

        richWordLineRegex.matchEntire(line)?.let { match ->
            val time = match.groupValues[1].toLongOrNull() ?: return@let
            val text = match.groupValues[3].replace(richWordTimestampRegex, "").trim()
            if (text.isNotBlank()) add(TimedRichText(time, text))
            return@forEach
        }

        val timestamps = richLrcTimestampRegex.findAll(line).toList()
        if (timestamps.isEmpty()) return@forEach
        val text = line.replace(richLrcTimestampRegex, "").trim()
        if (text.isBlank()) return@forEach
        timestamps.forEach { timestamp ->
            parseRichLrcTime(timestamp)?.let { time -> add(TimedRichText(time, text)) }
        }
    }
}.sortedBy(TimedRichText::timeMs)

private fun alignedSecondaryText(
    track: List<TimedRichText>,
    primaryTimeMs: Long,
    primaryIndex: Int,
    primarySize: Int,
): String? {
    val nearest = track.minByOrNull { abs(it.timeMs - primaryTimeMs) }
    if (nearest != null && abs(nearest.timeMs - primaryTimeMs) <= RICH_LYRIC_ALIGNMENT_TOLERANCE_MS) {
        return nearest.text
    }
    return track.getOrNull(primaryIndex)
        ?.takeIf { track.size == primarySize && abs(it.timeMs - primaryTimeMs) <= RICH_LYRIC_INDEX_TOLERANCE_MS }
        ?.text
}

private fun parseRichLrcTime(match: MatchResult): Long? {
    val minutes = match.groupValues[1].toLongOrNull() ?: return null
    val seconds = match.groupValues[2].toLongOrNull() ?: return null
    val fraction = match.groupValues[3]
        .takeIf(String::isNotBlank)
        ?.padEnd(3, '0')
        ?.take(3)
        ?.toLongOrNull()
        ?: 0L
    return minutes * 60_000L + seconds * 1_000L + fraction
}

private fun formatRichLrcTimestamp(timeMs: Long): String {
    val normalized = timeMs.coerceAtLeast(0L)
    val minutes = normalized / 60_000L
    val seconds = (normalized % 60_000L) / 1_000L
    val millis = normalized % 1_000L
    return "[${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}.${millis.toString().padStart(3, '0')}]"
}

private val richLrcTimestampRegex = Regex("""\[(\d{1,3}):(\d{1,2})(?:\.(\d{1,3}))?]""")
private val richWordLineRegex = Regex("""^\[(\d+),(\d+)](.*)$""")
private val richWordTimestampRegex = Regex("""\(\d+,\d+(?:,\d+)?\)""")
private const val RICH_LYRIC_ALIGNMENT_TOLERANCE_MS = 350L
private const val RICH_LYRIC_INDEX_TOLERANCE_MS = 1_500L
