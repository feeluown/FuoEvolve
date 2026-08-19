package org.feeluown.mobile

import kotlinx.serialization.Serializable

@Serializable
enum class SearchScope {
    Local,
    Provider,
    All,
}

@Serializable
enum class ProviderSearchTab {
    Songs,
    Artists,
    Albums,
    Playlists,
    Videos,
}

@Serializable
enum class HomeSection {
    Recommend,
    Music,
    Mine,
}

@Serializable
enum class ProviderDisplaySection(val label: String) {
    Search("搜索"),
    Recommend("推荐"),
    Explore("探索"),
    Mine("我的"),
    Replace("替换"),
}

@Serializable
enum class MineSection {
    Playlists,
    Songs,
    Artists,
    Albums,
    LocalMusic,
}

@Serializable
enum class PlaylistFilter {
    All,
    UserPlaylists,
    FavoritePlaylists,
    Local,
}

@Serializable
enum class LocalMusicViewMode {
    All,
    Artist,
    Album,
}

data class LocalMusicCollectionSelection(
    val mode: LocalMusicViewMode,
    val key: String,
)

data class TrackArtistTarget(
    val name: String,
    val mediaItem: ProviderMediaItem? = null,
)

enum class PlaylistTargetType {
    Provider,
    Local,
}
