package org.feeluown.mobile

/**
 * Home owns the information architecture. Provider features only supply content
 * and are mapped into stable roles before Compose decides how to present them.
 */
internal enum class HomeContentRole {
    DailyRecommendation,
    PersonalRadio,
    RecommendedContent,
    NewMusic,
    PlaylistDiscovery,
    Charts,
    NewAlbums,
    Artists,
    Videos,
    Styles,
    Other,
}

internal fun ProviderFeature.homeContentRole(): HomeContentRole = when {
    isDailySongs() -> HomeContentRole.DailyRecommendation
    isPrivateFm() -> HomeContentRole.PersonalRadio
    isBilibiliRecommendedVideos() || isBilibiliDynamicVideos() -> HomeContentRole.RecommendedContent
    isRecommendedNewSongs() || id.endsWith("_new_songs") -> HomeContentRole.NewMusic
    id.contains("toplist", ignoreCase = true) || isBilibiliWeeklyMustWatch() -> HomeContentRole.Charts
    id.contains("new_album", ignoreCase = true) -> HomeContentRole.NewAlbums
    id.contains("style", ignoreCase = true) || id.contains("mood", ignoreCase = true) -> HomeContentRole.Styles
    contentType == ProviderContentType.Artists -> HomeContentRole.Artists
    contentType == ProviderContentType.Videos -> HomeContentRole.Videos
    contentType == ProviderContentType.Playlists -> HomeContentRole.PlaylistDiscovery
    else -> HomeContentRole.Other
}

internal fun ProviderContentSection.homeContentRole(): HomeContentRole = feature.homeContentRole()

internal fun List<ProviderContentSection>.homeProviderLabel(): String =
    map { it.feature.providerName }
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(" / ")

internal fun List<ProviderContentSection>.homeActionSections(): List<ProviderContentSection> =
    sortedWith(
        compareBy<ProviderContentSection> {
            when (it.homeContentRole()) {
                HomeContentRole.DailyRecommendation -> 0
                HomeContentRole.PersonalRadio -> 1
                HomeContentRole.RecommendedContent -> 2
                HomeContentRole.NewMusic -> 3
                else -> 4
            }
        }.thenBy { it.feature.providerName },
    )

internal fun List<ProviderContentSection>.homeExploreEntries(): List<ProviderContentSection> =
    sortedWith(
        compareBy<ProviderContentSection> {
            when (it.homeContentRole()) {
                HomeContentRole.Charts -> 0
                HomeContentRole.PlaylistDiscovery -> 1
                HomeContentRole.NewMusic -> 2
                HomeContentRole.NewAlbums -> 3
                HomeContentRole.Artists -> 4
                HomeContentRole.Styles -> 5
                HomeContentRole.Videos -> 6
                else -> 7
            }
        }.thenBy { it.feature.providerName },
    )
