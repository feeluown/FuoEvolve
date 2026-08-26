package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.feeluown.mobile.feature.home.HomeCatalogPort as CoreHomeCatalogPort
import org.feeluown.mobile.feature.home.HomeCatalogSnapshot as CoreHomeCatalogSnapshot
import org.feeluown.mobile.feature.home.HomeContentPort as CoreHomeContentPort
import org.feeluown.mobile.feature.home.HomeDisplaySurface as CoreHomeDisplaySurface
import org.feeluown.mobile.feature.home.HomeFeatureKind as CoreHomeFeatureKind
import org.feeluown.mobile.feature.home.HomeFeatureOwner as CoreHomeFeatureOwner
import org.feeluown.mobile.feature.home.HomeLocalLibraryPort as CoreHomeLocalLibraryPort
import org.feeluown.mobile.feature.home.HomeMineSection as CoreHomeMineSection
import org.feeluown.mobile.feature.home.HomeMutationResult as CoreHomeMutationResult
import org.feeluown.mobile.feature.home.HomePlaybackPort as CoreHomePlaybackPort
import org.feeluown.mobile.feature.home.HomePlaylistFilter as CoreHomePlaylistFilter
import org.feeluown.mobile.feature.home.HomePreferencesPort as CoreHomePreferencesPort
import org.feeluown.mobile.feature.home.HomePreferencesSnapshot as CoreHomePreferencesSnapshot
import org.feeluown.mobile.feature.home.HomeTopSection as CoreHomeTopSection
import org.feeluown.mobile.feature.home.createHomeFeatureOwner

private const val HOME_PLAYLIST_STATS_KEY_SEPARATOR = "::"

