package org.feeluown.mobile

data class LyricWord(
    val startMs: Long,
    val durationMs: Long,
    val text: String,
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<LyricWord>? = null,
    val romanization: String? = null,
    val romanizationWords: List<LyricWord>? = null,
)

private data class RawLyricLine(
    val timeMs: Long,
    val text: String,
    val order: Int,
)

const val LYRIC_TRANSLATION_MARKER = "\n__FUO_LYRIC_TRANSLATION__\n"
const val LYRIC_ROMANIZATION_MARKER = "\n__FUO_LYRIC_ROMANIZATION__\n"

fun composeLyricsWithTranslation(main: String, translation: String?): String =
    composeLyricsWithRichTracks(main = main, translation = translation)

fun composeLyricsWithRichTracks(
    main: String,
    translation: String? = null,
    romanization: String? = null,
): String {
    val trimmedMain = main.trimEnd()
    val trimmedRomanization = romanization?.trim()?.takeIf(String::isNotBlank)
    val trimmedTranslation = translation?.trim()?.takeIf(String::isNotBlank)
    if (trimmedRomanization == null && trimmedTranslation == null) return trimmedMain
    return buildString {
        append(trimmedMain)
        trimmedRomanization?.let {
            append(LYRIC_ROMANIZATION_MARKER)
            append(it)
        }
        trimmedTranslation?.let {
            append(LYRIC_TRANSLATION_MARKER)
            append(it)
        }
    }
}

fun parseLyrics(raw: String?): List<LyricLine> {
    if (raw.isNullOrBlank()) return emptyList()
    val translationParts = raw.split(LYRIC_TRANSLATION_MARKER, limit = 2)
    val mainAndRomanization = translationParts[0]
    val translationRaw = translationParts.getOrNull(1)
    val romanizationParts = mainAndRomanization.split(LYRIC_ROMANIZATION_MARKER, limit = 2)
    val main = romanizationParts[0]
    val romanizationRaw = romanizationParts.getOrNull(1)
    val lines = parseLyricTrack(main)
    return attachLyricRomanization(
        lines = attachLyricTranslations(lines, translationRaw),
        romanizationRaw = romanizationRaw,
    )
}

private fun parseLyricTrack(raw: String?): List<LyricLine> {
    if (raw.isNullOrBlank()) return emptyList()
    return if (raw.lineSequence().any { yrcLineHeaderRegex.containsMatchIn(it.trim()) }) {
        parseYrc(raw)
    } else {
        parseLrc(raw)
    }
}

fun attachLyricTranslations(lines: List<LyricLine>, translationRaw: String?): List<LyricLine> {
    if (translationRaw.isNullOrBlank() || lines.isEmpty()) return lines
    val translationLines = parseLrc(translationRaw).filter { it.timeMs != Long.MAX_VALUE }
    if (translationLines.isEmpty()) return lines
    val groupedTranslationLines = translationLines
        .groupBy { it.timeMs }
        .map { (timeMs, sameTimeLines) ->
            LyricLine(
                timeMs = timeMs,
                text = sameTimeLines.flatMap { secondary ->
                    listOfNotNull(secondary.text, secondary.translation)
                }
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                    .joinToString("\n"),
            )
        }
        .filter { it.text.isNotBlank() }
        .sortedBy(LyricLine::timeMs)
    val aligned = alignSecondaryLyricLines(
        primary = lines,
        secondary = groupedTranslationLines,
        toleranceMs = LYRIC_TRANSLATION_ALIGNMENT_TOLERANCE_MS,
        eligiblePrimary = { it.translation.isNullOrBlank() },
    )
    return lines.mapIndexed { index, line ->
        if (line.timeMs == Long.MAX_VALUE || !line.translation.isNullOrBlank()) return@mapIndexed line
        val translation = aligned[index]?.text?.takeIf(String::isNotBlank) ?: return@mapIndexed line
        line.copy(translation = translation)
    }
}

