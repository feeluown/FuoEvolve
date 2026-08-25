package org.feeluown.mobile

import kotlin.math.abs

/**
 * Composes provider-neutral catalog/search capabilities with a concrete-track resolver.
 * Smart replacement is deliberately implemented here rather than in provider aggregation.
 */
fun createPlaybackProviderPort(
    registry: ProviderRegistryRepository,
    search: ProviderSearchRepository,
    catalog: ProviderCatalogRepository,
    source: PlaybackProviderSourcePort,
    failureMessage: (Throwable, String, String?) -> String,
): PlaybackProviderPort = DefaultPlaybackProviderPort(
    registry = registry,
    search = search,
    catalog = catalog,
    source = source,
    failureMessage = failureMessage,
)

private class DefaultPlaybackProviderPort(
    private val registry: ProviderRegistryRepository,
    private val search: ProviderSearchRepository,
    private val catalog: ProviderCatalogRepository,
    private val source: PlaybackProviderSourcePort,
    private val failureMessage: (Throwable, String, String?) -> String,
) : PlaybackProviderPort,
    ProviderRegistryRepository by registry,
    ProviderSearchRepository by search,
    ProviderCatalogRepository by catalog {

    override suspend fun replacementCandidates(
        track: MusicTrack,
        smartReplacementProviderIds: Set<String>,
        smartReplacementMinScore: Double,
    ): List<ReplacementCandidate> {
        val original = track.asOriginalMediaTrack()
        val originalProviderId = original.providerSourceId()
        val providerIds = if (smartReplacementProviderIds.isEmpty()) {
            registry.providers().mapTo(linkedSetOf()) { it.providerId }
        } else {
            smartReplacementProviderIds
        }
        val candidates = providerIds
            .asSequence()
            .filter { it != originalProviderId }
            .flatMap { providerId ->
                search.search("${original.title} ${original.artists}", providerId).asSequence()
            }
            .toList()
        return sortReplacementScoreTies(
            origin = original,
            ranked = rankReplacementCandidates(
                candidates = candidates,
                minScore = smartReplacementMinScore,
                scoreOf = { candidate -> replacementScore(original, candidate) },
            ),
        )
    }

    override suspend fun resolve(
        track: MusicTrack,
        unavailablePolicy: UnavailablePlaybackPolicy,
        smartReplacementProviderIds: Set<String>,
        smartReplacementMinScore: Double,
        smartReplacementUseOriginalMetadata: Boolean,
        smartReplacementUseOriginalLyrics: Boolean,
    ): PlaybackPayload {
        var failedKnownReplacementId: String? = null
        if (track.isSmartReplacement) {
            val replacementTrack = track.asReplacementMediaTrack()
            if (replacementTrack != null) {
                source.resolveTrack(replacementTrack)?.let { payload ->
                    return annotateSmartReplacement(
                        payload = payload,
                        original = track.asOriginalMediaTrack(),
                        candidate = replacementTrack,
                        score = track.replacementScore ?: 1.0,
                        useOriginalMetadata = smartReplacementUseOriginalMetadata,
                        useOriginalLyrics = smartReplacementUseOriginalLyrics,
                    ).copy(replacementStrategy = track.replacementStrategy ?: "title_artist_duration")
                }
                failedKnownReplacementId = replacementTrack.id
            }
        }

        val original = track.asOriginalMediaTrack()
        source.resolveTrack(original)?.let { return it }
        if (unavailablePolicy == UnavailablePlaybackPolicy.Skip) {
            error("media not found: ${original.id}")
        }

        val candidates = replacementCandidates(
            track = original,
            smartReplacementProviderIds = smartReplacementProviderIds,
            smartReplacementMinScore = smartReplacementMinScore,
        ).filterNot { it.track.id == failedKnownReplacementId }
        val selected = selectRankedReplacementCandidate(
            candidates = candidates,
            resolve = source::resolveTrack,
        ) ?: error("media not found after smart replacement: ${original.id}")
        val (candidate, score, payload) = selected
        return annotateSmartReplacement(
            payload = payload,
            original = original,
            candidate = candidate,
            score = score,
            useOriginalMetadata = smartReplacementUseOriginalMetadata,
            useOriginalLyrics = smartReplacementUseOriginalLyrics,
        )
    }

    override suspend fun resolveSelectedReplacement(
        track: MusicTrack,
        smartReplacementUseOriginalMetadata: Boolean,
        smartReplacementUseOriginalLyrics: Boolean,
        smartReplacementProviderIds: Set<String>,
    ): PlaybackPayload {
        val replacementTrack = track.asReplacementMediaTrack()
            ?: error("selected replacement is missing: ${track.id}")
        val enabledProviderIds = registry.providers().mapTo(linkedSetOf()) { it.providerId }
        val allowedProviderIds = if (smartReplacementProviderIds.isEmpty()) {
            enabledProviderIds
        } else {
            smartReplacementProviderIds.intersect(enabledProviderIds)
        }
        if (replacementTrack.source !in allowedProviderIds) {
            error("selected replacement source is disabled: ${replacementTrack.source}")
        }
        val payload = source.resolveTrack(replacementTrack)
            ?: error("selected replacement is unavailable: ${replacementTrack.id}")
        return annotateSmartReplacement(
            payload = payload,
            original = track.asOriginalMediaTrack(),
            candidate = replacementTrack,
            score = track.replacementScore ?: 1.0,
            useOriginalMetadata = smartReplacementUseOriginalMetadata,
            useOriginalLyrics = smartReplacementUseOriginalLyrics,
        ).copy(replacementStrategy = "user_selected")
    }

    override suspend fun lyrics(track: MusicTrack): String? =
        source.lyrics(track.asOriginalMediaTrack())

    override suspend fun lyricsSearchKeyword(track: MusicTrack): String? =
        source.lyricsSearchKeyword(track.asOriginalMediaTrack())

    override fun failureMessage(throwable: Throwable, fallback: String, providerId: String?): String =
        failureMessage.invoke(throwable, fallback, providerId)
}

