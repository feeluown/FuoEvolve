package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val HOME_PROVIDER_TIMEOUT_MS = 30_000L
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
    providerRepository: ProviderMusicRepository,
    providerCatalog: ProviderCatalogFeatureController,
    providerDetails: ProviderDetailOwners,
    playbackQueue: PlaybackQueueUiPort,
    localPlaylist: LocalPlaylistFeatureController,
    localMusic: LocalMusicFeatureController,
    settingsRepository: AppSettingsRepository,
    navigator: AppNavigator,
    scope: CoroutineScope,
): HomeFeatureController = DefaultHomeFeatureController(
    providerRepository = providerRepository,
    providerCatalog = providerCatalog,
    providerDetails = providerDetails,
    playbackQueue = playbackQueue,
    localPlaylist = localPlaylist,
    localMusic = localMusic,
    settingsRepository = settingsRepository,
    navigator = navigator,
    scope = scope,
)

private class DefaultHomeFeatureController(
    private val providerRepository: ProviderMusicRepository,
    private val providerCatalog: ProviderCatalogFeatureController,
    private val providerDetails: ProviderDetailOwners,
    private val playbackQueue: PlaybackQueueUiPort,
    private val localPlaylist: LocalPlaylistFeatureController,
    private val localMusic: LocalMusicFeatureController,
    private val settingsRepository: AppSettingsRepository,
    private val navigator: AppNavigator,
    private val scope: CoroutineScope,
) : HomeFeatureController {
    private val mutableUiState = MutableStateFlow(HomeFeatureUiState())
    override val uiState: StateFlow<HomeFeatureUiState> = mutableUiState.asStateFlow()

    private var recommendRefreshSerial = 0L
    private var exploreRefreshSerial = 0L
    private var minePlaylistRefreshSerial = 0L
    private var mineContentRefreshSerial = 0L
    private var initialRefreshStarted = false

    init {
        scope.launch {
            combine(settingsRepository.state, providerCatalog.uiState) { settings, catalog -> settings to catalog }
                .collect { (settingsState, catalog) ->
                    val settings = settingsState.settings
                    mutableUiState.value = mutableUiState.value.copy(
                        homeSection = settings.homeSection,
                        mineSection = settings.mineSection.normalizeMineSection(),
                        playlistFilter = settings.playlistFilter.normalizePlaylistFilter(),
                        playlistPlaybackStats = settings.playlistPlaybackStats,
                        errorMessage = mutableUiState.value.errorMessage ?: catalog.errorMessage ?: settingsState.errorMessage,
                    )
                    if (settingsState.isLoaded && catalog.isInitialized) ensureInitialContent()
                }
        }
    }

    override fun openSettings(providerId: String?) {
        if (providerId != null) {
            scope.launch {
                settingsRepository.update { it.copy(selectedSettingsProviderId = providerId) }
                navigator.navigate(AppRoute.Settings)
            }
        } else {
            navigator.navigate(AppRoute.Settings)
        }
    }

    override fun openSearch() {
        navigator.navigate(AppRoute.Search)
    }

    override fun setHomeSection(section: HomeSection) {
        if (uiState.value.homeSection == section) return
        mutableUiState.value = uiState.value.copy(homeSection = section)
        scope.launch { settingsRepository.update { it.copy(homeSection = section) } }
        when (section) {
            HomeSection.Recommend -> if (uiState.value.recommendSections.isEmpty()) refreshHome(section)
            HomeSection.Music -> if (uiState.value.exploreSections.isEmpty()) refreshHome(section)
            HomeSection.Mine -> refreshMineIfNeeded()
        }
    }

    override fun setMineSection(section: MineSection) {
        val normalized = section.normalizeMineSection()
        if (uiState.value.mineSection == normalized) return
        mutableUiState.value = uiState.value.copy(mineSection = normalized)
        scope.launch { settingsRepository.update { it.copy(mineSection = normalized) } }
        refreshMineIfNeeded()
    }

    override fun setPlaylistFilter(filter: PlaylistFilter) {
        val normalized = filter.normalizePlaylistFilter()
        if (uiState.value.playlistFilter == normalized) return
        mutableUiState.value = uiState.value.copy(playlistFilter = normalized)
        scope.launch { settingsRepository.update { it.copy(playlistFilter = normalized) } }
        if (uiState.value.minePlaylistSections.isEmpty() && uiState.value.mineFavoritePlaylistSections.isEmpty()) {
            refreshMinePlaylists()
        }
    }

    override fun ensureInitialContent() {
        if (initialRefreshStarted) return
        initialRefreshStarted = true
        when (uiState.value.homeSection) {
            HomeSection.Recommend -> refreshHome(HomeSection.Recommend)
            HomeSection.Music -> refreshHome(HomeSection.Music)
            HomeSection.Mine -> refreshMineIfNeeded()
        }
    }

    override fun refreshHome(section: HomeSection) {
        if (section == HomeSection.Mine) {
            refreshMine()
            return
        }
        val serial = when (section) {
            HomeSection.Recommend -> ++recommendRefreshSerial
            HomeSection.Music -> ++exploreRefreshSerial
            HomeSection.Mine -> error("mine is loaded separately")
        }
        fun current(): Boolean = when (section) {
            HomeSection.Recommend -> serial == recommendRefreshSerial
            HomeSection.Music -> serial == exploreRefreshSerial
            HomeSection.Mine -> false
        }
        scope.launch {
            if (current()) publishLoading(if (section == HomeSection.Recommend) "正在加载推荐" else "正在加载探索")
            val result = runCatching {
                val catalog = providerCatalog.uiState.value
                val category = if (section == HomeSection.Recommend) ProviderFeatureCategory.Recommend else ProviderFeatureCategory.Music
                val selectedIds = selectedProviderIdsFor(
                    if (section == HomeSection.Recommend) ProviderDisplaySection.Recommend else ProviderDisplaySection.Explore,
                    catalog,
                )
                val currentSections = if (section == HomeSection.Recommend) uiState.value.recommendSections else uiState.value.exploreSections
                loadSectionsIncrementally(
                    features = catalog.features.filter { it.category == category && it.providerId in selectedIds },
                    currentSections = currentSections,
                    deferFeature = ProviderFeature::isDeferredOnHome,
                ) { sections ->
                    if (!current()) return@loadSectionsIncrementally
                    mutableUiState.value = if (section == HomeSection.Recommend) {
                        uiState.value.copy(recommendSections = sections)
                    } else {
                        uiState.value.copy(exploreSections = sections)
                    }
                }
            }
            if (current()) {
                result.onSuccess { sections ->
                    finishLoading(if (sections.isEmpty()) "暂无内容" else "已更新")
                }.onFailure(::publishError)
            }
        }
    }

    override fun refreshMine() {
        when (uiState.value.mineSection.normalizeMineSection()) {
            MineSection.Playlists, MineSection.Songs -> refreshMinePlaylists()
            MineSection.Artists, MineSection.Albums -> refreshMineContent()
            MineSection.LocalMusic -> localMusic.refresh()
        }
    }

    override fun openFeature(feature: ProviderFeature) = providerDetails.feature.open(feature)

    override fun openPlaylist(playlist: ProviderPlaylist, category: ProviderFeatureCategory?) =
        providerDetails.playlist.open(playlist, category)

    override fun openMediaItem(item: ProviderMediaItem) = providerDetails.mediaItem.open(item)

    override fun openVideo(video: ProviderVideo) = providerDetails.video.open(video)

    override fun playFeature(section: ProviderContentSection, index: Int) {
        if (index !in section.tracks.indices) return
        playbackQueue.playFeatureTracks(section.tracks, index, section.feature)
    }

    override fun playAllFeature(section: ProviderContentSection) {
        val feature = section.feature
        if (feature.isDynamicQueueFeature()) {
            if (section.tracks.isNotEmpty()) {
                playbackQueue.playFeatureTracks(section.tracks, 0, feature)
            } else {
                loadDynamicFeatureAndPlay(feature)
            }
            return
        }
        if (section.tracks.isEmpty()) return
        if (!section.hasMore) {
            playbackQueue.playFeatureTracks(section.tracks, 0, feature)
            return
        }
        scope.launch {
            publishLoading("正在加载${feature.title}")
            runCatching {
                loadAllHomeFeatureTracks(section) { pageFeature, offset ->
                    withTimeout(HOME_PROVIDER_TIMEOUT_MS) {
                        providerRepository.loadFeaturePage(pageFeature, offset)
                    }
                }
            }.onSuccess { tracks ->
                if (tracks.isEmpty()) {
                    finishLoading("暂无可播放歌曲")
                } else {
                    playbackQueue.playFeatureTracks(tracks, 0, feature)
                    finishLoading("已加载全部歌曲")
                }
            }.onFailure(::publishError)
        }
    }

    private fun loadDynamicFeatureAndPlay(feature: ProviderFeature) {
        scope.launch {
            publishLoading("正在加载${feature.title}")
            runCatching {
                withTimeout(HOME_PROVIDER_TIMEOUT_MS) {
                    providerRepository.loadFeaturePage(feature, offset = 0)
                }
            }.onSuccess { loadedSection ->
                updateHomeFeatureSection(loadedSection)
                when {
                    !loadedSection.errorMessage.isNullOrBlank() ->
                        finishLoading(loadedSection.errorMessage.orEmpty(), asError = true)
                    loadedSection.tracks.isEmpty() -> finishLoading("暂无可播放歌曲")
                    else -> {
                        playbackQueue.playFeatureTracks(loadedSection.tracks, 0, feature)
                        finishLoading("正在播放${feature.title}")
                    }
                }
            }.onFailure(::publishError)
        }
    }

    private fun updateHomeFeatureSection(section: ProviderContentSection) {
        val current = uiState.value
        fun replace(sections: List<ProviderContentSection>): List<ProviderContentSection> =
            sections.map { existing -> if (existing.feature.id == section.feature.id) section else existing }
        mutableUiState.value = current.copy(
            recommendSections = replace(current.recommendSections),
            exploreSections = replace(current.exploreSections),
            mineSections = replace(current.mineSections),
            minePlaylistSections = replace(current.minePlaylistSections),
            mineFavoritePlaylistSections = replace(current.mineFavoritePlaylistSections),
        )
    }

    override fun creatablePlaylistProviders(): List<ProviderInfo> {
        val catalog = providerCatalog.uiState.value
        return catalog.providers.filter { provider ->
            catalog.sessions.authStates[provider.providerId]?.isLoggedIn == true &&
                catalog.capabilities[provider.providerId]?.canCreatePlaylist == true
        }
    }

    override fun createProviderPlaylist(providerId: String, name: String) {
        val normalized = name.trim()
        if (normalized.isBlank()) return
        scope.launch {
            publishLoading("正在新建歌单")
            runCatching { providerRepository.createPlaylist(providerId, normalized) }
                .onSuccess { result ->
                    if (result.success) {
                        finishLoading(result.message.ifBlank { "歌单已新建" })
                        refreshMinePlaylists()
                    } else {
                        finishLoading(result.message.ifBlank { "新建歌单失败" }, asError = true)
                    }
                }
                .onFailure(::publishError)
        }
    }

    override fun categoryForMinePlaylist(playlist: ProviderPlaylist): ProviderFeatureCategory =
        if (uiState.value.mineFavoritePlaylistSections.any { section ->
                section.playlists.any { it.homePlaybackStatsKey() == playlist.homePlaybackStatsKey() }
            }
        ) ProviderFeatureCategory.MineFavoritePlaylists else ProviderFeatureCategory.MinePlaylists

    private fun refreshMineIfNeeded() {
        when (uiState.value.mineSection.normalizeMineSection()) {
            MineSection.Playlists, MineSection.Songs -> if (
                uiState.value.minePlaylistSections.isEmpty() && uiState.value.mineFavoritePlaylistSections.isEmpty()
            ) refreshMinePlaylists()
            MineSection.Artists, MineSection.Albums -> if (uiState.value.mineSections.isEmpty()) refreshMineContent()
            MineSection.LocalMusic -> localMusic.ensure()
        }
    }

    private fun refreshMinePlaylists() {
        val serial = ++minePlaylistRefreshSerial
        scope.launch {
            publishLoading("正在加载我的歌单")
            val result = runCatching {
                val catalog = providerCatalog.uiState.value
                val selectedIds = selectedProviderIdsFor(ProviderDisplaySection.Mine, catalog)
                val user = loadSectionsIncrementally(
                    features = catalog.features.filter {
                        it.category == ProviderFeatureCategory.MinePlaylists && it.providerId in selectedIds
                    },
                    currentSections = uiState.value.minePlaylistSections,
                ) { sections ->
                    if (serial == minePlaylistRefreshSerial) {
                        mutableUiState.value = uiState.value.copy(minePlaylistSections = sections)
                    }
                }
                val favorite = loadSectionsIncrementally(
                    features = catalog.features.filter {
                        it.category == ProviderFeatureCategory.MineFavoritePlaylists && it.providerId in selectedIds
                    },
                    currentSections = uiState.value.mineFavoritePlaylistSections,
                ) { sections ->
                    if (serial == minePlaylistRefreshSerial) {
                        mutableUiState.value = uiState.value.copy(mineFavoritePlaylistSections = sections)
                    }
                }
                localPlaylist.refresh()
                user to favorite
            }
            if (serial == minePlaylistRefreshSerial) {
                result.onSuccess { (user, favorite) ->
                    finishLoading(if (user.isEmpty() && favorite.isEmpty()) "歌单暂无内容" else "歌单已更新")
                }.onFailure(::publishError)
            }
        }
    }

    private fun refreshMineContent() {
        val serial = ++mineContentRefreshSerial
        scope.launch {
            publishLoading("正在加载我的内容")
            val result = runCatching {
                val catalog = providerCatalog.uiState.value
                val selectedIds = selectedProviderIdsFor(ProviderDisplaySection.Mine, catalog)
                loadSectionsIncrementally(
                    features = catalog.features.filter {
                        it.category == ProviderFeatureCategory.Mine && it.providerId in selectedIds
                    },
                    currentSections = uiState.value.mineSections,
                ) { sections ->
                    if (serial == mineContentRefreshSerial) {
                        mutableUiState.value = uiState.value.copy(mineSections = sections)
                    }
                }
            }
            if (serial == mineContentRefreshSerial) {
                result.onSuccess { sections ->
                    finishLoading(if (sections.isEmpty()) "我的内容暂无内容" else "我的内容已更新")
                }.onFailure(::publishError)
            }
        }
    }

    private suspend fun loadSectionsIncrementally(
        features: List<ProviderFeature>,
        currentSections: List<ProviderContentSection>,
        deferFeature: (ProviderFeature) -> Boolean = { false },
        onUpdate: (List<ProviderContentSection>) -> Unit,
    ): List<ProviderContentSection> {
        val catalog = providerCatalog.uiState.value
        val featureIds = features.mapTo(mutableSetOf()) { it.id }
        var sections = sortSections(currentSections.filter { it.feature.id in featureIds }, catalog)
        val loadingFeatures = mutableListOf<ProviderFeature>()
        features.forEach { feature ->
            val immediate = when {
                feature.requiresLogin && catalog.sessions.authStates[feature.providerId]?.isLoggedIn != true ->
                    ProviderContentSection(feature = feature, isLoginRequired = true)
                deferFeature(feature) -> ProviderContentSection(feature = feature)
                else -> null
            }
            if (immediate == null) loadingFeatures += feature else sections = mergeSection(sections, immediate, catalog)
        }
        onUpdate(sections)
        if (loadingFeatures.isEmpty()) return sections

        val updates = Channel<ProviderContentSection>(Channel.UNLIMITED)
        loadingFeatures.forEach { feature ->
            scope.launch {
                val loadedSection = runCatching {
                    withTimeout(HOME_PROVIDER_TIMEOUT_MS) { providerRepository.loadFeaturePage(feature, offset = 0) }
                }.getOrElse { throwable ->
                    ProviderContentSection(
                        feature = feature,
                        errorMessage = providerHomeError(throwable, feature.providerId),
                    )
                }
                updates.send(loadedSection)
            }
        }
        repeat(loadingFeatures.size) {
            sections = mergeSection(sections, updates.receive(), catalog)
            onUpdate(sections)
        }
        updates.close()
        return sections
    }

    private fun mergeSection(
        sections: List<ProviderContentSection>,
        section: ProviderContentSection,
        catalog: ProviderCatalogUiState,
    ): List<ProviderContentSection> =
        sortSections(sections.filterNot { it.feature.id == section.feature.id } + section, catalog)

    private fun sortSections(
        sections: List<ProviderContentSection>,
        catalog: ProviderCatalogUiState,
    ): List<ProviderContentSection> {
        val providerOrder = catalog.providers.mapIndexed { index, provider -> provider.providerId to index }.toMap()
        val featureOrder = catalog.features.mapIndexed { index, feature -> feature.id to index }.toMap()
        val sorted = sections.sortedWith(
            compareBy<ProviderContentSection> { providerOrder[it.feature.providerId] ?: Int.MAX_VALUE }
                .thenBy { featureOrder[it.feature.id] ?: Int.MAX_VALUE }
                .thenBy { it.feature.id }
        )
        val contentTypes = sorted.map { it.feature.contentType }.distinct()
        return contentTypes.flatMap { type -> sorted.filter { it.feature.contentType == type } }
    }

    private fun selectedProviderIdsFor(
        section: ProviderDisplaySection,
        catalog: ProviderCatalogUiState,
    ): Set<String> {
        val configured = when (section) {
            ProviderDisplaySection.Search -> catalog.searchProviderIds
            ProviderDisplaySection.Recommend -> catalog.recommendProviderIds
            ProviderDisplaySection.Explore -> catalog.exploreProviderIds
            ProviderDisplaySection.Mine -> catalog.mineProviderIds
            ProviderDisplaySection.Replace -> catalog.replacementProviderIds
        }
        return if (configured.isEmpty() && section != ProviderDisplaySection.Replace) {
            catalog.availableProviders.mapTo(linkedSetOf()) { it.providerId }.intersect(catalog.enabledProviderIds)
        } else {
            configured.intersect(catalog.enabledProviderIds)
        }
    }

    private fun publishLoading(message: String) {
        mutableUiState.value = uiState.value.copy(isLoading = true, message = message, errorMessage = null)
    }

    private fun finishLoading(message: String, asError: Boolean = false) {
        mutableUiState.value = uiState.value.copy(
            isLoading = false,
            message = message,
            errorMessage = message.takeIf { asError },
        )
    }

    private fun publishError(throwable: Throwable) {
        val message = providerHomeError(throwable)
        mutableUiState.value = uiState.value.copy(isLoading = false, message = message, errorMessage = message)
    }
}