fun attachLyricRomanization(lines: List<LyricLine>, romanizationRaw: String?): List<LyricLine> {
    if (romanizationRaw.isNullOrBlank() || lines.isEmpty()) return lines
    val romanizationLines = parseLyricTrack(romanizationRaw)
        .filter { it.timeMs != Long.MAX_VALUE }
    if (romanizationLines.isEmpty()) return lines
    val aligned = alignSecondaryLyricLines(
        primary = lines,
        secondary = romanizationLines,
        toleranceMs = LYRIC_ROMANIZATION_ALIGNMENT_TOLERANCE_MS,
        indexToleranceMs = LYRIC_ROMANIZATION_INDEX_TOLERANCE_MS,
        eligiblePrimary = { it.romanization.isNullOrBlank() },
    )
    return lines.mapIndexed { index, line ->
        if (line.timeMs == Long.MAX_VALUE || !line.romanization.isNullOrBlank()) return@mapIndexed line
        val secondary = aligned[index] ?: return@mapIndexed line
        val romanization = listOfNotNull(secondary.text, secondary.translation)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString("\n")
            .takeIf { it.isNotBlank() && it != line.text.trim() }
            ?: return@mapIndexed line
        val words = secondary.words
            ?.takeIf { secondary.translation.isNullOrBlank() && secondary.text.trim() == romanization }
        line.copy(
            romanization = romanization,
            romanizationWords = words,
        )
    }
}

private fun alignSecondaryLyricLines(
    primary: List<LyricLine>,
    secondary: List<LyricLine>,
    toleranceMs: Long,
    indexToleranceMs: Long? = null,
    eligiblePrimary: (LyricLine) -> Boolean = { true },
): Map<Int, LyricLine> {
    val timedPrimary = primary.mapIndexedNotNull { index, line ->
        if (line.timeMs == Long.MAX_VALUE || !eligiblePrimary(line)) null else index to line
    }
    if (timedPrimary.isEmpty() || secondary.isEmpty()) return emptyMap()

    val aligned = mutableMapOf<Int, LyricLine>()
    var minimumPrimaryPosition = 0
    secondary.forEachIndexed { secondaryIndex, secondaryLine ->
        val secondaryIsCredit = isLikelyLyricCreditText(secondaryLine.text)
        val nearestPosition = (minimumPrimaryPosition until timedPrimary.size)
            .asSequence()
            .filter { primaryPosition ->
                kotlin.math.abs(timedPrimary[primaryPosition].second.timeMs - secondaryLine.timeMs) <= toleranceMs
            }
            .minWithOrNull(
                compareBy<Int> { primaryPosition ->
                    if (
                        isLikelyLyricCreditText(timedPrimary[primaryPosition].second.text) == secondaryIsCredit
                    ) {
                        0
                    } else {
                        1
                    }
                }.thenBy { primaryPosition ->
                    kotlin.math.abs(timedPrimary[primaryPosition].second.timeMs - secondaryLine.timeMs)
                }.thenBy { it },
            )
        val fallbackPosition = if (
            nearestPosition == null &&
            indexToleranceMs != null &&
            timedPrimary.size == secondary.size &&
            secondaryIndex >= minimumPrimaryPosition &&
            kotlin.math.abs(timedPrimary[secondaryIndex].second.timeMs - secondaryLine.timeMs) <= indexToleranceMs
        ) {
            secondaryIndex
        } else {
            null
        }
        val primaryPosition = nearestPosition ?: fallbackPosition ?: return@forEachIndexed
        aligned[timedPrimary[primaryPosition].first] = secondaryLine
        minimumPrimaryPosition = primaryPosition + 1
    }
    return aligned
}

private fun isLikelyLyricCreditText(text: String): Boolean =
    lyricCreditPrefixRegex.containsMatchIn(text.trim())

private const val LYRIC_TRANSLATION_ALIGNMENT_TOLERANCE_MS = 50L
private const val LYRIC_ROMANIZATION_ALIGNMENT_TOLERANCE_MS = 350L
private const val LYRIC_ROMANIZATION_INDEX_TOLERANCE_MS = 1_500L

fun parseYrc(raw: String?): List<LyricLine> {
    if (raw.isNullOrBlank()) return emptyList()
    val lines = mutableListOf<LyricLine>()
    raw.lineSequence().forEach { rawLine ->
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty() || trimmed.startsWith('{')) return@forEach
        val headerMatch = yrcLineHeaderRegex.find(trimmed) ?: return@forEach
        val startMs = headerMatch.groupValues[1].toLongOrNull() ?: return@forEach
        val body = trimmed.substring(headerMatch.range.last + 1)
        val words = yrcWordRegex.findAll(body).mapNotNull { match ->
            val wordStart = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val duration = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val text = match.groupValues[3]
            if (text.isEmpty()) null else LyricWord(wordStart, duration, text)
        }.toList()
        val text = words.joinToString("") { it.text }.ifBlank {
            body.replace(yrcWordRegex, "").trim()
        }
        if (text.isBlank()) return@forEach
        lines += LyricLine(
            timeMs = startMs,
            text = text,
            words = words.takeIf { it.isNotEmpty() },
        )
    }
    return lines.sortedBy { it.timeMs }
}