internal fun annotateSmartReplacement(
    payload: PlaybackPayload,
    original: MusicTrack,
    candidate: MusicTrack,
    score: Double,
    useOriginalMetadata: Boolean,
    useOriginalLyrics: Boolean,
): PlaybackPayload = payload.copy(
    // A replacement substitutes exactly one logical queue track. Provider multipart metadata is
    // provider-internal continuation and must not become queue-local Next behavior.
    parts = emptyList(),
    currentPartIndex = -1,
    isSmartReplacement = true,
    originalId = original.id,
    originalTitle = original.title,
    originalArtists = original.artists,
    originalAlbum = original.album,
    originalSource = original.source,
    originalProviderName = original.providerName,
    originalCoverUrl = original.coverUrl,
    replacementId = candidate.id,
    replacementTitle = candidate.title,
    replacementArtists = candidate.artists,
    replacementAlbum = candidate.album,
    replacementSource = candidate.source,
    replacementProviderName = candidate.providerName,
    replacementCoverUrl = candidate.coverUrl,
    replacementStrategy = "title_artist_duration",
    replacementScore = score,
    title = if (useOriginalMetadata) original.title else candidate.title,
    artists = if (useOriginalMetadata) original.artists else candidate.artists,
    album = if (useOriginalMetadata) original.album else candidate.album,
    coverUrl = if (useOriginalMetadata) original.coverUrl else candidate.coverUrl,
    lyrics = if (useOriginalLyrics) original.lyrics else payload.lyrics,
)

