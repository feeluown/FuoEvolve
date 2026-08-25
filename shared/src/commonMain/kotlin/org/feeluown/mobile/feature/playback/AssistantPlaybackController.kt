package org.feeluown.mobile

/**
 * Resolves assistant/media-session text searches into the existing playback transaction path.
 *
 * The controller deliberately returns only a durable [MusicTrack]. Android MediaSession and
 * vendor-specific voice adapters both feed text into this boundary, while the actual playback
 * start stays owned by the playback feature.
 */
class AssistantPlaybackController(
    private val providerSearchRepository: ProviderSearchRepository,
    private val localSearch: suspend (String) -> List<MusicTrack>,
    private val providerIdsForSearch: suspend () -> List<String>,
    private val startPlayback: (MusicTrack) -> Unit,
) {
    suspend fun playFromSearch(rawQuery: String): Boolean {
        val query = rawQuery.trim()
        if (query.isBlank()) return false

        val localTracks = runCatching { localSearch(query) }.getOrDefault(emptyList())
        val providerTracks = providerIdsForSearch()
            .distinct()
            .flatMap { providerId ->
                runCatching { providerSearchRepository.search(query, providerId) }
                    .getOrDefault(emptyList())
            }
        val tracks = (localTracks + providerTracks).distinctBy(::assistantTrackIdentity)
        val selected = selectAssistantSearchTrack(query, tracks) ?: return false
        startPlayback(selected)
        return true
    }
}

internal fun selectAssistantSearchTrack(query: String, tracks: List<MusicTrack>): MusicTrack? {
    return tracks.withIndex()
        .map { indexed -> indexed to assistantSearchScore(query, indexed.value) }
        .filter { (_, score) -> score > 0 }
        .maxWithOrNull(
            compareBy<Pair<IndexedValue<MusicTrack>, Int>> { it.second }
                .thenBy { -it.first.index },
        )
        ?.first
        ?.value
}

private fun assistantSearchScore(query: String, track: MusicTrack): Int {
    val normalizedQuery = query.assistantComparableText()
    val normalizedTitle = track.title.assistantComparableText()
    if (normalizedQuery.isBlank() || normalizedTitle.isBlank()) return 0

    if (normalizedQuery == normalizedTitle) return 1_000

    var score = 0
    if (normalizedQuery.contains(normalizedTitle)) score += 520
    if (normalizedTitle.contains(normalizedQuery)) score += 480

    track.artists
        .split('/', '、', ',', '，', '&')
        .map(String::assistantComparableText)
        .filter(String::isNotBlank)
        .distinct()
        .forEach { artist ->
            when {
                normalizedQuery == artist -> score += 260
                normalizedQuery.contains(artist) -> score += 220
                artist.contains(normalizedQuery) -> score += 120
            }
        }

    val combined = (track.title + track.artists).assistantComparableText()
    if (score == 0 && combined.contains(normalizedQuery)) score = 180
    return score
}

private fun String.assistantComparableText(): String = buildString(length) {
    this@assistantComparableText.lowercase().forEach { char ->
        if (char.isLetterOrDigit()) append(char)
    }
}

private fun assistantTrackIdentity(track: MusicTrack): String =
    listOf(track.source, track.providerId.orEmpty(), track.id).joinToString("\u0000")