fun parseLrc(raw: String?): List<LyricLine> {
    if (raw.isNullOrBlank()) return emptyList()
    val parsedLines = mutableListOf<RawLyricLine>()
    raw.lines().forEachIndexed { order, line ->
        val matches = lrcTimeRegex.findAll(line).toList()
        val text = line.replace(lrcTimeRegex, "").trim()
        if (text.isBlank()) return@forEachIndexed
        if (matches.isEmpty()) {
            if (!lrcMetadataRegex.matches(line.trim())) {
                parsedLines += RawLyricLine(Long.MAX_VALUE, text, order)
            }
        } else {
            matches.forEach { match ->
                parsedLines += RawLyricLine(parseLrcTime(match.groupValues[1]), text, order)
            }
        }
    }

    val timedLines = parsedLines
        .filter { it.timeMs != Long.MAX_VALUE }
        .sortedWith(compareBy<RawLyricLine> { it.timeMs }.thenBy { it.order })
    val mergedLines = timedLines
        .groupBy { it.timeMs }
        .values
        .flatMap { sameTimeLines ->
            if (
                sameTimeLines.size == 2 &&
                sameTimeLines.none { isLikelyLyricCreditText(it.text) }
            ) {
                val original = sameTimeLines[0]
                val translation = sameTimeLines[1].text.takeIf { it != original.text }
                listOf(LyricLine(original.timeMs, original.text, translation))
            } else {
                sameTimeLines.map { LyricLine(it.timeMs, it.text) }
            }
        }
    val untimedLines = parsedLines
        .filter { it.timeMs == Long.MAX_VALUE }
        .sortedBy { it.order }
        .map { LyricLine(it.timeMs, it.text) }
    return mergedLines + untimedLines
}

fun parseLrcTime(value: String): Long {
    val minuteAndRest = value.split(':', limit = 2)
    if (minuteAndRest.size != 2) return 0
    val minutes = minuteAndRest[0].toLongOrNull() ?: return 0
    val secondAndFraction = minuteAndRest[1].split('.', limit = 2)
    val seconds = secondAndFraction[0].toLongOrNull() ?: 0
    val fraction = secondAndFraction.getOrNull(1)
        ?.padEnd(3, '0')
        ?.take(3)
        ?.toLongOrNull()
        ?: 0
    return minutes * 60_000 + seconds * 1_000 + fraction
}

fun currentLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    val timedLines = lines.takeWhile { it.timeMs != Long.MAX_VALUE }
    if (timedLines.isEmpty()) return -1
    val index = timedLines.indexOfLast { it.timeMs <= positionMs }
    return index.coerceAtLeast(0)
}

fun karaokeFillProgress(
    words: List<LyricWord>,
    positionMs: Long,
    wordWidths: List<Float>,
): Float {
    if (words.isEmpty() || wordWidths.isEmpty()) return 0f
    val totalWidth = wordWidths.sum()
    if (totalWidth <= 0f) return 0f
    var filledWidth = 0f
    val count = minOf(words.size, wordWidths.size)
    for (index in 0 until count) {
        val word = words[index]
        val width = wordWidths[index]
        val durationMs = word.durationMs.coerceAtLeast(1L)
        when {
            positionMs < word.startMs -> return (filledWidth / totalWidth).coerceIn(0f, 1f)
            positionMs >= word.startMs + durationMs -> filledWidth += width
            else -> {
                val fraction = ((positionMs - word.startMs).toFloat() / durationMs).coerceIn(0f, 1f)
                filledWidth += width * fraction
                return (filledWidth / totalWidth).coerceIn(0f, 1f)
            }
        }
    }
    return (filledWidth / totalWidth).coerceIn(0f, 1f)
}

val lrcTimeRegex = Regex("""\[(\d{1,3}:\d{1,2}(?:\.\d{1,3})?)]""")
val lrcMetadataRegex = Regex("""^\[[A-Za-z]+:.*]$""")
val yrcLineHeaderRegex = Regex("""^\[(\d+),(\d+)]""")
val yrcWordRegex = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")
private val lyricCreditPrefixRegex = Regex(
    """^\s*(?:(?:作词|作詞|作曲|编曲|編曲|填词|填詞|混音|制作人|製作人|监制|監製|原唱|演唱|歌手|和声|和聲|录音|錄音|母带|母帶|吉他|贝斯|貝斯|鼓|弦乐|弦樂|词|詞|曲)|(?:lyrics?|lyricist|composer|arranger|producer|vocals?|singer|mix(?:ing)?|master(?:ing)?|written\s+by|music\s+by))\s*(?:[:：/]|-\s+)""",
    RegexOption.IGNORE_CASE,
)