private fun MusicTrack.asOriginalMediaTrack(): MusicTrack {
    if (!isSmartReplacement) return this
    val restoredId = originalId?.takeIf { it.isNotBlank() } ?: id
    val restoredSource = originalSource?.takeIf { it.isNotBlank() }
        ?: providerIdFromResource(restoredId)
        ?: source
    return copy(
        id = restoredId,
        providerId = restoredId,
        source = restoredSource,
        providerName = originalProviderName ?: providerName,
        title = originalTitle ?: title,
        artists = originalArtists ?: artists,
        album = originalAlbum ?: album,
        coverUrl = originalCoverUrl ?: coverUrl,
        isSmartReplacement = false,
        originalId = null,
        originalTitle = null,
        originalArtists = null,
        originalAlbum = null,
        originalSource = null,
        originalProviderName = null,
        originalCoverUrl = null,
        replacementId = null,
        replacementTitle = null,
        replacementArtists = null,
        replacementAlbum = null,
        replacementSource = null,
        replacementProviderName = null,
        replacementCoverUrl = null,
        replacementStrategy = null,
        replacementScore = null,
    )
}

private fun MusicTrack.asReplacementMediaTrack(): MusicTrack? {
    val restoredId = replacementId?.takeIf { it.isNotBlank() } ?: return null
    val restoredSource = replacementSource?.takeIf { it.isNotBlank() }
        ?: providerIdFromResource(restoredId)
        ?: return null
    return copy(
        id = restoredId,
        providerId = restoredId,
        source = restoredSource,
        providerName = replacementProviderName ?: providerName,
        title = replacementTitle ?: title,
        artists = replacementArtists ?: artists,
        album = replacementAlbum ?: album,
        coverUrl = replacementCoverUrl ?: coverUrl,
        isSmartReplacement = false,
        originalId = null,
        originalTitle = null,
        originalArtists = null,
        originalAlbum = null,
        originalSource = null,
        originalProviderName = null,
        originalCoverUrl = null,
        replacementId = null,
        replacementTitle = null,
        replacementArtists = null,
        replacementAlbum = null,
        replacementSource = null,
        replacementProviderName = null,
        replacementCoverUrl = null,
        replacementStrategy = null,
        replacementScore = null,
    )
}

private fun MusicTrack.providerSourceId(): String =
    source.takeIf { it.isNotBlank() }
        ?: providerId?.let(::providerIdFromResource)
        ?: providerIdFromResource(id)
        ?: ""

private fun providerIdFromResource(value: String): String? =
    value.substringBefore(':').takeIf { it.isNotBlank() && it != value }

private fun replacementScore(origin: MusicTrack, candidate: MusicTrack): Double {
    if (candidate.source == BILIBILI_PROVIDER_ID) return bilibiliReplacementScore(origin, candidate)
    val originTitle = normalizeReplacementText(origin.title)
    val candidateTitle = normalizeReplacementText(candidate.title)
    val titleScore = when {
        originTitle == candidateTitle -> 1.0
        originTitle.contains(candidateTitle) || candidateTitle.contains(originTitle) -> 0.8
        else -> tokenSimilarity(originTitle, candidateTitle)
    }
    val originArtists = normalizeReplacementText(origin.artists)
    val candidateArtists = normalizeReplacementText(candidate.artists)
    val artistScore = when {
        originArtists == candidateArtists -> 1.0
        originArtists.contains(candidateArtists) || candidateArtists.contains(originArtists) -> 0.8
        else -> tokenSimilarity(originArtists, candidateArtists)
    }
    val durationScore = if (origin.durationMs == null || candidate.durationMs == null) {
        0.5
    } else {
        (1.0 - (abs(origin.durationMs - candidate.durationMs).toDouble() / 30_000.0)).coerceIn(0.0, 1.0)
    }
    return titleScore * 0.55 + artistScore * 0.35 + durationScore * 0.10
}

private fun tokenSimilarity(left: String, right: String): Double {
    if (left.isBlank() || right.isBlank()) return 0.0
    val leftSet = left.toSet()
    val rightSet = right.toSet()
    val common = leftSet.intersect(rightSet).size.toDouble()
    return common / maxOf(leftSet.size, rightSet.size).toDouble()
}