data class HomeFeatureUiState(
    val homeSection: HomeSection = HomeSection.Recommend,
    val mineSection: MineSection = MineSection.Playlists,
    val playlistFilter: PlaylistFilter = PlaylistFilter.UserPlaylists,
    val recommendSections: List<ProviderContentSection> = emptyList(),
    val exploreSections: List<ProviderContentSection> = emptyList(),
    val mineSections: List<ProviderContentSection> = emptyList(),
    val minePlaylistSections: List<ProviderContentSection> = emptyList(),
    val mineFavoritePlaylistSections: List<ProviderContentSection> = emptyList(),
    val playlistPlaybackStats: Map<String, PlaylistPlaybackStat> = emptyMap(),
    val isLoading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

interface HomeFeatureController {
    val uiState: StateFlow<HomeFeatureUiState>
    fun openSettings(providerId: String? = null)
    fun openSearch()
    fun setHomeSection(section: HomeSection)
    fun setMineSection(section: MineSection)
    fun setPlaylistFilter(filter: PlaylistFilter)
    fun refreshHome(section: HomeSection)
    fun refreshMine()
    fun ensureInitialContent()
    fun openFeature(feature: ProviderFeature)
    fun openPlaylist(playlist: ProviderPlaylist, category: ProviderFeatureCategory? = null)
    fun openMediaItem(item: ProviderMediaItem)
    fun openVideo(video: ProviderVideo)
    fun playFeature(section: ProviderContentSection, index: Int = 0)
    fun playAllFeature(section: ProviderContentSection)
    fun createProviderPlaylist(providerId: String, name: String)
    fun creatablePlaylistProviders(): List<ProviderInfo>
    fun categoryForMinePlaylist(playlist: ProviderPlaylist): ProviderFeatureCategory
}

fun createHomeFeatureController(
    providerRepository: ProviderContentRepository,
    providerCatalog: ProviderCatalogFeatureController,
    providerDetails: ProviderDetailOwners,
    playbackQueue: PlaybackQueueUiPort,
    localPlaylist: LocalPlaylistFeatureController,
    localMusic: LocalMusicFeatureController,
    settingsRepository: AppSettingsRepository,
    navigator: AppNavigator,
    scope: CoroutineScope,
): HomeFeatureController {
    val owner = createHomeFeatureOwner(
        preferences = HomePreferencesBinding(settingsRepository, scope),
        catalog = HomeCatalogBinding(providerCatalog, scope),
        content = HomeContentBinding(providerRepository),
        playback = HomePlaybackBinding(playbackQueue),
        localLibrary = HomeLocalLibraryBinding(localPlaylist, localMusic),
        scope = scope,
    )
    return BoundHomeFeatureController(owner, providerDetails, settingsRepository, navigator, scope)
}

private class BoundHomeFeatureController(
    private val owner: CoreHomeFeatureOwner<ProviderInfo, ProviderFeature, ProviderContentSection, MusicTrack, ProviderPlaylist, Map<String, PlaylistPlaybackStat>>,
    private val providerDetails: ProviderDetailOwners,
    private val settingsRepository: AppSettingsRepository,
    private val navigator: AppNavigator,
    private val scope: CoroutineScope,
) : HomeFeatureController {
    override val uiState: StateFlow<HomeFeatureUiState> = owner.state.map { state ->
        HomeFeatureUiState(
            homeSection = state.homeSection.toApp(),
            mineSection = state.mineSection.toApp(),
            playlistFilter = state.playlistFilter.toApp(),
            recommendSections = state.recommendSections,
            exploreSections = state.exploreSections,
            mineSections = state.mineSections,
            minePlaylistSections = state.minePlaylistSections,
            mineFavoritePlaylistSections = state.mineFavoritePlaylistSections,
            playlistPlaybackStats = state.playlistPlaybackStats,
            isLoading = state.isLoading,
            message = state.message,
            errorMessage = state.errorMessage,
        )
    }.stateIn(scope, SharingStarted.Eagerly, owner.state.value.toUiState())

    override fun openSettings(providerId: String?) {
        if (providerId == null) { navigator.navigate(AppRoute.Settings); return }
        scope.launch {
            settingsRepository.update { it.copy(selectedSettingsProviderId = providerId) }
            navigator.navigate(AppRoute.Settings)
        }
    }
    override fun openSearch() = navigator.navigate(AppRoute.Search)
    override fun setHomeSection(section: HomeSection) = owner.setHomeSection(section.toCore())
    override fun setMineSection(section: MineSection) = owner.setMineSection(section.toCore())
    override fun setPlaylistFilter(filter: PlaylistFilter) = owner.setPlaylistFilter(filter.toCore())
    override fun refreshHome(section: HomeSection) = owner.refreshHome(section.toCore())
    override fun refreshMine() = owner.refreshMine()
    override fun ensureInitialContent() = owner.ensureInitialContent()
    override fun openFeature(feature: ProviderFeature) = providerDetails.feature.open(feature)
    override fun openPlaylist(playlist: ProviderPlaylist, category: ProviderFeatureCategory?) = providerDetails.playlist.open(playlist, category)
    override fun openMediaItem(item: ProviderMediaItem) = providerDetails.mediaItem.open(item)
    override fun openVideo(video: ProviderVideo) = providerDetails.video.open(video)
    override fun playFeature(section: ProviderContentSection, index: Int) = owner.playFeature(section, index)
    override fun playAllFeature(section: ProviderContentSection) = owner.playAllFeature(section)
    override fun createProviderPlaylist(providerId: String, name: String) = owner.createProviderPlaylist(providerId, name)
    override fun creatablePlaylistProviders(): List<ProviderInfo> = owner.creatablePlaylistProviders()
    override fun categoryForMinePlaylist(playlist: ProviderPlaylist) =
        if (owner.isFavoriteMinePlaylist(playlist)) ProviderFeatureCategory.MineFavoritePlaylists else ProviderFeatureCategory.MinePlaylists
}

private class HomePreferencesBinding(
    private val delegate: AppSettingsRepository,
    scope: CoroutineScope,
) : CoreHomePreferencesPort<Map<String, PlaylistPlaybackStat>> {
    override val state: StateFlow<CoreHomePreferencesSnapshot<Map<String, PlaylistPlaybackStat>>> = delegate.state
        .map(SettingsState::toHomePreferencesSnapshot)
        .stateIn(scope, SharingStarted.Eagerly, delegate.state.value.toHomePreferencesSnapshot())
    override suspend fun setHomeSection(section: CoreHomeTopSection) { delegate.update { it.copy(homeSection = section.toApp()) } }
    override suspend fun setMineSection(section: CoreHomeMineSection) { delegate.update { it.copy(mineSection = section.toApp()) } }
    override suspend fun setPlaylistFilter(filter: CoreHomePlaylistFilter) { delegate.update { it.copy(playlistFilter = filter.toApp()) } }
}

private class HomeCatalogBinding(
    delegate: ProviderCatalogFeatureController,
    scope: CoroutineScope,
) : CoreHomeCatalogPort<ProviderInfo, ProviderFeature> {
    override val state: StateFlow<CoreHomeCatalogSnapshot<ProviderInfo, ProviderFeature>> = delegate.uiState
        .map(ProviderCatalogUiState::toHomeCatalogSnapshot)
        .stateIn(scope, SharingStarted.Eagerly, delegate.uiState.value.toHomeCatalogSnapshot())
    override fun providerId(provider: ProviderInfo) = provider.providerId
}

private class HomeContentBinding(
    private val delegate: ProviderContentRepository,
) : CoreHomeContentPort<ProviderFeature, ProviderContentSection, MusicTrack, ProviderPlaylist> {
    override suspend fun loadFeaturePage(feature: ProviderFeature, offset: Int) = delegate.loadFeaturePage(feature, offset)
    override suspend fun createPlaylist(providerId: String, name: String): CoreHomeMutationResult {
        val result = delegate.createPlaylist(providerId, name)
        return CoreHomeMutationResult(result.success, result.message)
    }
    override fun featureId(feature: ProviderFeature) = feature.id
    override fun featureProviderId(feature: ProviderFeature) = feature.providerId
    override fun featureTitle(feature: ProviderFeature) = feature.title
    override fun featureKind(feature: ProviderFeature): CoreHomeFeatureKind? = when (feature.category) {
        ProviderFeatureCategory.Recommend -> CoreHomeFeatureKind.Recommend
        ProviderFeatureCategory.Music -> CoreHomeFeatureKind.Explore
        ProviderFeatureCategory.MinePlaylists -> CoreHomeFeatureKind.MinePlaylists
        ProviderFeatureCategory.MineFavoritePlaylists -> CoreHomeFeatureKind.MineFavoritePlaylists
        ProviderFeatureCategory.Mine -> CoreHomeFeatureKind.MineContent
    }
    override fun featureRequiresLogin(feature: ProviderFeature) = feature.requiresLogin
    override fun featureContentTypeKey(feature: ProviderFeature) = feature.contentType.name
    override fun isDeferredFeature(feature: ProviderFeature) = feature.isDeferredOnHomeBinding()
    override fun isDynamicQueueFeature(feature: ProviderFeature) = feature.isDynamicQueueFeature()
    override fun contentFeature(content: ProviderContentSection) = content.feature
    override fun contentTracks(content: ProviderContentSection) = content.tracks
    override fun contentPlaylists(content: ProviderContentSection) = content.playlists
    override fun contentNextOffset(content: ProviderContentSection) = content.nextOffset
    override fun contentHasMore(content: ProviderContentSection) = content.hasMore
    override fun contentErrorMessage(content: ProviderContentSection) = content.errorMessage
    override fun loginRequiredContent(feature: ProviderFeature) = ProviderContentSection(feature = feature, isLoginRequired = true)
    override fun deferredContent(feature: ProviderFeature) = ProviderContentSection(feature = feature)
    override fun errorContent(feature: ProviderFeature, message: String) = ProviderContentSection(feature = feature, errorMessage = message)
    override fun trackKey(track: MusicTrack) = track.id
    override fun playlistKey(playlist: ProviderPlaylist) = "${playlist.providerId}$HOME_PLAYLIST_STATS_KEY_SEPARATOR${playlist.id}"
    override fun errorMessage(throwable: Throwable, providerId: String?) =
        throwable.providerFailureOrNull(providerId)?.userMessage ?: throwable.message ?: throwable::class.simpleName.orEmpty().ifBlank { "加载失败" }
}

private class HomePlaybackBinding(private val delegate: PlaybackQueueUiPort) : CoreHomePlaybackPort<ProviderFeature, MusicTrack> {
    override fun playFeatureTracks(tracks: List<MusicTrack>, index: Int, feature: ProviderFeature) = delegate.playFeatureTracks(tracks, index, feature)
}

private class HomeLocalLibraryBinding(
    private val localPlaylist: LocalPlaylistFeatureController,
    private val localMusic: LocalMusicFeatureController,
) : CoreHomeLocalLibraryPort {
    override fun refreshLocalPlaylists() = localPlaylist.refresh()
    override fun refreshLocalMusic() = localMusic.refresh()
    override fun ensureLocalMusic() = localMusic.ensure()
}

private fun SettingsState.toHomePreferencesSnapshot() = CoreHomePreferencesSnapshot(
    isLoaded = isLoaded,
    homeSection = settings.homeSection.toCore(),
    mineSection = settings.mineSection.toCore(),
    playlistFilter = settings.playlistFilter.toCore(),
    playlistPlaybackStats = settings.playlistPlaybackStats,
    errorMessage = errorMessage,
)

private fun ProviderCatalogUiState.toHomeCatalogSnapshot() = CoreHomeCatalogSnapshot(
    isInitialized = isInitialized,
    providers = providers,
    features = features,
    availableProviderIds = availableProviders.mapTo(linkedSetOf()) { it.providerId },
    enabledProviderIds = enabledProviderIds,
    displayProviderIds = mapOf(
        CoreHomeDisplaySurface.Recommend to recommendProviderIds,
        CoreHomeDisplaySurface.Explore to exploreProviderIds,
        CoreHomeDisplaySurface.Mine to mineProviderIds,
    ),
    loggedInProviderIds = sessions.authStates.filterValues { it.isLoggedIn }.keys,
    creatablePlaylistProviderIds = capabilities.filterValues { it.canCreatePlaylist }.keys,
    errorMessage = errorMessage,
)

private fun org.feeluown.mobile.feature.home.HomeFeatureState<ProviderContentSection, Map<String, PlaylistPlaybackStat>>.toUiState() = HomeFeatureUiState(
    homeSection = homeSection.toApp(), mineSection = mineSection.toApp(), playlistFilter = playlistFilter.toApp(),
    recommendSections = recommendSections, exploreSections = exploreSections, mineSections = mineSections,
    minePlaylistSections = minePlaylistSections, mineFavoritePlaylistSections = mineFavoritePlaylistSections,
    playlistPlaybackStats = playlistPlaybackStats, isLoading = isLoading, message = message, errorMessage = errorMessage,
)

private fun HomeSection.toCore() = when (this) { HomeSection.Recommend -> CoreHomeTopSection.Recommend; HomeSection.Music -> CoreHomeTopSection.Explore; HomeSection.Mine -> CoreHomeTopSection.Mine }
private fun CoreHomeTopSection.toApp() = when (this) { CoreHomeTopSection.Recommend -> HomeSection.Recommend; CoreHomeTopSection.Explore -> HomeSection.Music; CoreHomeTopSection.Mine -> HomeSection.Mine }
private fun MineSection.toCore() = when (this) { MineSection.Playlists, MineSection.Songs -> CoreHomeMineSection.Playlists; MineSection.Artists -> CoreHomeMineSection.Artists; MineSection.Albums -> CoreHomeMineSection.Albums; MineSection.LocalMusic -> CoreHomeMineSection.LocalMusic }
private fun CoreHomeMineSection.toApp() = when (this) { CoreHomeMineSection.Playlists -> MineSection.Playlists; CoreHomeMineSection.Artists -> MineSection.Artists; CoreHomeMineSection.Albums -> MineSection.Albums; CoreHomeMineSection.LocalMusic -> MineSection.LocalMusic }
private fun PlaylistFilter.toCore() = when (this) { PlaylistFilter.All, PlaylistFilter.UserPlaylists -> CoreHomePlaylistFilter.UserPlaylists; PlaylistFilter.FavoritePlaylists -> CoreHomePlaylistFilter.FavoritePlaylists; PlaylistFilter.Local -> CoreHomePlaylistFilter.Local }
private fun CoreHomePlaylistFilter.toApp() = when (this) { CoreHomePlaylistFilter.UserPlaylists -> PlaylistFilter.UserPlaylists; CoreHomePlaylistFilter.FavoritePlaylists -> PlaylistFilter.FavoritePlaylists; CoreHomePlaylistFilter.Local -> PlaylistFilter.Local }

private fun ProviderFeature.isDeferredOnHomeBinding(): Boolean {
    val deferredMusic = category == ProviderFeatureCategory.Music &&
        (contentType == ProviderContentType.Songs || contentType == ProviderContentType.Videos || isBilibiliWeeklyMustWatch())
    val deferredRecommend = category == ProviderFeatureCategory.Recommend &&
        (id.endsWith("_daily_songs") || isDynamicQueueFeature() || isBilibiliRecommendedVideos())
    return deferredMusic || deferredRecommend
}