internal suspend fun loadAllHomeFeatureTracks(
    initial: ProviderContentSection,
    loadPage: suspend (ProviderFeature, Int) -> ProviderContentSection,
): List<MusicTrack> {
    val tracks = initial.tracks.toMutableList()
    val seenIds = tracks.mapTo(mutableSetOf()) { it.id }
    var hasMore = initial.hasMore
    var nextOffset = initial.nextOffset.takeIf { it > 0 } ?: initial.tracks.size

    while (hasMore) {
        val requestedOffset = nextOffset
        val page = loadPage(initial.feature, requestedOffset)
        page.tracks.forEach { track ->
            if (seenIds.add(track.id)) tracks += track
        }
        hasMore = page.hasMore
        if (!hasMore) break

        val candidateOffset = page.nextOffset.takeIf { it > requestedOffset }
            ?: (requestedOffset + page.tracks.size)
        if (candidateOffset <= requestedOffset || page.tracks.isEmpty()) break
        nextOffset = candidateOffset
    }
    return tracks
}

private fun MineSection.normalizeMineSection(): MineSection =
    if (this == MineSection.Songs) MineSection.Playlists else this

private fun PlaylistFilter.normalizePlaylistFilter(): PlaylistFilter =
    if (this == PlaylistFilter.All) PlaylistFilter.UserPlaylists else this

private fun ProviderFeature.isDeferredOnHome(): Boolean {
    val deferredMusic = category == ProviderFeatureCategory.Music &&
        (contentType == ProviderContentType.Songs || contentType == ProviderContentType.Videos || isBilibiliWeeklyMustWatch())
    val deferredRecommend = category == ProviderFeatureCategory.Recommend &&
        (id.endsWith("_daily_songs") || isDynamicQueueFeature() || isBilibiliRecommendedVideos())
    return deferredMusic || deferredRecommend
}

private fun ProviderPlaylist.homePlaybackStatsKey(): String =
    "$providerId$HOME_PLAYLIST_STATS_KEY_SEPARATOR$id"

private fun providerHomeError(throwable: Throwable, providerId: String? = null): String =
    throwable.providerFailureOrNull(providerId)?.userMessage
        ?: throwable.message
        ?: throwable::class.simpleName.orEmpty().ifBlank { "加载失败" }