internal fun rankReplacementCandidates(
    candidates: List<MusicTrack>,
    minScore: Double,
    scoreOf: (MusicTrack) -> Double,
): List<ReplacementCandidate> = candidates
    .map { candidate -> ReplacementCandidate(candidate, scoreOf(candidate)) }
    .filter { candidate -> candidate.score >= minScore }
    .sortedByDescending { candidate -> candidate.score }
    .distinctBy { candidate -> candidate.track.id }

internal suspend fun <T> selectRankedReplacementCandidate(
    candidates: List<ReplacementCandidate>,
    resolve: suspend (MusicTrack) -> T?,
): Triple<MusicTrack, Double, T>? {
    for (candidate in candidates) {
        val resolved = resolve(candidate.track) ?: continue
        return Triple(candidate.track, candidate.score, resolved)
    }
    return null
}

internal suspend fun <T> selectReplacementCandidate(
    candidates: List<MusicTrack>,
    minScore: Double,
    scoreOf: (MusicTrack) -> Double,
    resolve: suspend (MusicTrack) -> T?,
): Triple<MusicTrack, Double, T>? = selectRankedReplacementCandidate(
    candidates = rankReplacementCandidates(candidates, minScore, scoreOf),
    resolve = resolve,
)

internal fun bilibiliReplacementScore(origin: MusicTrack, candidate: MusicTrack): Double {
    val originTitle = normalizeReplacementTitle(origin.title)
    val candidateTitle = normalizeReplacementTitle(candidate.title)
    if (originTitle.isBlank() || candidateTitle.isBlank()) return 0.0

    var score = when {
        originTitle == candidateTitle -> BILIBILI_TITLE_EXACT_SCORE
        originTitle in candidateTitle || candidateTitle in originTitle -> BILIBILI_TITLE_CONTAINS_SCORE
        else -> BILIBILI_TITLE_EXACT_SCORE * replacementTextSimilarity(originTitle, candidateTitle)
    }

    val originArtists = replacementArtistMatchTexts(origin.artists)
    val candidateUploader = normalizeReplacementText(candidate.artists)
    val candidateRawTitle = normalizeReplacementText(candidate.title)
    val uploaderMatchesArtist = originArtists.any { artist -> replacementArtistMatches(artist, candidateUploader) }
    val titleMentionsArtist = originArtists.any { artist -> replacementArtistMatches(artist, candidateRawTitle) }
    score += when {
        uploaderMatchesArtist -> BILIBILI_UPLOADER_ARTIST_SCORE
        titleMentionsArtist -> BILIBILI_TITLE_ARTIST_SCORE
        else -> 0.0
    }

    val originVersions = replacementVersionKinds("${origin.title} ${origin.album}")
    val candidateTitleVersions = replacementVersionKinds(candidate.title)
    val candidateTagVersions = replacementVersionKinds(candidate.providerTags.joinToString(" "))
    val candidateVersions = candidateTitleVersions.ifEmpty { candidateTagVersions }
    score += BILIBILI_VERSION_SCORE * replacementVersionCompatibility(
        originVersions = originVersions,
        candidateVersions = candidateVersions,
        uploaderMatchesArtist = uploaderMatchesArtist,
    )
    if (hasReplacementVersionConflict(originVersions, candidateVersions, uploaderMatchesArtist)) {
        score -= BILIBILI_VERSION_CONFLICT_PENALTY
    }

    if (uploaderMatchesArtist) {
        score += when {
            BILIBILI_OFFICIAL_MEDIA_KEYWORDS.any { keyword -> keyword in candidateRawTitle } -> BILIBILI_OFFICIAL_MEDIA_SCORE
            "musicvideo" in candidateRawTitle || "mv" in candidateRawTitle -> BILIBILI_MV_SCORE
            else -> 0.0
        }
    }
    if (BILIBILI_QUALITY_KEYWORDS.any { keyword -> keyword in candidateRawTitle }) {
        score += BILIBILI_QUALITY_SCORE
    }

    score += replacementDurationScore(origin.durationMs, candidate.durationMs)
    return score.coerceIn(0.0, 1.0)
}

private enum class ReplacementVersionKind { COVER, REMIX, LIVE, INSTRUMENTAL }

private fun normalizeReplacementTitle(value: String): String {
    var title = value
    REPLACEMENT_TITLE_DECORATION_PATTERNS.forEach { pattern -> title = pattern.replace(title, " ") }
    return normalizeReplacementText(title)
}

private fun replacementVersionKinds(value: String): Set<ReplacementVersionKind> = buildSet {
    if (REPLACEMENT_COVER_PATTERNS.any { it.containsMatchIn(value) }) add(ReplacementVersionKind.COVER)
    if (REPLACEMENT_REMIX_PATTERNS.any { it.containsMatchIn(value) }) add(ReplacementVersionKind.REMIX)
    if (REPLACEMENT_LIVE_PATTERNS.any { it.containsMatchIn(value) }) add(ReplacementVersionKind.LIVE)
    if (REPLACEMENT_INSTRUMENTAL_PATTERNS.any { it.containsMatchIn(value) }) add(ReplacementVersionKind.INSTRUMENTAL)
}

private fun replacementVersionCompatibility(
    originVersions: Set<ReplacementVersionKind>,
    candidateVersions: Set<ReplacementVersionKind>,
    uploaderMatchesArtist: Boolean,
): Double = when {
    originVersions == candidateVersions -> 1.0
    originVersions.isEmpty() && candidateVersions.isNotEmpty() -> if (uploaderMatchesArtist) 0.5 else 0.0
    originVersions.isNotEmpty() && candidateVersions.isEmpty() -> 0.5
    originVersions.intersect(candidateVersions).isNotEmpty() -> 0.6
    else -> 0.0
}

private fun hasReplacementVersionConflict(
    originVersions: Set<ReplacementVersionKind>,
    candidateVersions: Set<ReplacementVersionKind>,
    uploaderMatchesArtist: Boolean,
): Boolean = when {
    originVersions.isNotEmpty() && candidateVersions.isNotEmpty() -> originVersions.intersect(candidateVersions).isEmpty()
    originVersions.isEmpty() && candidateVersions.isNotEmpty() -> !uploaderMatchesArtist
    originVersions.isNotEmpty() && candidateVersions.isEmpty() -> !uploaderMatchesArtist
    else -> false
}

private fun replacementDurationScore(originDurationMs: Long?, candidateDurationMs: Long?): Double {
    val originDuration = originDurationMs?.takeIf { it > 0 } ?: return BILIBILI_UNKNOWN_DURATION_SCORE
    val candidateDuration = candidateDurationMs?.takeIf { it > 0 } ?: return BILIBILI_UNKNOWN_DURATION_SCORE
    return when (abs(originDuration - candidateDuration)) {
        in 0L..3_000L -> 0.10
        in 3_001L..8_000L -> 0.095
        in 8_001L..15_000L -> 0.08
        in 15_001L..30_000L -> 0.04
        else -> 0.0
    }
}

private fun replacementTextSimilarity(left: String, right: String): Double {
    if (left.isBlank() || right.isBlank()) return 0.0
    val leftSet = left.toSet()
    val rightSet = right.toSet()
    return leftSet.intersect(rightSet).size.toDouble() / maxOf(leftSet.size, rightSet.size).toDouble()
}

private fun replacementArtistMatches(artist: String, value: String): Boolean {
    if (artist.length < 2 || value.length < 2) return false
    return artist in value || value in artist
}

private fun normalizeReplacementText(value: String): String =
    value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

private fun replacementArtistMatchTexts(value: String): List<String> {
    var parts = listOf(value)
    REPLACEMENT_ARTIST_SEPARATORS.forEach { separator -> parts = parts.flatMap { it.split(separator) } }
    return parts.map { normalizeReplacementText(it.trim()) }.filter { it.isNotBlank() }.distinct()
}

/**
 * Re-ranks candidates that already passed the configured replacement score threshold. Recording
 * identity is structural and therefore precedes the legacy score when versions conflict.
 */
internal fun sortReplacementScoreTies(
    origin: MusicTrack,
    ranked: List<ReplacementCandidate>,
): List<ReplacementCandidate> {
    if (ranked.size < 2) return ranked
    return ranked.withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<ReplacementCandidate>> {
                replacementRecordingVersionRank(origin, it.value.track)
            }
                .thenByDescending { replacementCollaborationCoverage(origin, it.value.track) }
                .thenByDescending { it.value.score }
                .thenByDescending { replacementTieBreakConfidence(origin, it.value.track) }
                .thenBy { it.index },
        )
        .map { it.value }
}

internal fun replacementTieBreakConfidence(origin: MusicTrack, candidate: MusicTrack): Double {
    val titleScore = orderedTextSimilarity(origin.title, candidate.title)
    val artistScore = artistSetSimilarity(origin.artists, candidate.artists)
    val albumScore = exactMetadataMatch(origin.album, candidate.album)
    val durationScore = durationCloseness(origin.durationMs, candidate.durationMs)
    return titleScore * 0.45 + artistScore * 0.30 + albumScore * 0.15 + durationScore * 0.10
}

private fun replacementRecordingVersionRank(origin: MusicTrack, candidate: MusicTrack): Int {
    val originVersions = recordingVersionKinds("${origin.title} ${origin.album}")
    val candidateVersions = recordingVersionKinds(
        buildString {
            append(candidate.title)
            append(' ')
            append(candidate.album)
            if (candidate.providerTags.isNotEmpty()) {
                append(' ')
                append(candidate.providerTags.joinToString(" "))
            }
        },
    )
    return when {
        originVersions == candidateVersions -> 3
        originVersions.isEmpty() && candidateVersions.isNotEmpty() -> 0
        originVersions.isNotEmpty() && candidateVersions.isEmpty() -> 1
        originVersions.intersect(candidateVersions).isNotEmpty() -> 1
        else -> 0
    }
}

private fun replacementCollaborationCoverage(origin: MusicTrack, candidate: MusicTrack): Double {
    val originArtists = splitArtists(origin.artists)
    if (originArtists.size <= 1) return 1.0
    val evidence = normalizeTieBreakText("${candidate.title} ${candidate.artists}")
    if (evidence.isBlank()) return 0.0
    val matched = originArtists.count { artist -> artist.length >= 2 && artist in evidence }
    return matched.toDouble() / originArtists.size.toDouble()
}

private enum class RecordingVersionKind { COVER, REMIX, LIVE, INSTRUMENTAL, ACOUSTIC, DEMO, SOLO }

private fun recordingVersionKinds(value: String): Set<RecordingVersionKind> = buildSet {
    if (RECORDING_COVER_PATTERNS.any { it.containsMatchIn(value) }) add(RecordingVersionKind.COVER)
    if (RECORDING_REMIX_PATTERNS.any { it.containsMatchIn(value) }) add(RecordingVersionKind.REMIX)
    if (RECORDING_LIVE_PATTERNS.any { it.containsMatchIn(value) }) add(RecordingVersionKind.LIVE)
    if (RECORDING_INSTRUMENTAL_PATTERNS.any { it.containsMatchIn(value) }) add(RecordingVersionKind.INSTRUMENTAL)
    if (RECORDING_ACOUSTIC_PATTERNS.any { it.containsMatchIn(value) }) add(RecordingVersionKind.ACOUSTIC)
    if (RECORDING_DEMO_PATTERNS.any { it.containsMatchIn(value) }) add(RecordingVersionKind.DEMO)
    if (RECORDING_SOLO_PATTERNS.any { it.containsMatchIn(value) }) add(RecordingVersionKind.SOLO)
}

private fun orderedTextSimilarity(leftValue: String, rightValue: String): Double {
    val left = normalizeTieBreakText(leftValue)
    val right = normalizeTieBreakText(rightValue)
    if (left.isBlank() || right.isBlank()) return 0.0
    if (left == right) return 1.0
    val longest = maxOf(left.length, right.length)
    val distance = levenshteinDistance(left, right)
    return (1.0 - distance.toDouble() / longest.toDouble()).coerceIn(0.0, 1.0)
}

private fun artistSetSimilarity(leftValue: String, rightValue: String): Double {
    val left = splitArtists(leftValue)
    val right = splitArtists(rightValue)
    if (left.isEmpty() || right.isEmpty()) return 0.0
    if (left == right) return 1.0
    val intersection = left.intersect(right).size.toDouble()
    val union = left.union(right).size.toDouble()
    return if (union == 0.0) 0.0 else intersection / union
}

private fun exactMetadataMatch(leftValue: String, rightValue: String): Double {
    val left = normalizeTieBreakText(leftValue)
    val right = normalizeTieBreakText(rightValue)
    if (left.isBlank() || right.isBlank()) return 0.0
    return if (left == right) 1.0 else 0.0
}

private fun durationCloseness(left: Long?, right: Long?): Double {
    val leftDuration = left?.takeIf { it > 0 } ?: return 0.0
    val rightDuration = right?.takeIf { it > 0 } ?: return 0.0
    return when (abs(leftDuration - rightDuration)) {
        in 0L..3_000L -> 1.0
        in 3_001L..10_000L -> 0.5
        else -> 0.0
    }
}

private fun splitArtists(value: String): Set<String> {
    val expanded = Regex("(?i)\\b(feat|featuring|ft|with)\\b\\.?").replace(value, "/")
    return Regex("\\s*(?:/|、|,|，|;|；|&|\\+|＋|×)\\s*")
        .split(expanded)
        .map(::normalizeTieBreakText)
        .filter { it.isNotBlank() }
        .toSet()
}

private fun levenshteinDistance(left: String, right: String): Int {
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length
    var previous = IntArray(right.length + 1) { it }
    var current = IntArray(right.length + 1)
    left.forEachIndexed { leftIndex, leftChar ->
        current[0] = leftIndex + 1
        right.forEachIndexed { rightIndex, rightChar ->
            val insertion = current[rightIndex] + 1
            val deletion = previous[rightIndex + 1] + 1
            val substitution = previous[rightIndex] + if (leftChar == rightChar) 0 else 1
            current[rightIndex + 1] = minOf(insertion, deletion, substitution)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[right.length]
}

private fun normalizeTieBreakText(value: String): String =
    value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

private const val BILIBILI_PROVIDER_ID = "bilibili"
private const val BILIBILI_TITLE_EXACT_SCORE = 0.45
private const val BILIBILI_TITLE_CONTAINS_SCORE = 0.42
private const val BILIBILI_UPLOADER_ARTIST_SCORE = 0.25
private const val BILIBILI_TITLE_ARTIST_SCORE = 0.10
private const val BILIBILI_VERSION_SCORE = 0.10
private const val BILIBILI_VERSION_CONFLICT_PENALTY = 0.15
private const val BILIBILI_OFFICIAL_MEDIA_SCORE = 0.10
private const val BILIBILI_MV_SCORE = 0.05
private const val BILIBILI_QUALITY_SCORE = 0.02
private const val BILIBILI_UNKNOWN_DURATION_SCORE = 0.05
private val BILIBILI_OFFICIAL_MEDIA_KEYWORDS = listOf("officialmusicvideo", "officialvideo", "officialmv")
private val BILIBILI_QUALITY_KEYWORDS = listOf("hires")
private val REPLACEMENT_COVER_PATTERNS = listOf(
    Regex("\\bcover\\b", RegexOption.IGNORE_CASE), Regex("翻唱"), Regex("歌ってみた"), Regex("弾いてみた"),
)
private val REPLACEMENT_REMIX_PATTERNS = listOf(Regex("\\bremix\\b", RegexOption.IGNORE_CASE), Regex("重混"))
private val REPLACEMENT_LIVE_PATTERNS = listOf(
    Regex("\\blive\\b", RegexOption.IGNORE_CASE), Regex("现场"), Regex("現場"), Regex("ライブ"),
)
private val REPLACEMENT_INSTRUMENTAL_PATTERNS = listOf(
    Regex("\\binstrumental\\b", RegexOption.IGNORE_CASE),
    Regex("\\boff[ -]?vocal\\b", RegexOption.IGNORE_CASE),
    Regex("\\bkaraoke\\b", RegexOption.IGNORE_CASE),
    Regex("伴奏"), Regex("纯音乐"), Regex("純音樂"),
)
private val REPLACEMENT_MEDIA_DECORATION_PATTERNS = listOf(
    Regex("\\bofficial[ -]?(?:music[ -]?)?video\\b", RegexOption.IGNORE_CASE),
    Regex("\\bofficial[ -]?mv\\b", RegexOption.IGNORE_CASE),
    Regex("\\bmusic[ -]?video\\b", RegexOption.IGNORE_CASE),
    Regex("\\bmv\\b", RegexOption.IGNORE_CASE),
    Regex("\\bhi[ -]?res\\b", RegexOption.IGNORE_CASE),
)
private val REPLACEMENT_TITLE_DECORATION_PATTERNS =
    REPLACEMENT_COVER_PATTERNS + REPLACEMENT_REMIX_PATTERNS + REPLACEMENT_LIVE_PATTERNS +
        REPLACEMENT_INSTRUMENTAL_PATTERNS + REPLACEMENT_MEDIA_DECORATION_PATTERNS
private val REPLACEMENT_ARTIST_SEPARATORS = listOf(" / ", "/", "、", ",", "，", ";", "；", "&", "+", "＋")

private val RECORDING_COVER_PATTERNS = listOf(
    Regex("(?i)(?:^|[^a-z0-9])cover(?:$|[^a-z0-9])"), Regex("翻唱"), Regex("歌ってみた"), Regex("弾いてみた"),
)
private val RECORDING_REMIX_PATTERNS = listOf(Regex("(?i)(?:^|[^a-z0-9])remix(?:$|[^a-z0-9])"), Regex("重混"))
private val RECORDING_LIVE_PATTERNS = listOf(
    Regex("(?i)(?:^|[^a-z0-9])live(?:$|[^a-z0-9])"), Regex("现场"), Regex("現場"), Regex("ライブ"),
)
private val RECORDING_INSTRUMENTAL_PATTERNS = listOf(
    Regex("(?i)(?:^|[^a-z0-9])instrumental(?:$|[^a-z0-9])"),
    Regex("(?i)(?:^|[^a-z0-9])off[ -]?vocal(?:$|[^a-z0-9])"),
    Regex("(?i)(?:^|[^a-z0-9])karaoke(?:$|[^a-z0-9])"),
    Regex("伴奏"), Regex("纯音乐"), Regex("純音樂"),
)
private val RECORDING_ACOUSTIC_PATTERNS = listOf(
    Regex("(?i)(?:^|[^a-z0-9])acoustic(?:$|[^a-z0-9])"),
    Regex("(?i)(?:^|[^a-z0-9])unplugged(?:$|[^a-z0-9])"), Regex("不插电"), Regex("不插電"),
)
private val RECORDING_DEMO_PATTERNS = listOf(
    Regex("(?i)(?:^|[^a-z0-9])demo(?:$|[^a-z0-9])"), Regex("小样"), Regex("小樣"),
)
private val RECORDING_SOLO_PATTERNS = listOf(
    Regex("(?i)(?:^|[^a-z0-9])solo(?:$|[^a-z0-9])"), Regex("独唱"), Regex("獨唱"),
)
